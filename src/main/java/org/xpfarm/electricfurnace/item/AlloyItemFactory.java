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
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.xpfarm.electricfurnace.alloy.AlloyDefinition;

import java.util.List;
import java.util.Objects;

/**
 * Mints alloy ingot {@code ItemStack}s from an {@link AlloyDefinition}.
 *
 * <p>Base material is always {@link Material#NETHERITE_INGOT} -- <b>never</b>
 * {@code setCustomModelData} or the {@code item_model} component, which are invisible
 * to Bedrock clients without an authored Bedrock resource pack. Visual distinction
 * between alloys comes entirely from name/lore color, not from the model.
 *
 * <p>Every minted item is stamped with both {@code xpfarm:} keys from
 * {@link MaterialContract} so any plugin sharing that contract -- including this one's
 * own {@link MetalClassifier} -- can recognize it purely by PDC.
 */
public final class AlloyItemFactory {

    private AlloyItemFactory() {
    }

    /** Builds one alloy ingot {@code ItemStack} for {@code definition}. */
    public static ItemStack create(AlloyDefinition definition) {
        Objects.requireNonNull(definition, "definition");

        ItemStack stack = new ItemStack(Material.NETHERITE_INGOT);
        ItemMeta meta = stack.getItemMeta();

        Component name = Component.text(definition.displayName())
                .color(parseColor(definition.color()))
                .decoration(TextDecoration.ITALIC, false);
        meta.displayName(name);

        List<Component> lore = definition.lore().stream()
                .<Component>map(line -> Component.text(line).decoration(TextDecoration.ITALIC, false))
                .toList();
        meta.lore(lore);

        meta.getPersistentDataContainer().set(MaterialContract.CUSTOM_MATERIAL, PersistentDataType.STRING,
                MaterialContract.OWNING_SYSTEM_ELECTRICFURNACE);
        meta.getPersistentDataContainer().set(MaterialContract.MATERIAL_ID, PersistentDataType.STRING,
                definition.id());

        stack.setItemMeta(meta);
        return stack;
    }

    private static TextColor parseColor(String hex) {
        TextColor color = TextColor.fromHexString(hex);
        return color != null ? color : TextColor.fromHexString("#FFFFFF");
    }
}
