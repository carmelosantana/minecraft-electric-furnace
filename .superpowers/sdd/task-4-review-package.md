# Review package: 47e4d17..ae86948

ae86948 Task 4: machine persistence via chunk PersistentDataContainer

 .superpowers/sdd/task-4-report.md                  | 129 ++++++++++++
 .../electricfurnace/item/MaterialContract.java     |   7 +
 .../xpfarm/electricfurnace/machine/MachineKey.java | 149 ++++++++++++++
 .../electricfurnace/machine/MachineRegistry.java   | 110 ++++++++++
 .../electricfurnace/machine/MachineKeyTest.java    | 222 +++++++++++++++++++++
 5 files changed, 617 insertions(+)

```diff
diff --git a/.superpowers/sdd/task-4-report.md b/.superpowers/sdd/task-4-report.md
new file mode 100644
index 0000000..39089f2
--- /dev/null
+++ b/.superpowers/sdd/task-4-report.md
@@ -0,0 +1,129 @@
+# Task 4 — Machine persistence — Report
+
+## What was implemented
+
+Followed TDD: wrote `MachineKeyTest` first, confirmed it failed to compile with no
+implementation present (20 tests, `package MachineKey does not exist`), then
+implemented `MachineKey` and `MachineRegistry`.
+
+- **`MachineKey`** — the pure, Bukkit-free encode/decode core for the machine
+  locations persisted in a chunk's `PersistentDataContainer`. Nested record
+  `Coord(int x, int y, int z)` represents one machine location: `x`/`z` are
+  chunk-relative (0-15), `y` is the block's absolute world height with no range
+  restriction. Persisted form: comma-separated `"x:y:z"` entries.
+  - `encode(Collection<Coord>)` — joins entries; empty collection encodes to `""`.
+  - `decode(String raw, Consumer<String> warn)` — the critical method. Never throws.
+    `raw == null` or blank returns an empty set with no warning (missing PDC key /
+    no machines yet is not corruption). Otherwise each comma-separated entry is
+    decoded independently: wrong `":"`-field count, a non-numeric field, or an
+    out-of-range `x`/`z` causes that one entry to be skipped with exactly one
+    warning naming the offending entry — every other entry in the same string still
+    decodes normally. This satisfies the plan's core requirement: a single corrupt
+    entry must never prevent the rest of a chunk's machines from loading and must
+    never propagate an exception into Bukkit's chunk-load path.
+  - `isValidRelative(int x, int z)` — the shared range check (`[0, 15]` for both).
+
+- **`MachineRegistry`** — the thin Bukkit-facing glue implementing the plan's exact
+  API: `isMachine(Block)`, `register(Block)`, `unregister(Block)`,
+  `machinesIn(Chunk)`. Converts a `Block` to/from a chunk-relative `MachineKey.Coord`
+  (`block.getX() & 15`, `block.getZ() & 15` — always in `[0, 15]` by construction,
+  matching Bukkit's own chunk-relative convention) and reads/writes the chunk PDC
+  under `MaterialContract.MACHINES`. Constructor takes a `Consumer<String> warn`
+  (the project's established pattern from `ConfigValidator`/`AlloyRegistry`), wired
+  by the caller to the plugin logger — this is what carries `MachineKey.decode`'s
+  per-entry warnings out to the server log without this class doing any logging
+  itself. Removes the PDC key entirely (rather than writing an empty string) when
+  the last machine in a chunk is unregistered, keeping the chunk's NBT clean.
+
+- **`MaterialContract`** (existing file, extended per explicit task instruction) —
+  added `MACHINES` (`electricfurnace:machines`, STRING, on a **chunk's** PDC, not an
+  item's), built with the same `NamespacedKey(String, String)` constructor as every
+  other key in the file, with a doc comment distinguishing it as a chunk-level key.
+
+## Files
+
+- `src/main/java/org/xpfarm/electricfurnace/machine/MachineKey.java` (new)
+- `src/main/java/org/xpfarm/electricfurnace/machine/MachineRegistry.java` (new)
+- `src/test/java/org/xpfarm/electricfurnace/machine/MachineKeyTest.java` (new)
+- `src/main/java/org/xpfarm/electricfurnace/item/MaterialContract.java` (modified —
+  added the `MACHINES` key, per the task brief's explicit instruction to add it
+  "alongside those, following the same pattern")
+
+No other files were created or modified.
+
+## Test coverage (`MachineKeyTest`, 20 tests)
+
+- Round-trip encode/decode (multi-coordinate and single-coordinate, including a
+  negative `y` and boundary `x`/`z` values).
+- Empty string, blank string, and `null` input all yield an empty set with no
+  warning (distinguished from corruption).
+- Empty collection encodes to `""`.
+- Malformed input skipped, never thrown: garbage string, wrong separator count (too
+  few / too many fields), non-numeric field, truncated entry (trailing comma).
+- Out-of-range coordinates rejected: `x` above 15, `z` above 15, negative `x`,
+  negative `z` — each individually, plus a test confirming `x`/`z` at the exact
+  boundaries (0 and 15) are accepted and negative `y` is allowed (only `x`/`z` are
+  range-restricted).
+- One bad entry among several good ones drops only the bad entry (two variants:
+  mixed good/bad, and all-bad with one warning per entry).
+- Warning message names the offending entry.
+
+## Build verification
+
+Command: `mvn --batch-mode --no-transfer-progress clean verify`
+
+Result: **BUILD SUCCESS**. 87 tests total, 0 failures, 0 errors, 0 skipped:
+
+```
+Running org.xpfarm.electricfurnace.item.MetalClassifierTest      -> 20 tests, all pass
+Running org.xpfarm.electricfurnace.machine.MachineKeyTest        -> 20 tests, all pass (new)
+Running org.xpfarm.electricfurnace.recycle.RecycleResolverTest   -> 20 tests, all pass
+Running org.xpfarm.electricfurnace.alloy.AlloyRegistryTest       ->  7 tests, all pass
+Running org.xpfarm.electricfurnace.config.ConfigValidatorTest    -> 20 tests, all pass
+Tests run: 87, Failures: 0, Errors: 0, Skipped: 0
+BUILD SUCCESS
+```
+
+Shaded JAR built successfully at `target/electric-furnace-0.1.0.jar`.
+
+Environment: sourced `~/.sdkman/bin/sdkman-init.sh` for Java 25.0.3-tem / Maven
+3.9.16, since `java`/`mvn` are not on PATH by default.
+
+## Deviations from the plan
+
+- None from the file list, API signatures, PDC key name/type, or encoding format.
+- `MachineRegistry`'s constructor takes a `Consumer<String> warn` — not specified
+  verbatim by the plan (which only lists the four API methods), but required to
+  carry `MachineKey.decode`'s warnings anywhere, and chosen to match the exact
+  pattern already established by `ConfigValidator` and `AlloyRegistry.fromDefinitions`
+  rather than introducing a second, Logger-based convention.
+- `register`/`unregister` skip the PDC write entirely when the set is unchanged
+  (block already registered / already absent), and `unregister` removes the PDC key
+  outright rather than persisting an empty string when the last machine is removed.
+  Neither behavior is specified by the plan; both are conservative, side-effect-free
+  choices that keep chunk NBT minimal without changing observable behavior of the
+  four API methods.
+
+## Dependencies
+
+**No dependencies were added to `pom.xml`.** Everything builds against the existing
+`paper-api` (provided) and `junit-jupiter` (test) dependencies already present.
+
+## Concerns
+
+- `MachineRegistry` itself has no dedicated unit test, per the plan's own file list
+  (only `MachineKeyTest` is listed for Task 4) and the stated reason: `Block`/`Chunk`/
+  `PersistentDataContainer` cannot be constructed or exercised without a live Bukkit
+  server (confirmed empirically in Task 3 — `ItemStack` construction alone throws
+  `IllegalStateException: No RegistryAccess implementation found` outside a running
+  server; the same constraint applies to `Chunk`/`Block`). All of its logic that
+  *can* be pure (the encode/decode contract, including every malformed-input edge
+  case) was deliberately split into `MachineKey` and is fully covered. Recommend
+  exercising `MachineRegistry` end-to-end at gate 7a runtime verification once the
+  plugin is wireable (Tasks 5-6): register a machine, restart or reload the chunk,
+  confirm it survives; hand-corrupt a chunk's `electricfurnace:machines` PDC value
+  and confirm the chunk still loads with only the bad entry dropped.
+- `machinesIn(Chunk)` calls `chunk.getWorld().getBlockAt(...)`, which per the Paper
+  API is documented as safe to call for any chunk coordinate (it does not force-load
+  neighboring chunks) — this was not empirically verified against a live server in
+  this task, consistent with the "no server available" constraint noted above.
diff --git a/src/main/java/org/xpfarm/electricfurnace/item/MaterialContract.java b/src/main/java/org/xpfarm/electricfurnace/item/MaterialContract.java
index b58a91a..3256f69 100644
--- a/src/main/java/org/xpfarm/electricfurnace/item/MaterialContract.java
+++ b/src/main/java/org/xpfarm/electricfurnace/item/MaterialContract.java
@@ -42,20 +42,27 @@ public final class MaterialContract {
 
     /** STRING: the specific material id within the owning system, e.g. {@code "steel"}. */
     public static final NamespacedKey MATERIAL_ID = new NamespacedKey("xpfarm", "material_id");
 
     /** BYTE: marks an item as the Electric Furnace machine item. */
     public static final NamespacedKey MACHINE = new NamespacedKey("electricfurnace", "machine");
 
     /** The byte value stamped under {@link #MACHINE} to mark a machine item. */
     public static final byte MACHINE_MARKER = 1;
 
+    /**
+     * STRING (on a <b>chunk's</b> PersistentDataContainer, not an item): the set of
+     * machine locations registered in that chunk, encoded by
+     * {@link org.xpfarm.electricfurnace.machine.MachineKey}.
+     */
+    public static final NamespacedKey MACHINES = new NamespacedKey("electricfurnace", "machines");
+
     // ---- CopperKingdom's namespace -- read only, never written to here ----------------
 
     /** STRING (foreign, CopperKingdom): marks an item as CopperKingdom copper armor. */
     public static final NamespacedKey COPPERKINGDOM_COPPER_ARMOR = new NamespacedKey("copperkingdom", "copper_armor");
 
     /** STRING (foreign, CopperKingdom): marks an item as a CopperKingdom copper weapon. */
     public static final NamespacedKey COPPERKINGDOM_COPPER_WEAPON = new NamespacedKey("copperkingdom", "copper_weapon");
 
     private MaterialContract() {
     }
diff --git a/src/main/java/org/xpfarm/electricfurnace/machine/MachineKey.java b/src/main/java/org/xpfarm/electricfurnace/machine/MachineKey.java
new file mode 100644
index 0000000..d47b611
--- /dev/null
+++ b/src/main/java/org/xpfarm/electricfurnace/machine/MachineKey.java
@@ -0,0 +1,149 @@
+/*
+ * ElectricFurnace - a redstone-powered smelter that recycles metal gear into ingots.
+ * Copyright (C) 2026 Carmelo Santana
+ *
+ * This program is free software: you can redistribute it and/or modify it under the
+ * terms of the GNU Affero General Public License as published by the Free Software
+ * Foundation, either version 3 of the License, or (at your option) any later version.
+ * See the LICENSE file at the project root for the full license text.
+ */
+package org.xpfarm.electricfurnace.machine;
+
+import java.util.Collection;
+import java.util.LinkedHashSet;
+import java.util.Objects;
+import java.util.Set;
+import java.util.function.Consumer;
+import java.util.stream.Collectors;
+
+/**
+ * Pure, Bukkit-free encode/decode logic for the machine locations persisted in a
+ * chunk's {@code PersistentDataContainer} under {@code electricfurnace:machines}.
+ *
+ * <p>The persisted form is a single {@code STRING}: comma-separated entries of
+ * {@code "x:y:z"}, where {@code x} and {@code z} are chunk-relative (0-15) and
+ * {@code y} is the block's absolute world height with no range restriction.
+ *
+ * <p>This class touches nothing but {@link String}, {@code int}, and {@link Set} --
+ * no {@code org.bukkit} types anywhere -- which is exactly what lets
+ * {@code MachineKeyTest} exercise it with no running server. All Bukkit-facing glue
+ * (reading/writing the chunk PDC, converting a {@code Block} to a chunk-relative
+ * {@link Coord} and back) belongs in {@code MachineRegistry}, not here.
+ *
+ * <p><b>Contract:</b> {@link #decode} never throws. Malformed data -- a garbage
+ * string, the wrong number of {@code ":"}-separated fields, a non-numeric field, or
+ * an out-of-range {@code x}/{@code z} -- is skipped entry-by-entry with one warning
+ * per bad entry sent to the caller-supplied {@link Consumer}. A single corrupt entry
+ * never prevents the rest of a chunk's well-formed entries from decoding. This is the
+ * single most important behavior in this class: a corrupt chunk must never break
+ * chunk loading.
+ */
+public final class MachineKey {
+
+    /** Chunk-relative coordinates are 0-15 inclusive, per Minecraft's 16x16 chunk grid. */
+    public static final int MIN_RELATIVE = 0;
+    public static final int MAX_RELATIVE = 15;
+
+    private static final String ENTRY_SEPARATOR = ",";
+    private static final String FIELD_SEPARATOR = ":";
+
+    private MachineKey() {
+    }
+
+    /**
+     * One machine location within a chunk: {@code x} and {@code z} are chunk-relative
+     * (0-15), {@code y} is the block's absolute world height. Deliberately unvalidated
+     * by the record itself -- validation lives in {@link #decode}, where malformed
+     * data must be reported through the warning sink rather than thrown as an
+     * exception.
+     */
+    public record Coord(int x, int y, int z) {
+    }
+
+    /** Whether {@code x} and {@code z} are both within the valid chunk-relative range. */
+    public static boolean isValidRelative(int x, int z) {
+        return x >= MIN_RELATIVE && x <= MAX_RELATIVE && z >= MIN_RELATIVE && z <= MAX_RELATIVE;
+    }
+
+    /**
+     * Encodes {@code coords} as comma-separated {@code "x:y:z"} entries. An empty
+     * collection encodes to the empty string. Does not validate its input -- callers
+     * (namely {@code MachineRegistry}) are expected to only ever hold coordinates
+     * derived from real chunk-relative block positions, which are valid by
+     * construction (Bukkit's {@code chunkX = blockX & 15} is always in {@code
+     * [0, 15]}).
+     */
+    public static String encode(Collection<Coord> coords) {
+        Objects.requireNonNull(coords, "coords");
+        return coords.stream()
+                .map(c -> c.x() + FIELD_SEPARATOR + c.y() + FIELD_SEPARATOR + c.z())
+                .collect(Collectors.joining(ENTRY_SEPARATOR));
+    }
+
+    /**
+     * Decodes the comma-separated {@code "x:y:z"} entries in {@code raw} into a set
+     * of {@link Coord}s.
+     *
+     * <ul>
+     *   <li>{@code raw} is {@code null} or blank: returns an empty set, no warning --
+     *       this is the normal "no machines registered yet" case, not corruption.</li>
+     *   <li>An entry with the wrong number of {@code ":"}-separated fields, a
+     *       non-numeric field, or an {@code x}/{@code z} outside {@code [0, 15]}: that
+     *       one entry is skipped and a warning naming it is sent to {@code warn}. The
+     *       rest of the entries still decode normally.</li>
+     * </ul>
+     *
+     * <p>Never throws.
+     *
+     * @param raw  the persisted PDC string, possibly {@code null} or corrupt
+     * @param warn sink for one warning per malformed entry; must not be {@code null}
+     */
+    public static Set<Coord> decode(String raw, Consumer<String> warn) {
+        Objects.requireNonNull(warn, "warn");
+        Set<Coord> result = new LinkedHashSet<>();
+        if (raw == null || raw.isBlank()) {
+            return result;
+        }
+        for (String entry : raw.split(ENTRY_SEPARATOR, -1)) {
+            Coord coord = decodeEntry(entry, warn);
+            if (coord != null) {
+                result.add(coord);
+            }
+        }
+        return result;
+    }
+
+    private static Coord decodeEntry(String entry, Consumer<String> warn) {
+        String[] parts = entry.split(FIELD_SEPARATOR, -1);
+        if (parts.length != 3) {
+            warn.accept("ElectricFurnace machine PDC: skipping entry '" + entry
+                    + "' (expected 3 ':'-separated fields, found " + parts.length + ").");
+            return null;
+        }
+
+        Integer x = parseInt(parts[0]);
+        Integer y = parseInt(parts[1]);
+        Integer z = parseInt(parts[2]);
+        if (x == null || y == null || z == null) {
+            warn.accept("ElectricFurnace machine PDC: skipping entry '" + entry
+                    + "' (non-numeric coordinate field).");
+            return null;
+        }
+
+        if (!isValidRelative(x, z)) {
+            warn.accept("ElectricFurnace machine PDC: skipping entry '" + entry
+                    + "' (x and z must be in [" + MIN_RELATIVE + ", " + MAX_RELATIVE + "], chunk-relative).");
+            return null;
+        }
+
+        return new Coord(x, y, z);
+    }
+
+    private static Integer parseInt(String s) {
+        try {
+            return Integer.parseInt(s.trim());
+        } catch (NumberFormatException e) {
+            return null;
+        }
+    }
+}
diff --git a/src/main/java/org/xpfarm/electricfurnace/machine/MachineRegistry.java b/src/main/java/org/xpfarm/electricfurnace/machine/MachineRegistry.java
new file mode 100644
index 0000000..b57f639
--- /dev/null
+++ b/src/main/java/org/xpfarm/electricfurnace/machine/MachineRegistry.java
@@ -0,0 +1,110 @@
+/*
+ * ElectricFurnace - a redstone-powered smelter that recycles metal gear into ingots.
+ * Copyright (C) 2026 Carmelo Santana
+ *
+ * This program is free software: you can redistribute it and/or modify it under the
+ * terms of the GNU Affero General Public License as published by the Free Software
+ * Foundation, either version 3 of the License, or (at your option) any later version.
+ * See the LICENSE file at the project root for the full license text.
+ */
+package org.xpfarm.electricfurnace.machine;
+
+import org.bukkit.Chunk;
+import org.bukkit.World;
+import org.bukkit.block.Block;
+import org.bukkit.persistence.PersistentDataContainer;
+import org.bukkit.persistence.PersistentDataType;
+import org.xpfarm.electricfurnace.item.MaterialContract;
+
+import java.util.HashSet;
+import java.util.Objects;
+import java.util.Set;
+import java.util.function.Consumer;
+
+/**
+ * Remembers which block locations are Electric Furnaces, across server restarts.
+ *
+ * <p>Machine locations are stored in the owning <b>chunk's</b>
+ * {@code PersistentDataContainer} under {@link MaterialContract#MACHINES}, encoded by
+ * the pure {@link MachineKey}. There is no flat file and no database -- machines
+ * load and unload naturally with their chunks.
+ *
+ * <p>This class is the thin Bukkit-facing glue: it converts a {@link Block} to and
+ * from a chunk-relative {@link MachineKey.Coord} and reads/writes the chunk PDC.
+ * All encode/decode logic -- including the "never throw, skip malformed entries"
+ * contract -- lives in {@link MachineKey}, which is why that class (not this one) is
+ * unit tested without a server. A malformed persisted entry is skipped with a
+ * warning by {@link MachineKey#decode}; it is never allowed to propagate as an
+ * exception into a Bukkit chunk-load path.
+ */
+public final class MachineRegistry {
+
+    private final Consumer<String> warn;
+
+    /**
+     * @param warn sink for warnings about malformed persisted data; must not be
+     *             {@code null}. Wire this to the owning plugin's logger.
+     */
+    public MachineRegistry(Consumer<String> warn) {
+        this.warn = Objects.requireNonNull(warn, "warn");
+    }
+
+    /** Whether {@code block}'s location is registered as an Electric Furnace. */
+    public boolean isMachine(Block block) {
+        Objects.requireNonNull(block, "block");
+        return readCoords(block.getChunk()).contains(relativeOf(block));
+    }
+
+    /** Registers {@code block}'s location as an Electric Furnace. */
+    public void register(Block block) {
+        Objects.requireNonNull(block, "block");
+        Chunk chunk = block.getChunk();
+        Set<MachineKey.Coord> coords = readCoords(chunk);
+        if (coords.add(relativeOf(block))) {
+            writeCoords(chunk, coords);
+        }
+    }
+
+    /** Removes {@code block}'s location from the registered set, if present. */
+    public void unregister(Block block) {
+        Objects.requireNonNull(block, "block");
+        Chunk chunk = block.getChunk();
+        Set<MachineKey.Coord> coords = readCoords(chunk);
+        if (coords.remove(relativeOf(block))) {
+            writeCoords(chunk, coords);
+        }
+    }
+
+    /** All registered machine blocks in {@code chunk}. */
+    public Set<Block> machinesIn(Chunk chunk) {
+        Objects.requireNonNull(chunk, "chunk");
+        World world = chunk.getWorld();
+        int baseX = chunk.getX() << 4;
+        int baseZ = chunk.getZ() << 4;
+
+        Set<Block> blocks = new HashSet<>();
+        for (MachineKey.Coord coord : readCoords(chunk)) {
+            blocks.add(world.getBlockAt(baseX + coord.x(), coord.y(), baseZ + coord.z()));
+        }
+        return blocks;
+    }
+
+    private static MachineKey.Coord relativeOf(Block block) {
+        return new MachineKey.Coord(block.getX() & 15, block.getY(), block.getZ() & 15);
+    }
+
+    private Set<MachineKey.Coord> readCoords(Chunk chunk) {
+        PersistentDataContainer pdc = chunk.getPersistentDataContainer();
+        String raw = pdc.get(MaterialContract.MACHINES, PersistentDataType.STRING);
+        return MachineKey.decode(raw, warn);
+    }
+
+    private static void writeCoords(Chunk chunk, Set<MachineKey.Coord> coords) {
+        PersistentDataContainer pdc = chunk.getPersistentDataContainer();
+        if (coords.isEmpty()) {
+            pdc.remove(MaterialContract.MACHINES);
+        } else {
+            pdc.set(MaterialContract.MACHINES, PersistentDataType.STRING, MachineKey.encode(coords));
+        }
+    }
+}
diff --git a/src/test/java/org/xpfarm/electricfurnace/machine/MachineKeyTest.java b/src/test/java/org/xpfarm/electricfurnace/machine/MachineKeyTest.java
new file mode 100644
index 0000000..fc3eb4e
--- /dev/null
+++ b/src/test/java/org/xpfarm/electricfurnace/machine/MachineKeyTest.java
@@ -0,0 +1,222 @@
+/*
+ * ElectricFurnace - a redstone-powered smelter that recycles metal gear into ingots.
+ * Copyright (C) 2026 Carmelo Santana
+ *
+ * This program is free software: you can redistribute it and/or modify it under the
+ * terms of the GNU Affero General Public License as published by the Free Software
+ * Foundation, either version 3 of the License, or (at your option) any later version.
+ * See the LICENSE file at the project root for the full license text.
+ */
+package org.xpfarm.electricfurnace.machine;
+
+import org.junit.jupiter.api.Test;
+
+import java.util.ArrayList;
+import java.util.LinkedHashSet;
+import java.util.List;
+import java.util.Set;
+
+import static org.junit.jupiter.api.Assertions.assertEquals;
+import static org.junit.jupiter.api.Assertions.assertTrue;
+
+/**
+ * Pure unit tests for {@link MachineKey}. Deliberately imports nothing from
+ * {@code org.bukkit} and requires no running server -- this is the whole point of
+ * splitting the encode/decode logic out of {@code MachineRegistry}.
+ *
+ * <p>The most important behavior under test: malformed persisted data must be
+ * SKIPPED with a warning, never thrown -- a single corrupt entry must not prevent
+ * the rest of a chunk's machines from decoding, and must never propagate an
+ * exception into Bukkit's chunk-load path.
+ */
+class MachineKeyTest {
+
+    private final List<String> warnings = new ArrayList<>();
+
+    private void warn(String message) {
+        warnings.add(message);
+    }
+
+    // ---- round-trip ----------------------------------------------------------------
+
+    @Test
+    void encodeThenDecode_roundTripsExactly() {
+        Set<MachineKey.Coord> original = new LinkedHashSet<>(Set.of(
+                new MachineKey.Coord(0, 64, 0),
+                new MachineKey.Coord(15, 70, 15),
+                new MachineKey.Coord(3, -64, 9),
+                new MachineKey.Coord(8, 319, 1)));
+
+        String encoded = MachineKey.encode(original);
+        Set<MachineKey.Coord> decoded = MachineKey.decode(encoded, this::warn);
+
+        assertEquals(original, decoded);
+        assertTrue(warnings.isEmpty(), "round-tripping well-formed data must not warn");
+    }
+
+    @Test
+    void encodeThenDecode_singleCoordinate_roundTrips() {
+        Set<MachineKey.Coord> original = Set.of(new MachineKey.Coord(7, 12, 4));
+
+        String encoded = MachineKey.encode(original);
+        Set<MachineKey.Coord> decoded = MachineKey.decode(encoded, this::warn);
+
+        assertEquals(original, decoded);
+        assertTrue(warnings.isEmpty());
+    }
+
+    // ---- empty input -----------------------------------------------------------------
+
+    @Test
+    void decode_emptyString_yieldsEmptySet() {
+        Set<MachineKey.Coord> decoded = MachineKey.decode("", this::warn);
+
+        assertTrue(decoded.isEmpty());
+        assertTrue(warnings.isEmpty(), "an empty string is not corruption, it's just no machines yet");
+    }
+
+    @Test
+    void decode_blankString_yieldsEmptySet() {
+        Set<MachineKey.Coord> decoded = MachineKey.decode("   ", this::warn);
+
+        assertTrue(decoded.isEmpty());
+        assertTrue(warnings.isEmpty());
+    }
+
+    @Test
+    void decode_nullString_yieldsEmptySet() {
+        Set<MachineKey.Coord> decoded = MachineKey.decode(null, this::warn);
+
+        assertTrue(decoded.isEmpty());
+        assertTrue(warnings.isEmpty(), "a missing PDC key is not corruption, it's just no machines yet");
+    }
+
+    @Test
+    void encode_emptyCollection_yieldsEmptyString() {
+        assertEquals("", MachineKey.encode(Set.of()));
+    }
+
+    // ---- malformed input ignored, never thrown --------------------------------------
+
+    @Test
+    void decode_garbageString_isSkippedNotThrown() {
+        Set<MachineKey.Coord> decoded = MachineKey.decode("not-a-coordinate-at-all", this::warn);
+
+        assertTrue(decoded.isEmpty());
+        assertEquals(1, warnings.size());
+    }
+
+    @Test
+    void decode_wrongSeparatorCount_tooFew_isSkipped() {
+        Set<MachineKey.Coord> decoded = MachineKey.decode("3:64", this::warn);
+
+        assertTrue(decoded.isEmpty());
+        assertEquals(1, warnings.size());
+    }
+
+    @Test
+    void decode_wrongSeparatorCount_tooMany_isSkipped() {
+        Set<MachineKey.Coord> decoded = MachineKey.decode("3:64:5:9", this::warn);
+
+        assertTrue(decoded.isEmpty());
+        assertEquals(1, warnings.size());
+    }
+
+    @Test
+    void decode_nonNumericField_isSkipped() {
+        Set<MachineKey.Coord> decoded = MachineKey.decode("a:64:5", this::warn);
+
+        assertTrue(decoded.isEmpty());
+        assertEquals(1, warnings.size());
+    }
+
+    @Test
+    void decode_truncatedEntry_trailingComma_isSkippedButDropsOnlyThatEntry() {
+        Set<MachineKey.Coord> decoded = MachineKey.decode("3:64:5,", this::warn);
+
+        assertEquals(Set.of(new MachineKey.Coord(3, 64, 5)), decoded);
+        assertEquals(1, warnings.size());
+    }
+
+    // ---- out-of-range coordinates rejected (x, z must be 0-15) ----------------------
+
+    @Test
+    void decode_xAboveRange_isRejected() {
+        Set<MachineKey.Coord> decoded = MachineKey.decode("16:64:5", this::warn);
+
+        assertTrue(decoded.isEmpty());
+        assertEquals(1, warnings.size());
+    }
+
+    @Test
+    void decode_zAboveRange_isRejected() {
+        Set<MachineKey.Coord> decoded = MachineKey.decode("5:64:16", this::warn);
+
+        assertTrue(decoded.isEmpty());
+        assertEquals(1, warnings.size());
+    }
+
+    @Test
+    void decode_negativeX_isRejected() {
+        Set<MachineKey.Coord> decoded = MachineKey.decode("-1:64:5", this::warn);
+
+        assertTrue(decoded.isEmpty());
+        assertEquals(1, warnings.size());
+    }
+
+    @Test
+    void decode_negativeZ_isRejected() {
+        Set<MachineKey.Coord> decoded = MachineKey.decode("5:64:-1", this::warn);
+
+        assertTrue(decoded.isEmpty());
+        assertEquals(1, warnings.size());
+    }
+
+    @Test
+    void decode_xAndZAtBoundaries_areAccepted() {
+        Set<MachineKey.Coord> decoded = MachineKey.decode("0:1:0,15:2:15", this::warn);
+
+        assertEquals(Set.of(new MachineKey.Coord(0, 1, 0), new MachineKey.Coord(15, 2, 15)), decoded);
+        assertTrue(warnings.isEmpty());
+    }
+
+    @Test
+    void decode_negativeYIsAllowed_onlyXAndZAreRangeRestricted() {
+        Set<MachineKey.Coord> decoded = MachineKey.decode("5:-64:5", this::warn);
+
+        assertEquals(Set.of(new MachineKey.Coord(5, -64, 5)), decoded);
+        assertTrue(warnings.isEmpty());
+    }
+
+    // ---- one bad entry among several good drops only the bad entry ------------------
+
+    @Test
+    void decode_oneBadEntryAmongGoodOnes_dropsOnlyTheBadOne() {
+        Set<MachineKey.Coord> decoded = MachineKey.decode(
+                "1:64:2,garbage,3:70:9,16:64:1,5:80:5", this::warn);
+
+        assertEquals(Set.of(
+                new MachineKey.Coord(1, 64, 2),
+                new MachineKey.Coord(3, 70, 9),
+                new MachineKey.Coord(5, 80, 5)), decoded);
+        assertEquals(2, warnings.size(), "two malformed entries (garbage, out-of-range x) should each warn once");
+    }
+
+    @Test
+    void decode_allEntriesBad_yieldsEmptySetWithOneWarningPerEntry() {
+        Set<MachineKey.Coord> decoded = MachineKey.decode("garbage,16:1:1,1:1:-1", this::warn);
+
+        assertTrue(decoded.isEmpty());
+        assertEquals(3, warnings.size());
+    }
+
+    // ---- warning content ---------------------------------------------------------
+
+    @Test
+    void warningMessage_namesTheOffendingEntry() {
+        MachineKey.decode("garbage", this::warn);
+
+        assertEquals(1, warnings.size());
+        assertTrue(warnings.get(0).contains("garbage"), "warning should name the offending entry: " + warnings.get(0));
+    }
+}
```
