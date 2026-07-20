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
import org.xpfarm.electricfurnace.gui.SlotLock.Action;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlotLockTest {

    @Test
    void idleMachine_allowsEveryInputAndFuelInteraction() {
        for (Action action : Action.values()) {
            assertTrue(SlotLock.allows(GuiLayout.SlotRole.INPUT, action, false));
            assertTrue(SlotLock.allows(GuiLayout.SlotRole.FUEL, action, false));
        }
    }

    @Test
    void runningMachine_locksInputsAgainstBothInsertAndRemove() {
        assertFalse(SlotLock.allows(GuiLayout.SlotRole.INPUT, Action.REMOVE, true));
        assertFalse(SlotLock.allows(GuiLayout.SlotRole.INPUT, Action.INSERT, true));
    }

    @Test
    void runningMachine_acceptsFuelTopUpButRefusesFuelRemoval() {
        assertTrue(SlotLock.allows(GuiLayout.SlotRole.FUEL, Action.INSERT, true));
        assertFalse(SlotLock.allows(GuiLayout.SlotRole.FUEL, Action.REMOVE, true));
    }

    @Test
    void outputIsAlwaysTakable_runningOrNot() {
        assertTrue(SlotLock.allows(GuiLayout.SlotRole.OUTPUT, Action.REMOVE, true));
        assertTrue(SlotLock.allows(GuiLayout.SlotRole.OUTPUT, Action.REMOVE, false));
    }

    @Test
    void outputNeverAcceptsInsertion() {
        assertFalse(SlotLock.allows(GuiLayout.SlotRole.OUTPUT, Action.INSERT, true));
        assertFalse(SlotLock.allows(GuiLayout.SlotRole.OUTPUT, Action.INSERT, false));
    }

    @Test
    void decorativeSlotsAreNeverInteractive() {
        for (Action action : Action.values()) {
            for (boolean running : new boolean[]{true, false}) {
                assertFalse(SlotLock.allows(GuiLayout.SlotRole.FILLER, action, running));
                assertFalse(SlotLock.allows(GuiLayout.SlotRole.INDICATOR, action, running));
            }
        }
    }
}
