/*
 * ElectricFurnace - a redstone-powered smelter that recycles metal gear into ingots.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.electricfurnace.gui;

import java.util.Set;

/**
 * The fixed 27-slot (3-row) layout of the Electric Furnace GUI, as named constants.
 *
 * <p>No magic slot numbers belong anywhere else in this plugin -- every listener and
 * the GUI factory itself refer to slots only through {@link #FUEL_SLOT},
 * {@link #INPUT_SLOTS}, {@link #OUTPUT_SLOT}, {@link #INDICATOR_SLOT}, and
 * {@link #roleOf(int)}.
 *
 * <p>Layout:
 * <ul>
 *   <li>Slot 3: redstone fuel slot.</li>
 *   <li>Slots 10-14: the five recycler input slots.</li>
 *   <li>Slot 16: the output slot (may be taken from, never inserted into).</li>
 *   <li>Slot 22: the non-interactive status indicator.</li>
 *   <li>Every other slot: a non-interactive filler pane.</li>
 * </ul>
 *
 * <p>This class touches nothing but {@code int} and {@link Set} -- no
 * {@code org.bukkit} type anywhere -- so {@code GuiLayoutTest} can assert the slot
 * index math and role classification exhaustively with no running server.
 */
public final class GuiLayout {

    /** Total slot count of the GUI (3 rows of 9). */
    public static final int SIZE = 27;

    /** Plain-text GUI title. Custom inventory titles are Bedrock-safe. */
    public static final String TITLE_TEXT = "Electric Furnace";

    /** The single redstone fuel slot. */
    public static final int FUEL_SLOT = 3;

    /** The five recycler input slots. */
    public static final int INPUT_SLOT_1 = 10;
    public static final int INPUT_SLOT_2 = 11;
    public static final int INPUT_SLOT_3 = 12;
    public static final int INPUT_SLOT_4 = 13;
    public static final int INPUT_SLOT_5 = 14;

    /** All five recycler input slots, as an immutable set. */
    public static final Set<Integer> INPUT_SLOTS =
            Set.of(INPUT_SLOT_1, INPUT_SLOT_2, INPUT_SLOT_3, INPUT_SLOT_4, INPUT_SLOT_5);

    /** The output slot: may be taken from, never inserted into. */
    public static final int OUTPUT_SLOT = 16;

    /** The non-interactive status indicator slot. */
    public static final int INDICATOR_SLOT = 22;

    private GuiLayout() {
    }

    /** The functional role a given raw slot index plays in this layout. */
    public enum SlotRole {
        /** One of the five recycler input slots. */
        INPUT,
        /** The redstone fuel slot. */
        FUEL,
        /** The output slot: takeable, never insertable. */
        OUTPUT,
        /** The non-interactive status indicator. */
        INDICATOR,
        /** A non-interactive filler pane. */
        FILLER
    }

    /**
     * Classifies a raw slot index into its {@link SlotRole}.
     *
     * @param slot a raw slot index into the 27-slot GUI inventory
     * @return the role that slot plays
     * @throws IllegalArgumentException if {@code slot} is outside {@code [0, SIZE)}
     */
    public static SlotRole roleOf(int slot) {
        if (slot < 0 || slot >= SIZE) {
            throw new IllegalArgumentException("slot " + slot + " is outside [0, " + SIZE + ")");
        }
        if (slot == FUEL_SLOT) {
            return SlotRole.FUEL;
        }
        if (INPUT_SLOTS.contains(slot)) {
            return SlotRole.INPUT;
        }
        if (slot == OUTPUT_SLOT) {
            return SlotRole.OUTPUT;
        }
        if (slot == INDICATOR_SLOT) {
            return SlotRole.INDICATOR;
        }
        return SlotRole.FILLER;
    }
}
