/*
 * ElectricFurnace - a redstone-powered smelter that recycles metal gear into ingots.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.electricfurnace.alloy;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The loaded set of named alloy recipes, keyed by id, with the balance-ceiling clamp
 * applied to every stat block on load.
 *
 * <p>The balance ceiling is a binding, code-enforced constraint -- not merely
 * documented -- and is applied unconditionally by {@link #fromDefinitions}: there is
 * deliberately no configuration switch to disable it. Any stat exceeding the
 * netherite reference is clamped down to the diamond reference and a warning is
 * logged naming the alloy, the stat, the configured value, and the clamp.
 *
 * <p>{@link #fromDefinitions} is the pure, Bukkit-free core: it takes already-parsed
 * {@link AlloyDefinition}s and is what {@code AlloyRegistryTest} exercises directly,
 * with no running server required. {@link #load} is the thin glue that parses the
 * {@code alloys:} section of {@code config.yml} and delegates to it.
 */
public final class AlloyRegistry {

    // ---- Balance ceiling reference constants (see design doc "Balance ceiling") ----

    public static final double IRON_ATTACK_DAMAGE = 6.0;
    public static final int IRON_ARMOR = 15;
    public static final double IRON_ARMOR_TOUGHNESS = 0.0;
    public static final int IRON_MAX_DURABILITY = 250;

    public static final double DIAMOND_ATTACK_DAMAGE = 7.0;
    public static final int DIAMOND_ARMOR = 20;
    public static final double DIAMOND_ARMOR_TOUGHNESS = 2.0;
    public static final int DIAMOND_MAX_DURABILITY = 1561;

    public static final double NETHERITE_ATTACK_DAMAGE = 8.0;
    public static final int NETHERITE_ARMOR = 20;
    public static final double NETHERITE_ARMOR_TOUGHNESS = 3.0;
    public static final int NETHERITE_MAX_DURABILITY = 2031;

    /** id of the synthesized fallback used if no configured recipe declares empty inputs. */
    private static final String SYNTHESIZED_FALLBACK_ID = "fused_alloy";

    private final Map<String, AlloyDefinition> byId;
    private final String fallbackId;

    private AlloyRegistry(Map<String, AlloyDefinition> byId, String fallbackId) {
        this.byId = byId;
        this.fallbackId = fallbackId;
    }

    /**
     * Builds a registry from already-parsed alloy definitions, applying the
     * balance-ceiling clamp to every stat block. Pure -- no Bukkit types involved.
     *
     * @param rawDefinitions definitions as configured, stats not yet clamped
     * @param warn           sink for balance-ceiling clamp warnings; must not be {@code null}
     */
    public static AlloyRegistry fromDefinitions(List<AlloyDefinition> rawDefinitions, Consumer<String> warn) {
        Objects.requireNonNull(rawDefinitions, "rawDefinitions");
        Objects.requireNonNull(warn, "warn");

        Map<String, AlloyDefinition> clamped = new LinkedHashMap<>();
        String fallbackId = null;
        for (AlloyDefinition def : rawDefinitions) {
            AlloyDefinition safe = new AlloyDefinition(
                    def.id(), def.displayName(), def.lore(), def.color(), def.inputIds(),
                    clampStats(def.id(), def.stats(), warn));
            clamped.put(safe.id(), safe);
            if (safe.isFallback() && fallbackId == null) {
                fallbackId = safe.id();
            }
        }

        if (fallbackId == null) {
            warn.accept("ElectricFurnace alloys: no recipe with empty inputs was configured (the generic "
                    + "fallback); synthesizing a default '" + SYNTHESIZED_FALLBACK_ID + "'.");
            AlloyDefinition synthesized = new AlloyDefinition(
                    SYNTHESIZED_FALLBACK_ID, "Fused Alloy", List.of(), "#4B4B4B", Set.of(),
                    new AlloyStats(IRON_ATTACK_DAMAGE, -2.6, IRON_ARMOR, IRON_ARMOR_TOUGHNESS,
                            IRON_MAX_DURABILITY, 10));
            clamped.put(synthesized.id(), synthesized);
            fallbackId = synthesized.id();
        }

        return new AlloyRegistry(Map.copyOf(clamped), fallbackId);
    }

