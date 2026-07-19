# Task 5 — GUI and machine listeners — Report

## What was implemented

Followed TDD: wrote `GuiLayoutTest`, `FurnaceGuiTest`, and `MachineGuiListenerTest`
first (each failed to compile with no implementation present), then implemented
`GuiLayout`, `FurnaceGui`, `MachineGuiListener`, `MachineBlockListener`, and
`RedstoneListener` against them.

- **`GuiLayout`** — the fixed 27-slot layout as named constants: `FUEL_SLOT = 3`,
  `INPUT_SLOTS = {10,11,12,13,14}`, `OUTPUT_SLOT = 16`, `INDICATOR_SLOT = 22`, every
  other slot FILLER. `roleOf(int slot)` is a pure static function (no `org.bukkit`
  type) classifying any of the 27 slots into `SlotRole {INPUT, FUEL, OUTPUT,
  INDICATOR, FILLER}`, throwing `IllegalArgumentException` outside `[0, 27)`.

- **`FurnaceGui`** — owns the custom `Inventory` (a `Holder implements
  InventoryHolder` carries the owning `Block`), renders filler panes and the status
  indicator, runs the recycler resolution, and is the single place that returns items
  to players. Pure logic:
  - `indicatorStateOf(powered, requireSignal, hasFuel)` → `RUNNING` / `NO_SIGNAL` /
    `NO_FUEL`, materialized as `REDSTONE_TORCH` / `LEVER` / `REDSTONE` per the plan.
  - `mayRun(powered, requireSignal, hasFuel, OutputSlotState)` → the processing gate.
    `OutputSlotState` is `EMPTY` / `SAME_ITEM` / `DIFFERENT_ITEM`; `DIFFERENT_ITEM`
    also covers "same item but merging would exceed the max stack size" (Bukkit-facing
    `classifyOutputSlot`, untestable without a server, computes which state applies).
  - `tryProcess(...)` — the Bukkit-facing orchestration: collects `RecycleInput`s from
    the 5 input slots via `MetalClassifier.classify`, calls `RecycleResolver.resolve`
    (never reimplemented), and on a successful gate check consumes exactly
    `fuelPerOperation` redstone and **exactly one item from each occupied input slot**
    (not the whole slot — a slot holding a stack of 5 keeps 4 for the next operation).
  - `returnAllItems(inventory, player)` — drains input, fuel, **and** output back to
    the player, giving via `player.getInventory().addItem` and dropping any overflow
    at the player's feet.
  - `closeForBlock(Block)` / `closeAll()` — force-close (`player.closeInventory()`)
    anyone viewing a given machine's GUI, or every open Electric Furnace GUI. Closing
    synchronously fires `InventoryCloseEvent`, so `MachineGuiListener#onClose` (below)
    returns the items before these methods return.

- **`MachineGuiListener`** — the slot guard and the item-safety net.
  - `classify(InventoryAction)` maps every one of Paper's 25 `InventoryAction`
    constants (including the 6 newer bundle-interaction actions) to `(isPlace,
    isTake)`. Ambiguous/future actions (`UNKNOWN`, all 6 bundle actions) are
    conservatively mapped to both `true` — a documented fail-safe rather than a guess.
  - `shouldCancel(SlotRole, boolean isPlace, boolean isTake)` — the core guard:
    FILLER/INDICATOR always cancel; OUTPUT cancels only when placing; INPUT/FUEL
    never cancel here.
  - `onClick` handles plain clicks, shift-click-out, hotbar swaps, and double-click
    collection uniformly through the above (all reachable via `event.getAction()`
    when the clicked inventory is the GUI itself).
  - **Shift-click into the GUI** (`MOVE_TO_OTHER_INVENTORY` where the clicked
    inventory is the player's own): Bukkit picks the destination top slot internally;
    `event.getSlot()` reports the source slot in the bottom inventory, not the
    destination, so the per-slot-role guard cannot be applied. This is uniformly
    cancelled — see "Deviations" below.
  - `onDrag` collects every touched raw slot `< GuiLayout.SIZE` into a `Set<SlotRole>`
    and cancels the whole drag if any is FILLER/INDICATOR/OUTPUT
    (`shouldCancelDrag`), since one drag can span several slots at once.
  - `onClose` calls `FurnaceGui.returnAllItems` unconditionally — for any close
    reason, including `DISCONNECT` (Paper fires `InventoryCloseEvent` on player
    disconnect too, so no special-case code was needed for that scenario).
  - A non-cancelled click/drag schedules one `FurnaceGui.tryProcess` call via
    `Bukkit.getScheduler().runTask` for the next tick, since the click's own item
    movement is applied by the server only after the event handler returns.

- **`MachineBlockListener`** — `BlockPlaceEvent` registers a block carrying
  `electricfurnace:machine`, gated on `electricfurnace.use`. `BlockBreakEvent`
  force-closes any open GUI at that block first (`FurnaceGui.closeForBlock`, so items
  are safely back with the player before anything is removed), then unregisters,
  cancels the vanilla drop (`setDropItems(false)`), and drops the machine item.
  `PlayerInteractEvent` on `RIGHT_CLICK_BLOCK`/main-hand cancels the event
  unconditionally for a registered machine (so the vanilla blast furnace GUI never
  opens even for a player lacking permission), then opens `FurnaceGui` if the player
  has `electricfurnace.use`, else sends a denial message.

- **`RedstoneListener`** — on `BlockRedstoneEvent` for a registered machine, drives
  every adjacent `COPPER_BULB` (`CopperBulb#setLit` + `Block#setBlockData`) when
  `machine.status-bulb.enabled`, and re-attempts `FurnaceGui.tryProcess` for anyone
  currently viewing that block's GUI (the redstone change may be exactly what was
  blocking it). Holds no separate "powered" map — `Block#getBlockPower()` is queried
  live wherever powered state is needed (here, in `MachineGuiListener`, and in
  `MachineBlockListener`), so there is nothing that can drift out of sync with the
  world.

