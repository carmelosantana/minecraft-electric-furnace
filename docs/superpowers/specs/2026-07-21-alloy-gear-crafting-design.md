# ElectricFurnace — Alloy Gear Crafting Design

Date: 2026-07-21
Status: approved
Delivers the "alloy armor and weapons deferred to v2" limitation recorded in
`docs/PLUGIN_CHECKLIST.md` §1. Builds on `2026-07-19-electric-furnace-design.md`
(alloy model, balance ceiling, PDC contract); does not supersede it.

## Problem

`AlloyStats` (`alloy/AlloyStats.java`) declares six stats — `attackDamage`,
`attackSpeed`, `armor`, `armorToughness`, `maxDurability`, `enchantability` — and
its own javadoc hedges every one of them with "if this alloy is ever used in a
weapon" / "if this alloy is ever used in armor". Nothing ever is. Today the only
code that reads these numbers is `AlloyRegistry.clampStats`, which range-checks
values that then go unused. Five alloys ship in `config.yml` with fully specified
stat blocks that do nothing.

So a player smelts five mixed metals, receives a Steel ingot whose lore promises
it is "stronger than iron, cheaper than netherite", and can do precisely nothing
with it except remelt it back into itself. The recycling loop terminates in an
item with no sink.

This design gives those numbers a destination: 30 craftable items — sword, axe,
helmet, chestplate, leggings, boots × 5 alloys — whose stats are derived from the
alloy's existing stat block.

## Scope

**In:** 30 gear items, their crafting-table recipes, per-piece stat derivation,
recycling alloy gear back into ingots, and a `piece` argument on
`/electricfurnace alloy`.

**Out, deliberately:**

- **Tools** (pickaxe, shovel, hoe). `AlloyStats` has no mining-speed or harvest-tier
  field, and adding one means deciding for each alloy whether it mines obsidian —
  a balance question, not an implementation one.
- **Spears.** `IRON_SPEAR`/`COPPER_SPEAR`/`NETHERITE_SPEAR` exist in this version
  and are already in `MetalClassifier.METAL_TABLE`, so a spear is cheap to add
  later. Throwing and reach interact with attack-speed modifiers in ways worth
  verifying on their own rather than folding into this pass.
- **Custom textures.** Unchanged constraint from v1 — no resource pack exists, so
  `custom_model_data` and `item_model` remain unusable. Visual identity comes from
  base material choice (below) instead.

## Decision 1: one distinct vanilla base material per alloy

With no resource pack, five alloys built on one base are five visually identical
swords distinguishable only by hovering. Exactly five vanilla tiers have **both**
weapon and armor forms — chainmail and leather have no sword — and there are
exactly five alloys:

| Alloy | Base | Rationale | Inherited behaviour |
|---|---|---|---|
| `steel` | iron | carbon-hardened iron | none — inert |
| `rose_gold` | gold | copper-gold blend | **piglin neutrality** |
| `ferrocopper` | copper | copper toughened with iron | none — inert |
| `electrum_steel` | diamond | strongest alloy; distinct texture | none — inert |
| `fused_alloy` | netherite | `#4B4B4B` reads as netherite | fire immunity, knockback resistance |

This buys real visual distinction on **both** editions for free, using textures
that already exist client-side.

Two inherited behaviours need handling:

- **Netherite knockback resistance** (0.1/piece) disappears at no cost. It lives in
  `attribute_modifiers`, which has an implicit item-type default that is *replaced*
  wholesale the moment the component is written explicitly — and this design writes
  it for every piece anyway. Simply omitting `knockback_resistance` removes it.
  Do **not** write an empty modifier list, which would strip armor points too.
- **Netherite fire immunity** rides on the `minecraft:damage_resistant` component
  (renamed from `fire_resistant` in 1.21.2). Strip per item with
  `stack.unsetData(DataComponentTypes.DAMAGE_RESISTANT)`. Not `resetData`, which
  restores the item type's default and puts immunity back.
- **Gold's piglin neutrality cannot be stripped.** It is driven by the
  `#minecraft:piglin_safe_armor` **item tag**, not a component; no per-item lever
  exists. Accepted as flavour — Rose Gold is a gold alloy, and piglins reading it
  as gold is defensible. Recorded as a known limitation, not fixed.

### Alloy ingots stay on `NETHERITE_INGOT`

