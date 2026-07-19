# ElectricFurnace — Implementation Plan

Executes [the design](../specs/2026-07-19-electric-furnace-design.md). Six tasks,
strictly sequential — each depends on the interfaces the previous one publishes.

Base package: `org.xpfarm.electricfurnace`

## Global Constraints

Binding on every task. Copy verbatim into every dispatch.

- **Java 25.** Paper API `26.1.2.build.74-stable`, `provided` scope. `api-version: '1.21'`.
- **Maven group `org.xpfarm`**, artifactId `electric-furnace`. Owner `carmelosantana`.
- **AGPL-3.0-or-later.** Every new `.java` file carries a short license header comment.
- **No external services.** No network calls of any kind. No Ollama, no Umami, no HTTP client.
- **Bedrock/Geyser safety — hard rules, researched and settled:**
  - NEVER use `setCustomModelData` or the `item_model` component. Invisible to Bedrock without an authored Bedrock pack.
  - NEVER use display entities (`BLOCK_DISPLAY`, `ITEM_DISPLAY`, `TEXT_DISPLAY`). Untranslated by Geyser.
  - NEVER use colored `DUST` particles. Color does not survive to Bedrock.
  - ONLY `Particle.ELECTRIC_SPARK` and `Particle.CAMPFIRE_COSY_SMOKE` are approved.
  - NEVER require typed chat input. Bedrock clients cannot reliably use Java chat prompts. All interaction is inventory clicks or commands.
  - Custom inventory titles ARE safe and expected.
  - Identify items by PersistentDataContainer ONLY. Never by display name or lore substring matching.
- **Balance ceiling:** alloy stats sit between iron and diamond. Never above netherite. Enforced in code, not just documented.
- **Tests are written with the code, in the same commit.** Not afterward. Any logic separable from the Bukkit runtime must be unit tested. JUnit 5 (`junit-jupiter`, already in the POM).
- **Run `mvn --batch-mode --no-transfer-progress clean verify` before reporting DONE.** Report the actual output.
- Do not add dependencies to `pom.xml` without saying so explicitly in the report.

---

## Task 1 — Configuration layer

**Goal:** Load and validate `config.yml` into typed, immutable objects. No Bukkit
runtime behavior beyond `ConfigurationSection` reads.

**Files:**
- `src/main/java/org/xpfarm/electricfurnace/config/EfConfig.java`
- `src/main/java/org/xpfarm/electricfurnace/config/MachineSettings.java`
- `src/main/java/org/xpfarm/electricfurnace/config/EffectSettings.java`
- `src/main/java/org/xpfarm/electricfurnace/config/RecyclingSettings.java`
- `src/main/java/org/xpfarm/electricfurnace/config/ConfigValidator.java`
- `src/main/resources/config.yml`
- `src/test/java/org/xpfarm/electricfurnace/config/ConfigValidatorTest.java`

**Requirements:**

Records (immutable, no setters):

```
MachineSettings(double smeltSpeedMultiplier, int fuelPerOperation,
                boolean requireRedstoneSignal, boolean statusBulbEnabled)
EffectSettings(boolean enabled, int periodTicks, int playerRadius, String sound)
RecyclingSettings(int slots, int yieldSameMetal, int yieldMixedAlloy,
                  int yieldRemeltAlloy, boolean acceptDamaged)
EfConfig(MachineSettings machine, EffectSettings effects, RecyclingSettings recycling)
```

Defaults and validated ranges — use these exact values:

| Key | Default | Valid range |
|---|---|---|
| `machine.smelt-speed-multiplier` | `2.0` | 1.0 – 10.0 |
| `machine.fuel-per-operation` | `1` | 1 – 64 |
| `machine.require-redstone-signal` | `true` | — |
| `machine.status-bulb.enabled` | `true` | — |
| `effects.enabled` | `true` | — |
| `effects.period-ticks` | `15` | 10 – 40 |
| `effects.player-radius` | `32` | 8 – 128 |
| `effects.sound` | `BLOCK_BEACON_AMBIENT` | must resolve via `Registry`/`Sound` lookup; unresolvable → warn, disable sound only |
| `recycling.slots` | `5` | 1 – 9 |
| `recycling.yield-same-metal` | `3` | 0 – 64 |
| `recycling.yield-mixed-alloy` | `2` | 0 – 64 |
| `recycling.yield-remelt-alloy` | `1` | 0 – 64 |
| `recycling.accept-damaged` | `true` | — |

