/*
 * ElectricFurnace - a redstone-powered smelter that recycles metal gear into ingots.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.electricfurnace.gear;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The vanilla base material family a given alloy's gear is built on.
 *
 * <p>With no resource pack, base material is the <b>only</b> way two alloys' gear can
 * look different from each other, on Java and Bedrock alike. These five families are
 * exactly the vanilla tiers that have both weapon and armor forms -- chainmail and
 * leather have no sword -- which is why there are five, matching the five shipped
 * alloys.
 *
 * <p>{@link #materialName(GearPiece)} deliberately returns a {@code String} rather
 * than a Bukkit {@code Material}. That keeps this enum pure and unit-testable, and it
 * lets {@code GearItemFactory} resolve names through {@code Material.getMaterial},
 * which is required anyway: copper equipment only exists from 1.21.9, so a direct
 * {@code Material.COPPER_SWORD} reference would throw {@code NoSuchFieldError} at
 * class-load on an older server.
 */
public enum GearBase {

    COPPER("copper", "COPPER", false),
    IRON("iron", "IRON", false),
    /** Vanilla spells gold equipment {@code GOLDEN_*}, not {@code GOLD_*}. */
    GOLD("gold", "GOLDEN", false),
    DIAMOND("diamond", "DIAMOND", false),
    NETHERITE("netherite", "NETHERITE", true);

    /**
     * Default base per shipped alloy id. Thematic rather than arbitrary: steel is
     * hardened iron, rose gold is a gold blend, ferrocopper is copper, electrum steel
     * is the strongest alloy, and fused alloy's {@code #4B4B4B} reads as netherite.
     */
    private static final Map<String, GearBase> DEFAULTS = Map.of(
            "steel", IRON,
            "rose_gold", GOLD,
            "ferrocopper", COPPER,
            "electrum_steel", DIAMOND,
            "fused_alloy", NETHERITE);

    /** Base used for any alloy an operator added that is not in {@link #DEFAULTS}. */
    private static final GearBase FALLBACK = IRON;

    private final String id;
    private final String materialPrefix;
    private final boolean fireImmuneByDefault;

    GearBase(String id, String materialPrefix, boolean fireImmuneByDefault) {
        this.id = id;
        this.materialPrefix = materialPrefix;
        this.fireImmuneByDefault = fireImmuneByDefault;
    }

    /** The token an operator writes in {@code alloys.<id>.base}. */
    public String id() {
        return id;
    }

    /**
     * Whether items on this base carry vanilla fire immunity that must be stripped.
     *
     * <p>Only netherite does, via the {@code minecraft:damage_resistant} component
     * (renamed from {@code fire_resistant} in 1.21.2).
     */
    public boolean fireImmuneByDefault() {
        return fireImmuneByDefault;
    }

    /** The Bukkit {@code Material} name for this base and piece, e.g. {@code "GOLDEN_SWORD"}. */
    public String materialName(GearPiece piece) {
        return materialPrefix + "_" + piece.name();
    }

    /** Resolves a configured token to a base, case-insensitively. */
    public static Optional<GearBase> byId(String token) {
        if (token == null) {
            return Optional.empty();
        }
        String normalized = token.toLowerCase(Locale.ROOT);
        for (GearBase base : values()) {
            if (base.id.equals(normalized)) {
                return Optional.of(base);
            }
        }
        return Optional.empty();
    }

    /** The default base for an alloy id; {@link #FALLBACK} for anything unrecognized. */
    public static GearBase defaultFor(String alloyId) {
        if (alloyId == null) {
            return FALLBACK;
        }
        return DEFAULTS.getOrDefault(alloyId.toLowerCase(Locale.ROOT), FALLBACK);
    }
}
