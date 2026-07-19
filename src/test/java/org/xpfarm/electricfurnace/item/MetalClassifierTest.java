/*
 * ElectricFurnace - a redstone-powered smelter that recycles metal gear into ingots.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.electricfurnace.item;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import org.xpfarm.electricfurnace.alloy.MetalType;
import org.xpfarm.electricfurnace.config.RecyclingSettings;
import org.xpfarm.electricfurnace.recycle.RecycleInput;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link MetalClassifier}.
 *
 * <p>Everything here exercises the pure, {@code Material}/primitive-based surface of
 * the classifier -- {@link MetalClassifier#metalOf}, {@link MetalClassifier#isModifier},
 * and the primitive overload of {@link MetalClassifier#classify}. None of it
 * constructs a real {@code ItemStack}: doing so requires a live server (Paper's
 * {@code ItemStack} construction resolves item types through {@code RegistryAccess},
 * which throws {@code IllegalStateException} with no server bootstrapped), which is
 * exactly why the classifier is split this way -- the mapping table and the
 * accept-damaged decision are fully testable here, and the {@code ItemStack}-facing
 * overload is exercised only at runtime (gate 7a), not in this suite.
 */
class MetalClassifierTest {

    private static final RecyclingSettings ACCEPT_DAMAGED =
            new RecyclingSettings(5, 3, 2, 1, true);
    private static final RecyclingSettings REJECT_DAMAGED =
            new RecyclingSettings(5, 3, 2, 1, false);

    // ---- Exhaustive Material -> MetalType mapping, asserted without a running server ----

    @Test
    void ironFamily_mapsToIron() {
        for (Material material : List.of(
                Material.IRON_INGOT, Material.RAW_IRON,
                Material.IRON_SWORD, Material.IRON_SPEAR, Material.IRON_PICKAXE, Material.IRON_AXE,
                Material.IRON_SHOVEL, Material.IRON_HOE,
                Material.IRON_HELMET, Material.IRON_CHESTPLATE, Material.IRON_LEGGINGS, Material.IRON_BOOTS)) {
            assertEquals(Optional.of(MetalType.IRON), MetalClassifier.metalOf(material), material.name());
        }
    }

    @Test
    void chainmail_mapsToIron() {
        for (Material material : List.of(
                Material.CHAINMAIL_HELMET, Material.CHAINMAIL_CHESTPLATE,
                Material.CHAINMAIL_LEGGINGS, Material.CHAINMAIL_BOOTS)) {
            assertEquals(Optional.of(MetalType.IRON), MetalClassifier.metalOf(material), material.name());
        }
    }

    @Test
    void goldFamily_mapsToGold() {
        for (Material material : List.of(
                Material.GOLD_INGOT, Material.RAW_GOLD,
                Material.GOLDEN_SWORD, Material.GOLDEN_SPEAR, Material.GOLDEN_PICKAXE, Material.GOLDEN_AXE,
                Material.GOLDEN_SHOVEL, Material.GOLDEN_HOE,
                Material.GOLDEN_HELMET, Material.GOLDEN_CHESTPLATE, Material.GOLDEN_LEGGINGS, Material.GOLDEN_BOOTS)) {
            assertEquals(Optional.of(MetalType.GOLD), MetalClassifier.metalOf(material), material.name());
        }
    }

    @Test
    void copperFamily_mapsToCopper() {
        for (Material material : List.of(
                Material.COPPER_INGOT, Material.RAW_COPPER,
                Material.COPPER_SWORD, Material.COPPER_SPEAR, Material.COPPER_PICKAXE, Material.COPPER_AXE,
                Material.COPPER_SHOVEL, Material.COPPER_HOE,
                Material.COPPER_HELMET, Material.COPPER_CHESTPLATE, Material.COPPER_LEGGINGS, Material.COPPER_BOOTS)) {
            assertEquals(Optional.of(MetalType.COPPER), MetalClassifier.metalOf(material), material.name());
        }
    }

    @Test
    void netheriteFamily_mapsToNetherite() {
        for (Material material : List.of(
                Material.NETHERITE_INGOT,
                Material.NETHERITE_SWORD, Material.NETHERITE_SPEAR, Material.NETHERITE_PICKAXE, Material.NETHERITE_AXE,
                Material.NETHERITE_SHOVEL, Material.NETHERITE_HOE,
                Material.NETHERITE_HELMET, Material.NETHERITE_CHESTPLATE, Material.NETHERITE_LEGGINGS,
                Material.NETHERITE_BOOTS)) {
            assertEquals(Optional.of(MetalType.NETHERITE), MetalClassifier.metalOf(material), material.name());
        }
    }

    @Test
    void metalTable_hasExactlyTheExpectedEntries_noMoreNoLess() {
        // 16 iron-family (incl. 4 chainmail) + 12 gold + 12 copper + 11 netherite.
        assertEquals(16 + 12 + 12 + 11, MetalClassifier.METAL_TABLE.size());
    }

    // ---- Coal and charcoal are modifiers, not metals ---------------------------------

