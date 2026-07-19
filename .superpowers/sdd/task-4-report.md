# Task 4 — Machine persistence — Report

## What was implemented

Followed TDD: wrote `MachineKeyTest` first, confirmed it failed to compile with no
implementation present (20 tests, `package MachineKey does not exist`), then
implemented `MachineKey` and `MachineRegistry`.

- **`MachineKey`** — the pure, Bukkit-free encode/decode core for the machine
  locations persisted in a chunk's `PersistentDataContainer`. Nested record
  `Coord(int x, int y, int z)` represents one machine location: `x`/`z` are
  chunk-relative (0-15), `y` is the block's absolute world height with no range
  restriction. Persisted form: comma-separated `"x:y:z"` entries.
  - `encode(Collection<Coord>)` — joins entries; empty collection encodes to `""`.
  - `decode(String raw, Consumer<String> warn)` — the critical method. Never throws.
    `raw == null` or blank returns an empty set with no warning (missing PDC key /
    no machines yet is not corruption). Otherwise each comma-separated entry is
    decoded independently: wrong `":"`-field count, a non-numeric field, or an
    out-of-range `x`/`z` causes that one entry to be skipped with exactly one
    warning naming the offending entry — every other entry in the same string still
    decodes normally. This satisfies the plan's core requirement: a single corrupt
    entry must never prevent the rest of a chunk's machines from loading and must
    never propagate an exception into Bukkit's chunk-load path.
  - `isValidRelative(int x, int z)` — the shared range check (`[0, 15]` for both).

- **`MachineRegistry`** — the thin Bukkit-facing glue implementing the plan's exact
  API: `isMachine(Block)`, `register(Block)`, `unregister(Block)`,
  `machinesIn(Chunk)`. Converts a `Block` to/from a chunk-relative `MachineKey.Coord`
  (`block.getX() & 15`, `block.getZ() & 15` — always in `[0, 15]` by construction,
  matching Bukkit's own chunk-relative convention) and reads/writes the chunk PDC
  under `MaterialContract.MACHINES`. Constructor takes a `Consumer<String> warn`
  (the project's established pattern from `ConfigValidator`/`AlloyRegistry`), wired
  by the caller to the plugin logger — this is what carries `MachineKey.decode`'s
  per-entry warnings out to the server log without this class doing any logging
  itself. Removes the PDC key entirely (rather than writing an empty string) when
  the last machine in a chunk is unregistered, keeping the chunk's NBT clean.

- **`MaterialContract`** (existing file, extended per explicit task instruction) —
  added `MACHINES` (`electricfurnace:machines`, STRING, on a **chunk's** PDC, not an
  item's), built with the same `NamespacedKey(String, String)` constructor as every
  other key in the file, with a doc comment distinguishing it as a chunk-level key.

## Files

- `src/main/java/org/xpfarm/electricfurnace/machine/MachineKey.java` (new)
- `src/main/java/org/xpfarm/electricfurnace/machine/MachineRegistry.java` (new)
- `src/test/java/org/xpfarm/electricfurnace/machine/MachineKeyTest.java` (new)
- `src/main/java/org/xpfarm/electricfurnace/item/MaterialContract.java` (modified —
  added the `MACHINES` key, per the task brief's explicit instruction to add it
  "alongside those, following the same pattern")

No other files were created or modified.

## Test coverage (`MachineKeyTest`, 20 tests)

- Round-trip encode/decode (multi-coordinate and single-coordinate, including a
  negative `y` and boundary `x`/`z` values).
- Empty string, blank string, and `null` input all yield an empty set with no
  warning (distinguished from corruption).
- Empty collection encodes to `""`.
- Malformed input skipped, never thrown: garbage string, wrong separator count (too
  few / too many fields), non-numeric field, truncated entry (trailing comma).
- Out-of-range coordinates rejected: `x` above 15, `z` above 15, negative `x`,
  negative `z` — each individually, plus a test confirming `x`/`z` at the exact
  boundaries (0 and 15) are accepted and negative `y` is allowed (only `x`/`z` are
  range-restricted).
- One bad entry among several good ones drops only the bad entry (two variants:
  mixed good/bad, and all-bad with one warning per entry).
- Warning message names the offending entry.

## Build verification

Command: `mvn --batch-mode --no-transfer-progress clean verify`

Result: **BUILD SUCCESS**. 87 tests total, 0 failures, 0 errors, 0 skipped:

```
Running org.xpfarm.electricfurnace.item.MetalClassifierTest      -> 20 tests, all pass
Running org.xpfarm.electricfurnace.machine.MachineKeyTest        -> 20 tests, all pass (new)
Running org.xpfarm.electricfurnace.recycle.RecycleResolverTest   -> 20 tests, all pass
Running org.xpfarm.electricfurnace.alloy.AlloyRegistryTest       ->  7 tests, all pass
Running org.xpfarm.electricfurnace.config.ConfigValidatorTest    -> 20 tests, all pass
Tests run: 87, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Shaded JAR built successfully at `target/electric-furnace-0.1.0.jar`.

Environment: sourced `~/.sdkman/bin/sdkman-init.sh` for Java 25.0.3-tem / Maven
3.9.16, since `java`/`mvn` are not on PATH by default.

## Deviations from the plan

- None from the file list, API signatures, PDC key name/type, or encoding format.
- `MachineRegistry`'s constructor takes a `Consumer<String> warn` — not specified
  verbatim by the plan (which only lists the four API methods), but required to
  carry `MachineKey.decode`'s warnings anywhere, and chosen to match the exact
  pattern already established by `ConfigValidator` and `AlloyRegistry.fromDefinitions`
  rather than introducing a second, Logger-based convention.
- `register`/`unregister` skip the PDC write entirely when the set is unchanged
  (block already registered / already absent), and `unregister` removes the PDC key
  outright rather than persisting an empty string when the last machine is removed.
  Neither behavior is specified by the plan; both are conservative, side-effect-free
  choices that keep chunk NBT minimal without changing observable behavior of the
  four API methods.

## Dependencies

**No dependencies were added to `pom.xml`.** Everything builds against the existing
`paper-api` (provided) and `junit-jupiter` (test) dependencies already present.

## Concerns

- `MachineRegistry` itself has no dedicated unit test, per the plan's own file list
  (only `MachineKeyTest` is listed for Task 4) and the stated reason: `Block`/`Chunk`/
  `PersistentDataContainer` cannot be constructed or exercised without a live Bukkit
  server (confirmed empirically in Task 3 — `ItemStack` construction alone throws
  `IllegalStateException: No RegistryAccess implementation found` outside a running
  server; the same constraint applies to `Chunk`/`Block`). All of its logic that
  *can* be pure (the encode/decode contract, including every malformed-input edge
  case) was deliberately split into `MachineKey` and is fully covered. Recommend
  exercising `MachineRegistry` end-to-end at gate 7a runtime verification once the
  plugin is wireable (Tasks 5-6): register a machine, restart or reload the chunk,
  confirm it survives; hand-corrupt a chunk's `electricfurnace:machines` PDC value
  and confirm the chunk still loads with only the bad entry dropped.
- `machinesIn(Chunk)` calls `chunk.getWorld().getBlockAt(...)`, which per the Paper
  API is documented as safe to call for any chunk coordinate (it does not force-load
  neighboring chunks) — this was not empirically verified against a live server in
  this task, consistent with the "no server available" constraint noted above.
