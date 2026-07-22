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

import java.util.Locale;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link GearBase}. Pure -- no running server. */
class GearBaseTest {

    @Test
    void materialName_usesTheGoldenPrefixForGold() {
        // Vanilla names gold equipment GOLDEN_*, not GOLD_*. Getting this wrong yields
        // a null Material at runtime and silently drops the alloy's gear.
        assertEquals("GOLDEN_SWORD", GearBase.GOLD.materialName(GearPiece.SWORD));
        assertEquals("GOLDEN_CHESTPLATE", GearBase.GOLD.materialName(GearPiece.CHESTPLATE));
    }

    @Test
    void materialName_coversEveryBaseAndPiece() {
        assertEquals("IRON_SWORD", GearBase.IRON.materialName(GearPiece.SWORD));
        assertEquals("COPPER_AXE", GearBase.COPPER.materialName(GearPiece.AXE));
        assertEquals("DIAMOND_HELMET", GearBase.DIAMOND.materialName(GearPiece.HELMET));
        assertEquals("NETHERITE_BOOTS", GearBase.NETHERITE.materialName(GearPiece.BOOTS));
        assertEquals("IRON_LEGGINGS", GearBase.IRON.materialName(GearPiece.LEGGINGS));
    }

    @Test
    void defaultFor_mapsTheFiveShippedAlloys() {
        assertEquals(GearBase.IRON, GearBase.defaultFor("steel"));
        assertEquals(GearBase.GOLD, GearBase.defaultFor("rose_gold"));
        assertEquals(GearBase.COPPER, GearBase.defaultFor("ferrocopper"));
        assertEquals(GearBase.DIAMOND, GearBase.defaultFor("electrum_steel"));
        assertEquals(GearBase.NETHERITE, GearBase.defaultFor("fused_alloy"));
    }

    @Test
    void defaultFor_unknownAlloyFallsBackToIron() {
        // Operators may add their own alloys; they must still get working gear.
        assertEquals(GearBase.IRON, GearBase.defaultFor("operator_invented_alloy"));
        assertEquals(GearBase.IRON, GearBase.defaultFor(null));
    }

    @Test
    void defaultFor_ignoresAlloyIdCase() {
        // Alloy ids arrive from operator-written config, where case is not enforced.
        assertEquals(GearBase.GOLD, GearBase.defaultFor("ROSE_GOLD"));
        assertEquals(GearBase.NETHERITE, GearBase.defaultFor("Fused_Alloy"));
    }

    @Test
    void id_isTheLowercaseFamilyTokenNotTheMaterialPrefix() {
        // id() is what an operator writes in alloys.<id>.base. For gold that token is
        // "gold" -- the GOLDEN_ irregularity belongs to the material name alone.
        assertEquals("copper", GearBase.COPPER.id());
        assertEquals("iron", GearBase.IRON.id());
        assertEquals("gold", GearBase.GOLD.id());
        assertEquals("diamond", GearBase.DIAMOND.id());
        assertEquals("netherite", GearBase.NETHERITE.id());
    }

    @Test
    void byId_resolvesEveryBaseFromItsOwnId() {
        for (GearBase base : GearBase.values()) {
            assertEquals(Optional.of(base), GearBase.byId(base.id()),
                    "byId must resolve " + base + " from its own id");
            assertEquals(Optional.of(base), GearBase.byId(base.id().toUpperCase(Locale.ROOT)),
                    "byId must resolve " + base + " case-insensitively");
        }
    }

    @Test
    void byId_isCaseInsensitiveAndRejectsUnknown() {
        assertEquals(Optional.of(GearBase.NETHERITE), GearBase.byId("netherite"));
        assertEquals(Optional.of(GearBase.NETHERITE), GearBase.byId("NETHERITE"));
        assertTrue(GearBase.byId("mithril").isEmpty());
        assertTrue(GearBase.byId(null).isEmpty());
    }

    @Test
    void onlyNetheriteIsFireImmuneByDefault() {
        assertTrue(GearBase.NETHERITE.fireImmuneByDefault());
        assertFalse(GearBase.IRON.fireImmuneByDefault());
        assertFalse(GearBase.GOLD.fireImmuneByDefault());
        assertFalse(GearBase.COPPER.fireImmuneByDefault());
        assertFalse(GearBase.DIAMOND.fireImmuneByDefault());
    }
}
