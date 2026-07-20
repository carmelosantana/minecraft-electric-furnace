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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MachineSettingsTest {

    @Test
    void smeltTicksFor_defaultMultiplier_isTwoAndAHalfTimesFasterThanVanilla() {
        assertEquals(80, MachineSettings.smeltTicksFor(2.5D));
    }

    @Test
    void smeltTicksFor_multiplierOfOne_matchesVanilla() {
        assertEquals(MachineSettings.BASE_SMELT_TICKS, MachineSettings.smeltTicksFor(1.0D));
    }

    @Test
    void smeltTicksFor_validatedRange_neverLeavesTwentyToTwoHundredTicks() {
        for (int tenths = 10; tenths <= 100; tenths++) {
            int ticks = MachineSettings.smeltTicksFor(tenths / 10.0D);
            assertTrue(ticks >= 20 && ticks <= 200,
                    "multiplier " + (tenths / 10.0D) + " produced " + ticks + " ticks");
        }
    }

    @Test
    void smeltTicks_readsTheRecordsOwnMultiplier() {
        MachineSettings settings = new MachineSettings(2.5D, 200, true, true);
        assertEquals(80, settings.smeltTicks());
    }
}