**Validation contract:** an out-of-range or unparseable value logs a WARNING naming
the key, the offending value, and the substituted default — then falls back to the
default. It NEVER throws, NEVER disables the plugin, and NEVER fails startup. This
is the single most important behavior to test.

`ConfigValidator` must expose the clamping/fallback logic as pure static methods
taking primitives (e.g. `int clampInt(String key, int value, int min, int max, int fallback, Consumer<String> warn)`)
so it is unit-testable with no Bukkit types at all. `ConfigValidatorTest` must not
require a running server.

Write `config.yml` with every key above, each with an explanatory comment, and a
header comment stating the balance ceiling rule.

**Tests:** in-range passthrough; below-min clamp; above-max clamp; wrong type;
missing key; that the warning message names key, bad value, and substitute.

**Done when:** `mvn clean verify` green, all validator tests pass.

---

## Task 2 — Alloy model and the pure recycle resolver

**Goal:** The heart of the plugin. Given a list of inputs, decide what comes out.
**Entirely pure — no Bukkit types in `RecycleResolver` or its tests.**

**Files:**
- `src/main/java/org/xpfarm/electricfurnace/alloy/MetalType.java`
- `src/main/java/org/xpfarm/electricfurnace/alloy/AlloyDefinition.java`
- `src/main/java/org/xpfarm/electricfurnace/alloy/AlloyStats.java`
- `src/main/java/org/xpfarm/electricfurnace/alloy/AlloyRegistry.java`
- `src/main/java/org/xpfarm/electricfurnace/recycle/RecycleInput.java`
- `src/main/java/org/xpfarm/electricfurnace/recycle/RecycleResult.java`
- `src/main/java/org/xpfarm/electricfurnace/recycle/RecycleResolver.java`
- `src/test/java/org/xpfarm/electricfurnace/recycle/RecycleResolverTest.java`
- `src/test/java/org/xpfarm/electricfurnace/alloy/AlloyRegistryTest.java`

**Model:**

`MetalType` — enum: `IRON`, `GOLD`, `COPPER`, `NETHERITE`. Plus `COAL` as a
**modifier, not a metal** (see below).

`RecycleInput` — a record describing ONE input slot, deliberately free of Bukkit
types so the resolver stays pure:

```
RecycleInput(String materialId, MetalType metal, boolean isModifier,
             boolean isAlloy, String alloyId, int ingotValue)
```

`RecycleResult` — a sealed interface or record with a `Kind` enum:
`SAME_METAL` (yields N ingots of that metal), `NAMED_ALLOY` (yields N of a named
alloy), `GENERIC_ALLOY`, `REMELT` (alloy in → ingot out), `REJECTED` (with a reason
string).

**Resolution rules — implement exactly, in this precedence order:**

1. **Empty input** → `REJECTED("empty")`.
2. **Single alloy item** → `REMELT`, yielding `yieldRemeltAlloy` ingots. This is the
   ONLY case that accepts fewer than the configured slot count.
3. **Fewer than `recycling.slots` non-modifier items** → `REJECTED("needs N items")`.
4. **Any input that is neither a metal nor a modifier** → `REJECTED("non-metal input")`.
5. **Only modifiers, no metals** (e.g. 5 coal) → `REJECTED("no metal")`. Coal alone
   is never recyclable.
6. **All metals identical AND no modifiers present** → `SAME_METAL`, yielding
   `yieldSameMetal` ingots of that metal.
7. **Input matches a named alloy recipe** → `NAMED_ALLOY`, yielding
   `yieldMixedAlloy` of that alloy.
8. **Otherwise (mixed metals, unrecognized combination)** → `GENERIC_ALLOY`,
   yielding `yieldMixedAlloy` of `fused_alloy`.

