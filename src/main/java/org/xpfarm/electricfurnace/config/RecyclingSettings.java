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
 * Validated {@code recycling} settings section of {@code config.yml}.
 *
 * @param slots            number of recycler input slots (valid range 1-9)
 * @param yieldSameMetal   ingots yielded when all inputs are the same metal with no
 *                         modifier present (valid range 0-64)
 * @param yieldMixedAlloy  alloy ingots yielded for a mixed-metal input, whether a named
 *                         recipe or the generic fallback (valid range 0-64)
 * @param yieldRemeltAlloy ingots yielded per alloy item remelted; an all-alloy input of
 *                         N items sharing one alloy id yields N times this. The only
 *                         yield that multiplies by the input count, and so the only one
 *                         capped relative to {@code slots} rather than at a flat 64 --
 *                         valid range 0 to {@code 64 / slots}, so that even a full batch
 *                         fits one output stack. See
 *                         {@link ConfigValidator#remeltYieldCeiling}.
 * @param acceptDamaged    whether damaged (partially worn) gear is accepted at full yield
 */
public record RecyclingSettings(
        int slots,
        int yieldSameMetal,
        int yieldMixedAlloy,
        int yieldRemeltAlloy,
        boolean acceptDamaged
) {
}
