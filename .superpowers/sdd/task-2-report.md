# Task 2 Report — Alloy model and the pure recycle resolver

## Status: DONE

## What was implemented

**Pure model (no `org.bukkit` anywhere in these files):**
- `MetalType` — enum `IRON, GOLD, COPPER, NETHERITE`. Coal is deliberately excluded
  (it is a modifier, represented via `RecycleInput.isModifier`, not a metal).
- `RecycleInput` — record `(materialId, metal, isModifier, isAlloy, alloyId,
  ingotValue)`, exactly as specified. Has no durability/damage field at all, which is
  what makes "damaged vs. undamaged produce identical results" true by construction.
- `RecycleResult` — sealed interface with a `Kind` enum and five record
  implementations: `SameMetal`, `NamedAlloy`, `GenericAlloy`, `Remelt`, `Rejected`.
- `AlloyStats` — record `(attackDamage, attackSpeed, armor, armorToughness,
  maxDurability, enchantability)`.
- `AlloyDefinition` — record `(id, displayName, lore, color, inputIds, stats)`.
  `color` is a plain hex string, `lore` plain text lines — kept Bukkit/Adventure-free
  per the plan; Task 3 owns turning this into an actual `ItemStack`.
- `RecycleResolver` — pure static `resolve(inputs, RecyclingSettings, AlloyRegistry)`.
  Implements all 8 rules in exact precedence order (see design notes below).

**Alloy registry (mostly pure, one glue method touches Bukkit):**
- `AlloyRegistry.fromDefinitions(List<AlloyDefinition>, Consumer<String> warn)` — the
  pure core. Applies the balance-ceiling clamp to every stat block on load and
  designates the fallback (the definition with empty `inputIds`), synthesizing a
  default `fused_alloy` and warning if none was configured. This is what
  `AlloyRegistryTest` exercises directly — no `ConfigurationSection`, no server.
- `AlloyRegistry.load(ConfigurationSection, Consumer<String>)` — thin glue that parses
  `config.yml`'s `alloys:` section into `AlloyDefinition`s and delegates to
  `fromDefinitions`. This is the one method in the whole task that imports
  `org.bukkit.configuration.ConfigurationSection`; it lives only in `AlloyRegistry.java`
  and is untested directly (analogous to `EfConfig.load` in Task 1, which is also
  untested glue around the pure `ConfigValidator`).
- Balance ceiling reference constants (`IRON_*`, `DIAMOND_*`, `NETHERITE_*`) per the
  plan's exact values, plus `findNamedMatch(Set<String>)` (exact-set match, order- and
  quantity-independent) and `fallback()`.

## Files

- `src/main/java/org/xpfarm/electricfurnace/alloy/MetalType.java`
- `src/main/java/org/xpfarm/electricfurnace/alloy/AlloyDefinition.java`
- `src/main/java/org/xpfarm/electricfurnace/alloy/AlloyStats.java`
- `src/main/java/org/xpfarm/electricfurnace/alloy/AlloyRegistry.java`
- `src/main/java/org/xpfarm/electricfurnace/recycle/RecycleInput.java`
- `src/main/java/org/xpfarm/electricfurnace/recycle/RecycleResult.java`
- `src/main/java/org/xpfarm/electricfurnace/recycle/RecycleResolver.java`
- `src/test/java/org/xpfarm/electricfurnace/recycle/RecycleResolverTest.java`
- `src/test/java/org/xpfarm/electricfurnace/alloy/AlloyRegistryTest.java`
- `src/main/resources/config.yml` — extended `alloys:` section (see deviations below)

## The rule 6 vs. 7 interaction (the subtlest part)

Confirmed and directly tested (`rule6vs7_fourIronPlusCoal_isNotSameMetal_matchesSteelInstead`,
plus `acceptanceCheck_fourIronOneCoal_yieldsTwoSteel`): 4 iron + 1 coal is checked for
rule 6 ("all metals identical AND no modifiers present") — the coal modifier's mere
presence fails the "no modifiers" clause regardless of metal identity, so control
falls through to rule 7, where the distinct id set `{iron, coal}` exactly matches
Steel's `inputIds`. The test explicitly asserts `result.kind() != SAME_METAL` in
addition to asserting the `NamedAlloy("steel", 2)` outcome.

## Design decision: rule 3's item count

The plan's rule 3 text reads "fewer than `recycling.slots` **non-modifier** items →
REJECTED", but the plan's own worked examples (4 iron + 1 coal must reach rules 6/7,
which requires 5 total items to clear rule 3 at the default `slots=5`) are only
consistent if rule 3 counts **all** items (metals and modifiers alike), not just
non-modifier ones — a strict "non-modifier count ≥ slots" reading would reject 4
iron + 1 coal at rule 3 before it ever reached the rule 6/7 interaction the plan asks
to specifically test. I implemented rule 3 as a plain headcount over every input
(`inputs.size() < settings.slots()`), with a comment in `RecycleResolver` documenting
this reasoning. This resolves an internal contradiction in the plan text in favor of
its own explicit, mandatory test cases.

## Deviation: no `alloys.balance-ceiling.enabled` toggle

