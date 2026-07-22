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

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link GearPiece}. Pure -- no running server. */
class GearPieceTest {

    @Test
    void armorShareNumerators_sumToDenominator() {
        int sum = GearPiece.armorPieces().stream()
                .mapToInt(GearPiece::armorShareNumerator)
                .sum();
        assertEquals(GearPiece.ARMOR_SHARE_DENOMINATOR, sum,
                "armor numerators must sum to the denominator, or splitArmor cannot preserve the total");
    }

    @Test
    void armorPieces_areTheFourArmourSlotsInTiebreakOrder() {
        assertEquals(List.of(GearPiece.CHESTPLATE, GearPiece.LEGGINGS, GearPiece.HELMET, GearPiece.BOOTS),
                GearPiece.armorPieces());
    }

    @Test
    void weapons_haveNoArmorShareAndNoDurabilityFactor() {
        for (GearPiece piece : List.of(GearPiece.SWORD, GearPiece.AXE)) {
            assertEquals(GearPiece.Kind.WEAPON, piece.kind());
            assertEquals(0, piece.armorShareNumerator());
            assertEquals(0, piece.durabilityFactor());
        }
    }

    @Test
    void sword_hasNoWeaponDeltas_axeIsStrongerAndSlower() {
        assertEquals(0.0, GearPiece.SWORD.attackDamageDelta());
        assertEquals(0.0, GearPiece.SWORD.attackSpeedDelta());
        assertEquals(2.0, GearPiece.AXE.attackDamageDelta());
        assertEquals(-0.6, GearPiece.AXE.attackSpeedDelta());
    }

    @Test
    void vanillaCosts_matchTheSpec() {
        assertEquals(2, GearPiece.SWORD.ingotCost());
        assertEquals(1, GearPiece.SWORD.stickCost());
        assertEquals(3, GearPiece.AXE.ingotCost());
        assertEquals(2, GearPiece.AXE.stickCost());
        assertEquals(5, GearPiece.HELMET.ingotCost());
        assertEquals(8, GearPiece.CHESTPLATE.ingotCost());
        assertEquals(7, GearPiece.LEGGINGS.ingotCost());
        assertEquals(4, GearPiece.BOOTS.ingotCost());
        assertEquals(0, GearPiece.HELMET.stickCost());
    }

    @Test
    void armorPieces_carryVanillasPerSlotShareAndDurabilityConstants() {
        // The sum-only check above cannot catch a redistribution (e.g. helmet 4 / boots 2),
        // and nothing else pins the durability column at all. Both are read verbatim by
        // later stat derivation, so pin the whole table against vanilla diamond armor.
        assertEquals(3, GearPiece.HELMET.armorShareNumerator());
        assertEquals(8, GearPiece.CHESTPLATE.armorShareNumerator());
        assertEquals(6, GearPiece.LEGGINGS.armorShareNumerator());
        assertEquals(3, GearPiece.BOOTS.armorShareNumerator());

        assertEquals(11, GearPiece.HELMET.durabilityFactor());
        assertEquals(16, GearPiece.CHESTPLATE.durabilityFactor());
        assertEquals(15, GearPiece.LEGGINGS.durabilityFactor());
        assertEquals(13, GearPiece.BOOTS.durabilityFactor());
    }

    @Test
    void everyPiece_hasItsIdAndDisplayName() {
        for (GearPiece piece : GearPiece.values()) {
            assertEquals(piece.name().toLowerCase(Locale.ROOT), piece.id());
        }
        assertEquals("Sword", GearPiece.SWORD.displayName());
        assertEquals("Axe", GearPiece.AXE.displayName());
        assertEquals("Helmet", GearPiece.HELMET.displayName());
        assertEquals("Chestplate", GearPiece.CHESTPLATE.displayName());
        assertEquals("Leggings", GearPiece.LEGGINGS.displayName());
        assertEquals("Boots", GearPiece.BOOTS.displayName());
    }

    @Test
    void byId_isCaseInsensitiveAndRejectsUnknown() {
        assertEquals(Optional.of(GearPiece.CHESTPLATE), GearPiece.byId("chestplate"));
        assertEquals(Optional.of(GearPiece.CHESTPLATE), GearPiece.byId("CHESTPLATE"));
        assertTrue(GearPiece.byId("trousers").isEmpty());
        assertTrue(GearPiece.byId(null).isEmpty());
    }
}
