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

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Pure, Bukkit-free encode/decode logic for the machine locations persisted in a
 * chunk's {@code PersistentDataContainer} under {@code electricfurnace:machines}.
 *
 * <p>The persisted form is a single {@code STRING}: comma-separated entries of
 * {@code "x:y:z"}, where {@code x} and {@code z} are chunk-relative (0-15) and
 * {@code y} is the block's absolute world height with no range restriction.
 *
 * <p>This class touches nothing but {@link String}, {@code int}, and {@link Set} --
 * no {@code org.bukkit} types anywhere -- which is exactly what lets
 * {@code MachineKeyTest} exercise it with no running server. All Bukkit-facing glue
 * (reading/writing the chunk PDC, converting a {@code Block} to a chunk-relative
 * {@link Coord} and back) belongs in {@code MachineRegistry}, not here.
 *
 * <p><b>Contract:</b> {@link #decode} never throws. Malformed data -- a garbage
 * string, the wrong number of {@code ":"}-separated fields, a non-numeric field, or
 * an out-of-range {@code x}/{@code z} -- is skipped entry-by-entry with one warning
 * per bad entry sent to the caller-supplied {@link Consumer}. A single corrupt entry
 * never prevents the rest of a chunk's well-formed entries from decoding. This is the
 * single most important behavior in this class: a corrupt chunk must never break
 * chunk loading.
 */
public final class MachineKey {

    /** Chunk-relative coordinates are 0-15 inclusive, per Minecraft's 16x16 chunk grid. */
    public static final int MIN_RELATIVE = 0;
    public static final int MAX_RELATIVE = 15;

    private static final String ENTRY_SEPARATOR = ",";
    private static final String FIELD_SEPARATOR = ":";

    private MachineKey() {
    }

    /**
     * One machine location within a chunk: {@code x} and {@code z} are chunk-relative
     * (0-15), {@code y} is the block's absolute world height. Deliberately unvalidated
     * by the record itself -- validation lives in {@link #decode}, where malformed
     * data must be reported through the warning sink rather than thrown as an
     * exception.
     */
    public record Coord(int x, int y, int z) {
    }

    /** Whether {@code x} and {@code z} are both within the valid chunk-relative range. */
    public static boolean isValidRelative(int x, int z) {
        return x >= MIN_RELATIVE && x <= MAX_RELATIVE && z >= MIN_RELATIVE && z <= MAX_RELATIVE;
    }

    /**
     * Encodes {@code coords} as comma-separated {@code "x:y:z"} entries. An empty
     * collection encodes to the empty string. Does not validate its input -- callers
     * (namely {@code MachineRegistry}) are expected to only ever hold coordinates
     * derived from real chunk-relative block positions, which are valid by
     * construction (Bukkit's {@code chunkX = blockX & 15} is always in {@code
     * [0, 15]}).
     */
    public static String encode(Collection<Coord> coords) {
        Objects.requireNonNull(coords, "coords");
        return coords.stream()
                .map(c -> c.x() + FIELD_SEPARATOR + c.y() + FIELD_SEPARATOR + c.z())
                .collect(Collectors.joining(ENTRY_SEPARATOR));
    }

    /**
     * Decodes the comma-separated {@code "x:y:z"} entries in {@code raw} into a set
     * of {@link Coord}s.
     *
     * <ul>
     *   <li>{@code raw} is {@code null} or blank: returns an empty set, no warning --
     *       this is the normal "no machines registered yet" case, not corruption.</li>
     *   <li>An entry with the wrong number of {@code ":"}-separated fields, a
     *       non-numeric field, or an {@code x}/{@code z} outside {@code [0, 15]}: that
     *       one entry is skipped and a warning naming it is sent to {@code warn}. The
     *       rest of the entries still decode normally.</li>
     * </ul>
     *
     * <p>Never throws.
     *
     * @param raw  the persisted PDC string, possibly {@code null} or corrupt
     * @param warn sink for one warning per malformed entry; must not be {@code null}
     */
    public static Set<Coord> decode(String raw, Consumer<String> warn) {
        Objects.requireNonNull(warn, "warn");
        Set<Coord> result = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) {
            return result;
        }
        for (String entry : raw.split(ENTRY_SEPARATOR, -1)) {
            Coord coord = decodeEntry(entry, warn);
            if (coord != null) {
                result.add(coord);
            }
        }
        return result;
    }

    private static Coord decodeEntry(String entry, Consumer<String> warn) {
        String[] parts = entry.split(FIELD_SEPARATOR, -1);
        if (parts.length != 3) {
            warn.accept("ElectricFurnace machine PDC: skipping entry '" + entry
                    + "' (expected 3 ':'-separated fields, found " + parts.length + ").");
            return null;
        }

        Integer x = parseInt(parts[0]);
        Integer y = parseInt(parts[1]);
        Integer z = parseInt(parts[2]);
        if (x == null || y == null || z == null) {
            warn.accept("ElectricFurnace machine PDC: skipping entry '" + entry
                    + "' (non-numeric coordinate field).");
            return null;
        }

        if (!isValidRelative(x, z)) {
            warn.accept("ElectricFurnace machine PDC: skipping entry '" + entry
                    + "' (x and z must be in [" + MIN_RELATIVE + ", " + MAX_RELATIVE + "], chunk-relative).");
            return null;
        }

        return new Coord(x, y, z);
    }

    private static Integer parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
