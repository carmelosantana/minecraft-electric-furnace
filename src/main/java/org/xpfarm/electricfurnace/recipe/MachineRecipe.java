/*
 * ElectricFurnace - a redstone-powered smelter that recycles metal gear into ingots.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.electricfurnace.recipe;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.ShapedRecipe;
import org.xpfarm.electricfurnace.item.MachineItemFactory;
import org.xpfarm.electricfurnace.item.MaterialContract;

/**
 * Registers the shaped crafting recipe for the Electric Furnace machine item, and
 * enforces {@code electricfurnace.craft} on it.
 *
 * <pre>
 *   C C C     C = COPPER_INGOT
 *   R F R     R = REDSTONE_BLOCK
 *   C C C     F = BLAST_FURNACE
 * </pre>
 *
 * <p><b>Why {@link #unregister()} exists.</b> {@code Bukkit.addRecipe} throws on a
 * duplicate {@link NamespacedKey}, and recipes survive a {@code /reload} of the
 * plugin. Registration therefore always removes the key first, so re-enabling never
 * fails startup -- consistent with this plugin's rule that nothing in the enable path
 * may throw.
 *
 * <p>The permission gate lives in {@link #onPrepareCraft}: the recipe is registered
 * for everyone (it must be, for the server to know it exists), but the result is
 * blanked for a player without {@code electricfurnace.craft}. Blanking the result is
 * the correct hook -- it cannot consume ingredients, unlike cancelling later in the
 * craft.
 */
public final class MachineRecipe implements Listener {

    /** The recipe's stable key. */
    public static final NamespacedKey KEY = new NamespacedKey("electricfurnace", "electric_furnace");

    /**
     * The permission node gating every craft this plugin registers.
     *
     * <p>Public and shared with {@link GearRecipes} deliberately: two handlers enforcing
     * the same node from two copies of the same string literal is one typo away from an
     * operator revoking the node and finding half the recipes still work.
     */
    public static final String CRAFT_PERMISSION = "electricfurnace.craft";

    /** Builds the shaped recipe. Pure construction -- registers nothing. */
    public static ShapedRecipe build() {
        ShapedRecipe recipe = new ShapedRecipe(KEY, MachineItemFactory.create());
        recipe.shape("CCC", "RFR", "CCC");
        recipe.setIngredient('C', Material.COPPER_INGOT);
        recipe.setIngredient('R', Material.REDSTONE_BLOCK);
        recipe.setIngredient('F', Material.BLAST_FURNACE);
        return recipe;
    }

    /**
     * Registers the recipe, replacing any previous registration under the same key.
     * Never throws: a recipe failure must not prevent the plugin from enabling.
     *
     * @return {@code true} if the recipe is now registered
     */
    public static boolean register() {
        try {
            Bukkit.removeRecipe(KEY);
            return Bukkit.addRecipe(build());
        } catch (Throwable t) {
            Bukkit.getLogger().warning("ElectricFurnace: could not register the crafting recipe ("
                    + t.getClass().getName() + ": " + t.getMessage()
                    + "). The machine item is still obtainable via /electricfurnace give.");
            return false;
        }
    }

    /** Removes the recipe. Never throws. */
    public static void unregister() {
        try {
            Bukkit.removeRecipe(KEY);
        } catch (Throwable t) {
            Bukkit.getLogger().warning("ElectricFurnace: could not remove the crafting recipe ("
                    + t.getClass().getName() + ": " + t.getMessage() + ").");
        }
    }

    /**
     * Blanks the crafting result for anyone lacking {@code electricfurnace.craft}.
     *
     * <p>Identification is by {@link MaterialContract} PDC, never by display name or
     * lore substring -- a hard rule of this plugin's item contract, and the only check
     * that cannot be spoofed by a renamed item.
     *
     * <p>The permission check is against {@link org.bukkit.inventory.InventoryView#getPlayer()}
     * -- the crafter -- not {@code event.getViewers()}. A crafting table's viewer list
     * can be empty (nobody but the crafter is looking at their own personal 2x2, and
     * even a workbench GUI's viewer list is not guaranteed non-empty at this point in
     * the event), and {@code Stream.allMatch} on an empty stream is vacuously
     * {@code true} -- silently permitting the craft for everyone once no one happens to
     * be in the viewer list, instead of correctly denying it.
     */
    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        Recipe recipe = event.getRecipe();
        if (recipe == null) {
            return;
        }
        ItemStack result = recipe.getResult();
        if (!MaterialContract.isMachine(result)) {
            return;
        }
        boolean permitted = event.getView().getPlayer().hasPermission(CRAFT_PERMISSION);
        if (!permitted) {
            event.getInventory().setResult(null);
        }
    }
}
