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

    @Test
    void mayRun_everyCombination() {
        for (boolean powered : new boolean[] {true, false}) {
            for (boolean requireSignal : new boolean[] {true, false}) {
                for (boolean hasFuel : new boolean[] {true, false}) {
                    for (FurnaceGui.OutputSlotState state : FurnaceGui.OutputSlotState.values()) {
                        boolean expected = (!requireSignal || powered) && hasFuel
                                && state != FurnaceGui.OutputSlotState.DIFFERENT_ITEM;
                        assertEquals(expected, FurnaceGui.mayRun(powered, requireSignal, hasFuel, state),
                                () -> "powered=" + powered + " requireSignal=" + requireSignal
                                        + " hasFuel=" + hasFuel + " state=" + state);
                    }
                }
            }
        }
    }

    // ---- indicatorStateOf ------------------------------------------------------------

    @Test
    void indicatorState_unpoweredWhenSignalRequired_isNoSignal() {
        assertEquals(FurnaceGui.IndicatorState.NO_SIGNAL, FurnaceGui.indicatorStateOf(false, true, true));
    }

    @Test
    void indicatorState_unpoweredButSignalNotRequired_andHasFuel_isRunning() {
        assertEquals(FurnaceGui.IndicatorState.RUNNING, FurnaceGui.indicatorStateOf(false, false, true));
    }

    @Test
    void indicatorState_poweredButNoFuel_isNoFuel() {
        assertEquals(FurnaceGui.IndicatorState.NO_FUEL, FurnaceGui.indicatorStateOf(true, true, false));
    }

    @Test
    void indicatorState_poweredWithFuel_isRunning() {
        assertEquals(FurnaceGui.IndicatorState.RUNNING, FurnaceGui.indicatorStateOf(true, true, true));
    }

    @Test
    void indicatorState_noSignalTakesPrecedenceOverNoFuel() {
        // Unpowered AND no fuel: signal is the more fundamental blocker.
        assertEquals(FurnaceGui.IndicatorState.NO_SIGNAL, FurnaceGui.indicatorStateOf(false, true, false));
    }

    @Test
    void indicatorState_everyCombination() {
        for (boolean powered : new boolean[] {true, false}) {
            for (boolean requireSignal : new boolean[] {true, false}) {
                for (boolean hasFuel : new boolean[] {true, false}) {
                    boolean effectivePowered = !requireSignal || powered;
                    FurnaceGui.IndicatorState expected = !effectivePowered
                            ? FurnaceGui.IndicatorState.NO_SIGNAL
                            : !hasFuel ? FurnaceGui.IndicatorState.NO_FUEL : FurnaceGui.IndicatorState.RUNNING;
                    assertEquals(expected, FurnaceGui.indicatorStateOf(powered, requireSignal, hasFuel),
                            () -> "powered=" + powered + " requireSignal=" + requireSignal + " hasFuel=" + hasFuel);
                }
            }
        }
    }
}
