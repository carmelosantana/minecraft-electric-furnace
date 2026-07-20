# ElectricFurnace — Continuous Operation Design

Date: 2026-07-20
Status: approved
Supersedes the instantaneous-processing model in `2026-07-19-electric-furnace-design.md`.

## Problem

The machine has no concept of time. `FurnaceGui.tryProcess` validates, consumes one redstone,
and deposits output in a single synchronous call fired from a GUI click
(`MachineGuiListener:235`) or a redstone change (`RedstoneListener:156`). `machine.smelt-speed-multiplier`
is parsed and range-validated but never used — there is no duration to multiply.

Four requested behaviours all depend on a duration existing:

1. Redstone should drain continuously while the machine operates, like a burning furnace —
   not one dust per completed operation.
2. Smoke should appear only while actually smelting; sparks whenever the machine is powered.
3. Inputs should be locked while a run is in progress.
4. Smelting should take 2.5× less time than a vanilla furnace.

So this is one change — introduce a per-machine progress model — plus three behaviours that
fall out of it.

## Decision: machines run unattended

The machine keeps running with the GUI closed. Load it, walk away, come back to ingots.

**This deliberately deletes the plugin's current load-bearing safety invariant.** `FurnaceGui`
today guarantees that nothing persists and that every close returns every item
(`FurnaceGui.java:41-55`); that is what made item loss structurally impossible. Persistent
machines replace it with real saved item state, which is the largest source of item-loss bugs
in this class of plugin. The invariant that replaces it:

> Every item that enters a machine is either in that machine's persisted state, in a player's
> inventory, or on the ground. It is never only in memory.

Every design choice below serves that sentence.

## Architecture

### `MachineState`

Per-block runtime state:

| Field | Meaning |
|---|---|
| `inputs[5]` | input slot contents |
| `fuel` | fuel slot contents (redstone dust) |
| `output` | output slot contents |
| `progressTicks` | ticks elapsed on the current run; `0` means idle |
| `burnTicksRemaining` | unspent burn time bought with redstone |

Persisted in the **block's PDC**. `BLAST_FURNACE` is a `TileState`, so its PDC saves and loads
with the chunk automatically — no sidecar file, no save scheduler. Slots serialize via
`ItemStack.serializeAsBytes()` into `PersistentDataType.BYTE_ARRAY`. This mirrors the existing
`MachineRegistry`, which already persists machine *locations* in the owning chunk's PDC.

`MachineRegistry` keeps its current job (which blocks are machines). `MachineState` is a
separate concern (what is inside one machine) and does not merge into it.

### `MachineStore`

Holds live `MachineState` objects for loaded machines. Hydrates from block PDC on chunk load,
flushes to PDC on chunk unload, on world save, and in `onDisable`. A machine whose chunk is
unloaded has no live state — only its PDC bytes.

### `MachineTicker`

One global `BukkitTask`, mirroring the existing `MachineEffects` pattern — there is no
per-block scheduler. Each tick, for every machine in a **loaded** chunk:

1. If not effectively powered, stall (keep `progressTicks`, do not drain burn).
2. Resolve the current inputs. If rejected, or the output slot would block, reset
   `progressTicks` to `0` and stall.
3. If `burnTicksRemaining == 0`, buy more: consume one redstone from the fuel slot, add
   `burn-ticks-per-redstone`. If the fuel slot is empty, stall.
4. Decrement `burnTicksRemaining`, increment `progressTicks`.

Steps 2 and 3 are ordered deliberately: the recipe and output checks run **before** fuel is
purchased, so a machine that cannot smelt never consumes a dust it will not use.
5. When `progressTicks >= smeltTicks`, deposit output, consume one item from each occupied
   input slot, reset `progressTicks` to `0`.

Machines in unloaded chunks do not tick, exactly like vanilla furnaces.

**Stalling never destroys progress or fuel.** A run interrupted by a lost signal or a full
output slot resumes where it left off once the blocker clears — except a rejected *recipe*,
which resets progress, since the inputs themselves changed.

### GUI becomes a view

`FurnaceGui.open` stops creating a fresh inventory. Each `MachineState` owns one shared
`Inventory`, so two players viewing the same machine see identical contents and both watch
progress advance live. `InventoryCloseEvent` no longer returns items — the items belong to the
machine now. `returnAllItems` survives, but is called only on **block break** and on
`onDisable` for machines whose state cannot be flushed.

## Redstone as burn time

