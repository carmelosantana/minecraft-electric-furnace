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

import org.bukkit.block.Block;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Proxy;
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

    /** Lets a test build a hand-crafted frame that {@code encodeFrame} would never produce. */
    @FunctionalInterface
    private interface FrameWriter {
        void write(DataOutputStream out) throws IOException;
    }

    private static byte[] rawFrame(FrameWriter writer) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            writer.write(out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return buffer.toByteArray();
    }

    /** Mirrors {@code MachineStateCodec}'s private payload format for hand-built frames. */
    private static void writeTestPayload(DataOutputStream out, byte[] payload) throws IOException {
        if (payload == null) {
            out.writeInt(-1);
            return;
        }
        out.writeInt(payload.length);
        out.write(payload);
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
    void decodeFrame_oversizedPayloadLength_yieldsEmptyRatherThanAllocating() {
        // A hostile/corrupt length header claiming a payload far above the 1 MiB cap must not
        // trigger a giant allocation; it should be treated like any other malformed input.
        byte[] hostile = rawFrame(out -> {
            out.writeInt(1); // version
            out.writeInt(9); // progress
            out.writeInt(9); // burn
            out.writeInt(0); // inputCount
            out.writeInt(Integer.MAX_VALUE); // fuel payload length header: nowhere near real
        });

        MachineStateCodec.Frame restored = MachineStateCodec.decodeFrame(hostile);

        for (byte[] input : restored.inputs()) {
            assertNull(input);
        }
        assertNull(restored.fuel());
        assertNull(restored.output());
        assertEquals(0, restored.progressTicks());
        assertEquals(0, restored.burnTicksRemaining());
    }

    @Test
    void decodeFrame_inputCountBeyondCapacity_discardsExtraButStaysSynced() {
        // inputCount claims more slots than MachineState.INPUT_COUNT; the decoder must still
        // consume every claimed payload from the stream (or fuel/output would desync), while
        // truncating the returned array to INPUT_COUNT.
        byte[] hostile = rawFrame(out -> {
            out.writeInt(1); // version
            out.writeInt(11); // progress
            out.writeInt(22); // burn
            out.writeInt(8); // inputCount: more than MachineState.INPUT_COUNT
            for (int i = 0; i < 8; i++) {
                writeTestPayload(out, enc("i" + i));
            }
            writeTestPayload(out, enc("fuel"));
            writeTestPayload(out, enc("output"));
        });

        MachineStateCodec.Frame restored = MachineStateCodec.decodeFrame(hostile);

        assertEquals(MachineState.INPUT_COUNT, restored.inputs().length);
        for (int i = 0; i < MachineState.INPUT_COUNT; i++) {
            assertEquals("i" + i, dec(restored.inputs()[i]));
        }
        assertEquals("fuel", dec(restored.fuel()));
        assertEquals("output", dec(restored.output()));
        assertEquals(11, restored.progressTicks());
        assertEquals(22, restored.burnTicksRemaining());
    }

    /**
     * A {@link Block} that does nothing, so a {@link MachineState} can be constructed with
     * no running server.
     *
     * <p>{@code MachineState} needs a block because its item storage is a Bukkit inventory
     * bound to one, but its run counters are ordinary integer logic that must stay
     * testable. A reflective stub gets that: {@code MachineState.empty} only stores the
     * reference, and the counter methods below never reach the inventory, so no method on
     * this proxy is ever actually called. Anything that did touch the slots would fail
     * loudly here rather than silently pass.
     */
    private static Block stubBlock() {
        return (Block) Proxy.newProxyInstance(
                Block.class.getClassLoader(),
                new Class<?>[]{Block.class},
                (proxy, method, args) -> {
                    throw new UnsupportedOperationException(
                            "MachineState must not touch the block for " + method.getName());
                });
    }

    @Test
    void isIdle_reflectsProgress() {
        MachineState state = MachineState.empty(stubBlock());
        assertEquals(true, state.isIdle());
        state.setProgressTicks(1);
        assertEquals(false, state.isIdle());
    }

    @Test
    void progressAndBurnCounters_clampNegativesToZero() {
        MachineState state = MachineState.empty(stubBlock());

        state.setProgressTicks(-5);
        state.setBurnTicksRemaining(-1);

        assertEquals(0, state.progressTicks());
        assertEquals(0, state.burnTicksRemaining());
        assertEquals(true, state.isIdle());
    }
}
