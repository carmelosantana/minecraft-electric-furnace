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
import org.xpfarm.electricfurnace.machine.MachineState;
import org.xpfarm.electricfurnace.machine.MachineStore;

import java.util.ArrayList;
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
 * <p><b>The GUI is a shared view onto persistent machine state, not its owner.</b>
 * {@link #open} no longer creates a fresh, empty inventory: it binds a single
 * {@code Inventory} per machine block, populated once from that block's
 * {@link MachineState} (held by {@link MachineStore}) and reused for every later
 * viewer, so two players watching the same machine see identical contents update
 * live. Closing the GUI ({@code InventoryCloseEvent}) no longer drains it back to the
 * player -- the items belong to the machine now, and {@link #syncToState} folds
 * whatever is currently in the inventory back into that machine's persisted state.
 * {@link #returnAllItems} still exists, but its callers narrow to the two cases where
 * there genuinely is no machine left to hold the items: {@link #closeForBlock}
 * (the block is about to be broken) and {@link #closeAll} (shutdown, and only for a
 * machine whose state could not be persisted).
 *
 * <p>The processing gate ({@link #mayRun}) and the status-indicator decision
 * ({@link #indicatorStateOf}) are pure functions over primitives/enums -- no
 * {@code org.bukkit} type -- so {@code FurnaceGuiTest} exercises every combination
 * with no running server, following the same pattern as
 * {@code MetalClassifier.resolveBranch}. Recipe resolution itself (what used to be
 * {@code tryProcess}) has moved to {@code MachineTicker}, which advances every loaded
 * machine on its own schedule instead of once per GUI click.
 */
public final class FurnaceGui {

    private static final Logger LOGGER = Logger.getLogger("ElectricFurnace");

    /** The five recycler input slots, in the same order as {@link MachineState#inputs()}. */
    private static final int[] INPUT_SLOTS_ORDERED = {
            GuiLayout.INPUT_SLOT_1, GuiLayout.INPUT_SLOT_2, GuiLayout.INPUT_SLOT_3,
            GuiLayout.INPUT_SLOT_4, GuiLayout.INPUT_SLOT_5
    };

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

    /** What the output slot currently holds, relative to the item an operation would produce. */
    public enum OutputSlotState {
        /** The output slot is empty. */
        EMPTY,
        /** The output slot holds an item that matches what would be produced, with room to merge. */
        SAME_ITEM,
        /** The output slot holds something else -- or the same item with no room left -- blocking the run. */
        DIFFERENT_ITEM
    }

    /**
     * The processing gate: an operation may run only when effectively powered
     * (powered, or {@code requireSignal} is {@code false}), fuel is present, and the
     * output slot does not block it. An output slot occupied by a different item (or
     * the same item with no stacking room left) never runs and never consumes fuel --
     * a player's output is never silently overwritten or corrupted.
     *
     * <p>Kept here (rather than moved wholesale to {@code MachineTicker}) because it is
     * pure, already exhaustively tested, and is exactly the shape of decision
     * {@code MachineTicker}'s Bukkit-facing driver needs to compute {@code recipeValid}
     * and {@code outputBlocked} every tick.
     */
    public static boolean mayRun(boolean powered, boolean requireSignal, boolean hasFuel, OutputSlotState outputSlotState) {
        boolean effectivePowered = !requireSignal || powered;
        return effectivePowered && hasFuel && outputSlotState != OutputSlotState.DIFFERENT_ITEM;
    }

    // =================================================================================
    // Bukkit-facing glue
    // =================================================================================

    /** Marks a custom inventory as belonging to one specific Electric Furnace block. */
    public static final class Holder implements InventoryHolder {
        private final Block block;
        private Inventory inventory;

        /**
         * How many {@code MachineGuiListener#scheduleSync} deferred callbacks are
         * currently in flight for this block's shared inventory. A plain {@code int},
         * not a {@code boolean}: the same shared inventory can have more than one
         * viewer, and each viewer's click schedules its own independent one-tick
         * deferral, so a count (not a flag one callback could clear out from under
         * another) is what keeps {@link #refreshFromState}'s guard correct. Read and
         * written only on the Bukkit main thread, same as every other mutable field on
         * this class.
         */
        private int pendingSyncCount;

        private Holder(Block block) {
            this.block = Objects.requireNonNull(block, "block");
        }

        /** The Electric Furnace block this GUI instance belongs to. */
        public Block block() {
            return block;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    /** Whether {@code inventory} is an Electric Furnace GUI. */
    public static boolean isFurnaceGui(Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof Holder;
    }

    /** The block a given Electric Furnace GUI inventory belongs to, if it is one. */
    public static Optional<Block> blockOf(Inventory inventory) {
        if (inventory != null && inventory.getHolder() instanceof Holder holder) {
            return Optional.of(holder.block());
        }
        return Optional.empty();
    }

    /**
     * Opens {@code block}'s Electric Furnace GUI for {@code player}, binding a single
     * shared {@code Inventory} per machine.
     *
     * <p>If another player already has this block's GUI open, {@code player} is handed
     * that exact same {@code Inventory} instance -- found by scanning online players'
     * currently-open top inventories, the same technique {@link #closeForBlock} and
     * {@code RedstoneListener} already use to find viewers of a block, so no extra
     * cache is needed and nothing can go stale when a machine is broken or its chunk
     * unloads. Otherwise a new inventory is built and its slots populated directly from
     * {@code state}'s arrays.
     */
    public static Inventory open(Player player, Block block, EfConfig config, boolean powered, MachineState state) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(block, "block");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(state, "state");

        Inventory inventory = findOpenInventory(block).orElseGet(() -> buildInventory(block, state));

        refreshIndicator(inventory, config, powered, !state.isIdle(), state.progressTicks(), config.machine().smeltTicks());

        player.openInventory(inventory);
        return inventory;
    }

    /**
     * The currently-open shared GUI inventory for {@code block}, if any online player
     * has it open. Public because {@code MachineStore#flush} also calls this, to fold
     * a currently-open GUI's edits into state right before encoding it -- see the note
     * there on the deferred-sync window this closes.
     */
    public static Optional<Inventory> findOpenInventory(Block block) {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            Inventory top = viewer.getOpenInventory().getTopInventory();
            if (blockOf(top).filter(block::equals).isPresent()) {
                return Optional.of(top);
            }
        }
        return Optional.empty();
    }

    private static Inventory buildInventory(Block block, MachineState state) {
        Holder holder = new Holder(block);
        Inventory inventory = Bukkit.createInventory(holder, GuiLayout.SIZE, Component.text(GuiLayout.TITLE_TEXT));
        holder.inventory = inventory;

        for (int slot = 0; slot < GuiLayout.SIZE; slot++) {
            if (GuiLayout.roleOf(slot) == GuiLayout.SlotRole.FILLER) {
                inventory.setItem(slot, buildFillerItem());
            }
        }
        populateFromState(inventory, state);
        return inventory;
    }

    /** Mirrors {@code state}'s input/fuel/output arrays into a freshly built inventory's slots. */
    private static void populateFromState(Inventory inventory, MachineState state) {
        ItemStack[] inputs = state.inputs();
        for (int i = 0; i < INPUT_SLOTS_ORDERED.length && i < inputs.length; i++) {
            inventory.setItem(INPUT_SLOTS_ORDERED[i], inputs[i]);
        }
        inventory.setItem(GuiLayout.FUEL_SLOT, state.fuel());
        inventory.setItem(GuiLayout.OUTPUT_SLOT, state.output());
    }

    /**
     * The inverse of {@link #populateFromState}: copies {@code inventory}'s current
     * input/fuel/output slots back into {@code state}'s fields.
     *
     * <p>This is the one method standing between "items placed in an open GUI" and
     * "items the machine's persisted state actually knows about." {@code MachineStore}
     * persists only what is in a {@code MachineState}'s fields; it has no idea a GUI
     * inventory exists. Every place this class or {@code MachineGuiListener} lets go of
     * an inventory a player might have edited -- a click, a drag, a close, a shutdown --
     * calls this first, so a flush that happens to land in between never serializes
     * stale, pre-edit contents.
     */
    public static void syncToState(Inventory inventory, MachineState state) {
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(state, "state");

        ItemStack[] inputs = state.inputs();
        for (int i = 0; i < INPUT_SLOTS_ORDERED.length && i < inputs.length; i++) {
            inputs[i] = normalizeAir(inventory.getItem(INPUT_SLOTS_ORDERED[i]));
        }
        state.setFuel(normalizeAir(inventory.getItem(GuiLayout.FUEL_SLOT)));
        state.setOutput(normalizeAir(inventory.getItem(GuiLayout.OUTPUT_SLOT)));
    }

    /** Bukkit slots report an empty stack as either {@code null} or {@code AIR} depending on the path taken; normalize to {@code null}. */
    private static ItemStack normalizeAir(ItemStack item) {
        return (item == null || item.getType() == Material.AIR) ? null : item;
    }

    /**
     * Marks one {@code MachineGuiListener#scheduleSync} deferred callback as in flight
     * for {@code inventory}'s machine. Call synchronously, in the same tick as the
     * click or drag that produced the deferral -- <em>before</em> the one-tick delay,
     * not from inside it -- so {@link #refreshFromState} is guaranteed to see the
     * marker the instant a driver could possibly run ahead of the deferred callback
     * (a repeating task registered at {@code onEnable} has a lower task id than a
     * one-shot scheduled afterward, so it runs first when both are due the same tick).
     * A no-op if {@code inventory} is not a furnace GUI. Paired with
     * {@link #clearPendingSync}.
     */
    public static void markPendingSync(Inventory inventory) {
        if (inventory != null && inventory.getHolder() instanceof Holder holder) {
            holder.pendingSyncCount++;
        }
    }

    /**
     * The other half of {@link #markPendingSync}: call from inside the deferred
     * callback once it has run, in a {@code finally} so an early return (e.g. the
     * block stopped being a machine before the callback fired) still clears it -- a
     * count that never returns to zero would wedge {@link #refreshFromState} into
     * skipping this machine forever. A no-op if {@code inventory} is not a furnace GUI.
     */
    public static void clearPendingSync(Inventory inventory) {
        if (inventory != null && inventory.getHolder() instanceof Holder holder && holder.pendingSyncCount > 0) {
            holder.pendingSyncCount--;
        }
    }

    /**
     * Pure guard for {@link #refreshFromState}: whether a pending-sync count means a
     * {@code MachineGuiListener#scheduleSync} deferred callback for this machine has
     * not run yet. Extracted from {@code refreshFromState} itself -- which cannot be
     * constructed headlessly, since it takes a real {@code Inventory} -- so the
     * polarity of the decision is pinned by a server-less test: a positive count must
     * skip, never run.
     */
    static boolean shouldSkipRefresh(int pendingSyncCount) {
        return pendingSyncCount > 0;
    }

    /**
     * How many {@code MachineGuiListener#scheduleSync} deferred callbacks are
     * currently in flight for {@code block}'s shared inventory, or {@code 0} if no
     * online player currently has that block's GUI open.
     *
     * <p>{@code MachineTicker}'s runner reads this -- together with the pure
     * {@code MachineTicker#shouldSkipMachine} -- to skip a machine <b>entirely</b> for
     * a tick, not merely skip repainting its GUI, while a deferred sync is still in
     * flight for it. See {@link #shouldSkipRefresh}'s note on the collision this two-
     * part guard closes: that guard alone stops a stale repaint, but does nothing to
     * stop a driver from mutating {@link MachineState} itself during the same window,
     * which is what actually causes duplicated fuel once the deferred sync later
     * overwrites state with the (stale, pre-tick) inventory contents.
     */
    public static int pendingSyncCount(Block block) {
        Objects.requireNonNull(block, "block");
        return findOpenInventory(block)
                .map(inventory -> inventory.getHolder() instanceof Holder holder ? holder.pendingSyncCount : 0)
                .orElse(0);
    }

    /**
     * The inverse direction from {@link #syncToState}: pushes {@code state}'s current
     * input/fuel/output arrays into {@code inventory}'s slots, then redraws the status
     * indicator -- unless a deferred GUI-&gt;state sync is still in flight for this
     * machine, in which case this call is skipped entirely.
     *
     * <p>{@link #buildInventory} already does the input/fuel/output half of this once,
     * via {@link #populateFromState}, when a fresh inventory is first built. This
     * public entry point exists for a different caller: a driver (the future
     * {@code MachineTicker}) that mutates a machine's {@link MachineState} -- consuming
     * inputs, producing output -- <em>while</em> a viewer already has that block's GUI
     * open. Without pushing the change back into the shared {@code Inventory}, the
     * open GUI would keep showing stale, pre-tick contents.
     *
     * <p><b>The collision this guards against.</b>
     * {@code MachineGuiListener#scheduleSync} defers folding a click's item movement
     * into {@code state} by one tick, because Bukkit only finishes applying the move
     * after its own event handler returns. A driver calling this method in that same
     * window -- and it would run first, per {@link #markPendingSync}'s javadoc -- would
     * collide with it in both directions: repopulating from state before the deferred
     * sync runs would overwrite an item a player just placed (already gone from their
     * inventory, not yet folded into {@code state}) with state's stale, item-less
     * arrays, and the deferred sync would then copy that clobbered inventory back over
     * state, so the item ends up nowhere; taking from the output slot (always
     * permitted) would similarly get repopulated from stale state before the deferred
     * sync overwrites state with the already-empty inventory, so the item ends up both
     * on the player's cursor and, briefly, back in the GUI and state. Guarding on
     * {@link #shouldSkipRefresh} closes both directions: this call is skipped entirely
     * while any deferred sync for this machine is outstanding, and resumes on the
     * following call once {@link #clearPendingSync} has run -- which, for a driver that
     * ticks every server tick, means "the very next tick," not an indefinite stall.
     */
    public static void refreshFromState(Inventory inventory, MachineState state, EfConfig config, boolean powered) {
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(config, "config");

        int pendingSyncCount = inventory.getHolder() instanceof Holder holder ? holder.pendingSyncCount : 0;
        if (shouldSkipRefresh(pendingSyncCount)) {
            return;
        }

        populateFromState(inventory, state);
        refreshIndicator(inventory, config, powered, !state.isIdle(), state.progressTicks(),
                config.machine().smeltTicks());
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

    /** Whether the fuel slot holds redstone at all. One dust buys burn time; there is no per-operation quantity. */
    private static boolean hasFuel(Inventory inventory) {
        return hasFuel(inventory.getItem(GuiLayout.FUEL_SLOT));
    }

    /**
     * Whether {@code fuel} counts as fuel at all: any amount of redstone. One dust buys
     * burn time; there is no per-operation quantity. Public so {@code MachineTicker}'s
     * runner shares this exact definition for its {@code Conditions.fuelAvailable}
     * rather than reimplementing it against a {@link MachineState} field instead of an
     * {@link Inventory} slot.
     */
    public static boolean hasFuel(ItemStack fuel) {
        return fuel != null && fuel.getType() == Material.REDSTONE && fuel.getAmount() > 0;
    }

    /**
     * Compares the output slot's current contents against what an operation would
     * produce. Blocks the run (returns {@link OutputSlotState#DIFFERENT_ITEM}) not
     * only when a genuinely different item occupies the slot, but also when merging
     * would exceed the max stack size -- an overflowing stack is exactly the kind of
     * silent corruption this plugin must never cause.
     *
     * <p>Kept alongside {@link #mayRun} for {@code MachineTicker}'s driver: it needs
     * this exact classification and there is no reason to make it reinvent it. Public
     * for that cross-package caller.
     */
    public static OutputSlotState classifyOutputSlot(ItemStack current, ItemStack candidate) {
        if (current == null || current.getType() == Material.AIR) {
            if (candidate == null) {
                return OutputSlotState.DIFFERENT_ITEM;
            }
            // An empty slot is only usable if the candidate itself fits in one stack.
            if (candidate.getAmount() > candidate.getMaxStackSize()) {
                return OutputSlotState.DIFFERENT_ITEM;
            }
            return OutputSlotState.EMPTY;
        }
        if (candidate == null || !current.isSimilar(candidate)) {
            return OutputSlotState.DIFFERENT_ITEM;
        }
        if (current.getAmount() + candidate.getAmount() > current.getMaxStackSize()) {
            return OutputSlotState.DIFFERENT_ITEM;
        }
        return OutputSlotState.SAME_ITEM;
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
     * (shutdown, only when persisting failed). A normal close does not call this --
     * see {@link #syncToState}.
     *
     * <p><b>Gives before clearing, per slot.</b> Each slot is handed to the player (or
     * dropped) <em>before</em> its slot is cleared. If {@link #giveOrDrop} -&gt;
     * {@code dropItemNaturally} throws -- exactly the world-teardown scenario
     * {@link #closeAll}'s per-viewer {@code try}/{@code catch} exists for -- the item
     * is still sitting in that slot when the exception propagates, not already cleared
     * and gone nowhere. Clearing first (the previous order) meant a mid-loop throw left
     * that one slot's item existing in zero places: not on the player, not dropped, and
     * no longer in the inventory either.
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
     * items to belong to -- unlike a normal close, which folds the inventory into the
     * machine's state (see {@link #syncToState}), this returns them straight to the
     * viewer. Items are returned <em>before</em> {@link Player#closeInventory()} is
     * called, exactly like {@link #closeAll} already does.
     *
     * <p><b>Does not depend on {@code InventoryCloseEvent}.</b> If any viewer's items
     * were returned, {@code block}'s state in {@code store} is cleared directly (via
     * {@link MachineStore#forget}) before this method returns. Earlier, this method's
     * correctness against {@code MachineBlockListener}'s {@code dropStoreContents}
     * relied on {@code player.closeInventory()} firing {@code InventoryCloseEvent},
     * which {@code MachineGuiListener#onClose} used to sync the now-emptied inventory
     * into {@code MachineState} -- a dependency that was silently false whenever that
     * event did not reach {@code onClose}: {@code MachineState} would still hold the
     * items just handed to the viewer, and {@code dropStoreContents} would then drop
     * those same items on the ground too, duplicating them. Calling
     * {@link MachineStore#forget} here removes that dependency: {@code store} always
     * knows this block's state is now empty, event or no event.
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
            if (top.getHolder() instanceof Holder holder && holder.block().equals(block)) {
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
     * {@link #closeAll}'s note on why that dependency is unsafe here. This restores,
     * for both branches {@code closeAll} now has, the same guarantee the original
     * (pre-persistence) {@code ShutdownStep}/{@code shutdownSteps()} pinned for its one
     * unconditional branch.
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
            if (!(top.getHolder() instanceof Holder holder)) {
                continue;
            }
            Block block = holder.block();
            // Per-viewer try/catch: persist() already swallows its own failures, but
            // store.forget (whose block.getState() call is not itself guarded -- see
            // MachineStore#forget) and returnAllItems -> dropItemNaturally can both
            // still throw here, e.g. during world teardown. Without this guard, a throw
            // would abort the loop for every remaining viewer; worse, a throw between
            // forget and returnAllItems would destroy that one viewer's items outright
            // (PDC already cleared, inventory never handed over). Logging and moving on
            // keeps one bad viewer from stranding the rest.
            try {
                boolean persisted = persist(top, block, store);
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

    /** Attempts to sync {@code inventory} into its machine's state and flush that state to disk. */
    private static boolean persist(Inventory inventory, Block block, MachineStore store) {
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
            syncToState(inventory, store.get(block));
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