Note rule 6 vs. 7 carefully: **4 iron + 1 coal is NOT "all same metal"** — the
modifier's presence routes it to the named-recipe check, where it matches Steel.
That interaction is the subtlest part of this task and must be directly tested.

**Named alloy matching** is by multiset of `(metal or modifier)` ids, order
independent. `AlloyDefinition` carries `id`, `displayName`, `lore`, `color`,
`Set<String> inputIds` (the distinct ids required), and `AlloyStats`.

Shipping recipes (these go in `config.yml` under `alloys:`):

| id | displayName | Required distinct inputs |
|---|---|---|
| `steel` | Steel | iron, coal |
| `rose_gold` | Rose Gold | copper, gold |
| `ferrocopper` | Ferrocopper | copper, iron |
| `electrum_steel` | Electrum Steel | gold, iron |
| `fused_alloy` | Fused Alloy | *(fallback, matches nothing explicitly)* |

**`AlloyStats`** — record of `(double attackDamage, double attackSpeed, int armor,
double armorToughness, int maxDurability, int enchantability)`.

**Balance ceiling enforcement, in `AlloyRegistry`:** on load, any stat exceeding the
netherite reference is clamped to the diamond reference and logs a WARNING naming
the alloy, the stat, the configured value, and the clamp. Reference constants to
define and use:

```
IRON:      damage 6.0,  armor 15, toughness 0.0, durability 250
DIAMOND:   damage 7.0,  armor 20, toughness 2.0, durability 1561
NETHERITE: damage 8.0,  armor 20, toughness 3.0, durability 2031
```

**Tests — `RecycleResolverTest` must cover every numbered rule above, plus:**
- 5 iron → 3 iron ingots (the exact default yield)
- 5 mixed metals → 2 generic Fused Alloy
- 4 iron + 1 coal → 2 Steel (named match, and NOT same-metal)
- 5 coal → REJECTED (no metal)
- 1 alloy → 1 ingot (remelt)
- 4 items → REJECTED (below slot count)
- damaged vs. undamaged inputs produce identical results
- yields track config, not hardcoded numbers (run the resolver with non-default config)

`AlloyRegistryTest`: recipe matching is order-independent; unknown mix falls back to
`fused_alloy`; a stat above netherite is clamped and warned.

**Done when:** `mvn clean verify` green; `RecycleResolverTest` imports nothing from
`org.bukkit`.

---

## Task 3 — Item layer and the cross-plugin PDC contract

**Goal:** Mint and recognize the machine item and alloy ingots.

**Files:**
- `src/main/java/org/xpfarm/electricfurnace/item/MaterialContract.java`
- `src/main/java/org/xpfarm/electricfurnace/item/AlloyItemFactory.java`
- `src/main/java/org/xpfarm/electricfurnace/item/MachineItemFactory.java`
- `src/main/java/org/xpfarm/electricfurnace/item/MetalClassifier.java`
- `src/test/java/org/xpfarm/electricfurnace/item/MetalClassifierTest.java`

**`MaterialContract`** — the shared cross-plugin namespace. Keys:

```
xpfarm:custom_material  (STRING)  — owning system, e.g. "electricfurnace"
xpfarm:material_id      (STRING)  — specific material, e.g. "steel"
electricfurnace:machine (BYTE)    — marks the machine item
```

Build these with `new NamespacedKey("xpfarm", "custom_material")` — the **String
constructor**, deliberately, so no plugin instance is needed and other plugins can
read them without a jar dependency. Expose read helpers that take an `ItemStack`
and return `Optional<String>`.

`MaterialContract` must also expose a read-only helper for CopperKingdom's keys
(`copperkingdom:copper_armor`, `copperkingdom:copper_weapon`, both STRING) so the
recycler recognizes copper gear. Read only — never write to a foreign namespace.

**`AlloyItemFactory`** — builds an alloy `ItemStack`: base `Material.NETHERITE_INGOT`,
display name and lore from `AlloyDefinition`, stamped with both `xpfarm:` keys.
**No custom model data.** Names/lore use Adventure `Component` with
`.decoration(TextDecoration.ITALIC, false)`.

