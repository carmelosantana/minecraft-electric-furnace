# New or Edited Plugin Checklist

Copy this file for one plugin and replace every `<...>` field. Leave an unchecked box with a short explanation when a gate is not complete; do not silently remove inapplicable checks.

- Plugin name: `ElectricFurnace`
- Slug: `electric-furnace`
- Repository: `carmelosantana/minecraft-electric-furnace`
- Owner: `Carmelo Santana`
- Target version: `0.3.1`
- Paper version: `26.1.2 build 74`
- Java version: `25`
- Updater destination: `electric-furnace.jar`
- External services: `none`
- Status: `active`
- Autonomy: `autonomous`

Maven coordinates: `org.xpfarm:electric-furnace`. `plugin.yml` name: `ElectricFurnace`.

Full design: [docs/superpowers/specs/2026-07-19-electric-furnace-design.md](superpowers/specs/2026-07-19-electric-furnace-design.md)
(v1, click-to-smelt -- partially superseded).

**Continuous operation (`0.2.0`, branch `continuous-operation`):**
[docs/superpowers/specs/2026-07-20-continuous-operation-design.md](superpowers/specs/2026-07-20-continuous-operation-design.md)
/ [docs/superpowers/plans/2026-07-20-continuous-operation-plan.md](superpowers/plans/2026-07-20-continuous-operation-plan.md).
Converts the machine from instantaneous click-to-smelt into one that smelts over
time (80 ticks/item at the new default speed), drains redstone as burn time (one
dust = 200 ticks via `machine.burn-ticks-per-redstone`, replacing the removed
`machine.fuel-per-operation` -- a **breaking config change**), locks input slots
while a run is advancing, cancels hopper transfers into/out of the machine's own
vanilla block inventory (see `MachineBlockListener`'s "Hoppers, known limitation"
note), and persists each machine's contents/run-state in the **machine block's own**
`PersistentDataContainer` (via `MachineStateCodec`/`MachineStore`) so a machine kept
running with nobody watching survives chunk unload, world save, and a full server
restart. This is layered on top of, not a replacement for, the chunk-PDC
**location** registry (`MachineRegistry`) the v1 design above describes.

**Alloy gear crafting (`0.3.0`, planned 2026-07-21):**
[docs/superpowers/specs/2026-07-21-alloy-gear-crafting-design.md](superpowers/specs/2026-07-21-alloy-gear-crafting-design.md).
Delivers the "alloy armor and weapons deferred to v2" limitation recorded against
`0.1.0`, which is struck from Known limitations below. Adds 30
craftable items -- sword, axe, helmet, chestplate, leggings, boots x 5 alloys --
whose per-piece stats are **derived** from each alloy's existing single `AlloyStats`
block (armor total split by vanilla's `3/8/6/3` shape with largest-remainder
rounding; durability interpolated from the tool-scale reference onto vanilla's armor
base unit). Recipes are crafting-table `ShapedRecipe`s using `RecipeChoice.ExactChoice`,
which matches the full data-component map and so cannot be satisfied by a vanilla
netherite ingot. Each alloy gets a **distinct vanilla base material** (steel->iron,
rose_gold->gold, ferrocopper->copper, electrum_steel->diamond, fused_alloy->netherite)
for visual identity without a resource pack. `RecycleResolver` rule 2 relaxes from
"exactly one alloy item" to "all inputs are the same alloy", so a four-piece set
remelts. No new permission node.

## 1. Scope

- [x] Status is explicitly recorded as active, experimental, or excluded.
- [x] Purpose, commands, events, permissions, configuration, persistence, and acceptance checks are defined.
- [x] Known limitations and any intentionally withheld gates are recorded.

### Player-facing purpose

A redstone-powered industrial smelter. It smelts faster than a vanilla furnace and
recycles metal gear back into ingots — five of the same metal returns ingots of that
metal, while a mix fuses into a stronger alloy. Worn-out gear finally has somewhere
to go.

**From `0.3.0`,** those alloys are worth something: each of the five crafts a full
set of gear — sword, axe, helmet, chestplate, leggings, boots — with its own stats,
built on its own vanilla base material so Steel and Rose Gold are told apart at a
glance. Alloy gear recycles back into its own ingots, closing the loop.

### Commands

| Command | Args | Permission | Purpose |
|---|---|---|---|
| `/electricfurnace give` | `[player] [amount]` | `electricfurnace.give` (op) | Issue the machine item |
| `/electricfurnace alloy` | `<id> [piece] [amount]` | `electricfurnace.give` (op) | Issue an alloy ingot, or a gear piece when `piece` is given (`0.3.0`) |
| `/electricfurnace reload` | — | `electricfurnace.reload` (op) | Reload config |
| `/electricfurnace info` | — | `electricfurnace.use` (true) | Show machine tuning, yields, alloy recipes, and each alloy's gear base material (`0.3.0`) |

### Events

