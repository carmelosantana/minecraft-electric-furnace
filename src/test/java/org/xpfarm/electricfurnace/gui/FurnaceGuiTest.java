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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for {@link FurnaceGui}'s status-indicator decision
 * ({@link FurnaceGui#indicatorStateOf}). It is a plain function over booleans/enums --
 * no {@code org.bukkit} type, no running server.
 */
class FurnaceGuiTest {

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

    // ---- Review fix pass 1, finding 4: closeAll's shutdown step ordering -------------
    //
    // Restores the property the deleted ShutdownStep/shutdownSteps() pair used to pin:
    // the item-safety step (persist, or return-to-player) must never come after the
    // inventory is closed. closeAll now has two branches instead of one unconditional
    // sequence, so both are asserted here.

    @Test
    void closeAllSteps_whenPersistSucceeded_isPersistThenClose() {
        assertEquals(
                List.of(FurnaceGui.CloseAllStep.PERSIST, FurnaceGui.CloseAllStep.CLOSE),
                FurnaceGui.closeAllSteps(true));
    }

    @Test
    void closeAllSteps_whenPersistFailed_isReturnThenClose() {
        assertEquals(
                List.of(FurnaceGui.CloseAllStep.RETURN, FurnaceGui.CloseAllStep.CLOSE),
                FurnaceGui.closeAllSteps(false));
    }

    @Test
    void closeAllSteps_itemSafetyStepAlwaysPrecedesClose_inBothBranches() {
        for (boolean persistSucceeded : new boolean[] {true, false}) {
            List<FurnaceGui.CloseAllStep> steps = FurnaceGui.closeAllSteps(persistSucceeded);
            int closeIndex = steps.indexOf(FurnaceGui.CloseAllStep.CLOSE);
            assertTrue(closeIndex >= 0, () -> "CLOSE missing for persistSucceeded=" + persistSucceeded);
            for (int i = 0; i < steps.size(); i++) {
                FurnaceGui.CloseAllStep step = steps.get(i);
                boolean isItemSafetyStep =
                        step == FurnaceGui.CloseAllStep.PERSIST || step == FurnaceGui.CloseAllStep.RETURN;
                int finalI = i;
                boolean finalPersistSucceeded = persistSucceeded;
                if (isItemSafetyStep) {
                    assertTrue(finalI < closeIndex, () -> "item-safety step " + step
                            + " did not precede CLOSE for persistSucceeded=" + finalPersistSucceeded);
                }
            }
        }
    }

    @Test
    void closeAllSteps_neverProducesReturnAndPersistTogether() {
        // Exactly one item-safety step per branch -- never both, never neither.
        assertEquals(2, FurnaceGui.closeAllSteps(true).size());
        assertEquals(2, FurnaceGui.closeAllSteps(false).size());
        assertFalse(FurnaceGui.closeAllSteps(true).contains(FurnaceGui.CloseAllStep.RETURN));
        assertFalse(FurnaceGui.closeAllSteps(false).contains(FurnaceGui.CloseAllStep.PERSIST));
    }

    // ---- Review fix pass 2: refreshFromState vs. the deferred GUI->state sync -------
    //
    // MachineGuiListener#scheduleSync defers syncToState by one tick. If a future
    // driver (MachineTicker) calls refreshFromState in the same window -- and it would
    // run first, since a repeating task registered at onEnable has a lower task id than
    // a one-shot scheduled afterward -- refreshFromState would overwrite the inventory
    // with stale state just before the deferred sync overwrites state with that same
    // now-clobbered inventory: an item placed into an input slot exists nowhere, and an
    // item taken from the output slot exists in both the player's inventory and state.
    // shouldSkipRefresh is the pure guard that closes this: a pending-sync count > 0
    // means a deferred callback has not run yet, so refreshFromState must skip this
    // call and let the following call (the driver's next tick, after the deferred
    // callback has cleared the count) refresh instead.

    @Test
    void shouldSkipRefresh_whenNoPendingSync_isFalse() {
        assertFalse(FurnaceGui.shouldSkipRefresh(0));
    }

    @Test
    void shouldSkipRefresh_whenOnePendingSync_isTrue() {
        assertTrue(FurnaceGui.shouldSkipRefresh(1));
    }

    @Test
    void shouldSkipRefresh_whenMultiplePendingSyncs_isTrue() {
        // Two viewers of the same shared inventory can each schedule their own deferred
        // sync in the same tick -- the guard must stay tripped until every one of them
        // has cleared, not just the first.
        assertTrue(FurnaceGui.shouldSkipRefresh(2));
    }

    @Test
    void shouldSkipRefresh_neverTrippedByANonPositiveCount() {
        // Defensive: a count that somehow went negative (more clears than marks) must
        // never be read as "pending" -- that would wedge refreshFromState forever.
        assertFalse(FurnaceGui.shouldSkipRefresh(-1));
    }
}
