/*
 * ElectricFurnace - a redstone-powered smelter that recycles metal gear into ingots.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.electricfurnace.effect;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the effects loop's cost-control decisions as pure functions over primitives.
 *
 * <p>These tests exist because the sibling plugin CopperKingdom shipped a listener
 * that scanned 1,331 blocks on every player movement packet -- a defect that only
 * shows up on a populated server, long after review. The equivalent defect here would
 * be emitting particles for machines nobody can see, or scheduling per-machine tasks.
 * The gate below is the single decision that prevents it, so it is verified with no
 * running server rather than trusted to a runtime smoke test.
 */
class MachineEffectsTest {

    @Nested
    @DisplayName("shouldEmit -- the per-machine, per-run gate")
    class ShouldEmit {

        @Test
        void emitsWhenEnabledActiveAndWatched() {
            assertTrue(MachineEffects.shouldEmit(true, 15, 1, true));
        }

        @Test
        void neverEmitsWhenEffectsAreDisabled() {
            assertFalse(MachineEffects.shouldEmit(false, 15, 4, true));
        }

        @Test
        void neverEmitsForAnIdleMachine() {
            // An unpowered machine is not running; sparking would be a lie as well as
            // wasted packets.
            assertFalse(MachineEffects.shouldEmit(true, 15, 4, false));
        }

        @Test
        void neverEmitsWithNoNearbyPlayers() {
            // The whole point of the radius gate: no observer, no packets. This is the
            // case that keeps a world full of machines from costing anything.
            assertFalse(MachineEffects.shouldEmit(true, 15, 0, true));
        }

        @Test
        void aNegativeNearbyCountIsTreatedAsNone() {
            assertFalse(MachineEffects.shouldEmit(true, 15, -1, true));
        }

        @Test
        void nonPositivePeriodNeverEmits() {
            // A zero or negative period would mean "every tick or faster"; refusing to
            // emit is safer than scheduling a hot loop.
            assertFalse(MachineEffects.shouldEmit(true, 0, 4, true));
            assertFalse(MachineEffects.shouldEmit(true, -5, 4, true));
        }

        // A "gateIsExhaustiveOverEveryCombination" test previously lived here, but it
        // computed its expected value as `enabled && period > 0 && nearby > 0 &&
        // active` -- the implementation's own expression -- so it could not fail for
        // any change that preserved that shape; it was deleted rather than kept as a
        // test that cannot fail. The six concrete-literal cases above (and the two
        // below) already pin every dimension of the gate with a hand-picked
        // input/output pair apiece.

        @Test
        void allFourGatingDimensionsMustHoldSimultaneously() {
            // A hand-written truth table row for the one case not otherwise covered
            // above: every gate open at once is the only combination that must emit.
            assertTrue(MachineEffects.shouldEmit(true, 15, 3, true));
        }

        @Test
        void anySingleGateClosedIsEnoughToSuppressEmission() {
            // Four concrete rows, one per dimension, each flipping exactly one gate
            // shut against an otherwise-fully-open baseline (enabled, period 15,
            // 3 nearby players, active).
            assertFalse(MachineEffects.shouldEmit(false, 15, 3, true), "enabled=false");
            assertFalse(MachineEffects.shouldEmit(true, 0, 3, true), "period=0");
            assertFalse(MachineEffects.shouldEmit(true, 15, 0, true), "nearby=0");
            assertFalse(MachineEffects.shouldEmit(true, 15, 3, false), "active=false");
        }
    }

    @Nested
    @DisplayName("shouldSchedule -- whether the one global task runs at all")
    class ShouldSchedule {

        @Test
        void schedulesWhenEnabledWithAValidPeriod() {
            assertTrue(MachineEffects.shouldSchedule(true, 15));
        }

        @Test
        void doesNotScheduleWhenDisabled() {
            // effects.enabled: false must cost exactly zero -- not a task that runs and
            // then decides to do nothing.
            assertFalse(MachineEffects.shouldSchedule(false, 15));
        }

        @Test
        void doesNotScheduleWithANonPositivePeriod() {
            assertFalse(MachineEffects.shouldSchedule(true, 0));
            assertFalse(MachineEffects.shouldSchedule(true, -1));
        }
    }

    @Nested
    @DisplayName("machineIsActive -- redstone gating")
    class MachineIsActive {

        @Test
        void poweredMachineIsActiveWhenSignalIsRequired() {
            assertTrue(MachineEffects.machineIsActive(true, true));
        }

        @Test
        void unpoweredMachineIsIdleWhenSignalIsRequired() {
            assertFalse(MachineEffects.machineIsActive(false, true));
        }

        @Test
        void machineIsAlwaysActiveWhenSignalIsNotRequired() {
            // With require-redstone-signal: false the machine is always on, so it should
            // always look on.
            assertTrue(MachineEffects.machineIsActive(false, false));
            assertTrue(MachineEffects.machineIsActive(true, false));
        }
    }

    @Nested
    @DisplayName("Bedrock/Geyser particle safety")
    class ParticleSafety {

        @Test
        void onlyTheTwoApprovedParticlesAreEverUsed() {
            // Hard constraint, researched and settled: ELECTRIC_SPARK and
            // CAMPFIRE_COSY_SMOKE are the only particles confirmed mapped in Geyser.
            // Colored DUST does not survive translation to Bedrock. Adding a third
            // particle to MachineEffects must fail here.
            assertEquals(2, MachineEffects.approvedParticleNames().size());
            assertTrue(MachineEffects.approvedParticleNames().contains("ELECTRIC_SPARK"));
            assertTrue(MachineEffects.approvedParticleNames().contains("CAMPFIRE_COSY_SMOKE"));
        }
    }
}
