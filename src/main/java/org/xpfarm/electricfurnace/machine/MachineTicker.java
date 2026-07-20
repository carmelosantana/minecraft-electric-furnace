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

/**
 * Advances every machine in a loaded chunk, one tick at a time.
 *
 * <p>{@link #step} is the entire decision, expressed as a pure transition over
 * primitives -- no {@code org.bukkit} type -- so every stall, resume, and completion path
 * is table-tested with no running server, following the same pattern as
 * {@code FurnaceGui.mayRun}.
 */
public final class MachineTicker {

    private MachineTicker() {
    }

    /** What one tick did to a machine. */
    public enum Outcome {
        /** A redstone signal is required and absent. Progress and burn are held. */
        STALLED_NO_POWER,
        /** The current inputs resolve to nothing smeltable. Progress is reset. */
        STALLED_NO_RECIPE,
        /** The output slot cannot accept the result. Progress is held so the run resumes. */
        STALLED_OUTPUT_BLOCKED,
        /** No burn time left and no redstone to buy more. Progress is held. */
        STALLED_NO_FUEL,
        /** Progress moved forward by one tick. */
        ADVANCED,
        /** This tick finished the run: deposit output and consume inputs. */
        COMPLETED
    }

    /** Everything the step needs to know about the world, as plain booleans. */
    public record Conditions(
            boolean powered,
            boolean requireSignal,
            boolean recipeValid,
            boolean outputBlocked,
            boolean fuelAvailable
    ) {
    }

    /** The result of one tick: the new counters, and whether to take a dust. */
    public record Step(Outcome outcome, int progressTicks, int burnTicksRemaining, boolean consumeOneFuel) {
    }

    /**
     * Advances one machine by one tick.
     *
     * <p>Ordering matters and is deliberate. Power, recipe, and output are all checked
     * <b>before</b> fuel is purchased, so a machine that cannot smelt never consumes a
     * dust it will not use. Only an invalid recipe resets progress -- every other stall
     * holds it, so a run interrupted by a lost signal or a full output slot resumes
     * exactly where it left off once the blocker clears.
     */
    public static Step step(Conditions conditions, int progressTicks, int burnTicksRemaining,
                            int smeltTicks, int burnTicksPerRedstone) {
        boolean effectivePowered = !conditions.requireSignal() || conditions.powered();
        if (!effectivePowered) {
            return new Step(Outcome.STALLED_NO_POWER, progressTicks, burnTicksRemaining, false);
        }
        if (!conditions.recipeValid()) {
            return new Step(Outcome.STALLED_NO_RECIPE, 0, burnTicksRemaining, false);
        }
        if (conditions.outputBlocked()) {
            return new Step(Outcome.STALLED_OUTPUT_BLOCKED, progressTicks, burnTicksRemaining, false);
        }

        int burn = burnTicksRemaining;
        boolean consumeOneFuel = false;
        if (burn <= 0) {
            if (!conditions.fuelAvailable()) {
                return new Step(Outcome.STALLED_NO_FUEL, progressTicks, 0, false);
            }
            burn = burnTicksPerRedstone;
            consumeOneFuel = true;
        }

        int progress = progressTicks + 1;
        burn = burn - 1;

        if (progress >= smeltTicks) {
            return new Step(Outcome.COMPLETED, 0, burn, consumeOneFuel);
        }
        return new Step(Outcome.ADVANCED, progress, burn, consumeOneFuel);
    }
}
