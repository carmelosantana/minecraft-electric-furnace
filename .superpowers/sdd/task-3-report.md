# Task 3 — Item layer and the cross-plugin PDC contract — Report

## What was implemented

Followed TDD: wrote `MetalClassifierTest` first (confirmed it failed to compile with
no implementation present), then implemented the four production classes.

- **`MaterialContract`** — the shared `xpfarm:` PDC namespace. `CUSTOM_MATERIAL`
  (`xpfarm:custom_material`, STRING), `MATERIAL_ID` (`xpfarm:material_id`, STRING),
  and `MACHINE` (`electricfurnace:machine`, BYTE), all built with the
  `NamespacedKey(String, String)` constructor as required. Read helpers
  (`readCustomMaterial`, `readMaterialId`, `isMachine`) return `Optional<String>`/
  `boolean`. Also exposes read-only recognition of CopperKingdom's foreign keys
  (`copperkingdom:copper_armor`, `copperkingdom:copper_weapon`, both STRING) —
  never written to.

- **`MetalClassifier`** — the Bukkit↔pure-model bridge, split into two layers:
  - A pure core (`metalOf(Material)`, `isModifier(Material)`, and a primitive overload
    `classify(Material, String materialId, boolean damaged, boolean isAlloyStamped,
    String alloyId, RecyclingSettings)`) touching nothing but `Material` and plain
    values.
  - `classify(ItemStack, RecyclingSettings)`, the thin Bukkit-facing overload that
    reads type, PDC (`xpfarm:` keys, then CopperKingdom's keys as a fallback for
    copper gear), and durability (`Damageable#hasDamage()`), then delegates to the
    pure core.

  The static `METAL_TABLE` (package-private, exhaustively asserted by the test) maps:
  IRON (ingot, raw, sword/spear/pickaxe/axe/shovel/hoe, helmet/chestplate/leggings/
  boots, plus all four chainmail pieces), GOLD, COPPER, and NETHERITE the same way
  (no raw/chainmail equivalent for netherite). Nuggets (iron/gold/copper — no
  netherite nugget exists) are explicitly rejected as sub-ingot value. Coal and
  charcoal are both modifiers sharing the same named-alloy id `"coal"` so charcoal
  also satisfies the Steel recipe. Unrecognized materials (dirt, stick, etc.)
  classify to `Optional.empty()`.

  **Accept-damaged (the carried-over obligation from Task 2):** the pure core takes
  `damaged` as an explicit boolean parameter — no `ItemStack` needed to prove this
  behavior. When `acceptDamaged=true`, `damaged` is not consulted at all beyond the
  gate check, so a pristine and a heavily-damaged item of the same material produce
  `.equals()`-identical `RecycleInput` values (tested directly). When
  `acceptDamaged=false`, a damaged item classifies to `Optional.empty()` (rejected
  outright) while a pristine item of the same type still classifies normally (also
  tested). Alloy items bypass this gate entirely — remelting isn't a "damaged gear"
  concept.

- **`AlloyItemFactory`** — builds an alloy `ItemStack` on base `Material.NETHERITE_INGOT`,
  name/lore from `AlloyDefinition` as Adventure `Component`s (`.decoration(ITALIC,
  false)`, color from the definition's hex string via `TextColor.fromHexString`,
  falling back to white if unparseable), stamped with both `xpfarm:` keys. No custom
  model data anywhere.

- **`MachineItemFactory`** — builds the machine item on base `Material.BLAST_FURNACE`,
  named "Electric Furnace" with lore explaining the redstone requirement, stamped
  with `electricfurnace:machine` (BYTE marker `1`).

## Files

- `src/main/java/org/xpfarm/electricfurnace/item/MaterialContract.java` (new)
- `src/main/java/org/xpfarm/electricfurnace/item/MetalClassifier.java` (new)
- `src/main/java/org/xpfarm/electricfurnace/item/AlloyItemFactory.java` (new)
- `src/main/java/org/xpfarm/electricfurnace/item/MachineItemFactory.java` (new)
- `src/test/java/org/xpfarm/electricfurnace/item/MetalClassifierTest.java` (new)

No files outside Task 3's list were created or modified.

## Build verification

Command: `mvn --batch-mode --no-transfer-progress clean verify`

