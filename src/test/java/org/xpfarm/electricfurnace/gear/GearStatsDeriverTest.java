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

import org.junit.jupiter.api.Test;
import org.xpfarm.electricfurnace.alloy.AlloyRegistry;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link GearStatsDeriver}. Pure -- no running server. */
class GearStatsDeriverTest {

    @Test
    void splitArmor_steelTotalOf16_matchesTheWorkedExampleInTheSpec() {
        Map<GearPiece, Integer> split = GearStatsDeriver.splitArmor(16);

        assertEquals(2, split.get(GearPiece.HELMET));
        assertEquals(7, split.get(GearPiece.CHESTPLATE));
        assertEquals(5, split.get(GearPiece.LEGGINGS));
        assertEquals(2, split.get(GearPiece.BOOTS));
    }

    @Test
    void splitArmor_alwaysSumsToExactlyTheTotal() {
        for (int total = 0; total <= 40; total++) {
            int sum = GearStatsDeriver.splitArmor(total).values().stream()
                    .mapToInt(Integer::intValue)
                    .sum();
            assertEquals(total, sum, "split of " + total + " must sum back to " + total);
        }
    }

    @Test
    void splitArmor_diamondTotalOf20_reproducesVanillaDistribution() {
        Map<GearPiece, Integer> split = GearStatsDeriver.splitArmor(20);

        assertEquals(3, split.get(GearPiece.HELMET));
        assertEquals(8, split.get(GearPiece.CHESTPLATE));
        assertEquals(6, split.get(GearPiece.LEGGINGS));
        assertEquals(3, split.get(GearPiece.BOOTS));
    }

    @Test
    void splitArmor_sparePointsFavourChestplateOverBoots() {
        // Total 1: every piece floors to 0, one spare point, chestplate wins the tiebreak.
        Map<GearPiece, Integer> split = GearStatsDeriver.splitArmor(1);

        assertEquals(1, split.get(GearPiece.CHESTPLATE));
        assertEquals(0, split.get(GearPiece.HELMET));
        assertEquals(0, split.get(GearPiece.LEGGINGS));
        assertEquals(0, split.get(GearPiece.BOOTS));
    }

    @Test
    void splitArmor_sparePointsGoByRemainderSizeNotByPieceOrder() {
        // Total 17 floors to 6/5/2/2 = 15, leaving two spare points. Remainders are
        // chest 16, helmet 11, boots 11, leggings 2 -- so the helmet, the *smallest*
        // share, outranks the leggings for the second spare point. Handing spares out
        // in plain armorPieces() order would give leggings 6 and helmet 2 instead.
        Map<GearPiece, Integer> split = GearStatsDeriver.splitArmor(17);

        assertEquals(7, split.get(GearPiece.CHESTPLATE));
        assertEquals(5, split.get(GearPiece.LEGGINGS));
        assertEquals(3, split.get(GearPiece.HELMET));
        assertEquals(2, split.get(GearPiece.BOOTS));
    }

    @Test
    void splitArmor_isDeterministicAcrossRepeatedCalls() {
        for (int i = 0; i < 50; i++) {
            assertEquals(GearStatsDeriver.splitArmor(17), GearStatsDeriver.splitArmor(17));
        }
    }

    @Test
    void splitArmor_negativeTotalIsTreatedAsZero() {
        Map<GearPiece, Integer> split = GearStatsDeriver.splitArmor(-5);

        assertTrue(split.values().stream().allMatch(value -> value == 0));
    }

    @Test
    void armorBaseUnit_atTheIronReference_isVanillaIronArmorBase() {
        assertEquals(GearStatsDeriver.IRON_ARMOR_BASE,
                GearStatsDeriver.armorBaseUnit(AlloyRegistry.IRON_MAX_DURABILITY));
    }

    @Test
    void armorBaseUnit_atTheDiamondReference_isVanillaDiamondArmorBase() {
        assertEquals(GearStatsDeriver.DIAMOND_ARMOR_BASE,
                GearStatsDeriver.armorBaseUnit(AlloyRegistry.DIAMOND_MAX_DURABILITY));
    }

    @Test
    void armorBaseUnit_steel700_derivesTo21() {
        assertEquals(21, GearStatsDeriver.armorBaseUnit(700));
    }

    @Test
    void armorBaseUnit_belowIronReference_clampsToIronBase() {
        assertEquals(GearStatsDeriver.IRON_ARMOR_BASE, GearStatsDeriver.armorBaseUnit(0));
        assertEquals(GearStatsDeriver.IRON_ARMOR_BASE, GearStatsDeriver.armorBaseUnit(100));
    }

    @Test
    void armorBaseUnit_atNetheriteReference_clampsToNetheriteBase() {
        // (2031-250)/1311 * 18 + 15 = 39.45, above the netherite base -- must clamp.
        assertEquals(GearStatsDeriver.NETHERITE_ARMOR_BASE,
                GearStatsDeriver.armorBaseUnit(AlloyRegistry.NETHERITE_MAX_DURABILITY));
    }

    @Test
    void armorBaseUnit_isMonotonic() {
        int previous = GearStatsDeriver.armorBaseUnit(0);
        for (int durability = 0; durability <= 2100; durability += 7) {
            int current = GearStatsDeriver.armorBaseUnit(durability);
            assertTrue(current >= previous,
                    "more durable alloys must never derive a smaller armor base");
            previous = current;
        }
    }

    @Test
    void armorBaseUnit_roundsToNearestRatherThanTruncating() {
        // (800-250)/1311 * 18 + 15 = 22.55 -- nearest is 23; truncating would give 22.
        assertEquals(23, GearStatsDeriver.armorBaseUnit(800));
    }
}
