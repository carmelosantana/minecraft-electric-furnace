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
 * The complete stat block carried by an alloy definition.
 *
 * <p>Per the balance ceiling, every stat here is expected to sit between the iron
 * and diamond reference points and never exceed the netherite reference point.
 * {@link AlloyRegistry} enforces this on load by clamping and warning -- this record
 * itself performs no validation; it is a plain data holder.
 *
 * @param attackDamage    base attack damage if this alloy is ever used in a weapon
 * @param attackSpeed     base attack speed modifier if this alloy is ever used in a weapon
 * @param armor           armor points if this alloy is ever used in armor
 * @param armorToughness  armor toughness if this alloy is ever used in armor
 * @param maxDurability   maximum durability of items made from this alloy
 * @param enchantability  enchantability of items made from this alloy
 */
public record AlloyStats(
        double attackDamage,
        double attackSpeed,
        int armor,
        double armorToughness,
        int maxDurability,
        int enchantability
) {
}
