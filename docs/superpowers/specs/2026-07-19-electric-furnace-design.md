# ElectricFurnace — Design

- Date: 2026-07-19
- Status: approved, pre-scaffold (gate 1 complete)
- Owner: Carmelo Santana

## Purpose

A redstone-powered industrial smelter for `play.xpfarm.org`. It smelts faster and
more efficiently than a vanilla furnace, and it recycles metal gear back into
ingots. Feeding it five of the same metal returns ingots of that metal. Feeding it
a mix returns a fused alloy — and because alloying real metals produces something
*stronger* than its parents, mixed input is the interesting input.

The design teaches a true distinction:

- **Alloying** (combining different metals) yields a stronger material. Bronze beats
  copper; steel beats iron.
- **Remelting** (melting the same metal repeatedly) accumulates impurities and loses
  material. This is where the loss lives.

## Naming chain

Fixed here; every later gate inherits it without reinvention.

| Link | Value |
|---|---|
| Slug | `electric-furnace` |
| Repository | `carmelosantana/minecraft-electric-furnace` |
| Maven groupId | `org.xpfarm` |
| Maven artifactId | `electric-furnace` |
| Updater destination | `electric-furnace.jar` |
| `plugin.yml` name | `ElectricFurnace` |

## Architecture

Units are kept small and separately testable. The recycler's resolution logic is
pure and takes no Bukkit types, so it is unit-testable without a server.

| Unit | Responsibility | Depends on |
|---|---|---|
| `ElectricFurnacePlugin` | Bootstrap, config load, scheduler registration | Paper API |
| `MachineRegistry` | Which block locations are machines; chunk PDC read/write | Paper API |
| `MachineBlock` | One machine's state: powered, fueled, running | `MachineRegistry` |
| `FurnaceGui` | The custom inventory: layout, click routing, slot guards | Paper API |
| `RecycleResolver` | **Pure.** Input item list → `RecycleResult`. No Bukkit types. | config records only |
| `AlloyRegistry` | Named alloy definitions loaded from config | config |
| `AlloyItemFactory` | Mints alloy ingots: material, name, lore, PDC stamps | Paper API, `AlloyRegistry` |
| `MachineEffects` | Global throttled particle/sound loop over active machines | Paper API |
| `MaterialContract` | Shared `xpfarm:` PDC keys; reads foreign namespaces | Paper API |

## The block

A crafted custom item places a `BLAST_FURNACE`. The location is recorded in the
**chunk's** `PersistentDataContainer`, which survives restarts with no database and
no separate location file to corrupt or desync. Breaking the block returns the
custom item and clears the record.

`BLAST_FURNACE` was chosen because it is native on Bedrock, has a native lit state,
and needs no Geyser configuration. See *Visual constraints* below for why the more
exciting options were rejected.

An optional adjacent `COPPER_BULB` acts as a status light, driven via
`CopperBulb#setLit`. Vanilla only recomputes `lit` on a redstone rising edge, so a
server-set value is stable and nothing will fight the plugin for it.

## Power model

Redstone **signal gates** the machine; redstone **dust fuels** it.

- No signal → idle, regardless of fuel. The signal is the on/off switch.
- Signal but no dust → idle, status indicator shows unfueled.
- Signal and dust → running. Dust is consumed per operation.

Both stated requirements stay mechanically meaningful under this model.

## Interface

One custom plugin inventory with a custom title (titles render correctly on
Bedrock). Zones:

- 5 recycler input slots
- 1 redstone fuel slot
- 1 output slot
- 1 status indicator (non-interactive; reflects powered / fueled / running)

Slot guards reject items into non-input slots and prevent extraction of the
indicator.

## Operations

All yields are configurable; the values below are the shipping defaults.

| Input | Output | Real-world process |
|---|---|---|
| 5× the same metal item | **3** ingots of that metal | Recycling — quantity |
| 5× mixed metal items | **2** alloy ingots | Alloying — quality |
| 1× alloy item | **1** ingot | Remelting — the loss |

