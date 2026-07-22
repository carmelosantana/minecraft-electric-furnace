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
import org.xpfarm.electricfurnace.alloy.AlloyStats;

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

    // Steel, as shipped in config.yml.
    private static final AlloyStats STEEL = new AlloyStats(6.5, -2.6, 16, 1.0, 700, 12);

    @Test
    void derive_sword_usesConfiguredCombatStatsAndToolDurability() {
        GearStats stats = GearStatsDeriver.derive(STEEL, GearPiece.SWORD);

        assertEquals(6.5, stats.attackDamage());
        assertEquals(-2.6, stats.attackSpeed());
        assertEquals(700, stats.maxDurability());
        assertEquals(12, stats.enchantability());
        assertEquals(0, stats.armor());
        assertEquals(0.0, stats.armorToughness());
    }

    @Test
    void derive_axe_appliesTheVanillaAxeDelta() {
        GearStats stats = GearStatsDeriver.derive(STEEL, GearPiece.AXE);

        assertEquals(8.5, stats.attackDamage(), 1e-9);
        assertEquals(-3.2, stats.attackSpeed(), 1e-9);
        assertEquals(700, stats.maxDurability());
    }

    @Test
    void derive_armor_matchesTheWorkedExampleInTheSpec() {
        assertEquals(2, GearStatsDeriver.derive(STEEL, GearPiece.HELMET).armor());
        assertEquals(7, GearStatsDeriver.derive(STEEL, GearPiece.CHESTPLATE).armor());
        assertEquals(5, GearStatsDeriver.derive(STEEL, GearPiece.LEGGINGS).armor());
        assertEquals(2, GearStatsDeriver.derive(STEEL, GearPiece.BOOTS).armor());

        assertEquals(231, GearStatsDeriver.derive(STEEL, GearPiece.HELMET).maxDurability());
        assertEquals(336, GearStatsDeriver.derive(STEEL, GearPiece.CHESTPLATE).maxDurability());
        assertEquals(315, GearStatsDeriver.derive(STEEL, GearPiece.LEGGINGS).maxDurability());
        assertEquals(273, GearStatsDeriver.derive(STEEL, GearPiece.BOOTS).maxDurability());
    }

    @Test
    void derive_armor_readsTheAlloyRatherThanReusingSteelsNumbers() {
        // Every other armor assertion here derives from STEEL, so on its own each one
        // is also satisfied by an implementation that hardcodes steel's split (16),
        // steel's armor base unit (21) or steel's toughness (1.0). Electrum Steel is a
        // second alloy whose three armor inputs all differ, so this pins the armor
        // branch to its arguments: split of 18, base unit of 24, toughness of 1.5.
        AlloyStats electrumSteel = new AlloyStats(6.8, -2.4, 18, 1.5, 900, 16);

        assertEquals(3, GearStatsDeriver.derive(electrumSteel, GearPiece.HELMET).armor());
        assertEquals(7, GearStatsDeriver.derive(electrumSteel, GearPiece.CHESTPLATE).armor());
        assertEquals(5, GearStatsDeriver.derive(electrumSteel, GearPiece.LEGGINGS).armor());
        assertEquals(3, GearStatsDeriver.derive(electrumSteel, GearPiece.BOOTS).armor());

        assertEquals(264, GearStatsDeriver.derive(electrumSteel, GearPiece.HELMET).maxDurability());
        assertEquals(384, GearStatsDeriver.derive(electrumSteel, GearPiece.CHESTPLATE).maxDurability());
        assertEquals(360, GearStatsDeriver.derive(electrumSteel, GearPiece.LEGGINGS).maxDurability());
        assertEquals(312, GearStatsDeriver.derive(electrumSteel, GearPiece.BOOTS).maxDurability());

        assertEquals(1.5, GearStatsDeriver.derive(electrumSteel, GearPiece.CHESTPLATE).armorToughness());
    }

    @Test
    void derive_armor_carriesToughnessPerPieceNotSplit() {
        // Vanilla grants diamond's 2.0 toughness on every piece, not 0.5 each.
        for (GearPiece piece : GearPiece.armorPieces()) {
            assertEquals(1.0, GearStatsDeriver.derive(STEEL, piece).armorToughness());
        }
    }

    @Test
    void derive_armor_hasNoCombatStats() {
        for (GearPiece piece : GearPiece.armorPieces()) {
            GearStats stats = GearStatsDeriver.derive(STEEL, piece);
            assertEquals(0.0, stats.attackDamage());
            assertEquals(0.0, stats.attackSpeed());
        }
    }

    @Test
    void derive_enchantabilityPassesThroughToEveryPiece() {
        for (GearPiece piece : GearPiece.values()) {
            assertEquals(12, GearStatsDeriver.derive(STEEL, piece).enchantability());
        }
    }

    @Test
    void derive_armorAcrossAllPieces_sumsToTheConfiguredTotal() {
        int sum = GearPiece.armorPieces().stream()
                .mapToInt(piece -> GearStatsDeriver.derive(STEEL, piece).armor())
                .sum();
        assertEquals(STEEL.armor(), sum);
    }

    @Test
    void derive_axeDamageMayExceedTheNetheriteSwordReference() {
        // Electrum Steel: 6.8 + 2.0 = 8.8, above NETHERITE_ATTACK_DAMAGE (8.0).
        // Deliberate -- the references are sword-scale, and vanilla's netherite axe
        // does 10.0 against its sword's 8.0. Clamping here would make every alloy axe
        // worse than its own sword is allowed to be.
        AlloyStats electrumSteel = new AlloyStats(6.8, -2.4, 18, 1.5, 900, 16);

        assertEquals(8.8, GearStatsDeriver.derive(electrumSteel, GearPiece.AXE).attackDamage(), 1e-9);
        assertTrue(GearStatsDeriver.derive(electrumSteel, GearPiece.AXE).attackDamage()
                > AlloyRegistry.NETHERITE_ATTACK_DAMAGE);
    }
}
