/*
 * ElectricFurnace - a redstone-powered smelter that recycles metal gear into ingots.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.electricfurnace.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.xpfarm.electricfurnace.config.EfConfig;
import org.xpfarm.electricfurnace.machine.MachineRules;
import org.xpfarm.electricfurnace.machine.MachineState;
import org.xpfarm.electricfurnace.machine.MachineStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Owns the Electric Furnace's 27-slot custom {@link Inventory}: rendering the filler
 * panes and status indicator, and moving items safely in and out -- never destroying
 * them.
 *
 * <p><b>The GUI does not own the items; it does not even hold them.</b> A machine's
 * {@link MachineState} <em>is</em> the inventory's {@link InventoryHolder}, and the
 * inventory it owns is this machine's only item storage. {@link #open} hands that exact
 * instance to the player, so two players watching one machine are editing one object,
 * and so is {@code MachineTicker}. Nothing is copied anywhere and nothing needs syncing
 * back: a click writes the same slots the ticker reads and {@code MachineStateCodec}
 * encodes. A normal close therefore does nothing at all to the items.
 *
 * <p>This class contributes exactly two things to that inventory -- the filler panes and
 * the status indicator (see {@link #paintDecoration} and {@link #refreshIndicator}) --
 * neither of which is machine contents and neither of which is ever persisted.
 * {@link #returnAllItems} is reserved for the two cases where there is no machine left to
 * hold the items: {@link #closeForBlock} (the block is about to be broken) and
 * {@link #closeAll} (shutdown, and only when the state could not be persisted).
 *
 * <p>The status-indicator decision ({@link #indicatorStateOf}) is a pure function over
 * primitives -- no {@code org.bukkit} type -- so {@code FurnaceGuiTest} exercises every
 * combination with no running server. What counts as fuel and whether the output slot
 * can accept a result are machine rules, not view logic, and live in
 * {@link MachineRules}; recipe resolution and the run itself belong to
 * {@code MachineTicker}. This class renders their consequences and moves items; it
 * decides none of them.
 */
public final class FurnaceGui {

    private static final Logger LOGGER = Logger.getLogger("ElectricFurnace");

    private FurnaceGui() {
    }

    // =================================================================================
    // Pure decision logic
    // =================================================================================

    /** What the non-interactive status indicator (slot {@link GuiLayout#INDICATOR_SLOT}) should show. */
    public enum IndicatorState {
        /** Powered (or signal not required) and fuel present, but no run is currently advancing. */
        RUNNING,
        /** {@code machine.require-redstone-signal} is {@code true} and the machine is not currently powered. */
        NO_SIGNAL,
        /** Powered (or signal not required), but the fuel slot holds no redstone. */
        NO_FUEL,
        /** A run is currently advancing ({@code progressTicks > 0}). */
        SMELTING
    }

    /**
     * Decides the status indicator's state from plain booleans. Precedence, most
     * fundamental blocker first: {@code NO_SIGNAL > NO_FUEL > SMELTING > RUNNING}. A
     * signal or fuel problem is reported even mid-run, since {@code MachineTicker}
     * (not this method) is what decides whether a stalled run holds its progress.
     */
    public static IndicatorState indicatorStateOf(boolean powered, boolean requireSignal,
                                                  boolean hasFuel, boolean smelting) {
        boolean effectivePowered = !requireSignal || powered;
        if (!effectivePowered) {
            return IndicatorState.NO_SIGNAL;
        }
        if (!hasFuel) {
            return IndicatorState.NO_FUEL;
        }
        if (smelting) {
            return IndicatorState.SMELTING;
        }
        return IndicatorState.RUNNING;
    }

    // =================================================================================
    // Bukkit-facing glue
    // =================================================================================

    /** Whether {@code inventory} is an Electric Furnace GUI. */
    public static boolean isFurnaceGui(Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof MachineState;
    }

    /** The machine whose contents {@code inventory} holds, if it is a furnace GUI. */
    public static Optional<MachineState> stateOf(Inventory inventory) {
        if (inventory != null && inventory.getHolder() instanceof MachineState state) {
            return Optional.of(state);
        }
        return Optional.empty();
    }

    /** The block a given Electric Furnace GUI inventory belongs to, if it is one. */
    public static Optional<Block> blockOf(Inventory inventory) {
        return stateOf(inventory).map(MachineState::block);
    }

