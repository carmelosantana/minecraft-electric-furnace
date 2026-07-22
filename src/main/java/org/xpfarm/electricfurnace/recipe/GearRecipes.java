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
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.xpfarm.electricfurnace.alloy.AlloyDefinition;
import org.xpfarm.electricfurnace.alloy.AlloyRegistry;
import org.xpfarm.electricfurnace.gear.GearPiece;
import org.xpfarm.electricfurnace.item.AlloyItemFactory;
import org.xpfarm.electricfurnace.item.GearItemFactory;
import org.xpfarm.electricfurnace.item.MaterialContract;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Registers the crafting-table recipes for alloy gear, and guards them.
 *
 * <p>Shapes are vanilla, with the alloy ingot substituted for the metal. Ingredients
 * use {@link RecipeChoice.ExactChoice}, which the server matches with
 * {@code isSameItemSameComponents} -- the full data-component map, including the
 * {@code minecraft:custom_data} where PDC lives. A vanilla netherite ingot therefore
 * cannot satisfy these recipes on Java.
 *
 * <p><b>Ingredients must come from {@link AlloyItemFactory}.</b> {@code ExactChoice}
 * snapshots the ingredient stack at registration time and compares it whole, so an
 * ingot built locally here that differed from a minted one by so much as a lore line
 * would produce recipes that silently never match, with no error anywhere.
 *
 * <p><b>Bedrock.</b> Bedrock's recipe ingredient format carries no NBT at all, so
 * every one of these recipes reaches a Bedrock client as "netherite_ingot xN + stick".
 * Real crafting still works -- Geyser synthesizes a one-off recipe from the actual
 * grid contents once the Java server produces a result -- but a Bedrock player can lay
 * out <em>vanilla</em> netherite ingots in one of these shapes and see a phantom
 * output. {@link #onPrepareCraft} is what makes that phantom fail safe: it blanks the
 * result unless the grid genuinely holds stamped ingots, so the worst case is that
 * nothing happens, never a free item.
 *
 * <p>Registration always removes the key first, like {@link MachineRecipe}, because
 * {@code Bukkit.addRecipe} throws on a duplicate {@link NamespacedKey} and recipes
 * survive a plugin reload -- and nothing in the enable path may throw. The removal is
 * per key inside {@link #registerOne}, not only the tracked sweep in
 * {@link #unregister()}: after a {@code /reload} this instance's tracking list starts
 * empty while the server still holds the previous instance's recipes, so tracking
 * alone would not clear the way.
 */
public final class GearRecipes implements Listener {

    private final Supplier<AlloyRegistry> alloysSupplier;
    private final Consumer<String> warn;
    private final List<NamespacedKey> registered = new ArrayList<>();

    public GearRecipes(Supplier<AlloyRegistry> alloysSupplier, Consumer<String> warn) {
        this.alloysSupplier = Objects.requireNonNull(alloysSupplier, "alloysSupplier");
        this.warn = Objects.requireNonNull(warn, "warn");
    }

    /** The recipe key for one alloy and piece, e.g. {@code electricfurnace:steel_sword}. */
    public static NamespacedKey keyFor(String alloyId, GearPiece piece) {
        return new NamespacedKey("electricfurnace", alloyId + "_" + piece.id());
    }

    /** Registers all 30 recipes, replacing any already present under the same keys. */
    public void register() {
        unregister();
        for (AlloyDefinition definition : alloysSupplier.get().all()) {
            for (GearPiece piece : GearPiece.values()) {
                registerOne(definition, piece);
            }
        }
    }

    private void registerOne(AlloyDefinition definition, GearPiece piece) {
        try {
            Optional<ItemStack> result = GearItemFactory.create(definition, piece);
            if (result.isEmpty()) {
                warn.accept("Alloy '" + definition.id() + "' has no " + piece.id()
                        + " on this server (base '" + definition.base().id()
                        + "' is unavailable); skipping that recipe.");
                return;
            }

            NamespacedKey key = keyFor(definition.id(), piece);
            ShapedRecipe recipe = new ShapedRecipe(key, result.get());
            recipe.shape(shapeFor(piece));
            recipe.setIngredient('I', new RecipeChoice.ExactChoice(AlloyItemFactory.create(definition)));
            if (piece.stickCost() > 0) {
                recipe.setIngredient('S', new RecipeChoice.MaterialChoice(Material.STICK));
            }

            // Unconditional, not only via unregister(): a key left behind by a previous
            // plugin instance is not in this instance's tracking list, and addRecipe
            // throws on a duplicate.
            Bukkit.removeRecipe(key);
            Bukkit.addRecipe(recipe);
            registered.add(key);
        } catch (Throwable failure) {
            // One bad alloy must not cost the other four their gear.
            warn.accept("Failed to register the " + piece.id() + " recipe for alloy '"
                    + definition.id() + "': " + failure.getMessage());
        }
    }

    /**
     * Vanilla shapes; {@code I} is the alloy ingot, {@code S} a plain stick.
     *
     * <p>Each shape's {@code I} count is {@link GearPiece#ingotCost()} and its {@code S}
     * count is {@link GearPiece#stickCost()}; {@code S} appears only in the two shapes
     * whose stick cost is non-zero, because {@code setIngredient} rejects a character
     * absent from the shape and a shape character with no ingredient is a null
     * ingredient at add time.
     */
    private static String[] shapeFor(GearPiece piece) {
        return switch (piece) {
            case SWORD -> new String[] {"I", "I", "S"};
            case AXE -> new String[] {"II", "IS", " S"};
            case HELMET -> new String[] {"III", "I I"};
            case CHESTPLATE -> new String[] {"I I", "III", "III"};
            case LEGGINGS -> new String[] {"III", "I I", "I I"};
            case BOOTS -> new String[] {"I I", "I I"};
        };
    }

    /**
     * Removes every recipe this instance registered.
     *
     * <p>The tracking list is cleared unconditionally, and a failure on one key is
     * warned rather than thrown: {@link #register()} calls this first, and Task 11's
     * config reload calls {@code register()} again, so a key that refused to be removed
     * must not leave a stale entry behind to be re-removed forever.
     */
    public void unregister() {
        for (NamespacedKey key : registered) {
            try {
                Bukkit.removeRecipe(key);
            } catch (Throwable failure) {
                warn.accept("Failed to remove the gear recipe '" + key + "': " + failure.getMessage());
            }
        }
        registered.clear();
    }

    /**
     * Blanks the craft result unless every ingot in the grid is a genuine alloy ingot.
     *
     * <p>A backstop, not the primary matcher: {@code ExactChoice} already rejects
     * correctly on Java. This exists so the Bedrock false-positive path -- where the
     * client matches a type-only recipe against vanilla netherite ingots -- cannot
     * become an item duplication path. Blanking the result is the correct hook because
     * it cannot consume ingredients, unlike cancelling later in the craft.
     *
     * <p>The key check comes first and is against {@link #registered}, so this handler
     * can only ever blank a recipe this instance itself added -- never a vanilla recipe
     * and never another plugin's.
     */
    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        if (!(event.getRecipe() instanceof Keyed keyed) || !registered.contains(keyed.getKey())) {
            return;
        }
        for (ItemStack ingredient : event.getInventory().getMatrix()) {
            if (ingredient == null || ingredient.getType() == Material.AIR
                    || ingredient.getType() == Material.STICK) {
                continue;
            }
            if (MaterialContract.readCustomMaterial(ingredient).isEmpty()) {
                event.getInventory().setResult(null);
                return;
            }
        }
    }

    /**
     * Unlocks these recipes so they appear in the recipe book.
     *
     * <p>Required, not cosmetic: since 1.21.2 the server sends only recipes the player
     * has unlocked, and {@code Bukkit.addRecipe} unlocks nothing. Without this the
     * recipe book is empty on <b>both</b> editions.
     */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        for (NamespacedKey key : registered) {
            try {
                player.discoverRecipe(key);
            } catch (Throwable failure) {
                warn.accept("Failed to unlock the gear recipe '" + key + "' for "
                        + player.getName() + ": " + failure.getMessage());
            }
        }
    }
}