Deliberately **not** changed to match each alloy's gear base. `NETHERITE_INGOT` has
essentially no vanilla crafting recipes, so this design's recipe shapes collide with
nothing. Moving Steel ingots onto an `IRON_INGOT` base would place stamped ingots
into genuine vanilla recipe shapes: two iron ingots and a stick is the vanilla iron
sword recipe, whose ingredient is a type/tag match that a stamped ingot satisfies.
Both recipes would then match the same grid, and which one wins depends on recipe
iteration order. Keeping ingots on netherite avoids the ambiguity entirely.

## Decision 2: derive per-piece stats from the single stat block

`AlloyStats.armor` is a **full-set total**, not a per-piece value — confirmed by
`AlloyRegistry`'s own reference constants, `IRON_ARMOR = 15` and `DIAMOND_ARMOR = 20`,
which are vanilla set totals. Six items must come out of one stat block.

Operators tune one five-line block per alloy rather than twenty-four values, and the
five shipped alloys work unchanged with no config migration.

### Armor points

Split the configured total across pieces using vanilla's `3/8/6/3` diamond-armor
shape, with **largest-remainder rounding** and a fixed tiebreak order
(chest → legs → helmet → boots) applied when fractional parts tie.

The tiebreak is not decoration: it is what guarantees the four pieces sum to
*exactly* the configured total, and what makes the result deterministic instead of
dependent on map iteration order.

Worked example, Steel (`armor: 16`):

```
raw:        16 × 3/20 = 2.4   16 × 8/20 = 6.4   16 × 6/20 = 4.8   16 × 3/20 = 2.4
floor:      2                 6                 4                 2                = 14
remainder:  2 points to distribute
fracs:      .4                .4                .8                .4
rank:       legs (.8), then chest (.4, wins tiebreak over helmet/boots)
final:      helmet 2          chest 7           legs 5            boots 2          = 16 ✓
```

### Durability

`AlloyStats.maxDurability` is **tool-scale** — `IRON_MAX_DURABILITY = 250` and
`DIAMOND_MAX_DURABILITY = 1561` are vanilla sword/tool durabilities. Vanilla armor
uses a separate base unit (iron 15, diamond 33, netherite 37) multiplied by per-slot
factors `11/16/15/13`. The two scales are not proportional (250/15 ≈ 16.7 vs
1561/33 ≈ 47.3), so a flat ratio is wrong.

Interpolate the alloy's position between the iron and diamond **tool** references
onto the armor base unit, clamped to `[15, 37]`:

```
position   = (maxDurability − 250) / (1561 − 250)
armorBase  = round(15 + position × (33 − 15))
piece      = armorBase × slotFactor
```

Worked example, Steel (`max-durability: 700`):

```
position  = (700 − 250) / 1311 = 0.343
armorBase = round(15 + 0.343 × 18) = 21
helmet 21×11 = 231   chest 21×16 = 336   legs 21×15 = 315   boots 21×13 = 273
```

Sits between iron (165/240/225/195) and diamond (363/528/495/429), as intended.
Weapons use `maxDurability` directly, unscaled.

### Remaining stats

- **Armor toughness** — per piece, as in vanilla (diamond grants 2.0 on each piece,
  not 2.0 split four ways). The configured value is used verbatim per armor piece.
- **Attack damage / speed** — the sword uses the configured values directly. The axe
  applies a fixed delta of **+2.0 damage, −0.6 attack speed**, which is vanilla's
  own sword→axe relationship at the diamond and netherite tiers (diamond sword 7/1.6
  → axe 9/1.0; netherite sword 8/1.6 → axe 10/1.0). Iron's relationship is steeper
  (+3.0/−0.7); the diamond figure is used for every alloy so the axe delta does not
  vary by tier.

  **The derived axe damage is deliberately not re-clamped, and may exceed
  `NETHERITE_ATTACK_DAMAGE`.** Electrum Steel's `6.8` yields an `8.8` axe. This is
  correct, not a ceiling violation: the reference constants are *sword*-scale, and
  vanilla's own netherite axe does 10.0 against a netherite sword's 8.0. An 8.8 alloy
  axe therefore still sits below the vanilla netherite axe it is measured against.
  Clamping the axe to 8.0 would make every alloy axe *worse* than its own sword is
  allowed to be, inverting the vanilla relationship. The ceiling constrains the
  configured stat block; it is not a per-item cap.
- **Enchantability** — passed through unchanged to all six pieces.

### The balance ceiling needs no new code

`AlloyRegistry.clampStats` runs on load, before any derivation. Gear inherits clamped
values automatically. There is deliberately **no** second clamp on derived values and
no per-piece override mechanism — an override path would be a way for an operator to
defeat a ceiling that `config.yml` documents as unconditional.

