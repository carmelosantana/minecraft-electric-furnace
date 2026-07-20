/*
 * ElectricFurnace - a redstone-powered smelter that recycles metal gear into ingots.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.electricfurnace.machine;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.xpfarm.electricfurnace.gui.GuiLayout;

import java.util.Objects;

/**
 * The persisted contents and run state of one Electric Furnace block.
 *
 * <h2>The inventory is the storage, not a view of it</h2>
 *
 * <p>This class holds no {@code ItemStack} fields. Its items live in one Bukkit
 * {@link Inventory}, created lazily per machine block and handed unchanged to every
 * player who opens the GUI. There is therefore exactly one place a machine's items can
 * be: a player's click writes the same slots {@link MachineTicker} reads and
 * {@link MachineStateCodec} encodes.
 *
 * <p>That single location is what removes the entire class of bug this plugin used to
 * defend against with deferred syncs, pending-sync counters, and skip guards. Bukkit
 * finishes applying a click only after the event handler returns, so a handler cannot
 * observe the click's own result -- but with one storage location it no longer needs to.
 * Nothing has to be copied back anywhere, so there is no window in which two copies can
 * disagree.
 *
 * <p>This class <b>is</b> the inventory's {@link InventoryHolder}, so any code holding a
 * furnace GUI inventory can recover both the machine and its block with no store lookup.
 *
 * <h2>Slots are read and written, never aliased</h2>
 *
 * <p>{@link #inputs()}, {@link #fuel()} and {@link #output()} return <em>detached
 * copies</em>. Every mutation is therefore read-modify-{@link Inventory#setItem
 * write-back}, here and in {@link MachineTicker}.
 *
 * <p>That is enforced rather than agreed. {@link Inventory#getItem} is unspecified as to
 * liveness: CraftBukkit happens to return a fresh wrapper around the live stack, so a
 * mutation reaches the slot, but nothing in the API promises it and another
 * implementation may hand back a copy. Returning that stack directly would leave a rule
 * that only conventions uphold, whose violation works on one server and silently loses
 * the mutation on another -- the kind of contract that rots, because nothing fails when
 * it is broken. Cloning deletes the live case: a caller that mutates a returned stack
 * and forgets to write it back provably cannot have touched the slot, anywhere. See
 * {@code snapshotOf}.
 *
 * <p>The same rule is why {@link RecipeCache} can no longer fingerprint slots by stack
 * identity: a fresh object per {@code getItem} call would make an identity comparison
 * fail every tick and the cache dead weight. See that class for what replaced it.
 *
 * <p>Only the five input slots, the fuel slot, and the output slot are this machine's
 * contents. The filler panes and status indicator that share the same inventory are
 * decoration owned by {@code FurnaceGui}; they are never read here and never persisted
 * -- see {@link MachineStateCodec}.
 *
 * <p>Ownership is single-threaded -- only the main-thread ticker and main-thread event
 * handlers touch it.
 */
public final class MachineState implements InventoryHolder {

    /** Number of recycler input slots. Mirrors {@code GuiLayout.INPUT_SLOTS}. */
    public static final int INPUT_COUNT = 5;

    /** The five input slots in a fixed order, so slot {@code i} is always input {@code i}. */
    private static final int[] INPUT_SLOTS_ORDERED = {
            GuiLayout.INPUT_SLOT_1, GuiLayout.INPUT_SLOT_2, GuiLayout.INPUT_SLOT_3,
            GuiLayout.INPUT_SLOT_4, GuiLayout.INPUT_SLOT_5
    };

    private final Block block;

    /**
     * This machine's one and only item storage, created on first use.
     *
     * <p>Lazy rather than eager purely so a {@code MachineState} can exist without a
     * running server: {@link Bukkit#createInventory} cannot be called headlessly, but
     * {@link #progressTicks()}, {@link #burnTicksRemaining()} and {@link #isIdle()} are
     * ordinary integer logic that a unit test must still be able to reach. Every
     * item-facing method below goes through {@link #getInventory()}, so the distinction
     * is invisible in production, where the first hydration touches the slots
     * immediately.
     */
    private Inventory inventory;

    private int progressTicks;
    private int burnTicksRemaining;

    /**
     * This machine's memo of what its input slots currently resolve to. Kept here, not
     * in {@link MachineTicker}, because it is per-machine and must outlive a single
     * tick; it decides its own validity by re-reading the input slots, so nothing that
     * writes to them has to know it exists.
     */
    private final RecipeCache recipeCache = new RecipeCache();

    private MachineState(Block block) {
        this.block = Objects.requireNonNull(block, "block");
    }

    /** A machine with nothing in it and no run in progress. */
    public static MachineState empty(Block block) {
        return new MachineState(block);
    }

    /** The Electric Furnace block these contents belong to. */
    public Block block() {
        return block;
    }