One redstone dust buys `burn-ticks-per-redstone` ticks of burn — default **200** (~10s, so 2.5
items at default speed). The redstone *signal* remains the on/off switch; the *dust* is fuel.

Burn time drains **only while a run is actively advancing**, not while idle. Vanilla furnaces
burn fuel down regardless; this deviates deliberately so a powered, loaded machine with nothing
to smelt does not quietly eat a player's dust.

`machine.fuel-per-operation` is removed and replaced by `machine.burn-ticks-per-redstone`.
This is a breaking config change; the validator logs the removed key rather than silently
ignoring it.

## Speed

Vanilla smelt is 200 ticks. `smeltTicks = round(BASE_SMELT_TICKS / smeltSpeedMultiplier)`,
with `BASE_SMELT_TICKS = 200`. `machine.smelt-speed-multiplier` default changes from `2.0` to
**2.5**, giving **80 ticks** per item. The existing 1.0–10.0 validated range stands, so the
derived duration is always within 20–200 ticks.

## Input lock

While `progressTicks > 0`:

- Input slots reject **both removal and insertion**. Insertion is blocked too because changing
  inputs mid-run would invalidate the already-resolved recipe.
- The fuel slot accepts insertion but rejects removal, so a player can top up a running machine.
- The output slot is always freely takable, running or not.

Breaking the block mid-run still returns everything, including partially-progressed inputs.
Progress is forfeited; items never are.

## Effects

`MachineEffects.emitFor` currently spawns both particles together (`MachineEffects.java:368`).
Split them:

| Emission | Condition |
|---|---|
| `ELECTRIC_SPARK` | machine is powered |
| `CAMPFIRE_COSY_SMOKE` | `progressTicks` advanced this effect tick |
| `BLOCK_BEACON_AMBIENT` | same condition as smoke |

The approved-particle allowlist and its guarding test are unchanged.

## Progress display

`FurnaceGui.IndicatorState` gains a `SMELTING` state: a `BLAST_FURNACE` item whose lore renders
`▰▰▰▱▱▱ 50%`, refreshed each effect tick for open viewers. Lore text updates render correctly on
Bedrock; animated bars driven by item-model tricks do not, which is why this is text.

Precedence: `NO_SIGNAL` > `NO_FUEL` > `SMELTING` > `RUNNING`. The existing pure
`indicatorStateOf` keeps its shape and gains the progress input.

## Hoppers

A `BLAST_FURNACE` accepts hopper input into its own vanilla inventory, which this plugin ignores
entirely. Harmless today because nothing persists; with persistent machines, hopper-fed items
would sit invisible and unreachable inside the real furnace inventory.

**Decision: cancel hopper interaction with machine blocks.** `InventoryMoveItemEvent` is
cancelled when either endpoint is a machine block. Hopper automation is a possible later
feature, not part of this change.

## Testing

The existing pattern holds: decision logic stays pure and unit-tested with no running server.

- `MachineTicker` — the whole step function is a pure transition over a `MachineState` record
  and plain booleans (`powered`, `outputBlocked`, `recipeValid`), returning a new state. Every
  stall, resume, and completion path is table-tested with no Bukkit types.
- `smeltTicks` derivation across the full 1.0–10.0 multiplier range.
- Burn-time purchase: exact boundaries at 0 fuel, 1 dust, and mid-burn top-up.
- Lock decisions as a pure function of `(slotRole, action, progressTicks > 0)`, exhaustively.
- Round-trip serialization of `MachineState` through its byte encoding, including empty slots
  and alloy items carrying PDC.

Serialization round-trip is the highest-value test here: it is the one that pins the new
invariant.

## Out of scope

- Hopper automation (cancelled, not implemented).
- Multi-item batch smelting beyond one item per input slot per run.
- Migration of existing in-world machines' contents — today no contents persist, so there is
  nothing to migrate.
- Any change to alloy recipes, stats, or the balance ceiling.

## Risks

1. **Item loss through a persistence gap** — the dominant risk. Mitigated by the round-trip
   test, by flushing on unload/save/disable, and by never holding items only in memory.
2. **Chunk-unload race** — a machine ticking as its chunk unloads. The ticker reads the loaded
   set each pass rather than caching it.
3. **PDC size** — seven serialized stacks per machine. Small, but worth watching if machine
   counts grow.
4. **Bedrock GUI refresh** — live lore updates on an open inventory are more packet traffic than
   the current static GUI. Refresh on the effect tick (15 ticks default), not every tick.