Damaged gear yields full value regardless of durability. This is deliberate: giving
worn-out gear a worthwhile destination is a primary purpose of the plugin, and
durability-scaled yield would undercut it.

## Alloys

A config table of **named signature alloys**, each with its own complete stat block,
plus a generic `Fused Alloy` fallback for any unrecognized mix. Named alloys are
recipes players discover.

Shipping table (all editable):

| Alloy | Inputs |
|---|---|
| Steel | iron + coal |
| Rose Gold | copper + gold |
| Ferrocopper | copper + iron |
| Electrum Steel | gold + iron |
| Fused Alloy | any other mix, or 3+ distinct metals |

**Coal is a modifier, not a metal.** It cannot be recycled on its own and does not
count toward the "all same metal" check. It only participates as a named-recipe
ingredient — which is metallurgically correct, since carbon into iron is exactly how
steel is made.

### Representation

All alloys display as a renamed `NETHERITE_INGOT` with distinguishing lore and
color. Netherite reads as dark, mottled, and industrial — clearly distinct from
iron, gold, and copper in a hotbar. Identity is carried in PDC, never in lore
matching.

### Balance ceiling

Written into `config.yml` as a documented, enforced constraint:

> **Alloy stats sit between iron and diamond. Never above netherite.**

The five-items-in cost makes "stronger than iron" earned, while the ceiling prevents
the recycler from short-circuiting progression. Config validation clamps any stat
that exceeds the ceiling and logs a warning rather than silently accepting it.

## Visual constraints

Researched 2026-07-19 with sources. These are settled facts, not assumptions.

**Not viable:**

