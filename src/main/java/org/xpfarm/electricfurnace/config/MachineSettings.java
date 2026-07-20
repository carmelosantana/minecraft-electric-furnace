/*
 * ElectricFurnace - a redstone-powered smelter that recycles metal gear into ingots.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.electricfurnace.config;

/**
 * Validated {@code machine} settings section of {@code config.yml}.
 *
 * @param smeltSpeedMultiplier   multiplier applied to the vanilla smelt duration (valid range 1.0-10.0)
 * @param burnTicksPerRedstone   ticks of burn time bought by one redstone dust (valid range 20-6000)
 * @param requireRedstoneSignal  whether a redstone signal is required for the machine to run at all
 * @param statusBulbEnabled      whether an adjacent {@code COPPER_BULB} is driven to reflect machine state
 */
public record MachineSettings(
        double smeltSpeedMultiplier,
        int burnTicksPerRedstone,
        boolean requireRedstoneSignal,
        boolean statusBulbEnabled
) {

    /** A vanilla furnace smelts one item in this many ticks. */
    public static final int BASE_SMELT_TICKS = 200;

    /**
     * The per-item smelt duration for a given speed multiplier.
     *
     * <p>Pure so the derivation is pinned by a test across the whole validated
     * 1.0-10.0 range: the result must never fall outside 20-200 ticks, and must never
     * reach zero (a zero-tick smelt would complete every tick and drain an inventory
     * instantly).
     */
    public static int smeltTicksFor(double multiplier) {
        return Math.max(1, (int) Math.round(BASE_SMELT_TICKS / multiplier));
    }

    /** The per-item smelt duration implied by this configuration. */
    public int smeltTicks() {
        return smeltTicksFor(smeltSpeedMultiplier);
    }
}
