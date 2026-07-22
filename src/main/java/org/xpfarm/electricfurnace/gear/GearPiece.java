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

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The six craftable gear pieces, and the vanilla-derived constants each one needs.
 *
 * <p>Deliberately free of any Bukkit type. The equipment slot an armor piece occupies
 * and the concrete {@code Material} it is built on are both resolved later, by
 * {@code GearItemFactory} -- this enum only carries plain numbers, so it stays
 * exhaustively unit-testable with no running server.
 *
 * <p>{@link #armorShareNumerator()} values are vanilla's diamond-armor distribution
 * (3/8/6/3 of 20); {@link #durabilityFactor()} values are vanilla's per-slot armor
 * durability multipliers (11/16/15/13).
 */
public enum GearPiece {

    SWORD("sword", "Sword", Kind.WEAPON, 0, 0, 2, 1, 0.0, 0.0),
    AXE("axe", "Axe", Kind.WEAPON, 0, 0, 3, 2, 2.0, -0.6),
    HELMET("helmet", "Helmet", Kind.ARMOR, 3, 11, 5, 0, 0.0, 0.0),
    CHESTPLATE("chestplate", "Chestplate", Kind.ARMOR, 8, 16, 8, 0, 0.0, 0.0),
    LEGGINGS("leggings", "Leggings", Kind.ARMOR, 6, 15, 7, 0, 0.0, 0.0),
    BOOTS("boots", "Boots", Kind.ARMOR, 3, 13, 4, 0, 0.0, 0.0);

    /** Whether a piece is held and swung, or worn. */
    public enum Kind {
        WEAPON,
        ARMOR
    }

    /** Sum of every armor piece's share numerator. */
    public static final int ARMOR_SHARE_DENOMINATOR = 20;

    /**
     * The four armor pieces in <b>largest-remainder tiebreak order</b>.
     *
     * <p>This order is load-bearing, not cosmetic: when two pieces' rounding
     * remainders tie, the earlier one here receives the spare armor point. Fixing the
     * order is what makes {@code GearStatsDeriver.splitArmor} deterministic instead of
     * dependent on enum declaration order, and the order itself (chest, legs, helmet,
     * boots) is vanilla's own descending armor-value order, so spare points land on
     * the pieces that already carry the most.
     */
    private static final List<GearPiece> ARMOR_PIECES =
            List.of(CHESTPLATE, LEGGINGS, HELMET, BOOTS);

    private final String id;
    private final String displayName;
    private final Kind kind;
    private final int armorShareNumerator;
    private final int durabilityFactor;
    private final int ingotCost;
    private final int stickCost;
    private final double attackDamageDelta;
    private final double attackSpeedDelta;

    GearPiece(String id, String displayName, Kind kind, int armorShareNumerator, int durabilityFactor,
            int ingotCost, int stickCost, double attackDamageDelta, double attackSpeedDelta) {
        this.id = id;
        this.displayName = displayName;
        this.kind = kind;
        this.armorShareNumerator = armorShareNumerator;
        this.durabilityFactor = durabilityFactor;
        this.ingotCost = ingotCost;
        this.stickCost = stickCost;
        this.attackDamageDelta = attackDamageDelta;
        this.attackSpeedDelta = attackSpeedDelta;
    }

    /** Stable lowercase identifier, e.g. {@code "chestplate"}. */
    public String id() {
        return id;
    }

    /** Human-readable suffix, e.g. {@code "Chestplate"} in "Steel Chestplate". */
    public String displayName() {
        return displayName;
    }

    public Kind kind() {
        return kind;
    }

    /** This piece's share of the alloy's full-set armor total, over {@link #ARMOR_SHARE_DENOMINATOR}. */
    public int armorShareNumerator() {
        return armorShareNumerator;
    }

    /** Vanilla's per-slot armor durability multiplier; {@code 0} for weapons. */
    public int durabilityFactor() {
        return durabilityFactor;
    }

    /** Alloy ingots consumed by this piece's recipe. */
    public int ingotCost() {
        return ingotCost;
    }

    /** Vanilla sticks consumed by this piece's recipe. */
    public int stickCost() {
        return stickCost;
    }

    /** Added to the alloy's configured attack damage; non-zero only for the axe. */
    public double attackDamageDelta() {
        return attackDamageDelta;
    }

    /** Added to the alloy's configured attack speed; non-zero only for the axe. */
    public double attackSpeedDelta() {
        return attackSpeedDelta;
    }

    /** The four armor pieces, in largest-remainder tiebreak order. */
    public static List<GearPiece> armorPieces() {
        return ARMOR_PIECES;
    }

    /** Resolves a typed token to a piece, case-insensitively. */
    public static Optional<GearPiece> byId(String token) {
        if (token == null) {
            return Optional.empty();
        }
        String normalized = token.toLowerCase(Locale.ROOT);
        for (GearPiece piece : values()) {
            if (piece.id.equals(normalized)) {
                return Optional.of(piece);
            }
        }
        return Optional.empty();
    }
}