**`MachineItemFactory`** — builds the machine item: base `Material.BLAST_FURNACE`,
named "Electric Furnace", lore explaining the redstone requirement, stamped with
`electricfurnace:machine`.

**`MetalClassifier`** — maps a `Material` to `Optional<MetalType>` plus an ingot
value, and identifies modifiers. This is where Bukkit `Material` meets Task 2's pure
model, converting an `ItemStack` into a `RecycleInput`.

Classify as metal: ingots, nuggets (value < 1 → treat nuggets as non-recyclable,
reject), raw metals, and **gear** — iron/gold/copper/netherite/chainmail tools,
weapons, and armor. Chainmail counts as IRON. Coal and charcoal are the modifiers.
An alloy item (identified by PDC) classifies as `isAlloy`.

Keep the `Material` → `MetalType` mapping in a static table so
`MetalClassifierTest` can assert it exhaustively without a server.

**Tests:** every metal family maps correctly; chainmail → IRON; coal and charcoal →
modifier; dirt/stick → empty; an alloy-stamped stack → `isAlloy`.

**Done when:** `mvn clean verify` green.

---

## Task 4 — Machine persistence

**Goal:** Remember which block locations are Electric Furnaces, across restarts.

**Files:**
- `src/main/java/org/xpfarm/electricfurnace/machine/MachineRegistry.java`
- `src/main/java/org/xpfarm/electricfurnace/machine/MachineKey.java`
- `src/test/java/org/xpfarm/electricfurnace/machine/MachineKeyTest.java`

Store machine locations in the **chunk's** `PersistentDataContainer` under
`electricfurnace:machines`, as a `STRING` of comma-separated chunk-relative
coordinates (`"x:y:z"`, x and z 0–15). `MachineKey` handles encode/decode and is
pure — that is what `MachineKeyTest` covers (round-trip, malformed input ignored
rather than thrown, out-of-range rejected, empty string → empty set).

`MachineRegistry` API:

```
boolean isMachine(Block)
void register(Block)
void unregister(Block)
Set<Block> machinesIn(Chunk)
```

Malformed persisted data must be skipped with a warning, never thrown — a corrupt
chunk must not break chunk loading.

**Done when:** `mvn clean verify` green; `MachineKeyTest` passes without a server.

---

## Task 5 — GUI and machine listeners

**Goal:** The player-facing behavior.

**Files:**
- `src/main/java/org/xpfarm/electricfurnace/gui/FurnaceGui.java`
- `src/main/java/org/xpfarm/electricfurnace/gui/GuiLayout.java`
- `src/main/java/org/xpfarm/electricfurnace/listener/MachineBlockListener.java`
- `src/main/java/org/xpfarm/electricfurnace/listener/MachineGuiListener.java`
- `src/main/java/org/xpfarm/electricfurnace/listener/RedstoneListener.java`

**GUI** — a 27-slot (3-row) plugin-owned `Inventory` with the title
`Electric Furnace` (custom titles are Bedrock-safe). Layout:

- Slots 10–14: the five recycler inputs
- Slot 3: redstone fuel slot
- Slot 16: output slot
- Slot 22: status indicator — a non-interactive item whose material and name reflect
  state (unpowered / no fuel / running). Use `REDSTONE_TORCH` (running),
  `LEVER` (no signal), `REDSTONE` (no fuel).
- All other slots: a filler pane, non-interactive

Keep this layout in `GuiLayout` as named constants. Do not scatter slot indices.

**Slot guards, in `MachineGuiListener`:** cancel clicks on filler, the indicator, and
any attempt to *place* into the output slot. Output may be taken, never inserted.
Handle shift-click and number-key swaps — they route to the same guard. **On
`InventoryCloseEvent`, return all input and fuel items to the player**, dropping at
the player's location if the inventory is full. Nothing is ever silently destroyed.

**`MachineBlockListener`:**
- `BlockPlaceEvent`: if the item carries `electricfurnace:machine`, register the
  block. Requires `electricfurnace.use`.
