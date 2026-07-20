# ElectricFurnace Continuous Operation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the Electric Furnace from an instantaneous click-to-smelt GUI into a machine that smelts over time, drains redstone as burn fuel, locks its inputs while running, and keeps running with no player watching.

**Architecture:** Per-block `MachineState` persisted in the `BLAST_FURNACE` block's PDC (a `TileState`, so it saves and loads with its chunk). A `MachineStore` hydrates and flushes those states. One global `MachineTicker` advances every machine in a loaded chunk, driven by a pure step function that is unit-tested with no running server. The GUI stops owning items and becomes a shared view onto machine state.

**Tech Stack:** Java 25, Paper API `26.1.2.build.74-stable`, JUnit Jupiter 5.10.0, Maven.

## Global Constraints

- Spec: `docs/superpowers/specs/2026-07-20-continuous-operation-design.md`. It is the source of truth; this plan implements it.
- Java 25 (`maven.compiler.release=25`). Paper API `26.1.2.build.74-stable`, scope `provided`.
- Group `org.xpfarm`, package root `org.xpfarm.electricfurnace`.
- Every new file starts with the AGPL header block copied verbatim from `FurnaceGui.java:1-9`.
- **Decision logic must be pure and testable with no running server.** Bukkit types (`Player`, `ItemStack`, `Block`, `Inventory`) cannot be constructed headlessly in this project. Extract every decision into a function over primitives, enums, and records, and test that. This is the established pattern — see `MetalClassifier.resolveBranch` and `FurnaceGui.mayRun`.
- **Never destroy items.** The invariant this whole change must preserve: *every item that enters a machine is either in that machine's persisted state, in a player's inventory, or on the ground — never only in memory.*
- Nothing in a scheduled task may throw. An exception inside the ticker is swallowed by the scheduler and aborts the pass partway through, which is exactly how items get destroyed. Log and skip instead.
- Config keys are validated on load via `ConfigValidator`; an out-of-range or unparseable value logs a warning naming the key, the offending value, and the default it was replaced with, and never stops the plugin from starting.
- Environment: `source ~/.sdkman/bin/sdkman-init.sh` first — `java` and `mvn` are not on PATH.
- Full build: `mvn --batch-mode --no-transfer-progress clean verify`.
- Single test: `mvn --batch-mode --no-transfer-progress test -Dtest=ClassName#methodName`.
- Existing suite is 244 tests and must stay green, except where a task explicitly deletes a test whose behaviour is being replaced.

---

## File Structure

**Create:**
- `src/main/java/org/xpfarm/electricfurnace/machine/MachineState.java` — the per-machine record: slots, progress, burn time. Pure data.
- `src/main/java/org/xpfarm/electricfurnace/machine/MachineStateCodec.java` — encode/decode `MachineState` to bytes for the block PDC.
- `src/main/java/org/xpfarm/electricfurnace/machine/MachineTicker.java` — the global task plus the pure `step` transition.
- `src/main/java/org/xpfarm/electricfurnace/machine/MachineStore.java` — live states, hydrate on chunk load, flush on unload/save/disable.
- `src/main/java/org/xpfarm/electricfurnace/gui/SlotLock.java` — pure lock decision.
- `src/main/java/org/xpfarm/electricfurnace/gui/ProgressBar.java` — pure progress-string rendering.
- Matching tests under `src/test/java/org/xpfarm/electricfurnace/...`.

**Modify:**
- `src/main/resources/config.yml` — replace `fuel-per-operation`, change speed default.
- `config/MachineSettings.java`, `config/EfConfig.java` — the new keys and derived `smeltTicks`.
- `gui/FurnaceGui.java` — shared inventory, `SMELTING` indicator state, stop returning items on close.
- `gui/GuiLayout.java` — no slot changes; only if a progress slot proves necessary (it should not — progress renders as lore on the existing indicator).
- `listener/MachineGuiListener.java` — apply `SlotLock`, stop returning items on close.
- `listener/MachineBlockListener.java` — return machine contents on block break.
- `listener/RedstoneListener.java` — stop calling `tryProcess`; the ticker owns processing now.
- `effect/MachineEffects.java` — split sparks from smoke.
- `ElectricFurnacePlugin.java` — wire store and ticker, flush on disable.

---

## Task 1: Config — burn time and derived smelt duration

Replaces the per-operation fuel model with burn time, and makes `smelt-speed-multiplier` actually do something.

**Files:**
- Modify: `src/main/java/org/xpfarm/electricfurnace/config/MachineSettings.java`
- Modify: `src/main/java/org/xpfarm/electricfurnace/config/EfConfig.java:47-48`
- Modify: `src/main/resources/config.yml:14-21`
- Test: `src/test/java/org/xpfarm/electricfurnace/config/MachineSettingsTest.java` (create)

**Interfaces:**
- Consumes: `ConfigValidator.parseDouble(String key, Object raw, double min, double max, double def, Consumer<String> warn)` and the existing `parseInt` with the same shape.
- Produces:
  - `MachineSettings(double smeltSpeedMultiplier, int burnTicksPerRedstone, boolean requireRedstoneSignal, boolean statusBulbEnabled)`
  - `MachineSettings.BASE_SMELT_TICKS` — `int`, value `200`
  - `MachineSettings.smeltTicks()` — `int`, the derived per-item duration
  - `MachineSettings.smeltTicksFor(double multiplier)` — `static int`, the pure derivation

- [ ] **Step 1: Write the failing test**

Create `src/test/java/org/xpfarm/electricfurnace/config/MachineSettingsTest.java`:

