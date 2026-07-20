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

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.util.logging.Logger;

/**
 * Encodes and decodes {@link MachineState} for the machine block's PDC.
 *
 * <p>The wire format is split from the {@code ItemStack} conversion on purpose. The
 * {@link Frame} layer deals only in {@code byte[]} payloads and is fully unit-testable
 * with no running server -- which matters, because a framing bug here silently destroys
 * every item in every machine on the next restart. The {@code ItemStack} layer is a thin
 * adapter over Paper's {@code serializeAsBytes}/{@code deserializeBytes}.
 *
 * <p><b>Never throws.</b> Malformed bytes decode to an empty machine and log a warning.
 * Throwing here would propagate into a chunk-load path.
 */
public final class MachineStateCodec {

    private static final Logger LOGGER = Logger.getLogger("ElectricFurnace");

    /** PDC key on the machine block holding the encoded state. */
    public static final NamespacedKey KEY = new NamespacedKey("electricfurnace", "machine_state");

    private static final int FORMAT_VERSION = 1;

    /**
     * Upper bound on a single payload's declared length. A serialized {@code ItemStack} is
     * realistically a few KB; 1 MiB is generous headroom while still bounding the damage a
     * corrupted length header can do. Without this cap, {@code readPayload} would allocate
     * straight from an untrusted 4-byte field -- up to ~2GB -- on every chunk load.
     */
    private static final int MAX_PAYLOAD_LENGTH = 1024 * 1024;

    private MachineStateCodec() {
    }

    /** The server-independent view of a machine's persisted bytes. */
    public record Frame(byte[][] inputs, byte[] fuel, byte[] output, int progressTicks, int burnTicksRemaining) {
    }

    /**
     * Wire format, all big-endian:
     * {@code version:int, progress:int, burn:int, inputCount:int,
     *  then inputCount payloads, then fuel payload, then output payload}.
     * Each payload is {@code length:int} followed by that many bytes; a length of
     * {@code -1} means the slot is empty.
     */
    public static byte[] encodeFrame(Frame frame) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            out.writeInt(FORMAT_VERSION);
            out.writeInt(frame.progressTicks());
            out.writeInt(frame.burnTicksRemaining());
            out.writeInt(frame.inputs().length);
            for (byte[] input : frame.inputs()) {
                writePayload(out, input);
            }
            writePayload(out, frame.fuel());
            writePayload(out, frame.output());
        } catch (IOException e) {
            // ByteArrayOutputStream does not perform IO; unreachable in practice.
            LOGGER.warning("ElectricFurnace: failed to encode machine state: " + e.getMessage());
            return new byte[0];
        }
        return buffer.toByteArray();
    }

    /** Decodes {@code bytes}, yielding an empty frame for anything malformed. */
    public static Frame decodeFrame(byte[] bytes) {
        Frame empty = new Frame(new byte[MachineState.INPUT_COUNT][], null, null, 0, 0);
        if (bytes == null || bytes.length == 0) {
            return empty;
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            int version = in.readInt();
            if (version != FORMAT_VERSION) {
                LOGGER.warning("ElectricFurnace: machine state has unsupported format version "
                        + version + "; treating the machine as empty.");
                return empty;
            }
            int progress = in.readInt();
            int burn = in.readInt();
            int inputCount = in.readInt();
            if (inputCount < 0 || inputCount > 64) {
                return empty;
            }
            byte[][] inputs = new byte[MachineState.INPUT_COUNT][];
            for (int i = 0; i < inputCount; i++) {
                byte[] payload = readPayload(in);
                if (i < inputs.length) {
                    inputs[i] = payload;
                }
            }
            byte[] fuel = readPayload(in);
            byte[] output = readPayload(in);
            return new Frame(inputs, fuel, output, Math.max(0, progress), Math.max(0, burn));
        } catch (Throwable e) {
            // Deliberately catches Throwable, not just IOException/RuntimeException: a
            // corrupted length header (see MAX_PAYLOAD_LENGTH) or any other malformed input
            // can trigger an OutOfMemoryError, which is an Error and would otherwise escape
            // straight into the chunk-load path. Nothing in this method may throw.
            LOGGER.warning("ElectricFurnace: machine state could not be decoded ("
                    + e.getClass().getSimpleName() + "); treating the machine as empty.");
            return empty;
        }
    }

    private static void writePayload(DataOutputStream out, byte[] payload) throws IOException {
        if (payload == null) {
            out.writeInt(-1);
            return;
        }
        out.writeInt(payload.length);
        out.write(payload);
    }

    private static byte[] readPayload(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0) {
            return null;
        }
        if (length > MAX_PAYLOAD_LENGTH) {
            // Untrusted length header from the PDC; treat it as malformed input rather than
            // allocating on the caller's say-so. The catch in decodeFrame turns this into an
            // empty machine.
            throw new IOException("payload length " + length + " exceeds cap of "
                    + MAX_PAYLOAD_LENGTH + " bytes");
        }
        byte[] payload = new byte[length];
        in.readFully(payload);
        return payload;
    }

    // ---- ItemStack adapter (needs a running server; not unit-tested) ----------------

    /** Encodes a live state for storage in a block PDC. */
    public static byte[] encode(MachineState state) {
        byte[][] inputs = new byte[MachineState.INPUT_COUNT][];
        for (int i = 0; i < inputs.length; i++) {
            inputs[i] = toBytes(state.inputs()[i]);
        }
        return encodeFrame(new Frame(inputs, toBytes(state.fuel()), toBytes(state.output()),
                state.progressTicks(), state.burnTicksRemaining()));
    }

    /** Decodes bytes read from a block PDC into a live state. */
    public static MachineState decode(byte[] bytes) {
        Frame frame = decodeFrame(bytes);
        MachineState state = MachineState.empty();
        for (int i = 0; i < MachineState.INPUT_COUNT; i++) {
            state.inputs()[i] = fromBytes(frame.inputs()[i]);
        }
        state.setFuel(fromBytes(frame.fuel()));
        state.setOutput(fromBytes(frame.output()));
        state.setProgressTicks(frame.progressTicks());
        state.setBurnTicksRemaining(frame.burnTicksRemaining());
        return state;
    }

    private static byte[] toBytes(ItemStack stack) {
        return stack == null ? null : stack.serializeAsBytes();
    }

    private static ItemStack fromBytes(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        try {
            return ItemStack.deserializeBytes(bytes);
        } catch (RuntimeException e) {
            LOGGER.warning("ElectricFurnace: dropping an unreadable item from a machine slot ("
                    + e.getClass().getSimpleName() + ").");
            return null;
        }
    }
}
