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

import org.xpfarm.electricfurnace.gear.GearBase;

import java.util.List;
import java.util.Set;

/**
 * One named alloy recipe: an id, its display metadata, the distinct set of inputs
 * required to produce it, and its stat block.
 *
 * <p>Deliberately free of any Bukkit or Adventure type -- {@code color} is a plain
 * hex string (e.g. {@code "#71797E"}) and {@code lore} is plain text lines. Task 3's
 * {@code AlloyItemFactory} is responsible for turning this into an actual
 * {@code ItemStack} with Adventure {@code Component} name/lore.
 *
 * <p>{@code inputIds} is the distinct set of metal/modifier ids required to match
 * this recipe (e.g. {@code {"iron", "coal"}} for Steel), order- and
 * quantity-independent -- as long as every listed input is present at least once,
 * the recipe matches regardless of how many of each. An <b>empty</b> {@code inputIds}
 * marks the generic fallback recipe (shipped as {@code fused_alloy}): it matches
 * nothing explicitly and is used only when no named recipe matches.
 *
 * @param id          stable identifier, e.g. {@code "steel"}
 * @param displayName human-readable name, e.g. {@code "Steel"}
 * @param lore        flavor/description lines
 * @param color       hex color string used to distinguish this alloy visually
 * @param inputIds    distinct required input ids; empty marks the generic fallback
 * @param stats       the stat block, balance-ceiling-clamped by {@link AlloyRegistry}
 * @param base        the vanilla base material family this alloy's gear is built on
 */
public record AlloyDefinition(
        String id,
        String displayName,
        List<String> lore,
        String color,
        Set<String> inputIds,
        AlloyStats stats,
        GearBase base
) {
    public AlloyDefinition {
        lore = List.copyOf(lore);
        inputIds = Set.copyOf(inputIds);
    }

    /** Whether this definition is the generic fallback (no fixed inputs). */
    public boolean isFallback() {
        return inputIds.isEmpty();
    }
}
