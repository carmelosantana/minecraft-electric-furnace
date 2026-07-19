/*
 * ElectricFurnace - a redstone-powered smelter that recycles metal gear into ingots.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.electricfurnace.recycle;

import org.xpfarm.electricfurnace.alloy.MetalType;

/**
 * One item occupying one recycler input slot, described entirely in plain terms so
 * that {@link RecycleResolver} never needs to know what an {@code ItemStack} is.
 *
 * <p>Task 3's {@code MetalClassifier} is the bridge from Bukkit {@code ItemStack}s to
 * this record: it decides {@code metal}, {@code isModifier}, {@code isAlloy}, and
 * {@code ingotValue} from the item's material and PDC, discarding anything the
 * resolver does not need -- notably durability/damage, since
 * {@code recycling.accept-damaged} means damaged and undamaged gear must resolve
 * identically, and that is only guaranteed if this record cannot represent damage at
 * all.
 *
 * <p>Exactly one of {@code metal != null}, {@code isModifier}, or {@code isAlloy}
 * should hold for any well-formed input; an input where {@code metal == null} and
 * {@code isModifier == false} (whether or not {@code isAlloy}) is treated by the
 * resolver as a non-metal input.
 *
 * @param materialId a stable identifier for the underlying material/item, used as
 *                    the modifier's id in named-alloy matching (e.g. {@code "coal"});
 *                    ignored for metal inputs, whose id is derived from {@code metal}
 * @param metal       the metal this input counts as, or {@code null} if this input is
 *                    not a metal (a modifier, an alloy, or unrecognized)
 * @param isModifier  whether this input is a modifier (e.g. coal/charcoal) rather
 *                    than a metal
 * @param isAlloy     whether this input is itself an alloy item (remelt candidate)
 * @param alloyId     the alloy id if {@code isAlloy}, otherwise {@code null}
 * @param ingotValue  the ingot-equivalent value of this input; carried through for
 *                    downstream/display use only -- {@link RecycleResolver} does not
 *                    consult it, since none of the eight resolution rules depend on it
 */
public record RecycleInput(
        String materialId,
        MetalType metal,
        boolean isModifier,
        boolean isAlloy,
        String alloyId,
        int ingotValue
) {
}
