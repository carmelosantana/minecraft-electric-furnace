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

import org.bukkit.inventory.ItemStack;

/**
 * The persisted contents and run state of one Electric Furnace block.
 *
 * <p>Mutable by design: {@link MachineTicker} advances the same instance in place every
 * tick, and copying seven {@code ItemStack}s per machine per tick would be wasteful.
 * Ownership is single-threaded -- only the main-thread ticker and main-thread event
 * handlers touch it.
 */
public final class MachineState {

    /** Number of recycler input slots. Mirrors {@code GuiLayout.INPUT_SLOTS}. */
    public static final int INPUT_COUNT = 5;

    private final ItemStack[] inputs = new ItemStack[INPUT_COUNT];
    private ItemStack fuel;
    private ItemStack output;
    private int progressTicks;
    private int burnTicksRemaining;

    private MachineState() {
    }

    /** A machine with nothing in it and no run in progress. */
    public static MachineState empty() {
        return new MachineState();
    }

    /** The live input slot array. Entries may be {@code null}; length is always {@link #INPUT_COUNT}. */
    public ItemStack[] inputs() {
        return inputs;
    }

    public ItemStack fuel() {
        return fuel;
    }

    public void setFuel(ItemStack fuel) {
        this.fuel = fuel;
    }

    public ItemStack output() {
        return output;
    }

    public void setOutput(ItemStack output) {
        this.output = output;
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

    /** Whether no run is currently in progress. Drives the input lock. */
    public boolean isIdle() {
        return progressTicks == 0;
    }
}