Result: **BUILD SUCCESS**. 62 tests total, 0 failures, 0 errors, 0 skipped:

```
Running org.xpfarm.electricfurnace.recycle.RecycleResolverTest    -> 20 tests, all pass
Running org.xpfarm.electricfurnace.item.MetalClassifierTest       -> 15 tests, all pass (new)
Running org.xpfarm.electricfurnace.config.ConfigValidatorTest     -> 20 tests, all pass
Running org.xpfarm.electricfurnace.alloy.AlloyRegistryTest        ->  7 tests, all pass
Tests run: 62, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Shaded JAR built successfully at `target/electric-furnace-0.1.0.jar`.

Environment: sourced `~/.sdkman/bin/sdkman-init.sh` for Java 25.0.3-tem / Maven
3.9.16, since `java`/`mvn` are not on PATH by default.

## Key investigation before writing code

Verified empirically (compiled and ran a throwaway `ItemStack` construction against
the resolved `paper-api-26.1.2.build.74-stable.jar` with no server bootstrapped) that
**no `ItemStack` can be constructed at all outside a live server** — `new
ItemStack(Material.IRON_INGOT)` throws `IllegalStateException: No RegistryAccess
implementation found` at construction time, before any meta/PDC access is even
attempted. This confirms `MetalClassifierTest` genuinely cannot touch `ItemStack`
directly (matching the plan's own instruction to keep the mapping lookup testable
independently of ItemStack/ItemMeta construction), and is why the accept-damaged
tests are written against the primitive-parameter core rather than real items. No
new test dependency (Mockito/MockBukkit) was added to reach this coverage — the
existing `RecyclingSettings`/`Material`/`RecycleInput` primitives were sufficient
once the classifier was structured with a pure core.

Also discovered the resolved Paper API jar is a newer build that already ships
native vanilla copper tools/armor/weapons (`COPPER_SWORD`, `COPPER_HELMET`, etc.,
plus a new `SPEAR` weapon variant for all four metals) and `COPPER_NUGGET`. Task 3's
text says "iron/gold/copper/netherite/chainmail tools, weapons, and armor," so these
were included in the static table for symmetry across all four metals. Excluded
from the table by design judgment (not explicitly requested, kept the mapping
bounded and parallel across metals): horse armor and "nautilus armor" variants,
which exist for all four metals in this Paper build but are not standard player
equipment slots.

## Deviations from the plan

- No literal deviation from file list, key names, or constructors. One judgment call
  documented above (SPEAR included, horse/nautilus armor excluded from the gear
  table) since the plan names the gear categories generically ("tools, weapons, and
  armor") without an exhaustive material list.
- Ingot-equivalent value: the plan only specifies nuggets must be < 1 (rejected).
  All other recognized materials (ingots, raw ore, gear) were assigned a flat
  `ingotValue = 1`, since `RecycleInput.ingotValue()` is documented as
  display/downstream-only and not consulted by `RecycleResolver`. Modifiers (coal/
  charcoal) get `ingotValue = 0`.
- Damaged-gear-when-disabled behavior (classify to `Optional.empty()`, i.e. rejected
  outright) was not explicitly specified by the plan beyond "test the accept-damaged
  = false path if you implement one" — this is the implementation choice made and
  is exactly what's tested.

## Dependencies

**No dependencies were added to `pom.xml`.** All classes and tests build against the
existing `paper-api` (provided) and `junit-jupiter` (test) dependencies already
present.

## Concerns

- `AlloyItemFactory` and `MachineItemFactory` have no dedicated unit tests, per the
  plan's file list (only `MetalClassifierTest` is listed for Task 3) — they cannot be
  unit tested without a live server for the same `RegistryAccess` reason documented
  above (item construction itself fails). Their correctness (PDC stamping, no custom
  model data, Adventure Component usage) was verified by careful reading against the
  decompiled Paper API surface, and should be exercised in gate 7a runtime
  verification once the plugin is wireable end-to-end (Tasks 4–6).
- The exhaustive metal-table test hardcodes an expected count (51 entries: 16 iron +
  12 gold + 12 copper + 11 netherite) as a guard against silent drift; if a future
  task extends the table, that count must be updated deliberately alongside it.
