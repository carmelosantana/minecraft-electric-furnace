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
 * @param smeltSpeedMultiplier multiplier applied to the base smelt speed (valid range 1.0-10.0)
 * @param fuelPerOperation     redstone dust consumed per completed operation (valid range 1-64)
 * @param requireRedstoneSignal whether a redstone signal is required for the machine to run at all
 * @param statusBulbEnabled    whether an adjacent {@code COPPER_BULB} is driven to reflect machine state
 */
public record MachineSettings(
        double smeltSpeedMultiplier,
        int fuelPerOperation,
        boolean requireRedstoneSignal,
        boolean statusBulbEnabled
) {
}
