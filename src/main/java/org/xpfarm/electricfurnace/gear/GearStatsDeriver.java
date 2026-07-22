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

import org.xpfarm.electricfurnace.alloy.AlloyRegistry;
import org.xpfarm.electricfurnace.alloy.AlloyStats;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Derives one gear piece's stats from an alloy's single stat block.
 *
 * <p><b>Entirely pure.</b> No {@code org.bukkit} type appears here or in its tests.
 * Durability reference values come from {@code AlloyRegistry} and armor-shape constants
 * from {@link GearPiece}, never re-declared here; the vanilla armor base units below have
 * no home elsewhere and are declared once, here.
 */
public final class GearStatsDeriver {

    /** Vanilla iron armor's durability base unit; pairs with {@code AlloyRegistry.IRON_MAX_DURABILITY}. */
    public static final int IRON_ARMOR_BASE = 15;

    /** Vanilla diamond armor's durability base unit. */
    public static final int DIAMOND_ARMOR_BASE = 33;

    /** Vanilla netherite armor's durability base unit; the ceiling for any derived value. */
    public static final int NETHERITE_ARMOR_BASE = 37;

    /**
     * The player's own base {@code ATTACK_DAMAGE} attribute value, which every held
     * weapon's modifier is added on top of.
     *
     * <p>This exists because attack damage is the one stat here configured in
     * <b>display space</b> while the item must be written in <b>modifier space</b>.
     * Vanilla's swords <em>display</em> 6/7/8 for iron/diamond/netherite but carry
     * modifiers of +5/+6/+7 -- the displayed number is base-plus-modifier, and the base
     * is this 1.0. The alloy reference constants ({@code AlloyRegistry.IRON_ATTACK_DAMAGE}
     * and friends) are those displayed numbers, so an alloy's configured
     * {@code attack-damage} is a displayed total too.
     *
     * <p>The subtraction cannot be skipped, because writing an item's
     * {@code attribute_modifiers} <b>replaces</b> the item type's vanilla defaults rather
     * than merging with them. Writing the displayed value straight through would stack it
     * on the player's base a second time and inflate every weapon by exactly 1.0 -- which
     * would also let a configured 8.0 display as 9.0 and slip past the balance ceiling
     * that {@code AlloyRegistry.clampStats} exists to enforce.
     *
     * <p>No sibling constant exists for the other three stats, and none should: the
     * player's base {@code ATTACK_SPEED} is 4.0 but {@code attack-speed} is already
     * configured in modifier space (-2.6 displays as 1.4), and the base for both
     * {@code ARMOR} and {@code ARMOR_TOUGHNESS} is 0, so display and modifier space
     * coincide there.
     */
    public static final double PLAYER_BASE_ATTACK_DAMAGE = 1.0;

    private GearStatsDeriver() {
    }

    /**
     * Converts a display-space attack damage -- what {@link GearStats#attackDamage()}
     * carries, and what a tooltip should read -- into the modifier-space value to write
     * as an {@code ADD_NUMBER ATTACK_DAMAGE} modifier.
     *
     * <p>See {@link #PLAYER_BASE_ATTACK_DAMAGE} for why the two spaces differ. This is
     * the <em>only</em> stat needing the conversion; call it for nothing else.
     *
     * @param displayAttackDamage the player-meaningful damage the tooltip should show
     * @return the modifier amount to write on the item
     */
    public static double attackDamageModifier(double displayAttackDamage) {
        return displayAttackDamage - PLAYER_BASE_ATTACK_DAMAGE;
    }

