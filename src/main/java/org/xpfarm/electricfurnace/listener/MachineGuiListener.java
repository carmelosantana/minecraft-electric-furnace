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
import org.xpfarm.electricfurnace.alloy.AlloyRegistry;
import org.xpfarm.electricfurnace.config.EfConfig;
import org.xpfarm.electricfurnace.gui.FurnaceGui;
import org.xpfarm.electricfurnace.gui.GuiLayout;
import org.xpfarm.electricfurnace.item.MetalClassifier;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Guards every way an item can enter or leave an Electric Furnace GUI slot, and
 * returns items to the player whenever the GUI closes.
 *
 * <p><b>The slot guard.</b> Filler and the status indicator are never interactive --
 * any click on them is cancelled outright. The output slot may be taken from but
 * never placed into. This is enforced through {@link #shouldCancel(GuiLayout.SlotRole,
 * boolean, boolean)}, a pure function over the clicked slot's role and whether the
 * click places into / takes from that slot -- covering plain clicks, shift-clicks
 * <em>out</em> of the GUI, number-key hotbar swaps, and double-click collection, all
 * of which route through {@link InventoryClickEvent#getAction()}.
 *
 * <p><b>Shift-click into the GUI is routed manually.</b> When a player shift-clicks an
 * item in their own inventory, Bukkit's {@code MOVE_TO_OTHER_INVENTORY} handling picks
 * the destination slot in the top inventory internally -- {@link
 * InventoryClickEvent#getSlot()} reports the <em>source</em> slot in the player's own
 * inventory, not where the item lands -- and that algorithm will use the output slot if
 * it is the only empty top slot at the time. So Bukkit's move is always cancelled.
 * Rather than stop there, {@link #handleShiftClickIn} performs the move itself:
 * redstone to the fuel slot, recyclable items to the input slots, anything else
 * nowhere. This keeps shift-click working -- filling the inputs is one click instead
 * of five, which matters on Bedrock, a platform this plugin explicitly supports --
 * while making the destination something this class chooses explicitly and can
 * guarantee is never OUTPUT, INDICATOR, or FILLER.
 *
 * <p><b>Drags</b> ({@link InventoryDragEvent}) can span multiple slots, including
 * guarded ones, in a single event -- {@link #shouldCancelDrag} cancels the whole drag
 * if any touched top slot is FILLER, INDICATOR, or OUTPUT.
 *
 * <p><b>Never destroy items.</b> On {@link InventoryCloseEvent} -- for any reason,
 * including disconnect (Paper fires this event on player disconnect too) -- every
 * input, fuel, and output item is returned to the player via
 * {@link FurnaceGui#returnAllItems}, dropping at their feet if their inventory is
 * full.
 */
public final class MachineGuiListener implements Listener {

    private final Plugin plugin;
    private final Supplier<EfConfig> configSupplier;
    private final Supplier<AlloyRegistry> alloysSupplier;

    /**
     * @param plugin         owning plugin instance, used only to schedule the
     *                       post-click processing attempt on the next server tick
     * @param configSupplier supplies the live, possibly-reloaded configuration
     * @param alloysSupplier supplies the live, possibly-reloaded alloy registry
     */
    public MachineGuiListener(Plugin plugin, Supplier<EfConfig> configSupplier, Supplier<AlloyRegistry> alloysSupplier) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
        this.alloysSupplier = Objects.requireNonNull(alloysSupplier, "alloysSupplier");
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

        if (shouldCancel(GuiLayout.roleOf(event.getSlot()), event.getAction())) {
            event.setCancelled(true);
            return;
        }

        if (event.getWhoClicked() instanceof Player player) {
            scheduleProcess(player, top);
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
     * path that can reach OUTPUT, INDICATOR, or FILLER.
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

        int moved = switch (target) {
            case FUEL -> FurnaceGui.insertIntoFuel(top, moving);
            case INPUT -> FurnaceGui.insertIntoInputs(top, moving);
            case NONE -> 0;
        };

        if (moved <= 0) {
            return;
        }

        event.setCurrentItem(moving.getAmount() <= 0 ? null : moving);

        if (event.getWhoClicked() instanceof Player player) {
            scheduleProcess(player, top);
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

        if (event.getWhoClicked() instanceof Player player) {
            scheduleProcess(player, top);
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
        if (event.getPlayer() instanceof Player player) {
            FurnaceGui.returnAllItems(top, player);
        }
    }

    /**
     * Schedules one processing attempt for the next server tick -- a click's item
     * movement is applied by the server only after this event handler returns, so
     * reading slot contents synchronously here would see the pre-click state.
     */
    private void scheduleProcess(Player player, Inventory top) {
        FurnaceGui.blockOf(top).ifPresent(block -> Bukkit.getScheduler().runTask(plugin, () -> {
            // Guard on THIS machine's GUI still being open, not merely "some furnace
            // GUI". Between the click and this tick the player may have closed this GUI
            // and opened a different machine's; processing `top` then would run against
            // an inventory they are no longer viewing, using the wrong block's power.
            if (!player.isOnline()) {
                return;
            }
            boolean sameGuiStillOpen = FurnaceGui.blockOf(player.getOpenInventory().getTopInventory())
                    .filter(block::equals)
                    .isPresent();
            if (!sameGuiStillOpen) {
                return;
            }
            boolean powered = block.getBlockPower() > 0;
            FurnaceGui.tryProcess(top, configSupplier.get(), alloysSupplier.get(), powered);
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
     * FILLER, INDICATOR, or OUTPUT.
     */
    static boolean shouldCancelDrag(Set<GuiLayout.SlotRole> touchedTopRoles) {
        return touchedTopRoles.contains(GuiLayout.SlotRole.FILLER)
                || touchedTopRoles.contains(GuiLayout.SlotRole.INDICATOR)
                || touchedTopRoles.contains(GuiLayout.SlotRole.OUTPUT);
    }
}
