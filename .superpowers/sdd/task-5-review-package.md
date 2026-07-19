# Review package: 586c7b2..4180623

4180623 Task 5: GUI and machine listeners

 .superpowers/sdd/task-5-report.md                  | 294 ++++++++++++++
 .../org/xpfarm/electricfurnace/gui/FurnaceGui.java | 431 +++++++++++++++++++++
 .../org/xpfarm/electricfurnace/gui/GuiLayout.java  | 105 +++++
 .../listener/MachineBlockListener.java             | 118 ++++++
 .../listener/MachineGuiListener.java               | 231 +++++++++++
 .../electricfurnace/listener/RedstoneListener.java |  95 +++++
 .../xpfarm/electricfurnace/gui/FurnaceGuiTest.java | 122 ++++++
 .../xpfarm/electricfurnace/gui/GuiLayoutTest.java  | 108 ++++++
 .../listener/MachineGuiListenerTest.java           | 279 +++++++++++++
 9 files changed, 1783 insertions(+)

```diff
diff --git a/.superpowers/sdd/task-5-report.md b/.superpowers/sdd/task-5-report.md
new file mode 100644
index 0000000..c8f81a0
--- /dev/null
+++ b/.superpowers/sdd/task-5-report.md
@@ -0,0 +1,294 @@
+# Task 5 — GUI and machine listeners — Report
+
+## What was implemented
+
+Followed TDD: wrote `GuiLayoutTest`, `FurnaceGuiTest`, and `MachineGuiListenerTest`
+first (each failed to compile with no implementation present), then implemented
+`GuiLayout`, `FurnaceGui`, `MachineGuiListener`, `MachineBlockListener`, and
+`RedstoneListener` against them.
+
+- **`GuiLayout`** — the fixed 27-slot layout as named constants: `FUEL_SLOT = 3`,
+  `INPUT_SLOTS = {10,11,12,13,14}`, `OUTPUT_SLOT = 16`, `INDICATOR_SLOT = 22`, every
+  other slot FILLER. `roleOf(int slot)` is a pure static function (no `org.bukkit`
+  type) classifying any of the 27 slots into `SlotRole {INPUT, FUEL, OUTPUT,
+  INDICATOR, FILLER}`, throwing `IllegalArgumentException` outside `[0, 27)`.
+
+- **`FurnaceGui`** — owns the custom `Inventory` (a `Holder implements
+  InventoryHolder` carries the owning `Block`), renders filler panes and the status
+  indicator, runs the recycler resolution, and is the single place that returns items
+  to players. Pure logic:
+  - `indicatorStateOf(powered, requireSignal, hasFuel)` → `RUNNING` / `NO_SIGNAL` /
+    `NO_FUEL`, materialized as `REDSTONE_TORCH` / `LEVER` / `REDSTONE` per the plan.
+  - `mayRun(powered, requireSignal, hasFuel, OutputSlotState)` → the processing gate.
+    `OutputSlotState` is `EMPTY` / `SAME_ITEM` / `DIFFERENT_ITEM`; `DIFFERENT_ITEM`
+    also covers "same item but merging would exceed the max stack size" (Bukkit-facing
+    `classifyOutputSlot`, untestable without a server, computes which state applies).
+  - `tryProcess(...)` — the Bukkit-facing orchestration: collects `RecycleInput`s from
+    the 5 input slots via `MetalClassifier.classify`, calls `RecycleResolver.resolve`
+    (never reimplemented), and on a successful gate check consumes exactly
+    `fuelPerOperation` redstone and **exactly one item from each occupied input slot**
+    (not the whole slot — a slot holding a stack of 5 keeps 4 for the next operation).
+  - `returnAllItems(inventory, player)` — drains input, fuel, **and** output back to
+    the player, giving via `player.getInventory().addItem` and dropping any overflow
+    at the player's feet.
+  - `closeForBlock(Block)` / `closeAll()` — force-close (`player.closeInventory()`)
+    anyone viewing a given machine's GUI, or every open Electric Furnace GUI. Closing
+    synchronously fires `InventoryCloseEvent`, so `MachineGuiListener#onClose` (below)
+    returns the items before these methods return.
+
+- **`MachineGuiListener`** — the slot guard and the item-safety net.
+  - `classify(InventoryAction)` maps every one of Paper's 25 `InventoryAction`
+    constants (including the 6 newer bundle-interaction actions) to `(isPlace,
+    isTake)`. Ambiguous/future actions (`UNKNOWN`, all 6 bundle actions) are
+    conservatively mapped to both `true` — a documented fail-safe rather than a guess.
+  - `shouldCancel(SlotRole, boolean isPlace, boolean isTake)` — the core guard:
+    FILLER/INDICATOR always cancel; OUTPUT cancels only when placing; INPUT/FUEL
+    never cancel here.
+  - `onClick` handles plain clicks, shift-click-out, hotbar swaps, and double-click
+    collection uniformly through the above (all reachable via `event.getAction()`
+    when the clicked inventory is the GUI itself).
+  - **Shift-click into the GUI** (`MOVE_TO_OTHER_INVENTORY` where the clicked
+    inventory is the player's own): Bukkit picks the destination top slot internally;
+    `event.getSlot()` reports the source slot in the bottom inventory, not the
+    destination, so the per-slot-role guard cannot be applied. This is uniformly
+    cancelled — see "Deviations" below.
+  - `onDrag` collects every touched raw slot `< GuiLayout.SIZE` into a `Set<SlotRole>`
+    and cancels the whole drag if any is FILLER/INDICATOR/OUTPUT
+    (`shouldCancelDrag`), since one drag can span several slots at once.
+  - `onClose` calls `FurnaceGui.returnAllItems` unconditionally — for any close
+    reason, including `DISCONNECT` (Paper fires `InventoryCloseEvent` on player
+    disconnect too, so no special-case code was needed for that scenario).
+  - A non-cancelled click/drag schedules one `FurnaceGui.tryProcess` call via
+    `Bukkit.getScheduler().runTask` for the next tick, since the click's own item
+    movement is applied by the server only after the event handler returns.
+
+- **`MachineBlockListener`** — `BlockPlaceEvent` registers a block carrying
+  `electricfurnace:machine`, gated on `electricfurnace.use`. `BlockBreakEvent`
+  force-closes any open GUI at that block first (`FurnaceGui.closeForBlock`, so items
+  are safely back with the player before anything is removed), then unregisters,
+  cancels the vanilla drop (`setDropItems(false)`), and drops the machine item.
+  `PlayerInteractEvent` on `RIGHT_CLICK_BLOCK`/main-hand cancels the event
+  unconditionally for a registered machine (so the vanilla blast furnace GUI never
+  opens even for a player lacking permission), then opens `FurnaceGui` if the player
+  has `electricfurnace.use`, else sends a denial message.
+
+- **`RedstoneListener`** — on `BlockRedstoneEvent` for a registered machine, drives
+  every adjacent `COPPER_BULB` (`CopperBulb#setLit` + `Block#setBlockData`) when
+  `machine.status-bulb.enabled`, and re-attempts `FurnaceGui.tryProcess` for anyone
+  currently viewing that block's GUI (the redstone change may be exactly what was
+  blocking it). Holds no separate "powered" map — `Block#getBlockPower()` is queried
+  live wherever powered state is needed (here, in `MachineGuiListener`, and in
+  `MachineBlockListener`), so there is nothing that can drift out of sync with the
+  world.
+
+## Files
+
+- `src/main/java/org/xpfarm/electricfurnace/gui/GuiLayout.java` (new)
+- `src/main/java/org/xpfarm/electricfurnace/gui/FurnaceGui.java` (new)
+- `src/main/java/org/xpfarm/electricfurnace/listener/MachineGuiListener.java` (new)
+- `src/main/java/org/xpfarm/electricfurnace/listener/MachineBlockListener.java` (new)
+- `src/main/java/org/xpfarm/electricfurnace/listener/RedstoneListener.java` (new)
+- `src/test/java/org/xpfarm/electricfurnace/gui/GuiLayoutTest.java` (new)
+- `src/test/java/org/xpfarm/electricfurnace/gui/FurnaceGuiTest.java` (new)
+- `src/test/java/org/xpfarm/electricfurnace/listener/MachineGuiListenerTest.java` (new)
+
+No files outside this list were created or modified. `MachineBlockListener` and
+`RedstoneListener` have no dedicated test file — see "Concerns."
+
+## Test coverage (51 new tests)
+
+- **`GuiLayoutTest`** (12) — exact constants (`SIZE`, `FUEL_SLOT`, `INPUT_SLOTS`,
+  `OUTPUT_SLOT`, `INDICATOR_SLOT`, `TITLE_TEXT`); exhaustive loop over all 27 slots
+  asserting the correct role for every one; parameterized out-of-range slots
+  (`-100, -1, 27, 28, 1000`) each asserted to throw.
+- **`FurnaceGuiTest`** (13) — `mayRun` exhaustive over all `2×2×2×3 = 24`
+  (powered, requireSignal, hasFuel, OutputSlotState) combinations plus named edge
+  cases (unpowered-but-signal-not-required still runs; different-output blocks even
+  when powered+fueled). `indicatorStateOf` exhaustive over all 8
+  (powered, requireSignal, hasFuel) combinations plus a precedence test (no-signal
+  beats no-fuel).
+- **`MachineGuiListenerTest`** (26) — `classify` covers every `InventoryAction`
+  constant by category (plain pickup/place, swap/hotbar, shift-click,
+  double-click-collect, drop-from-slot, no-op, and the conservative bundle/unknown
+  bucket) plus one loop asserting `classify` never throws for any constant.
+  `shouldCancel(role, isPlace, isTake)` exhaustive over all `5×2×2 = 20` combinations.
+  `shouldCancel(role, action)` exhaustive over all `5×25 = 125` role×action
+  combinations, cross-checked against the composed `classify`+`shouldCancel(3-arg)`
+  result. `shouldCancelDrag` exhaustive over all `2^5 = 32` subsets of the 5 roles.
+
+## Build verification
+
+Command:
+
+```
+mvn --batch-mode --no-transfer-progress clean verify
+```
+
+Result: **BUILD SUCCESS**. 150 tests total (99 pre-existing + 51 new), 0 failures,
+0 errors, 0 skipped:
+
+```
+[INFO] Running org.xpfarm.electricfurnace.alloy.AlloyRegistryTest
+[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0
+[INFO] Running org.xpfarm.electricfurnace.recycle.RecycleResolverTest
+[INFO] Tests run: 20, Failures: 0, Errors: 0, Skipped: 0
+[INFO] Running org.xpfarm.electricfurnace.machine.MachineKeyTest
+[INFO] Tests run: 32, Failures: 0, Errors: 0, Skipped: 0
+[INFO] Running org.xpfarm.electricfurnace.listener.MachineGuiListenerTest
+[INFO] Tests run: 26, Failures: 0, Errors: 0, Skipped: 0
+[INFO] Running org.xpfarm.electricfurnace.item.MetalClassifierTest
+[INFO] Tests run: 20, Failures: 0, Errors: 0, Skipped: 0
+[INFO] Running org.xpfarm.electricfurnace.config.ConfigValidatorTest
+[INFO] Tests run: 20, Failures: 0, Errors: 0, Skipped: 0
+[INFO] Running org.xpfarm.electricfurnace.gui.FurnaceGuiTest
+[INFO] Tests run: 13, Failures: 0, Errors: 0, Skipped: 0
+[INFO] Running org.xpfarm.electricfurnace.gui.GuiLayoutTest
+[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0
+
+Tests run: 150, Failures: 0, Errors: 0, Skipped: 0
+BUILD SUCCESS
+```
+
+Shaded JAR built successfully at `target/electric-furnace-0.1.0.jar`.
+
+Environment: sourced `~/.sdkman/bin/sdkman-init.sh` for Java 25.0.3-tem / Maven
+3.9.16, since `java`/`mvn` are not on PATH by default.
+
+One build-time hazard found and fixed during TDD: an early draft of `FurnaceGui` built
+its filler-pane `ItemStack` in a `static final` field, initialized at class-load time.
+Referencing `FurnaceGui.mayRun(...)` from a plain JUnit test then triggered
+`<clinit>`, which tried to construct an `ItemStack` outside a running server and threw
+`ExceptionInInitializerError`. Fixed by building the filler item on demand inside
+`open()` instead of caching it statically — this is exactly the kind of "Bukkit type
+touched anywhere near a pure function" trap the TESTABILITY section warns about, just
+one level removed (via a static initializer rather than a method parameter).
+
+## Deviations from the plan, and why
+
+1. **No cross-session persistence for GUI contents.** The plan only specifies
+   returning "all input and fuel items" on close; it does not describe how/whether
+   slot contents persist across GUI opens (Task 4's `MachineRegistry` persists only
+   machine *locations*). This implementation deliberately makes every `open()` call
+   create a brand-new, empty backing `Inventory`, and `returnAllItems` drains
+   input+fuel **and output** on every close (not just input+fuel as literally
+   specified). Rationale: the backing inventory lives only in the JVM heap (a Bukkit
+   `Inventory`/`InventoryHolder`, not a chunk/block PDC) — if any item were ever left
+   sitting in it while no player views it, a server restart would silently erase it,
+   which is exactly the item-loss failure this task calls the highest-stakes
+   requirement. Draining everything on every close eliminates that risk entirely at
+   the cost of one behavior beyond the letter of the spec (a player who closes without
+   collecting their output gets it handed back immediately rather than finding it
+   still sitting there next time they open the GUI) — strictly safer, never worse.
+
+2. **Shift-click into the GUI is always cancelled**, not selectively guarded per
+   destination slot. Bukkit's `MOVE_TO_OTHER_INVENTORY` handling for a shift-click
+   *from* the player's own inventory picks the destination top slot using its own
+   internal fill-order algorithm; `InventoryClickEvent.getSlot()` at that point is the
+   *source* slot in the bottom inventory, not the destination, so
+   `GuiLayout.roleOf(event.getSlot())` cannot be used to guard it directly. That fill
+   algorithm will use the output slot if it is the only empty top slot at the time —
+   exactly the placement the plan says must never happen. Rather than reimplement
+   Bukkit's fill order to predict the destination (fragile, and easy to get subtly
+   wrong across Paper versions), shift-click-in is uniformly disallowed. Players can
+   still plain-click or drag items into the input/fuel slots one at a time; shift-click
+   *out* of the GUI (taking items) is unaffected and still works normally.
+
+3. **Input slots are consumed one item at a time per successful operation**, not
+   cleared wholesale. Not specified either way by the plan, but clearing a slot
+   containing a stack (e.g. a player queues 5 iron ingots in one slot) would silently
+   destroy the 4 leftover items — a direct violation of the "never destroy items"
+   requirement. Each operation now consumes exactly one item from each currently
+   occupied input slot, leaving any surplus for the next operation.
+
+4. **`RedstoneListener` tracks no explicit "powered" state map.** The plan's wording
+   ("track powered state") is satisfied by querying `Block#getBlockPower()` live
+   wherever powered state is needed, rather than maintaining a cache that could drift
+   from the world. `RedstoneListener` itself reacts to `BlockRedstoneEvent` to drive
+   the copper bulb and re-attempt processing, but does not need to remember the
+   previous state to do either.
+
+5. **`MachineGuiListener`/`MachineBlockListener`/`RedstoneListener` take
+   `Supplier<EfConfig>`/`Supplier<AlloyRegistry>`** (not the config/registry directly),
+   so Task 6's `/electricfurnace reload` can hot-swap the underlying config/alloys by
+   updating whatever mutable holder backs the supplier, without needing to
+   re-register listeners. Not specified by the plan, but required for the "reload...
+   re-applies it live... without a server restart" requirement that Task 6 owns to be
+   satisfiable at all from a Task 5 listener that reads config at click/redstone time
+   rather than once at construction.
+
+## Dependencies
+
+**No dependencies were added to `pom.xml`.** Everything builds against the existing
+`paper-api` (provided) and `junit-jupiter` (test) dependencies already present.
+
+## Item-loss scenarios: what was handled, and how
+
+- **`InventoryCloseEvent` (any reason)** — `MachineGuiListener#onClose` calls
+  `FurnaceGui.returnAllItems`, which gives every input/fuel/output item to the player
+  via `Inventory#addItem` and drops whatever doesn't fit at the player's location.
+- **Player disconnects with the GUI open** — Paper's `InventoryCloseEvent.Reason`
+  includes `DISCONNECT` specifically for this case, and Paper fires the close event
+  on disconnect; the same `onClose` handler above covers it with no special-case code.
+- **Server shutdown with the GUI open** — `FurnaceGui.closeAll()` iterates online
+  players and calls `player.closeInventory()` for anyone viewing an Electric Furnace
+  GUI, which synchronously fires `InventoryCloseEvent` and returns items via the same
+  path above. This is exposed as a static method for Task 6's `onDisable` to call; it
+  is not itself wired into an `onDisable` in this task, since `ElectricFurnacePlugin`
+  is a Task 6 file. **Flagging this explicitly as a cross-task dependency**: Task 6
+  must call `FurnaceGui.closeAll()` in `onDisable`, or this scenario is not actually
+  covered end-to-end despite the method existing.
+- **The block is broken while someone has the GUI open** — `MachineBlockListener#onBreak`
+  calls `FurnaceGui.closeForBlock(block)` before unregistering/dropping the machine
+  item, force-closing any viewer and returning their items via the same `onClose`
+  path, before the block (and its now-orphaned virtual inventory) disappears.
+- **Output slot occupied by a different item** — `FurnaceGui.classifyOutputSlot`
+  returns `DIFFERENT_ITEM` (blocking `mayRun`) both when a genuinely different item
+  occupies the slot and when the same item is present but merging would exceed the
+  max stack size — the latter isn't explicitly called out by the plan, but silently
+  overflowing a stack is the same category of corruption the plan's explicit rule is
+  guarding against.
+- **A slot holding more than one item** — only one item per occupied input slot is
+  consumed per successful operation (see Deviation 3); the rest survive for next time.
+- **Drag spanning a guarded slot** — `InventoryDragEvent` is guarded independently of
+  `InventoryClickEvent`; the whole drag is cancelled if it touches FILLER, INDICATOR,
+  or OUTPUT, since a single drag can distribute the cursor stack across several slots
+  at once and a per-click guard alone would miss it.
+
+## Concerns
+
+- **`MachineBlockListener` and `RedstoneListener` have no dedicated unit test.**
+  Consistent with the established pattern from Tasks 1-4 (`MachineRegistry` was
+  likewise left untested in Task 4): `Block`, `Player`, `BlockRedstoneEvent`,
+  `BlockPlaceEvent`, `BlockBreakEvent`, and `PlayerInteractEvent` cannot be
+  constructed without a live Bukkit server. Every piece of logic in these two classes
+  that *could* be extracted as a pure decision already has been (the slot guard in
+  `MachineGuiListener`, the processing gate and indicator state in `FurnaceGui`); what
+  remains in `MachineBlockListener`/`RedstoneListener` is thin, sequential Bukkit glue
+  (permission checks, event cancellation order, delegating to already-tested pure
+  logic) with no independent branching worth extracting. Recommend exercising both at
+  gate 7a runtime verification: place/break the machine as a permitted and
+  unpermitted player, right-click to confirm the vanilla blast furnace GUI never
+  appears, toggle redstone to confirm the copper bulb and indicator both react, and
+  specifically test the four item-loss scenarios listed above against a live server.
+- **Task 6 must wire `FurnaceGui.closeAll()` into `onDisable`** for the
+  "server shutdown with the GUI open" scenario to actually be covered — see Deviation/
+  scenario notes above. This is called out so it isn't silently dropped when Task 6
+  is implemented.
+- **`scheduleProcess`'s next-tick deferral is unverified against a live server.** The
+  reasoning (a click's item movement is applied by the server only after the event
+  handler returns, so reading slot contents synchronously would see stale state) is
+  standard Bukkit plugin-development knowledge, but this task's environment cannot run
+  a live server to confirm it empirically. Recommend confirming at gate 7a: insert
+  fuel and a full set of inputs in the same click sequence a player would realistically
+  perform, and confirm the operation actually runs on the following tick.
+- **Bundle `InventoryAction` semantics were not empirically verified.** The exact
+  item-movement direction of the 6 bundle-related actions
+  (`PICKUP_FROM_BUNDLE`/`PICKUP_ALL_INTO_BUNDLE`/`PICKUP_SOME_INTO_BUNDLE`/
+  `PLACE_FROM_BUNDLE`/`PLACE_ALL_INTO_BUNDLE`/`PLACE_SOME_INTO_BUNDLE`) was not looked
+  up against Minecraft/Paper source; they are deliberately treated as
+  "conservatively both place and take" (see Deviations), which cannot let an item
+  through a guarded slot regardless of which way they actually move items, at the cost
+  of also blocking a legitimate bundle-based take from the output slot in that rare
+  case. Given bundles are an uncommon interaction with a furnace-style GUI, this was
+  judged an acceptable, safety-first trade-off rather than worth the risk of guessing
+  wrong.
diff --git a/src/main/java/org/xpfarm/electricfurnace/gui/FurnaceGui.java b/src/main/java/org/xpfarm/electricfurnace/gui/FurnaceGui.java
new file mode 100644
index 0000000..61bdbf4
--- /dev/null
+++ b/src/main/java/org/xpfarm/electricfurnace/gui/FurnaceGui.java
@@ -0,0 +1,431 @@
+/*
+ * ElectricFurnace - a redstone-powered smelter that recycles metal gear into ingots.
+ * Copyright (C) 2026 Carmelo Santana
+ *
+ * This program is free software: you can redistribute it and/or modify it under the
+ * terms of the GNU Affero General Public License as published by the Free Software
+ * Foundation, either version 3 of the License, or (at your option) any later version.
+ * See the LICENSE file at the project root for the full license text.
+ */
+package org.xpfarm.electricfurnace.gui;
+
+import net.kyori.adventure.text.Component;
+import net.kyori.adventure.text.format.TextDecoration;
+import org.bukkit.Bukkit;
+import org.bukkit.Material;
+import org.bukkit.block.Block;
+import org.bukkit.entity.Player;
+import org.bukkit.inventory.Inventory;
+import org.bukkit.inventory.InventoryHolder;
+import org.bukkit.inventory.ItemStack;
+import org.bukkit.inventory.meta.ItemMeta;
+import org.xpfarm.electricfurnace.alloy.AlloyDefinition;
+import org.xpfarm.electricfurnace.alloy.AlloyRegistry;
+import org.xpfarm.electricfurnace.alloy.MetalType;
+import org.xpfarm.electricfurnace.config.EfConfig;
+import org.xpfarm.electricfurnace.item.AlloyItemFactory;
+import org.xpfarm.electricfurnace.item.MetalClassifier;
+import org.xpfarm.electricfurnace.recycle.RecycleInput;
+import org.xpfarm.electricfurnace.recycle.RecycleResolver;
+import org.xpfarm.electricfurnace.recycle.RecycleResult;
+
+import java.util.ArrayList;
+import java.util.HashSet;
+import java.util.List;
+import java.util.Map;
+import java.util.Objects;
+import java.util.Optional;
+import java.util.Set;
+
+/**
+ * Owns the Electric Furnace's 27-slot custom {@link Inventory}: rendering the filler
+ * panes and status indicator, running the recycler resolution, and returning items to
+ * players -- never destroying them.
+ *
+ * <p><b>No cross-session persistence, by design.</b> Every {@link #open} call creates
+ * a brand-new, empty backing {@code Inventory}; nothing is ever written to a chunk or
+ * block PDC for slot contents (Task 4's {@code MachineRegistry} persists only machine
+ * <em>locations</em>, never item state). This is exactly what makes the highest-stakes
+ * requirement tractable: {@link #returnAllItems} unconditionally drains input, fuel,
+ * <b>and</b> output back to the viewing player on every close, so nothing is ever left
+ * sitting in a plain in-memory {@code Map} that would silently evaporate on server
+ * restart. The plan only requires returning input and fuel on close; this class
+ * deliberately also returns any unclaimed output, since leaving it behind would be an
+ * item-loss bug the moment the server restarts before that player reopens the GUI.
+ *
+ * <p>The processing gate ({@link #mayRun}) and the status-indicator decision
+ * ({@link #indicatorStateOf}) are pure functions over primitives/enums -- no
+ * {@code org.bukkit} type -- so {@code FurnaceGuiTest} exercises every combination
+ * with no running server, following the same pattern as
+ * {@code MetalClassifier.resolveBranch}.
+ */
+public final class FurnaceGui {
+
+    private FurnaceGui() {
+    }
+
+    // =================================================================================
+    // Pure decision logic
+    // =================================================================================
+
+    /** What the non-interactive status indicator (slot {@link GuiLayout#INDICATOR_SLOT}) should show. */
+    public enum IndicatorState {
+        /** Powered (or signal not required) and fuel present: the machine will process on the next attempt. */
+        RUNNING,
+        /** {@code machine.require-redstone-signal} is {@code true} and the machine is not currently powered. */
+        NO_SIGNAL,
+        /** Powered (or signal not required), but the fuel slot lacks enough redstone. */
+        NO_FUEL
+    }
+
+    /**
+     * Decides the status indicator's state from plain booleans. {@code NO_SIGNAL}
+     * takes precedence over {@code NO_FUEL} when both apply: the more fundamental
+     * blocker is reported first.
+     */
+    public static IndicatorState indicatorStateOf(boolean powered, boolean requireSignal, boolean hasFuel) {
+        boolean effectivePowered = !requireSignal || powered;
+        if (!effectivePowered) {
+            return IndicatorState.NO_SIGNAL;
+        }
+        if (!hasFuel) {
+            return IndicatorState.NO_FUEL;
+        }
+        return IndicatorState.RUNNING;
+    }
+
+    /** What the output slot currently holds, relative to the item an operation would produce. */
+    public enum OutputSlotState {
+        /** The output slot is empty. */
+        EMPTY,
+        /** The output slot holds an item that matches what would be produced, with room to merge. */
+        SAME_ITEM,
+        /** The output slot holds something else -- or the same item with no room left -- blocking the run. */
+        DIFFERENT_ITEM
+    }
+
+    /**
+     * The processing gate: an operation may run only when effectively powered
+     * (powered, or {@code requireSignal} is {@code false}), fuel is present, and the
+     * output slot does not block it. An output slot occupied by a different item (or
+     * the same item with no stacking room left) never runs and never consumes fuel --
+     * a player's output is never silently overwritten or corrupted.
+     */
+    public static boolean mayRun(boolean powered, boolean requireSignal, boolean hasFuel, OutputSlotState outputSlotState) {
+        boolean effectivePowered = !requireSignal || powered;
+        return effectivePowered && hasFuel && outputSlotState != OutputSlotState.DIFFERENT_ITEM;
+    }
+
+    // =================================================================================
+    // Bukkit-facing glue
+    // =================================================================================
+
+    /** Marks a custom inventory as belonging to one specific Electric Furnace block. */
+    public static final class Holder implements InventoryHolder {
+        private final Block block;
+        private Inventory inventory;
+
+        private Holder(Block block) {
+            this.block = Objects.requireNonNull(block, "block");
+        }
+
+        /** The Electric Furnace block this GUI instance belongs to. */
+        public Block block() {
+            return block;
+        }
+
+        @Override
+        public Inventory getInventory() {
+            return inventory;
+        }
+    }
+
+    /** Whether {@code inventory} is an Electric Furnace GUI. */
+    public static boolean isFurnaceGui(Inventory inventory) {
+        return inventory != null && inventory.getHolder() instanceof Holder;
+    }
+
+    /** The block a given Electric Furnace GUI inventory belongs to, if it is one. */
+    public static Optional<Block> blockOf(Inventory inventory) {
+        if (inventory != null && inventory.getHolder() instanceof Holder holder) {
+            return Optional.of(holder.block());
+        }
+        return Optional.empty();
+    }
+
+    /**
+     * Opens a brand-new Electric Furnace GUI for {@code player} at {@code block}.
+     * Always starts empty -- see the class-level note on why nothing persists across
+     * sessions.
+     */
+    public static Inventory open(Player player, Block block, EfConfig config, boolean powered) {
+        Objects.requireNonNull(player, "player");
+        Objects.requireNonNull(block, "block");
+        Objects.requireNonNull(config, "config");
+
+        Holder holder = new Holder(block);
+        Inventory inventory = Bukkit.createInventory(holder, GuiLayout.SIZE, Component.text(GuiLayout.TITLE_TEXT));
+        holder.inventory = inventory;
+
+        for (int slot = 0; slot < GuiLayout.SIZE; slot++) {
+            if (GuiLayout.roleOf(slot) == GuiLayout.SlotRole.FILLER) {
+                inventory.setItem(slot, buildFillerItem());
+            }
+        }
+        refreshIndicator(inventory, config, powered);
+
+        player.openInventory(inventory);
+        return inventory;
+    }
+
+    /** Recomputes and redraws the status indicator item from the inventory's current fuel slot. */
+    public static void refreshIndicator(Inventory inventory, EfConfig config, boolean powered) {
+        Objects.requireNonNull(inventory, "inventory");
+        Objects.requireNonNull(config, "config");
+
+        boolean hasFuel = hasSufficientFuel(inventory, config.machine().fuelPerOperation());
+        IndicatorState state = indicatorStateOf(powered, config.machine().requireRedstoneSignal(), hasFuel);
+        inventory.setItem(GuiLayout.INDICATOR_SLOT, indicatorItem(state));
+    }
+
+    private static boolean hasSufficientFuel(Inventory inventory, int fuelPerOperation) {
+        ItemStack fuel = inventory.getItem(GuiLayout.FUEL_SLOT);
+        return fuel != null && fuel.getType() == Material.REDSTONE && fuel.getAmount() >= fuelPerOperation;
+    }
+
+    /**
+     * Attempts one recycler operation against {@code inventory}'s current input/fuel
+     * slots. Always redraws the status indicator, whether or not the attempt
+     * succeeds.
+     *
+     * @return {@code true} if an operation ran (fuel consumed, output produced)
+     */
+    public static boolean tryProcess(Inventory inventory, EfConfig config, AlloyRegistry alloys, boolean powered) {
+        Objects.requireNonNull(inventory, "inventory");
+        Objects.requireNonNull(config, "config");
+        Objects.requireNonNull(alloys, "alloys");
+
+        List<RecycleInput> inputs = collectInputs(inventory, config);
+        RecycleResult result = RecycleResolver.resolve(inputs, config.recycling(), alloys);
+
+        refreshIndicator(inventory, config, powered);
+
+        if (result instanceof RecycleResult.Rejected) {
+            return false;
+        }
+
+        boolean hasFuel = hasSufficientFuel(inventory, config.machine().fuelPerOperation());
+        ItemStack candidateOutput = candidateItemFor(result, alloys);
+        ItemStack currentOutput = inventory.getItem(GuiLayout.OUTPUT_SLOT);
+        OutputSlotState outputState = classifyOutputSlot(currentOutput, candidateOutput);
+
+        if (!mayRun(powered, config.machine().requireRedstoneSignal(), hasFuel, outputState)) {
+            return false;
+        }
+
+        consumeFuel(inventory, config.machine().fuelPerOperation());
+        depositOutput(inventory, currentOutput, candidateOutput);
+        consumeOneFromEachOccupiedInputSlot(inventory);
+
+        refreshIndicator(inventory, config, powered);
+        return true;
+    }
+
+    private static List<RecycleInput> collectInputs(Inventory inventory, EfConfig config) {
+        List<RecycleInput> inputs = new ArrayList<>();
+        for (int slot : GuiLayout.INPUT_SLOTS) {
+            ItemStack item = inventory.getItem(slot);
+            if (item == null || item.getType() == Material.AIR) {
+                continue;
+            }
+            inputs.add(MetalClassifier.classify(item, config.recycling())
+                    .orElseGet(() -> new RecycleInput(item.getType().name(), null, false, false, null, 0)));
+        }
+        return inputs;
+    }
+
+    private static void consumeFuel(Inventory inventory, int fuelPerOperation) {
+        ItemStack fuel = inventory.getItem(GuiLayout.FUEL_SLOT);
+        int remaining = fuel.getAmount() - fuelPerOperation;
+        if (remaining <= 0) {
+            inventory.setItem(GuiLayout.FUEL_SLOT, null);
+        } else {
+            fuel.setAmount(remaining);
+        }
+    }
+
+    private static void depositOutput(Inventory inventory, ItemStack currentOutput, ItemStack candidateOutput) {
+        if (currentOutput == null || currentOutput.getType() == Material.AIR) {
+            inventory.setItem(GuiLayout.OUTPUT_SLOT, candidateOutput);
+        } else {
+            currentOutput.setAmount(currentOutput.getAmount() + candidateOutput.getAmount());
+        }
+    }
+
+    /**
+     * Consumes exactly one item from each currently-occupied input slot -- never the
+     * whole stack. A slot holding more than one item (e.g. a player queued up a stack
+     * of 5 iron ingots in one slot) keeps its remaining items for the next operation;
+     * clearing the whole slot here would silently destroy them.
+     */
+    private static void consumeOneFromEachOccupiedInputSlot(Inventory inventory) {
+        for (int slot : GuiLayout.INPUT_SLOTS) {
+            ItemStack item = inventory.getItem(slot);
+            if (item == null || item.getType() == Material.AIR) {
+                continue;
+            }
+            int remaining = item.getAmount() - 1;
+            if (remaining <= 0) {
+                inventory.setItem(slot, null);
+            } else {
+                item.setAmount(remaining);
+            }
+        }
+    }
+
+    /**
+     * Compares the output slot's current contents against what an operation would
+     * produce. Blocks the run (returns {@link OutputSlotState#DIFFERENT_ITEM}) not
+     * only when a genuinely different item occupies the slot, but also when merging
+     * would exceed the max stack size -- an overflowing stack is exactly the kind of
+     * silent corruption this plugin must never cause.
+     */
+    static OutputSlotState classifyOutputSlot(ItemStack current, ItemStack candidate) {
+        if (current == null || current.getType() == Material.AIR) {
+            return OutputSlotState.EMPTY;
+        }
+        if (candidate == null || !current.isSimilar(candidate)) {
+            return OutputSlotState.DIFFERENT_ITEM;
+        }
+        if (current.getAmount() + candidate.getAmount() > current.getMaxStackSize()) {
+            return OutputSlotState.DIFFERENT_ITEM;
+        }
+        return OutputSlotState.SAME_ITEM;
+    }
+
+    private static ItemStack candidateItemFor(RecycleResult result, AlloyRegistry alloys) {
+        if (result instanceof RecycleResult.SameMetal sameMetal) {
+            ItemStack stack = new ItemStack(ingotMaterialOf(sameMetal.metal()));
+            stack.setAmount(sameMetal.amount());
+            return stack;
+        }
+        if (result instanceof RecycleResult.NamedAlloy namedAlloy) {
+            return alloyStack(namedAlloy.alloyId(), namedAlloy.amount(), alloys);
+        }
+        if (result instanceof RecycleResult.GenericAlloy genericAlloy) {
+            return alloyStack(genericAlloy.alloyId(), genericAlloy.amount(), alloys);
+        }
+        if (result instanceof RecycleResult.Remelt remelt) {
+            return alloyStack(remelt.alloyId(), remelt.amount(), alloys);
+        }
+        // Rejected: never reached, callers return early on Rejected before calling this.
+        throw new IllegalStateException("candidateItemFor called with a Rejected result");
+    }
+
+    private static ItemStack alloyStack(String alloyId, int amount, AlloyRegistry alloys) {
+        AlloyDefinition definition = alloys.get(alloyId)
+                .orElseThrow(() -> new IllegalStateException(
+                        "RecycleResolver referenced unknown alloy id '" + alloyId + "'"));
+        ItemStack stack = AlloyItemFactory.create(definition);
+        stack.setAmount(amount);
+        return stack;
+    }
+
+    private static Material ingotMaterialOf(MetalType metal) {
+        return switch (metal) {
+            case IRON -> Material.IRON_INGOT;
+            case GOLD -> Material.GOLD_INGOT;
+            case COPPER -> Material.COPPER_INGOT;
+            case NETHERITE -> Material.NETHERITE_INGOT;
+        };
+    }
+
+    // ---- Item-safety: returning contents, and force-closing viewers ----------------
+
+    /**
+     * Unconditionally returns every input, fuel, and output item currently in
+     * {@code inventory} to {@code player}, dropping at the player's location whatever
+     * does not fit in their inventory. Nothing is ever silently destroyed.
+     */
+    public static void returnAllItems(Inventory inventory, Player player) {
+        Objects.requireNonNull(inventory, "inventory");
+        Objects.requireNonNull(player, "player");
+
+        Set<Integer> slots = new HashSet<>(GuiLayout.INPUT_SLOTS);
+        slots.add(GuiLayout.FUEL_SLOT);
+        slots.add(GuiLayout.OUTPUT_SLOT);
+
+        for (int slot : slots) {
+            ItemStack item = inventory.getItem(slot);
+            if (item == null || item.getType() == Material.AIR) {
+                continue;
+            }
+            inventory.setItem(slot, null);
+            giveOrDrop(player, item);
+        }
+    }
+
+    private static void giveOrDrop(Player player, ItemStack item) {
+        Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
+        for (ItemStack overflow : leftover.values()) {
+            player.getWorld().dropItemNaturally(player.getLocation(), overflow);
+        }
+    }
+
+    /**
+     * Force-closes any online player currently viewing {@code block}'s Electric
+     * Furnace GUI. Closing synchronously fires {@code InventoryCloseEvent}, so the
+     * normal close handler ({@code MachineGuiListener#onClose}) returns their items
+     * before this method returns -- callers (e.g. a block break) can safely proceed
+     * immediately afterward.
+     */
+    public static void closeForBlock(Block block) {
+        Objects.requireNonNull(block, "block");
+        for (Player player : Bukkit.getOnlinePlayers()) {
+            Inventory top = player.getOpenInventory().getTopInventory();
+            if (top.getHolder() instanceof Holder holder && holder.block().equals(block)) {
+                player.closeInventory();
+            }
+        }
+    }
+
+    /**
+     * Force-closes every online player currently viewing any Electric Furnace GUI.
+     * Intended for the plugin's {@code onDisable}, so a server shutdown never leaves
+     * a player's input/fuel/output stranded in a GUI that is about to vanish.
+     */
+    public static void closeAll() {
+        for (Player player : Bukkit.getOnlinePlayers()) {
+            Inventory top = player.getOpenInventory().getTopInventory();
+            if (top.getHolder() instanceof Holder) {
+                player.closeInventory();
+            }
+        }
+    }
+
+    private static ItemStack indicatorItem(IndicatorState state) {
+        Material material = switch (state) {
+            case RUNNING -> Material.REDSTONE_TORCH;
+            case NO_SIGNAL -> Material.LEVER;
+            case NO_FUEL -> Material.REDSTONE;
+        };
+        String label = switch (state) {
+            case RUNNING -> "Running";
+            case NO_SIGNAL -> "No redstone signal";
+            case NO_FUEL -> "No fuel";
+        };
+        ItemStack stack = new ItemStack(material);
+        ItemMeta meta = stack.getItemMeta();
+        meta.displayName(Component.text(label).decoration(TextDecoration.ITALIC, false));
+        stack.setItemMeta(meta);
+        return stack;
+    }
+
+    private static ItemStack buildFillerItem() {
+        ItemStack stack = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
+        ItemMeta meta = stack.getItemMeta();
+        meta.displayName(Component.text(" ").decoration(TextDecoration.ITALIC, false));
+        stack.setItemMeta(meta);
+        return stack;
+    }
+}
diff --git a/src/main/java/org/xpfarm/electricfurnace/gui/GuiLayout.java b/src/main/java/org/xpfarm/electricfurnace/gui/GuiLayout.java
new file mode 100644
index 0000000..cfcda76
--- /dev/null
+++ b/src/main/java/org/xpfarm/electricfurnace/gui/GuiLayout.java
@@ -0,0 +1,105 @@
+/*
+ * ElectricFurnace - a redstone-powered smelter that recycles metal gear into ingots.
+ * Copyright (C) 2026 Carmelo Santana
+ *
+ * This program is free software: you can redistribute it and/or modify it under the
+ * terms of the GNU Affero General Public License as published by the Free Software
+ * Foundation, either version 3 of the License, or (at your option) any later version.
+ * See the LICENSE file at the project root for the full license text.
+ */
+package org.xpfarm.electricfurnace.gui;
+
+import java.util.Set;
+
+/**
+ * The fixed 27-slot (3-row) layout of the Electric Furnace GUI, as named constants.
+ *
+ * <p>No magic slot numbers belong anywhere else in this plugin -- every listener and
+ * the GUI factory itself refer to slots only through {@link #FUEL_SLOT},
+ * {@link #INPUT_SLOTS}, {@link #OUTPUT_SLOT}, {@link #INDICATOR_SLOT}, and
+ * {@link #roleOf(int)}.
+ *
+ * <p>Layout:
+ * <ul>
+ *   <li>Slot 3: redstone fuel slot.</li>
+ *   <li>Slots 10-14: the five recycler input slots.</li>
+ *   <li>Slot 16: the output slot (may be taken from, never inserted into).</li>
+ *   <li>Slot 22: the non-interactive status indicator.</li>
+ *   <li>Every other slot: a non-interactive filler pane.</li>
+ * </ul>
+ *
+ * <p>This class touches nothing but {@code int} and {@link Set} -- no
+ * {@code org.bukkit} type anywhere -- so {@code GuiLayoutTest} can assert the slot
+ * index math and role classification exhaustively with no running server.
+ */
+public final class GuiLayout {
+
+    /** Total slot count of the GUI (3 rows of 9). */
+    public static final int SIZE = 27;
+
+    /** Plain-text GUI title. Custom inventory titles are Bedrock-safe. */
+    public static final String TITLE_TEXT = "Electric Furnace";
+
+    /** The single redstone fuel slot. */
+    public static final int FUEL_SLOT = 3;
+
+    /** The five recycler input slots. */
+    public static final int INPUT_SLOT_1 = 10;
+    public static final int INPUT_SLOT_2 = 11;
+    public static final int INPUT_SLOT_3 = 12;
+    public static final int INPUT_SLOT_4 = 13;
+    public static final int INPUT_SLOT_5 = 14;
+
+    /** All five recycler input slots, as an immutable set. */
+    public static final Set<Integer> INPUT_SLOTS =
+            Set.of(INPUT_SLOT_1, INPUT_SLOT_2, INPUT_SLOT_3, INPUT_SLOT_4, INPUT_SLOT_5);
+
+    /** The output slot: may be taken from, never inserted into. */
+    public static final int OUTPUT_SLOT = 16;
+
+    /** The non-interactive status indicator slot. */
+    public static final int INDICATOR_SLOT = 22;
+
+    private GuiLayout() {
+    }
+
+    /** The functional role a given raw slot index plays in this layout. */
+    public enum SlotRole {
+        /** One of the five recycler input slots. */
+        INPUT,
+        /** The redstone fuel slot. */
+        FUEL,
+        /** The output slot: takeable, never insertable. */
+        OUTPUT,
+        /** The non-interactive status indicator. */
+        INDICATOR,
+        /** A non-interactive filler pane. */
+        FILLER
+    }
+
+    /**
+     * Classifies a raw slot index into its {@link SlotRole}.
+     *
+     * @param slot a raw slot index into the 27-slot GUI inventory
+     * @return the role that slot plays
+     * @throws IllegalArgumentException if {@code slot} is outside {@code [0, SIZE)}
+     */
+    public static SlotRole roleOf(int slot) {
+        if (slot < 0 || slot >= SIZE) {
+            throw new IllegalArgumentException("slot " + slot + " is outside [0, " + SIZE + ")");
+        }
+        if (slot == FUEL_SLOT) {
+            return SlotRole.FUEL;
+        }
+        if (INPUT_SLOTS.contains(slot)) {
+            return SlotRole.INPUT;
+        }
+        if (slot == OUTPUT_SLOT) {
+            return SlotRole.OUTPUT;
+        }
+        if (slot == INDICATOR_SLOT) {
+            return SlotRole.INDICATOR;
+        }
+        return SlotRole.FILLER;
+    }
+}
diff --git a/src/main/java/org/xpfarm/electricfurnace/listener/MachineBlockListener.java b/src/main/java/org/xpfarm/electricfurnace/listener/MachineBlockListener.java
new file mode 100644
index 0000000..59ca563
--- /dev/null
+++ b/src/main/java/org/xpfarm/electricfurnace/listener/MachineBlockListener.java
@@ -0,0 +1,118 @@
+/*
+ * ElectricFurnace - a redstone-powered smelter that recycles metal gear into ingots.
+ * Copyright (C) 2026 Carmelo Santana
+ *
+ * This program is free software: you can redistribute it and/or modify it under the
+ * terms of the GNU Affero General Public License as published by the Free Software
+ * Foundation, either version 3 of the License, or (at your option) any later version.
+ * See the LICENSE file at the project root for the full license text.
+ */
+package org.xpfarm.electricfurnace.listener;
+
+import net.kyori.adventure.text.Component;
+import net.kyori.adventure.text.format.NamedTextColor;
+import org.bukkit.block.Block;
+import org.bukkit.entity.Player;
+import org.bukkit.event.EventHandler;
+import org.bukkit.event.Listener;
+import org.bukkit.event.block.Action;
+import org.bukkit.event.block.BlockBreakEvent;
+import org.bukkit.event.block.BlockPlaceEvent;
+import org.bukkit.event.player.PlayerInteractEvent;
+import org.bukkit.inventory.EquipmentSlot;
+import org.bukkit.inventory.ItemStack;
+import org.xpfarm.electricfurnace.config.EfConfig;
+import org.xpfarm.electricfurnace.gui.FurnaceGui;
+import org.xpfarm.electricfurnace.item.MachineItemFactory;
+import org.xpfarm.electricfurnace.item.MaterialContract;
+import org.xpfarm.electricfurnace.machine.MachineRegistry;
+
+import java.util.Objects;
+import java.util.function.Supplier;
+
+/**
+ * Registers/unregisters Electric Furnace blocks and opens the custom GUI in place of
+ * the native blast furnace's.
+ *
+ * <p><b>Placing:</b> an item carrying {@link MaterialContract#MACHINE} registers the
+ * placed block, gated on {@code electricfurnace.use}.
+ *
+ * <p><b>Breaking:</b> a registered machine drops the machine item (never a plain
+ * blast furnace) and the vanilla drop is cancelled. Anyone currently viewing that
+ * block's GUI is force-closed first -- {@link FurnaceGui#closeForBlock} synchronously
+ * fires {@code InventoryCloseEvent}, so their items are already back in their
+ * inventory (or dropped at their feet) before the block disappears underneath the
+ * (now nonexistent) custom inventory.
+ *
+ * <p><b>Right-click:</b> the event is <em>always</em> cancelled for a registered
+ * machine before anything else runs -- if the vanilla blast furnace GUI were also
+ * allowed to open, a player could move items through it and bypass every guard in
+ * {@link MachineGuiListener}. Only after cancelling do we check permission and open
+ * the real GUI.
+ */
+public final class MachineBlockListener implements Listener {
+
+    private static final String USE_PERMISSION = "electricfurnace.use";
+
+    private final MachineRegistry machines;
+    private final Supplier<EfConfig> configSupplier;
+
+    public MachineBlockListener(MachineRegistry machines, Supplier<EfConfig> configSupplier) {
+        this.machines = Objects.requireNonNull(machines, "machines");
+        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
+    }
+
+    @EventHandler(ignoreCancelled = true)
+    public void onPlace(BlockPlaceEvent event) {
+        ItemStack placed = event.getItemInHand();
+        if (!MaterialContract.isMachine(placed)) {
+            return;
+        }
+        if (!event.getPlayer().hasPermission(USE_PERMISSION)) {
+            return;
+        }
+        machines.register(event.getBlockPlaced());
+    }
+
+    @EventHandler(ignoreCancelled = true)
+    public void onBreak(BlockBreakEvent event) {
+        Block block = event.getBlock();
+        if (!machines.isMachine(block)) {
+            return;
+        }
+
+        // Return any open viewer's items BEFORE the block (and its virtual inventory)
+        // are gone -- never destroy items by breaking the block out from under an open GUI.
+        FurnaceGui.closeForBlock(block);
+
+        machines.unregister(block);
+        event.setDropItems(false);
+        block.getWorld().dropItemNaturally(block.getLocation(), MachineItemFactory.create());
+    }
+
+    @EventHandler(ignoreCancelled = true)
+    public void onInteract(PlayerInteractEvent event) {
+        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) {
+            return;
+        }
+        Block block = event.getClickedBlock();
+        if (block == null || !machines.isMachine(block)) {
+            return;
+        }
+
+        // Cancel unconditionally, before the permission check: the native blast
+        // furnace GUI must never open for a registered machine regardless of whether
+        // this player is allowed to use it.
+        event.setCancelled(true);
+
+        Player player = event.getPlayer();
+        if (!player.hasPermission(USE_PERMISSION)) {
+            player.sendMessage(Component.text("You don't have permission to use this Electric Furnace.")
+                    .color(NamedTextColor.RED));
+            return;
+        }
+
+        boolean powered = block.getBlockPower() > 0;
+        FurnaceGui.open(player, block, configSupplier.get(), powered);
+    }
+}
diff --git a/src/main/java/org/xpfarm/electricfurnace/listener/MachineGuiListener.java b/src/main/java/org/xpfarm/electricfurnace/listener/MachineGuiListener.java
new file mode 100644
index 0000000..b5c3bd8
--- /dev/null
+++ b/src/main/java/org/xpfarm/electricfurnace/listener/MachineGuiListener.java
@@ -0,0 +1,231 @@
+/*
+ * ElectricFurnace - a redstone-powered smelter that recycles metal gear into ingots.
+ * Copyright (C) 2026 Carmelo Santana
+ *
+ * This program is free software: you can redistribute it and/or modify it under the
+ * terms of the GNU Affero General Public License as published by the Free Software
+ * Foundation, either version 3 of the License, or (at your option) any later version.
+ * See the LICENSE file at the project root for the full license text.
+ */
+package org.xpfarm.electricfurnace.listener;
+
+import org.bukkit.Bukkit;
+import org.bukkit.entity.Player;
+import org.bukkit.event.EventHandler;
+import org.bukkit.event.Listener;
+import org.bukkit.event.inventory.InventoryAction;
+import org.bukkit.event.inventory.InventoryClickEvent;
+import org.bukkit.event.inventory.InventoryCloseEvent;
+import org.bukkit.event.inventory.InventoryDragEvent;
+import org.bukkit.inventory.Inventory;
+import org.bukkit.plugin.Plugin;
+import org.xpfarm.electricfurnace.alloy.AlloyRegistry;
+import org.xpfarm.electricfurnace.config.EfConfig;
+import org.xpfarm.electricfurnace.gui.FurnaceGui;
+import org.xpfarm.electricfurnace.gui.GuiLayout;
+
+import java.util.HashSet;
+import java.util.Objects;
+import java.util.Set;
+import java.util.function.Supplier;
+
+/**
+ * Guards every way an item can enter or leave an Electric Furnace GUI slot, and
+ * returns items to the player whenever the GUI closes.
+ *
+ * <p><b>The slot guard.</b> Filler and the status indicator are never interactive --
+ * any click on them is cancelled outright. The output slot may be taken from but
+ * never placed into. This is enforced through {@link #shouldCancel(GuiLayout.SlotRole,
+ * boolean, boolean)}, a pure function over the clicked slot's role and whether the
+ * click places into / takes from that slot -- covering plain clicks, shift-clicks
+ * <em>out</em> of the GUI, number-key hotbar swaps, and double-click collection, all
+ * of which route through {@link InventoryClickEvent#getAction()}.
+ *
+ * <p><b>Shift-click into the GUI is a special case.</b> When a player shift-clicks an
+ * item in their own inventory, Bukkit's {@code MOVE_TO_OTHER_INVENTORY} handling picks
+ * the destination slot in the top inventory internally -- {@link
+ * InventoryClickEvent#getSlot()} reports the <em>source</em> slot in the player's own
+ * inventory, not where the item lands. That destination-picking algorithm will use the
+ * output slot if it is the only empty top slot at the time, which would let an item
+ * slip in through a path this class cannot inspect. Rather than reimplement Bukkit's
+ * fill order to predict the destination, shift-click-in is uniformly disallowed;
+ * players can still drag or plain-click items into the input/fuel slots one at a
+ * time.
+ *
+ * <p><b>Drags</b> ({@link InventoryDragEvent}) can span multiple slots, including
+ * guarded ones, in a single event -- {@link #shouldCancelDrag} cancels the whole drag
+ * if any touched top slot is FILLER, INDICATOR, or OUTPUT.
+ *
+ * <p><b>Never destroy items.</b> On {@link InventoryCloseEvent} -- for any reason,
+ * including disconnect (Paper fires this event on player disconnect too) -- every
+ * input, fuel, and output item is returned to the player via
+ * {@link FurnaceGui#returnAllItems}, dropping at their feet if their inventory is
+ * full.
+ */
+public final class MachineGuiListener implements Listener {
+
+    private final Plugin plugin;
+    private final Supplier<EfConfig> configSupplier;
+    private final Supplier<AlloyRegistry> alloysSupplier;
+
+    /**
+     * @param plugin         owning plugin instance, used only to schedule the
+     *                       post-click processing attempt on the next server tick
+     * @param configSupplier supplies the live, possibly-reloaded configuration
+     * @param alloysSupplier supplies the live, possibly-reloaded alloy registry
+     */
+    public MachineGuiListener(Plugin plugin, Supplier<EfConfig> configSupplier, Supplier<AlloyRegistry> alloysSupplier) {
+        this.plugin = Objects.requireNonNull(plugin, "plugin");
+        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
+        this.alloysSupplier = Objects.requireNonNull(alloysSupplier, "alloysSupplier");
+    }
+
+    @EventHandler(ignoreCancelled = true)
+    public void onClick(InventoryClickEvent event) {
+        Inventory top = event.getView().getTopInventory();
+        if (!FurnaceGui.isFurnaceGui(top)) {
+            return;
+        }
+
+        Inventory clicked = event.getClickedInventory();
+        if (clicked == null) {
+            // Clicked outside any inventory (e.g. dropping the cursor item into the
+            // world) -- nothing of ours is touched.
+            return;
+        }
+
+        boolean clickedTop = clicked.equals(top);
+        boolean cancel = clickedTop
+                ? shouldCancel(GuiLayout.roleOf(event.getSlot()), event.getAction())
+                : event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY;
+
+        if (cancel) {
+            event.setCancelled(true);
+            return;
+        }
+
+        // A plain click confined to the player's own inventory never changes this
+        // GUI's contents -- only schedule a processing attempt when the GUI itself
+        // was the clicked inventory.
+        if (clickedTop && event.getWhoClicked() instanceof Player player) {
+            scheduleProcess(player, top);
+        }
+    }
+
+    @EventHandler(ignoreCancelled = true)
+    public void onDrag(InventoryDragEvent event) {
+        Inventory top = event.getView().getTopInventory();
+        if (!FurnaceGui.isFurnaceGui(top)) {
+            return;
+        }
+
+        Set<GuiLayout.SlotRole> touchedTopRoles = new HashSet<>();
+        for (int rawSlot : event.getRawSlots()) {
+            if (rawSlot < GuiLayout.SIZE) {
+                touchedTopRoles.add(GuiLayout.roleOf(rawSlot));
+            }
+        }
+
+        if (shouldCancelDrag(touchedTopRoles)) {
+            event.setCancelled(true);
+            return;
+        }
+
+        if (event.getWhoClicked() instanceof Player player) {
+            scheduleProcess(player, top);
+        }
+    }
+
+    @EventHandler(ignoreCancelled = true)
+    public void onClose(InventoryCloseEvent event) {
+        Inventory top = event.getView().getTopInventory();
+        if (!FurnaceGui.isFurnaceGui(top)) {
+            return;
+        }
+        if (event.getPlayer() instanceof Player player) {
+            FurnaceGui.returnAllItems(top, player);
+        }
+    }
+
+    /**
+     * Schedules one processing attempt for the next server tick -- a click's item
+     * movement is applied by the server only after this event handler returns, so
+     * reading slot contents synchronously here would see the pre-click state.
+     */
+    private void scheduleProcess(Player player, Inventory top) {
+        FurnaceGui.blockOf(top).ifPresent(block -> Bukkit.getScheduler().runTask(plugin, () -> {
+            if (!player.isOnline() || !FurnaceGui.isFurnaceGui(player.getOpenInventory().getTopInventory())) {
+                return;
+            }
+            boolean powered = block.getBlockPower() > 0;
+            FurnaceGui.tryProcess(top, configSupplier.get(), alloysSupplier.get(), powered);
+        }));
+    }
+
+    // =================================================================================
+    // Pure guard decisions -- no org.bukkit type beyond the plain InventoryAction enum,
+    // which (like Material in MetalClassifierTest) needs no running server to reference.
+    // =================================================================================
+
+    /** What effect a click's {@link InventoryAction} has on the clicked slot itself. */
+    record ClickEffect(boolean isPlace, boolean isTake) {
+    }
+
+    /**
+     * Maps a raw {@link InventoryAction} to whether it places into and/or takes from
+     * the clicked slot. Unknown actions and the newer bundle interactions (whose exact
+     * item-movement direction is not worth risking a mistake on) are conservatively
+     * treated as capable of both, so the guard still protects FILLER/INDICATOR/OUTPUT
+     * regardless of which way they actually move items.
+     */
+    static ClickEffect classify(InventoryAction action) {
+        return switch (action) {
+            case NOTHING, DROP_ALL_CURSOR, DROP_ONE_CURSOR, CLONE_STACK -> new ClickEffect(false, false);
+            case PICKUP_ALL, PICKUP_SOME, PICKUP_HALF, PICKUP_ONE,
+                    DROP_ALL_SLOT, DROP_ONE_SLOT, MOVE_TO_OTHER_INVENTORY, COLLECT_TO_CURSOR ->
+                    new ClickEffect(false, true);
+            case PLACE_ALL, PLACE_SOME, PLACE_ONE -> new ClickEffect(true, false);
+            case SWAP_WITH_CURSOR, HOTBAR_MOVE_AND_READD, HOTBAR_SWAP -> new ClickEffect(true, true);
+            case UNKNOWN, PICKUP_FROM_BUNDLE, PICKUP_ALL_INTO_BUNDLE, PICKUP_SOME_INTO_BUNDLE,
+                    PLACE_FROM_BUNDLE, PLACE_ALL_INTO_BUNDLE, PLACE_SOME_INTO_BUNDLE ->
+                    new ClickEffect(true, true);
+        };
+    }
+
+    /**
+     * The core slot-guard decision: given a slot's role and whether the click places
+     * into / takes from it, should the click be cancelled?
+     *
+     * <ul>
+     *   <li>FILLER, INDICATOR: always cancelled -- non-interactive, no exceptions.</li>
+     *   <li>OUTPUT: cancelled only when the click would place something into it;
+     *       taking is always allowed.</li>
+     *   <li>INPUT, FUEL: never cancelled by this guard.</li>
+     * </ul>
+     */
+    static boolean shouldCancel(GuiLayout.SlotRole role, boolean isPlace, boolean isTake) {
+        return switch (role) {
+            case FILLER, INDICATOR -> true;
+            case OUTPUT -> isPlace;
+            case INPUT, FUEL -> false;
+        };
+    }
+
+    /** Composes {@link #classify} and {@link #shouldCancel(GuiLayout.SlotRole, boolean, boolean)}. */
+    static boolean shouldCancel(GuiLayout.SlotRole role, InventoryAction action) {
+        ClickEffect effect = classify(action);
+        return shouldCancel(role, effect.isPlace(), effect.isTake());
+    }
+
+    /**
+     * The drag guard: a single {@link InventoryDragEvent} can distribute the cursor
+     * item across several slots in one action, including guarded ones. A drag always
+     * places, so the whole event is cancelled if any touched top-inventory slot is
+     * FILLER, INDICATOR, or OUTPUT.
+     */
+    static boolean shouldCancelDrag(Set<GuiLayout.SlotRole> touchedTopRoles) {
+        return touchedTopRoles.contains(GuiLayout.SlotRole.FILLER)
+                || touchedTopRoles.contains(GuiLayout.SlotRole.INDICATOR)
+                || touchedTopRoles.contains(GuiLayout.SlotRole.OUTPUT);
+    }
+}
diff --git a/src/main/java/org/xpfarm/electricfurnace/listener/RedstoneListener.java b/src/main/java/org/xpfarm/electricfurnace/listener/RedstoneListener.java
new file mode 100644
index 0000000..aa5ff3f
--- /dev/null
+++ b/src/main/java/org/xpfarm/electricfurnace/listener/RedstoneListener.java
@@ -0,0 +1,95 @@
+/*
+ * ElectricFurnace - a redstone-powered smelter that recycles metal gear into ingots.
+ * Copyright (C) 2026 Carmelo Santana
+ *
+ * This program is free software: you can redistribute it and/or modify it under the
+ * terms of the GNU Affero General Public License as published by the Free Software
+ * Foundation, either version 3 of the License, or (at your option) any later version.
+ * See the LICENSE file at the project root for the full license text.
+ */
+package org.xpfarm.electricfurnace.listener;
+
+import org.bukkit.Bukkit;
+import org.bukkit.block.Block;
+import org.bukkit.block.BlockFace;
+import org.bukkit.block.data.BlockData;
+import org.bukkit.block.data.type.CopperBulb;
+import org.bukkit.entity.Player;
+import org.bukkit.event.EventHandler;
+import org.bukkit.event.Listener;
+import org.bukkit.event.block.BlockRedstoneEvent;
+import org.bukkit.inventory.Inventory;
+import org.xpfarm.electricfurnace.alloy.AlloyRegistry;
+import org.xpfarm.electricfurnace.config.EfConfig;
+import org.xpfarm.electricfurnace.gui.FurnaceGui;
+import org.xpfarm.electricfurnace.machine.MachineRegistry;
+
+import java.util.Objects;
+import java.util.function.Supplier;
+
+/**
+ * Tracks powered state for registered Electric Furnace blocks and drives an adjacent
+ * {@code COPPER_BULB} indicator, reacting to {@link BlockRedstoneEvent}.
+ *
+ * <p>Deliberately holds no separate "is this block powered" map: {@link
+ * Block#getBlockPower()} already answers that synchronously and correctly at any
+ * time (used by {@code MachineGuiListener} and {@code MachineBlockListener} too), so
+ * there is no cached state that could ever drift out of sync with the world. This
+ * class's job is purely reactive -- when a registered machine's redstone current
+ * changes, update its status bulb and re-attempt processing for anyone currently
+ * viewing its GUI, since the redstone change may be exactly what was blocking it.
+ */
+public final class RedstoneListener implements Listener {
+
+    private static final BlockFace[] ADJACENT_FACES = {
+            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN
+    };
+
+    private final MachineRegistry machines;
+    private final Supplier<EfConfig> configSupplier;
+    private final Supplier<AlloyRegistry> alloysSupplier;
+
+    public RedstoneListener(MachineRegistry machines, Supplier<EfConfig> configSupplier,
+            Supplier<AlloyRegistry> alloysSupplier) {
+        this.machines = Objects.requireNonNull(machines, "machines");
+        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
+        this.alloysSupplier = Objects.requireNonNull(alloysSupplier, "alloysSupplier");
+    }
+
+    @EventHandler
+    public void onRedstoneChange(BlockRedstoneEvent event) {
+        Block block = event.getBlock();
+        if (!machines.isMachine(block)) {
+            return;
+        }
+
+        boolean powered = event.getNewCurrent() > 0;
+        EfConfig config = configSupplier.get();
+
+        if (config.machine().statusBulbEnabled()) {
+            updateAdjacentBulb(block, powered);
+        }
+
+        reattemptProcessingForViewers(block, config, powered);
+    }
+
+    private void updateAdjacentBulb(Block machine, boolean powered) {
+        for (BlockFace face : ADJACENT_FACES) {
+            Block neighbor = machine.getRelative(face);
+            BlockData data = neighbor.getBlockData();
+            if (data instanceof CopperBulb bulb) {
+                bulb.setLit(powered);
+                neighbor.setBlockData(bulb);
+            }
+        }
+    }
+
+    private void reattemptProcessingForViewers(Block block, EfConfig config, boolean powered) {
+        for (Player player : Bukkit.getOnlinePlayers()) {
+            Inventory top = player.getOpenInventory().getTopInventory();
+            FurnaceGui.blockOf(top)
+                    .filter(block::equals)
+                    .ifPresent(b -> FurnaceGui.tryProcess(top, config, alloysSupplier.get(), powered));
+        }
+    }
+}
diff --git a/src/test/java/org/xpfarm/electricfurnace/gui/FurnaceGuiTest.java b/src/test/java/org/xpfarm/electricfurnace/gui/FurnaceGuiTest.java
new file mode 100644
index 0000000..6b70d66
--- /dev/null
+++ b/src/test/java/org/xpfarm/electricfurnace/gui/FurnaceGuiTest.java
@@ -0,0 +1,122 @@
+/*
+ * ElectricFurnace - a redstone-powered smelter that recycles metal gear into ingots.
+ * Copyright (C) 2026 Carmelo Santana
+ *
+ * This program is free software: you can redistribute it and/or modify it under the
+ * terms of the GNU Affero General Public License as published by the Free Software
+ * Foundation, either version 3 of the License, or (at your option) any later version.
+ * See the LICENSE file at the project root for the full license text.
+ */
+package org.xpfarm.electricfurnace.gui;
+
+import org.junit.jupiter.api.Test;
+
+import static org.junit.jupiter.api.Assertions.assertEquals;
+import static org.junit.jupiter.api.Assertions.assertFalse;
+import static org.junit.jupiter.api.Assertions.assertTrue;
+
+/**
+ * Pure unit tests for {@link FurnaceGui}'s processing gate ({@link FurnaceGui#mayRun})
+ * and status-indicator decision ({@link FurnaceGui#indicatorStateOf}). Both are plain
+ * functions over booleans/enums -- no {@code org.bukkit} type, no running server.
+ *
+ * <p>{@link #mayRun_everyCombination} exhaustively covers all
+ * {@code 2 x 2 x 2 x 3 = 24} combinations of (powered, requireSignal, hasFuel,
+ * outputSlotState), following the same exhaustive-boolean-combination pattern as
+ * {@code MetalClassifierTest}'s coverage of {@code MetalClassifier.resolveBranch}.
+ */
+class FurnaceGuiTest {
+
+    // ---- mayRun: the processing gate ------------------------------------------------
+
+    @Test
+    void mayRun_poweredWithFuelAndEmptyOutput_runs() {
+        assertTrue(FurnaceGui.mayRun(true, true, true, FurnaceGui.OutputSlotState.EMPTY));
+    }
+
+    @Test
+    void mayRun_poweredWithFuelAndMatchingOutput_runs() {
+        assertTrue(FurnaceGui.mayRun(true, true, true, FurnaceGui.OutputSlotState.SAME_ITEM));
+    }
+
+    @Test
+    void mayRun_unpoweredWhenSignalRequired_doesNotRun() {
+        assertFalse(FurnaceGui.mayRun(false, true, true, FurnaceGui.OutputSlotState.EMPTY));
+    }
+
+    @Test
+    void mayRun_unpoweredWhenSignalNotRequired_stillRuns() {
+        assertTrue(FurnaceGui.mayRun(false, false, true, FurnaceGui.OutputSlotState.EMPTY));
+    }
+
+    @Test
+    void mayRun_noFuel_doesNotRun() {
+        assertFalse(FurnaceGui.mayRun(true, true, false, FurnaceGui.OutputSlotState.EMPTY));
+    }
+
+    @Test
+    void mayRun_outputHoldsDifferentItem_doesNotRun_evenWhenPoweredAndFueled() {
+        assertFalse(FurnaceGui.mayRun(true, true, true, FurnaceGui.OutputSlotState.DIFFERENT_ITEM));
+    }
+
+    @Test
+    void mayRun_everyCombination() {
+        for (boolean powered : new boolean[] {true, false}) {
+            for (boolean requireSignal : new boolean[] {true, false}) {
+                for (boolean hasFuel : new boolean[] {true, false}) {
+                    for (FurnaceGui.OutputSlotState state : FurnaceGui.OutputSlotState.values()) {
+                        boolean expected = (!requireSignal || powered) && hasFuel
+                                && state != FurnaceGui.OutputSlotState.DIFFERENT_ITEM;
+                        assertEquals(expected, FurnaceGui.mayRun(powered, requireSignal, hasFuel, state),
+                                () -> "powered=" + powered + " requireSignal=" + requireSignal
+                                        + " hasFuel=" + hasFuel + " state=" + state);
+                    }
+                }
+            }
+        }
+    }
+
+    // ---- indicatorStateOf ------------------------------------------------------------
+
+    @Test
+    void indicatorState_unpoweredWhenSignalRequired_isNoSignal() {
+        assertEquals(FurnaceGui.IndicatorState.NO_SIGNAL, FurnaceGui.indicatorStateOf(false, true, true));
+    }
+
+    @Test
+    void indicatorState_unpoweredButSignalNotRequired_andHasFuel_isRunning() {
+        assertEquals(FurnaceGui.IndicatorState.RUNNING, FurnaceGui.indicatorStateOf(false, false, true));
+    }
+
+    @Test
+    void indicatorState_poweredButNoFuel_isNoFuel() {
+        assertEquals(FurnaceGui.IndicatorState.NO_FUEL, FurnaceGui.indicatorStateOf(true, true, false));
+    }
+
+    @Test
+    void indicatorState_poweredWithFuel_isRunning() {
+        assertEquals(FurnaceGui.IndicatorState.RUNNING, FurnaceGui.indicatorStateOf(true, true, true));
+    }
+
+    @Test
+    void indicatorState_noSignalTakesPrecedenceOverNoFuel() {
+        // Unpowered AND no fuel: signal is the more fundamental blocker.
+        assertEquals(FurnaceGui.IndicatorState.NO_SIGNAL, FurnaceGui.indicatorStateOf(false, true, false));
+    }
+
+    @Test
+    void indicatorState_everyCombination() {
+        for (boolean powered : new boolean[] {true, false}) {
+            for (boolean requireSignal : new boolean[] {true, false}) {
+                for (boolean hasFuel : new boolean[] {true, false}) {
+                    boolean effectivePowered = !requireSignal || powered;
+                    FurnaceGui.IndicatorState expected = !effectivePowered
+                            ? FurnaceGui.IndicatorState.NO_SIGNAL
+                            : !hasFuel ? FurnaceGui.IndicatorState.NO_FUEL : FurnaceGui.IndicatorState.RUNNING;
+                    assertEquals(expected, FurnaceGui.indicatorStateOf(powered, requireSignal, hasFuel),
+                            () -> "powered=" + powered + " requireSignal=" + requireSignal + " hasFuel=" + hasFuel);
+                }
+            }
+        }
+    }
+}
diff --git a/src/test/java/org/xpfarm/electricfurnace/gui/GuiLayoutTest.java b/src/test/java/org/xpfarm/electricfurnace/gui/GuiLayoutTest.java
new file mode 100644
index 0000000..27d3b15
--- /dev/null
+++ b/src/test/java/org/xpfarm/electricfurnace/gui/GuiLayoutTest.java
@@ -0,0 +1,108 @@
+/*
+ * ElectricFurnace - a redstone-powered smelter that recycles metal gear into ingots.
+ * Copyright (C) 2026 Carmelo Santana
+ *
+ * This program is free software: you can redistribute it and/or modify it under the
+ * terms of the GNU Affero General Public License as published by the Free Software
+ * Foundation, either version 3 of the License, or (at your option) any later version.
+ * See the LICENSE file at the project root for the full license text.
+ */
+package org.xpfarm.electricfurnace.gui;
+
+import org.junit.jupiter.api.Test;
+import org.junit.jupiter.params.ParameterizedTest;
+import org.junit.jupiter.params.provider.ValueSource;
+
+import java.util.HashSet;
+import java.util.Set;
+
+import static org.junit.jupiter.api.Assertions.assertEquals;
+import static org.junit.jupiter.api.Assertions.assertThrows;
+
+/**
+ * Pure unit tests for {@link GuiLayout}'s slot index math and slot-role
+ * classification. Deliberately imports nothing from {@code org.bukkit} and requires
+ * no running server -- every one of the 27 GUI slots is asserted exhaustively so a
+ * future edit can never silently misclassify a slot.
+ */
+class GuiLayoutTest {
+
+    @Test
+    void size_isTwentySevenSlots() {
+        assertEquals(27, GuiLayout.SIZE);
+    }
+
+    @Test
+    void fuelSlot_isSlotThree() {
+        assertEquals(3, GuiLayout.FUEL_SLOT);
+        assertEquals(GuiLayout.SlotRole.FUEL, GuiLayout.roleOf(3));
+    }
+
+    @Test
+    void inputSlots_areTenThroughFourteen() {
+        assertEquals(Set.of(10, 11, 12, 13, 14), GuiLayout.INPUT_SLOTS);
+        for (int slot : GuiLayout.INPUT_SLOTS) {
+            assertEquals(GuiLayout.SlotRole.INPUT, GuiLayout.roleOf(slot));
+        }
+    }
+
+    @Test
+    void outputSlot_isSlotSixteen() {
+        assertEquals(16, GuiLayout.OUTPUT_SLOT);
+        assertEquals(GuiLayout.SlotRole.OUTPUT, GuiLayout.roleOf(16));
+    }
+
+    @Test
+    void indicatorSlot_isSlotTwentyTwo() {
+        assertEquals(22, GuiLayout.INDICATOR_SLOT);
+        assertEquals(GuiLayout.SlotRole.INDICATOR, GuiLayout.roleOf(22));
+    }
+
+    /**
+     * Exhaustive: every one of the 27 slots must classify to exactly one role, and
+     * every slot not named as INPUT/FUEL/OUTPUT/INDICATOR must be FILLER.
+     */
+    @Test
+    void everySlot_classifiesToExactlyOneRole_andUnnamedSlotsAreFiller() {
+        Set<Integer> named = new HashSet<>(GuiLayout.INPUT_SLOTS);
+        named.add(GuiLayout.FUEL_SLOT);
+        named.add(GuiLayout.OUTPUT_SLOT);
+        named.add(GuiLayout.INDICATOR_SLOT);
+
+        for (int slot = 0; slot < GuiLayout.SIZE; slot++) {
+            GuiLayout.SlotRole role = GuiLayout.roleOf(slot);
+            if (named.contains(slot)) {
+                assertEquals(namedRoleOf(slot), role, "slot " + slot);
+            } else {
+                assertEquals(GuiLayout.SlotRole.FILLER, role, "slot " + slot + " should be filler");
+            }
+        }
+    }
+
+    private GuiLayout.SlotRole namedRoleOf(int slot) {
+        if (slot == GuiLayout.FUEL_SLOT) {
+            return GuiLayout.SlotRole.FUEL;
+        }
+        if (GuiLayout.INPUT_SLOTS.contains(slot)) {
+            return GuiLayout.SlotRole.INPUT;
+        }
+        if (slot == GuiLayout.OUTPUT_SLOT) {
+            return GuiLayout.SlotRole.OUTPUT;
+        }
+        if (slot == GuiLayout.INDICATOR_SLOT) {
+            return GuiLayout.SlotRole.INDICATOR;
+        }
+        throw new IllegalStateException("not a named slot: " + slot);
+    }
+
+    @ParameterizedTest
+    @ValueSource(ints = {-1, -100, 27, 28, 1000})
+    void roleOf_outOfRangeSlot_throws(int slot) {
+        assertThrows(IllegalArgumentException.class, () -> GuiLayout.roleOf(slot));
+    }
+
+    @Test
+    void title_isElectricFurnace() {
+        assertEquals("Electric Furnace", GuiLayout.TITLE_TEXT);
+    }
+}
diff --git a/src/test/java/org/xpfarm/electricfurnace/listener/MachineGuiListenerTest.java b/src/test/java/org/xpfarm/electricfurnace/listener/MachineGuiListenerTest.java
new file mode 100644
index 0000000..3dd16c5
--- /dev/null
+++ b/src/test/java/org/xpfarm/electricfurnace/listener/MachineGuiListenerTest.java
@@ -0,0 +1,279 @@
+/*
+ * ElectricFurnace - a redstone-powered smelter that recycles metal gear into ingots.
+ * Copyright (C) 2026 Carmelo Santana
+ *
+ * This program is free software: you can redistribute it and/or modify it under the
+ * terms of the GNU Affero General Public License as published by the Free Software
+ * Foundation, either version 3 of the License, or (at your option) any later version.
+ * See the LICENSE file at the project root for the full license text.
+ */
+package org.xpfarm.electricfurnace.listener;
+
+import org.bukkit.event.inventory.InventoryAction;
+import org.junit.jupiter.api.Test;
+import org.xpfarm.electricfurnace.gui.GuiLayout;
+
+import java.util.EnumSet;
+import java.util.HashSet;
+import java.util.Set;
+
+import static org.junit.jupiter.api.Assertions.assertEquals;
+import static org.junit.jupiter.api.Assertions.assertFalse;
+import static org.junit.jupiter.api.Assertions.assertTrue;
+
+/**
+ * Pure unit tests for {@link MachineGuiListener}'s slot-guard decision.
+ *
+ * <p>{@link InventoryAction} is a plain Bukkit enum -- like {@code Material} in
+ * {@code MetalClassifierTest} -- referencing its constants requires no running
+ * server, which is exactly what lets {@link #classify_everyActionIsHandled} and
+ * {@link #shouldCancel_everyRoleActionCombination} exhaustively cover every
+ * combination without a live server, following the same
+ * {@code MetalClassifier.resolveBranch} exhaustive-combination pattern.
+ *
+ * <p>The most important behavior under test: any click that would PLACE an item
+ * into the output slot must be cancelled, and any click at all on FILLER or
+ * INDICATOR must be cancelled -- regardless of click type (plain click, shift-click,
+ * hotbar swap, double-click collect).
+ */
+class MachineGuiListenerTest {
+
+    // ---- classify(InventoryAction): the click -> (isPlace, isTake) mapping ----------
+
+    @Test
+    void classify_plainPickup_isTakeOnly() {
+        for (InventoryAction action : new InventoryAction[] {
+                InventoryAction.PICKUP_ALL, InventoryAction.PICKUP_SOME,
+                InventoryAction.PICKUP_HALF, InventoryAction.PICKUP_ONE}) {
+            MachineGuiListener.ClickEffect effect = MachineGuiListener.classify(action);
+            assertFalse(effect.isPlace(), action + " should not place");
+            assertTrue(effect.isTake(), action + " should take");
+        }
+    }
+
+    @Test
+    void classify_plainPlace_isPlaceOnly() {
+        for (InventoryAction action : new InventoryAction[] {
+                InventoryAction.PLACE_ALL, InventoryAction.PLACE_SOME, InventoryAction.PLACE_ONE}) {
+            MachineGuiListener.ClickEffect effect = MachineGuiListener.classify(action);
+            assertTrue(effect.isPlace(), action + " should place");
+            assertFalse(effect.isTake(), action + " should not take");
+        }
+    }
+
+    @Test
+    void classify_swapAndHotbarActions_areBothPlaceAndTake() {
+        for (InventoryAction action : new InventoryAction[] {
+                InventoryAction.SWAP_WITH_CURSOR, InventoryAction.HOTBAR_SWAP,
+                InventoryAction.HOTBAR_MOVE_AND_READD}) {
+            MachineGuiListener.ClickEffect effect = MachineGuiListener.classify(action);
+            assertTrue(effect.isPlace(), action + " should place");
+            assertTrue(effect.isTake(), action + " should take");
+        }
+    }
+
+    @Test
+    void classify_shiftClickOut_isTakeOnly() {
+        MachineGuiListener.ClickEffect effect = MachineGuiListener.classify(InventoryAction.MOVE_TO_OTHER_INVENTORY);
+        assertFalse(effect.isPlace());
+        assertTrue(effect.isTake());
+    }
+
+    @Test
+    void classify_doubleClickCollect_isTakeOnly() {
+        MachineGuiListener.ClickEffect effect = MachineGuiListener.classify(InventoryAction.COLLECT_TO_CURSOR);
+        assertFalse(effect.isPlace());
+        assertTrue(effect.isTake());
+    }
+
+    @Test
+    void classify_dropFromSlot_isTakeOnly() {
+        for (InventoryAction action : new InventoryAction[] {InventoryAction.DROP_ALL_SLOT, InventoryAction.DROP_ONE_SLOT}) {
+            MachineGuiListener.ClickEffect effect = MachineGuiListener.classify(action);
+            assertFalse(effect.isPlace());
+            assertTrue(effect.isTake());
+        }
+    }
+
+    @Test
+    void classify_nothingAndCursorDrops_touchNoSlot() {
+        for (InventoryAction action : new InventoryAction[] {
+                InventoryAction.NOTHING, InventoryAction.DROP_ALL_CURSOR,
+                InventoryAction.DROP_ONE_CURSOR, InventoryAction.CLONE_STACK}) {
+            MachineGuiListener.ClickEffect effect = MachineGuiListener.classify(action);
+            assertFalse(effect.isPlace(), action + " should not place");
+            assertFalse(effect.isTake(), action + " should not take");
+        }
+    }
+
+    @Test
+    void classify_unknownAndBundleActions_areConservativelyBoth() {
+        // Fail-safe: an action whose exact item-movement direction is not worth
+        // getting wrong is treated as capable of both placing and taking, so the
+        // guard still protects FILLER/INDICATOR/OUTPUT regardless.
+        for (InventoryAction action : new InventoryAction[] {
+                InventoryAction.UNKNOWN, InventoryAction.PICKUP_FROM_BUNDLE,
+                InventoryAction.PICKUP_ALL_INTO_BUNDLE, InventoryAction.PICKUP_SOME_INTO_BUNDLE,
+                InventoryAction.PLACE_FROM_BUNDLE, InventoryAction.PLACE_ALL_INTO_BUNDLE,
+                InventoryAction.PLACE_SOME_INTO_BUNDLE}) {
+            MachineGuiListener.ClickEffect effect = MachineGuiListener.classify(action);
+            assertTrue(effect.isPlace(), action + " should conservatively place");
+            assertTrue(effect.isTake(), action + " should conservatively take");
+        }
+    }
+
+    @Test
+    void classify_everyActionIsHandled() {
+        // Exhaustive: classify() must never throw for any InventoryAction constant,
+        // present or future-proofed by the exhaustive switch's compile-time check.
+        for (InventoryAction action : InventoryAction.values()) {
+            MachineGuiListener.ClickEffect effect = MachineGuiListener.classify(action);
+            assertEquals(effect, effect); // merely proves classify() didn't throw
+        }
+    }
+
+    // ---- shouldCancel(role, isPlace, isTake): the core guard decision ---------------
+
+    @Test
+    void filler_alwaysCancelled_regardlessOfPlaceOrTake() {
+        assertTrue(MachineGuiListener.shouldCancel(GuiLayout.SlotRole.FILLER, true, false));
+        assertTrue(MachineGuiListener.shouldCancel(GuiLayout.SlotRole.FILLER, false, true));
+        assertTrue(MachineGuiListener.shouldCancel(GuiLayout.SlotRole.FILLER, false, false));
+        assertTrue(MachineGuiListener.shouldCancel(GuiLayout.SlotRole.FILLER, true, true));
+    }
+
+    @Test
+    void indicator_alwaysCancelled_regardlessOfPlaceOrTake() {
+        assertTrue(MachineGuiListener.shouldCancel(GuiLayout.SlotRole.INDICATOR, true, false));
+        assertTrue(MachineGuiListener.shouldCancel(GuiLayout.SlotRole.INDICATOR, false, true));
+        assertTrue(MachineGuiListener.shouldCancel(GuiLayout.SlotRole.INDICATOR, false, false));
+        assertTrue(MachineGuiListener.shouldCancel(GuiLayout.SlotRole.INDICATOR, true, true));
+    }
+
+    @Test
+    void output_cancelledOnlyWhenPlacing() {
+        assertTrue(MachineGuiListener.shouldCancel(GuiLayout.SlotRole.OUTPUT, true, false),
+                "placing into output must be cancelled");
+        assertTrue(MachineGuiListener.shouldCancel(GuiLayout.SlotRole.OUTPUT, true, true),
+                "a swap that places into output must be cancelled even though it also takes");
+        assertFalse(MachineGuiListener.shouldCancel(GuiLayout.SlotRole.OUTPUT, false, true),
+                "taking from output must be allowed");
+        assertFalse(MachineGuiListener.shouldCancel(GuiLayout.SlotRole.OUTPUT, false, false));
+    }
+
+    @Test
+    void input_neverCancelledByThisGuard() {
+        assertFalse(MachineGuiListener.shouldCancel(GuiLayout.SlotRole.INPUT, true, false));
+        assertFalse(MachineGuiListener.shouldCancel(GuiLayout.SlotRole.INPUT, false, true));
+        assertFalse(MachineGuiListener.shouldCancel(GuiLayout.SlotRole.INPUT, false, false));
+        assertFalse(MachineGuiListener.shouldCancel(GuiLayout.SlotRole.INPUT, true, true));
+    }
+
+    @Test
+    void fuel_neverCancelledByThisGuard() {
+        assertFalse(MachineGuiListener.shouldCancel(GuiLayout.SlotRole.FUEL, true, false));
+        assertFalse(MachineGuiListener.shouldCancel(GuiLayout.SlotRole.FUEL, false, true));
+        assertFalse(MachineGuiListener.shouldCancel(GuiLayout.SlotRole.FUEL, false, false));
+        assertFalse(MachineGuiListener.shouldCancel(GuiLayout.SlotRole.FUEL, true, true));
+    }
+
+    @Test
+    void shouldCancel_everyRoleAndPlaceTakeCombination() {
+        for (GuiLayout.SlotRole role : GuiLayout.SlotRole.values()) {
+            for (boolean isPlace : new boolean[] {true, false}) {
+                for (boolean isTake : new boolean[] {true, false}) {
+                    boolean expected = switch (role) {
+                        case FILLER, INDICATOR -> true;
+                        case OUTPUT -> isPlace;
+                        case INPUT, FUEL -> false;
+                    };
+                    assertEquals(expected, MachineGuiListener.shouldCancel(role, isPlace, isTake),
+                            () -> "role=" + role + " isPlace=" + isPlace + " isTake=" + isTake);
+                }
+            }
+        }
+    }
+
+    // ---- shouldCancel(role, action): the composed, action-level guard --------------
+
+    @Test
+    void shouldCancel_everyRoleActionCombination() {
+        for (GuiLayout.SlotRole role : GuiLayout.SlotRole.values()) {
+            for (InventoryAction action : InventoryAction.values()) {
+                MachineGuiListener.ClickEffect effect = MachineGuiListener.classify(action);
+                boolean expected = MachineGuiListener.shouldCancel(role, effect.isPlace(), effect.isTake());
+                assertEquals(expected, MachineGuiListener.shouldCancel(role, action),
+                        () -> "role=" + role + " action=" + action);
+            }
+        }
+    }
+
+    @Test
+    void placingIntoOutput_viaPlaceAll_isCancelled() {
+        assertTrue(MachineGuiListener.shouldCancel(GuiLayout.SlotRole.OUTPUT, InventoryAction.PLACE_ALL));
+    }
+
+    @Test
+    void takingFromOutput_viaShiftClick_isAllowed() {
+        assertFalse(MachineGuiListener.shouldCancel(GuiLayout.SlotRole.OUTPUT, InventoryAction.MOVE_TO_OTHER_INVENTORY));
+    }
+
+    @Test
+    void hotbarSwapIntoOutput_isCancelled() {
+        assertTrue(MachineGuiListener.shouldCancel(GuiLayout.SlotRole.OUTPUT, InventoryAction.HOTBAR_SWAP));
+    }
+
+    @Test
+    void clickingFiller_withAnyAction_isCancelled() {
+        for (InventoryAction action : InventoryAction.values()) {
+            assertTrue(MachineGuiListener.shouldCancel(GuiLayout.SlotRole.FILLER, action),
+                    "filler + " + action + " should always cancel");
+        }
+    }
+
+    // ---- shouldCancelDrag(Set<SlotRole>): drag spanning multiple slots --------------
+
+    @Test
+    void drag_touchingOnlyInputAndFuel_isAllowed() {
+        assertFalse(MachineGuiListener.shouldCancelDrag(Set.of(GuiLayout.SlotRole.INPUT, GuiLayout.SlotRole.FUEL)));
+    }
+
+    @Test
+    void drag_touchingOutput_isCancelled() {
+        assertTrue(MachineGuiListener.shouldCancelDrag(Set.of(GuiLayout.SlotRole.INPUT, GuiLayout.SlotRole.OUTPUT)));
+    }
+
+    @Test
+    void drag_touchingFiller_isCancelled() {
+        assertTrue(MachineGuiListener.shouldCancelDrag(Set.of(GuiLayout.SlotRole.FILLER)));
+    }
+
+    @Test
+    void drag_touchingIndicator_isCancelled() {
+        assertTrue(MachineGuiListener.shouldCancelDrag(Set.of(GuiLayout.SlotRole.INDICATOR)));
+    }
+
+    @Test
+    void drag_touchingNoTopSlots_isAllowed() {
+        assertFalse(MachineGuiListener.shouldCancelDrag(Set.of()));
+    }
+
+    @Test
+    void drag_everyNonEmptySubsetOfRoles_matchesContainsAnyGuardedRole() {
+        Set<GuiLayout.SlotRole> allRoles = EnumSet.allOf(GuiLayout.SlotRole.class);
+        // Iterate every subset of the 5 roles (2^5 = 32) via a bitmask.
+        GuiLayout.SlotRole[] roles = allRoles.toArray(new GuiLayout.SlotRole[0]);
+        for (int mask = 0; mask < (1 << roles.length); mask++) {
+            Set<GuiLayout.SlotRole> subset = new HashSet<>();
+            for (int i = 0; i < roles.length; i++) {
+                if ((mask & (1 << i)) != 0) {
+                    subset.add(roles[i]);
+                }
+            }
+            boolean expected = subset.contains(GuiLayout.SlotRole.FILLER)
+                    || subset.contains(GuiLayout.SlotRole.INDICATOR)
+                    || subset.contains(GuiLayout.SlotRole.OUTPUT);
+            assertEquals(expected, MachineGuiListener.shouldCancelDrag(subset), "subset=" + subset);
+        }
+    }
+}
```