```java
package org.xpfarm.electricfurnace.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MachineSettingsTest {

    @Test
    void smeltTicksFor_defaultMultiplier_isTwoAndAHalfTimesFasterThanVanilla() {
        assertEquals(80, MachineSettings.smeltTicksFor(2.5D));
    }

    @Test
    void smeltTicksFor_multiplierOfOne_matchesVanilla() {
        assertEquals(MachineSettings.BASE_SMELT_TICKS, MachineSettings.smeltTicksFor(1.0D));
    }

    @Test
    void smeltTicksFor_validatedRange_neverLeavesTwentyToTwoHundredTicks() {
        for (int tenths = 10; tenths <= 100; tenths++) {
            int ticks = MachineSettings.smeltTicksFor(tenths / 10.0D);
            assertTrue(ticks >= 20 && ticks <= 200,
                    "multiplier " + (tenths / 10.0D) + " produced " + ticks + " ticks");
        }
    }

    @Test
    void smeltTicks_readsTheRecordsOwnMultiplier() {
        MachineSettings settings = new MachineSettings(2.5D, 200, true, true);
        assertEquals(80, settings.smeltTicks());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
source ~/.sdkman/bin/sdkman-init.sh
mvn --batch-mode --no-transfer-progress test -Dtest=MachineSettingsTest
```

Expected: FAIL to compile — `smeltTicksFor` and `BASE_SMELT_TICKS` do not exist, and the 4-arg constructor's second parameter is still `fuelPerOperation`.

- [ ] **Step 3: Rewrite `MachineSettings`**

```java
package org.xpfarm.electricfurnace.config;

/**
 * Validated {@code machine} settings section of {@code config.yml}.
 *
 * @param smeltSpeedMultiplier   multiplier applied to the vanilla smelt duration (valid range 1.0-10.0)
 * @param burnTicksPerRedstone   ticks of burn time bought by one redstone dust (valid range 20-6000)
 * @param requireRedstoneSignal  whether a redstone signal is required for the machine to run at all
 * @param statusBulbEnabled      whether an adjacent {@code COPPER_BULB} is driven to reflect machine state
 */
public record MachineSettings(
        double smeltSpeedMultiplier,
        int burnTicksPerRedstone,
        boolean requireRedstoneSignal,
        boolean statusBulbEnabled
) {

    /** A vanilla furnace smelts one item in this many ticks. */
    public static final int BASE_SMELT_TICKS = 200;

    /**
     * The per-item smelt duration for a given speed multiplier.
     *
     * <p>Pure so the derivation is pinned by a test across the whole validated
     * 1.0-10.0 range: the result must never fall outside 20-200 ticks, and must never
     * reach zero (a zero-tick smelt would complete every tick and drain an inventory
     * instantly).
     */
    public static int smeltTicksFor(double multiplier) {
        return Math.max(1, (int) Math.round(BASE_SMELT_TICKS / multiplier));
    }

    /** The per-item smelt duration implied by this configuration. */
    public int smeltTicks() {
        return smeltTicksFor(smeltSpeedMultiplier);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
mvn --batch-mode --no-transfer-progress test -Dtest=MachineSettingsTest
```

Expected: PASS, 4 tests.

- [ ] **Step 5: Update `EfConfig` to parse the new key**

In `EfConfig.java`, change the multiplier default from `2.0` to `2.5` and replace the `fuel-per-operation` parse with `burn-ticks-per-redstone`:

```java
ConfigValidator.parseDouble("machine.smelt-speed-multiplier",
        get(root, "machine.smelt-speed-multiplier"), 1.0, 10.0, 2.5, warn),
ConfigValidator.parseInt("machine.burn-ticks-per-redstone",
        get(root, "machine.burn-ticks-per-redstone"), 20, 6000, 200, warn),
```

Immediately after building the `MachineSettings`, warn about the removed key so an old config does not fail silently:

```java
if (get(root, "machine.fuel-per-operation") != null) {
    warn.accept("machine.fuel-per-operation was removed in this version; redstone is now"
            + " consumed as burn time. Use machine.burn-ticks-per-redstone instead."
            + " The old key is being ignored.");
}
```

- [ ] **Step 6: Update `config.yml`**

Replace lines 14-21 of `src/main/resources/config.yml`:

```yaml
machine:
  # Multiplier applied to the vanilla 200-tick smelt duration. 2.5 means one item
  # smelts in 80 ticks (4 seconds). Valid range: 1.0-10.0.
  smelt-speed-multiplier: 2.5
  # Ticks of burn time bought by one redstone dust. Burn time is spent only while a
  # smelt is actively advancing -- a powered machine with nothing to do does not eat
  # your dust. At the defaults, one dust smelts 2.5 items. Valid range: 20-6000.
  burn-ticks-per-redstone: 200
  # Whether the machine requires a redstone signal to run at all. The signal is the
  # on/off switch; redstone dust is the fuel. See status-bulb below.
  require-redstone-signal: true
```

- [ ] **Step 7: Fix every existing compile break**

`fuelPerOperation()` is referenced in `FurnaceGui.java` (lines ~189, 220, 234) and in existing tests. For now, replace those call sites with the literal `1` so the project compiles — Task 5 removes them entirely when the GUI stops processing. Update `ConfigValidatorTest` occurrences of `machine.smelt-speed-multiplier` whose default is `2.0` to `2.5`.

- [ ] **Step 8: Full build**

```bash
mvn --batch-mode --no-transfer-progress clean verify
```

Expected: BUILD SUCCESS, all tests green.

- [ ] **Step 9: Commit**

```bash
git add src/main src/test
git commit -m "Replace per-operation fuel with redstone burn time; derive smelt duration"
```

---

## Task 2: `MachineState` and its byte codec

The persistence layer. This is the highest-stakes task in the plan — the round-trip test is what pins the new item-safety invariant.

**Files:**
- Create: `src/main/java/org/xpfarm/electricfurnace/machine/MachineState.java`
- Create: `src/main/java/org/xpfarm/electricfurnace/machine/MachineStateCodec.java`
- Test: `src/test/java/org/xpfarm/electricfurnace/machine/MachineStateTest.java`

