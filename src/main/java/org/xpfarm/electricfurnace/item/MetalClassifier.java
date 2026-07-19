/*
 * ElectricFurnace - a redstone-powered smelter that recycles metal gear into ingots.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.electricfurnace.item;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.xpfarm.electricfurnace.alloy.MetalType;
import org.xpfarm.electricfurnace.config.RecyclingSettings;
import org.xpfarm.electricfurnace.recycle.RecycleInput;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The bridge from Bukkit {@link Material}/{@link ItemStack} to Task 2's pure recycle
 * model: maps a material to an {@link Optional} {@link MetalType}, identifies
 * modifiers (coal, charcoal), recognizes alloy items by PDC, and turns all of that
 * into a {@link RecycleInput} the resolver can consume.
 *
 * <p><b>Two layers, deliberately split.</b> {@link #METAL_TABLE}, {@link #metalOf},
 * {@link #isModifier}, and the primitive overload of {@link #classify} touch nothing
 * but {@code Material} and plain values -- they are exhaustively unit-testable with
 * no running server. The {@link #classify(ItemStack, RecyclingSettings)} overload is
 * the thin Bukkit-facing glue that reads a real item's type, PDC, and damage state and
 * delegates to the pure core; constructing a real {@code ItemStack} requires a live
 * server (Paper resolves item types through {@code RegistryAccess}), so that overload
 * is exercised only at runtime, not by {@code MetalClassifierTest}.
 *
 * <p><b>Accept-damaged.</b> {@link RecycleInput} has no damage field: durability is
 * never scaled into the yield, and damaged/undamaged gear of the same type must
 * classify identically when {@code recycling.accept-damaged} is {@code true}. When it
 * is {@code false}, damaged gear is rejected outright (classifies to
 * {@link Optional#empty()}) rather than being partially accepted -- pristine gear of
 * the same type still classifies normally. This gate does not apply to alloy items:
 * remelting is not a "damaged gear" concept.
 */
public final class MetalClassifier {

    /**
     * Exhaustive {@code Material -> MetalType} table for every recognized ingot, raw
     * ore, and gear (tools, weapons, armor) material. Chainmail counts as
     * {@link MetalType#IRON}. Package-private so {@code MetalClassifierTest} can
     * assert it exhaustively.
     */
    static final Map<Material, MetalType> METAL_TABLE = buildMetalTable();

    /** Materials treated as modifiers -- never metals, never recyclable alone. */
    static final Set<Material> MODIFIER_MATERIALS = Set.of(Material.COAL, Material.CHARCOAL);

    /** The shared named-alloy-matching id for every modifier material (see class docs). */
    private static final String MODIFIER_ID = "coal";

    /**
     * Nuggets: their ingot-equivalent value is below 1 (9 nuggets = 1 ingot), so they
     * are treated as non-recyclable and rejected outright rather than assigned a
     * fractional or rounded value.
     */
    private static final Set<Material> SUB_INGOT_MATERIALS =
            Set.of(Material.IRON_NUGGET, Material.GOLD_NUGGET, Material.COPPER_NUGGET);

    /** Ingot-equivalent value assigned to every recognized metal/gear/alloy input. */
    private static final int STANDARD_INGOT_VALUE = 1;

    private MetalClassifier() {
    }

    private static Map<Material, MetalType> buildMetalTable() {
        Map<Material, MetalType> table = new EnumMap<>(Material.class);
        putAll(table, MetalType.IRON,
                Material.IRON_INGOT, Material.RAW_IRON,
                Material.IRON_SWORD, Material.IRON_SPEAR, Material.IRON_PICKAXE, Material.IRON_AXE,
                Material.IRON_SHOVEL, Material.IRON_HOE,
                Material.IRON_HELMET, Material.IRON_CHESTPLATE, Material.IRON_LEGGINGS, Material.IRON_BOOTS,
                Material.CHAINMAIL_HELMET, Material.CHAINMAIL_CHESTPLATE,
                Material.CHAINMAIL_LEGGINGS, Material.CHAINMAIL_BOOTS);
        putAll(table, MetalType.GOLD,
                Material.GOLD_INGOT, Material.RAW_GOLD,
                Material.GOLDEN_SWORD, Material.GOLDEN_SPEAR, Material.GOLDEN_PICKAXE, Material.GOLDEN_AXE,
                Material.GOLDEN_SHOVEL, Material.GOLDEN_HOE,
                Material.GOLDEN_HELMET, Material.GOLDEN_CHESTPLATE, Material.GOLDEN_LEGGINGS, Material.GOLDEN_BOOTS);
        putAll(table, MetalType.COPPER,
                Material.COPPER_INGOT, Material.RAW_COPPER,
                Material.COPPER_SWORD, Material.COPPER_SPEAR, Material.COPPER_PICKAXE, Material.COPPER_AXE,
                Material.COPPER_SHOVEL, Material.COPPER_HOE,
                Material.COPPER_HELMET, Material.COPPER_CHESTPLATE, Material.COPPER_LEGGINGS, Material.COPPER_BOOTS);
        putAll(table, MetalType.NETHERITE,
                Material.NETHERITE_INGOT,
                Material.NETHERITE_SWORD, Material.NETHERITE_SPEAR, Material.NETHERITE_PICKAXE, Material.NETHERITE_AXE,
                Material.NETHERITE_SHOVEL, Material.NETHERITE_HOE,
                Material.NETHERITE_HELMET, Material.NETHERITE_CHESTPLATE, Material.NETHERITE_LEGGINGS,
                Material.NETHERITE_BOOTS);
        return Map.copyOf(table);
    }

