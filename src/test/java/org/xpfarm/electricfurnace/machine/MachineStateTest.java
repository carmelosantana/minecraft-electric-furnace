/*
 * ElectricFurnace - a redstone-powered smelter that recycles metal gear into ingots.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.electricfurnace.machine;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MachineStateTest {

    /** Stand-in for ItemStack.serializeAsBytes, which needs a running server. */
    private static byte[] enc(String token) {
        return token.getBytes(StandardCharsets.UTF_8);
    }

    private static String dec(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Test
    void roundTrip_fullMachine_preservesEverySlotAndCounter() {
        MachineStateCodec.Frame original = new MachineStateCodec.Frame(
                new byte[][]{enc("i0"), null, enc("i2"), null, enc("i4")},
                enc("redstone"),
                enc("ingot"),
                57,
                143);

        MachineStateCodec.Frame restored = MachineStateCodec.decodeFrame(
                MachineStateCodec.encodeFrame(original));

        assertEquals(5, restored.inputs().length);
        assertEquals("i0", dec(restored.inputs()[0]));
        assertNull(restored.inputs()[1]);
        assertEquals("i2", dec(restored.inputs()[2]));
        assertNull(restored.inputs()[3]);
        assertEquals("i4", dec(restored.inputs()[4]));
        assertEquals("redstone", dec(restored.fuel()));
        assertEquals("ingot", dec(restored.output()));
        assertEquals(57, restored.progressTicks());
        assertEquals(143, restored.burnTicksRemaining());
    }

    @Test
    void roundTrip_emptyMachine_survives() {
        MachineStateCodec.Frame empty = new MachineStateCodec.Frame(
                new byte[5][], null, null, 0, 0);

        MachineStateCodec.Frame restored = MachineStateCodec.decodeFrame(
                MachineStateCodec.encodeFrame(empty));

        for (byte[] input : restored.inputs()) {
            assertNull(input);
        }
        assertNull(restored.fuel());
        assertNull(restored.output());
        assertEquals(0, restored.progressTicks());
        assertEquals(0, restored.burnTicksRemaining());
    }

    @Test
    void roundTrip_largeStackPayload_isNotTruncated() {
        byte[] big = new byte[8192];
        for (int i = 0; i < big.length; i++) {
            big[i] = (byte) (i % 251);
        }
        MachineStateCodec.Frame original = new MachineStateCodec.Frame(
                new byte[][]{big, null, null, null, null}, null, null, 0, 0);

        MachineStateCodec.Frame restored = MachineStateCodec.decodeFrame(
                MachineStateCodec.encodeFrame(original));

        assertArrayEquals(big, restored.inputs()[0]);
    }

    @Test
    void decodeFrame_truncatedBytes_yieldsEmptyRatherThanThrowing() {
        byte[] encoded = MachineStateCodec.encodeFrame(new MachineStateCodec.Frame(
                new byte[][]{enc("i0"), null, null, null, null}, enc("f"), null, 3, 4));
        byte[] truncated = new byte[encoded.length / 2];
        System.arraycopy(encoded, 0, truncated, 0, truncated.length);

        MachineStateCodec.Frame restored = MachineStateCodec.decodeFrame(truncated);

        assertEquals(0, restored.progressTicks());
        assertNull(restored.fuel());
    }

    @Test
    void decodeFrame_garbage_yieldsEmptyRatherThanThrowing() {
        MachineStateCodec.Frame restored =
                MachineStateCodec.decodeFrame(new byte[]{9, 9, 9, 9, 9, 9, 9});

        assertEquals(0, restored.progressTicks());
        assertEquals(0, restored.burnTicksRemaining());
    }

    @Test
    void isIdle_reflectsProgress() {
        MachineState state = MachineState.empty();
        assertEquals(true, state.isIdle());
        state.setProgressTicks(1);
        assertEquals(false, state.isIdle());
    }
}
