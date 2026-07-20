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
 * The machine's rules about its own contents: what counts as fuel, and whether the
 * output slot can accept what a run would produce.
 *
 * <p>Both are pure functions over an {@link ItemStack}, shared by {@link MachineTicker}
 * (deciding a tick's {@code Conditions}) and by the GUI (drawing the status indicator).
 * They live with the machine rather than the view because the machine is what they
 * describe -- the GUI merely renders their consequences.
 */
public final class MachineRules {

    private MachineRules() {
    }

    /**
     * Whether {@code fuel} counts as fuel at all: any amount of redstone. One dust buys
     * burn time; there is no per-operation quantity.
     */
    public static boolean hasFuel(ItemStack fuel) {
        return fuel != null && fuel.getType() == Material.REDSTONE && fuel.getAmount() > 0;
    }

    /** What the output slot currently holds, relative to the item an operation would produce. */
    public enum OutputSlotState {
        /** The output slot is empty. */
        EMPTY,
        /** The output slot holds an item that matches what would be produced, with room to merge. */
        SAME_ITEM,
        /** The output slot holds something else -- or the same item with no room left -- blocking the run. */
        DIFFERENT_ITEM
    }

    /**
     * Compares the output slot's current contents against what an operation would
     * produce. Blocks the run (returns {@link OutputSlotState#DIFFERENT_ITEM}) not
     * only when a genuinely different item occupies the slot, but also when merging
     * would exceed the max stack size -- an overflowing stack is exactly the kind of
     * silent corruption this plugin must never cause.
     */
    public static OutputSlotState classifyOutputSlot(ItemStack current, ItemStack candidate) {
        if (current == null || current.getType() == Material.AIR) {
            if (candidate == null) {
                return OutputSlotState.DIFFERENT_ITEM;
            }
            // An empty slot is only usable if the candidate itself fits in one stack.
            if (candidate.getAmount() > candidate.getMaxStackSize()) {
                return OutputSlotState.DIFFERENT_ITEM;
            }
            return OutputSlotState.EMPTY;
        }
        if (candidate == null || !current.isSimilar(candidate)) {
            return OutputSlotState.DIFFERENT_ITEM;
        }
        if (current.getAmount() + candidate.getAmount() > current.getMaxStackSize()) {
            return OutputSlotState.DIFFERENT_ITEM;
        }
        return OutputSlotState.SAME_ITEM;
    }
}