## Decision 3: crafting table with `ExactChoice`, plus a `PrepareItemCraftEvent` backstop

Vanilla recipes match on `Material`, so a plain shaped recipe for a Steel Sword would
also accept real netherite ingots. `RecipeChoice.ExactChoice` is the fix.

### Recipe shapes

Unchanged from vanilla, with the alloy ingot substituted for the metal. Sticks stay
vanilla sticks — they are not alloy-specific and use an ordinary material choice.

| Piece | Alloy ingots | Sticks |
|---|---|---|
| Sword | 2 | 1 |
| Axe | 3 | 2 |
| Helmet | 5 | — |
| Chestplate | 8 | — |
| Leggings | 7 | — |
| Boots | 4 | — |

A full set plus both weapons costs 29 ingots. At the shipped `yield-mixed-alloy: 2`,
that is ~15 recycler operations, or ~75 pieces of scrap gear — deliberately a
long-term sink rather than a quick upgrade.

### What was verified

`Bukkit.addRecipe` converts an `ExactChoice` into an NMS `Ingredient` holding an
`ItemStackLinkedSet.createTypeAndComponentsSet()`, and matching is a set lookup
resolving to `ItemStack.isSameItemSameComponents`. That compares the item holder plus
the entire `DataComponentPatch`. PDC is stored in `minecraft:custom_data`, so it *is*
compared, and a vanilla netherite ingot does **not** satisfy a recipe built from a
stamped one. Count is not compared.

Three caveats that shape the implementation:

- **Display name and lore are part of the comparison.** An anvil-renamed alloy ingot
  no longer matches its own recipes. Recorded as a known limitation.
- **Choices are snapshotted at registration.** Any later change to how
  `AlloyItemFactory` builds an ingot — an added lore line, a reordered PDC write —
  silently stops the recipe matching, with no error. `AlloyItemFactory` output is
  therefore the single source for both minting and recipe registration; recipes must
  never construct their own ingredient stacks.
- **`RecipeChoice.ExactChoice#test` is not the matcher.** The Bukkit-facing `test` is
  implemented with `ItemStack#isSimilar`; the server's real match uses
  `isSameItemSameComponents`. They usually agree. Tests must not use `test` as a
  proxy for "will this craft succeed."

### Recipes are not auto-discovered

Since 1.21.2 the server sends only recipes the player has unlocked, and
`Bukkit.addRecipe` does not unlock anything. Without `Player#discoverRecipe`, the
recipe book is empty on **both** editions. Discovery fires on `PlayerJoinEvent`.

### Bedrock: the constraint that shapes this decision

