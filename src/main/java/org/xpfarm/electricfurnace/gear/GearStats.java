/*
 * ElectricFurnace - a redstone-powered smelter that recycles metal gear into ingots.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.electricfurnace.gear;

/**
 * One gear piece's derived stat block.
 *
 * <p>Unlike {@code AlloyStats}, whose {@code armor} is a full-set total and whose
 * {@code maxDurability} is tool-scale, every value here is the concrete number that
 * belongs on one specific item. Armor pieces carry no combat stats and weapons carry
 * no armor stats; the unused fields are zero rather than absent, so the record stays a
 * plain data holder with no optionality to handle.
 *
 * @param attackDamage    attack damage, or {@code 0.0} for armor
 * @param attackSpeed     attack speed modifier, or {@code 0.0} for armor
 * @param armor           armor points for this one piece, or {@code 0} for weapons
 * @param armorToughness  armor toughness for this one piece, or {@code 0.0} for weapons
 * @param maxDurability   this item's maximum durability
 * @param enchantability  this item's enchantability
 */
public record GearStats(
        double attackDamage,
        double attackSpeed,
        int armor,
        double armorToughness,
        int maxDurability,
        int enchantability
) {
}