## Files

- `src/main/java/org/xpfarm/electricfurnace/gui/GuiLayout.java` (new)
- `src/main/java/org/xpfarm/electricfurnace/gui/FurnaceGui.java` (new)
- `src/main/java/org/xpfarm/electricfurnace/listener/MachineGuiListener.java` (new)
- `src/main/java/org/xpfarm/electricfurnace/listener/MachineBlockListener.java` (new)
- `src/main/java/org/xpfarm/electricfurnace/listener/RedstoneListener.java` (new)
- `src/test/java/org/xpfarm/electricfurnace/gui/GuiLayoutTest.java` (new)
- `src/test/java/org/xpfarm/electricfurnace/gui/FurnaceGuiTest.java` (new)
- `src/test/java/org/xpfarm/electricfurnace/listener/MachineGuiListenerTest.java` (new)

No files outside this list were created or modified. `MachineBlockListener` and
`RedstoneListener` have no dedicated test file — see "Concerns."

## Test coverage (51 new tests)

- **`GuiLayoutTest`** (12) — exact constants (`SIZE`, `FUEL_SLOT`, `INPUT_SLOTS`,
  `OUTPUT_SLOT`, `INDICATOR_SLOT`, `TITLE_TEXT`); exhaustive loop over all 27 slots
  asserting the correct role for every one; parameterized out-of-range slots
  (`-100, -1, 27, 28, 1000`) each asserted to throw.
- **`FurnaceGuiTest`** (13) — `mayRun` exhaustive over all `2×2×2×3 = 24`
  (powered, requireSignal, hasFuel, OutputSlotState) combinations plus named edge
  cases (unpowered-but-signal-not-required still runs; different-output blocks even
  when powered+fueled). `indicatorStateOf` exhaustive over all 8
  (powered, requireSignal, hasFuel) combinations plus a precedence test (no-signal
  beats no-fuel).
- **`MachineGuiListenerTest`** (26) — `classify` covers every `InventoryAction`
  constant by category (plain pickup/place, swap/hotbar, shift-click,
  double-click-collect, drop-from-slot, no-op, and the conservative bundle/unknown
  bucket) plus one loop asserting `classify` never throws for any constant.
  `shouldCancel(role, isPlace, isTake)` exhaustive over all `5×2×2 = 20` combinations.
  `shouldCancel(role, action)` exhaustive over all `5×25 = 125` role×action
  combinations, cross-checked against the composed `classify`+`shouldCancel(3-arg)`
  result. `shouldCancelDrag` exhaustive over all `2^5 = 32` subsets of the 5 roles.

## Build verification

Command:

```
mvn --batch-mode --no-transfer-progress clean verify
```

Result: **BUILD SUCCESS**. 150 tests total (99 pre-existing + 51 new), 0 failures,
0 errors, 0 skipped:

```
[INFO] Running org.xpfarm.electricfurnace.alloy.AlloyRegistryTest
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running org.xpfarm.electricfurnace.recycle.RecycleResolverTest
[INFO] Tests run: 20, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running org.xpfarm.electricfurnace.machine.MachineKeyTest
[INFO] Tests run: 32, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running org.xpfarm.electricfurnace.listener.MachineGuiListenerTest
[INFO] Tests run: 26, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running org.xpfarm.electricfurnace.item.MetalClassifierTest
[INFO] Tests run: 20, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running org.xpfarm.electricfurnace.config.ConfigValidatorTest
[INFO] Tests run: 20, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running org.xpfarm.electricfurnace.gui.FurnaceGuiTest
[INFO] Tests run: 13, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running org.xpfarm.electricfurnace.gui.GuiLayoutTest
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0

Tests run: 150, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Shaded JAR built successfully at `target/electric-furnace-0.1.0.jar`.

Environment: sourced `~/.sdkman/bin/sdkman-init.sh` for Java 25.0.3-tem / Maven
3.9.16, since `java`/`mvn` are not on PATH by default.

One build-time hazard found and fixed during TDD: an early draft of `FurnaceGui` built
its filler-pane `ItemStack` in a `static final` field, initialized at class-load time.
Referencing `FurnaceGui.mayRun(...)` from a plain JUnit test then triggered
`<clinit>`, which tried to construct an `ItemStack` outside a running server and threw
`ExceptionInInitializerError`. Fixed by building the filler item on demand inside
`open()` instead of caching it statically — this is exactly the kind of "Bukkit type
touched anywhere near a pure function" trap the TESTABILITY section warns about, just
one level removed (via a static initializer rather than a method parameter).

## Deviations from the plan, and why

1. **No cross-session persistence for GUI contents.** The plan only specifies
   returning "all input and fuel items" on close; it does not describe how/whether
   slot contents persist across GUI opens (Task 4's `MachineRegistry` persists only
   machine *locations*). This implementation deliberately makes every `open()` call
   create a brand-new, empty backing `Inventory`, and `returnAllItems` drains
   input+fuel **and output** on every close (not just input+fuel as literally
   specified). Rationale: the backing inventory lives only in the JVM heap (a Bukkit
   `Inventory`/`InventoryHolder`, not a chunk/block PDC) — if any item were ever left
   sitting in it while no player views it, a server restart would silently erase it,
   which is exactly the item-loss failure this task calls the highest-stakes
   requirement. Draining everything on every close eliminates that risk entirely at
   the cost of one behavior beyond the letter of the spec (a player who closes without
   collecting their output gets it handed back immediately rather than finding it
   still sitting there next time they open the GUI) — strictly safer, never worse.

2. **Shift-click into the GUI is always cancelled**, not selectively guarded per
   destination slot. Bukkit's `MOVE_TO_OTHER_INVENTORY` handling for a shift-click
   *from* the player's own inventory picks the destination top slot using its own
   internal fill-order algorithm; `InventoryClickEvent.getSlot()` at that point is the
   *source* slot in the bottom inventory, not the destination, so
   `GuiLayout.roleOf(event.getSlot())` cannot be used to guard it directly. That fill
   algorithm will use the output slot if it is the only empty top slot at the time —
   exactly the placement the plan says must never happen. Rather than reimplement
   Bukkit's fill order to predict the destination (fragile, and easy to get subtly
   wrong across Paper versions), shift-click-in is uniformly disallowed. Players can
   still plain-click or drag items into the input/fuel slots one at a time; shift-click
   *out* of the GUI (taking items) is unaffected and still works normally.

3. **Input slots are consumed one item at a time per successful operation**, not
   cleared wholesale. Not specified either way by the plan, but clearing a slot
   containing a stack (e.g. a player queues 5 iron ingots in one slot) would silently
   destroy the 4 leftover items — a direct violation of the "never destroy items"
   requirement. Each operation now consumes exactly one item from each currently
   occupied input slot, leaving any surplus for the next operation.

4. **`RedstoneListener` tracks no explicit "powered" state map.** The plan's wording
   ("track powered state") is satisfied by querying `Block#getBlockPower()` live
   wherever powered state is needed, rather than maintaining a cache that could drift
   from the world. `RedstoneListener` itself reacts to `BlockRedstoneEvent` to drive
   the copper bulb and re-attempt processing, but does not need to remember the
   previous state to do either.

5. **`MachineGuiListener`/`MachineBlockListener`/`RedstoneListener` take
   `Supplier<EfConfig>`/`Supplier<AlloyRegistry>`** (not the config/registry directly),
   so Task 6's `/electricfurnace reload` can hot-swap the underlying config/alloys by
   updating whatever mutable holder backs the supplier, without needing to
   re-register listeners. Not specified by the plan, but required for the "reload...
   re-applies it live... without a server restart" requirement that Task 6 owns to be
   satisfiable at all from a Task 5 listener that reads config at click/redstone time
   rather than once at construction.

## Dependencies

**No dependencies were added to `pom.xml`.** Everything builds against the existing
`paper-api` (provided) and `junit-jupiter` (test) dependencies already present.

## Item-loss scenarios: what was handled, and how

- **`InventoryCloseEvent` (any reason)** — `MachineGuiListener#onClose` calls
  `FurnaceGui.returnAllItems`, which gives every input/fuel/output item to the player
  via `Inventory#addItem` and drops whatever doesn't fit at the player's location.