Bedrock's recipe ingredient wire format has **no NBT field at all**. The complete
descriptor set is `DEFAULT, MOLANG, ITEM_TAG, DEFERRED, COMPLEX_ALIAS`, and `DEFAULT`
is `(itemDefinition, auxValue)`. This is a client limitation, not a Geyser gap —
GeyserMC closed the equivalent smithing request (#4706) as *Can't Fix / Missing
Client Feature*.

Because every alloy ingot is a `NETHERITE_INGOT`, all 30 recipes reach a Bedrock
client identically, as "netherite_ingot ×N + stick".

What follows:

- **Manual placement works.** Geyser watches the Java result slot and, finding no
  client-side match, synthesizes a one-off Bedrock recipe from the exact stacks in
  the grid, dispatched on a 150 ms delay during which grid slots blank and re-send.
  The Java server does the real matching, so the result is always correct.
- **Phantom outputs appear.** A Bedrock player laying out *vanilla* netherite ingots
  in one of these shapes gets a client-side match against the type-only recipe and a
  fake output. Clicking sends a craft request the server rejects — the documented
  "item sticks to cursor, then drops" symptom (Geyser #1571).

`PrepareItemCraftEvent` nulls the result whenever the grid's ingredients are not
genuinely PDC-stamped. The phantom then fails **safely**: the player receives
nothing rather than a free item. This is a backstop, not the primary matcher —
`ExactChoice` already rejects on Java; the handler exists so the Bedrock false-positive
path cannot become an item duplication path.

### Alternatives rejected

- **A plugin-owned crafting GUI** avoids the flicker and phantoms entirely, but adds
  a whole second way to craft — its own inventory, slot guards, and test matrix — to
  solve a problem whose failure mode is already "nothing happens". Deferred; it can
  be layered on if gate 7a shows the flicker is unusable in play.
- **Smithing table** is strictly worse: Geyser #4706 is closed *Can't Fix* precisely
  because Bedrock cannot place a custom smithing ingredient at all. Not viable.

## Decision 4: alloy gear remelts, and rule 2 is relaxed

`MetalClassifier.resolveBranch` already puts `isAlloyStamped` ahead of every other
branch, so alloy gear on an `IRON_SWORD` base already classifies as an alloy remelt
rather than as iron. **No precedence change is needed** — the existing ordering,
written for CopperKingdom's iron-based copper weapons, covers this case correctly.

`RecycleResolver` rule 2 does need to change. Today it remelts only a *single* alloy
item; two or more fall through to rule 4 and reject as `"non-metal input"`. A player
recycling a four-piece alloy armor set therefore gets a message that is not merely
unhelpful but wrong — the inputs are neither non-metal nor unrecognized.

Rule 2 becomes: **all** inputs are alloys.

- All sharing one alloy id → `Remelt(id, n × yieldRemeltAlloy)`.
- Mixed alloy ids → `Rejected("mixed alloys")`.

It stays ahead of the slot-count rule, since remelt remains the one path that accepts
fewer than `slots` items. Rules 3–8 are untouched.

## Architecture

One new package, `org.xpfarm.electricfurnace.gear`, following the split this codebase
already uses: a pure core with no Bukkit types, and thin Bukkit-facing glue.

**Pure — no `org.bukkit` import, no running server:**

| Type | Responsibility |
|---|---|
| `GearPiece` | Enum of the six pieces. Carries equipment slot group, armor-share numerator (`3/8/6/3`), durability factor (`11/16/15/13`), and recipe shape. |
| `GearStats` | The derived per-piece stat block. |
| `GearStatsDeriver` | `derive(AlloyStats, GearPiece) → GearStats`. The armor split and durability interpolation live here. |

**Bukkit-facing — thin glue, exercised at runtime:**

| Type | Responsibility |
|---|---|
| `GearItemFactory` | Mints the `ItemStack`, alongside the existing `AlloyItemFactory`. |
| `GearRecipes` | Registers and unregisters the 30 `ShapedRecipe`s. |
| `GearCraftListener` | `PrepareItemCraftEvent` backstop; `discoverRecipe` on join. |

This mirrors `MetalClassifier`'s existing two-layer structure, and for the same
reason: constructing a real `ItemStack` requires a live server, so every decision
worth testing exhaustively is pushed into types that need no server at all.

### Data flow

```
config.yml alloys.*
  → AlloyRegistry (parses, balance-clamps AlloyStats)
      → GearStatsDeriver (per piece)  → GearItemFactory → ItemStack
      → GearRecipes (ExactChoice built from AlloyItemFactory output)
```

### PDC contract

Gear carries the existing shared keys — `xpfarm:custom_material` =
`"electricfurnace"` and `xpfarm:material_id` = the alloy id — plus one new key:

- `xpfarm:gear_piece` (STRING): the `GearPiece` id, e.g. `"chestplate"`.

The two existing keys are what make gear classify as an alloy remelt with no
recycler change. The new key exists so gear is distinguishable from an ingot without
inspecting base material, which varies per alloy.

## Implementation notes

These are the specific ways this feature can be built wrong. Each was verified
against the API surface of this Paper version.

- **`Attribute` is not an enum on this version.** It became an interface in 1.21.3.
  `Attribute.GENERIC_ATTACK_DAMAGE` throws `NoSuchFieldError`; use
  `Attribute.ATTACK_DAMAGE`, `ATTACK_SPEED`, `ARMOR`, `ARMOR_TOUGHNESS`. Consequently
  `EnumMap<Attribute, …>`, `EnumSet`, `Attribute.values()`, and `switch` on
  `Attribute` all fail to compile or throw. Any per-attribute table must key on
  something else.
- **Attribute modifiers replace vanilla defaults, they do not merge.** Adding
  `+2 ATTACK_DAMAGE` to a netherite sword yields a sword with 2 total, not 10 — base
  damage and attack speed are dropped. `GearItemFactory` must recover the defaults via
  `ItemType#getDefaultAttributeModifiers()` and write the merged set explicitly.
- **`/electricfurnace reload` must unregister first.** `Bukkit.removeRecipe` for each
  of the 30 `NamespacedKey`s before re-adding, or reload accumulates stale recipes
  built from the previous config's ingot appearance — which, per the snapshot caveat
  above, then match nothing.
- **`COPPER_*` materials need a guard.** They exist only from 1.21.9. A direct
  `Material.COPPER_SWORD` reference throws `NoSuchFieldError` at class-load on an
  older server. Gate on `Material.getMaterial("COPPER_SWORD") != null` and fall back
  to the iron base with a warning.
- **`setMaxDamage` is on `Damageable`, not `ItemMeta`.** Also mutually exclusive with
  a max stack size above 1 — not a concern for gear, which stacks to 1.

### Configuration

One new optional key per alloy:

```yaml
alloys:
  steel:
    base: iron        # one of: copper, iron, gold, diamond, netherite
```

Defaults to the mapping in Decision 1. An unrecognized value warns naming the alloy,
the key, the offending value, and the default it fell back to — matching the existing
convention that a typo degrades to defaults and never stops the plugin from starting.

A malformed `base` skips nothing: gear for that alloy is still registered, on the
default base. A failure to register one alloy's recipes skips **that alloy's gear
only**, keeping the other four — mirroring how a single malformed alloy entry is
already skipped rather than discarding the whole config.

There is deliberately no `gear.enabled` master switch. Nobody has asked for one, and
an operator who wants no alloy gear can already remove the alloys.

### Command

`/electricfurnace alloy <id> [piece] [amount]` — `piece` is optional; omitted yields
an ingot as today. `ElectricFurnaceCommand.ParseResult` gains a `piece` field.
Permissions are unchanged: `electricfurnace.give` (op) gates issuing,
`electricfurnace.craft` (default true) gates crafting. **No new permission node.**

`/electricfurnace info` gains a line per alloy naming its base material, so an
operator can see the mapping without reading config.

## Acceptance checks

1. Crafting a Steel Sword from 2 Steel ingots + 1 stick yields an iron-base sword
   named "Steel Sword" carrying all three PDC keys.
2. The same shape with **vanilla** netherite ingots yields nothing — no output, no
   error, no item.
3. Each alloy's four armor pieces' armor points sum to exactly the configured
   `armor` total. Steel → 2/7/5/2 = 16.
4. Steel armor durability derives to 231/336/315/273; Steel weapons to 700.
5. An alloy with stats above netherite is clamped by `AlloyRegistry` *before*
   derivation, and its gear reflects the clamped values.
6. A Fused Alloy sword dropped in lava burns — fire immunity is stripped from the
   netherite base.
7. No alloy armor grants knockback resistance, including Fused Alloy.
8. Recycling 4 Steel armor pieces yields `4 × yield-remelt-alloy` Steel ingots.
9. Recycling a Steel sword and a Rose Gold sword together rejects with
   `"mixed alloys"`, not `"non-metal input"`.
10. `/electricfurnace reload` leaves exactly 30 registered recipes, not 60.
11. All 30 recipes appear in the Java recipe book after joining.
12. A Bedrock client can craft each of the six Steel pieces by manual placement.
13. `GearStatsDeriver` unit tests pass for all five alloys × six pieces, plus the
    rounding-tiebreak and interpolation-endpoint cases.

## Known limitations

- **Enchantability is Java-only.** `ItemMeta#setEnchantable` is stable API and works
  on Java, but Geyser reads enchantability from the item **type** during handshake
  registration, never per stack. A Bedrock player sees the base material's
  enchantability. Whether Bedrock refuses to *place* an item its own definition marks
  non-enchantable is unresolved and is a gate 7a check.
- **Max durability is Java-only in display.** Geyser derives the durability bar from
  the item type's vanilla maximum. Durability *loss* is server-authoritative and
  correct; only the bar's scale is wrong on Bedrock. A Steel Sword shows a 700-point
  bar on Java and a 2031-point bar on Bedrock while losing durability identically.
- **Attack speed has essentially no Bedrock feedback.** Bedrock still uses 1.8
  combat; Geyser offers only a cosmetic cooldown indicator. The modifier changes
  server-side damage scaling with almost no client signal.
- **Attribute tooltips on Bedrock are synthesized lore and are buggy for tools.**
  Geyser renders Java modifiers as lore. Geyser #5037 reports tools showing base-item
  defaults while armor displays correctly. Real damage and armor are correct — combat
  is server-side — so this is cosmetic.
- **Rose Gold gear makes piglins neutral.** Driven by the `#minecraft:piglin_safe_armor`
  item tag; no per-item lever exists. Accepted as flavour.
- **Anvil-renaming an alloy ingot breaks its recipes.** `ExactChoice` compares display
  name and lore alongside PDC.
- **Bedrock players see a ~150 ms flicker when crafting**, and can trigger a phantom
  output with vanilla netherite ingots. The phantom yields nothing when clicked. Exact
  presentation is unresolved and is a gate 7a check.
- **Tools and spears are not implemented.** See Scope.

No gates are withheld. Status remains `active`.