**Interfaces:**
- Consumes: `MachineSettings.smeltTicks()` from Task 1.
- Produces:
  - `MachineState` — a mutable class (not a record; the ticker mutates it in place) with:
    - `ItemStack[] inputs()` — length 5, entries nullable
    - `ItemStack fuel()` / `setFuel(ItemStack)`
    - `ItemStack output()` / `setOutput(ItemStack)`
    - `int progressTicks()` / `setProgressTicks(int)`
    - `int burnTicksRemaining()` / `setBurnTicksRemaining(int)`
    - `boolean isIdle()` — `progressTicks == 0`
    - `static MachineState empty()`
  - `MachineStateCodec.encode(MachineState) -> byte[]`
  - `MachineStateCodec.decode(byte[]) -> MachineState` — returns `MachineState.empty()` on any malformed input, never throws
  - `MachineStateCodec.KEY` — `NamespacedKey("electricfurnace", "machine_state")`

- [ ] **Step 1: Write the failing test**

Note: `ItemStack` cannot be constructed headlessly, so the codec test exercises the **framing** — counts, ordering, progress and burn fields, null-slot handling — using a seam. `MachineStateCodec` takes its stack serializer as two function parameters so the test can substitute string bytes for real `ItemStack` bytes.

Create `src/test/java/org/xpfarm/electricfurnace/machine/MachineStateTest.java`:

```java
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
```

- [ ] **Step 2: Run test to verify it fails**

```bash
mvn --batch-mode --no-transfer-progress test -Dtest=MachineStateTest
```

Expected: FAIL to compile — neither class exists.

- [ ] **Step 3: Write `MachineState`**

```java
package org.xpfarm.electricfurnace.machine;

import org.bukkit.inventory.ItemStack;

/**
 * The persisted contents and run state of one Electric Furnace block.
 *
 * <p>Mutable by design: {@link MachineTicker} advances the same instance in place every
 * tick, and copying seven {@code ItemStack}s per machine per tick would be wasteful.
 * Ownership is single-threaded -- only the main-thread ticker and main-thread event
 * handlers touch it.
 */
public final class MachineState {

    /** Number of recycler input slots. Mirrors {@code GuiLayout.INPUT_SLOTS}. */
    public static final int INPUT_COUNT = 5;

    private final ItemStack[] inputs = new ItemStack[INPUT_COUNT];
    private ItemStack fuel;
    private ItemStack output;
    private int progressTicks;
    private int burnTicksRemaining;

    private MachineState() {
    }

    /** A machine with nothing in it and no run in progress. */
    public static MachineState empty() {
        return new MachineState();
    }

    /** The live input slot array. Entries may be {@code null}; length is always {@link #INPUT_COUNT}. */
    public ItemStack[] inputs() {
        return inputs;
    }

    public ItemStack fuel() {
        return fuel;
    }

    public void setFuel(ItemStack fuel) {
        this.fuel = fuel;
    }

    public ItemStack output() {
        return output;
    }

    public void setOutput(ItemStack output) {
        this.output = output;
    }

    public int progressTicks() {
        return progressTicks;
    }

    public void setProgressTicks(int progressTicks) {
        this.progressTicks = Math.max(0, progressTicks);
    }

    public int burnTicksRemaining() {
        return burnTicksRemaining;
    }

    public void setBurnTicksRemaining(int burnTicksRemaining) {
        this.burnTicksRemaining = Math.max(0, burnTicksRemaining);
    }

    /** Whether no run is currently in progress. Drives the input lock. */
    public boolean isIdle() {
        return progressTicks == 0;
    }
}
```

- [ ] **Step 4: Write `MachineStateCodec`**

```java
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
        } catch (IOException | RuntimeException e) {
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
```

- [ ] **Step 5: Run test to verify it passes**

```bash
mvn --batch-mode --no-transfer-progress test -Dtest=MachineStateTest
```

Expected: PASS, 6 tests.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/xpfarm/electricfurnace/machine src/test/java/org/xpfarm/electricfurnace/machine
git commit -m "Add MachineState and a versioned, fail-soft byte codec for block PDC"
```

---

## Task 3: `MachineTicker` — the pure step function

The heart of the change. The step function is a pure transition, so every stall, resume, and completion path is table-tested with no server.

**Files:**
- Create: `src/main/java/org/xpfarm/electricfurnace/machine/MachineTicker.java`
- Test: `src/test/java/org/xpfarm/electricfurnace/machine/MachineTickerTest.java`

**Interfaces:**
- Consumes: `MachineState` (Task 2), `MachineSettings.smeltTicks()` (Task 1).
- Produces:
  - `MachineTicker.Outcome` — enum: `STALLED_NO_POWER`, `STALLED_NO_RECIPE`, `STALLED_OUTPUT_BLOCKED`, `STALLED_NO_FUEL`, `ADVANCED`, `COMPLETED`
  - `MachineTicker.Conditions` — record `(boolean powered, boolean requireSignal, boolean recipeValid, boolean outputBlocked, boolean fuelAvailable)`
  - `MachineTicker.Step` — record `(Outcome outcome, int progressTicks, int burnTicksRemaining, boolean consumeOneFuel)`
  - `MachineTicker.step(Conditions, int progressTicks, int burnTicksRemaining, int smeltTicks, int burnTicksPerRedstone)` — `static Step`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/org/xpfarm/electricfurnace/machine/MachineTickerTest.java`:

```java
package org.xpfarm.electricfurnace.machine;

import org.junit.jupiter.api.Test;
import org.xpfarm.electricfurnace.machine.MachineTicker.Conditions;
import org.xpfarm.electricfurnace.machine.MachineTicker.Outcome;
import org.xpfarm.electricfurnace.machine.MachineTicker.Step;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MachineTickerTest {

    private static final int SMELT_TICKS = 80;
    private static final int BURN_PER_REDSTONE = 200;

    private static Conditions ready() {
        return new Conditions(true, true, true, false, true);
    }

    private static Step run(Conditions conditions, int progress, int burn) {
        return MachineTicker.step(conditions, progress, burn, SMELT_TICKS, BURN_PER_REDSTONE);
    }

    @Test
    void unpowered_stallsWithoutLosingProgressOrBurn() {
        Step step = run(new Conditions(false, true, true, false, true), 40, 100);

        assertEquals(Outcome.STALLED_NO_POWER, step.outcome());
        assertEquals(40, step.progressTicks());
        assertEquals(100, step.burnTicksRemaining());
        assertFalse(step.consumeOneFuel());
    }

    @Test
    void signalNotRequired_runsEvenWhenUnpowered() {
        Step step = run(new Conditions(false, false, true, false, true), 0, 100);

        assertEquals(Outcome.ADVANCED, step.outcome());
        assertEquals(1, step.progressTicks());
    }

    @Test
    void invalidRecipe_resetsProgressBecauseTheInputsThemselvesChanged() {
        Step step = run(new Conditions(true, true, false, false, true), 40, 100);

        assertEquals(Outcome.STALLED_NO_RECIPE, step.outcome());
        assertEquals(0, step.progressTicks());
        assertEquals(100, step.burnTicksRemaining());
    }

    @Test
    void blockedOutput_stallsButKeepsProgressSoItResumes() {
        Step step = run(new Conditions(true, true, true, true, true), 40, 100);

        assertEquals(Outcome.STALLED_OUTPUT_BLOCKED, step.outcome());
        assertEquals(40, step.progressTicks());
        assertEquals(100, step.burnTicksRemaining());
    }

    @Test
    void noBurnAndNoFuel_stallsWithoutLosingProgress() {
        Step step = run(new Conditions(true, true, true, false, false), 40, 0);

        assertEquals(Outcome.STALLED_NO_FUEL, step.outcome());
        assertEquals(40, step.progressTicks());
        assertEquals(0, step.burnTicksRemaining());
        assertFalse(step.consumeOneFuel());
    }

    @Test
    void noBurnButFuelPresent_buysBurnTimeAndAdvancesInTheSameTick() {
        Step step = run(ready(), 40, 0);

        assertEquals(Outcome.ADVANCED, step.outcome());
        assertTrue(step.consumeOneFuel());
        assertEquals(BURN_PER_REDSTONE - 1, step.burnTicksRemaining());
        assertEquals(41, step.progressTicks());
    }

    @Test
    void blockedRun_neverBuysFuelItCannotUse() {
        Step blockedOutput = run(new Conditions(true, true, true, true, true), 0, 0);
        Step badRecipe = run(new Conditions(true, true, false, false, true), 0, 0);
        Step unpowered = run(new Conditions(false, true, true, false, true), 0, 0);

        assertFalse(blockedOutput.consumeOneFuel());
        assertFalse(badRecipe.consumeOneFuel());
        assertFalse(unpowered.consumeOneFuel());
    }

    @Test
    void advancing_spendsExactlyOneBurnTickPerProgressTick() {
        Step step = run(ready(), 10, 50);

        assertEquals(Outcome.ADVANCED, step.outcome());
        assertEquals(11, step.progressTicks());
        assertEquals(49, step.burnTicksRemaining());
    }

    @Test
    void finalTick_completesAndResetsProgress() {
        Step step = run(ready(), SMELT_TICKS - 1, 50);

        assertEquals(Outcome.COMPLETED, step.outcome());
        assertEquals(0, step.progressTicks());
        assertEquals(49, step.burnTicksRemaining());
    }

    @Test
    void fullRunFromCold_takesExactlySmeltTicksAndOneDust() {
        int progress = 0;
        int burn = 0;
        int dustSpent = 0;
        int ticks = 0;

        Outcome outcome;
        do {
            Step step = run(ready(), progress, burn);
            outcome = step.outcome();
            if (step.consumeOneFuel()) {
                dustSpent++;
            }
            progress = step.progressTicks();
            burn = step.burnTicksRemaining();
            ticks++;
        } while (outcome != Outcome.COMPLETED && ticks < 1000);

        assertEquals(Outcome.COMPLETED, outcome);
        assertEquals(SMELT_TICKS, ticks);
        assertEquals(1, dustSpent);
    }

    @Test
    void idleMachine_neverSpendsBurnTime() {
        Step step = run(new Conditions(true, true, false, false, true), 0, 200);

        assertEquals(200, step.burnTicksRemaining());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
mvn --batch-mode --no-transfer-progress test -Dtest=MachineTickerTest
```

Expected: FAIL to compile — `MachineTicker` does not exist.

- [ ] **Step 3: Write the pure part of `MachineTicker`**