- **Player disconnects with the GUI open** — Paper's `InventoryCloseEvent.Reason`
  includes `DISCONNECT` specifically for this case, and Paper fires the close event
  on disconnect; the same `onClose` handler above covers it with no special-case code.
- **Server shutdown with the GUI open** — `FurnaceGui.closeAll()` iterates online
  players and calls `player.closeInventory()` for anyone viewing an Electric Furnace
  GUI, which synchronously fires `InventoryCloseEvent` and returns items via the same
  path above. This is exposed as a static method for Task 6's `onDisable` to call; it
  is not itself wired into an `onDisable` in this task, since `ElectricFurnacePlugin`
  is a Task 6 file. **Flagging this explicitly as a cross-task dependency**: Task 6
  must call `FurnaceGui.closeAll()` in `onDisable`, or this scenario is not actually
  covered end-to-end despite the method existing.
- **The block is broken while someone has the GUI open** — `MachineBlockListener#onBreak`
  calls `FurnaceGui.closeForBlock(block)` before unregistering/dropping the machine
  item, force-closing any viewer and returning their items via the same `onClose`
  path, before the block (and its now-orphaned virtual inventory) disappears.
- **Output slot occupied by a different item** — `FurnaceGui.classifyOutputSlot`
  returns `DIFFERENT_ITEM` (blocking `mayRun`) both when a genuinely different item
  occupies the slot and when the same item is present but merging would exceed the
  max stack size — the latter isn't explicitly called out by the plan, but silently
  overflowing a stack is the same category of corruption the plan's explicit rule is
  guarding against.
- **A slot holding more than one item** — only one item per occupied input slot is
  consumed per successful operation (see Deviation 3); the rest survive for next time.
- **Drag spanning a guarded slot** — `InventoryDragEvent` is guarded independently of
  `InventoryClickEvent`; the whole drag is cancelled if it touches FILLER, INDICATOR,
  or OUTPUT, since a single drag can distribute the cursor stack across several slots
  at once and a per-click guard alone would miss it.

## Concerns

- **`MachineBlockListener` and `RedstoneListener` have no dedicated unit test.**
  Consistent with the established pattern from Tasks 1-4 (`MachineRegistry` was
  likewise left untested in Task 4): `Block`, `Player`, `BlockRedstoneEvent`,
  `BlockPlaceEvent`, `BlockBreakEvent`, and `PlayerInteractEvent` cannot be
  constructed without a live Bukkit server. Every piece of logic in these two classes
  that *could* be extracted as a pure decision already has been (the slot guard in
  `MachineGuiListener`, the processing gate and indicator state in `FurnaceGui`); what
  remains in `MachineBlockListener`/`RedstoneListener` is thin, sequential Bukkit glue
  (permission checks, event cancellation order, delegating to already-tested pure
  logic) with no independent branching worth extracting. Recommend exercising both at
  gate 7a runtime verification: place/break the machine as a permitted and
  unpermitted player, right-click to confirm the vanilla blast furnace GUI never
  appears, toggle redstone to confirm the copper bulb and indicator both react, and
  specifically test the four item-loss scenarios listed above against a live server.
- **Task 6 must wire `FurnaceGui.closeAll()` into `onDisable`** for the
  "server shutdown with the GUI open" scenario to actually be covered — see Deviation/
  scenario notes above. This is called out so it isn't silently dropped when Task 6
  is implemented.
- **`scheduleProcess`'s next-tick deferral is unverified against a live server.** The
  reasoning (a click's item movement is applied by the server only after the event
  handler returns, so reading slot contents synchronously would see stale state) is
  standard Bukkit plugin-development knowledge, but this task's environment cannot run
  a live server to confirm it empirically. Recommend confirming at gate 7a: insert
  fuel and a full set of inputs in the same click sequence a player would realistically
  perform, and confirm the operation actually runs on the following tick.
- **Bundle `InventoryAction` semantics were not empirically verified.** The exact
  item-movement direction of the 6 bundle-related actions
  (`PICKUP_FROM_BUNDLE`/`PICKUP_ALL_INTO_BUNDLE`/`PICKUP_SOME_INTO_BUNDLE`/
  `PLACE_FROM_BUNDLE`/`PLACE_ALL_INTO_BUNDLE`/`PLACE_SOME_INTO_BUNDLE`) was not looked
  up against Minecraft/Paper source; they are deliberately treated as
  "conservatively both place and take" (see Deviations), which cannot let an item
  through a guarded slot regardless of which way they actually move items, at the cost
  of also blocking a legitimate bundle-based take from the output slot in that rare
  case. Given bundles are an uncommon interaction with a furnace-style GUI, this was
  judged an acceptable, safety-first trade-off rather than worth the risk of guessing
  wrong.