    /**
     * Opens {@code block}'s Electric Furnace GUI for {@code player}.
     *
     * <p>There is nothing to build and nothing to populate: {@code state} already owns
     * the inventory that holds its items, so this paints the decoration onto it and hands
     * that same instance over. A second viewer of the same machine is handed the same
     * object again -- which is why no viewer registry, and no scan of online players to
     * find an existing view, is needed to keep two viewers consistent.
     */
    public static Inventory open(Player player, Block block, EfConfig config, boolean powered, MachineState state) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(block, "block");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(state, "state");

        Inventory inventory = state.getInventory();
        paintDecoration(inventory);
        refreshIndicator(inventory, config, powered, !state.isIdle(), state.progressTicks(),
                config.machine().smeltTicks());

        player.openInventory(inventory);
        return inventory;
    }

    /**
     * Draws the non-interactive filler panes into every slot that has no functional role.
     *
     * <p>Idempotent, and deliberately called on every {@link #open} rather than once at
     * inventory creation: the panes are decoration, not contents, so they are never
     * persisted by {@code MachineStateCodec} and are therefore absent from a machine
     * freshly hydrated from disk. Painting them at open is what makes "not persisted"
     * invisible to a player.
     */
    private static void paintDecoration(Inventory inventory) {
        for (int slot = 0; slot < GuiLayout.SIZE; slot++) {
            if (GuiLayout.roleOf(slot) == GuiLayout.SlotRole.FILLER) {
                inventory.setItem(slot, buildFillerItem());
            }
        }
    }

    /**
     * Every currently-open Electric Furnace GUI, keyed by the block it belongs to, from
     * a single pass over the online players.
     *
     * <p>{@code MachineTicker} uses this to decide which machines have a viewer worth
     * repainting the status indicator for. {@link #open} does not need it -- a machine's
     * inventory is reachable directly from its {@link MachineState} -- so this is a
     * rendering optimisation only, never a correctness dependency. Two viewers of the
     * same machine share one {@code Inventory}, so the map has one entry per machine.
     */
    public static Map<Block, Inventory> openInventoriesByBlock() {
        Map<Block, Inventory> open = new HashMap<>();
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            Inventory top = viewer.getOpenInventory().getTopInventory();
            if (top != null && top.getHolder() instanceof MachineState state) {
                open.putIfAbsent(state.block(), top);
            }
        }
        return open;
    }

    /**
     * Recomputes and redraws the status indicator item from the inventory's current
     * fuel slot plus the caller-supplied power/progress facts.
     */
    public static void refreshIndicator(Inventory inventory, EfConfig config, boolean powered,
                                        boolean smelting, int progressTicks, int smeltTicks) {
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(config, "config");

        boolean hasFuel = hasFuel(inventory);
        IndicatorState state = indicatorStateOf(powered, config.machine().requireRedstoneSignal(), hasFuel, smelting);
        inventory.setItem(GuiLayout.INDICATOR_SLOT, indicatorItem(state, progressTicks, smeltTicks));
    }

    /** Whether the fuel slot holds redstone at all -- see {@link MachineRules#hasFuel}. */
    private static boolean hasFuel(Inventory inventory) {
        return MachineRules.hasFuel(inventory.getItem(GuiLayout.FUEL_SLOT));
    }

    // ---- Shift-click routing (see MachineGuiListener's shift-click note) ------------

    /**
     * How many items may move from a source stack into one destination slot.
     *
     * <p>Pure integer math, exhaustively unit tested: this is the arithmetic that keeps
     * manual shift-click routing from either duplicating items (moving more than the
     * source holds) or overflowing a slot (moving past {@code maxStackSize}).
     *
     * @param sourceAmount how many items the moving stack holds
     * @param destAmount   how many items the destination slot already holds ({@code 0}
     *                     for an empty slot)
     * @param maxStackSize the destination's maximum stack size
     * @return the number of items to move; never negative, never more than
     *         {@code sourceAmount}, and never enough to push the destination past
     *         {@code maxStackSize}
     */
    public static int transferAmount(int sourceAmount, int destAmount, int maxStackSize) {
        int room = maxStackSize - destAmount;
        if (room <= 0 || sourceAmount <= 0) {
            return 0;
        }
        return Math.min(sourceAmount, room);
    }

    /**
     * Moves as much of {@code source} as will fit into the fuel slot, mutating
     * {@code source}'s amount by however much moved.
     *
     * @return the number of items actually moved
     */
    public static int insertIntoFuel(Inventory inventory, ItemStack source) {
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(source, "source");
        return insertIntoSlot(inventory, GuiLayout.FUEL_SLOT, source);
    }

    /**
     * Moves as much of {@code source} as will fit into the input slots, merging into
     * matching stacks first and then filling empty slots, mutating {@code source}'s
     * amount by however much moved.
     *
     * @return the number of items actually moved
     */
    public static int insertIntoInputs(Inventory inventory, ItemStack source) {
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(source, "source");

        List<Integer> slots = new ArrayList<>(GuiLayout.INPUT_SLOTS);
        slots.sort(Integer::compareTo);

        int moved = 0;
        // Two passes, vanilla-style: top up matching stacks before claiming empty slots.
        for (int slot : slots) {
            ItemStack existing = inventory.getItem(slot);
            if (existing != null && existing.getType() != Material.AIR) {
                moved += insertIntoSlot(inventory, slot, source);
            }
        }
        for (int slot : slots) {
            ItemStack existing = inventory.getItem(slot);
            if (existing == null || existing.getType() == Material.AIR) {
                moved += insertIntoSlot(inventory, slot, source);
            }
        }
        return moved;
    }

    private static int insertIntoSlot(Inventory inventory, int slot, ItemStack source) {
        if (source.getAmount() <= 0) {
            return 0;
        }
        ItemStack existing = inventory.getItem(slot);

        if (existing == null || existing.getType() == Material.AIR) {
            int move = transferAmount(source.getAmount(), 0, source.getMaxStackSize());
            if (move <= 0) {
                return 0;
            }
            ItemStack placed = source.clone();
            placed.setAmount(move);
            inventory.setItem(slot, placed);
            source.setAmount(source.getAmount() - move);
            return move;
        }

        if (!existing.isSimilar(source)) {
            return 0;
        }
        int move = transferAmount(source.getAmount(), existing.getAmount(), existing.getMaxStackSize());
        if (move <= 0) {
            return 0;
        }
        existing.setAmount(existing.getAmount() + move);
        // Write the grown stack back explicitly. Mutating what getItem returned happens
        // to reach the real slot on CraftBukkit, which returns a wrapper around the live
        // stack, but the Bukkit API promises no such thing -- and this inventory is now
        // the machine's only copy of these items, so "correct by implementation accident"
        // is not good enough here. On an implementation that hands back a copy, the line
        // above alone would silently consume from the player's stack and add nothing.
        inventory.setItem(slot, existing);
        source.setAmount(source.getAmount() - move);
        return move;
    }

    // ---- Item-safety: returning contents, and force-closing viewers ----------------

    /**
     * Unconditionally returns every input, fuel, and output item currently in
     * {@code inventory} to {@code player}, dropping at the player's location whatever
     * does not fit in their inventory. Nothing is ever silently destroyed.
     *
     * <p>Reserved for the two cases where there is no machine state left to hold the
     * items: {@link #closeForBlock} (the block is being broken) and {@link #closeAll}
     * (shutdown, only when persisting failed). A normal close does not call this: the
     * items are already in the machine's own storage and simply stay there.
     *
     * <p><b>Gives before clearing, per slot -- do not reorder.</b> If {@link #giveOrDrop}
     * -&gt; {@code dropItemNaturally} throws, which it can during world teardown, the
     * item is still sitting in its slot when the exception propagates. Clearing first
     * would leave that item existing in zero places: not on the player, not dropped, and
     * no longer in the inventory.
     */
    public static void returnAllItems(Inventory inventory, Player player) {
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(player, "player");

        Set<Integer> slots = new HashSet<>(GuiLayout.INPUT_SLOTS);
        slots.add(GuiLayout.FUEL_SLOT);
        slots.add(GuiLayout.OUTPUT_SLOT);

        for (int slot : slots) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.getType() == Material.AIR) {
                continue;
            }
            giveOrDrop(player, item);
            inventory.setItem(slot, null);
        }
    }

    private static void giveOrDrop(Player player, ItemStack item) {
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
        for (ItemStack overflow : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), overflow);
        }
    }

    /**
     * Force-closes any online player currently viewing {@code block}'s Electric
     * Furnace GUI, returning their items directly first.
     *
     * <p>The block is about to stop existing, so there is no machine left for these
     * items to belong to -- unlike a normal close, which leaves them in the machine's own
     * storage, this returns them straight to the viewer. Items are returned <em>before</em> {@link Player#closeInventory()} is
     * called, exactly like {@link #closeAll} already does.
     *
     * <p><b>Does not depend on {@code InventoryCloseEvent}.</b> If any viewer's items
     * were returned, {@code block}'s state in {@code store} is cleared directly (via
     * {@link MachineStore#forget}) before this method returns, rather than trusting
     * {@code player.closeInventory()} to fire an event that reaches
     * {@code MachineGuiListener#onClose}. If that event were ever missed,
     * {@code MachineState} would still hold the items just handed to the viewer and
     * {@code MachineBlockListener}'s {@code dropStoreContents} would drop the same items
     * on the ground -- duplicating them.
     *
     * @param store the machine state store, or {@code null} if it failed to wire up.
     *              Items are still returned when {@code store} is {@code null}; only
     *              the state-clearing step is skipped, since there is nothing to clear.
     */
    public static void closeForBlock(Block block, MachineStore store) {
        Objects.requireNonNull(block, "block");
        boolean returnedAny = false;
        for (Player player : Bukkit.getOnlinePlayers()) {
            Inventory top = player.getOpenInventory().getTopInventory();
            if (top.getHolder() instanceof MachineState state && state.block().equals(block)) {
                returnAllItems(top, player);
                player.closeInventory();
                returnedAny = true;
            }
        }
        if (returnedAny && store != null) {
            store.forget(block);
        }
    }

    /** One step of the per-viewer shutdown sequence performed by {@link #closeAll}. */
    public enum CloseAllStep {
        /**
         * Sync the inventory into machine state and flush it to the block's PDC.
         * Already attempted by {@link #closeAll} before this list is consulted --
         * {@link #closeAllSteps} takes the outcome as a parameter rather than deciding
         * it, since deciding it requires the Bukkit/PDC call that cannot be made from
         * pure data. This step exists in the sequence so the ordering guarantee below
         * is enforced by an exhaustive {@code switch} over the enum, not restated.
         */
        PERSIST,
        /**
         * Clear the block's persisted state (nothing may be left duplicated in the
         * PDC), then return every item directly to the viewer.
         */
        RETURN,
        /** Close the now-settled inventory. */
        CLOSE
    }

    /**
     * The ordered steps {@link #closeAll} performs for one viewer, given whether
     * persisting that viewer's machine succeeded.
     *
     * <p>Extracted as data, and driven directly by {@link #closeAll}, so the property
     * that keeps shutdown from ever losing <em>or</em> duplicating an item is pinned by
     * a unit test with no server: the item-safety step ({@link CloseAllStep#PERSIST} or
     * {@link CloseAllStep#RETURN}) always comes before {@link CloseAllStep#CLOSE}.
     * Reordering it after {@code CLOSE} would put us back to relying on an
     * {@code InventoryCloseEvent} that never fires during {@code onDisable} -- see
     * {@link #closeAll}'s note on why that dependency is unsafe here.
     */
    public static List<CloseAllStep> closeAllSteps(boolean persistSucceeded) {
        return persistSucceeded
                ? List.of(CloseAllStep.PERSIST, CloseAllStep.CLOSE)
                : List.of(CloseAllStep.RETURN, CloseAllStep.CLOSE);
    }

    /**
     * Force-closes every online player currently viewing any Electric Furnace GUI.
     * Intended for the plugin's {@code onDisable}, called <em>after</em>
     * {@code MachineStore#flushAll()}.
     *
     * <p>For each open GUI, this makes one more attempt to fold its current contents
     * into its machine's state and flush that state to the block's PDC directly --
     * closing the gap between "flushAll already ran" and "this particular inventory
     * had unsynced edits sitting in it at that exact moment." The outcome selects which
     * branch of {@link #closeAllSteps} runs: on success, nothing more needs to happen
     * to the block's state (already flushed) before closing; on failure -- including
     * when {@code store} itself failed to wire up -- the block's state is cleared via
     * {@link MachineStore#forget} <em>before</em> the items are returned directly to
     * the viewer, exactly like the pre-persistence model did unconditionally. That
     * clearing step is what stops a machine whose earlier {@code flushAll()} already
     * succeeded from ending up with its contents in both the block's PDC and the
     * player's inventory. Neither branch relies on {@code InventoryCloseEvent}: Bukkit
     * clears {@code isEnabled} <em>before</em> invoking {@code onDisable()}, and
     * {@code SimplePluginManager#fireEvent} skips every {@code RegisteredListener}
     * belonging to a disabled plugin, so {@code MachineGuiListener#onClose} never runs
     * here.
     *
     * <p>Each viewer's body ({@link #persist}, {@link #closeAllSteps}'s steps) runs
     * inside its own {@code try}/{@code catch (Throwable)}: {@link MachineStore#forget}
     * and {@link #returnAllItems} (via {@code dropItemNaturally}) can both still throw
     * during world teardown even though {@link #persist} already swallows its own
     * failures, and an uncaught throw here would abort the loop for every remaining
     * viewer -- or, worse, strand one viewer's items outright if it landed between
     * {@code forget} clearing the PDC and {@code returnAllItems} handing the items
     * over. A failure is logged and shutdown moves on to the next viewer.
     */
    public static void closeAll(MachineStore store) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Inventory top = player.getOpenInventory().getTopInventory();
            if (!(top.getHolder() instanceof MachineState state)) {
                continue;
            }
            Block block = state.block();
            // Per-viewer try/catch: persist() already swallows its own failures, but
            // store.forget (whose block.getState() call is not itself guarded -- see
            // MachineStore#forget) and returnAllItems -> dropItemNaturally can both
            // still throw here, e.g. during world teardown. Without this guard, a throw
            // would abort the loop for every remaining viewer; worse, a throw between
            // forget and returnAllItems would destroy that one viewer's items outright
            // (PDC already cleared, inventory never handed over). Logging and moving on
            // keeps one bad viewer from stranding the rest.
            try {
                boolean persisted = persist(block, store);
                for (CloseAllStep step : closeAllSteps(persisted)) {
                    switch (step) {
                        case PERSIST -> {
                            // Nothing further to do -- persisted (computed above)
                            // already captures that this attempt succeeded.
                        }
                        case RETURN -> {
                            if (store != null) {
                                store.forget(block);
                            }
                            returnAllItems(top, player);
                        }
                        case CLOSE -> player.closeInventory();
                    }
                }
            } catch (Throwable t) {
                LOGGER.warning("ElectricFurnace: failed to close the Electric Furnace GUI for "
                        + player.getName() + " at " + describe(block) + " during shutdown ("
                        + t.getClass().getName() + ": " + t.getMessage()
                        + "); continuing to the next viewer.");
            }
        }
    }

    /**
     * Attempts to flush {@code block}'s machine state to its PDC.
     *
     * <p>There is no "sync the inventory into state" step any more, and none is missing:
     * the inventory <em>is</em> the state's storage, so whatever the viewer last did to
     * it is already what {@code MachineStateCodec} will encode.
     */
    private static boolean persist(Block block, MachineStore store) {
        // store can be null here even though FurnaceGui itself never stores it: this is
        // a static utility, and ElectricFurnacePlugin#onDisable calls closeAll(store)
        // unconditionally -- including when the "machine store" wiring step in onEnable
        // failed and the field was never assigned. Calling closeAll in that case is
        // still correct (items must still be returned to viewers); this check is what
        // makes that safe rather than an NPE the catch below would otherwise mask.
        if (store == null) {
            return false;
        }
        try {
            store.flush(block);
            return true;
        } catch (Throwable t) {
            LOGGER.warning("ElectricFurnace: failed to persist machine state during shutdown at "
                    + describe(block) + " (" + t.getClass().getName() + ": " + t.getMessage()
                    + "); returning items to the viewing player instead.");
            return false;
        }
    }

    private static String describe(Block block) {
        return block.getWorld().getName() + " " + block.getX() + "," + block.getY() + "," + block.getZ();
    }

    private static ItemStack indicatorItem(IndicatorState state, int progressTicks, int smeltTicks) {
        Material material = switch (state) {
            case RUNNING -> Material.REDSTONE_TORCH;
            case NO_SIGNAL -> Material.LEVER;
            case NO_FUEL -> Material.REDSTONE;
            case SMELTING -> Material.BLAST_FURNACE;
        };
        String label = switch (state) {
            case RUNNING -> "Running";
            case NO_SIGNAL -> "No redstone signal";
            case NO_FUEL -> "No fuel";
            case SMELTING -> "Smelting";
        };
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text(label).decoration(TextDecoration.ITALIC, false));
        if (state == IndicatorState.SMELTING) {
            meta.lore(List.of(Component.text(ProgressBar.render(progressTicks, smeltTicks))
                    .decoration(TextDecoration.ITALIC, false)));
        }
        stack.setItemMeta(meta);
        return stack;
    }

    private static ItemStack buildFillerItem() {
        ItemStack stack = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text(" ").decoration(TextDecoration.ITALIC, false));
        stack.setItemMeta(meta);
        return stack;
    }
}
