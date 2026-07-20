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
    @DisplayName("shouldEmitSparks -- sparks follow power, whether or not there is work to do")
    class ShouldEmitSparks {

        @Test
        void sparksFollowPower_notSmelting() {
            assertTrue(MachineEffects.shouldEmitSparks(true, 1, true));
            assertFalse(MachineEffects.shouldEmitSparks(true, 1, false));
        }

        @Test
        void noNearbyPlayers_emitsNothing() {
            assertFalse(MachineEffects.shouldEmitSparks(true, 0, true));
        }

        @Test
        void effectsDisabled_emitsNothing() {
            assertFalse(MachineEffects.shouldEmitSparks(false, 5, true));
        }

        @Test
        void aNegativeNearbyCountIsTreatedAsNone() {
            assertFalse(MachineEffects.shouldEmitSparks(true, -1, true));
        }
    }

    @Nested
    @DisplayName("shouldEmitSmoke -- smoke (and the beacon-hum sound) follow actual smelting, not mere power")
    class ShouldEmitSmoke {

        @Test
        void smokeFollowsSmelting_notMerePower() {
            assertTrue(MachineEffects.shouldEmitSmoke(true, 1, true));
            assertFalse(MachineEffects.shouldEmitSmoke(true, 1, false));
        }

        @Test
        void noNearbyPlayers_emitsNothing() {
            assertFalse(MachineEffects.shouldEmitSmoke(true, 0, true));
        }

        @Test
        void effectsDisabled_emitsNothing() {
            assertFalse(MachineEffects.shouldEmitSmoke(false, 5, true));
        }

        @Test
        void aNegativeNearbyCountIsTreatedAsNone() {
            assertFalse(MachineEffects.shouldEmitSmoke(true, -1, true));
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