    /**
     * This machine's item storage, created on first call. The same instance is returned
     * for the life of this state, and is what {@code FurnaceGui.open} hands to every
     * viewer -- so two players watching one machine are literally editing one object.
     */
    @Override
    public Inventory getInventory() {
        if (inventory == null) {
            inventory = Bukkit.createInventory(this, GuiLayout.SIZE, Component.text(GuiLayout.TITLE_TEXT));
        }
        return inventory;
    }

    /**
     * A snapshot of the five input slots, in slot order. Entries are {@code null} for an
     * empty slot; length is always {@link #INPUT_COUNT}.
     *
     * <p>A snapshot, <b>not</b> a live array, and the stacks in it are detached copies:
     * neither writing into the array nor mutating a stack it holds changes any slot. Use
     * {@link #setInput} to change one. See {@code snapshotOf} for why this is a copy
     * rather than a promise.
     */
    public ItemStack[] inputs() {
        ItemStack[] snapshot = new ItemStack[INPUT_COUNT];
        Inventory contents = getInventory();
        for (int i = 0; i < INPUT_COUNT; i++) {
            snapshot[i] = snapshotOf(contents.getItem(INPUT_SLOTS_ORDERED[i]));
        }
        return snapshot;
    }

    /** Replaces one input slot's contents. {@code null} empties it. */
    public void setInput(int index, ItemStack item) {
        if (index < 0 || index >= INPUT_COUNT) {
            throw new IllegalArgumentException("input index " + index + " is outside [0, " + INPUT_COUNT + ")");
        }
        getInventory().setItem(INPUT_SLOTS_ORDERED[index], item);
    }

    /**
     * The fuel slot's contents, or {@code null} when empty. A detached copy -- see
     * {@link #snapshotOf}; mutate and {@link #setFuel} it back.
     */
    public ItemStack fuel() {
        return snapshotOf(getInventory().getItem(GuiLayout.FUEL_SLOT));
    }

    public void setFuel(ItemStack fuel) {
        getInventory().setItem(GuiLayout.FUEL_SLOT, fuel);
    }

    /**
     * The output slot's contents, or {@code null} when empty. A detached copy -- see
     * {@link #snapshotOf}; mutate and {@link #setOutput} it back.
     */
    public ItemStack output() {
        return snapshotOf(getInventory().getItem(GuiLayout.OUTPUT_SLOT));
    }

    public void setOutput(ItemStack output) {
        getInventory().setItem(GuiLayout.OUTPUT_SLOT, output);
    }

    public int progressTicks() {
        return progressTicks;
    }

    public void setProgressTicks(int progressTicks) {
        this.progressTicks = Math.max(0, progressTicks);
    }

    public int burnTicksRemaining() {
        return burnTicksRemaining;
    }

    public void setBurnTicksRemaining(int burnTicksRemaining) {
        this.burnTicksRemaining = Math.max(0, burnTicksRemaining);
    }

    /** This machine's recipe-resolution memo. See {@link RecipeCache}. */
    RecipeCache recipeCache() {
        return recipeCache;
    }

    /** Whether no run is currently in progress. Drives the input lock. */
    public boolean isIdle() {
        return progressTicks == 0;
    }

    /**
     * Bukkit slots report an empty stack as either {@code null} or {@code AIR} depending
     * on the path taken; normalize to {@code null} so every caller has one empty case.
     */
    static ItemStack normalizeAir(ItemStack item) {
        return (item == null || item.getType() == Material.AIR) ? null : item;
    }

    /**
     * One slot read: normalized to {@code null} when empty, and <b>detached from the
     * inventory</b> when not.
     *
     * <p>This is what makes the read-modify-write-back rule structural instead of
     * conventional. {@link Inventory#getItem} is unspecified as to liveness -- CraftBukkit
     * happens to return a fresh wrapper around the live stack, so a mutation reaches the
     * slot, but nothing in the API promises that and a different implementation may hand
     * back a copy. Code written against the accidental behaviour works or does not
     * depending on which one you are running, which is the worst possible failure mode:
     * a mutation that silently reaches nothing on someone else's server.
     *
     * <p>Cloning removes the ambiguity by removing the live case. A caller that mutates
     * what {@link #inputs()}, {@link #fuel()} or {@link #output()} returned and forgets to
     * {@code set} it back now <em>provably</em> cannot have changed the slot, on every
     * implementation. The bug becomes a visibly lost mutation rather than something that
     * reproduces on one server and not another.
     *
     * <p>The cost is one {@code ItemStack} copy per occupied slot per read;
     * {@link #inputs()} is read once per machine per tick, far below the recipe
     * resolution {@link RecipeCache} exists to avoid.
     */
    private static ItemStack snapshotOf(ItemStack item) {
        ItemStack present = normalizeAir(item);
        return present == null ? null : present.clone();
    }
}