    /**
     * Parses the {@code alloys:} section of {@code config.yml} and delegates to
     * {@link #fromDefinitions}. Each child section is read as one alloy: {@code
     * display-name} (string), {@code lore} (string list, optional), {@code color}
     * (string, optional), {@code inputs} (string list, may be empty to mark the
     * fallback), and {@code stats.*} (the six {@link AlloyStats} fields).
     *
     * <p>A missing or malformed alloy entry is skipped with a warning rather than
     * thrown -- consistent with the rest of this plugin's "never fail startup"
     * config contract.
     *
     * @param alloysSection the {@code alloys} configuration section; may be {@code null}
     * @param warn          sink for warnings, both parsing and balance-ceiling clamp
     */
    public static AlloyRegistry load(ConfigurationSection alloysSection, Consumer<String> warn) {
        Objects.requireNonNull(warn, "warn");
        List<AlloyDefinition> definitions = new ArrayList<>();
        if (alloysSection != null) {
            for (String id : alloysSection.getKeys(false)) {
                ConfigurationSection section = alloysSection.getConfigurationSection(id);
                if (section == null) {
                    warn.accept("ElectricFurnace alloys: entry '" + id + "' is not a section; skipped.");
                    continue;
                }
                definitions.add(parseDefinition(id, section));
            }
        }
        return fromDefinitions(definitions, warn);
    }

    private static AlloyDefinition parseDefinition(String id, ConfigurationSection section) {
        String displayName = section.getString("display-name", id);
        List<String> lore = section.getStringList("lore");
        String color = section.getString("color", "#FFFFFF");
        Set<String> inputIds = Set.copyOf(section.getStringList("inputs"));

        ConfigurationSection statsSection = section.getConfigurationSection("stats");
        AlloyStats stats = new AlloyStats(
                statsSection == null ? IRON_ATTACK_DAMAGE : statsSection.getDouble("attack-damage", IRON_ATTACK_DAMAGE),
                statsSection == null ? -2.6 : statsSection.getDouble("attack-speed", -2.6),
                statsSection == null ? IRON_ARMOR : statsSection.getInt("armor", IRON_ARMOR),
                statsSection == null ? IRON_ARMOR_TOUGHNESS : statsSection.getDouble("armor-toughness", IRON_ARMOR_TOUGHNESS),
                statsSection == null ? IRON_MAX_DURABILITY : statsSection.getInt("max-durability", IRON_MAX_DURABILITY),
                statsSection == null ? 10 : statsSection.getInt("enchantability", 10)
        );

        return new AlloyDefinition(id, displayName, lore, color, inputIds, stats);
    }

    /** Looks up a definition by id. */
    public Optional<AlloyDefinition> get(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    /** All loaded definitions, including the fallback. */
    public Collection<AlloyDefinition> all() {
        return byId.values();
    }

    /**
     * Finds a named (non-fallback) recipe whose required input ids are exactly the
     * given present ids -- order- and quantity-independent, since both sides are
     * plain sets.
     */
    public Optional<AlloyDefinition> findNamedMatch(Set<String> presentIds) {
        return byId.values().stream()
                .filter(def -> !def.isFallback())
                .filter(def -> def.inputIds().equals(presentIds))
                .findFirst();
    }

    /** The generic fallback recipe (empty inputs), used when no named recipe matches. */
    public AlloyDefinition fallback() {
        return byId.get(fallbackId);
    }

    /**
     * Clamps a stat block against the balance ceiling: any of attack damage, armor,
     * armor toughness, or max durability that exceeds the netherite reference is
     * replaced with the diamond reference, and a warning naming the alloy, the stat,
     * the configured value, and the clamp is sent to {@code warn}. Attack speed and
     * enchantability have no defined ceiling reference and are passed through
     * unchanged.
     */
    static AlloyStats clampStats(String alloyId, AlloyStats stats, Consumer<String> warn) {
        double attackDamage = clampToCeiling(alloyId, "attack-damage", stats.attackDamage(),
                NETHERITE_ATTACK_DAMAGE, DIAMOND_ATTACK_DAMAGE, warn);
        int armor = (int) clampToCeiling(alloyId, "armor", stats.armor(),
                NETHERITE_ARMOR, DIAMOND_ARMOR, warn);
        double armorToughness = clampToCeiling(alloyId, "armor-toughness", stats.armorToughness(),
                NETHERITE_ARMOR_TOUGHNESS, DIAMOND_ARMOR_TOUGHNESS, warn);
        int maxDurability = (int) clampToCeiling(alloyId, "max-durability", stats.maxDurability(),
                NETHERITE_MAX_DURABILITY, DIAMOND_MAX_DURABILITY, warn);

        return new AlloyStats(attackDamage, stats.attackSpeed(), armor, armorToughness,
                maxDurability, stats.enchantability());
    }

    private static double clampToCeiling(String alloyId, String statName, double value,
                                          double netheriteReference, double diamondReference,
                                          Consumer<String> warn) {
        if (value > netheriteReference) {
            warn.accept("ElectricFurnace alloys: alloy '" + alloyId + "' stat '" + statName
                    + "' value '" + value + "' exceeds the netherite reference '" + netheriteReference
                    + "'; clamped to the diamond reference '" + diamondReference + "'.");
            return diamondReference;
        }
        return value;
    }
}
