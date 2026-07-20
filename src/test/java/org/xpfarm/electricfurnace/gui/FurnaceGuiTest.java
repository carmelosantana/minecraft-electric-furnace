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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for {@link FurnaceGui}'s processing gate ({@link FurnaceGui#mayRun})
 * and status-indicator decision ({@link FurnaceGui#indicatorStateOf}). Both are plain
 * functions over booleans/enums -- no {@code org.bukkit} type, no running server.
 *
 * <p>{@link #mayRun_everyCombination} exhaustively covers all
 * {@code 2 x 2 x 2 x 3 = 24} combinations of (powered, requireSignal, hasFuel,
 * outputSlotState), following the same exhaustive-boolean-combination pattern as
 * {@code MetalClassifierTest}'s coverage of {@code MetalClassifier.resolveBranch}.
 */
class FurnaceGuiTest {

    // ---- mayRun: the processing gate ------------------------------------------------

    @Test
    void mayRun_poweredWithFuelAndEmptyOutput_runs() {
        assertTrue(FurnaceGui.mayRun(true, true, true, FurnaceGui.OutputSlotState.EMPTY));
    }

    @Test
    void mayRun_poweredWithFuelAndMatchingOutput_runs() {
        assertTrue(FurnaceGui.mayRun(true, true, true, FurnaceGui.OutputSlotState.SAME_ITEM));
    }

    @Test
    void mayRun_unpoweredWhenSignalRequired_doesNotRun() {
        assertFalse(FurnaceGui.mayRun(false, true, true, FurnaceGui.OutputSlotState.EMPTY));
    }

    @Test
    void mayRun_unpoweredWhenSignalNotRequired_stillRuns() {
        assertTrue(FurnaceGui.mayRun(false, false, true, FurnaceGui.OutputSlotState.EMPTY));
    }

    @Test
    void mayRun_noFuel_doesNotRun() {
        assertFalse(FurnaceGui.mayRun(true, true, false, FurnaceGui.OutputSlotState.EMPTY));
    }

    @Test
    void mayRun_outputHoldsDifferentItem_doesNotRun_evenWhenPoweredAndFueled() {
        assertFalse(FurnaceGui.mayRun(true, true, true, FurnaceGui.OutputSlotState.DIFFERENT_ITEM));
    }

    /**
     * The complete {@code mayRun} truth table, written out by hand.
     *
     * <p>Rows are {@code {powered, requireSignal, hasFuel, outputSlotState, expected}}.
     * Deliberately NOT derived from the implementation's own boolean expression: an
     * "expected" value that restates the implementation passes against any
     * implementation matching that restatement, wrong ones included. All 24 outcomes are
     * therefore spelled out literally.
     */
    private static final Object[][] MAY_RUN_TABLE = {
            // Powered, signal required -- runs only with fuel and a non-blocking output.
            {true, true, true, FurnaceGui.OutputSlotState.EMPTY, true},
            {true, true, true, FurnaceGui.OutputSlotState.SAME_ITEM, true},
            {true, true, true, FurnaceGui.OutputSlotState.DIFFERENT_ITEM, false},
            {true, true, false, FurnaceGui.OutputSlotState.EMPTY, false},
            {true, true, false, FurnaceGui.OutputSlotState.SAME_ITEM, false},
            {true, true, false, FurnaceGui.OutputSlotState.DIFFERENT_ITEM, false},

            // Powered, signal not required -- same as above; power is moot.
            {true, false, true, FurnaceGui.OutputSlotState.EMPTY, true},
            {true, false, true, FurnaceGui.OutputSlotState.SAME_ITEM, true},
            {true, false, true, FurnaceGui.OutputSlotState.DIFFERENT_ITEM, false},
            {true, false, false, FurnaceGui.OutputSlotState.EMPTY, false},
            {true, false, false, FurnaceGui.OutputSlotState.SAME_ITEM, false},
            {true, false, false, FurnaceGui.OutputSlotState.DIFFERENT_ITEM, false},

            // Unpowered, signal required -- never runs, whatever else is true.
            {false, true, true, FurnaceGui.OutputSlotState.EMPTY, false},
            {false, true, true, FurnaceGui.OutputSlotState.SAME_ITEM, false},
            {false, true, true, FurnaceGui.OutputSlotState.DIFFERENT_ITEM, false},
            {false, true, false, FurnaceGui.OutputSlotState.EMPTY, false},
            {false, true, false, FurnaceGui.OutputSlotState.SAME_ITEM, false},
            {false, true, false, FurnaceGui.OutputSlotState.DIFFERENT_ITEM, false},

            // Unpowered but signal not required -- runs on fuel + non-blocking output.
            {false, false, true, FurnaceGui.OutputSlotState.EMPTY, true},
            {false, false, true, FurnaceGui.OutputSlotState.SAME_ITEM, true},
            {false, false, true, FurnaceGui.OutputSlotState.DIFFERENT_ITEM, false},
            {false, false, false, FurnaceGui.OutputSlotState.EMPTY, false},
            {false, false, false, FurnaceGui.OutputSlotState.SAME_ITEM, false},
            {false, false, false, FurnaceGui.OutputSlotState.DIFFERENT_ITEM, false},
    };

    @Test
    void mayRun_matchesTheHandWrittenTruthTable() {
        for (Object[] row : MAY_RUN_TABLE) {
            boolean powered = (Boolean) row[0];
            boolean requireSignal = (Boolean) row[1];
            boolean hasFuel = (Boolean) row[2];
            FurnaceGui.OutputSlotState state = (FurnaceGui.OutputSlotState) row[3];
            boolean expected = (Boolean) row[4];
            assertEquals(expected, FurnaceGui.mayRun(powered, requireSignal, hasFuel, state),
                    () -> "powered=" + powered + " requireSignal=" + requireSignal
                            + " hasFuel=" + hasFuel + " state=" + state);
        }
    }

    @Test
    void mayRun_truthTableIsExhaustive() {
        assertEquals(2 * 2 * 2 * FurnaceGui.OutputSlotState.values().length, MAY_RUN_TABLE.length);
    }

    // ---- indicatorStateOf ------------------------------------------------------------

    @Test
    void indicatorState_unpoweredWhenSignalRequired_isNoSignal() {
        assertEquals(FurnaceGui.IndicatorState.NO_SIGNAL, FurnaceGui.indicatorStateOf(false, true, true, false));
    }

    @Test
    void indicatorState_unpoweredButSignalNotRequired_andHasFuel_isRunning() {
        assertEquals(FurnaceGui.IndicatorState.RUNNING, FurnaceGui.indicatorStateOf(false, false, true, false));
    }

    @Test
    void indicatorState_poweredButNoFuel_isNoFuel() {
        assertEquals(FurnaceGui.IndicatorState.NO_FUEL, FurnaceGui.indicatorStateOf(true, true, false, false));
    }

    @Test
    void indicatorState_poweredWithFuel_isRunning() {
        assertEquals(FurnaceGui.IndicatorState.RUNNING, FurnaceGui.indicatorStateOf(true, true, true, false));
    }

    @Test
    void indicatorState_noSignalTakesPrecedenceOverNoFuel() {
        // Unpowered AND no fuel: signal is the more fundamental blocker.
        assertEquals(FurnaceGui.IndicatorState.NO_SIGNAL, FurnaceGui.indicatorStateOf(false, true, false, false));
    }

    @Test
    void indicatorState_poweredFueledAndSmelting_isSmelting() {
        assertEquals(FurnaceGui.IndicatorState.SMELTING, FurnaceGui.indicatorStateOf(true, true, true, true));
    }

    @Test
    void indicatorState_noSignalTakesPrecedenceOverSmelting() {
        assertEquals(FurnaceGui.IndicatorState.NO_SIGNAL, FurnaceGui.indicatorStateOf(false, true, true, true));
    }

    @Test
    void indicatorState_noFuelTakesPrecedenceOverSmelting() {
        // A run in progress but the fuel slot has since been emptied: NO_FUEL still wins,
        // exactly mirroring the precedence NO_SIGNAL already has over NO_FUEL.
        assertEquals(FurnaceGui.IndicatorState.NO_FUEL, FurnaceGui.indicatorStateOf(true, true, false, true));
    }

    /**
     * The complete {@code indicatorStateOf} truth table, written out by hand rather than
     * restating the implementation's nested conditional. Rows are
     * {@code {powered, requireSignal, hasFuel, smelting, expectedState}}.
     *
     * <p>Precedence under test: {@code NO_SIGNAL > NO_FUEL > SMELTING > RUNNING}.
     */
    private static final Object[][] INDICATOR_TABLE = {
            // Powered, signal required, fueled -- SMELTING or RUNNING depending on progress.
            {true, true, true, false, FurnaceGui.IndicatorState.RUNNING},
            {true, true, true, true, FurnaceGui.IndicatorState.SMELTING},
            // Powered, signal required, no fuel -- NO_FUEL regardless of smelting.
            {true, true, false, false, FurnaceGui.IndicatorState.NO_FUEL},
            {true, true, false, true, FurnaceGui.IndicatorState.NO_FUEL},

            // Powered, signal not required -- power is moot, same shape as above.
            {true, false, true, false, FurnaceGui.IndicatorState.RUNNING},
            {true, false, true, true, FurnaceGui.IndicatorState.SMELTING},
            {true, false, false, false, FurnaceGui.IndicatorState.NO_FUEL},
            {true, false, false, true, FurnaceGui.IndicatorState.NO_FUEL},

            // Unpowered, signal required -- NO_SIGNAL always, the most fundamental blocker.
            {false, true, true, false, FurnaceGui.IndicatorState.NO_SIGNAL},
            {false, true, true, true, FurnaceGui.IndicatorState.NO_SIGNAL},
            {false, true, false, false, FurnaceGui.IndicatorState.NO_SIGNAL},
            {false, true, false, true, FurnaceGui.IndicatorState.NO_SIGNAL},

            // Unpowered but signal not required -- power is irrelevant, fuel/smelting decide.
            {false, false, true, false, FurnaceGui.IndicatorState.RUNNING},
            {false, false, true, true, FurnaceGui.IndicatorState.SMELTING},
            {false, false, false, false, FurnaceGui.IndicatorState.NO_FUEL},
            {false, false, false, true, FurnaceGui.IndicatorState.NO_FUEL},
    };

    @Test
    void indicatorState_matchesTheHandWrittenTruthTable() {
        for (Object[] row : INDICATOR_TABLE) {
            boolean powered = (Boolean) row[0];
            boolean requireSignal = (Boolean) row[1];
            boolean hasFuel = (Boolean) row[2];
            boolean smelting = (Boolean) row[3];
            FurnaceGui.IndicatorState expected = (FurnaceGui.IndicatorState) row[4];
            assertEquals(expected, FurnaceGui.indicatorStateOf(powered, requireSignal, hasFuel, smelting),
                    () -> "powered=" + powered + " requireSignal=" + requireSignal
                            + " hasFuel=" + hasFuel + " smelting=" + smelting);
        }
    }

    @Test
    void indicatorState_truthTableIsExhaustive() {
        assertEquals(2 * 2 * 2 * 2, INDICATOR_TABLE.length);
    }

    // ---- I4: shift-click transfer arithmetic ----------------------------------------

    @Test
    void transferAmount_emptyDestination_takesWholeSourceWhenItFits() {
        assertEquals(16, FurnaceGui.transferAmount(16, 0, 64));
    }

    @Test
    void transferAmount_emptyDestination_isCappedAtMaxStackSize() {
        assertEquals(64, FurnaceGui.transferAmount(100, 0, 64));
    }

    @Test
    void transferAmount_partiallyFilledDestination_movesOnlyTheRoomLeft() {
        assertEquals(4, FurnaceGui.transferAmount(20, 60, 64));
    }

    @Test
    void transferAmount_fullDestination_movesNothing() {
        assertEquals(0, FurnaceGui.transferAmount(20, 64, 64));
    }

    @Test
    void transferAmount_overfullDestination_movesNothingRatherThanNegative() {
        // Defensive: a slot somehow holding more than its max must never produce a
        // negative transfer, which would silently ADD items to the source stack.
        assertEquals(0, FurnaceGui.transferAmount(20, 70, 64));
    }

    @Test
    void transferAmount_emptySource_movesNothing() {
        assertEquals(0, FurnaceGui.transferAmount(0, 0, 64));
    }

    @Test
    void transferAmount_singleStackSizeItems_moveOneAtMost() {
        assertEquals(1, FurnaceGui.transferAmount(5, 0, 1));
        assertEquals(0, FurnaceGui.transferAmount(5, 1, 1));
    }

    @Test
    void transferAmount_neverExceedsSourceOrDestinationRoom() {
        // Exhaustive over a small grid: the two invariants that make manual shift-click
        // routing incapable of duplicating or destroying items.
        for (int source = 0; source <= 70; source++) {
            for (int dest = 0; dest <= 70; dest++) {
                int moved = FurnaceGui.transferAmount(source, dest, 64);
                int finalSource = source;
                int finalDest = dest;
                assertTrue(moved >= 0, () -> "negative move for source=" + finalSource + " dest=" + finalDest);
                assertTrue(moved <= source, () -> "moved more than source held: source=" + finalSource);
                assertTrue(dest + moved <= Math.max(dest, 64),
                        () -> "overflowed destination: dest=" + finalDest + " moved=" + moved);
            }
        }
    }
}
