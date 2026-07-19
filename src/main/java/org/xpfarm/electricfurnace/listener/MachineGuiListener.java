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
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.Plugin;
import org.xpfarm.electricfurnace.alloy.AlloyRegistry;
import org.xpfarm.electricfurnace.config.EfConfig;
import org.xpfarm.electricfurnace.gui.FurnaceGui;
import org.xpfarm.electricfurnace.gui.GuiLayout;

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
 * <p><b>Shift-click into the GUI is a special case.</b> When a player shift-clicks an
 * item in their own inventory, Bukkit's {@code MOVE_TO_OTHER_INVENTORY} handling picks
 * the destination slot in the top inventory internally -- {@link
 * InventoryClickEvent#getSlot()} reports the <em>source</em> slot in the player's own
 * inventory, not where the item lands. That destination-picking algorithm will use the
 * output slot if it is the only empty top slot at the time, which would let an item
 * slip in through a path this class cannot inspect. Rather than reimplement Bukkit's
 * fill order to predict the destination, shift-click-in is uniformly disallowed;
 * players can still drag or plain-click items into the input/fuel slots one at a
 * time.
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

        Inventory clicked = event.getClickedInventory();
        if (clicked == null) {
            // Clicked outside any inventory (e.g. dropping the cursor item into the
            // world) -- nothing of ours is touched.
            return;
        }

        boolean clickedTop = clicked.equals(top);
        boolean cancel = clickedTop
                ? shouldCancel(GuiLayout.roleOf(event.getSlot()), event.getAction())
                : event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY;

        if (cancel) {
            event.setCancelled(true);
            return;
        }

        // A plain click confined to the player's own inventory never changes this
        // GUI's contents -- only schedule a processing attempt when the GUI itself
        // was the clicked inventory.
        if (clickedTop && event.getWhoClicked() instanceof Player player) {
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

    @EventHandler(ignoreCancelled = true)
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
            if (!player.isOnline() || !FurnaceGui.isFurnaceGui(player.getOpenInventory().getTopInventory())) {
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