```java
package org.xpfarm.electricfurnace.machine;

/**
 * Advances every machine in a loaded chunk, one tick at a time.
 *
 * <p>{@link #step} is the entire decision, expressed as a pure transition over
 * primitives -- no {@code org.bukkit} type -- so every stall, resume, and completion path
 * is table-tested with no running server, following the same pattern as
 * {@code FurnaceGui.mayRun}.
 */
public final class MachineTicker {

    private MachineTicker() {
    }

    /** What one tick did to a machine. */
    public enum Outcome {
        /** A redstone signal is required and absent. Progress and burn are held. */
        STALLED_NO_POWER,
        /** The current inputs resolve to nothing smeltable. Progress is reset. */
        STALLED_NO_RECIPE,
        /** The output slot cannot accept the result. Progress is held so the run resumes. */
        STALLED_OUTPUT_BLOCKED,
        /** No burn time left and no redstone to buy more. Progress is held. */
        STALLED_NO_FUEL,
        /** Progress moved forward by one tick. */
        ADVANCED,
        /** This tick finished the run: deposit output and consume inputs. */
        COMPLETED
    }

    /** Everything the step needs to know about the world, as plain booleans. */
    public record Conditions(
            boolean powered,
            boolean requireSignal,
            boolean recipeValid,
            boolean outputBlocked,
            boolean fuelAvailable
    ) {
    }

    /** The result of one tick: the new counters, and whether to take a dust. */
    public record Step(Outcome outcome, int progressTicks, int burnTicksRemaining, boolean consumeOneFuel) {
    }

    /**
     * Advances one machine by one tick.
     *
     * <p>Ordering matters and is deliberate. Power, recipe, and output are all checked
     * <b>before</b> fuel is purchased, so a machine that cannot smelt never consumes a
     * dust it will not use. Only an invalid recipe resets progress -- every other stall
     * holds it, so a run interrupted by a lost signal or a full output slot resumes
     * exactly where it left off once the blocker clears.
     */
    public static Step step(Conditions conditions, int progressTicks, int burnTicksRemaining,
                            int smeltTicks, int burnTicksPerRedstone) {
        boolean effectivePowered = !conditions.requireSignal() || conditions.powered();
        if (!effectivePowered) {
            return new Step(Outcome.STALLED_NO_POWER, progressTicks, burnTicksRemaining, false);
        }
        if (!conditions.recipeValid()) {
            return new Step(Outcome.STALLED_NO_RECIPE, 0, burnTicksRemaining, false);
        }
        if (conditions.outputBlocked()) {
            return new Step(Outcome.STALLED_OUTPUT_BLOCKED, progressTicks, burnTicksRemaining, false);
        }

        int burn = burnTicksRemaining;
        boolean consumeOneFuel = false;
        if (burn <= 0) {
            if (!conditions.fuelAvailable()) {
                return new Step(Outcome.STALLED_NO_FUEL, progressTicks, 0, false);
            }
            burn = burnTicksPerRedstone;
            consumeOneFuel = true;
        }

        int progress = progressTicks + 1;
        burn = burn - 1;

        if (progress >= smeltTicks) {
            return new Step(Outcome.COMPLETED, 0, burn, consumeOneFuel);
        }
        return new Step(Outcome.ADVANCED, progress, burn, consumeOneFuel);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
mvn --batch-mode --no-transfer-progress test -Dtest=MachineTickerTest
```

Expected: PASS, 11 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/xpfarm/electricfurnace/machine/MachineTicker.java src/test/java/org/xpfarm/electricfurnace/machine/MachineTickerTest.java
git commit -m "Add pure MachineTicker step transition with stall, resume, and completion paths"
```

---

## Task 4: Slot lock and progress rendering

Both are pure; both are small; they ship together because neither carries a meaningful review gate alone.

**Files:**
- Create: `src/main/java/org/xpfarm/electricfurnace/gui/SlotLock.java`
- Create: `src/main/java/org/xpfarm/electricfurnace/gui/ProgressBar.java`
- Test: `src/test/java/org/xpfarm/electricfurnace/gui/SlotLockTest.java`
- Test: `src/test/java/org/xpfarm/electricfurnace/gui/ProgressBarTest.java`

**Interfaces:**
- Consumes: `GuiLayout.SlotRole` (existing enum: `FILLER`, `FUEL`, `INPUT`, `OUTPUT`, `INDICATOR`).
- Produces:
  - `SlotLock.Action` — enum: `INSERT`, `REMOVE`
  - `SlotLock.allows(GuiLayout.SlotRole role, Action action, boolean running)` — `static boolean`
  - `ProgressBar.render(int progressTicks, int smeltTicks)` — `static String`
  - `ProgressBar.SEGMENTS` — `int`, value `10`

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/org/xpfarm/electricfurnace/gui/SlotLockTest.java`:

```java
package org.xpfarm.electricfurnace.gui;

import org.junit.jupiter.api.Test;
import org.xpfarm.electricfurnace.gui.SlotLock.Action;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlotLockTest {

    @Test
    void idleMachine_allowsEveryInputAndFuelInteraction() {
        for (Action action : Action.values()) {
            assertTrue(SlotLock.allows(GuiLayout.SlotRole.INPUT, action, false));
            assertTrue(SlotLock.allows(GuiLayout.SlotRole.FUEL, action, false));
        }
    }

    @Test
    void runningMachine_locksInputsAgainstBothInsertAndRemove() {
        assertFalse(SlotLock.allows(GuiLayout.SlotRole.INPUT, Action.REMOVE, true));
        assertFalse(SlotLock.allows(GuiLayout.SlotRole.INPUT, Action.INSERT, true));
    }

    @Test
    void runningMachine_acceptsFuelTopUpButRefusesFuelRemoval() {
        assertTrue(SlotLock.allows(GuiLayout.SlotRole.FUEL, Action.INSERT, true));
        assertFalse(SlotLock.allows(GuiLayout.SlotRole.FUEL, Action.REMOVE, true));
    }

    @Test
    void outputIsAlwaysTakable_runningOrNot() {
        assertTrue(SlotLock.allows(GuiLayout.SlotRole.OUTPUT, Action.REMOVE, true));
        assertTrue(SlotLock.allows(GuiLayout.SlotRole.OUTPUT, Action.REMOVE, false));
    }

    @Test
    void outputNeverAcceptsInsertion() {
        assertFalse(SlotLock.allows(GuiLayout.SlotRole.OUTPUT, Action.INSERT, true));
        assertFalse(SlotLock.allows(GuiLayout.SlotRole.OUTPUT, Action.INSERT, false));
    }

    @Test
    void decorativeSlotsAreNeverInteractive() {
        for (Action action : Action.values()) {
            for (boolean running : new boolean[]{true, false}) {
                assertFalse(SlotLock.allows(GuiLayout.SlotRole.FILLER, action, running));
                assertFalse(SlotLock.allows(GuiLayout.SlotRole.INDICATOR, action, running));
            }
        }
    }
}
```

Create `src/test/java/org/xpfarm/electricfurnace/gui/ProgressBarTest.java`:

