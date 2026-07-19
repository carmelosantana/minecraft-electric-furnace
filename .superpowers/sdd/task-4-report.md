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

---

# Task 4 — Close review findings — Report

## Finding 1 (Important): missing hostile-input tests in `MachineKeyTest`

`MachineKey.decode`/`encode` themselves were not touched (confirmed total/correct by
review; only tests were added around them). Added 13 new tests to
`src/test/java/org/xpfarm/electricfurnace/machine/MachineKeyTest.java`, each wrapping
the call in `assertDoesNotThrow` (in addition to asserting the resulting set) so the
"never throws" contract is checked explicitly, not just implicitly via test-framework
failure propagation:

- **Empty segments**: `"1::3"` (middle field empty), `"::"` (all three fields empty),
  plus `"1::3,5:70:5"` proving a good sibling entry still survives.
- **Integer overflow**: `"99999999999:5:5"`, `"-99999999999:5:5"` (both overflow
  `Integer.parseInt`, caught as `NumberFormatException` inside `MachineKey.parseInt`),
  plus a good-entry-survives variant.
- **Leading/trailing separators**: `",1:2:3"` (leading entry separator — the resulting
  empty entry is skipped, the real entry `(1,2,3)` survives), `":5:5"` (leading field
  separator), `"5:5:"` (trailing field separator).
- **Whitespace-only segment**: `"1: :3"`, plus a good-entry-survives variant
  (`"1: :3,4:64:4"`).
- **Null input**: `decode_nullString_yieldsEmptySetNotNpe` — a null-input test already
  existed (`decode_nullString_yieldsEmptySet`); added a second one that explicitly
  wraps the call in `assertDoesNotThrow` per the finding's exact wording ("assert it
  yields an empty set, not an NPE"), rather than relying only on the pre-existing
  assertion style.

Every new test asserts both the resulting `Set<MachineKey.Coord>` contents (empty, or
containing exactly the surviving good entry) and that no exception escaped
(`assertDoesNotThrow`), and where a good entry was paired with a bad one, asserts the
good entry survives.

`MachineKeyTest` went from 20 to 32 tests, all passing.

## Finding 2 (real runtime risk): unguarded PDC read/write in `MachineRegistry`

File: `src/main/java/org/xpfarm/electricfurnace/machine/MachineRegistry.java`

- **`readCoords`**: now calls `pdc.has(MaterialContract.MACHINES,
  PersistentDataType.STRING)` before `pdc.get(...)`, and the whole read (the `has`
  check plus the `get`) is wrapped in `try { ... } catch (RuntimeException e)`. If a
  non-STRING NBT primitive is ever found under that key (another plugin, a bug,
  hand-edited chunk data) and the PDC implementation throws
  `IllegalArgumentException` rather than returning `null`, the exception is caught,
  a warning naming the chunk (world name + chunk x,z) and the exception type/message
  is sent to `warn`, and the chunk is treated as having no machines (`new HashSet<>()`
  returned) instead of letting the exception escape into Bukkit's chunk-load path.
- **`writeCoords`**: the same defensive treatment was applied to the write path
  (`pdc.remove`/`pdc.set`), wrapped in `try/catch (RuntimeException e)`, logging a
  warning naming the chunk and the exception, and swallowing it rather than letting
  it propagate into the caller's block-place/break event handling. `writeCoords` was
  changed from `static` to an instance method so it can reach the instance's `warn`
  sink (the only change to its signature; its call sites in `register`/`unregister`
  were already unqualified instance calls and needed no changes).
- No other logic in `MachineRegistry` was refactored; `isMachine`, `register`,
  `unregister`, `machinesIn`, and `relativeOf` are unchanged apart from `readCoords`/
  `writeCoords` bodies as described above.

`MachineRegistry` still has no dedicated unit test (unchanged from Task 4's original
report: `Chunk`/`Block`/`PersistentDataContainer` cannot be constructed without a live
Bukkit server), so this fix is unverified by an automated test exercising the actual
`IllegalArgumentException`-on-non-STRING-read scenario; it is verified by inspection
and by the full build continuing to pass.

## Build verification

Command:

```
mvn --batch-mode --no-transfer-progress clean verify
```

Output (tail):

```
[INFO] Running org.xpfarm.electricfurnace.item.MetalClassifierTest
[INFO] Tests run: 20, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.137 s -- in org.xpfarm.electricfurnace.item.MetalClassifierTest
[INFO] Running org.xpfarm.electricfurnace.config.ConfigValidatorTest
[INFO] Tests run: 20, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.039 s -- in org.xpfarm.electricfurnace.config.ConfigValidatorTest
[INFO] Running org.xpfarm.electricfurnace.recycle.RecycleResolverTest
[INFO] Tests run: 20, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.024 s -- in org.xpfarm.electricfurnace.recycle.RecycleResolverTest
[INFO] Running org.xpfarm.electricfurnace.machine.MachineKeyTest
[INFO] Tests run: 32, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.044 s -- in org.xpfarm.electricfurnace.machine.MachineKeyTest
[INFO] Running org.xpfarm.electricfurnace.alloy.AlloyRegistryTest
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.011 s -- in org.xpfarm.electricfurnace.alloy.AlloyRegistryTest
[INFO]
[INFO] Results:
[INFO]
[INFO] Tests run: 99, Failures: 0, Errors: 0, Skipped: 0
...
[INFO] BUILD SUCCESS
```

Total tests: 99 (up from 87; `MachineKeyTest` grew from 20 to 32, the other four
suites unchanged). Shaded JAR built successfully.

Environment: sourced `~/.sdkman/bin/sdkman-init.sh` for Java 25.0.3-tem / Maven
3.9.16, since `java`/`mvn` are not on PATH by default.
