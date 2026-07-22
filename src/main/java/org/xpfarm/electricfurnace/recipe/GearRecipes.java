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
 * output. That phantom fails safe on the server side without any help from this class:
 * {@code ExactChoice} matches on the whole data-component map, so the Java server
 * matches no recipe at all, produces no result, and has nothing to hand over when the
 * client clicks. The phantom is a client-side rendering artifact, not a live craft.
 *
 * <p>{@link #onPrepareCraft} therefore exists for other reasons entirely -- a
 * permission gate and an anti-laundering guard -- described in its own javadoc.
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
            // Removed unconditionally, and BEFORE the buildability check below, for two
            // separate reasons. Unconditionally, not only via unregister(): a key left
            // behind by a previous plugin instance is not in this instance's tracking
            // list, and addRecipe throws on a duplicate. Before the check: a piece that
            // has BECOME unbuildable -- an operator moves an alloy's base from
            // netherite to copper on a server without copper equipment, then runs
            // /reload confirm -- must not keep the previous instance's recipe alive,
            // untracked and still craftable for its old result while the log claims the
            // recipe was skipped. Clearing the key first makes "skipped" mean skipped.
            NamespacedKey key = keyFor(definition.id(), piece);
            Bukkit.removeRecipe(key);

            Optional<ItemStack> result = GearItemFactory.create(definition, piece);
            if (result.isEmpty()) {
                warn.accept("Alloy '" + definition.id() + "' has no " + piece.id()
                        + " on this server (base '" + definition.base().id()
                        + "' is unavailable); skipping that recipe.");
                return;
            }

            ShapedRecipe recipe = new ShapedRecipe(key, result.get());
            recipe.shape(shapeFor(piece));
            recipe.setIngredient('I', new RecipeChoice.ExactChoice(AlloyItemFactory.create(definition)));
            if (piece.stickCost() > 0) {
                recipe.setIngredient('S', new RecipeChoice.MaterialChoice(Material.STICK));
            }

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
     * Guards the crafting grid in both directions, by blanking the result.
     *
     * <p>Blanking is the right hook for both arms because it cannot consume
     * ingredients, unlike cancelling later in the craft. Nothing here can destroy an
     * item; the worst it can do is decline to produce one.
     *
     * <p><b>One of ours ({@link #registered} holds the recipe key): the crafter must
     * hold {@link MachineRecipe#CRAFT_PERMISSION}.</b> The spec gates all crafting on
     * that node and {@link MachineRecipe} already enforced it for the machine item, so
     * an operator revoking it to stop a player crafting would otherwise have found all
     * 30 gear recipes still working. The check is against
     * {@link org.bukkit.inventory.InventoryView#getPlayer()} -- the crafter -- for the
     * same reason {@link MachineRecipe} uses it: a crafting inventory's viewer list can
     * be empty, and an {@code allMatch} over no viewers permits the craft for everyone.
     *
     * <p><b>One of ours: every non-stick ingredient must be stamped.</b> A redundant
     * backstop in practice -- {@code ExactChoice} has already validated every ingredient
     * by the time this runs, so this branch cannot currently fire. It is kept as a cheap
     * invariant check that would catch an ingredient switched away from
     * {@code ExactChoice} in future.
     *
     * <p><b>Anyone else's recipe: no stamped ingredient may be consumed at all.</b>
     * This is the arm that does real work, and it closes two live leaks:
     *
     * <ul>
     *   <li><b>Laundering.</b> Alloy ingots are netherite ingots underneath, and
     *       vanilla recipes match on item type while ignoring components. Nine alloy
     *       ingots in a 3x3 make a real {@code minecraft:netherite_block}, which breaks
     *       back down into nine <em>vanilla</em> netherite ingots. The same shape exists
     *       for the smithing-template duplication recipe. At the shipped
     *       {@code yield-mixed-alloy: 2} that is about five recycler runs for nine
     *       netherite ingots -- worth more laundered than crafted into gear.</li>
     *   <li><b>Identity loss.</b> Vanilla {@code minecraft:repair_item} takes two
     *       damaged items of the same type and assembles a fresh {@code ItemStack}
     *       carrying only merged enchantments. Two damaged Steel Swords would come back
     *       as one plain iron sword: PDC, name, lore, derived attribute modifiers and
     *       the raised max durability all silently gone, from an action a player
     *       reasonably reads as maintenance. Anvil repair is unaffected -- it preserves
     *       components and PDC -- so gear still has a repair path.</li>
     * </ul>
     *
     * <p>The rule is blanket rather than a list of known-bad recipe keys, because the
     * failure mode of an incomplete list is item laundering, while the failure mode of
     * over-blanking is a craft that does not happen. No vanilla crafting-grid recipe
     * legitimately consumes one of these items: every vanilla recipe reachable from an
     * alloy ingot or a piece of alloy gear either launders it or strips its identity.
     *
     * <p>The stamp test is on the {@code xpfarm:custom_material} <b>value</b>, not on
     * the key merely being present. That namespace is a shared cross-plugin contract: an
     * item minted by a sibling {@code xpfarm:} plugin is that plugin's to protect, and
     * blanking crafts over it here would be this plugin overreaching.
     *
     * <p><b>Known trade-off.</b> This would also blank a recipe another plugin
     * deliberately registered to consume an alloy ingot. None exists today, and the
     * asymmetry is intentional -- an unwanted blank is a craft that does not happen,
     * whereas the leak it prevents is permanent.
     */
    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        boolean ourRecipe = event.getRecipe() instanceof Keyed keyed && registered.contains(keyed.getKey());
        if (ourRecipe && !event.getView().getPlayer().hasPermission(MachineRecipe.CRAFT_PERMISSION)) {
            event.getInventory().setResult(null);
            return;
        }
        for (ItemStack ingredient : event.getInventory().getMatrix()) {
            if (ingredient == null || ingredient.getType() == Material.AIR) {
                continue;
            }
            boolean stamped = isElectricFurnaceItem(ingredient);
            boolean refuse = ourRecipe
                    ? !stamped && ingredient.getType() != Material.STICK
                    : stamped;
            if (refuse) {
                event.getInventory().setResult(null);
                return;
            }
        }
    }

    /** Whether {@code stack} was minted by this plugin, by owning-system value rather than key presence. */
    private static boolean isElectricFurnaceItem(ItemStack stack) {
        return MaterialContract.readCustomMaterial(stack)
                .filter(MaterialContract.OWNING_SYSTEM_ELECTRICFURNACE::equals)
                .isPresent();
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
        discoverAll(event.getPlayer());
    }

    /**
     * Pushes the current recipe set to everyone <em>already</em> online.
     *
     * <p>{@link #onJoin} only ever fires on join, so without this a player who was on
     * the server when {@link #register()} ran is left behind. That happens on both
     * re-registration paths: {@code /electricfurnace reload}, and the
     * {@code /reload confirm} that re-enables this plugin under a live player's feet.
     * Two distinct things go stale for that player, and each needs its own half of this
     * method:
     *
     * <ul>
     *   <li><b>The recipe book's unlock set.</b> Removing a recipe may discard the
     *       players' discovery flag for it -- the API says so in as many words -- and a
     *       newly-added alloy was never discovered by anyone. Hence the re-discovery
     *       loop; {@code discoverRecipe} is a no-op for a key already unlocked, so this
     *       costs nothing when nothing changed.</li>
     *   <li><b>The recipe data the client has cached.</b> Neither
     *       {@code Bukkit.removeRecipe} nor {@code Bukkit.addRecipe} resends anything on
     *       its own, so a client keeps rendering the ingredient and result it was last
     *       sent -- a reload that changes an ingot's colour would otherwise show the old
     *       one in the book until relog. {@code Bukkit.updateRecipes()} is Paper's
     *       "updates recipe data and the recipe book to each player" resend and is the
     *       whole of the server-side fix. It is called once, after the whole batch,
     *       rather than through the per-recipe {@code resendRecipes} overloads, which
     *       would push a full recipe list to every player thirty times over.</li>
     * </ul>
     *
     * <p>Never throws: called from the enable path and from the reload path.
     */
    public void refreshOnlinePlayers() {
        try {
            for (Player player : Bukkit.getOnlinePlayers()) {
                discoverAll(player);
            }
            // Last, so the resend carries the discovery state set just above.
            Bukkit.updateRecipes();
        } catch (Throwable failure) {
            warn.accept("Failed to refresh gear recipes for online players: " + failure.getMessage());
        }
    }

    /** Unlocks every tracked key for one player; one bad key costs only itself. */
    private void discoverAll(Player player) {
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
