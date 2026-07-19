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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pure unit tests for {@link GuiLayout}'s slot index math and slot-role
 * classification. Deliberately imports nothing from {@code org.bukkit} and requires
 * no running server -- every one of the 27 GUI slots is asserted exhaustively so a
 * future edit can never silently misclassify a slot.
 */
class GuiLayoutTest {

    @Test
    void size_isTwentySevenSlots() {
        assertEquals(27, GuiLayout.SIZE);
    }

    @Test
    void fuelSlot_isSlotThree() {
        assertEquals(3, GuiLayout.FUEL_SLOT);
        assertEquals(GuiLayout.SlotRole.FUEL, GuiLayout.roleOf(3));
    }

    @Test
    void inputSlots_areTenThroughFourteen() {
        assertEquals(Set.of(10, 11, 12, 13, 14), GuiLayout.INPUT_SLOTS);
        for (int slot : GuiLayout.INPUT_SLOTS) {
            assertEquals(GuiLayout.SlotRole.INPUT, GuiLayout.roleOf(slot));
        }
    }

    @Test
    void outputSlot_isSlotSixteen() {
        assertEquals(16, GuiLayout.OUTPUT_SLOT);
        assertEquals(GuiLayout.SlotRole.OUTPUT, GuiLayout.roleOf(16));
    }

    @Test
    void indicatorSlot_isSlotTwentyTwo() {
        assertEquals(22, GuiLayout.INDICATOR_SLOT);
        assertEquals(GuiLayout.SlotRole.INDICATOR, GuiLayout.roleOf(22));
    }

    /**
     * Exhaustive: every one of the 27 slots must classify to exactly one role, and
     * every slot not named as INPUT/FUEL/OUTPUT/INDICATOR must be FILLER.
     */
    @Test
    void everySlot_classifiesToExactlyOneRole_andUnnamedSlotsAreFiller() {
        Set<Integer> named = new HashSet<>(GuiLayout.INPUT_SLOTS);
        named.add(GuiLayout.FUEL_SLOT);
        named.add(GuiLayout.OUTPUT_SLOT);
        named.add(GuiLayout.INDICATOR_SLOT);

        for (int slot = 0; slot < GuiLayout.SIZE; slot++) {
            GuiLayout.SlotRole role = GuiLayout.roleOf(slot);
            if (named.contains(slot)) {
                assertEquals(namedRoleOf(slot), role, "slot " + slot);
            } else {
                assertEquals(GuiLayout.SlotRole.FILLER, role, "slot " + slot + " should be filler");
            }
        }
    }

    private GuiLayout.SlotRole namedRoleOf(int slot) {
        if (slot == GuiLayout.FUEL_SLOT) {
            return GuiLayout.SlotRole.FUEL;
        }
        if (GuiLayout.INPUT_SLOTS.contains(slot)) {
            return GuiLayout.SlotRole.INPUT;
        }
        if (slot == GuiLayout.OUTPUT_SLOT) {
            return GuiLayout.SlotRole.OUTPUT;
        }
        if (slot == GuiLayout.INDICATOR_SLOT) {
            return GuiLayout.SlotRole.INDICATOR;
        }
        throw new IllegalStateException("not a named slot: " + slot);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, -100, 27, 28, 1000})
    void roleOf_outOfRangeSlot_throws(int slot) {
        assertThrows(IllegalArgumentException.class, () -> GuiLayout.roleOf(slot));
    }

    @Test
    void title_isElectricFurnace() {
        assertEquals("Electric Furnace", GuiLayout.TITLE_TEXT);
    }
}
