/*
 * ElectricFurnace - a redstone-powered smelter that recycles metal gear into ingots.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.electricfurnace.listener;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.xpfarm.electricfurnace.config.EfConfig;
import org.xpfarm.electricfurnace.gui.FurnaceGui;
import org.xpfarm.electricfurnace.gui.GuiLayout;
import org.xpfarm.electricfurnace.gui.SlotLock;
import org.xpfarm.electricfurnace.item.MetalClassifier;
import org.xpfarm.electricfurnace.machine.MachineRegistry;
import org.xpfarm.electricfurnace.machine.MachineState;
import org.xpfarm.electricfurnace.machine.MachineStore;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Guards every way an item can enter or leave an Electric Furnace GUI slot, and keeps
 * the machine's persisted state in sync with whatever a viewer does to it.
 *
 * <p><b>The slot guard.</b> Filler and the status indicator are never interactive --
 * any click on them is cancelled outright. The output slot may be taken from but
 * never placed into. This is enforced through {@link #shouldCancel(GuiLayout.SlotRole,
 * boolean, boolean)}, a pure function over the clicked slot's role and whether the
 * click places into / takes from that slot -- covering plain clicks, shift-clicks
 * <em>out</em> of the GUI, number-key hotbar swaps, and double-click collection, all
 * of which route through {@link InventoryClickEvent#getAction()}.
 *
 * <p><b>The run lock.</b> On top of the slot guard, {@link SlotLock#allows} is
 * consulted with {@code running = !state.isIdle()}: while a run is in progress, input
 * slots reject both insertion and removal, and the fuel slot accepts insertion but not
 * removal. {@link #shouldCancelForLock} and {@link #shouldCancelDragForLock} apply
 * this the same way {@link #shouldCancel} applies the structural slot guard, so a
 * click or drag is cancelled if either guard objects.
 *
 * <p><b>Shift-click into the GUI is routed manually.</b> When a player shift-clicks an
 * item in their own inventory, Bukkit's {@code MOVE_TO_OTHER_INVENTORY} handling picks
 * the destination slot in the top inventory internally -- {@link
 * InventoryClickEvent#getSlot()} reports the <em>source</em> slot in the player's own
 * inventory, not where the item lands -- and that algorithm will use the output slot if
 * it is the only empty top slot at the time. So Bukkit's move is always cancelled.
 * Rather than stop there, {@link #handleShiftClickIn} performs the move itself:
 * redstone to the fuel slot, recyclable items to the input slots (unless the run lock
 * currently rejects insertion there), anything else nowhere.
 *
 * <p><b>Drags</b> ({@link InventoryDragEvent}) can span multiple slots, including
 * guarded ones, in a single event -- {@link #shouldCancelDrag} cancels the whole drag
 * if any touched top slot is FILLER, INDICATOR, or OUTPUT.
 *
 * <p><b>Never destroy items.</b> A click or drag that is allowed to proceed schedules
 * {@link #scheduleSync} for the next server tick, which folds the inventory's
 * resulting contents into the machine's {@link MachineState} via
 * {@link FurnaceGui#syncToState} and redraws the status indicator -- deferred one tick
 * because Bukkit applies a click's own item movement only after this event handler
 * returns, so reading slot contents synchronously here would still see the pre-click
 * state. {@link InventoryCloseEvent} -- for any reason, including disconnect -- also
 * syncs immediately: the items belong to the machine now, not to the closing player,
 * so nothing is returned here (see {@link FurnaceGui#returnAllItems}'s narrower
 * remaining callers).
 */
public final class MachineGuiListener implements Listener {

    private final Plugin plugin;
    private final MachineStore store;
    private final MachineRegistry machines;
    private final Supplier<EfConfig> configSupplier;

