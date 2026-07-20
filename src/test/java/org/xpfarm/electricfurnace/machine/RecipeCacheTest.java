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

import org.bukkit.Material;
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

    // ---- slotMatches: the per-slot decision, truth-tabled ---------------------------
    //
    // Every test above passes all-null inputs, so isValidFor's loop could only ever reach
    // its "both sides empty" branch. Deleting the material/amount comparison AND the
    // isSimilar comparison left all of them green -- which means the comparisons that
    // actually invalidate the cache on an input change had no coverage at all. That is a
    // correctness hole, not a performance one: a fingerprint that never notices an input
    // change keeps a machine smelting a recipe its inputs no longer contain.
    //
    // slotMatches is stated over Material (an enum), int and boolean precisely so it can
    // be exercised headlessly -- the same reason MetalClassifier is split that way. See
    // that class's test for the constraint: a real ItemStack cannot be constructed
    // without a running server.

    @Test
    void slotMatches_bothSlotsEmpty_matches() {
        assertTrue(RecipeCache.slotMatches(null, 0, null, 0, false));
    }

    @Test
    void slotMatches_storedEmptyAndCurrentOccupied_doesNotMatch() {
        // A player put something into a slot that was empty when the recipe resolved.
        assertFalse(RecipeCache.slotMatches(null, 0, Material.IRON_SWORD, 1, true));
    }

    @Test
    void slotMatches_storedOccupiedAndCurrentEmpty_doesNotMatch() {
        // A player took the last item out -- or the ticker consumed it on completion.
        assertFalse(RecipeCache.slotMatches(Material.IRON_SWORD, 1, null, 0, true));
    }

    @Test
    void slotMatches_sameMaterialAndAmountAndSimilar_matches() {
        assertTrue(RecipeCache.slotMatches(Material.IRON_SWORD, 3, Material.IRON_SWORD, 3, true));
    }

    @Test
    void slotMatches_sameMaterialDifferentAmount_doesNotMatch() {
        // The overwhelmingly common real change: a player added or removed items, or the
        // completion step consumed one. The amount is part of the resolution -- it decides
        // how many ingots come out -- so a cache that ignored it would deposit the wrong
        // quantity. `similar` is true here so only the amount comparison can reject it.
        assertFalse(RecipeCache.slotMatches(Material.IRON_SWORD, 3, Material.IRON_SWORD, 2, true));
        assertFalse(RecipeCache.slotMatches(Material.IRON_SWORD, 2, Material.IRON_SWORD, 3, true));
    }

    @Test
    void slotMatches_differentMaterial_doesNotMatch() {
        // similar is true so that only the material comparison can reject this: a cache
        // that dropped it would resolve gold gear as if it were still iron.
        assertFalse(RecipeCache.slotMatches(Material.IRON_SWORD, 1, Material.GOLDEN_SWORD, 1, true));
    }

    @Test
    void slotMatches_sameMaterialAndAmountButNotSimilar_doesNotMatch() {
        // The case the cheap tests cannot see: two stacks differing only in ItemMeta,
        // damage, or PDC -- a plain iron sword versus one another plugin has tagged.
        // MetalClassifier discriminates on exactly that, so reusing the resolution here
        // would smelt the wrong metal.
        assertFalse(RecipeCache.slotMatches(Material.IRON_SWORD, 1, Material.IRON_SWORD, 1, false));
    }

    @Test
    void slotMatches_similarIsIgnoredWhenEitherSlotIsEmpty() {
        // isValidFor never evaluates isSimilar for an empty slot, so it passes false
        // there; the empty/empty case must not be rejected because of it.
        assertTrue(RecipeCache.slotMatches(null, 0, null, 0, false));
        assertFalse(RecipeCache.slotMatches(null, 0, Material.IRON_SWORD, 1, false));
    }

    @Test
    void slotMatches_amountIsComparedEvenWhenBothAreZero() {
        // Guards the degenerate encoding: an occupied slot never has amount 0, but the
        // rule must not depend on that to reach a decision.
        assertTrue(RecipeCache.slotMatches(Material.IRON_SWORD, 0, Material.IRON_SWORD, 0, true));
    }
}
