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

/**
 * One machine's memo of what its current inputs resolve to, so {@link MachineTicker}
 * does not re-run recipe resolution -- and rebuild the candidate {@code ItemStack},
 * {@code ItemMeta} and PDC included -- twenty times a second for inputs that have not
 * changed.
 *
 * <p><b>Validity is decided by re-reading the inputs, not by call sites remembering to
 * invalidate.</b> {@link MachineState#inputs()} hands out the live array, and several
 * unrelated paths write to it: the GUI folding a player's click back into state, the
 * ticker consuming one item per slot on completion, hydration from the block's PDC. A
 * cache that depended on each of those calling an {@code invalidate()} would be one
 * forgotten call away from a machine smelting an item its inputs no longer contain.
 * {@link #isValidFor} instead compares a cheap fingerprint of the slots -- stack
 * identity, {@link Material}, and amount -- so a path that mutates inputs without
 * telling anyone still gets a fresh resolution. The one mutation this cannot see is an
 * {@code ItemMeta} edited in place on a retained stack whose type and amount are
 * unchanged; nothing in this plugin does that.
 *
 * <p>The recycling settings and alloy registry are part of the fingerprint too, by
 * identity: {@code /electricfurnace reload} swaps both objects, and the resolution
 * depends on them as much as on the inputs.
 *
 * <p>Not thread-safe, and does not need to be: like the {@link MachineState} that owns
 * it, it is touched only on the Bukkit main thread.
 */
final class RecipeCache {

    private boolean populated;

    private final ItemStack[] stacks = new ItemStack[MachineState.INPUT_COUNT];
    private final Material[] materials = new Material[MachineState.INPUT_COUNT];
    private final int[] amounts = new int[MachineState.INPUT_COUNT];
    private Object recycling;
    private Object alloys;

    private boolean recipeValid;
    private ItemStack candidate;

    /**
     * Whether a stored resolution still describes {@code inputs} under
     * {@code recycling}/{@code alloys}. Cheap enough to call every tick for every
     * machine: five reference comparisons and two int reads, no allocation.
     */
    boolean isValidFor(ItemStack[] inputs, Object recycling, Object alloys) {
        if (!populated || this.recycling != recycling || this.alloys != alloys) {
            return false;
        }
        for (int i = 0; i < stacks.length; i++) {
            ItemStack current = i < inputs.length ? inputs[i] : null;
            if (current != stacks[i]) {
                return false;
            }
            if (current != null && (current.getType() != materials[i] || current.getAmount() != amounts[i])) {
                return false;
            }
        }
        return true;
    }

    /**
     * Stores a fresh resolution, fingerprinting {@code inputs} as it stands now.
     *
     * @param candidate the item a completed run would deposit, or {@code null} when
     *                  {@code recipeValid} is {@code false}
     */
    void store(ItemStack[] inputs, Object recycling, Object alloys, boolean recipeValid, ItemStack candidate) {
        for (int i = 0; i < stacks.length; i++) {
            ItemStack current = i < inputs.length ? inputs[i] : null;
            stacks[i] = current;
            materials[i] = current == null ? null : current.getType();
            amounts[i] = current == null ? 0 : current.getAmount();
        }
        this.recycling = recycling;
        this.alloys = alloys;
        this.recipeValid = recipeValid;
        this.candidate = candidate;
        this.populated = true;
    }

    /** Whether the cached inputs resolve to something smeltable. */
    boolean recipeValid() {
        return recipeValid;
    }

    /**
     * The item a completed run would deposit, or {@code null} if the inputs resolve to
     * nothing. <b>Owned by this cache</b> -- a caller that intends to hand it to a
     * machine's output slot must clone it first, or the next completion will mutate the
     * amount the cache is still holding.
     */
    ItemStack candidate() {
        return candidate;
    }
}