The design doc's config table lists `alloys.balance-ceiling.enabled | bool | true |
clamp + warn above netherite`, implying a toggle. The plan's binding Global
Constraints state the balance ceiling must be "ENFORCED IN CODE... not just
documented," and Task 2's own text repeats "ENFORCED IN CODE (AlloyRegistry clamps +
warns), not just documented." Adding a config switch that can disable the clamp would
contradict that binding constraint, so I did not add one — `AlloyRegistry.fromDefinitions`
clamps unconditionally, with no way to turn it off from config. Noted in the commit
message as well.

## Config.yml changes

Kept all five shipping recipes (`steel`, `rose_gold`, `ferrocopper`, `electrum_steel`,
`fused_alloy`) and their explanatory comments, and added per the design's
`alloys.<id>.*` shape: `color` (hex), `lore` (list), and `stats` (six fields). All
shipped stat values sit strictly between the iron and diamond references (never
reaching netherite), so the shipped config never triggers a clamp — the clamp is a
safety net for operator misconfiguration, verified separately in `AlloyRegistryTest`.

## Test summary

`RecycleResolverTest`: 21 tests — one (or more) per numbered rule (1–8), the explicit
rule 6-vs-7 interaction test, and every scenario from the plan's required list (5
iron → 3 ingots; 5 mixed → 2 generic; 4 iron+1 coal → 2 Steel; 5 coal → rejected; 1
alloy → 1 ingot remelt; 4 items → rejected; damaged/undamaged identical; yields track
config via a non-default `RecyclingSettings`).

`AlloyRegistryTest`: 7 tests — order-independent recipe matching, unknown mix falls
back to `fused_alloy`, missing-fallback synthesis warns, a single stat above netherite
clamped+warned (message contents checked for alloy id, stat name, and configured
value), a stat exactly at the netherite ceiling passing through unclamped, and
multiple simultaneously-overshooting stats each independently clamped and warned.

## `mvn verify` — actual output

Command: `mvn --batch-mode --no-transfer-progress clean verify`

```
[INFO] Running org.xpfarm.electricfurnace.alloy.AlloyRegistryTest
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.062 s -- in org.xpfarm.electricfurnace.alloy.AlloyRegistryTest
[INFO] Running org.xpfarm.electricfurnace.config.ConfigValidatorTest
[INFO] Tests run: 20, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.041 s -- in org.xpfarm.electricfurnace.config.ConfigValidatorTest
[INFO] Running org.xpfarm.electricfurnace.recycle.RecycleResolverTest
[INFO] Tests run: 21, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.057 s -- in org.xpfarm.electricfurnace.recycle.RecycleResolverTest
[INFO]
[INFO] Results:
[INFO]
[INFO] Tests run: 48, Failures: 0, Errors: 0, Skipped: 0
...
[INFO] BUILD SUCCESS
```

48 total tests (20 pre-existing from Task 1 + 28 new), all passing.

## Bukkit-purity verification

`grep -n "^import" RecycleResolver.java` and `RecycleResolverTest.java` show zero
`org.bukkit` imports. Their full dependency chain (`RecycleInput`, `RecycleResult`,
`MetalType`, `AlloyDefinition`, `AlloyStats`, `RecyclingSettings`) is also import-clean
of `org.bukkit`. The only `org.bukkit` import in the entire Task 2 file set is in
`AlloyRegistry.java`'s `load(ConfigurationSection, ...)` glue method, which
`RecycleResolver`/`RecycleResolverTest` do not call or depend on.

## No dependencies added to pom.xml

Confirmed — `pom.xml` was not touched.

## Concerns

- `AlloyRegistry.load(ConfigurationSection, ...)` is not directly unit tested (mirrors
  Task 1's `EfConfig.load`, which is similarly untested glue around a tested pure
  core). It will get exercised indirectly once Task 6 wires `ElectricFurnacePlugin`
  and calls it against the real `config.yml`; recommend a smoke check at that point
  (or in gate 7a) that the shipped `alloys:` section parses into the five expected
  ids with the right `inputIds`.
- The rule 3 interpretation (total item count vs. "non-modifier items") is a judgment
  call resolving an internal ambiguity in the plan text — flagged above with the
  reasoning; worth a quick sanity read by whoever reviews this against the original
  design intent.

---

## Code Review Fix — Deletion of Vacuous Test

**Commit:** a4b1ba0

**What changed:**
Deleted method `acceptanceCheck_damagedAndUndamagedInputs_produceIdenticalResults` 
from `RecycleResolverTest.java` (lines 255–267).

**Reason:** 
The test is structurally vacuous. `RecycleInput` has no damage/durability field at all, 
so the two "damaged" and "undamaged" input lists the test builds are identical records. 
They resolve identically no matter what the resolver does, providing zero signal about 
the accept-damaged behavior. The accept-damaged feature genuinely belongs to Task 3's 
`MetalClassifier`, which converts Bukkit `ItemStack`s (which carry damage) into 
`RecycleInput` records; testing it at the resolver level is impossible by construction.

**Test command:**
```
mvn --batch-mode --no-transfer-progress clean verify
```

**Result:**
```
[INFO] Running org.xpfarm.electricfurnace.recycle.RecycleResolverTest
[INFO] Tests run: 20, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.102 s -- in org.xpfarm.electricfurnace.recycle.RecycleResolverTest
[INFO] Running org.xpfarm.electricfurnace.config.ConfigValidatorTest
[INFO] Tests run: 20, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.054 s -- in org.xpfarm.electricfurnace.config.ConfigValidatorTest
[INFO] Running org.xpfarm.electricfurnace.alloy.AlloyRegistryTest
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.017 s -- in org.xpfarm.electricfurnace.alloy.AlloyRegistryTest
[INFO]
[INFO] Results:
[INFO]
[INFO] Tests run: 47, Failures: 0, Errors: 0, Skipped: 0
...
[INFO] BUILD SUCCESS
```

47 total tests (down from 48; RecycleResolverTest now 20 tests instead of 21), all passing. No unused imports or helper methods required cleanup.
