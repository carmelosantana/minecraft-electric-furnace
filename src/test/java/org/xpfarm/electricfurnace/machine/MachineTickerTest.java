package org.xpfarm.electricfurnace.machine;

import org.junit.jupiter.api.Test;
import org.xpfarm.electricfurnace.machine.MachineTicker.Conditions;
import org.xpfarm.electricfurnace.machine.MachineTicker.Outcome;
import org.xpfarm.electricfurnace.machine.MachineTicker.Step;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MachineTickerTest {

    private static final int SMELT_TICKS = 80;
    private static final int BURN_PER_REDSTONE = 200;

    private static Conditions ready() {
        return new Conditions(true, true, true, false, true);
    }

    private static Step run(Conditions conditions, int progress, int burn) {
        return MachineTicker.step(conditions, progress, burn, SMELT_TICKS, BURN_PER_REDSTONE);
    }

    @Test
    void unpowered_stallsWithoutLosingProgressOrBurn() {
        Step step = run(new Conditions(false, true, true, false, true), 40, 100);

        assertEquals(Outcome.STALLED_NO_POWER, step.outcome());
        assertEquals(40, step.progressTicks());
        assertEquals(100, step.burnTicksRemaining());
        assertFalse(step.consumeOneFuel());
    }

    @Test
    void signalNotRequired_runsEvenWhenUnpowered() {
        Step step = run(new Conditions(false, false, true, false, true), 0, 100);

        assertEquals(Outcome.ADVANCED, step.outcome());
        assertEquals(1, step.progressTicks());
    }

    @Test
    void invalidRecipe_resetsProgressBecauseTheInputsThemselvesChanged() {
        Step step = run(new Conditions(true, true, false, false, true), 40, 100);

        assertEquals(Outcome.STALLED_NO_RECIPE, step.outcome());
        assertEquals(0, step.progressTicks());
        assertEquals(100, step.burnTicksRemaining());
    }

    @Test
    void blockedOutput_stallsButKeepsProgressSoItResumes() {
        Step step = run(new Conditions(true, true, true, true, true), 40, 100);

        assertEquals(Outcome.STALLED_OUTPUT_BLOCKED, step.outcome());
        assertEquals(40, step.progressTicks());
        assertEquals(100, step.burnTicksRemaining());
    }

    @Test
    void noBurnAndNoFuel_stallsWithoutLosingProgress() {
        Step step = run(new Conditions(true, true, true, false, false), 40, 0);

        assertEquals(Outcome.STALLED_NO_FUEL, step.outcome());
        assertEquals(40, step.progressTicks());
        assertEquals(0, step.burnTicksRemaining());
        assertFalse(step.consumeOneFuel());
    }

    @Test
    void noBurnButFuelPresent_buysBurnTimeAndAdvancesInTheSameTick() {
        Step step = run(ready(), 40, 0);

        assertEquals(Outcome.ADVANCED, step.outcome());
        assertTrue(step.consumeOneFuel());
        assertEquals(BURN_PER_REDSTONE - 1, step.burnTicksRemaining());
        assertEquals(41, step.progressTicks());
    }

    @Test
    void blockedRun_neverBuysFuelItCannotUse() {
        Step blockedOutput = run(new Conditions(true, true, true, true, true), 0, 0);
        Step badRecipe = run(new Conditions(true, true, false, false, true), 0, 0);
        Step unpowered = run(new Conditions(false, true, true, false, true), 0, 0);

        assertFalse(blockedOutput.consumeOneFuel());
        assertFalse(badRecipe.consumeOneFuel());
        assertFalse(unpowered.consumeOneFuel());
    }

    @Test
    void advancing_spendsExactlyOneBurnTickPerProgressTick() {
        Step step = run(ready(), 10, 50);

        assertEquals(Outcome.ADVANCED, step.outcome());
        assertEquals(11, step.progressTicks());
        assertEquals(49, step.burnTicksRemaining());
    }

    @Test
    void finalTick_completesAndResetsProgress() {
        Step step = run(ready(), SMELT_TICKS - 1, 50);

        assertEquals(Outcome.COMPLETED, step.outcome());
        assertEquals(0, step.progressTicks());
        assertEquals(49, step.burnTicksRemaining());
    }

    @Test
    void fullRunFromCold_takesExactlySmeltTicksAndOneDust() {
        int progress = 0;
        int burn = 0;
        int dustSpent = 0;
        int ticks = 0;

        Outcome outcome;
        do {
            Step step = run(ready(), progress, burn);
            outcome = step.outcome();
            if (step.consumeOneFuel()) {
                dustSpent++;
            }
            progress = step.progressTicks();
            burn = step.burnTicksRemaining();
            ticks++;
        } while (outcome != Outcome.COMPLETED && ticks < 1000);

        assertEquals(Outcome.COMPLETED, outcome);
        assertEquals(SMELT_TICKS, ticks);
        assertEquals(1, dustSpent);
    }

    @Test
    void idleMachine_neverSpendsBurnTime() {
        Step step = run(new Conditions(true, true, false, false, true), 0, 200);

        assertEquals(200, step.burnTicksRemaining());
    }
}