    /**
     * Splits an alloy's full-set armor total across the four armor pieces using
     * vanilla's 3/8/6/3 shape with largest-remainder rounding.
     *
     * <p><b>The pieces always sum to exactly {@code total}.</b> That is the entire
     * reason this is largest-remainder rather than plain rounding: rounding each piece
     * independently loses or gains points, so an alloy configured for 16 armor would
     * silently grant 15 or 17. All arithmetic is integer -- {@code numerator * total}
     * is divided by the denominator for the floor and the remainder is taken with
     * {@code %}, so there is no floating-point drift to reason about.
     *
     * <p>Ties in the remainder are broken by {@link GearPiece#armorPieces()} order.
     *
     * @param total the alloy's configured full-set armor points; negative is treated as 0
     * @return an entry for each of the four armor pieces, summing to {@code total}
     */
    public static Map<GearPiece, Integer> splitArmor(int total) {
        int safeTotal = Math.max(0, total);
        Map<GearPiece, Integer> result = new EnumMap<>(GearPiece.class);
        List<GearPiece> pieces = GearPiece.armorPieces();

        int assigned = 0;
        Map<GearPiece, Integer> remainders = new EnumMap<>(GearPiece.class);
        for (GearPiece piece : pieces) {
            int scaled = piece.armorShareNumerator() * safeTotal;
            int floor = scaled / GearPiece.ARMOR_SHARE_DENOMINATOR;
            result.put(piece, floor);
            remainders.put(piece, scaled % GearPiece.ARMOR_SHARE_DENOMINATOR);
            assigned += floor;
        }

        int spare = safeTotal - assigned;
        List<GearPiece> byRemainder = new ArrayList<>(pieces);
        byRemainder.sort(Comparator
                .comparingInt((GearPiece piece) -> remainders.get(piece)).reversed()
                .thenComparingInt(pieces::indexOf));

        for (int i = 0; i < spare; i++) {
            GearPiece piece = byRemainder.get(i);
            result.put(piece, result.get(piece) + 1);
        }
        return result;
    }

    /**
     * Converts an alloy's tool-scale {@code maxDurability} into vanilla's armor
     * durability base unit.
     *
     * <p>The two scales are deliberately <b>not</b> treated as proportional: vanilla
     * iron is 250 tool durability against an armor base of 15 (a ratio of ~16.7),
     * while diamond is 1561 against 33 (~47.3). Scaling by a flat ratio would badly
     * overstate armor durability at the top of the range. Instead the alloy's
     * <em>position</em> between the iron and diamond tool references is projected onto
     * the interval between the iron and diamond armor bases, and rounded to nearest.
     *
     * <p>The result is clamped to {@code [IRON_ARMOR_BASE, NETHERITE_ARMOR_BASE]}.
     * The upper clamp is reachable in normal use: {@code AlloyRegistry.clampStats}
     * permits any value up to the netherite reference (2031), which projects to ~39.
     *
     * @param maxDurability the alloy's configured, already-clamped tool-scale durability
     * @return the armor base unit to multiply by {@link GearPiece#durabilityFactor()}
     */
    public static int armorBaseUnit(int maxDurability) {
        double span = AlloyRegistry.DIAMOND_MAX_DURABILITY - AlloyRegistry.IRON_MAX_DURABILITY;
        double position = (maxDurability - AlloyRegistry.IRON_MAX_DURABILITY) / span;
        long projected = Math.round(IRON_ARMOR_BASE + position * (DIAMOND_ARMOR_BASE - IRON_ARMOR_BASE));
        return (int) Math.clamp(projected, IRON_ARMOR_BASE, NETHERITE_ARMOR_BASE);
    }

    /**
     * Derives one piece's stats from an alloy's stat block.
     *
     * <p>No balance clamp runs here. {@code AlloyRegistry.clampStats} has already
     * clamped {@code stats} at load time, so gear inherits clamped values for free --
     * and re-clamping the derived axe damage would be wrong, since vanilla's own
     * netherite axe exceeds its netherite sword.
     *
     * @param stats the alloy's already-clamped stat block
     * @param piece the piece being derived
     * @return the concrete stat block for that one item
     */
    public static GearStats derive(AlloyStats stats, GearPiece piece) {
        Objects.requireNonNull(stats, "stats");
        Objects.requireNonNull(piece, "piece");

        if (piece.kind() == GearPiece.Kind.WEAPON) {
            return new GearStats(
                    stats.attackDamage() + piece.attackDamageDelta(),
                    stats.attackSpeed() + piece.attackSpeedDelta(),
                    0,
                    0.0,
                    stats.maxDurability(),
                    stats.enchantability());
        }

        return new GearStats(
                0.0,
                0.0,
                splitArmor(stats.armor()).get(piece),
                stats.armorToughness(),
                armorBaseUnit(stats.maxDurability()) * piece.durabilityFactor(),
                stats.enchantability());
    }
}
