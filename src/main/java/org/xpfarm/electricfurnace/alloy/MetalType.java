/*
 * ElectricFurnace - a redstone-powered smelter that recycles metal gear into ingots.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.electricfurnace.alloy;

/**
 * The recyclable metals recognized by the resolver.
 *
 * <p><b>Coal is deliberately not a member of this enum.</b> Coal (and charcoal) is a
 * modifier, not a metal -- it cannot be recycled on its own, does not count toward
 * the "all same metal" check, and only participates as a named-alloy ingredient
 * (e.g. Steel). See {@link org.xpfarm.electricfurnace.recycle.RecycleInput#isModifier()}.
 */
public enum MetalType {
    IRON,
    GOLD,
    COPPER,
    NETHERITE
}
