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

import io.papermc.paper.datacomponent.DataComponentTypes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.xpfarm.electricfurnace.alloy.AlloyDefinition;
import org.xpfarm.electricfurnace.gear.GearPiece;
import org.xpfarm.electricfurnace.gear.GearStats;
import org.xpfarm.electricfurnace.gear.GearStatsDeriver;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Mints alloy gear {@code ItemStack}s, beside {@link AlloyItemFactory}.
 *
 * <p>Base material comes from the alloy's {@code base}, so Steel gear is iron-shaped
 * and Rose Gold gear is gold-shaped. As everywhere in this plugin, there is <b>no</b>
 * {@code setCustomModelData} and no {@code item_model} component -- those are
 * invisible to Bedrock without an authored resource pack, and base material is the
 * whole visual-identity mechanism instead.
 *
 * <p>Every minted item carries {@code xpfarm:custom_material} and
 * {@code xpfarm:material_id} -- which is exactly what makes gear classify as an alloy
 * remelt in {@link MetalClassifier} with no change there -- plus
 * {@code xpfarm:gear_piece}.
 */
public final class GearItemFactory {

    /** Namespace for this plugin's attribute modifiers, so they are identifiable and replaceable. */
    private static final String MODIFIER_NAMESPACE = "electricfurnace";

    private GearItemFactory() {
    }

    /**
     * Builds one gear {@code ItemStack}.
     *
     * @return the item, or {@link Optional#empty()} if this server has no such base
     *         material -- copper equipment only exists from 1.21.9
     */
    public static Optional<ItemStack> create(AlloyDefinition definition, GearPiece piece) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(piece, "piece");

        // Material.getMaterial, never a direct constant: a hard reference to
        // Material.COPPER_SWORD throws NoSuchFieldError at class-load on <1.21.9.
        Material material = Material.getMaterial(definition.base().materialName(piece));
        if (material == null) {
            return Optional.empty();
        }

        GearStats stats = GearStatsDeriver.derive(definition.stats(), piece);
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();

        meta.displayName(Component.text(definition.displayName() + " " + piece.displayName())
                .color(parseColor(definition.color()))
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(definition.lore().stream()
                .<Component>map(line -> Component.text(line).decoration(TextDecoration.ITALIC, false))
                .toList());

        meta.getPersistentDataContainer().set(MaterialContract.CUSTOM_MATERIAL, PersistentDataType.STRING,
                MaterialContract.OWNING_SYSTEM_ELECTRICFURNACE);
        meta.getPersistentDataContainer().set(MaterialContract.MATERIAL_ID, PersistentDataType.STRING,
                definition.id());
        meta.getPersistentDataContainer().set(MaterialContract.GEAR_PIECE, PersistentDataType.STRING,
                piece.id());

        applyAttributes(meta, piece, stats);

        // setMaxDamage lives on Damageable, not ItemMeta. Java shows this value; Geyser
        // scales the Bedrock durability bar against the base item's vanilla maximum
        // instead -- a recorded, cosmetic-only limitation.
        if (meta instanceof Damageable damageable) {
            damageable.setMaxDamage(stats.maxDurability());
        }

        // Stable API since 1.21.2. Java-only in effect: Geyser reads enchantability
        // from the item type at handshake, never per stack.
        meta.setEnchantable(stats.enchantability());

        stack.setItemMeta(meta);

        // Strip netherite's inherited fire immunity. unsetData, NOT resetData --
        // resetData restores the item type's default, which puts immunity straight back.
        if (definition.base().fireImmuneByDefault()) {
            stack.unsetData(DataComponentTypes.DAMAGE_RESISTANT);
        }
        return Optional.of(stack);
    }

    /**
     * Writes this item's complete attribute modifier set.
     *
     * <p><b>Writing any modifier replaces the item type's vanilla defaults rather than
     * merging with them</b>, so every stat the item should have must be written here.
     * Adding only {@code +2 ATTACK_DAMAGE} to a netherite sword would yield a sword
     * dealing 2 damage, not 10.
     *
     * <p>Netherite's knockback resistance disappears as a welcome side effect of that
     * same replacement: it is simply never written back. Do <b>not</b> write an empty
     * modifier set, which would strip armor points too.
     *
     * <p>Note {@code Attribute} is an interface on this Paper version, not an enum --
     * {@code EnumMap}, {@code Attribute.values()}, and {@code GENERIC_*} constants all
     * fail here.
     *
     * <p><b>Attack damage alone is converted out of display space</b> via
     * {@link GearStatsDeriver#attackDamageModifier(double)}: because the write replaces
     * rather than merges, the modifier must be the tooltip value minus the player's base
     * damage. The other three stats are written unconverted -- {@code attack-speed} is
     * configured in modifier space already, and the player's base {@code ARMOR} and
     * {@code ARMOR_TOUGHNESS} are both 0.
     */
    private static void applyAttributes(ItemMeta meta, GearPiece piece, GearStats stats) {
        if (piece.kind() == GearPiece.Kind.WEAPON) {
            addModifier(meta, Attribute.ATTACK_DAMAGE, piece.id() + "_attack_damage",
                    GearStatsDeriver.attackDamageModifier(stats.attackDamage()),
                    EquipmentSlotGroup.MAINHAND);
            addModifier(meta, Attribute.ATTACK_SPEED, piece.id() + "_attack_speed",
                    stats.attackSpeed(), EquipmentSlotGroup.MAINHAND);
            return;
        }

        EquipmentSlotGroup slot = armorSlotGroup(piece);
        addModifier(meta, Attribute.ARMOR, piece.id() + "_armor", stats.armor(), slot);
        addModifier(meta, Attribute.ARMOR_TOUGHNESS, piece.id() + "_armor_toughness",
                stats.armorToughness(), slot);
    }

    private static void addModifier(ItemMeta meta, Attribute attribute, String name, double amount,
            EquipmentSlotGroup slot) {
        meta.addAttributeModifier(attribute, new AttributeModifier(
                new NamespacedKey(MODIFIER_NAMESPACE, name),
                amount,
                AttributeModifier.Operation.ADD_NUMBER,
                slot));
    }

    /**
     * The slot an armor piece must be worn in for its modifiers to apply.
     *
     * <p>A plain {@code switch} rather than a table on {@link GearPiece}: keeping the
     * Bukkit {@code EquipmentSlotGroup} out of that enum is what lets it stay pure and
     * unit-testable with no running server.
     */
    private static EquipmentSlotGroup armorSlotGroup(GearPiece piece) {
        return switch (piece) {
            case HELMET -> EquipmentSlotGroup.HEAD;
            case CHESTPLATE -> EquipmentSlotGroup.CHEST;
            case LEGGINGS -> EquipmentSlotGroup.LEGS;
            case BOOTS -> EquipmentSlotGroup.FEET;
            case SWORD, AXE -> EquipmentSlotGroup.MAINHAND;
        };
    }

    private static TextColor parseColor(String hex) {
        TextColor color = TextColor.fromHexString(hex);
        return color != null ? color : TextColor.fromHexString("#FFFFFF");
    }

    /** Every piece of one alloy, skipping any whose base material this server lacks. */
    public static List<ItemStack> createAll(AlloyDefinition definition) {
        return java.util.Arrays.stream(GearPiece.values())
                .map(piece -> create(definition, piece))
                .flatMap(Optional::stream)
                .toList();
    }
}