- `custom_model_data` and the 1.21.4 `item_model` component. Geyser supports both
  formats but [requires a separately authored Bedrock resource
  pack](https://geysermc.org/wiki/geyser/custom-items/) — it does not generate one.
  With no assets, this is a dead end.
- **Display entities** (`BLOCK_DISPLAY`, `ITEM_DISPLAY`, `TEXT_DISPLAY`). Not
  translated by Geyser; invisible to Bedrock clients.
  [Geyser #3810](https://github.com/GeyserMC/Geyser/issues/3810), open since 2023.
- **Colored `DUST` particles.** Color does not survive translation to Bedrock.
  [Geyser #1937](https://github.com/GeyserMC/Geyser/issues/1937).
- `RESPAWN_ANCHOR` — explodes when right-clicked outside the Nether, not cleanly
  suppressible.
- Renaming a *placed* block. Block-item display names are discarded on placement;
  there is no server-side nameplate API that does not route through a display entity.

**Viable and used:**

- `COPPER_BULB` — independent `lit` and `powered` blockstates, both settable via
  `BlockData`, native on Bedrock, four oxidation tones available.
- `ELECTRIC_SPARK` and `CAMPFIRE_COSY_SMOKE` particles — both confirmed mapped in
  [GeyserMC/mappings `particles.json`](https://github.com/GeyserMC/mappings/blob/master/particles.json).
- Custom inventory titles.
- `BLOCK_BEACON_AMBIENT` for the electric hum. **Unverified** — the exact Geyser
  sound-event mapping could not be confirmed; requires in-game Bedrock testing.
  Unmapped sounds fail silently, so this degrades safely.

**Deferred to Phase 2:** the Slimefun player-head technique. It looks far better and
is proven at scale, but Bedrock support depends on Geyser auto-generating a pack
from `custom-skulls.yml`, making Geyser config a deployment dependency, and it
inherits [Geyser #5923](https://github.com/GeyserMC/Geyser/issues/5923) — registered
skulls swallow Bedrock interact events, which would directly break a
right-click-to-open machine.

### Effects performance

A **single global `BukkitRunnable` on a 10–20 tick period** iterates only *active*
machines, and skips any machine with no player within 32 blocks. Particles are sent
via the per-player `World#spawnParticle` overloads rather than broadcast. No
per-block per-tick loop under any circumstances.

## Cross-plugin material contract

ElectricFurnace stamps its items under a **shared** namespace rather than a private
one:

- `xpfarm:custom_material` — `STRING`, the owning system
- `xpfarm:material_id` — `STRING`, the specific material (e.g. `steel`)

It can also read CopperKingdom's existing `copperkingdom:copper_armor` and
`copperkingdom:copper_weapon` keys, because `NamespacedKey`'s string constructor
requires no plugin instance. **Neither plugin depends on the other's jar.**

This contract — not a shared library — is the v1 deliverable. A real
`org.xpfarm:xpfarm-items` module gets extracted later, once two plugins have proven
in practice what they genuinely need in common.

### Why not extract a library now

Analysis of CopperKingdom (2026-07-19) found the reusable surface is thin and the
code carries defects that must not be propagated:

- `ArmorType` / `WeaponType` are **enums**, so a second plugin cannot extend them.
  This is the load-bearing blocker; a framework needs config-loaded records.
- Durability is inert: `(int) Math.log(baseDurability / 100.0) + 1` truncates to 0
  for every shipped value, so every item receives Unbreaking 1 and `base_durability`
  changes nothing observable. Real max durability is never set.
- The entire `recipes.*.ingredients` config block is dead — never read by any code.
  Shapes and ingredients are hardcoded.
- `ATTACK_SPEED` is never set anywhere.
- Attribute modifier keys squat the `minecraft:` namespace and are reused across all
  four armor pieces.

ElectricFurnace therefore writes its own item construction, using
`Damageable#setMaxDamage` for real durability and setting attack speed explicitly.
Extraction is a v2 concern and will require fixing the above first.

## Commands

| Command | Args | Permission | Purpose |
|---|---|---|---|
| `/electricfurnace give` | `[player] [amount]` | `electricfurnace.give` (op) | Issue the machine item |
| `/electricfurnace alloy` | `<id> [amount]` | `electricfurnace.give` (op) | Issue an alloy ingot, for testing |
| `/electricfurnace reload` | — | `electricfurnace.reload` (op) | Reload config |
| `/electricfurnace info` | — | `electricfurnace.use` (true) | Show alloy recipes and yields |

## Events

| Event | Why |
|---|---|
| `BlockPlaceEvent` | Register a machine when the custom item is placed |
| `BlockBreakEvent` | Deregister; return the custom item |
| `PlayerInteractEvent` | Open the custom GUI, cancel the native blast furnace GUI |
| `BlockRedstoneEvent` | Track the gating signal; update running state and bulb |
| `InventoryClickEvent` | Slot guards, output extraction |
| `InventoryCloseEvent` | Return input items to the player; persist nothing mid-flight |
| `ChunkLoadEvent` / `ChunkUnloadEvent` | Add/remove machines from the active scheduler set |

## Permissions

| Node | Default | Gates |
|---|---|---|
| `electricfurnace.use` | `true` | Opening and using a machine |
| `electricfurnace.craft` | `true` | Crafting the machine item |
| `electricfurnace.give` | `op` | `/electricfurnace give`, `/electricfurnace alloy` |
| `electricfurnace.reload` | `op` | `/electricfurnace reload` |

## Configuration

| Key | Type | Default | Notes |
|---|---|---|---|
| `machine.smelt-speed-multiplier` | double | `2.0` | vs. vanilla furnace |
| `machine.fuel-per-operation` | int | `1` | redstone dust consumed |
| `machine.require-redstone-signal` | bool | `true` | disable to fuel-only |
| `machine.status-bulb.enabled` | bool | `true` | drive adjacent copper bulb |
| `effects.enabled` | bool | `true` | |
| `effects.period-ticks` | int | `15` | validated 10–40 |
| `effects.player-radius` | int | `32` | skip machines with no nearby player |
| `effects.sound` | string | `BLOCK_BEACON_AMBIENT` | unmapped sounds fail silently |
| `recycling.slots` | int | `5` | validated 1–9 |
| `recycling.yield-same-metal` | int | `3` | |
| `recycling.yield-mixed-alloy` | int | `2` | |
| `recycling.yield-remelt-alloy` | int | `1` | |
| `recycling.accept-damaged` | bool | `true` | full yield regardless of durability |
| `alloys.<id>.*` | section | see table | name, lore, color, inputs, stat block |

**Correction (implementation, 2026-07-19):** an earlier draft of this table listed
`alloys.balance-ceiling.enabled` as a toggle. It was **not implemented, deliberately.**
The ceiling is enforced unconditionally in `AlloyRegistry` — a ceiling an operator can
switch off is not a ceiling, and the binding constraint was that it be enforced in
code rather than documented. A stat above the netherite reference is clamped to the
diamond reference and logs a warning naming the alloy, the stat, the configured
value, and the clamp.

Two further implementation notes that diverge from this document's original text:

- **Rule 3 counts all input items, not only non-modifiers.** As originally worded,
  "fewer than `recycling.slots` non-modifier items → REJECTED" would have rejected
  this document's own mandated example (4 iron + 1 coal → Steel) before it could
  reach the named-recipe check. Counting all items is the only reading consistent
  with the specified behavior.
- **Yields shipped as 3 / 2 / 1** (same-metal / mixed-alloy / remelt), per the
  requirements interview.

Every numeric key is range-validated on load. Invalid values log a warning and fall
back to the default rather than disabling the plugin.

## Persistence

Chunk `PersistentDataContainer`, keyed by block coordinates within the chunk. No
flat file, no database. Machines load and unload naturally with their chunks, which
also gives the effects scheduler its active set for free.

## External integrations

**None.** No Ollama, no Umami, no outside network calls.

## Acceptance checks

1. A crafted machine item places a `BLAST_FURNACE` that is registered as a machine,
   and the registration survives a server restart.
2. Breaking the machine returns the custom item and deregisters the location.
3. With no redstone signal, the machine does not run even with dust in the fuel slot.
4. With a signal and dust, an operation completes and consumes the configured dust.
5. 5× iron ingots in the recycler yields exactly 3 iron ingots.
6. 5× mixed metals yields exactly 2 generic Fused Alloy ingots.
7. 4× iron + 1× coal yields exactly 2 Steel ingots (named recipe match).
8. 1× alloy ingot remelted yields exactly 1 ingot.
9. Fully damaged gear yields the same as undamaged gear.
10. Alloy ingots carry `xpfarm:custom_material` and `xpfarm:material_id` in PDC.
11. A config alloy with stats above netherite is clamped and logs a warning.
12. `RecycleResolver` unit tests pass for: all-same, mixed, named-recipe, remelt,
    fewer-than-five, non-metal-input, and coal-only-input.
13. A Bedrock client via Geyser can place, open, use, and break the machine.
14. Particles are visible to a Bedrock client; absent sound does not error.
15. Config reload applies new yields without a restart.

## Known limitations

- Alloy **armor and weapons deferred to v2.** v1 produces ingots with defined stats
  only.
- **No custom textures on any platform.** Bedrock constraint, documented above with
  sources. The machine looks like a blast furnace.
- **Player-head visuals deferred to Phase 2**, with the Geyser caveats recorded above.
- **`BLOCK_BEACON_AMBIENT` Geyser mapping unverified.** Needs in-game Bedrock
  testing at gate 7a. Fails silently if unmapped.
- **`ELECTRIC_SPARK` visual fidelity on Bedrock unconfirmed.** It is mapped; its
  appearance was not verified.
- **No shared item library.** The cross-plugin PDC contract ships instead;
  extraction is deferred until CopperKingdom's enum, durability, recipe, and
  attack-speed defects are fixed.
- Machines in unloaded chunks do not process. Deliberate — no chunk-forcing.

## Follow-ups outside this plugin

Three defects were found in CopperKingdom during research. They are not
ElectricFurnace's to fix, but they should be tracked:

1. Inert durability (`Math.log` truncation).
2. Dead `recipes.*.ingredients` config block.
3. `CopperLoreListener#onPlayerMove` runs a 1,331-block scan on every movement
   packet, and `CopperLoreListener.java:161` calls `getTargetBlock(null, 50)` with no
   null check (NPEs when a player right-clicks facing open sky).