    @Test
    void coalAndCharcoal_areModifiers() {
        assertTrue(MetalClassifier.isModifier(Material.COAL));
        assertTrue(MetalClassifier.isModifier(Material.CHARCOAL));
        assertTrue(MetalClassifier.metalOf(Material.COAL).isEmpty());
        assertTrue(MetalClassifier.metalOf(Material.CHARCOAL).isEmpty());
    }

    @Test
    void coalAndCharcoal_classifyAsModifier_sharingTheSameNamedAlloyId() {
        Optional<RecycleInput> coal =
                MetalClassifier.classify(Material.COAL, "coal", false, false, null, ACCEPT_DAMAGED);
        Optional<RecycleInput> charcoal =
                MetalClassifier.classify(Material.CHARCOAL, "charcoal", false, false, null, ACCEPT_DAMAGED);

        assertTrue(coal.isPresent());
        assertTrue(coal.get().isModifier());
        assertNull(coal.get().metal());
        assertTrue(charcoal.isPresent());
        assertTrue(charcoal.get().isModifier());
        assertEquals("coal", coal.get().materialId());
        assertEquals("coal", charcoal.get().materialId(),
                "charcoal must share coal's id so it also matches recipes like Steel");
    }

    @Test
    void nonMetalMaterials_areNotModifiers() {
        assertFalse(MetalClassifier.isModifier(Material.IRON_INGOT));
        assertFalse(MetalClassifier.isModifier(Material.DIRT));
    }

    // ---- Unrecognized items classify to empty -----------------------------------------

    @Test
    void dirtAndStick_classifyAsEmpty() {
        assertTrue(MetalClassifier.metalOf(Material.DIRT).isEmpty());
        assertTrue(MetalClassifier.metalOf(Material.STICK).isEmpty());
        assertTrue(MetalClassifier.classify(Material.DIRT, "dirt", false, false, null, ACCEPT_DAMAGED).isEmpty());
        assertTrue(MetalClassifier.classify(Material.STICK, "stick", false, false, null, ACCEPT_DAMAGED).isEmpty());
    }

    // ---- Nuggets have ingot value < 1 and are rejected outright -----------------------

    @Test
    void nuggets_areRejected() {
        assertTrue(MetalClassifier.classify(Material.IRON_NUGGET, "iron_nugget", false, false, null, ACCEPT_DAMAGED)
                .isEmpty());
        assertTrue(MetalClassifier.classify(Material.GOLD_NUGGET, "gold_nugget", false, false, null, ACCEPT_DAMAGED)
                .isEmpty());
        assertTrue(MetalClassifier.classify(Material.COPPER_NUGGET, "copper_nugget", false, false, null, ACCEPT_DAMAGED)
                .isEmpty());
    }

    // ---- An alloy-stamped stack classifies as isAlloy ---------------------------------

    @Test
    void alloyStampedInput_classifiesAsAlloy() {
        Optional<RecycleInput> result =
                MetalClassifier.classify(Material.NETHERITE_INGOT, "steel_ingot", false, true, "steel", ACCEPT_DAMAGED);

        assertTrue(result.isPresent());
        assertTrue(result.get().isAlloy());
        assertEquals("steel", result.get().alloyId());
        assertNull(result.get().metal());
        assertFalse(result.get().isModifier());
    }

    // ---- Accept-damaged coverage, owed by Task 3 --------------------------------------
    // RecycleInput has no damage field; the resolver cannot fail this on its own. This is
    // where Bukkit ItemStack damage actually exists, so this is where it must be tested.

    @Test
    void damagedAndPristineGear_classifyIdentically_whenAcceptDamagedIsTrue() {
        Optional<RecycleInput> pristine =
                MetalClassifier.classify(Material.IRON_SWORD, "iron_sword", false, false, null, ACCEPT_DAMAGED);
        Optional<RecycleInput> heavilyDamaged =
                MetalClassifier.classify(Material.IRON_SWORD, "iron_sword", true, false, null, ACCEPT_DAMAGED);

        assertTrue(pristine.isPresent());
        assertEquals(pristine, heavilyDamaged,
                "accept-damaged=true must yield identical RecycleInput regardless of durability");
    }

    @Test
    void damagedGear_isRejected_whenAcceptDamagedIsFalse() {
        Optional<RecycleInput> heavilyDamaged =
                MetalClassifier.classify(Material.IRON_SWORD, "iron_sword", true, false, null, REJECT_DAMAGED);
        Optional<RecycleInput> pristine =
                MetalClassifier.classify(Material.IRON_SWORD, "iron_sword", false, false, null, REJECT_DAMAGED);

        assertTrue(heavilyDamaged.isEmpty(), "accept-damaged=false must reject damaged gear outright");
        assertTrue(pristine.isPresent(), "accept-damaged=false must still accept pristine gear");
    }

    @Test
    void damageIsIgnored_forAlloyItems_evenWhenAcceptDamagedIsFalse() {
        // An alloy remelt is not "gear" in the accept-damaged sense; alloy items are not
        // subject to the accept-damaged gate at all.
        Optional<RecycleInput> result =
                MetalClassifier.classify(Material.NETHERITE_INGOT, "steel_ingot", true, true, "steel", REJECT_DAMAGED);

        assertTrue(result.isPresent());
        assertTrue(result.get().isAlloy());
    }
}