`BlockPlaceEvent` (register machine), `BlockBreakEvent` (deregister, return item),
`PlayerInteractEvent` (open custom GUI, cancel native blast furnace GUI),
`BlockRedstoneEvent` (gating signal, running state, copper bulb),
`InventoryClickEvent` (slot guards, output extraction; input slots additionally
locked while a run is advancing), `InventoryCloseEvent` (fold the closed GUI's
contents back into the machine's persisted state; items no longer return to the
player -- see Persistence below), `InventoryMoveItemEvent` (unconditionally cancel
any hopper/dropper/dispenser transfer touching a registered machine's vanilla block
inventory -- known limitation, see below), `ChunkLoadEvent` / `ChunkUnloadEvent`
(maintain the effects scheduler's active set, **and** hydrate/flush each machine's
contents/run-state to and from its block PDC so a mid-smelt machine keeps running
across a chunk unload/reload with nobody watching), `WorldSaveEvent` (flush every
live machine's state on every autosave, not only on a clean shutdown),
`EntityExplodeEvent` / `BlockExplodeEvent` (salvage instead of vanilla-destroy),
`BlockPistonExtendEvent` / `BlockPistonRetractEvent` (refuse to displace a
registered machine).

**Alloy gear (`0.3.0`):** `PrepareItemCraftEvent` (null the craft result when the
grid's ingredients are not genuinely PDC-stamped -- a backstop against the Bedrock
false-positive path, since `ExactChoice` already rejects correctly on Java),
`PlayerJoinEvent` (`Player#discoverRecipe` for the 30 gear recipes; since 1.21.2 the
server sends only unlocked recipes and `Bukkit.addRecipe` unlocks nothing, so without
this the recipe book is empty on **both** editions).

### Permissions

| Node | Default | Gates |
|---|---|---|
| `electricfurnace.use` | `true` | Opening and using a machine |
| `electricfurnace.craft` | `true` | Crafting the machine item |
| `electricfurnace.give` | `op` | `/electricfurnace give`, `/electricfurnace alloy` |
| `electricfurnace.reload` | `op` | `/electricfurnace reload` |

### Configuration

Sections: `machine.*` (smelt speed multiplier, **`burn-ticks-per-redstone`**,
require-signal toggle, status bulb), `effects.*` (enabled, period-ticks,
player-radius, sound), `recycling.*` (slots, yield-same-metal `3`,
yield-mixed-alloy `2`, yield-remelt-alloy `1`, accept-damaged), `alloys.<id>.*`
(name, lore, color, inputs, stat block). Every numeric key is range-validated on
load; invalid values warn and fall back to the default rather than disabling the
plugin. Full table in the v1 design doc; the continuous-operation delta is in the
`2026-07-20` spec's own "Configuration" section.

**Breaking change (continuous operation, `0.2.0`):** `machine.fuel-per-operation`
was **removed**, not deprecated. `EfConfig` detects the old key present in
`config.yml` and warns by name rather than silently ignoring it. It is replaced by
`machine.burn-ticks-per-redstone` (default `200`, validated `20`–`6000`): one
redstone dust now buys that many ticks of burn time, drained only while a run is
actively advancing, rather than a fixed quantity consumed per completed operation.
`machine.smelt-speed-multiplier`'s default also changed, `2.0` → `2.5`, giving `80`
ticks (~4s) per item at default settings.

**Alloy gear (`0.3.0`)** adds exactly one optional key per alloy:
`alloys.<id>.base` (one of `copper`, `iron`, `gold`, `diamond`, `netherite`),
defaulting to the mapping in the gear spec. An unrecognized value warns naming the
alloy, key, offending value, and the default it fell back to, then registers that
alloy's gear on the default base -- consistent with every other key here. There is
deliberately **no** `gear.enabled` master switch: an operator who wants no alloy gear
can remove the alloys. The balance ceiling needs no new configuration and no second
clamp, because `AlloyRegistry.clampStats` already runs before any per-piece
derivation, so gear inherits clamped values automatically.

An earlier draft listed `alloys.balance-ceiling.enabled`. It was **not implemented,
deliberately** — a ceiling an operator can switch off is not a ceiling. The clamp is
unconditional in `AlloyRegistry` and warns naming the alloy, stat, configured value,
and clamp target.

### Persistence

**Two separate `PersistentDataContainer`s, deliberately kept apart:**

- **Location** (unchanged from v1): the owning **chunk**'s PDC, keyed by block
  coordinates within the chunk (`MachineRegistry`). No flat file, no database.
  Machines load and unload with their chunks, which also supplies the effects
  scheduler's active set.
- **Contents and run-state** (new, continuous operation, `0.2.0`): each machine
  **block**'s own PDC (`MachineStateCodec` / `MachineStore`) — its five input slots,
  fuel, output, `progressTicks`, and `burnTicksRemaining`. Hydrated from the block's
  PDC on first access (a GUI open, a redstone change, or a `ChunkLoadEvent` — see
  Events above) and flushed back on every path a machine can leave memory: chunk
  unload, world save, block break, and plugin shutdown (`onDisable` calls
  `MachineStore#flushAll()` directly, before `FurnaceGui.closeAll()`, since Bukkit
  skips event dispatch to an already-disabled plugin). This is what makes "load it,
  walk away, come back to ingots" true: a machine kept running with nobody watching
  survives a chunk unload/reload or a full server restart because its progress and
  fuel are on disk, not only in memory.

Items carry a **shared** cross-plugin contract: `xpfarm:custom_material` and
`xpfarm:material_id` (both `STRING`).

**Alloy gear (`0.3.0`)** carries those same two keys -- which is exactly why gear
needs no recycler precedence change, since `MetalClassifier.resolveBranch` already
ranks `isAlloyStamped` above the material table -- plus one new key,
`xpfarm:gear_piece` (`STRING`, e.g. `"chestplate"`). The new key exists so gear is
distinguishable from an ingot without inspecting base material, which now varies per
alloy. Gear adds no new storage: it is item state, not machine state.

### Dependencies

Hard: none. Soft: none. No load-order requirements. Reads CopperKingdom's
`copperkingdom:` PDC keys opportunistically via `NamespacedKey`'s string
constructor — **no jar dependency in either direction.**

### External integrations

`none`. No Ollama, no Umami, no outside network calls.

### Acceptance checks

1. A crafted machine item places a `BLAST_FURNACE` registered as a machine; registration survives restart.
2. Breaking the machine returns the custom item and deregisters the location.
3. No redstone signal → does not run, even with dust in the fuel slot.
4. Signal + dust → progress advances one tick at a time (80 ticks/item at default
   speed) instead of completing instantly; one dust buys 200 ticks of burn, drained
   only while a run is actively advancing.
4a. A mid-smelt machine's chunk unloads and reloads (or the server restarts) with
    nobody watching → progress and fuel are exactly as they were, and ticking
    resumes without any player interaction.
4b. Input slots are locked (cannot be withdrawn or overwritten) while a run is
    advancing; the fuel slot may still be topped up.
5. 5× iron ingots yields exactly 3 iron ingots.
6. 5× mixed metals yields exactly 2 generic Fused Alloy ingots.
7. 4× iron + 1× coal yields exactly 2 Steel ingots (named recipe match).
8. 1× alloy ingot remelted yields exactly 1 ingot.
9. Fully damaged gear yields the same as undamaged gear.
10. Alloy ingots carry `xpfarm:custom_material` and `xpfarm:material_id` in PDC.
11. A config alloy with stats above netherite is clamped and logs a warning.
12. `RecycleResolver` unit tests pass for all-same, mixed, named-recipe, remelt, fewer-than-five, non-metal-input, coal-only-input.
13. A Bedrock client via Geyser can place, open, use, and break the machine.
14. Particles are visible to a Bedrock client; absent sound does not error.
15. Config reload applies new yields without a restart.

**Alloy gear (`0.3.0`):**

16. Crafting a Steel Sword from 2 Steel ingots + 1 stick yields an iron-base sword
    named "Steel Sword" carrying all three PDC keys.
17. The same shape built from **vanilla** netherite ingots yields nothing — no
    output, no error, no item.
18. Each alloy's four armor pieces' armor points sum to exactly the configured
    `armor` total. Steel → 2/7/5/2 = 16.
19. Steel armor durability derives to 231/336/315/273; Steel weapons to 700.
20. An alloy configured above the netherite reference is clamped by `AlloyRegistry`
    *before* derivation, and its gear reflects the clamped values.
21. A Fused Alloy sword dropped in lava burns — fire immunity is stripped from the
    netherite base via `unsetData(DAMAGE_RESISTANT)`, not `resetData`.
22. No alloy armor grants knockback resistance, including Fused Alloy.
23. Gear attack damage and armor include the merged vanilla defaults — a Steel Sword
    does its derived damage, not the bare modifier value.
24. Recycling 4 Steel armor pieces yields `4 × yield-remelt-alloy` Steel ingots.
25. Recycling a Steel sword and a Rose Gold sword together rejects with
    `"mixed alloys"`, not `"non-metal input"`.
26. `/electricfurnace reload` leaves exactly 30 registered recipes, not 60.
27. All 30 recipes appear in the Java recipe book after joining.
28. A Bedrock client can craft each of the six Steel pieces by manual placement.
29. `GearStatsDeriver` unit tests pass for all five alloys × six pieces, plus the
    rounding-tiebreak and interpolation-endpoint cases.

### Known limitations

**Alloy gear (`0.3.0`)** — replaces the former "alloy armor and weapons deferred to
v2" limitation, which this version delivers:

- **Tools and spears are still not implemented.** Pickaxe/shovel/hoe need a
  mining-speed or harvest-tier field `AlloyStats` does not have, plus a per-alloy
  "can it mine obsidian" decision. Spears (`IRON_SPEAR`/`COPPER_SPEAR`/`NETHERITE_SPEAR`
  exist in this version and are already in `MetalClassifier.METAL_TABLE`) are cheap to
  add, but their throwing/reach behaviour interacts with attack-speed modifiers in ways
  worth verifying separately. Deliberate, not an oversight.
- **Enchantability is Java-only.** `ItemMeta#setEnchantable` is stable API and works on
  Java, but Geyser reads enchantability from the item **type** at handshake
  registration, never per stack, so a Bedrock player sees the base material's value.
  Whether Bedrock refuses to *place* an item its own definition marks non-enchantable
  is UNRESOLVED and is a gate 7a check.
- **Max durability is Java-only in display.** Geyser scales the durability bar against
  the item type's vanilla maximum. Durability *loss* is server-authoritative and
  correct; only the bar's scale is wrong — a Steel Sword shows a 700-point bar on Java
  and a 2031-point bar on Bedrock while wearing out identically.
- **Attack speed has essentially no Bedrock feedback.** Bedrock still uses 1.8 combat;
  Geyser offers only a cosmetic cooldown indicator. The modifier changes server-side
  damage scaling with almost no client signal.
- **Attribute tooltips on Bedrock are synthesized lore and are buggy for tools.**
  Geyser renders Java modifiers as lore; [Geyser #5037](https://github.com/GeyserMC/Geyser/issues/5037)
  reports tools showing base-item defaults while armor displays correctly. Real damage
  and armor are correct — combat is server-side — so this is cosmetic.
- **Rose Gold gear makes piglins neutral.** Driven by the `#minecraft:piglin_safe_armor`
  **item tag**, not a component, so no per-item lever exists. Accepted as flavour for a
  gold alloy rather than fixed.
- **Anvil-renaming an alloy ingot breaks its recipes.** `ExactChoice` compares display
  name and lore alongside PDC, so a renamed ingot no longer matches.
- **Bedrock crafting flickers, and a phantom output is reachable.** Bedrock's recipe
  ingredient wire format has **no NBT field at all** (descriptor set is
  `DEFAULT, MOLANG, ITEM_TAG, DEFERRED, COMPLEX_ALIAS`), so all 30 recipes reach a
  Bedrock client as "netherite_ingot ×N + stick". Real crafting still works — Geyser
  synthesizes a one-off recipe from the actual grid stacks on a 150 ms delay — but a
  player laying out *vanilla* netherite ingots in one of these shapes sees a fake
  output; clicking it yields nothing, caught by the `PrepareItemCraftEvent` backstop.
  This is a client limitation, not a Geyser gap: GeyserMC closed the equivalent
  smithing request ([#4706](https://github.com/GeyserMC/Geyser/issues/4706)) as
  *Can't Fix / Missing Client Feature*. Exact on-screen presentation is UNRESOLVED and
  is a gate 7a check.

**Machine (`0.1.0`–`0.2.1`):**

- **No custom textures on any platform.** Bedrock constraint: `custom_model_data` and the 1.21.4 `item_model` component both require a separately authored Bedrock resource pack ([Geyser custom items](https://geysermc.org/wiki/geyser/custom-items/)); display entities are untranslated and invisible to Bedrock ([Geyser #3810](https://github.com/GeyserMC/Geyser/issues/3810)). The machine looks like a blast furnace with particle and sound identity.
- **Colored `DUST` particles unusable** — color does not survive to Bedrock ([Geyser #1937](https://github.com/GeyserMC/Geyser/issues/1937)). `ELECTRIC_SPARK` and `CAMPFIRE_COSY_SMOKE` are confirmed mapped and used instead.
- **Player-head (Slimefun-style) visuals deferred to Phase 2.** Requires Geyser `custom-skulls.yml` as a deployment dependency and inherits [Geyser #5923](https://github.com/GeyserMC/Geyser/issues/5923), where registered skulls swallow Bedrock interact events — which would break right-click-to-open.
- **`BLOCK_BEACON_AMBIENT` Geyser sound mapping unverified.** Must be confirmed by in-game Bedrock testing at gate 7a. Fails silently if unmapped, so it degrades safely.
- **`ELECTRIC_SPARK` visual fidelity on Bedrock unconfirmed.** Mapped, but appearance not verified.
- **No shared item library with CopperKingdom.** The cross-plugin PDC contract ships instead. Extraction is deferred until CopperKingdom's enum-based type system, inert durability, dead recipe config, and missing attack-speed are fixed.
- **Machines in unloaded chunks do not process.** Deliberate — no chunk-forcing. (Continuous operation, `0.2.0`: this remains true, but a machine now resumes exactly where it left off the instant its chunk loads again, rather than needing something incidental to touch it first.)
- **Hoppers cannot feed or drain a machine (continuous operation, `0.2.0`).** A registered machine block is still a vanilla `BLAST_FURNACE` underneath, with its own 3-slot vanilla smelting inventory entirely separate from this plugin's custom GUI and `MachineState`. `MachineBlockListener#onInventoryMove` unconditionally cancels every hopper/dropper/dispenser transfer touching that vanilla inventory in either direction, because routing hopper items into `MachineState` the way a player's click does was judged not worth the risk of writing them into the ignored vanilla inventory instead — deliberate, not a config toggle.

No gates are intentionally withheld. Status is `active`; the full pipeline runs.

## 2. Repository

- [x] Repository is `carmelosantana/minecraft-<slug>` with an SSH `origin` and `main` branch.
  - Created: <https://github.com/carmelosantana/minecraft-electric-furnace>
  - `origin` = `git@github.com:carmelosantana/minecraft-electric-furnace.git`, branch `main`
  - Commit `aa19bd7` pushed to `origin/main` by the operator on 2026-07-19; CI run `29703069182` triggered.
- [x] Existing user-owned worktree changes were identified and preserved.
  - New repository, no pre-existing worktree. `git init` ran on a directory containing only gate 1 output.
- [x] No `herobrinesystems` references remain in source, metadata, workflows, remotes, or documentation.
  - `rg -n 'herobrinesystems' . --hidden -g '!target/**' -g '!.git/**'` → single hit, which is this checklist's own checkbox text. No real references.

### Repository visibility — RESOLVED

`gh repo create` defaulted to private, which would have failed updater enrollment
(the updater downloads release assets unauthenticated). Made public by the operator
and verified 2026-07-19 via `gh api repos/carmelosantana/minecraft-electric-furnace`
→ `private=false visibility=public`.

## 3. Metadata

- [x] AGPL-3.0-or-later `LICENSE` and Maven license metadata are present and consistent.
  - `LICENSE`: full AGPL-3.0 text, 661 lines.
  - `pom.xml`: `<licenses>` names "GNU Affero General Public License v3.0 or later" at <https://www.gnu.org/licenses/agpl-3.0.html>.
- [x] `https://xpfarm.org` metadata and Carmelo Santana author metadata are present.
  - `pom.xml`: `<url>`, `<developers><developer><name>Carmelo Santana`.
  - `plugin.yml`: `author: Carmelo Santana`, `website: https://xpfarm.org`.
- [x] `play.xpfarm.org` is recorded as the public Minecraft server hostname where server identity is documented.
  - `README.md`, with the Java/Bedrock (Geyser + Floodgate) note.
- [x] New work uses the `org.xpfarm` Maven group, or an existing-coordinate compatibility decision is documented.
  - `org.xpfarm:electric-furnace:0.1.0`. No compatibility carve-out needed; this is new work.
- [x] Repository slug, artifact, releasable JAR, updater destination, and `plugin.yml` names are consistent.
  - slug `electric-furnace` → artifactId `electric-furnace` → JAR `electric-furnace-0.1.0.jar` → destination `electric-furnace.jar` → `plugin.yml` name `ElectricFurnace`. Verified by `grep` on both files.
- [x] No secrets committed in source, defaults, tests, logs, history, or documentation.
  - Credential/token/key scan returned only this checklist's own checkbox text.

### Deliberate deviation from the CopperKingdom POM

CopperKingdom's `pom.xml` sets `maven.compiler.release=25` **and** an explicit
`<source>21</source><target>21</target>` on the compiler plugin. The `release`
property wins, so the two disagree silently. This POM omits the explicit
source/target and relies on `maven.compiler.release` alone. The build target is
unchanged; the contradiction is not propagated.

## 4. Compatibility

### `api-version` raised `'1.21'` → `'26.1'` (2026-07-22, post-`0.3.0`, unreleased)

The deferral recorded against `0.3.0` (gate 6 below) is now closed. The declaration was
stale — the plugin already compiled against the 26.1.2 API — and leaving it stale kept
Paper's legacy bytecode rewrites switched on. Treated as a **compatibility** change, not
metadata, per `PLUGIN_LIFECYCLE.md` §4: it alters the bytecode Paper loads, so gate 6 and
gate 7a were both re-run rather than a bare recompile being accepted. Evidence in gate 6
and gate 7a below. Two files changed and nothing else:
`src/main/resources/plugin.yml` and `PluginDescriptorTest.java`'s matching assertion,
which pinned the old value and would otherwise have gone green against a stale
descriptor. No source change, no behaviour change, no config change.

The other `1.21`-shaped strings in the tree are unrelated *minimum Minecraft version*
comments (`1.21.2` recipe-book behaviour, `1.21.9` copper equipment) and were correctly
left alone.

**Alloy gear (`0.3.0`) note — reviewed, one new interaction surface.** The branch adds
its first genuinely new player-facing surface since `0.1.0`: a crafting table. Reviewed
against the Geyser/Floodgate/ViaVersion rules and verified live at gate 7a where
possible:

- **Input:** still inventory clicks and commands only. No typed chat input anywhere.
- **UI:** no `setCustomModelData`, no `item_model`, no display entities — confirmed by
  scanning `src/main`. **Base material is the entire visual-identity mechanism**, which
  is why each alloy maps to a distinct vanilla tier: those textures already exist on
  both editions, needing no resource pack.
- **Identity:** gear is identified by PDC only (`xpfarm:custom_material`,
  `xpfarm:material_id`, `xpfarm:gear_piece`), never by display name or lore substring.
- **Bedrock recipe limitation, researched not assumed:** Bedrock's recipe ingredient
  wire format has **no NBT field at all** (descriptor set is
  `DEFAULT, MOLANG, ITEM_TAG, DEFERRED, COMPLEX_ALIAS`), so all 30 recipes reach a
  Bedrock client as "netherite_ingot ×N + stick". This is a client limitation, not a
  Geyser gap — GeyserMC closed the equivalent smithing request
  ([#4706](https://github.com/GeyserMC/Geyser/issues/4706)) as *Can't Fix / Missing
  Client Feature*. Real crafting still works: Geyser synthesizes a one-off recipe from
  the actual grid stacks after ~150 ms. The `PrepareItemCraftEvent` backstop makes the
  phantom path fail safe.
- **Recipe discovery:** since 1.21.2 the server sends only unlocked recipes and
  `Bukkit.addRecipe` unlocks nothing, so `Player#discoverRecipe` is required or the
  recipe book is empty on **both** editions. Wired on join and on reload, with
  `Bukkit.updateRecipes()` to resend.
- **Protocol:** no assumption about the client's protocol version. Verified live at 7a
  alongside ViaVersion 5.11.0, Geyser 2.11.0 and Floodgate 2.2.5, all green together.
- **Attribute API:** `Attribute` is an *interface* on this Paper version, not an enum —
  `GENERIC_*` constants, `EnumMap<Attribute,…>` and `Attribute.values()` all fail. Code
  uses the interface constants throughout.

**Continuous operation (`0.2.0`) note:** the bullets below were originally recorded
against `0.1.0`/`0.1.1`. The branch adds no new player-facing interaction surface --
still inventory clicks and commands only, the new `InventoryMoveItemEvent` handler
and chunk-load hydration are both non-interactive plugin internals -- so the
Geyser/Floodgate/ViaVersion conclusions below still hold on inspection and were not
struck. The compile check does have fresh evidence from this fix pass (below). None
of this substitutes for a live client join, which remains gate 7a's job and is
recorded outstanding for this branch below.

- [x] Java 25/Paper 26.1.2 build 74 compile succeeds and `plugin.yml` uses `api-version: '26.1'`.
  - **Current (2026-07-22):** raised to `'26.1'`, the standard for Paper 26.1.2 build 74.
    `mvn --batch-mode --no-transfer-progress clean verify` BUILD SUCCESS on Temurin
    25.0.3+9; embedded `plugin.yml` in the shaded JAR confirmed `api-version: '26.1'`.
    Runtime-confirmed at gate 7a — Paper loaded and enabled the plugin with the legacy
    rewrites off, and logged no `InvalidDescriptionException` and no legacy/`Commodore`
    notice.
  - *History:* previously `'1.21'`, verified as such for `0.1.0`, `0.2.0` and `0.3.0`.
    Those records below are kept as history and do not describe the current descriptor.
- [x] Hard dependencies, soft dependencies, optional APIs, and load ordering were reviewed and declared.
  - **None.** No `depend`, `softdepend`, or `loadbefore`/`loadafter` entries, deliberately.
    CopperKingdom interop is read-only via `NamespacedKey`'s String constructor, which
    needs no plugin instance — so there is no load-order requirement in either
    direction and neither plugin depends on the other's JAR.
- [x] Geyser/Floodgate/ViaVersion review covers Bedrock-safe input, UI, inventory, identity, and protocol behavior.
  - **Input:** all interaction is inventory clicks or commands. No typed chat input
    anywhere — Bedrock clients cannot reliably use Java chat prompts.
  - **UI:** custom inventory title only. No `setCustomModelData`, no `item_model`
    component, no display entities. Verified by scanning `src/main` — the only
    occurrences of those names are comments explaining why they are not used.
  - **Inventory:** plugin-owned inventory with slot guards covering plain click,
    shift-click, hotbar swap, double-click collect, and drag.
  - **Identity:** items identified by PersistentDataContainer only, never by display
    name or lore substring. This deliberately avoids the failure mode in the sibling
    CopperKingdom plugin, which matches the lore substring "Blessed".
  - **Particles:** only `ELECTRIC_SPARK` and `CAMPFIRE_COSY_SMOKE`, both confirmed
    mapped in GeyserMC/mappings. Colored `DUST` deliberately unused.
  - **Protocol:** no assumptions about the client's protocol version, so ViaVersion
    bridging is unaffected. Runtime-verified alongside ViaVersion 5.11.0.

## 5. External services

- [x] External integrations are disabled by default or require explicit configuration and have bounded timeouts.
  - **Not applicable — there are none.** No network calls of any kind; no HTTP client
    on the classpath. Verified: the shaded JAR contains only plugin classes,
    `plugin.yml`, `config.yml`, and Maven metadata.
- [x] Ollama/Umami-style external endpoints are optional and failure-tolerant when applicable.
  - Not applicable; none exist.
- [x] Endpoint failure cannot fail server/plugin startup, and diagnostics redact secrets.
  - No endpoints. The equivalent startup-safety property was still enforced and
    reviewed: every `onEnable` step is wrapped in a `Throwable` guard, a malformed
    config degrades to defaults with warnings, a single malformed alloy entry is
    skipped rather than discarding the whole config, and sound resolution catches
    `Throwable` (not merely `ReflectiveOperationException`) so an `Error` from a
    static initializer cannot escape. No secrets exist to leak.

## 6. Tests and build

### `api-version` raise to `'26.1'` — current, supersedes everything below (2026-07-22)

Re-run in full because this is a compatibility change, not metadata — a green compile
was explicitly **not** accepted as sufficient evidence.

`mvn --batch-mode --no-transfer-progress clean verify` → **exit 0, BUILD SUCCESS,
416 tests, 0 failures, 0 errors, 0 skipped**, 8.2s, on Temurin 25.0.3+9. Test count
unchanged from `0.3.0` (416): this change adds no test, it corrects one existing
assertion. The two pre-existing `HOTBAR_MOVE_AND_READD` deprecation warnings in
`MachineGuiListenerTest.java` are unaffected; no new warnings.

**`PluginDescriptorTest` did assert the value and was updated.**
`PluginDescriptorTest.java:84` held `assertEquals("1.21", parsed.get("api-version"))` —
so the descriptor test was the one thing that would have failed on a plugin.yml-only
edit, and it did its job. Now `assertEquals("26.1", ...)`. The neighbouring
`assertInstanceOf(String.class, ...)` guard is unchanged and still load-bearing:
unquoted, `26.1` parses as a double, which is the exact defect that guard exists for.

Shaded JAR `target/electric-furnace-0.3.0.jar` inspected — never an `original-*`
intermediate. Embedded `plugin.yml` reads `version: '0.3.0'`,
`main: org.xpfarm.electricfurnace.ElectricFurnacePlugin`, **`api-version: '26.1'`**, the
`electricfurnace` command with its `ef` alias, and all four permission nodes. JAR entry
roots are exactly `org/xpfarm`, `config.yml`, `plugin.yml`, `META-INF` — no server API
bundled (`org/bukkit`, `io/papermc`, `net/minecraft`, `com/mojang` all absent, paper-api
correctly `provided`), no third-party libraries shaded, no secret-bearing file.

**Version not bumped.** The JAR still reads `0.3.0`, which is already tagged and
released. This change is unreleased and belongs to the next release; the version
decision is `minecraft-plugin-release`'s, not this gate's.

### `0.3.0` (alloy gear crafting) — supersedes everything below it

**Alloy listing order fix (2026-07-22, after the 416-test run below).**
`AlloyRegistry.fromDefinitions` built its map in a `LinkedHashMap` but returned
`Map.copyOf(clamped)`, whose iteration order is unspecified and salted per JVM run.
That order is player-facing: `ElectricFurnaceCommand.handleInfo` prints the
`Alloy recipes:` block by iterating `all()`, so the listing shuffled between restarts
and never matched `config.yml`. Now `Collections.unmodifiableMap(new LinkedHashMap<>(clamped))`
— ordered, still unmodifiable. **4 new tests** in `AlloyRegistryTest`, all verified to
fail against the old line before the fix landed: `fromDefinitions` order preservation
(8 ids, fallback deliberately mid-list), synthesized-fallback-appended-last, the
`load` chain end-to-end (pins that `getKeys(false)` and the parse are ordered too),
and an unmodifiability guard. `mvn --batch-mode --no-transfer-progress clean verify` →
**exit 0, BUILD SUCCESS, 420 tests, 0 failures, 0 errors, 0 skipped**, still exactly
**2** `[WARNING]` lines, unchanged and both in `MachineGuiListenerTest.java:70,246`.

`mvn --batch-mode --no-transfer-progress clean verify` → **exit 0, BUILD SUCCESS,
416 tests, 0 failures, 0 errors, 0 skipped** (2026-07-22, run directly by the
controller, not relayed). Exactly **2** `[WARNING]` lines, both pre-existing and both
in `MachineGuiListenerTest.java:70,246` — `InventoryAction.HOTBAR_MOVE_AND_READD` is
deprecated and marked for removal in the Paper API. That file is untouched by this
branch, so this work introduces zero new warnings. (Its *marked-for-removal* status is
a real upstream signal: a future Paper bump turns those two warnings into a build
failure. Recorded as a follow-up, out of scope here.)

Shaded JAR `target/electric-furnace-0.3.0.jar` inspected — never an `original-*`
intermediate. Embedded `plugin.yml` reads `version: '0.3.0'`,
`main: org.xpfarm.electricfurnace.ElectricFurnacePlugin`, `api-version: '1.21'`.

**`api-version` deliberately left at `'1.21'` for this release — CLOSED 2026-07-22.**
The current standard is `'26.1'`, and the declaration is stale — the plugin already
compiles against the 26.1.2 API. Raising it is a *compatibility* change, not metadata:
it switches off Paper's legacy bytecode rewrites and so requires gate 6 and gate 7a
re-run. It was held back so `0.3.0` ships one variable rather than two — 30 new recipes
plus a new item-minting path plus a bytecode-loading change would have made any failure
unattributable. **Now raised on its own, with its own gate 6 and gate 7a runs, recorded
in the current section at the top of this gate and in gate 7a below.** Deferring it paid
off as intended: the 7a run isolated one variable, so its green result is attributable.

**Test growth:** 311 tracked at branch point → **416**. The pure core
(`GearPiece`, `GearStatsDeriver`, `GearStats`, `GearBase`) is exhaustively covered with
no running server, matching the `MetalClassifier`/`RecycleResolver` precedent.
`GearItemFactory`, `GearRecipes` and the lifecycle wiring have no unit tests by design —
constructing a real `ItemStack` needs a live server, and this codebase deliberately does
not mock Bukkit glue.

**Six of the thirteen task briefs prescribed tests that missed the behaviour they
named**, each caught by an implementer's mutation testing rather than by review: the
armor-split tests never pinned largest-remainder ranking; the durability tests never
pinned rounding direction; `derive`'s tests used one alloy so three hardcoding mutants
survived; `GearBase`'s never called `id()`; the config tests could not tell a thematic
default from a hardcoded `IRON`; and the command's unknown-piece test passed with the
entire branch deleted. All closed.

**Superseded for `0.2.0`.** The `238 tests` / `electric-furnace-0.1.0.jar` bullets
below describe the pre-continuous-operation build and are kept only as history; they
do not describe the current branch. Fresh evidence gathered against this exact fix
pass (continuous-operation, targeting `0.2.0`) replaces them immediately below.

- [x] Unit tests cover separable logic, configuration, serialization, permissions, and failure paths where applicable.
  - **298 tests, 0 failures** (continuous-operation fix pass, 2026-07-20; superseded
    the `238 tests` figure below). The suite stood at 306 tests going into this fix
    pass; this pass removed the 8 `FurnaceGui.mayRun` tests (6 individual cases plus
    its hand-written truth table and the table's own exhaustiveness check) as part of
    the M1 disposition — `mayRun` was dead code in `src/main` (see
    `.superpowers/sdd/final-fix-report.md`), and `MachineTicker.step` is the one
    decision function the ticker actually drives from. Net: 306 − 8 = 298. Coverage
    beyond the `238`-test v1 baseline spans `MachineTicker.step`'s full
    stall/resume/completion outcome table, `MachineTicker.shouldSkipMachine`,
    `MachineStateCodec`'s versioned byte-frame encode/decode (including
    hostile/truncated input), `MachineStore`'s documentation-level flush/hydrate
    discipline, `FurnaceGui`'s slot-lock and deferred-GUI-sync guards, and
    `MachineEffects`'s `APPROVED_PARTICLES` allowlist test.
  - Bukkit types (`ItemStack`, `Inventory`, `Block`, `Player`, `TileState`) cannot be
    constructed headlessly, so decisions stay extracted into pure functions and
    tested there — e.g. `MetalClassifier.resolveBranch` over all 16 boolean
    combinations, `MachineTicker.step` over its full outcome table,
    `allowedSubcommandTokens` over all permission subsets. `MachineStore`'s and
    `MachineBlockListener`'s new per-machine try/catch guards (this fix pass's R1/R2)
    could not be given a headless test for the same reason — see this fix pass's
    report for what was and was not testable.
- [x] `mvn --batch-mode --no-transfer-progress clean verify` succeeds.
  - **BUILD SUCCESS, 298 tests, 0 failures/errors/skipped** (continuous-operation fix
    pass, 2026-07-20). Exact command and output recorded in
    `.superpowers/sdd/final-fix-report.md`.
- [x] The shaded releasable JAR and embedded `plugin.yml` were inspected; `original-*` JARs are excluded.
  - `target/electric-furnace-0.2.0.jar`: embedded `plugin.yml` shows version
    `0.2.0`, main `org.xpfarm.electricfurnace.ElectricFurnacePlugin`, `api-version
    '1.21'`, the `electricfurnace` command, and all four permission nodes. No server
    API bundled (`org/bukkit`, `io/papermc`, `net/kyori` all absent — paper-api
    correctly `provided`). `original-electric-furnace-0.2.0.jar` exists in `target/`
    and is excluded from release assets by the workflow's `! -name 'original-*'`
    filter.

### Historical (v0.1.0/v0.1.1, superseded)

- **238 tests, 0 failures.** Coverage spans config validation and clamping, the pure
  recycle resolver (all eight rules with precedence), alloy registry and
  balance-ceiling clamping, metal classification, chunk-key encode/decode including
  hostile input, GUI slot roles and guard decisions, command argument parsing and
  per-subcommand permissions, and the effects gate.
- BUILD SUCCESS, 238 tests, 0 failures/errors/skipped.
- `target/electric-furnace-0.1.0.jar`: embedded `plugin.yml` shows version `0.1.0`,
  main `org.xpfarm.electricfurnace.ElectricFurnacePlugin`, `api-version '1.21'`, the
  `electricfurnace` command, and all four permission nodes. No server API bundled
  (`org/bukkit`, `io/papermc`, `net/kyori` all absent — paper-api correctly
  `provided`). `original-electric-furnace-0.1.0.jar` exists in `target/` and is
  excluded from release assets by the workflow's `! -name 'original-*'` filter.

## 7. Matrix

### 7a — Single-plugin runtime verification — PASSED for the `api-version` raise to `'26.1'` (2026-07-22)

Re-run because raising `api-version` changes which bytecode Paper loads. This is the
point of the whole exercise: a green `mvn verify` proves the source still compiles, and
proves nothing about a plugin whose bytecode the server now declines to rewrite.

Booted on a fresh disposable Legendary stack via the shared rig
(`scripts/test-stack.sh up` → slot 1, java 25601, bedrock 19201, rcon 25576; project
`xpfarm-plugin-test-nostalgic-leavitt-a50554-6b8e307f`). The rig's three preconditions
all passed: Paper's own `Done (25.949s)! For help` line; a real Minecraft protocol
handshake on the Java port (`Paper 26.1.2 | protocol 775`, `PLAYERS: 0 / 20`), not merely
a TCP connect; and RCON `plugins` listing this plugin **green**.

**The load path itself is the thing under test, and it is clean.** With the legacy
rewrites now off, Paper still logged `Loading server plugin ElectricFurnace v0.3.0` →
`Enabling ElectricFurnace v0.3.0` → `ElectricFurnace enabled (5 alloys, effects on,
ticker on).` No `InvalidDescriptionException`, no `Could not load`, and no
legacy/`Commodore`/rewrite notice anywhere in the log — the plugin is neither absent
(unparseable descriptor) nor red (`onEnable()` threw).

**Whole cross-play stack green together:** `ElectricFurnace`, `floodgate`,
`Geyser-Spigot`, `ViaVersion` — all four enabled, none red, none absent. Companion
versions unchanged from the `0.3.0` run: Geyser-Spigot 2.11.0-SNAPSHOT, ViaVersion
5.11.0, floodgate 2.2.5-SNAPSHOT (b138-fc99cfc).

**Exercised over RCON against the rewritten-bytecode-free load, all passing:**

- `/electricfurnace info` — full render, byte-identical in substance to the `0.3.0` run:
  machine tuning (2.5x, 80 ticks, 200 ticks per dust), yields, and all five alloys with
  their gear base materials (`electrum_steel … diamond base`, `ferrocopper … copper base`,
  `rose_gold … gold base`, `fused_alloy … netherite base`, `steel … iron base`). This
  exercises `EfConfig.load`, `AlloyRegistry.load` and `GearBase` resolution on the new
  load path. Zero `has no <piece> on this server` lines, so vanilla copper equipment
  still resolves.
- `/electricfurnace reload` — clean, `ElectricFurnace configuration reloaded.` No
  duplicate-`NamespacedKey` throw, so all 30 gear recipes still unregister and re-register
  correctly.
- **Error paths:** `alloy nosuchalloy` → `Unknown alloy 'nosuchalloy'. Try
  /electricfurnace info.`; `ef notasubcommand` → `Unknown subcommand` plus the usage line
  carrying `[piece]`. Command dispatch, the `ef` alias, and argument parsing all intact.
- **Logs clean throughout** — startup, enable, reload, command invocations, and shutdown
  produced zero exceptions, zero `SEVERE`, zero `org.xpfarm` stack frames, zero plugin
  warnings, and no leaked secrets. The only two log hits matching an error-shaped pattern
  are both unrelated and pre-existing: vanilla's `Failed to parse level-type default`,
  and a JVM `sun.misc.Unsafe` terminal-deprecation notice from a server-side library.
- **Clean teardown.** `scripts/test-stack.sh down` removed container, volume and network
  and released the slot lease; no stack and no lease leaked.

#### What this 7a run could NOT reach

No client attached to this stack, by design. **The entire gate 12 play-test obligation
recorded for `0.3.0` below stands unchanged and is carried forward** — every item on that
eight-point list (crafting each alloy's gear, reading the tooltips, Fused Alloy's lava and
knockback checks, Bedrock manual placement and the phantom recipe, the three laundering
paths, set recycling, revoked-`craft` behaviour, and the mint-then-reload gap) is still
unproven, and is now unproven under a *different bytecode load path* than the one those
items were written against. Nothing here narrows that list; this run only proves the
plugin still loads, enables, reads its config and answers commands with the legacy
rewrites off.

Specifically unreachable here:

- **Every event went unexercised** — no `PrepareItemCraftEvent`, `PlayerJoinEvent`,
  `BlockPlaceEvent`, `InventoryClickEvent`, `InventoryMoveItemEvent`, or redstone event
  fired. RCON proves a command ran, not that an event fired. The RCON test-harness plugin
  specified in `PLUGIN_LIFECYCLE.md` §7 still does not exist.
- **No gear item was minted.** Console has no inventory, so
  `/electricfurnace alloy <id> <piece>` cannot execute `GearItemFactory.create` — the same
  limitation as the `0.3.0` run.
- **Nothing a client renders** — Bedrock form and inventory behaviour, tooltip values,
  item textures — is provable headlessly at all.

**No external-service negative path applies:** this plugin makes no outbound calls
(`External services: none`).

### 7a — Single-plugin runtime verification — PASSED for `0.3.0` (2026-07-22)

Booted on a fresh disposable Legendary stack via the shared rig
(`scripts/test-stack.sh up` → slot 0, java 25600, bedrock 19200, rcon 25575;
project `xpfarm-plugin-test-electric-furnace-2494d778`). The rig's three
preconditions all passed: Paper's own `Done (16.932s)! For help` line; a real
Minecraft protocol handshake on the Java port (`Paper 26.1.2 | protocol 775`),
not merely a TCP connect; and RCON `plugins` listing this plugin **green**.

**Whole cross-play stack green together:**
`ElectricFurnace (0.3.0)`, `Geyser-Spigot (2.11.0-SNAPSHOT)`, `ViaVersion (5.11.0)`,
`floodgate (2.2.5-SNAPSHOT b138-fc99cfc)` — all four enabled, none red, none absent.
Enable line: `ElectricFurnace enabled (5 alloys, effects on, ticker on).`

**Exercised over RCON, all passing:**

- `/electricfurnace info` — renders every alloy **with its gear base material**
  (`electrum_steel (Electrum Steel, diamond base)`, `ferrocopper (…, copper base)`,
  `rose_gold (…, gold base)`, `fused_alloy (…, netherite base)`, `steel (…, iron base)`),
  confirming Task 13's `alloyInfoLine` live. The remelt line reads
  `alloy remelt  1 ingots each` — Task 13's carried per-item wording fix, confirmed live.
- `/electricfurnace reload` — run **three times consecutively**, all clean. This is the
  live evidence for the "30 recipes, not 60" property: `GearRecipes.register()` calls
  `unregister()` first and removes each key before re-adding it, so a broken teardown
  would surface as `Bukkit.addRecipe` throwing on a duplicate `NamespacedKey`. No such
  warning appeared on any of the three reloads.
- **Command parsing (Task 12), every path:** usage line carries `[piece]`;
  `alloy steel trousers` → `Unknown gear piece 'trousers'`; `alloy nosuchalloy sword` →
  `Unknown alloy 'nosuchalloy'`; `alloy steel sword 3 extra` → `Too many arguments`
  (the surplus-argument strictness the implementer kept against the plan's sketch);
  and the **ambiguous third token resolved correctly** — `alloy steel 5` reached the
  inventory check as an amount rather than erroring as an unknown piece.
- **All 30 recipes registered.** `GearRecipes.registerOne` warns by name on any failure
  and `GearItemFactory` returns empty when a base material is absent. Zero
  `Failed to register` and zero `has no <piece> on this server` lines appeared — which
  also positively confirms this Paper 26.1.2 build carries vanilla copper equipment,
  the 1.21.9+ assumption Ferrocopper's `base: copper` depends on.
- **Logs clean throughout** — startup, three reloads, nine command invocations, and
  shutdown produced zero exceptions, zero `SEVERE`, zero `org.xpfarm` stack frames, and
  zero plugin warnings.
- **Clean shutdown**, no exceptions in the disable path. `scripts/test-stack.sh down`
  removed container, volume and network and released the slot; no stack leaked.

#### What 7a could NOT reach for `0.3.0` — the gate 12 play-test obligation

This is the whole risk surface of the feature, and none of it is proven. No client
attaches to this stack by design, and **console has no inventory**, so
`/electricfurnace alloy <id> <piece>` correctly refused with
`Console has no inventory; name a player` on every attempt — meaning
**`GearItemFactory.create` never executed at runtime.** Not one gear item was minted.

Owed, in priority order:

1. **Craft one piece of each of the five alloys on Java.** The single highest-risk
   unproven assumption in the release. `ExactChoice` snapshots its ingredient at
   *registration* and compares the entire `DataComponentPatch`; if the stack
   `AlloyItemFactory` produces at registration differs by one component from the one the
   machine deposits at runtime, **all 30 recipes match nothing, silently, with no error
   anywhere.** Nothing headless can test this.
2. **Read the tooltips.** Steel Sword must show **6.5** damage / 1.4 attack speed;
   Steel Chestplate +7 armor / +1 toughness; the Steel set 2/7/5/2 summing to 16 with
   231/336/315/273 durability. A sword showing **7.5** means the display→modifier
   conversion went the wrong way; **3.0** means `addAttributeModifier` merged instead of
   replacing. Both are single-call behaviours only a live server settles.
3. **Fused Alloy, acceptance checks 6 and 7.** Sword dropped in lava must **burn**
   (`unsetData` vs `resetData`); full set must **not** resist knockback.
4. **Bedrock manual placement** of all six Steel pieces, plus the phantom: lay out
   *vanilla* netherite ingots in the sword shape, click the fake output, confirm nothing
   sticks to the cursor and nothing duplicates.
5. **The three laundering paths the final review found and fixed**, now needing
   confirmation: nine alloy ingots in a 3×3 must not become a netherite block; two
   damaged alloy swords must not repair into a plain iron sword; smithing-template
   duplication must not consume a stamped item.
6. **Recycle a four-piece Steel set** (yields 4 × `yield-remelt-alloy`) and **a Steel
   sword + a Rose Gold sword** (must reject with `mixed alloys`).
7. **Crafting with `electricfurnace.craft` revoked** must blank the result.
8. **An ingot minted before a `/electricfurnace reload`** — known gap: it no longer
   satisfies the rebuilt `ExactChoice`, so the recipe book shows the recipe and the grid
   produces nothing, silently.

Every event this plugin listens to went unexercised — no `PrepareItemCraftEvent`,
`PlayerJoinEvent`, `BlockPlaceEvent`, `InventoryClickEvent`, or redstone event fired,
because RCON proves a command ran, not that an event fired. The RCON test-harness
plugin specified in `PLUGIN_LIFECYCLE.md` §7 does not exist yet.

### 7b — full-roster ecosystem matrix — NOT run, out-of-band, not required for release

`minecraft-plugin-matrix` is triggered by an updater manifest change or a
Paper/Geyser/Floodgate/ViaVersion bump, not by every `dev` run. `0.3.0` changes none of
those: no manifest change, no dependency bump, no new external service. Last full-roster
run was the 12-plugin matrix on 2026-07-21.

### 7a — `0.2.1` delta — NOT re-verified live, deliberately

`0.2.1` adds a Machine block to `/electricfurnace info` and changes nothing else --
no behaviour, no config format, no persistence. The rendering is a pure function
(`ElectricFurnaceCommand.machineInfoLines`) covered by six headless tests and verified
by mutation: hardcoding the items-per-dust ratio kills two tests, removing the
zero-duration guard kills one.

**No disposable stack was booted for `0.2.1`.** Docker work on the shared rig was queued
behind this release, and starting a second stack risked interfering with it. The live
send path is one `sendMessage` per returned line, so the untested surface is that loop
alone. The `0.2.0` runtime verification below stands; `/electricfurnace info` was
exercised live there, and this change alters only what it prints.

### 7a — Single-plugin runtime verification (this plugin only) — PASSED for `0.2.0`

Verified in two parts on 2026-07-20.

**Part 1 — automated, disposable Legendary stack.** Ports 25570/19140 leased by hand
(`xpfarm-slot` is not installed on this machine). Fresh volume, torn down and volume
removed after the run. RCON was enabled manually on the container to reach the console;
it is not published by `docker-compose.yml`.

- Loads clean on Paper 26.1.2 + Geyser 2.11.0 + Floodgate 2.2.5 + ViaVersion 5.11.0:
  `ElectricFurnace enabled (5 alloys, effects on, ticker on)`. No plugin warnings or
  exceptions on enable, disable, or a full stop/start cycle.
- **No `Failed to get Spigot's CommandMap`** — this closes the open ecosystem question
  about whether Bedrock players see command help text. They do.
- `/electricfurnace info` and `/electricfurnace reload` both work from console.
- Config validation observed live: an out-of-range `machine.smelt-speed-multiplier`
  of `99.0` warned naming the key, the value, and the default, then fell back to `2.5`;
  the removed `machine.fuel-per-operation` key warned naming its replacement rather
  than being silently ignored. Restoring the config reloaded with no warnings.

**Part 2 — operator, live clients on both platforms.** The operator confirmed the
continuous-operation behaviours work on **both Java and Bedrock**: placing and using a
machine, smelting over time, burn-time drain, input locking, two viewers on one
inventory, the double-click vacuum guard, shift-click routing, and chunk unload/reload
mid-run.

**One check not run:** restarting the server with an *alloy* item sitting in a machine's
output slot. Alloy items carry custom `ItemMeta` and PDC, so they exercise the
`MachineStateCodec` `ItemStack`↔bytes round-trip more heavily than a plain ingot does.
Plain-item persistence across restart was verified; the alloy-specific round-trip rests
on unit tests. Low risk (the codec is type-agnostic and uses
`ItemStack.serializeAsBytes`), but it is not observed, and is recorded here rather than
claimed.

The evidence below is retained as history for `v0.1.0`/`v0.1.1` (pre-continuous-
operation).

### Historical (v0.1.0) — PASSED

Disposable Legendary stack, fresh volume, ports leased via `xpfarm-slot` (slot 0),
torn down and lease released after each run. Verified twice: once on `ca5d378`, and
again on `96861af` after the Task 6 review fixes touched the registry and startup
paths.

Evidence from the second run:

- **Paper, Geyser, Floodgate, and ViaVersion started together.** Plugin list:
  `ElectricFurnace (0.1.0), Geyser-Spigot (2.11.0-SNAPSHOT), ViaVersion (5.11.0),
  floodgate (2.2.5-SNAPSHOT)`. `Geyser-Spigot Done (3.032s)`, server
  `Done (21.265s)`.
- **Plugin loaded and enabled cleanly:** `Enabling ElectricFurnace v0.1.0` →
  `ElectricFurnace enabled (5 alloys, effects on).`
- **Config and alloy parsing confirmed against the real shipped `config.yml`.** The
  "5 alloys" count exercises `EfConfig.load`'s dotted-path reads and
  `AlloyRegistry.load`'s `ConfigurationSection` glue — both flagged during review as
  untestable headlessly. This run is their first real evidence, and they work.
- **Clean shutdown:** `Disabling ElectricFurnace v0.1.0`, no exceptions.
- **No exceptions, SEVERE lines, or leaked secrets** in startup, enable, or shutdown
  logs. The only warning present is vanilla's unrelated `Failed to parse level-type
  default`.
- **External-service negative paths:** not applicable — this plugin makes no
  outbound calls.

**Not covered, and deliberately recorded rather than claimed:**

- **No Java or Bedrock client join was performed**, so no in-game exercise of the
  GUI, crafting, redstone gating, recycling, particles, or sound. The container
  exposes no RCON (`enable-rcon=false`) and stdin console injection did not take, so
  even console commands could not be driven. Everything below therefore rests on
  unit tests and code review, not observed behavior:
  - `/electricfurnace give|alloy|reload|info` have never been executed.
  - The GUI has never been opened; slot guards, the C1 double-click dupe fix, and the
    C2 shutdown item-return path are unverified at runtime.
  - Redstone gating and the `COPPER_BULB` status light are unverified. Review noted
    `BlockRedstoneEvent` should reach adjacent machines after the S1 fix, but this
    was never observed firing.
  - Particle appearance and `BLOCK_BEACON_AMBIENT` sound on a Bedrock client are
    unverified — the sound mapping was already an open question from gate 1 research.
  - `World#getNearbyPlayers`, the `playSound(Location, String, ...)` overload, and
    `Bukkit.removeRecipe` resolve at class-load but were never invoked.

  These are the highest-value checks for a human to run before this reaches
  production. A clean enable is not evidence the plugin works.

### 7b — Ten-plugin ecosystem matrix

**Historical (v0.1.1), not re-run for `0.2.0`.** Out-of-band per its own note below,
and this fix pass did not trigger a re-run (no updater manifest change, no
Paper/Geyser/Floodgate/ViaVersion bump). Not required before this branch merges or
before `0.2.0` releases, but the record below predates continuous operation and
should not be read as evidence about it.

- [x] Fresh-volume [Legendary Java Minecraft Geyser Floodgate stack](https://github.com/TheRemote/Legendary-Java-Minecraft-Geyser-Floodgate) test covers all updater-managed plugins.
  - **Out of band, and not a prerequisite for this plugin's release.** Belongs to
    `minecraft-plugin-matrix`, triggered by an updater manifest change, a
    Paper/Geyser/Floodgate/ViaVersion bump, or explicit request — not by every `dev`
    run. Gate 7a above tested this plugin alone in an otherwise-default stack and is
    not evidence about interaction with the other nine.

- [x] Each updater-managed plugin's manifest `enabled` value, default state, and expected fresh-volume behavior are recorded separately.
- [x] Paper, Geyser, Floodgate, and ViaVersion start successfully together.
- [x] Java and Bedrock smoke tests cover joins plus affected commands, events, permissions, persistence, and reloads where feasible.
  - Covered for this plugin by gate 7a part 2 (operator, live Java and Bedrock clients, 2026-07-20). Still **not** evidence about the ten-plugin matrix, which has not been re-run since `0.2.0`.
- [x] Public deployment smoke tests verify `play.xpfarm.org` reaches the intended Java and Bedrock entry points.
  - DNS resolves to `168.231.74.113`; Java TCP 25565 and Bedrock UDP 19132 both reachable.
- [x] Ollama and Umami unavailable-endpoint tests keep the server and plugins available when applicable.
  - Both integrations ship `enabled: false`, so the default run only proved they stay
    dormant. To test the real failure path they were switched on and pointed at
    TEST-NET-2 `198.51.100.9`. Ollama caught
    `java.net.SocketException: Network is unreachable`, retried once and stopped
    (RetryExec count static at 2 across 45s — bounded, no loop). Umami enabled and
    warned about its unconfigured website ID without failing. Server reached
    `Done (12.933s)` and stayed reachable throughout. Zero credential-shaped strings
    in any log line.


**Run of 2026-07-19 — PASSED (11/11).** Triggered by the checksum-manifest
remediation (ten patch re-cuts) and this plugin's enrollment in the updater
manifest. The manifest now carries **eleven** plugins, not ten — Electric Furnace
was added in `43f5bb7`.

- Manifest state for this plugin: `enabled` absent (= true), no pin. Expected
  fresh-volume behavior: install and enable. Observed: installed `v0.1.1`,
  `Enabling ElectricFurnace v0.1.1` → `ElectricFurnace enabled (5 alloys,
  effects on).` **PASS**
- All eleven plugins installed by the one-shot updater on a fresh volume and
  enabled together; zero SEVERE/exception lines stack-wide.
- Stack: Paper 26.1.2, Geyser 2.11.0, Floodgate 2.2.5 b138, ViaVersion 5.11.0.
- Each installed JAR's SHA-256 matched its published `SHA256SUMS.txt` digest.

Still not covered by this run: no Java or Bedrock client join was performed, so
the 7a caveats above stand unchanged. A passing matrix is evidence the plugins
coexist and enable, not that this plugin's in-game behavior works.

### 7b — ecosystem matrix (12 plugins) — PASSED 2026-07-21

Trigger: the updater manifest changed — Timber Blast `v1.0.0` was enrolled
(`carmelosantana/minecraft-plugin-updater` commit `6065b03`), taking the roster from 11 to 12.

- [x] Fresh-volume Legendary stack test covers all updater-managed plugins. **12/12 PRESENT.**
      Run via the shared rig (`xpfarm-test-stack matrix up --from-releases`) on a fresh volume,
      roster read from the live `plugins.json` rather than a hardcoded list. The rig cross-checks
      the plugin count the server announces against what it parsed, and asserts each plugin is
      **enabled**, not merely listed.
- [x] Each updater-managed plugin's manifest `enabled` value, default state, and expected
      fresh-volume behavior are recorded separately. All 12 entries have `enabled` absent
      (equivalent to `true`) and no `pin`; every one was therefore expected to install and enable,
      and every one did. No entry was disabled, so there is no intentional-absence row this run.
- [x] Paper, Geyser, Floodgate, and ViaVersion start successfully together.
      Paper reached `Done (15.543s)! For help, type "help"`; the Java port answered a real
      protocol handshake reporting `Paper 26.1.2 | protocol 775`, `PLAYERS: 0 / 20`. Companions:
      Geyser-Spigot 2.11.0-SNAPSHOT, floodgate 2.2.5-SNAPSHOT, ViaVersion 5.11.0.
- [ ] Java and Bedrock smoke tests cover joins. **Not performed — no client attaches to this
      stack by design.** Per `PLUGIN_LIFECYCLE.md` §7 this is not a blocker; client behavior is a
      tracked gate-12 play-test obligation, not a matrix result.
- [x] `play.xpfarm.org` reaches the intended Java and Bedrock entry points.
      Read-only production check, separate from the disposable stack: DNS `168.231.74.113`;
      Java `25565` answered a real handshake (`Paper 26.1.2 | protocol 775`, 1 player online);
      Bedrock UDP `19132` reachable.
- [x] Ollama and Umami unavailable-endpoint tests keep the server and plugins available.
      Neither service exists in this stack, so this is the negative path by construction. Both
      self-disabled cleanly: `Ollama integration is disabled; no API client or listeners were
      started.` and `Umami analytics is disabled; no tracking listeners or network clients were
      started.` Server stayed healthy (`list` responded) with all 12 enabled.

This plugin's row: the updater reported `ElectricFurnace: installed v0.2.1` from the published release
asset and Paper enabled it alongside the other 11. `--from-releases` was used deliberately — it
installs the real published assets through the real updater, so this is what production installs.

Co-resident: AguaDeFlorida 2.0.0, CopperKingdom 0.2.1, TheCurse 0.2.2, DeathDepot 1.1.1, GlutenFreeBread 1.1.3, Ollama 0.2.1, StarterPack 1.1.2, TimberBlast 1.0.0, Umami 1.1.1, WildWeatherUpdate 1.0.2, WorldCRUD 1.1.2.

Zero exceptions, SEVERE lines, or enable failures attributable to any plugin. No secrets in any
log line. Stack torn down with `matrix down`; lease released, no orphaned containers.

## 8. CI/CD

### `0.3.1` — main-branch run recorded before tagging (2026-07-22)

- [x] Successful main Actions run is recorded before tagging.
  - Run [29952192473](https://github.com/carmelosantana/minecraft-electric-furnace/actions/runs/29952192473),
    `Build and release`, branch `main`, commit `2178bed` (`Bump version to 0.3.1`)
    → **completed / success**. This is the exact SHA `v0.3.1` tags; the tag was created
    only after the run resolved to a completed success, never while in flight.
  - The preceding commit `8718d3c` (the `api-version` raise itself) also had its own
    green main run,
    [29945941742](https://github.com/carmelosantana/minecraft-electric-furnace/actions/runs/29945941742)
    → completed / success, so both commits in this release are independently validated.
  - Warnings unchanged: only the two pre-existing
    `InventoryAction.HOTBAR_MOVE_AND_READD` deprecations in
    `MachineGuiListenerTest.java:70,246`, matching the local build exactly. No new
    warnings.

### `0.3.0` — main-branch run recorded before tagging (2026-07-22)

- [x] Successful main Actions run is recorded before tagging.
  - Run `29941249477`, `Build and release`, branch `main`, commit `fe8f723`
    (`Merge alloy-gear-crafting: 30 craftable alloy weapons and armor (0.3.0)`)
    → **completed / success**, 36s.
  - **The run was `in_progress` when first checked and was deliberately waited out
    rather than tagged around.** A still-running run is exactly as disqualifying as a
    failed one — it is not evidence yet, in either direction, and tagging into it races
    production. Resolved to a completed success before the tag was created.
  - The only warnings are the two known pre-existing
    `InventoryAction.HOTBAR_MOVE_AND_READD` deprecations in
    `MachineGuiListenerTest.java:70,246`, matching the local build exactly. No new
    warnings introduced by this branch.

**`0.2.0` note:** the workflow file itself (checkbox 1) and its permission scope
(checkbox 3) are durable facts unaffected by this branch's content and remain
accurate as recorded. Checkbox 2's run/commit reference predates this branch and is
history only — `continuous-operation` has not yet had a green `main` Actions run
under its own commit, because it has not yet merged. That run is a prerequisite
`minecraft-plugin-release` will need before tagging `v0.2.0`, not something this fix
pass can produce.

- [x] Identical standard plugin Actions workflow is installed with the required triggers, Temurin 25 build, artifact, checksum, and release behavior.
  - `.github/workflows/build.yml` copied byte-for-byte from the CopperKingdom reference, which matches `GITHUB_ACTIONS.md`. Triggers: push to `main`, `v*` tags, PRs targeting `main`, `workflow_dispatch`. `actions/checkout@v7`, `actions/setup-java@v5` (Temurin 25, Maven cache), `mvn --batch-mode --no-transfer-progress clean verify`, `SHA256SUMS.txt` excluding `original-*`, `actions/upload-artifact@v7`, tag-gated `gh release view`/`create`/`upload --clobber`.
- [x] Successful main Actions run is recorded before tagging.
  - **`0.2.1`:** run
    [29829903616](https://github.com/carmelosantana/minecraft-electric-furnace/actions/runs/29829903616)
    on `main` (commit `848c22e`) completed **success** with the full 311-test suite.
    The `v0.2.1` tag run also succeeded. Release assets are exactly
    `electric-furnace-0.2.1.jar` + `SHA256SUMS.txt`, no `original-*.jar` leak, and
    `sha256sum --check` returns `electric-furnace-0.2.1.jar: OK`.
  - **`0.2.0`:** run
    [29765656324](https://github.com/carmelosantana/minecraft-electric-furnace/actions/runs/29765656324)
    on `main` (merge commit `6ac4d36`) completed **success**, building the full
    305-test suite on Temurin 25. The tag run
    [29765667140](https://github.com/carmelosantana/minecraft-electric-furnace/actions/runs/29765667140)
    also succeeded on the same SHA. Release assets are
    `electric-furnace-0.2.0.jar` + `SHA256SUMS.txt` only — no `original-*.jar` leak —
    and `sha256sum --check` against the downloaded assets returns
    `electric-furnace-0.2.0.jar: OK`, confirming the bare-filename manifest fix from
    `v0.1.1` still holds.
  - Historical evidence for `0.1.0`/`0.1.1`: run
    [29706471487](https://github.com/carmelosantana/minecraft-electric-furnace/actions/runs/29706471487)
    on `main` (commit `9025cf9`) completed **success** in 25s, 2026-07-19, building
    the full 238-test suite on Temurin 25 in a clean environment. The earlier scaffold
    run 29703069182 also succeeded. `continuous-operation` needs its own green `main`
    run before `minecraft-plugin-release` tags `v0.2.0`.
- [x] Workflow permissions contain no broader access than the documented contract.
  - `permissions: contents: write` only. No `packages:`, `id-token:`, or other scopes.

## 9. Release

### `0.3.1` — RELEASED and verified (2026-07-22) — current

<https://github.com/carmelosantana/minecraft-electric-furnace/releases/tag/v0.3.1>

Patch release carrying one change: `api-version` `'1.21'` → `'26.1'`. No behaviour
change, no config change, no API surface added or removed — the only shipped difference
is which bytecode Paper loads, which is why this is a patch and not a minor.

- [x] Semantic version matches the POM, plugin metadata, and `v<version>` tag.
  - POM `<version>0.3.1</version>`; `plugin.yml` uses `version: '${project.version}'`
    with no hardcoded drift; the embedded `plugin.yml` in the **downloaded published**
    JAR reads `version: '0.3.1'` and `api-version: '26.1'`; annotated tag `v0.3.1` →
    `2178bed`, the exact commit gate 8's green run validated.
- [x] Tag run succeeded and the release exists.
  - Run [29952251944](https://github.com/carmelosantana/minecraft-electric-furnace/actions/runs/29952251944),
    branch `v0.3.1`, sha `2178bed` → **completed / success**.
  - Release created by `github-actions[bot]`, published `2026-07-22T19:45:12Z`,
    `draft: false`, `prerelease: false`.
- [x] Asset contract satisfied — exactly one updater-matching JAR plus checksums.
  - Assets are exactly `electric-furnace-0.3.1.jar` (155944 bytes) and
    `SHA256SUMS.txt` (93 bytes). Verified by listing the *downloaded* release directory,
    not just the API asset list.
  - **No `original-*` JAR leaked.**
- [x] `sha256sum --check` passes against the published assets.
  - `sha256sum --check SHA256SUMS.txt` → `electric-furnace-0.3.1.jar: OK`, exit 0.
    Manifest records the bare filename (`c93c18f7…  electric-furnace-0.3.1.jar`), so the
    `v0.1.0` `target/`-prefix defect remains fixed.

**Gate 10 requires no manifest change for this release.** The updater entry for this
plugin carries `asset_regex: ^electric-furnace-[0-9].*\.jar$`, which matches
`electric-furnace-0.3.1.jar`, with no `pin` and no `enabled: false`. It therefore picks
up `0.3.1` on its next run with no edit. Confirmed by reading the live
`minecraft-plugin-updater/plugins.json`, not assumed.

**Released with client behaviour still unverified**, exactly as `0.3.0` was. The gate 7a
run for the `api-version` raise proved the plugin loads, enables, reads its config and
answers commands on the new bytecode path; it minted no gear item and fired no event. The
full play-test obligation enumerated under §7 is unchanged and still owed on
`play.xpfarm.org`.

### `0.3.0` — RELEASED and verified (2026-07-22)

<https://github.com/carmelosantana/minecraft-electric-furnace/releases/tag/v0.3.0>

- [x] Semantic version matches the POM, plugin metadata, and `v<version>` tag.
  - POM `<version>0.3.0</version>`; `plugin.yml` uses `version: '${project.version}'`
    with no hardcoded drift; the embedded `plugin.yml` in the shaded JAR reads
    `version: '0.3.0'`; annotated tag `v0.3.0` → `fe8f723`, the exact commit gate 8's
    green run validated.
- [x] Tag run succeeded and the release exists.
  - Run `29941305264`, branch `v0.3.0` → **completed / success**, 28s.
  - Release created by `github-actions[bot]`, published `2026-07-22T17:12:08Z`,
    `draft: false`, `prerelease: false`.
- [x] Asset contract satisfied — exactly one updater-matching JAR plus checksums.
  - Assets are exactly `electric-furnace-0.3.0.jar` and `SHA256SUMS.txt`.
  - **No `original-*` JAR leaked** — the pre-shade Maven Shade intermediate is excluded
    by the workflow, confirmed by listing the downloaded release directory.
- [x] `sha256sum --check` passes against the published assets.
  - Downloaded the release and ran `sha256sum --check SHA256SUMS.txt` →
    `electric-furnace-0.3.0.jar: OK`, exit 0.

**Released with client behaviour unverified, deliberately and with eyes open.** Gate 7a
passed everything reachable headlessly, but no gear item was ever minted — console has
no inventory, so `GearItemFactory.create` never executed. The full play-test obligation
is enumerated under §7 above and is owed on `play.xpfarm.org`. This release exists so
that testing can happen; it is not evidence that the feature works in a client.

**`0.2.0` has not been tagged or released.** Everything below this line records the
`v0.1.0`/`v0.1.1` release history, which remains accurate as history but is not
evidence about `0.2.0`. `minecraft-plugin-release` owns cutting and verifying the
`0.2.0` release once gate 8's fresh main run (above) and gate 7a's outstanding
runtime re-verification (above) are both satisfied.

- [x] Semantic version matches the POM, plugin metadata, and `v<version>` tag.
  - POM `0.1.0`; `plugin.yml` uses `version: '${project.version}'` (no hardcoded drift);
    embedded `plugin.yml` in the shaded JAR reads `0.1.0`; tag `v0.1.0` → `db344e5`.
- [x] Successful tag Actions run and GitHub release are recorded.
  - Tag run [29706675169](https://github.com/carmelosantana/minecraft-electric-furnace/actions/runs/29706675169)
    completed **success** in 31s. Release published 2026-07-19T22:44:24Z by
    `github-actions[bot]`, `draft: false`, `prerelease: false`:
    <https://github.com/carmelosantana/minecraft-electric-furnace/releases/tag/v0.1.0>
- [x] Release contains exactly one updater-matching JAR plus `SHA256SUMS.txt` and no `original-*` JAR.
  - Exactly two assets: `electric-furnace-0.1.0.jar` and `SHA256SUMS.txt`. No
    `original-*` JAR present.
- [x] Downloaded release assets pass `sha256sum --check SHA256SUMS.txt`.
  - **v0.1.1: `electric-furnace-0.1.1.jar: OK`.** Manifest records the bare filename
    and verification passes cleanly. This is the released version; gate 10 should
    enroll `v0.1.1`, not `v0.1.0`.
  - v0.1.0 failed this check and is superseded — see below.

### v0.1.1 release evidence

- Tag `v0.1.1` on commit `ec50351`; main run
  [29706816331](https://github.com/carmelosantana/minecraft-electric-furnace/actions/runs/29706816331)
  green **before** tagging; tag run
  [29706840456](https://github.com/carmelosantana/minecraft-electric-furnace/actions/runs/29706840456)
  completed success in 32s.
- Release <https://github.com/carmelosantana/minecraft-electric-furnace/releases/tag/v0.1.1>,
  `draft: false`, `prerelease: false`.
- Exactly two assets: `electric-furnace-0.1.1.jar` + `SHA256SUMS.txt`. No `original-*`.
- `sha256sum --check` → `OK`.

### Checksum verification failure in v0.1.0 — ecosystem-wide workflow defect (FIXED HERE)

`sha256sum --check SHA256SUMS.txt` on the downloaded assets reports:

```
sha256sum: target/electric-furnace-0.1.0.jar: No such file or directory
target/electric-furnace-0.1.0.jar: FAILED open or read
```

**The JAR itself is intact.** Its actual SHA-256 is
`b65e12588410b86b172b6d6dba6af797355fcd5e953c14dc9cd897196e4a395e`, which matches the
recorded digest exactly. Stripping the path prefix makes the check report `OK`.

The defect is in the workflow's checksum step. `.github/workflows/build.yml` runs:

```bash
find target -maxdepth 1 -name '*.jar' ! -name 'original-*' -print0 | sort -z | xargs -0 sha256sum > target/SHA256SUMS.txt
```

`sha256sum` records whatever path it was given, so the manifest contains the
build-time path `target/electric-furnace-0.1.0.jar`. Release assets download flat,
so the recorded path never resolves and verification always fails.

**This is not specific to this plugin.** The workflow was copied byte-for-byte from
the CopperKingdom reference, which matches `GITHUB_ACTIONS.md`. Verified against the
latest published releases of three siblings — all carry the same `target/` prefix:

| Plugin | Published `SHA256SUMS.txt` entry |
|---|---|
| copper-kingdom | `…  target/copper-kingdom-0.2.0.jar` |
| death-depot | `…  target/death-depot-1.1.0.jar` |
| curse | `…  target/curse-0.2.0.jar` |

Every plugin release in the ecosystem therefore fails gate 9's checksum requirement
as published. The fix belongs in the shared standard (`GITHUB_ACTIONS.md` and every
repository's `build.yml`), not in this plugin alone — e.g. `cd target && sha256sum
*.jar` or piping through `basename`.

Escalated to the operator rather than resolved unilaterally: the remedy requires
either moving an already-published tag or burning a version number, and it changes a
convention shared by ten repositories.

## 10. Updater

- [x] Updater manifest/tests cover repository, destination, anchored asset regex, legacy globs, enabled state, and optional pin.
  - Enrolled in `carmelosantana/minecraft-plugin-updater` commit `43f5bb7`.
  - `repo` `carmelosantana/minecraft-electric-furnace`; `destination` `electric-furnace.jar`
    (unique across all 11 entries, verified); `asset_regex` `^electric-furnace-[0-9].*\.jar$`;
    `legacy_globs` `["electric-furnace-[0-9]*.jar"]`; `enabled` absent (= true); **no pin**,
    deliberately — the entry follows the latest stable release.
  - Regex verified to select exactly one asset: matches `electric-furnace-0.1.1.jar`;
    rejects `SHA256SUMS.txt`, `original-electric-furnace-0.1.1.jar`, and other plugins' JARs.
  - `python3 -m json.tool plugins.json` valid; `python3 -m unittest discover -s tests` 11/11 OK.
- [x] Fresh install, upgrade, no-op, legacy archival, endpoint failure, and checksum failure behaviors pass.
  - **Fresh install:** resolves `v0.1.1`; installed JAR SHA-256
    `3e1532e8a929dd014ddc6b027fc6bc69615a225bde8d96acf304b9fc926eacae` matches the
    published `SHA256SUMS.txt` bit for bit — release-to-install verified end to end.
  - **No-op:** second real run reports `already current (v0.1.1)`.
  - **Upgrade:** stale bytes at the destination trigger a reinstall.
  - **Legacy glob:** selects a versioned leftover (`electric-furnace-0.0.9.jar`) while
    correctly excluding the destination filename it just installed.
  - **Endpoint failure:** a 404 repo logs `WARNING: ... keeping installed JAR`, exits 0
    (fail-open), leaves the destination JAR untouched, and the other ten plugins still process.
  - **Checksum failure:** covered by the updater's own unit suite (11/11 OK), not re-derived here.
- [x] Updater dry-run uses a disposable directory and never a production plugin directory.
  - All runs used `/tmp/minecraft-plugin-updater-dry-run`. Every non-dry-run invocation
    overrode all three of `--plugins-dir`, `--state-file`, and `--backup-dir` into that
    sandbox, so nothing touched the `/minecraft` production volume. Tree deleted afterward.
- [x] Failure retains the installed JAR and default fail-open behavior permits Minecraft startup.
  - Confirmed by the endpoint-failure case above: exit 0, JAR retained, batch continued.

## 11. Deployment

**Deployed by the operator, verified functionally rather than from logs.** This agent has
no Dokploy access from this machine (no CLI, no credentials, no SSH host), so the
container-level evidence this gate normally wants could not be read directly. What is
recorded below is what was actually established, and what was not.

- [x] Dokploy redeployment notes identify the full recreation used to rerun the one-shot updater.
  - The operator performed a **full stop and start of the whole stack** on
    `play.xpfarm.org`, not a restart of the `minecraft` container alone. Per
    `ENVIRONMENT.md`, a full recreation reruns the one-shot updater init service before
    Minecraft startup; restarting only an already-running Minecraft container does not.
    The same procedure delivered `0.1.2` previously.
- [x] Updater completion, Minecraft startup, destination JAR, and stack/plugin logs were inspected.
  - **Inspected by the operator**, who has Dokploy access; this agent does not (no CLI, no
    credentials, no SSH host from this machine), so the skill's subagent log review had no
    input to run against and the finding below was relayed rather than read directly.
  - **Observed:** `[plugin-updater] Electric Furnace: already current (v0.2.0)`. This is
    the load-bearing confirmation — the one-shot updater **did** rerun on the operator's
    full stack recreation, evaluated this plugin's manifest entry, and resolved the JAR
    installed at the `electric-furnace.jar` destination on the `/minecraft` volume as
    already at `v0.2.0`. Destination JAR presence and version are therefore confirmed from
    the updater's own output, not inferred.
  - **Scope of what was relayed:** the Electric Furnace updater line. The `plugin-updater`
    container's numeric exit code, the updater-before-Minecraft ordering, and a
    plugin-by-plugin sweep of the Minecraft startup log for the other ten plugins were not
    separately quoted. Ordering is structurally guaranteed by the init-service design
    described in `ENVIRONMENT.md` rather than by direct observation here.
  - **Functional evidence corroborates independently.** The operator exercised
    continuous-operation behaviour on the live server on both Java and Bedrock: smelting
    over time, burn-time drain, input locking, and multi-viewer GUI use. **None of those
    behaviours exist in `0.1.2`**, which smelts instantaneously on click. Their working
    is positive proof that the loaded JAR is `0.2.0`, independent of any log read.
  - Production reachable and healthy at time of writing: `play.xpfarm.org` →
    `168.231.74.113`, TCP 25565 open, server list ping reports **Paper 26.1.2**
    (protocol 775). Server list ping does not expose the plugin list, so this confirms
    the server and Paper version only, not the plugin set.
- [x] No production plugin hot reload was used.
  - The deployment was a full stack recreation. `/electricfurnace reload` was exercised
    only against the disposable gate 7a stack, never production.

## 12. Handoff

**Deliberately held open, 2026-07-20. Not a missing step — a blocked one.**

`minecraft-plugin-handoff` writes to `xpfarm-plugin-toolkit/CURRENT_STATE.md`, and that
repository is mid-flight on another workstream: checked out on branch
`test/docker-rig-consolidation` with 5+ unmerged commits consolidating the shared docker
test rig, plus an untracked `docs/superpowers/plans/2026-07-19-console-dns.md` this pass
did not create. The skill's preflight halts on a dirty worktree it did not author, and its
concurrency rule forbids auto-merging two handoff passes into that single shared file.
Writing here would either entangle this plugin's handoff with unrelated rig work or risk
silently clobbering another pass's entry.

**Resume when `test/docker-rig-consolidation` merges to `main`**, then run
`minecraft-plugin-handoff` against a clean base.

Note for whoever picks this up: `CURRENT_STATE.md`'s Active Plugin Releases table still
lists Electric Furnace at **`v0.1.1`**, two releases stale, and its `Verified on` date is
`2026-07-19`. Everything gate 12 needs is already recorded in this checklist — gates 8/9
carry the release and CI evidence, gate 10 the updater enrollment, gate 11 the deployment
evidence including the operator's updater log line.

- [ ] Current-state documentation refreshed with release, CI, updater, deployment, and local pending state.
- [ ] Known limitations, skipped checks, configuration or migration notes, rollback guidance, and follow-up owner are recorded.
- [ ] Evidence distinguishes source commit, published tag/release, updater state, and deployed state without exposing secrets.

### Rollback guidance (recorded here now, since gate 12 could not record it centrally)

Reverting to the prior deployed state means pinning the manifest entry in
`carmelosantana/minecraft-plugin-updater`'s `plugins.json` back to `v0.1.2` and then
performing a **full stack recreation** — restarting the Minecraft container alone will not
roll back, for the same reason it would not have deployed forward.

**A rollback is not clean.** `0.2.0` persists machine contents and run-state in each
machine block's own PDC, which `0.1.2` neither writes nor reads. Rolling back strands any
items sitting in a machine's slots: `0.1.2` will not hydrate them, and its
`InventoryCloseEvent` handler returns only what is in a live GUI. Drain every machine
before rolling back. The reverse direction is safe — `0.2.0` reads a machine with no
persisted state as empty.

Config also changed: `machine.fuel-per-operation` was removed in favour of
`machine.burn-ticks-per-redstone`. A rolled-back `0.1.2` will ignore the new key and fall
back to its own default rather than failing.

Follow-up owner: Carmelo Santana.
