/*
 * ElectricFurnace - a redstone-powered smelter that recycles metal gear into ingots.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.electricfurnace.machine;

import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the parts of {@link RecipeCache}'s validity rule that can be exercised without
 * a running server -- i.e. everything except a slot holding a real {@code ItemStack},
 * which cannot be constructed headlessly. The rule that matters most here is the
 * default: an unpopulated cache is never valid, so a missed {@code store} degrades to
 * "resolve again," never to "reuse a stale answer."
 */
class RecipeCacheTest {

    private static ItemStack[] emptyInputs() {
        return new ItemStack[MachineState.INPUT_COUNT];
    }

    @Test
    void freshCache_isNeverValid() {
        assertFalse(new RecipeCache().isValidFor(emptyInputs(), new Object(), new Object()));
    }

    @Test
    void afterStore_isValidForTheSameInputsAndConfig() {
        RecipeCache cache = new RecipeCache();
        Object recycling = new Object();
        Object alloys = new Object();
        ItemStack[] inputs = emptyInputs();

        cache.store(inputs, recycling, alloys, false, null);

        assertTrue(cache.isValidFor(inputs, recycling, alloys));
    }

    @Test
    void reloadedRecyclingSettings_invalidateTheCache() {
        RecipeCache cache = new RecipeCache();
        Object alloys = new Object();
        cache.store(emptyInputs(), new Object(), alloys, false, null);

        // /electricfurnace reload swaps the settings object; the resolution depended on it.
        assertFalse(cache.isValidFor(emptyInputs(), new Object(), alloys));
    }

    @Test
    void reloadedAlloyRegistry_invalidatesTheCache() {
        RecipeCache cache = new RecipeCache();
        Object recycling = new Object();
        cache.store(emptyInputs(), recycling, new Object(), false, null);

        assertFalse(cache.isValidFor(emptyInputs(), recycling, new Object()));
    }

    @Test
    void storedResolution_isReadBackUnchanged() {
        RecipeCache cache = new RecipeCache();
        cache.store(emptyInputs(), new Object(), new Object(), false, null);

        assertFalse(cache.recipeValid());
        assertTrue(cache.candidate() == null);
    }

    @Test
    void aDifferentButEquallyEmptyInputArray_isStillTheSameFingerprint() {
        // The fingerprint is over the slots' contents, not the array's identity: the
        // ticker passes state.inputs() every tick, but nothing should depend on that.
        RecipeCache cache = new RecipeCache();
        Object recycling = new Object();
        Object alloys = new Object();
        cache.store(emptyInputs(), recycling, alloys, false, null);

        assertTrue(cache.isValidFor(emptyInputs(), recycling, alloys));
    }
}