    /**
     * @param plugin         owning plugin instance, used only to schedule the
     *                       post-click state sync on the next server tick
     * @param store          the block-PDC-backed machine state store; every click/drag
     *                       is folded back into the block's live {@link MachineState}
     *                       here, and the run lock reads {@link MachineState#isIdle()}
     *                       from it
     * @param machines       the machine location registry, consulted before a deferred
     *                       sync runs so a block broken between the click and the next
     *                       tick is never written back into
     * @param configSupplier supplies the live, possibly-reloaded configuration
     */
    public MachineGuiListener(Plugin plugin, MachineStore store, MachineRegistry machines,
            Supplier<EfConfig> configSupplier) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.store = Objects.requireNonNull(store, "store");
        this.machines = Objects.requireNonNull(machines, "machines");
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
    }

    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!FurnaceGui.isFurnaceGui(top)) {
            return;
        }

        // View-wide actions must be judged before the clicked-inventory split, because
        // they do not act on the clicked slot alone. See shouldCancelViewWide.
        if (shouldCancelViewWide(event.getAction())) {
            event.setCancelled(true);
            return;
        }

        Inventory clicked = event.getClickedInventory();
        if (clicked == null) {
            // Clicked outside any inventory (e.g. dropping the cursor item into the
            // world) -- nothing of ours is touched.
            return;
        }

        boolean clickedTop = clicked.equals(top);

        if (!clickedTop) {
            if (event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
                handleShiftClickIn(event, top);
            }
            // Any other click confined to the player's own inventory never changes
            // this GUI's contents.
            return;
        }

        GuiLayout.SlotRole role = GuiLayout.roleOf(event.getSlot());
        if (shouldCancel(role, event.getAction())) {
            event.setCancelled(true);
            return;
        }

        ClickEffect effect = classify(event.getAction());
        boolean running = isRunning(top);
        if (shouldCancelForLock(role, effect, running)) {
            event.setCancelled(true);
            return;
        }

        if (event.getWhoClicked() instanceof Player player) {
            scheduleSync(player, top);
        }
    }

    /**
     * Handles a shift-click from the player's own inventory into the GUI by cancelling
     * Bukkit's move and performing the routing ourselves.
     *
     * <p>Bukkit's own {@code MOVE_TO_OTHER_INVENTORY} picks the destination slot
     * internally and would happily use the output slot, so it can never be allowed to
     * run. Blanket-cancelling it is safe but costs a Bedrock player 5+ individual
     * clicks to fill the inputs, on a platform this plugin explicitly supports. Routing
     * manually gives both: the destination is chosen by {@link #shiftTargetOf} (a pure,
     * exhaustively-tested decision) and can only ever be the fuel slot or the input
     * slots -- {@link FurnaceGui#insertIntoFuel} and
     * {@link FurnaceGui#insertIntoInputs} address those slots by name and have no code
     * path that can reach OUTPUT, INDICATOR, or FILLER. The input slot additionally
     * respects the run lock: while a run is in progress, nothing is routed there.
     *
     * <p>The moving stack is cloned before any insertion, so the slot's real item is
     * never aliased into the GUI; it is written back exactly once, with the remainder.
     */
    private void handleShiftClickIn(InventoryClickEvent event, Inventory top) {
        event.setCancelled(true);

        ItemStack current = event.getCurrentItem();
        if (current == null || current.getType() == Material.AIR) {
            return;
        }

        ItemStack moving = current.clone();
        ShiftTarget target = shiftTargetOf(
                moving.getType() == Material.REDSTONE,
                MetalClassifier.classify(moving, configSupplier.get().recycling()).isPresent());

        boolean running = isRunning(top);
        int moved = switch (target) {
            case FUEL -> FurnaceGui.insertIntoFuel(top, moving);
            case INPUT -> SlotLock.allows(GuiLayout.SlotRole.INPUT, SlotLock.Action.INSERT, running)
                    ? FurnaceGui.insertIntoInputs(top, moving) : 0;
            case NONE -> 0;
        };

        if (moved <= 0) {
            return;
        }

        event.setCurrentItem(moving.getAmount() <= 0 ? null : moving);

        if (event.getWhoClicked() instanceof Player player) {
            scheduleSync(player, top);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!FurnaceGui.isFurnaceGui(top)) {
            return;
        }

        Set<GuiLayout.SlotRole> touchedTopRoles = new HashSet<>();
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot < GuiLayout.SIZE) {
                touchedTopRoles.add(GuiLayout.roleOf(rawSlot));
            }
        }

        if (shouldCancelDrag(touchedTopRoles)) {
            event.setCancelled(true);
            return;
        }

        if (shouldCancelDragForLock(touchedTopRoles, isRunning(top))) {
            event.setCancelled(true);
            return;
        }

        if (event.getWhoClicked() instanceof Player player) {
            scheduleSync(player, top);
        }
    }

    // No ignoreCancelled: InventoryCloseEvent is not Cancellable, so the flag would be
    // meaningless here.
    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!FurnaceGui.isFurnaceGui(top)) {
            return;
        }
        // The items belong to the machine now, not the closing player -- fold whatever
        // is currently in the inventory into its MachineState. Done synchronously
        // (not deferred a tick, unlike scheduleSync) because a close is not a click:
        // there is no pending Bukkit-internal item movement still to apply, so the
        // inventory's contents are already final.
        FurnaceGui.blockOf(top).ifPresent(block -> FurnaceGui.syncToState(top, store.get(block)));
    }

    /** Whether the machine backing {@code top} currently has a run in progress. */
    private boolean isRunning(Inventory top) {
        return FurnaceGui.blockOf(top).map(store::get).map(state -> !state.isIdle()).orElse(false);
    }

    /**
     * Schedules a state sync and indicator refresh for the next server tick -- a
     * click's item movement is applied by the server only after this event handler
     * returns, so reading slot contents synchronously here would see the pre-click
     * state.
     */
    private void scheduleSync(Player player, Inventory top) {
        FurnaceGui.blockOf(top).ifPresent(block -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!machines.isMachine(block)) {
                // The block stopped being a machine between the click and this tick
                // (e.g. broken by another player). Its store entry, if any, has already
                // been handled by that path -- writing into it now would resurrect
                // stale contents under whatever is now at this location.
                return;
            }
            MachineState state = store.get(block);
            FurnaceGui.syncToState(top, state);
            boolean powered = block.getBlockPower() > 0;
            EfConfig config = configSupplier.get();
            FurnaceGui.refreshIndicator(top, config, powered, !state.isIdle(),
                    state.progressTicks(), config.machine().smeltTicks());
        }));
    }

    // =================================================================================
    // Pure guard decisions -- no org.bukkit type beyond the plain InventoryAction enum,
    // which (like Material in MetalClassifierTest) needs no running server to reference.
    // =================================================================================

    /** What effect a click's {@link InventoryAction} has on the clicked slot itself. */
    record ClickEffect(boolean isPlace, boolean isTake) {
    }

    /**
     * Maps a raw {@link InventoryAction} to whether it places into and/or takes from
     * the clicked slot. Unknown actions and the newer bundle interactions (whose exact
     * item-movement direction is not worth risking a mistake on) are conservatively
     * treated as capable of both, so the guard still protects FILLER/INDICATOR/OUTPUT
     * regardless of which way they actually move items.
     */
    static ClickEffect classify(InventoryAction action) {
        return switch (action) {
            case NOTHING, DROP_ALL_CURSOR, DROP_ONE_CURSOR, CLONE_STACK -> new ClickEffect(false, false);
            case PICKUP_ALL, PICKUP_SOME, PICKUP_HALF, PICKUP_ONE,
                    DROP_ALL_SLOT, DROP_ONE_SLOT, MOVE_TO_OTHER_INVENTORY, COLLECT_TO_CURSOR ->
                    new ClickEffect(false, true);
            case PLACE_ALL, PLACE_SOME, PLACE_ONE -> new ClickEffect(true, false);
            case SWAP_WITH_CURSOR, HOTBAR_MOVE_AND_READD, HOTBAR_SWAP -> new ClickEffect(true, true);
            case UNKNOWN, PICKUP_FROM_BUNDLE, PICKUP_ALL_INTO_BUNDLE, PICKUP_SOME_INTO_BUNDLE,
                    PLACE_FROM_BUNDLE, PLACE_ALL_INTO_BUNDLE, PLACE_SOME_INTO_BUNDLE ->
                    new ClickEffect(true, true);
        };
    }

    /**
     * The core slot-guard decision: given a slot's role and whether the click places
     * into / takes from it, should the click be cancelled?
     *
     * <ul>
     *   <li>FILLER, INDICATOR: always cancelled -- non-interactive, no exceptions.</li>
     *   <li>OUTPUT: cancelled only when the click would place something into it;
     *       taking is always allowed.</li>
     *   <li>INPUT, FUEL: never cancelled by this guard.</li>
     * </ul>
     *
     * <p>This is the structural guard only -- it knows nothing about whether a run is
     * in progress. {@link #shouldCancelForLock} applies that separately.
     */
    static boolean shouldCancel(GuiLayout.SlotRole role, boolean isPlace, boolean isTake) {
        return switch (role) {
            case FILLER, INDICATOR -> true;
            case OUTPUT -> isPlace;
            case INPUT, FUEL -> false;
        };
    }

    /** Composes {@link #classify} and {@link #shouldCancel(GuiLayout.SlotRole, boolean, boolean)}. */
    static boolean shouldCancel(GuiLayout.SlotRole role, InventoryAction action) {
        ClickEffect effect = classify(action);
        return shouldCancel(role, effect.isPlace(), effect.isTake());
    }

    /**
     * The run-lock guard: cancels a click that would insert into, or remove from, a
     * slot {@link SlotLock#allows} currently forbids for that action. Composes with
     * {@link #shouldCancel(GuiLayout.SlotRole, boolean, boolean)} by simple OR --
     * FILLER/INDICATOR/OUTPUT are already fully handled by the structural guard, and
     * {@link SlotLock#allows} agrees with it there regardless of {@code running}, so
     * this only ever adds a cancellation for INPUT (both directions, while running)
     * and FUEL (removal only, while running).
     */
    static boolean shouldCancelForLock(GuiLayout.SlotRole role, ClickEffect effect, boolean running) {
        if (effect.isPlace() && !SlotLock.allows(role, SlotLock.Action.INSERT, running)) {
            return true;
        }
        return effect.isTake() && !SlotLock.allows(role, SlotLock.Action.REMOVE, running);
    }

    /**
     * Whether an action must be cancelled purely because the top inventory is a furnace
     * GUI -- irrespective of which inventory was clicked or which slot.
     *
     * <p>{@code COLLECT_TO_CURSOR} (double-click collect) is the whole reason this
     * exists. It is not a single-slot action: it vacuums every matching stack in the
     * <em>entire view</em>, including top-inventory slots the player never clicked. The
     * per-slot guard cannot see it, because the clicked slot may legitimately be one of
     * the player's own. Classified take-only, it therefore passed the FILLER/INDICATOR
     * guard entirely, and this was directly exploitable: hold a gray stained glass pane,
     * open the GUI, double-click it in your own inventory, and collect up to 20 filler
     * panes -- which {@code FurnaceGui.open} then regenerates on the next open, minting
     * panes indefinitely. The same trick against the fuel slot vacuumed the status
     * indicator, which {@code refreshIndicator} re-mints, yielding unlimited
     * redstone/lever/redstone torch. Cancelled unconditionally; a double-click collect
     * inside a furnace GUI view does nothing at all.
     */
    static boolean shouldCancelViewWide(InventoryAction action) {
        return action == InventoryAction.COLLECT_TO_CURSOR;
    }

    /** Where a shift-clicked stack from the player's inventory should be routed. */
    enum ShiftTarget {
        /** Into the redstone fuel slot. */
        FUEL,
        /** Into the recycler input slots. */
        INPUT,
        /** Nowhere -- the item belongs in neither; the shift-click is a no-op. */
        NONE
    }

    /**
     * The pure shift-click routing decision. Redstone is fuel first and foremost: it is
     * never a recycler input, so the fuel slot always wins when both facts hold, and a
     * player shift-clicking redstone always gets the behavior they meant. Anything
     * neither burnable nor recyclable routes nowhere rather than being forced into a
     * slot it does not belong in.
     */
    static ShiftTarget shiftTargetOf(boolean isRedstone, boolean isRecyclable) {
        if (isRedstone) {
            return ShiftTarget.FUEL;
        }
        if (isRecyclable) {
            return ShiftTarget.INPUT;
        }
        return ShiftTarget.NONE;
    }

    /**
     * The drag guard: a single {@link InventoryDragEvent} can distribute the cursor
     * item across several slots in one action, including guarded ones. A drag always
     * places, so the whole event is cancelled if any touched top-inventory slot is
     * FILLER, INDICATOR, or OUTPUT. As with {@link #shouldCancel(GuiLayout.SlotRole,
     * boolean, boolean)}, this is the structural guard only.
     */
    static boolean shouldCancelDrag(Set<GuiLayout.SlotRole> touchedTopRoles) {
        return touchedTopRoles.contains(GuiLayout.SlotRole.FILLER)
                || touchedTopRoles.contains(GuiLayout.SlotRole.INDICATOR)
                || touchedTopRoles.contains(GuiLayout.SlotRole.OUTPUT);
    }

    /**
     * The drag guard's run-lock counterpart: a drag always places, so it is cancelled
     * if {@link SlotLock#allows} forbids insertion into any touched role for the
     * current {@code running} state. Redundant with, and consistent with,
     * {@link #shouldCancelDrag} for FILLER/INDICATOR/OUTPUT; the only case this adds is
     * a drag touching an INPUT slot while a run is in progress.
     */
    static boolean shouldCancelDragForLock(Set<GuiLayout.SlotRole> touchedTopRoles, boolean running) {
        for (GuiLayout.SlotRole role : touchedTopRoles) {
            if (!SlotLock.allows(role, SlotLock.Action.INSERT, running)) {
                return true;
            }
        }
        return false;
    }
}