```java
package org.xpfarm.electricfurnace.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProgressBarTest {

    @Test
    void zeroProgress_rendersAnEmptyBarAtZeroPercent() {
        assertEquals("▱▱▱▱▱▱▱▱▱▱ 0%", ProgressBar.render(0, 80));
    }

    @Test
    void halfProgress_rendersHalfFilledAtFiftyPercent() {
        assertEquals("▰▰▰▰▰▱▱▱▱▱ 50%", ProgressBar.render(40, 80));
    }

    @Test
    void nearlyComplete_neverRendersAsOneHundredPercent() {
        String rendered = ProgressBar.render(79, 80);
        assertTrue(rendered.endsWith("98%"), rendered);
    }

    @Test
    void everyProgressValue_rendersExactlySegmentsPlusPercentAndNeverOverflows() {
        for (int progress = 0; progress < 80; progress++) {
            String rendered = ProgressBar.render(progress, 80);
            long filled = rendered.chars().filter(c -> c == '▰').count();
            long emptied = rendered.chars().filter(c -> c == '▱').count();
            assertEquals(ProgressBar.SEGMENTS, filled + emptied, "at progress " + progress);
        }
    }

    @Test
    void nonPositiveSmeltTicks_doesNotDivideByZero() {
        assertEquals("▱▱▱▱▱▱▱▱▱▱ 0%", ProgressBar.render(5, 0));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
mvn --batch-mode --no-transfer-progress test -Dtest='SlotLockTest+ProgressBarTest'
```

Expected: FAIL to compile — neither class exists.

- [ ] **Step 3: Write `SlotLock`**

```java
package org.xpfarm.electricfurnace.gui;

/**
 * Decides whether a player may interact with a given GUI slot right now.
 *
 * <p>Pure, so the whole policy is pinned by a table test with no running server. The
 * listener's job is reduced to classifying a click into a
 * {@code (SlotRole, Action)} pair and asking this class.
 */
public final class SlotLock {

    private SlotLock() {
    }

    /** What the player is trying to do to a slot. */
    public enum Action {
        /** Put an item into the slot. */
        INSERT,
        /** Take an item out of the slot. */
        REMOVE
    }

    /**
     * Whether {@code action} is permitted on a slot of {@code role}.
     *
     * <p>Inputs lock against insertion as well as removal while a run is in progress:
     * changing the inputs mid-run would invalidate the recipe the run already resolved.
     * Fuel may be topped up but not withdrawn, so a player can refill a running machine
     * without being able to strand it. Output is always takable -- a completed ingot is
     * the player's, running or not -- and never accepts insertion.
     */
    public static boolean allows(GuiLayout.SlotRole role, Action action, boolean running) {
        return switch (role) {
            case INPUT -> !running;
            case FUEL -> action == Action.INSERT || !running;
            case OUTPUT -> action == Action.REMOVE;
            case FILLER, INDICATOR -> false;
        };
    }
}
```

- [ ] **Step 4: Write `ProgressBar`**

```java
package org.xpfarm.electricfurnace.gui;

/**
 * Renders smelt progress as lore text.
 *
 * <p>Text rather than an animated item model: lore updates on an open inventory render
 * correctly for Bedrock players through Geyser, while item-model driven progress
 * animation does not.
 */
public final class ProgressBar {

    /** Number of bar segments rendered. */
    public static final int SEGMENTS = 10;

    private static final char FILLED = '▰';
    private static final char EMPTY = '▱';

    private ProgressBar() {
    }

    /**
     * Renders {@code progressTicks} of {@code smeltTicks} as e.g. {@code "▰▰▰▰▰▱▱▱▱▱ 50%"}.
     *
     * <p>Always emits exactly {@link #SEGMENTS} segments, and never reports 100% -- a bar
     * reading 100% while the item has not appeared yet reads as a bug to a player. A
     * non-positive {@code smeltTicks} renders as empty rather than dividing by zero.
     */
    public static String render(int progressTicks, int smeltTicks) {
        int percent = 0;
        if (smeltTicks > 0 && progressTicks > 0) {
            percent = Math.min(99, (int) ((progressTicks * 100L) / smeltTicks));
        }
        int filled = Math.min(SEGMENTS, (percent * SEGMENTS) / 100);

        StringBuilder bar = new StringBuilder(SEGMENTS + 5);
        for (int i = 0; i < SEGMENTS; i++) {
            bar.append(i < filled ? FILLED : EMPTY);
        }
        return bar.append(' ').append(percent).append('%').toString();
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

```bash
mvn --batch-mode --no-transfer-progress test -Dtest='SlotLockTest+ProgressBarTest'
```

Expected: PASS, 11 tests.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/xpfarm/electricfurnace/gui src/test/java/org/xpfarm/electricfurnace/gui
git commit -m "Add pure slot-lock policy and Bedrock-safe progress bar rendering"
```

---

## Task 5: `MachineStore` — hydrate, hold, flush

Wires persistence into the server lifecycle. No new pure logic; the risk here is lifecycle coverage, so the review focus is *every path by which a machine can leave memory*.

**Files:**
- Create: `src/main/java/org/xpfarm/electricfurnace/machine/MachineStore.java`
- Modify: `src/main/java/org/xpfarm/electricfurnace/ElectricFurnacePlugin.java`
- Modify: `src/main/java/org/xpfarm/electricfurnace/listener/MachineBlockListener.java`

**Interfaces:**
- Consumes: `MachineStateCodec.encode/decode/KEY` (Task 2), `MachineRegistry.machinesIn(Chunk)` (existing).
- Produces:
  - `MachineStore(Plugin plugin, MachineRegistry machines)`
  - `MachineStore.get(Block) -> MachineState` — hydrates from PDC on first access, never `null`
  - `MachineStore.flush(Block)` — writes one machine's state back to its block PDC
  - `MachineStore.flushAll()` — flushes every live state
  - `MachineStore.forget(Block)` — drops a machine's live state and clears its PDC (for block break)
  - `MachineStore.liveStates() -> Map<Block, MachineState>` — for the ticker

- [ ] **Step 1: Implement `MachineStore`**

Key implementation requirements, each of which the reviewer must be able to point at in the code:

