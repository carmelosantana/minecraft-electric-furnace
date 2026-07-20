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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProgressBarTest {

    @Test
    void zeroProgress_rendersAnEmptyBarAtZeroPercent() {
        assertEquals("▱▱▱▱▱▱▱▱▱▱ 0%", ProgressBar.render(0, 80));
    }

    @Test
    void halfProgress_rendersHalfFilledAtFiftyPercent() {
        assertEquals("▰▰▰▰▰▱▱▱▱▱ 50%", ProgressBar.render(40, 80));
    }

    @Test
    void nearlyComplete_neverRendersAsOneHundredPercent() {
        String rendered = ProgressBar.render(79, 80);
        assertTrue(rendered.endsWith("98%"), rendered);
    }

    @Test
    void everyProgressValue_rendersExactlySegmentsPlusPercentAndNeverOverflows() {
        for (int progress = 0; progress < 80; progress++) {
            String rendered = ProgressBar.render(progress, 80);
            long filled = rendered.chars().filter(c -> c == '▰').count();
            long emptied = rendered.chars().filter(c -> c == '▱').count();
            assertEquals(ProgressBar.SEGMENTS, filled + emptied, "at progress " + progress);
        }
    }

    @Test
    void nonPositiveSmeltTicks_doesNotDivideByZero() {
        assertEquals("▱▱▱▱▱▱▱▱▱▱ 0%", ProgressBar.render(5, 0));
    }
}