    private static void putAll(Map<Material, MetalType> table, MetalType metal, Material... materials) {
        for (Material material : materials) {
            table.put(material, metal);
        }
    }

    /** The metal this material counts as, or {@link Optional#empty()} if it is not one. */
    public static Optional<MetalType> metalOf(Material material) {
        return Optional.ofNullable(METAL_TABLE.get(material));
    }

    /** Whether this material is a modifier (coal/charcoal) rather than a metal. */
    public static boolean isModifier(Material material) {
        return MODIFIER_MATERIALS.contains(material);
    }

    /**
     * The pure classification core: given already-extracted primitives, decides the
     * {@link RecycleInput} this item represents. No {@code org.bukkit} type beyond
     * {@link Material} appears here, and no live server is required to call it.
     *
     * @param material       the item's material
     * @param materialId     a stable id for this material, used verbatim as the
     *                       modifier id for named-alloy matching; ignored for metals
     *                       (whose id is derived from {@code metal}) and for alloys
     * @param damaged        whether the underlying item currently has any durability
     *                       damage; ignored entirely for alloy items
     * @param isAlloyStamped whether the item carries the {@code xpfarm:custom_material}
     *                       PDC marker (i.e. is itself a minted alloy item)
     * @param alloyId        the alloy id read from {@code xpfarm:material_id} if
     *                       {@code isAlloyStamped}, otherwise ignored
     * @param settings       validated {@code recycling} config; only
     *                       {@link RecyclingSettings#acceptDamaged()} is consulted here
     * @return the classified input, or {@link Optional#empty()} if this item cannot be
     *         used as a recycler input at all (unrecognized material, a sub-ingot
     *         nugget, or damaged gear while {@code accept-damaged} is disabled)
     */
    public static Optional<RecycleInput> classify(Material material, String materialId, boolean damaged,
            boolean isAlloyStamped, String alloyId, RecyclingSettings settings) {
        Objects.requireNonNull(material, "material");
        Objects.requireNonNull(settings, "settings");

        if (isAlloyStamped) {
            return Optional.of(new RecycleInput(materialId, null, false, true, alloyId, STANDARD_INGOT_VALUE));
        }

        if (damaged && !settings.acceptDamaged()) {
            return Optional.empty();
        }

        if (SUB_INGOT_MATERIALS.contains(material)) {
            return Optional.empty();
        }

        if (isModifier(material)) {
            return Optional.of(new RecycleInput(MODIFIER_ID, null, true, false, null, 0));
        }

        Optional<MetalType> metal = metalOf(material);
        if (metal.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(new RecycleInput(materialId, metal.get(), false, false, null, STANDARD_INGOT_VALUE));
    }

    /**
     * Classifies a real {@code ItemStack} into a {@link RecycleInput}, or
     * {@link Optional#empty()} if it cannot be used as a recycler input at all.
     *
     * <p>Reads, in order: the {@code xpfarm:} alloy-stamp PDC keys (an item stamped by
     * this plugin or another minting alloys under the same contract), the material
     * table above, CopperKingdom's foreign copper-armor/copper-weapon PDC keys as a
     * fallback recognition path for that sibling plugin's own copper gear, and
     * finally durability via {@link Damageable} if the item's meta supports it.
     */
    public static Optional<RecycleInput> classify(ItemStack stack, RecyclingSettings settings) {
        Objects.requireNonNull(stack, "stack");
        Objects.requireNonNull(settings, "settings");

        Material material = stack.getType();
        String materialId = material.name().toLowerCase(Locale.ROOT);
        boolean isAlloyStamped = MaterialContract.readCustomMaterial(stack).isPresent();
        String alloyId = MaterialContract.readMaterialId(stack).orElse(null);
        boolean damaged = readDamaged(stack);

        if (!isAlloyStamped && metalOf(material).isEmpty() && !isModifier(material)
                && (MaterialContract.isCopperKingdomCopperArmor(stack)
                    || MaterialContract.isCopperKingdomCopperWeapon(stack))) {
            if (damaged && !settings.acceptDamaged()) {
                return Optional.empty();
            }
            return Optional.of(new RecycleInput(materialId, MetalType.COPPER, false, false, null, STANDARD_INGOT_VALUE));
        }

        return classify(material, materialId, damaged, isAlloyStamped, alloyId, settings);
    }

    private static boolean readDamaged(ItemStack stack) {
        ItemMeta meta = stack.getItemMeta();
        return meta instanceof Damageable damageable && damageable.hasDamage();
    }
}
