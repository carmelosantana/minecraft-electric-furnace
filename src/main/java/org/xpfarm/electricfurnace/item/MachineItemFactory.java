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

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

/**
 * Mints the Electric Furnace machine item: base {@link Material#BLAST_FURNACE}, named
 * "Electric Furnace", stamped with {@link MaterialContract#MACHINE} so
 * {@code MachineBlockListener} (Task 5) can recognize a placed block's origin purely
 * by PDC -- never by display name or lore.
 *
 * <p><b>No custom model data, no {@code item_model} component.</b> The base material
 * itself communicates the item on Bedrock without an authored resource pack.
 */
public final class MachineItemFactory {

    private static final Component DISPLAY_NAME = Component.text("Electric Furnace")
            .color(NamedTextColor.YELLOW)
            .decoration(TextDecoration.ITALIC, false);

    private static final List<Component> LORE = List.of(
            Component.text("Recycles metal gear into ingots").color(NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false),
            Component.text("and fuses mixed metals into alloys.").color(NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false),
            Component.text("Requires a redstone signal to run.").color(NamedTextColor.DARK_GRAY)
                    .decoration(TextDecoration.ITALIC, false));

    private MachineItemFactory() {
    }

    /** Builds one Electric Furnace machine {@code ItemStack}. */
    public static ItemStack create() {
        ItemStack stack = new ItemStack(Material.BLAST_FURNACE);
        ItemMeta meta = stack.getItemMeta();

        meta.displayName(DISPLAY_NAME);
        meta.lore(LORE);
        meta.getPersistentDataContainer().set(MaterialContract.MACHINE, PersistentDataType.BYTE,
                MaterialContract.MACHINE_MARKER);

        stack.setItemMeta(meta);
        return stack;
    }
}
