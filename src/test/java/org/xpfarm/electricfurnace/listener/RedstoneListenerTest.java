/*
 * ElectricFurnace - a redstone-powered smelter that recycles metal gear into ingots.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.electricfurnace.listener;

import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for {@link RedstoneListener}'s neighbour enumeration and power
 * decision -- the S1 fix.
 *
 * <p><b>What went wrong, and what these tests pin.</b> The handler used to test
 * {@code machines.isMachine(event.getBlock())}. But {@code BlockRedstoneEvent} fires on
 * the redstone component whose current changed (wire, torch, repeater) and never on a
 * blast furnace, which is not redstone-sensitive -- so that condition could never hold
 * and the entire handler was dead code. The machine is always a <em>neighbour</em> of
 * the event block, which is why the six-neighbour enumeration is now the load-bearing
 * part and is tested here exhaustively.
 *
 * <p>{@link BlockFace} is a plain Bukkit enum whose {@code getModX/Y/Z} accessors are
 * pure -- referencing them needs no running server, exactly as {@code Material} does in
 * {@code MetalClassifierTest} -- which is what lets
 * {@link #adjacentFaces_matchTheNeighbourOffsets} tie the tested offset set to the
 * {@code BlockFace} array the listener actually uses at runtime.
 */
class RedstoneListenerTest {

    // ---- neighbourOffsets(): the six-neighbour enumeration --------------------------

    @Test
    void neighbourOffsets_areExactlySix() {
        assertEquals(6, RedstoneListener.neighbourOffsets().size());
    }

    @Test
    void neighbourOffsets_areAllDistinct() {
        List<RedstoneListener.Offset> offsets = RedstoneListener.neighbourOffsets();
        assertEquals(offsets.size(), new HashSet<>(offsets).size(), "duplicate neighbour offset");
    }

    @Test
    void neighbourOffsets_areTheSixUnitAxisSteps() {
        // Independently written out: +-x, +-y, +-z and nothing else.
        Set<RedstoneListener.Offset> expected = Set.of(
                new RedstoneListener.Offset(1, 0, 0),
                new RedstoneListener.Offset(-1, 0, 0),
                new RedstoneListener.Offset(0, 1, 0),
                new RedstoneListener.Offset(0, -1, 0),
                new RedstoneListener.Offset(0, 0, 1),
                new RedstoneListener.Offset(0, 0, -1));
        assertEquals(expected, new HashSet<>(RedstoneListener.neighbourOffsets()));
    }

    @Test
    void neighbourOffsets_eachMovesAlongExactlyOneAxisByOne() {
        for (RedstoneListener.Offset offset : RedstoneListener.neighbourOffsets()) {
            int nonZero = (offset.dx() != 0 ? 1 : 0) + (offset.dy() != 0 ? 1 : 0) + (offset.dz() != 0 ? 1 : 0);
            assertEquals(1, nonZero, () -> "not axis-aligned: " + offset);
            int magnitude = Math.abs(offset.dx()) + Math.abs(offset.dy()) + Math.abs(offset.dz());
            assertEquals(1, magnitude, () -> "not a unit step: " + offset);
        }
    }

    @Test
    void neighbourOffsets_excludeTheBlockItself() {
        assertFalse(RedstoneListener.neighbourOffsets().contains(new RedstoneListener.Offset(0, 0, 0)),
                "the event block itself is not one of its own neighbours");
    }

    @Test
    void neighbourOffsets_areSymmetric() {
        // Every offset's opposite is also present: a machine north of the wire and a
        // machine south of it must both be found.
        Set<RedstoneListener.Offset> offsets = new HashSet<>(RedstoneListener.neighbourOffsets());
        for (RedstoneListener.Offset offset : offsets) {
            assertTrue(offsets.contains(new RedstoneListener.Offset(-offset.dx(), -offset.dy(), -offset.dz())),
                    () -> "missing opposite of " + offset);
        }
    }

    @Test
    void adjacentFaces_matchTheNeighbourOffsets() {
        // The link that makes the tests above meaningful: the BlockFace array the
        // listener iterates at runtime must enumerate precisely the offsets tested here.
        Set<RedstoneListener.Offset> fromFaces = new HashSet<>();
        for (BlockFace face : RedstoneListener.ADJACENT_FACES) {
            fromFaces.add(new RedstoneListener.Offset(face.getModX(), face.getModY(), face.getModZ()));
        }
        assertEquals(new HashSet<>(RedstoneListener.neighbourOffsets()), fromFaces);
        assertEquals(6, RedstoneListener.ADJACENT_FACES.length, "no duplicate or missing face");
    }

    // ---- poweredAfterChange(): the rising/falling edge decision ----------------------

    @Test
    void poweredAfterChange_risingEdge_isPoweredEvenIfBlockPowerIsStale() {
        // BlockRedstoneEvent fires before the world applies the new current, so the
        // machine's own getBlockPower() can still read 0 on the tick it becomes powered.
        assertTrue(RedstoneListener.poweredAfterChange(15, 0));
    }

    @Test
    void poweredAfterChange_fallingEdgeWithAnotherSource_staysPowered() {
        assertTrue(RedstoneListener.poweredAfterChange(0, 9));
    }

    @Test
    void poweredAfterChange_fallingEdgeWithNoOtherSource_isUnpowered() {
        assertFalse(RedstoneListener.poweredAfterChange(0, 0));
    }

    @Test
    void poweredAfterChange_bothPresent_isPowered() {
        assertTrue(RedstoneListener.poweredAfterChange(15, 15));
    }

    @Test
    void poweredAfterChange_acrossEverySignalStrengthPair() {
        // Redstone strengths are 0..15; powered iff either side is non-zero.
        for (int newCurrent = 0; newCurrent <= 15; newCurrent++) {
            for (int otherPower = 0; otherPower <= 15; otherPower++) {
                boolean expected = newCurrent != 0 || otherPower != 0;
                int finalNew = newCurrent;
                int finalOther = otherPower;
                assertEquals(expected, RedstoneListener.poweredAfterChange(newCurrent, otherPower),
                        () -> "newCurrent=" + finalNew + " otherPower=" + finalOther);
            }
        }
    }
}