1. `get(Block)` reads `block.getState()` as a `TileState`, decodes `MachineStateCodec.KEY` from its PDC, caches the result, and returns `MachineState.empty()` if the block is not a `TileState`.
2. `flush(Block)` encodes and writes to the `TileState` PDC and calls `state.update(false, false)`. Without the `update` call the PDC write is discarded — this is the single most common way this class of code silently loses everything.
3. A `ChunkUnloadEvent` handler flushes every machine in that chunk and then drops it from the live map.
4. A `WorldSaveEvent` handler calls `flushAll()`.
5. `flushAll()` is called from `ElectricFurnacePlugin.onDisable()` **before** `FurnaceGui.closeAll()`.
6. `forget(Block)` removes the PDC key and the live entry. `MachineBlockListener`'s break handler calls `FurnaceGui.returnAllItems`-equivalent logic to drop the machine's contents at the block, *then* `forget`.
7. Nothing in any handler throws — wrap each machine's flush in its own try/catch and log, so one bad machine cannot abort a chunk unload for the rest.

- [ ] **Step 2: Update block break to return contents**

In `MachineBlockListener`, the break handler must, in this order: force-close viewers, drop every non-null input/fuel/output stack at the block's location via `world.dropItemNaturally`, then `store.forget(block)`, then `registry.unregister(block)`. Progress is forfeited; items never are.

- [ ] **Step 3: Full build**

```bash
mvn --batch-mode --no-transfer-progress clean verify
```

Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add src/main
git commit -m "Persist machine state across chunk unload, world save, and shutdown"
```

---

## Task 6: GUI becomes a view onto machine state

The behavioural change players actually see. This is where `FurnaceGui`'s old "return everything on close" model is deleted, so it carries the most careful review.

**Files:**
- Modify: `src/main/java/org/xpfarm/electricfurnace/gui/FurnaceGui.java`
- Modify: `src/main/java/org/xpfarm/electricfurnace/listener/MachineGuiListener.java`
- Modify: `src/main/java/org/xpfarm/electricfurnace/listener/RedstoneListener.java:156`
- Modify: `src/test/java/org/xpfarm/electricfurnace/gui/FurnaceGuiTest.java`

**Interfaces:**
- Consumes: `MachineStore.get(Block)` (Task 5), `SlotLock.allows` and `ProgressBar.render` (Task 4), `MachineSettings.smeltTicks()` (Task 1).
- Produces:
  - `FurnaceGui.IndicatorState` gains `SMELTING`
  - `FurnaceGui.indicatorStateOf(boolean powered, boolean requireSignal, boolean hasFuel, boolean smelting)` — the old 3-arg form is replaced, not overloaded
  - `FurnaceGui.open(Player, Block, EfConfig, boolean powered, MachineState)` — binds the shared inventory

Required changes:

1. **Delete `tryProcess` entirely.** The ticker owns processing now. Remove its call sites at `MachineGuiListener:235` and `RedstoneListener:156`. `RedstoneListener` keeps only its job of tracking powered state and refreshing the indicator.
2. **`open` binds a shared inventory.** Each `MachineState` owns one `Inventory`; a second viewer receives the same instance rather than a fresh one. Slot contents mirror the state's arrays.
3. **`InventoryCloseEvent` no longer returns items.** Delete that call. The items belong to the machine. `returnAllItems` stays, called only from block break and from the `onDisable` path for machines whose flush failed.
4. **Apply `SlotLock`** in the click handler: classify the click into `(SlotRole, Action)`, pass `!state.isIdle()` as `running`, and cancel when `allows` returns `false`. The existing unconditional cancel of `COLLECT_TO_CURSOR` on a furnace GUI **must stay** — it is what prevents the double-click duplication bug fixed during initial development.
5. **`indicatorStateOf` gains `SMELTING`**, with precedence `NO_SIGNAL` > `NO_FUEL` > `SMELTING` > `RUNNING`. The `SMELTING` item is `Material.BLAST_FURNACE` with `ProgressBar.render(...)` as its first lore line.
6. **Update `FurnaceGuiTest`**: extend the existing exhaustive `indicatorStateOf` table for the new fourth boolean and assert the stated precedence. Delete tests covering `tryProcess`; that behaviour now lives in `MachineTickerTest`.

- [ ] **Step 1: Extend `indicatorStateOf` and its test first**

```java
public static IndicatorState indicatorStateOf(boolean powered, boolean requireSignal,
                                              boolean hasFuel, boolean smelting) {
    boolean effectivePowered = !requireSignal || powered;
    if (!effectivePowered) {
        return IndicatorState.NO_SIGNAL;
    }
    if (!hasFuel) {
        return IndicatorState.NO_FUEL;
    }
    if (smelting) {
        return IndicatorState.SMELTING;
    }
    return IndicatorState.RUNNING;
}
```

Test all 16 boolean combinations, asserting the precedence above.

- [ ] **Step 2: Run the GUI tests**

```bash
mvn --batch-mode --no-transfer-progress test -Dtest=FurnaceGuiTest
```

Expected: PASS.

- [ ] **Step 3: Apply changes 1-4 above, then full build**

```bash
mvn --batch-mode --no-transfer-progress clean verify
```

Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add src/main src/test
git commit -m "Make the GUI a shared view onto persistent machine state; lock inputs while running"
```

---

## Task 7: Wire the ticker, split the effects, cancel hoppers

The last task connects everything and makes the machine visibly alive.

**Files:**
- Modify: `src/main/java/org/xpfarm/electricfurnace/machine/MachineTicker.java` (add the Bukkit-facing runner)
- Modify: `src/main/java/org/xpfarm/electricfurnace/effect/MachineEffects.java:368`
- Modify: `src/main/java/org/xpfarm/electricfurnace/ElectricFurnacePlugin.java`
- Modify: `src/main/resources/config.yml`
- Test: `src/test/java/org/xpfarm/electricfurnace/effect/MachineEffectsTest.java`