- `BlockBreakEvent`: if registered, unregister and drop the machine item instead of a
  plain blast furnace. Cancel the vanilla drop.
- `PlayerInteractEvent` (right-click on a registered block): **cancel the event** so
  the native blast furnace GUI never opens, then open `FurnaceGui`. Requires
  `electricfurnace.use`; deny with a message otherwise.

**`RedstoneListener`** (`BlockRedstoneEvent`): track powered state for adjacent
registered machines. When `machine.status-bulb.enabled`, drive an adjacent
`COPPER_BULB` via `CopperBulb#setLit` + `Block#setBlockData`.

**Processing:** an operation runs only when powered (unless
`require-redstone-signal` is false) AND fuel is present. On success consume
`fuelPerOperation` redstone and place output in the output slot. If the output slot
is occupied by a different item, do not run and do not consume fuel.

**Bedrock:** every interaction here is inventory clicks. No chat prompts, no
Java-only input. Verify nothing in this task uses display entities or custom model
data.

**Done when:** `mvn clean verify` green. Runtime behavior is verified in gate 7a, not
here — but the code must compile and any separable logic (slot guard decisions,
`GuiLayout` index math) must be unit tested.

---

## Task 6 — Effects, command, and plugin wiring

**Goal:** Make it run, and make it feel electric.

**Files:**
- `src/main/java/org/xpfarm/electricfurnace/ElectricFurnacePlugin.java`
- `src/main/java/org/xpfarm/electricfurnace/effect/MachineEffects.java`
- `src/main/java/org/xpfarm/electricfurnace/command/ElectricFurnaceCommand.java`
- `src/main/java/org/xpfarm/electricfurnace/recipe/MachineRecipe.java`
- `src/test/java/org/xpfarm/electricfurnace/command/CommandArgsTest.java`

**`MachineEffects` — performance is a hard requirement, not a preference.** ONE
global `BukkitRunnable` on `effects.period-ticks` (default 15). Each tick it
iterates ONLY currently-active machines in loaded chunks. For each, skip entirely if
`world.getNearbyPlayers(loc, effects.player-radius)` is empty. Emit
`Particle.ELECTRIC_SPARK` and `Particle.CAMPFIRE_COSY_SMOKE`, and play
`effects.sound`. Use the **per-player** `spawnParticle` overloads rather than
broadcasting. There must be no per-block scheduled task anywhere — one task total for
the whole server.

**`ElectricFurnaceCommand`** — `/electricfurnace <give|alloy|reload|info>` with tab
completion.

| Sub | Args | Permission |
|---|---|---|
| `give` | `[player] [amount]` | `electricfurnace.give` |
| `alloy` | `<id> [amount]` | `electricfurnace.give` |
| `reload` | — | `electricfurnace.reload` |
| `info` | — | `electricfurnace.use` |

`reload` re-reads config and re-applies it live — including restarting the effects
task with the new period — without a server restart. `info` prints the alloy recipe
table and current yields.

Argument parsing (permission resolution, amount parsing and bounds, unknown
subcommand handling) goes in a pure helper so `CommandArgsTest` can cover it without
a server: unknown subcommand, missing required arg, non-numeric amount, amount ≤ 0,
amount > 64.

**`MachineRecipe`** — register the shaped crafting recipe for the machine item.
Recipe (configurable later, hardcoded default acceptable for v1):

```
C C C     C = COPPER_INGOT
R F R     R = REDSTONE_BLOCK
C C C     F = BLAST_FURNACE
```

**`ElectricFurnacePlugin`** — `onEnable`: load and validate config, build the alloy
registry, register listeners, register the command, register the recipe, start the
effects task. `onDisable`: cancel the effects task, close any open GUIs returning
items to players. Startup must never throw — a bad config degrades to defaults with
warnings.

**Done when:** `mvn clean verify` green; the shaded JAR's embedded `plugin.yml` shows
version `0.1.0`, main `org.xpfarm.electricfurnace.ElectricFurnacePlugin`, the
command, and all four permissions.
