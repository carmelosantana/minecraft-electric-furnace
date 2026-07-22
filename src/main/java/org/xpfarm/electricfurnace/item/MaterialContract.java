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

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.Objects;
import java.util.Optional;

/**
 * The shared cross-plugin {@code PersistentDataContainer} namespace.
 *
 * <p>Every key here is built with the {@link NamespacedKey#NamespacedKey(String, String)}
 * <b>string</b> constructor, deliberately -- it needs no plugin instance, which is
 * exactly what lets other plugins on the same server read (and, for their own
 * namespace, write) compatible items without a jar dependency in either direction.
 * Do not switch these to the {@code NamespacedKey(Plugin, String)} constructor.
 *
 * <p>Items are identified by these keys ONLY -- never by display name or lore
 * substring matching, per the plugin's Bedrock/Geyser safety rules.
 *
 * <p>This class also exposes read-only helpers for CopperKingdom's own keys so the
 * recycler can recognize copper gear minted by that sibling plugin. Those keys belong
 * to CopperKingdom's namespace: this class never writes to them.
 */
public final class MaterialContract {

    /** Owning-system marker used by {@link #CUSTOM_MATERIAL}, e.g. {@code "electricfurnace"}. */
    public static final String OWNING_SYSTEM_ELECTRICFURNACE = "electricfurnace";

    /** STRING: the owning system that minted this item, e.g. {@code "electricfurnace"}. */
    public static final NamespacedKey CUSTOM_MATERIAL = new NamespacedKey("xpfarm", "custom_material");

    /** STRING: the specific material id within the owning system, e.g. {@code "steel"}. */
    public static final NamespacedKey MATERIAL_ID = new NamespacedKey("xpfarm", "material_id");

    /** STRING: the gear piece id for a minted gear item, e.g. {@code "chestplate"}. */
    public static final NamespacedKey GEAR_PIECE = new NamespacedKey("xpfarm", "gear_piece");

    /** BYTE: marks an item as the Electric Furnace machine item. */
    public static final NamespacedKey MACHINE = new NamespacedKey("electricfurnace", "machine");

    /** The byte value stamped under {@link #MACHINE} to mark a machine item. */
    public static final byte MACHINE_MARKER = 1;

    /**
     * STRING (on a <b>chunk's</b> PersistentDataContainer, not an item): the set of
     * machine locations registered in that chunk, encoded by
     * {@link org.xpfarm.electricfurnace.machine.MachineKey}.
     */
    public static final NamespacedKey MACHINES = new NamespacedKey("electricfurnace", "machines");

    // ---- CopperKingdom's namespace -- read only, never written to here ----------------

    /** STRING (foreign, CopperKingdom): marks an item as CopperKingdom copper armor. */
    public static final NamespacedKey COPPERKINGDOM_COPPER_ARMOR = new NamespacedKey("copperkingdom", "copper_armor");

    /** STRING (foreign, CopperKingdom): marks an item as a CopperKingdom copper weapon. */
    public static final NamespacedKey COPPERKINGDOM_COPPER_WEAPON = new NamespacedKey("copperkingdom", "copper_weapon");

    private MaterialContract() {
    }

    /** Reads {@link #CUSTOM_MATERIAL} off {@code stack}, if present. */
    public static Optional<String> readCustomMaterial(ItemStack stack) {
        return readString(stack, CUSTOM_MATERIAL);
    }

    /** Reads {@link #MATERIAL_ID} off {@code stack}, if present. */
    public static Optional<String> readMaterialId(ItemStack stack) {
        return readString(stack, MATERIAL_ID);
    }

    /** Reads {@link #GEAR_PIECE} off {@code stack}, if present. */
    public static Optional<String> readGearPiece(ItemStack stack) {
        return readString(stack, GEAR_PIECE);
    }

    /** Whether {@code stack} carries the {@link #MACHINE} marker. */
    public static boolean isMachine(ItemStack stack) {
        Objects.requireNonNull(stack, "stack");
        Byte value = stack.getPersistentDataContainer().get(MACHINE, PersistentDataType.BYTE);
        return value != null && value == MACHINE_MARKER;
    }

    /** Read-only: whether {@code stack} carries CopperKingdom's copper-armor marker. */
    public static boolean isCopperKingdomCopperArmor(ItemStack stack) {
        return readString(stack, COPPERKINGDOM_COPPER_ARMOR).isPresent();
    }

    /** Read-only: whether {@code stack} carries CopperKingdom's copper-weapon marker. */
    public static boolean isCopperKingdomCopperWeapon(ItemStack stack) {
        return readString(stack, COPPERKINGDOM_COPPER_WEAPON).isPresent();
    }

    private static Optional<String> readString(ItemStack stack, NamespacedKey key) {
        Objects.requireNonNull(stack, "stack");
        return Optional.ofNullable(stack.getPersistentDataContainer().get(key, PersistentDataType.STRING));
    }
}