**Interfaces:**
- Consumes: everything from Tasks 1-6.
- Produces:
  - `MachineEffects.shouldEmitSparks(boolean enabled, int nearbyPlayerCount, boolean powered)` — `static boolean`
  - `MachineEffects.shouldEmitSmoke(boolean enabled, int nearbyPlayerCount, boolean smelting)` — `static boolean`

- [ ] **Step 1: Write the failing effects test**

```java
@Test
void sparksFollowPower_notSmelting() {
    assertTrue(MachineEffects.shouldEmitSparks(true, 1, true));
    assertFalse(MachineEffects.shouldEmitSparks(true, 1, false));
}

@Test
void smokeFollowsSmelting_notMerePower() {
    assertTrue(MachineEffects.shouldEmitSmoke(true, 1, true));
    assertFalse(MachineEffects.shouldEmitSmoke(true, 1, false));
}

@Test
void noNearbyPlayers_emitsNothing() {
    assertFalse(MachineEffects.shouldEmitSparks(true, 0, true));
    assertFalse(MachineEffects.shouldEmitSmoke(true, 0, true));
}

@Test
void effectsDisabled_emitsNothing() {
    assertFalse(MachineEffects.shouldEmitSparks(false, 5, true));
    assertFalse(MachineEffects.shouldEmitSmoke(false, 5, true));
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
mvn --batch-mode --no-transfer-progress test -Dtest=MachineEffectsTest
```

Expected: FAIL to compile.

- [ ] **Step 3: Implement the split**

```java
/** Sparks show that the machine is energised, whether or not it has work to do. */
public static boolean shouldEmitSparks(boolean enabled, int nearbyPlayerCount, boolean powered) {
    return enabled && nearbyPlayerCount > 0 && powered;
}

/** Smoke shows actual work: it appears only while a smelt is advancing. */
public static boolean shouldEmitSmoke(boolean enabled, int nearbyPlayerCount, boolean smelting) {
    return enabled && nearbyPlayerCount > 0 && smelting;
}
```

Then split `emitFor` at `MachineEffects.java:368` so `ELECTRIC_SPARK` is guarded by `shouldEmitSparks` and both `CAMPFIRE_COSY_SMOKE` and the `BLOCK_BEACON_AMBIENT` sound are guarded by `shouldEmitSmoke`. The approved-particle allowlist and its guarding test are unchanged.

- [ ] **Step 4: Add the ticker runner**

Add to `MachineTicker` a Bukkit-facing runner that starts a `runTaskTimer(plugin, 1L, 1L)`, and each pass iterates `store.liveStates()`, skipping any machine whose chunk is not loaded. For each machine it builds `Conditions` from the block's power state, a `RecycleResolver` resolution of the current inputs, and the output-slot classification; calls `step`; then applies the result: consume a dust on `consumeOneFuel`, and on `COMPLETED` deposit the output and consume one item from each occupied input slot.

Wrap each machine's pass in its own try/catch that logs and continues. An exception here is swallowed by the scheduler and would abort the pass partway through — which is exactly how items get destroyed.

- [ ] **Step 5: Cancel hopper interaction**

Add an `InventoryMoveItemEvent` handler cancelling any move whose source or destination inventory belongs to a machine block. A `BLAST_FURNACE` accepts hopper input into its own vanilla inventory, which this plugin ignores; with persistent machines those items would sit invisible and unreachable.

Document the cancel in `config.yml` as a known limitation, not a config toggle.

- [ ] **Step 6: Wire it all in `ElectricFurnacePlugin`**

`onEnable` order: config → registry → store → effects → ticker → listeners.
`onDisable` order: ticker stop → `store.flushAll()` → `FurnaceGui.closeAll()` → effects stop.

The `flushAll` **must** precede `closeAll`, for the same reason `closeAll` returns items directly rather than relying on `InventoryCloseEvent`: by the time `onDisable` runs, `isEnabled` is already false and event dispatch to this plugin is dead.

- [ ] **Step 7: Full build**

```bash
mvn --batch-mode --no-transfer-progress clean verify
```

Expected: BUILD SUCCESS, all tests green.

- [ ] **Step 8: Commit**

```bash
git add src/main src/test
git commit -m "Run machines on a global ticker; split sparks from smoke; cancel hopper interaction"
```

---

## Self-Review

**Spec coverage:**

| Spec section | Task |
|---|---|
| `MachineState` | 2 |
| `MachineStore` | 5 |
| `MachineTicker` | 3 (pure), 7 (runner) |
| GUI becomes a view | 6 |
| Redstone as burn time | 1 (config), 3 (logic), 7 (application) |
| Speed | 1 |
| Input lock | 4 (policy), 6 (application) |
| Effects | 7 |
| Progress display | 4 (rendering), 6 (indicator state) |
| Hoppers | 7 |
| Testing — ticker table | 3 |
| Testing — `smeltTicks` range | 1 |
| Testing — burn boundaries | 3 |
| Testing — lock exhaustive | 4 |
| Testing — serialization round-trip | 2 |

No gaps.

**Known deviation from the spec, deliberate:** the spec's serialization round-trip test says it should include "alloy items carrying PDC". `ItemStack` cannot be constructed headlessly in this project, so Task 2 tests the **framing** through a `Frame` seam instead, and the `ItemStack` adapter is left to in-game verification. This is a real gap in coverage and the reviewer should treat the in-game check of a machine surviving a full server restart with an alloy in its output slot as **required**, not optional.

**Type consistency:** `smeltTicks` is used identically in Tasks 1, 3, 4, and 6. `MachineState.INPUT_COUNT` is the single source for the slot count in Tasks 2, 5, and 7. `SlotLock.Action` and `GuiLayout.SlotRole` are the only vocabularies used for slot decisions.

## Gate reminder

This is a behavioural change to a released, deployed plugin, so the lifecycle applies: bump the version, cut a release, and let the updater pick it up. `docs/PLUGIN_CHECKLIST.md` gates 6 through 11 all need re-running, and gate 7a (runtime verification on the disposable Legendary stack) is **not** optional for this change — the persistence paths cannot be proven by unit tests alone.
