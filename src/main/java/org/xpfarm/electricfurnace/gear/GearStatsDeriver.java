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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Derives one gear piece's stats from an alloy's single stat block.
 *
 * <p><b>Entirely pure.</b> No {@code org.bukkit} type appears here or in its tests.
 * Every reference constant comes from {@code AlloyRegistry}, never re-declared.
 */
public final class GearStatsDeriver {

    private GearStatsDeriver() {
    }

    /**
     * Splits an alloy's full-set armor total across the four armor pieces using
     * vanilla's 3/8/6/3 shape with largest-remainder rounding.
     *
     * <p><b>The pieces always sum to exactly {@code total}.</b> That is the entire
     * reason this is largest-remainder rather than plain rounding: rounding each piece
     * independently loses or gains points, so an alloy configured for 16 armor would
     * silently grant 15 or 17. All arithmetic is integer -- {@code numerator * total}
     * is divided by the denominator for the floor and the remainder is taken with
     * {@code %}, so there is no floating-point drift to reason about.
     *
     * <p>Ties in the remainder are broken by {@link GearPiece#armorPieces()} order.
     *
     * @param total the alloy's configured full-set armor points; negative is treated as 0
     * @return an entry for each of the four armor pieces, summing to {@code total}
     */
    public static Map<GearPiece, Integer> splitArmor(int total) {
        int safeTotal = Math.max(0, total);
        Map<GearPiece, Integer> result = new EnumMap<>(GearPiece.class);
        List<GearPiece> pieces = GearPiece.armorPieces();

        int assigned = 0;
        Map<GearPiece, Integer> remainders = new EnumMap<>(GearPiece.class);
        for (GearPiece piece : pieces) {
            int scaled = piece.armorShareNumerator() * safeTotal;
            int floor = scaled / GearPiece.ARMOR_SHARE_DENOMINATOR;
            result.put(piece, floor);
            remainders.put(piece, scaled % GearPiece.ARMOR_SHARE_DENOMINATOR);
            assigned += floor;
        }

        int spare = safeTotal - assigned;
        List<GearPiece> byRemainder = new ArrayList<>(pieces);
        byRemainder.sort(Comparator
                .comparingInt((GearPiece piece) -> remainders.get(piece)).reversed()
                .thenComparingInt(pieces::indexOf));

        for (int i = 0; i < spare; i++) {
            GearPiece piece = byRemainder.get(i);
            result.put(piece, result.get(piece) + 1);
        }
        return result;
    }
}
