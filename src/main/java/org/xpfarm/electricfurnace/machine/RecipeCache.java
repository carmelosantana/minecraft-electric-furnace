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
 * invalidate.</b> Several unrelated paths change a machine's input slots: a player's
 * click landing directly in the shared inventory, the ticker consuming one item per slot
 * on completion, hydration from the block's PDC. A cache that depended on each of those
 * calling an {@code invalidate()} would be one forgotten call away from a machine
 * smelting an item its inputs no longer contain. {@link #isValidFor} instead compares a
 * fingerprint of the slots, so a path that changes inputs without telling anyone still
 * gets a fresh resolution.
 *
 * <p><b>The fingerprint compares values, not stack identity.</b> It once compared the
 * {@code ItemStack} references themselves, which was sound while {@link MachineState}
 * held its own array and handed out the very objects it stored. It no longer does:
 * slots now live in a Bukkit {@link org.bukkit.inventory.Inventory}, and
 * {@code Inventory#getItem} is specified to return <em>a</em> stack for the slot, not
 * <em>the</em> stack -- CraftBukkit builds a fresh wrapper on every call. An identity
 * comparison against that would fail on every tick, leaving the cache permanently
 * invalid and re-resolving every recipe twenty times a second: not incorrect, but the
 * exact cost this class exists to avoid, and silently so.
 *
 * <p>So each slot is fingerprinted by {@link Material}, amount, and
 * {@link ItemStack#isSimilar} against a stored clone. That is strictly <em>stronger</em>
 * than the identity rule it replaces: identity could not tell apart two different stacks
 * of the same material and amount carrying different {@code ItemMeta} (a plain iron
 * sword versus one another plugin has tagged in its PDC, which
 * {@code MetalClassifier} resolves differently), and would happily reuse a resolution
 * for the wrong one. {@code isSimilar} compares the metadata, so it does not.
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
     * {@code recycling}/{@code alloys}.
     *
     * <p>Ordered cheapest-test-first so the expensive comparison is reached only by a
     * slot that already matches on both of the cheap ones: two reference comparisons and
     * a null check reject a reloaded config or an emptied slot outright, {@link Material}
     * and amount reject the overwhelmingly common real change (a player adding, removing,
     * or swapping items), and {@link ItemStack#isSimilar} -- the only part that inspects
     * metadata -- runs at most once per occupied slot per tick, on the path where the
     * answer is "still valid, do not re-resolve."
     */
    boolean isValidFor(ItemStack[] inputs, Object recycling, Object alloys) {
        if (!populated || this.recycling != recycling || this.alloys != alloys) {
            return false;
        }
        for (int i = 0; i < stacks.length; i++) {
            ItemStack current = i < inputs.length ? inputs[i] : null;
            ItemStack stored = stacks[i];

            // isSimilar is evaluated only for a slot that already matches on both cheap
            // tests -- the && chain is what preserves the cheapest-test-first ordering
            // now that the decision itself lives in slotMatches. When either side is
            // empty, `similar` is false and unused: slotMatches settles those cases on
            // the material nullity alone.
            boolean similar = current != null && stored != null
                    && current.getType() == materials[i]
                    && current.getAmount() == amounts[i]
                    && stored.isSimilar(current);

            if (!slotMatches(materials[i], amounts[i],
                    current == null ? null : current.getType(),
                    current == null ? 0 : current.getAmount(),
                    similar)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The per-slot half of {@link #isValidFor}, as a pure function of values so it can be
     * truth-tabled without a running server.
     *
     * <p>Split out deliberately. Every input this class's tests can build headlessly is
     * an empty slot, so the whole-object {@code isValidFor} could only ever exercise its
     * "both sides empty" branch: deleting the material/amount comparison <em>and</em> the
     * {@code isSimilar} comparison left the entire suite green. The failure that hides
     * behind that is not a slow cache, it is a wrong one -- a fingerprint that never
     * notices an input change keeps a machine smelting a recipe its inputs no longer
     * contain, and deposits the wrong item. Stated over {@link Material} (an enum),
     * {@code int}, and {@code boolean}, the decision is testable; {@link #isValidFor}
     * delegates so the tested function is the one that ships.
     *
     * @param storedMaterial  the material fingerprinted at {@code store} time, or
     *                        {@code null} if that slot was empty
     * @param storedAmount    the amount fingerprinted at {@code store} time
     * @param currentMaterial the material in the slot now, or {@code null} if empty
     * @param currentAmount   the amount in the slot now
     * @param similar         whether the stored clone {@link ItemStack#isSimilar} the
     *                        current stack -- metadata included. Same material and amount
     *                        is <em>not</em> sufficient: a plain iron sword and one another
     *                        plugin has tagged in its PDC are indistinguishable on the
     *                        cheap tests, and {@code MetalClassifier} resolves them to
     *                        different metals.
     * @return whether the slot's contribution to the fingerprint is unchanged
     */
    static boolean slotMatches(Material storedMaterial, int storedAmount,
                               Material currentMaterial, int currentAmount, boolean similar) {
        if (storedMaterial == null || currentMaterial == null) {
            // Both empty is a match; one empty and one occupied is a real change.
            return storedMaterial == currentMaterial;
        }
        return storedMaterial == currentMaterial && storedAmount == currentAmount && similar;
    }

    /**
     * Stores a fresh resolution, fingerprinting {@code inputs} as it stands now.
     *
     * <p>Each occupied slot is cloned. The stack handed in belongs to a Bukkit
     * inventory, which may hand out a different wrapper -- or a genuinely different
     * object -- on the next read, and may have its amount changed underneath us by the
     * ticker's own completion step; a clone is a stable value to compare against.
     *
     * @param candidate the item a completed run would deposit, or {@code null} when
     *                  {@code recipeValid} is {@code false}
     */
    void store(ItemStack[] inputs, Object recycling, Object alloys, boolean recipeValid, ItemStack candidate) {
        for (int i = 0; i < stacks.length; i++) {
            ItemStack current = i < inputs.length ? inputs[i] : null;
            stacks[i] = current == null ? null : current.clone();
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
