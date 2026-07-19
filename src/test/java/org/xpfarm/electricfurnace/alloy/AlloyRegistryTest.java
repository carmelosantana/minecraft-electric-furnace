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

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link AlloyRegistry}. Exercises {@link AlloyRegistry#fromDefinitions},
 * the pure core -- no {@code ConfigurationSection} or running server is needed.
 */
class AlloyRegistryTest {

    private static final AlloyStats BASELINE_STATS = new AlloyStats(6.5, -2.6, 16, 1.0, 700, 12);

    private final List<String> warnings = new ArrayList<>();

    private void warn(String message) {
        warnings.add(message);
    }

    // ---- Recipe matching is order-independent ---------------------------------------

    @Test
    void findNamedMatch_isOrderIndependent() {
        AlloyDefinition steel = new AlloyDefinition("steel", "Steel", List.of(), "#71797E",
                Set.of("iron", "coal"), BASELINE_STATS);
        AlloyRegistry registry = AlloyRegistry.fromDefinitions(List.of(steel, fallback()), this::warn);

        // Set.of(...) is unordered by construction, but build it two different ways to
        // make the intent explicit: the match must not depend on insertion order.
        Optional<AlloyDefinition> matchA = registry.findNamedMatch(Set.of("iron", "coal"));
        Optional<AlloyDefinition> matchB = registry.findNamedMatch(Set.of("coal", "iron"));

        assertTrue(matchA.isPresent());
        assertTrue(matchB.isPresent());
        assertEquals("steel", matchA.get().id());
        assertEquals("steel", matchB.get().id());
    }

    @Test
    void findNamedMatch_noMatch_returnsEmpty() {
        AlloyDefinition steel = new AlloyDefinition("steel", "Steel", List.of(), "#71797E",
                Set.of("iron", "coal"), BASELINE_STATS);
        AlloyRegistry registry = AlloyRegistry.fromDefinitions(List.of(steel, fallback()), this::warn);

        Optional<AlloyDefinition> match = registry.findNamedMatch(Set.of("gold", "copper"));

        assertTrue(match.isEmpty());
    }

    // ---- Unknown mix falls back to fused_alloy --------------------------------------

    @Test
    void unknownMix_fallsBackToFusedAlloy() {
        AlloyDefinition steel = new AlloyDefinition("steel", "Steel", List.of(), "#71797E",
                Set.of("iron", "coal"), BASELINE_STATS);
        AlloyRegistry registry = AlloyRegistry.fromDefinitions(List.of(steel, fallback()), this::warn);

        assertEquals("fused_alloy", registry.fallback().id());
        assertTrue(registry.fallback().isFallback());
    }

    @Test
    void missingFallbackInConfig_synthesizesDefaultAndWarns() {
        AlloyDefinition steel = new AlloyDefinition("steel", "Steel", List.of(), "#71797E",
                Set.of("iron", "coal"), BASELINE_STATS);
        AlloyRegistry registry = AlloyRegistry.fromDefinitions(List.of(steel), this::warn);

        assertEquals("fused_alloy", registry.fallback().id());
        assertFalse(warnings.isEmpty(), "a missing fallback recipe should warn");
    }

    // ---- Balance ceiling: a stat above netherite is clamped and warned --------------

    @Test
    void statAboveNetherite_isClampedToDiamondAndWarns() {
        AlloyStats overpowered = new AlloyStats(
                AlloyRegistry.NETHERITE_ATTACK_DAMAGE + 1.0, -2.6, 16, 1.0, 700, 12);
        AlloyDefinition busted = new AlloyDefinition("busted", "Busted Alloy", List.of(), "#000000",
                Set.of("iron", "gold"), overpowered);

        AlloyRegistry registry = AlloyRegistry.fromDefinitions(List.of(busted, fallback()), this::warn);

        AlloyDefinition clamped = registry.get("busted").orElseThrow();
        assertEquals(AlloyRegistry.DIAMOND_ATTACK_DAMAGE, clamped.stats().attackDamage());
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("busted"), "warning should name the alloy");
        assertTrue(warnings.get(0).contains("attack-damage"), "warning should name the stat");
        assertTrue(warnings.get(0).contains(String.valueOf(overpowered.attackDamage())),
                "warning should name the configured value");
    }

    @Test
    void statAtOrBelowNetherite_passesThroughUnclamped() {
        AlloyStats atCeiling = new AlloyStats(
                AlloyRegistry.NETHERITE_ATTACK_DAMAGE, -2.6, 16, 1.0, 700, 12);
        AlloyDefinition fine = new AlloyDefinition("fine", "Fine Alloy", List.of(), "#000000",
                Set.of("iron", "gold"), atCeiling);

        AlloyRegistry registry = AlloyRegistry.fromDefinitions(List.of(fine, fallback()), this::warn);

        assertEquals(AlloyRegistry.NETHERITE_ATTACK_DAMAGE, registry.get("fine").orElseThrow().stats().attackDamage());
        assertTrue(warnings.isEmpty());
    }

    @Test
    void multipleStatsAboveNetherite_areAllClampedAndEachWarned() {
        AlloyStats overpowered = new AlloyStats(
                AlloyRegistry.NETHERITE_ATTACK_DAMAGE + 1.0,
                -2.6,
                AlloyRegistry.NETHERITE_ARMOR,
                AlloyRegistry.NETHERITE_ARMOR_TOUGHNESS + 1.0,
                AlloyRegistry.NETHERITE_MAX_DURABILITY + 500,
                12);
        AlloyDefinition busted = new AlloyDefinition("busted", "Busted Alloy", List.of(), "#000000",
                Set.of("iron", "gold"), overpowered);

        AlloyRegistry registry = AlloyRegistry.fromDefinitions(List.of(busted, fallback()), this::warn);

        AlloyStats clamped = registry.get("busted").orElseThrow().stats();
        assertEquals(AlloyRegistry.DIAMOND_ATTACK_DAMAGE, clamped.attackDamage());
        assertEquals(AlloyRegistry.DIAMOND_ARMOR_TOUGHNESS, clamped.armorToughness());
        assertEquals(AlloyRegistry.DIAMOND_MAX_DURABILITY, clamped.maxDurability());
        assertEquals(3, warnings.size(), "attack-damage, armor-toughness, and max-durability should each warn once");
    }

    private static AlloyDefinition fallback() {
        return new AlloyDefinition("fused_alloy", "Fused Alloy", List.of(), "#4B4B4B", Set.of(), BASELINE_STATS);
    }
}
