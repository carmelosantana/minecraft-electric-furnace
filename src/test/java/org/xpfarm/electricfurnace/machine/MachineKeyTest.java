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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for {@link MachineKey}. Deliberately imports nothing from
 * {@code org.bukkit} and requires no running server -- this is the whole point of
 * splitting the encode/decode logic out of {@code MachineRegistry}.
 *
 * <p>The most important behavior under test: malformed persisted data must be
 * SKIPPED with a warning, never thrown -- a single corrupt entry must not prevent
 * the rest of a chunk's machines from decoding, and must never propagate an
 * exception into Bukkit's chunk-load path.
 */
class MachineKeyTest {

    private final List<String> warnings = new ArrayList<>();

    private void warn(String message) {
        warnings.add(message);
    }

    // ---- round-trip ----------------------------------------------------------------

    @Test
    void encodeThenDecode_roundTripsExactly() {
        Set<MachineKey.Coord> original = new LinkedHashSet<>(Set.of(
                new MachineKey.Coord(0, 64, 0),
                new MachineKey.Coord(15, 70, 15),
                new MachineKey.Coord(3, -64, 9),
                new MachineKey.Coord(8, 319, 1)));

        String encoded = MachineKey.encode(original);
        Set<MachineKey.Coord> decoded = MachineKey.decode(encoded, this::warn);

        assertEquals(original, decoded);
        assertTrue(warnings.isEmpty(), "round-tripping well-formed data must not warn");
    }

    @Test
    void encodeThenDecode_singleCoordinate_roundTrips() {
        Set<MachineKey.Coord> original = Set.of(new MachineKey.Coord(7, 12, 4));

        String encoded = MachineKey.encode(original);
        Set<MachineKey.Coord> decoded = MachineKey.decode(encoded, this::warn);

        assertEquals(original, decoded);
        assertTrue(warnings.isEmpty());
    }

    // ---- empty input -----------------------------------------------------------------

    @Test
    void decode_emptyString_yieldsEmptySet() {
        Set<MachineKey.Coord> decoded = MachineKey.decode("", this::warn);

        assertTrue(decoded.isEmpty());
        assertTrue(warnings.isEmpty(), "an empty string is not corruption, it's just no machines yet");
    }

    @Test
    void decode_blankString_yieldsEmptySet() {
        Set<MachineKey.Coord> decoded = MachineKey.decode("   ", this::warn);

        assertTrue(decoded.isEmpty());
        assertTrue(warnings.isEmpty());
    }

    @Test
    void decode_nullString_yieldsEmptySet() {
        Set<MachineKey.Coord> decoded = MachineKey.decode(null, this::warn);

        assertTrue(decoded.isEmpty());
        assertTrue(warnings.isEmpty(), "a missing PDC key is not corruption, it's just no machines yet");
    }

    @Test
    void encode_emptyCollection_yieldsEmptyString() {
        assertEquals("", MachineKey.encode(Set.of()));
    }

    // ---- malformed input ignored, never thrown --------------------------------------

    @Test
    void decode_garbageString_isSkippedNotThrown() {
        Set<MachineKey.Coord> decoded = MachineKey.decode("not-a-coordinate-at-all", this::warn);

        assertTrue(decoded.isEmpty());
        assertEquals(1, warnings.size());
    }

    @Test
    void decode_wrongSeparatorCount_tooFew_isSkipped() {
        Set<MachineKey.Coord> decoded = MachineKey.decode("3:64", this::warn);

        assertTrue(decoded.isEmpty());
        assertEquals(1, warnings.size());
    }

    @Test
    void decode_wrongSeparatorCount_tooMany_isSkipped() {
        Set<MachineKey.Coord> decoded = MachineKey.decode("3:64:5:9", this::warn);

        assertTrue(decoded.isEmpty());
        assertEquals(1, warnings.size());
    }

    @Test
    void decode_nonNumericField_isSkipped() {
        Set<MachineKey.Coord> decoded = MachineKey.decode("a:64:5", this::warn);

        assertTrue(decoded.isEmpty());
        assertEquals(1, warnings.size());
    }

    @Test
    void decode_truncatedEntry_trailingComma_isSkippedButDropsOnlyThatEntry() {
        Set<MachineKey.Coord> decoded = MachineKey.decode("3:64:5,", this::warn);

        assertEquals(Set.of(new MachineKey.Coord(3, 64, 5)), decoded);
        assertEquals(1, warnings.size());
    }

    // ---- out-of-range coordinates rejected (x, z must be 0-15) ----------------------

    @Test
    void decode_xAboveRange_isRejected() {
        Set<MachineKey.Coord> decoded = MachineKey.decode("16:64:5", this::warn);

        assertTrue(decoded.isEmpty());
        assertEquals(1, warnings.size());
    }

    @Test
    void decode_zAboveRange_isRejected() {
        Set<MachineKey.Coord> decoded = MachineKey.decode("5:64:16", this::warn);

        assertTrue(decoded.isEmpty());
        assertEquals(1, warnings.size());
    }

    @Test
    void decode_negativeX_isRejected() {
        Set<MachineKey.Coord> decoded = MachineKey.decode("-1:64:5", this::warn);

        assertTrue(decoded.isEmpty());
        assertEquals(1, warnings.size());
    }

    @Test
    void decode_negativeZ_isRejected() {
        Set<MachineKey.Coord> decoded = MachineKey.decode("5:64:-1", this::warn);

        assertTrue(decoded.isEmpty());
        assertEquals(1, warnings.size());
    }

    @Test
    void decode_xAndZAtBoundaries_areAccepted() {
        Set<MachineKey.Coord> decoded = MachineKey.decode("0:1:0,15:2:15", this::warn);

        assertEquals(Set.of(new MachineKey.Coord(0, 1, 0), new MachineKey.Coord(15, 2, 15)), decoded);
        assertTrue(warnings.isEmpty());
    }

    @Test
    void decode_negativeYIsAllowed_onlyXAndZAreRangeRestricted() {
        Set<MachineKey.Coord> decoded = MachineKey.decode("5:-64:5", this::warn);

        assertEquals(Set.of(new MachineKey.Coord(5, -64, 5)), decoded);
        assertTrue(warnings.isEmpty());
    }

    // ---- one bad entry among several good drops only the bad entry ------------------

    @Test
    void decode_oneBadEntryAmongGoodOnes_dropsOnlyTheBadOne() {
        Set<MachineKey.Coord> decoded = MachineKey.decode(
                "1:64:2,garbage,3:70:9,16:64:1,5:80:5", this::warn);

        assertEquals(Set.of(
                new MachineKey.Coord(1, 64, 2),
                new MachineKey.Coord(3, 70, 9),
                new MachineKey.Coord(5, 80, 5)), decoded);
        assertEquals(2, warnings.size(), "two malformed entries (garbage, out-of-range x) should each warn once");
    }

    @Test
    void decode_allEntriesBad_yieldsEmptySetWithOneWarningPerEntry() {
        Set<MachineKey.Coord> decoded = MachineKey.decode("garbage,16:1:1,1:1:-1", this::warn);

        assertTrue(decoded.isEmpty());
        assertEquals(3, warnings.size());
    }

    // ---- warning content ---------------------------------------------------------

    @Test
    void warningMessage_namesTheOffendingEntry() {
        MachineKey.decode("garbage", this::warn);

        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("garbage"), "warning should name the offending entry: " + warnings.get(0));
    }
}
