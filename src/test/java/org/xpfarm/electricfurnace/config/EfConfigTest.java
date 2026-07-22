/*
 * ElectricFurnace - a redstone-powered smelter that recycles metal gear into ingots.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.electricfurnace.config;

import org.bukkit.configuration.MemoryConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link EfConfig#load}'s wiring -- specifically the one place where two keys
 * are validated against each other rather than in isolation: {@code recycling.slots} sets
 * the ceiling for {@code recycling.yield-remelt-alloy}.
 *
 * <p>{@link MemoryConfiguration} is a plain in-memory {@code ConfigurationSection} with
 * no server behind it, so these run as fast and as offline as the rest of the suite. It
 * is the only {@code org.bukkit} type here, and it is inert data.
 */
class EfConfigTest {

    private final List<String> warnings = new ArrayList<>();

    private void warn(String message) {
        warnings.add(message);
    }

    private static MemoryConfiguration config(Object... keyValuePairs) {
        MemoryConfiguration root = new MemoryConfiguration();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            root.set((String) keyValuePairs[i], keyValuePairs[i + 1]);
        }
        return root;
    }

    @Test
    void remeltYield_withinTheCeilingSetBySlots_isKept() {
        EfConfig loaded = EfConfig.load(
                config("recycling.slots", 5, "recycling.yield-remelt-alloy", 12), this::warn);

        assertEquals(5, loaded.recycling().slots());
        assertEquals(12, loaded.recycling().yieldRemeltAlloy());
        assertTrue(warnings.isEmpty(), () -> "expected no warnings, got: " + warnings);
    }

    @Test
    void remeltYield_pastTheCeilingSetBySlots_fallsBackToTheDefault() {
        // 5 slots x 13 = 65 ingots, one past a full stack.
        EfConfig loaded = EfConfig.load(
                config("recycling.slots", 5, "recycling.yield-remelt-alloy", 13), this::warn);

        assertEquals(1, loaded.recycling().yieldRemeltAlloy());
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("recycling.yield-remelt-alloy"));
        assertTrue(warnings.get(0).contains("recycling.slots"),
                () -> "warning must name the key that set the ceiling: " + warnings.get(0));
    }

    /**
     * The ceiling must track the <em>validated</em> slot count, not the raw one. A slots
     * value of 99 is itself out of range and falls back to 5, so the yield must be judged
     * against 5's ceiling of 12 -- not against 99's, which would admit anything.
     */
    @Test
    void remeltYield_isJudgedAgainstTheValidatedSlotCount_notTheRawOne() {
        EfConfig loaded = EfConfig.load(
                config("recycling.slots", 99, "recycling.yield-remelt-alloy", 13), this::warn);

        assertEquals(5, loaded.recycling().slots());
        assertEquals(1, loaded.recycling().yieldRemeltAlloy());
        assertEquals(2, warnings.size(), () -> "both keys should warn: " + warnings);
    }

    /** At one slot a batch is one item, so the yield may be a full stack. */
    @Test
    void remeltYield_atOneSlot_mayBeAFullStack() {
        EfConfig loaded = EfConfig.load(
                config("recycling.slots", 1, "recycling.yield-remelt-alloy", 64), this::warn);

        assertEquals(64, loaded.recycling().yieldRemeltAlloy());
        assertTrue(warnings.isEmpty(), () -> "expected no warnings, got: " + warnings);
    }

    /** At nine slots the ceiling drops to 7, so the previously-legal 8 is now refused. */
    @Test
    void remeltYield_atNineSlots_isCappedAtSeven() {
        EfConfig accepted = EfConfig.load(
                config("recycling.slots", 9, "recycling.yield-remelt-alloy", 7), this::warn);
        assertEquals(7, accepted.recycling().yieldRemeltAlloy());
        assertTrue(warnings.isEmpty(), () -> "expected no warnings, got: " + warnings);

        EfConfig refused = EfConfig.load(
                config("recycling.slots", 9, "recycling.yield-remelt-alloy", 8), this::warn);
        assertEquals(1, refused.recycling().yieldRemeltAlloy());
        assertEquals(1, warnings.size());
    }

    /** The shipping defaults must survive their own validation untouched. */
    @Test
    void shippingDefaults_loadWithoutWarnings() {
        EfConfig loaded = EfConfig.load(config(), this::warn);

        assertEquals(5, loaded.recycling().slots());
        assertEquals(3, loaded.recycling().yieldSameMetal());
        assertEquals(2, loaded.recycling().yieldMixedAlloy());
        assertEquals(1, loaded.recycling().yieldRemeltAlloy());
        assertTrue(warnings.isEmpty(), () -> "expected no warnings, got: " + warnings);
    }

    /**
     * The other two yield keys are flat per operation -- they do not multiply by the
     * input count, so the slot count must not constrain them.
     */
    @Test
    void flatYields_areNotConstrainedByTheSlotCount() {
        EfConfig loaded = EfConfig.load(config(
                "recycling.slots", 9,
                "recycling.yield-same-metal", 64,
                "recycling.yield-mixed-alloy", 64), this::warn);

        assertEquals(64, loaded.recycling().yieldSameMetal());
        assertEquals(64, loaded.recycling().yieldMixedAlloy());
        assertTrue(warnings.isEmpty(), () -> "expected no warnings, got: " + warnings);
    }
}
