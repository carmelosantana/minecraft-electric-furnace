# ElectricFurnace Alloy Gear Crafting Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make each of the five alloys craftable into six gear items — sword, axe, helmet, chestplate, leggings, boots — whose stats are derived from the alloy's existing `AlloyStats` block.

**Architecture:** A new `org.xpfarm.electricfurnace.gear` package split the way this codebase always splits: a pure core (`GearPiece`, `GearStats`, `GearStatsDeriver`, `GearBase`) that is exhaustively unit-tested with no running server, and thin Bukkit-facing glue (`GearItemFactory`, `GearRecipes`) exercised only at runtime. Recipes are crafting-table `ShapedRecipe`s using `RecipeChoice.ExactChoice`, registered and gated exactly like the existing `MachineRecipe`.

**Tech Stack:** Java 25, Paper API `26.1.2.build.74-stable`, JUnit Jupiter 5.10.0, Maven.

## Global Constraints

- Spec: `docs/superpowers/specs/2026-07-21-alloy-gear-crafting-design.md`. It is the source of truth; this plan implements it.
- Java 25 (`maven.compiler.release=25`). Paper API `26.1.2.build.74-stable`, scope `provided`.
- Group `org.xpfarm`, package root `org.xpfarm.electricfurnace`.
- Every new file starts with the AGPL header block copied verbatim from `FurnaceGui.java:1-9`.
- **Decision logic must be pure and testable with no running server.** Bukkit types (`ItemStack`, `Player`, `Inventory`) cannot be constructed headlessly here. Extract every decision into a function over primitives, enums, and records, and test that. Established pattern: `MetalClassifier.resolveBranch`, `RecycleResolver.resolve`, `AlloyRegistry.clampStats`.
- **Items are identified by PDC only** — never by display name or lore substring. Non-negotiable Bedrock/Geyser rule.
- **No `setCustomModelData`, no `item_model`, no display entities.** No resource pack exists.
- `Attribute` is **not an enum** on this Paper version (it became an interface in 1.21.3). Use `Attribute.ATTACK_DAMAGE`, `ATTACK_SPEED`, `ARMOR`, `ARMOR_TOUGHNESS`. `Attribute.GENERIC_ATTACK_DAMAGE` throws `NoSuchFieldError`; `EnumMap<Attribute,…>`, `EnumSet`, `Attribute.values()`, and `switch` on `Attribute` will not work.
- Writing `attribute_modifiers` **replaces** the item type's vanilla defaults rather than merging. Any item that gets a modifier must have its full modifier set written explicitly.
- Config keys are validated on load; an invalid value logs a warning naming the key, the offending value, and the default it fell back to, and never stops the plugin from starting.
- Nothing in the enable path may throw. `ElectricFurnacePlugin.step(...)` wraps each stage in a `Throwable` guard — new wiring goes inside a `step`.
- Environment: `source ~/.sdkman/bin/sdkman-init.sh` first — `java` and `mvn` are not on PATH.
- Full build: `mvn --batch-mode --no-transfer-progress clean verify`.
- Single test: `mvn --batch-mode --no-transfer-progress test -Dtest=ClassName#methodName`.
- Existing suite is **311 tests** and must stay green. Only Task 8 changes an existing test's expectations, and it says so explicitly.

---

## File Structure

**Create (pure — no `org.bukkit` import):**

| File | Responsibility |
|---|---|
| `src/main/java/org/xpfarm/electricfurnace/gear/GearPiece.java` | The six pieces; each carries its armor-share numerator, durability factor, ingot/stick cost, and weapon deltas. |
| `src/main/java/org/xpfarm/electricfurnace/gear/GearStats.java` | The derived per-piece stat block. |
| `src/main/java/org/xpfarm/electricfurnace/gear/GearStatsDeriver.java` | `derive(AlloyStats, GearPiece)`. Armor split and durability interpolation live here. |
| `src/main/java/org/xpfarm/electricfurnace/gear/GearBase.java` | The five base material families; maps alloy id → base, and (piece, base) → Bukkit material **name**. |

**Create (Bukkit-facing):**

| File | Responsibility |
|---|---|
| `src/main/java/org/xpfarm/electricfurnace/item/GearItemFactory.java` | Mints a gear `ItemStack`, beside the existing `AlloyItemFactory`. |
| `src/main/java/org/xpfarm/electricfurnace/recipe/GearRecipes.java` | Registers/unregisters the 30 recipes; hosts the craft gate and recipe discovery. Modelled on `MachineRecipe`. |

**Create (tests):**

- `src/test/java/org/xpfarm/electricfurnace/gear/GearPieceTest.java`
- `src/test/java/org/xpfarm/electricfurnace/gear/GearStatsDeriverTest.java`
- `src/test/java/org/xpfarm/electricfurnace/gear/GearBaseTest.java`

**Modify:**

- `src/main/java/org/xpfarm/electricfurnace/alloy/AlloyDefinition.java` — add a `base` component.
- `src/main/java/org/xpfarm/electricfurnace/alloy/AlloyRegistry.java` — parse `alloys.<id>.base`.
- `src/main/java/org/xpfarm/electricfurnace/item/MaterialContract.java` — add the `xpfarm:gear_piece` key.
- `src/main/java/org/xpfarm/electricfurnace/recycle/RecycleResolver.java` — relax rule 2.
- `src/main/java/org/xpfarm/electricfurnace/command/ElectricFurnaceCommand.java` — `piece` argument.
- `src/main/java/org/xpfarm/electricfurnace/ElectricFurnacePlugin.java` — wire `GearRecipes` into enable/disable/reload.
- `src/main/resources/config.yml` — document `base`.
- Corresponding existing tests.

**Not modified, deliberately:** `MetalClassifier`. Its `resolveBranch` already ranks `isAlloyStamped` above the material table, so alloy gear on an `IRON_SWORD` base already classifies as an alloy remelt rather than as iron. Do not "fix" this.

---

### Task 1: `GearPiece` enum

**Files:**
- Create: `src/main/java/org/xpfarm/electricfurnace/gear/GearPiece.java`
- Test: `src/test/java/org/xpfarm/electricfurnace/gear/GearPieceTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `enum GearPiece` with `GearPiece.Kind { WEAPON, ARMOR }`; instance methods `String id()`, `String displayName()`, `Kind kind()`, `int armorShareNumerator()`, `int durabilityFactor()`, `int ingotCost()`, `int stickCost()`, `double attackDamageDelta()`, `double attackSpeedDelta()`; statics `Optional<GearPiece> byId(String)`, `List<GearPiece> armorPieces()`, `int ARMOR_SHARE_DENOMINATOR`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/org/xpfarm/electricfurnace/gear/GearPieceTest.java` (AGPL header first, copied from `FurnaceGui.java:1-9`):

```java
package org.xpfarm.electricfurnace.gear;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link GearPiece}. Pure -- no running server. */
class GearPieceTest {

    @Test
    void armorShareNumerators_sumToDenominator() {
        int sum = GearPiece.armorPieces().stream()
                .mapToInt(GearPiece::armorShareNumerator)
                .sum();
        assertEquals(GearPiece.ARMOR_SHARE_DENOMINATOR, sum,
                "armor numerators must sum to the denominator, or splitArmor cannot preserve the total");
    }

    @Test
    void armorPieces_areTheFourArmourSlotsInTiebreakOrder() {
        assertEquals(List.of(GearPiece.CHESTPLATE, GearPiece.LEGGINGS, GearPiece.HELMET, GearPiece.BOOTS),
                GearPiece.armorPieces());
    }

    @Test
    void weapons_haveNoArmorShareAndNoDurabilityFactor() {
        for (GearPiece piece : List.of(GearPiece.SWORD, GearPiece.AXE)) {
            assertEquals(GearPiece.Kind.WEAPON, piece.kind());
            assertEquals(0, piece.armorShareNumerator());
            assertEquals(0, piece.durabilityFactor());
        }
    }

    @Test
    void sword_hasNoWeaponDeltas_axeIsStrongerAndSlower() {
        assertEquals(0.0, GearPiece.SWORD.attackDamageDelta());
        assertEquals(0.0, GearPiece.SWORD.attackSpeedDelta());
        assertEquals(2.0, GearPiece.AXE.attackDamageDelta());
        assertEquals(-0.6, GearPiece.AXE.attackSpeedDelta());
    }

    @Test
    void vanillaCosts_matchTheSpec() {
        assertEquals(2, GearPiece.SWORD.ingotCost());
        assertEquals(1, GearPiece.SWORD.stickCost());
        assertEquals(3, GearPiece.AXE.ingotCost());
        assertEquals(2, GearPiece.AXE.stickCost());
        assertEquals(5, GearPiece.HELMET.ingotCost());
        assertEquals(8, GearPiece.CHESTPLATE.ingotCost());
        assertEquals(7, GearPiece.LEGGINGS.ingotCost());
        assertEquals(4, GearPiece.BOOTS.ingotCost());
        assertEquals(0, GearPiece.HELMET.stickCost());
    }

    @Test
    void byId_isCaseInsensitiveAndRejectsUnknown() {
        assertEquals(Optional.of(GearPiece.CHESTPLATE), GearPiece.byId("chestplate"));
        assertEquals(Optional.of(GearPiece.CHESTPLATE), GearPiece.byId("CHESTPLATE"));
        assertTrue(GearPiece.byId("trousers").isEmpty());
        assertTrue(GearPiece.byId(null).isEmpty());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `source ~/.sdkman/bin/sdkman-init.sh && mvn --batch-mode --no-transfer-progress test -Dtest=GearPieceTest`
Expected: FAIL — compilation error, `package org.xpfarm.electricfurnace.gear does not exist`.

- [ ] **Step 3: Write minimal implementation**

Create `src/main/java/org/xpfarm/electricfurnace/gear/GearPiece.java` (AGPL header first):

```java
package org.xpfarm.electricfurnace.gear;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The six craftable gear pieces, and the vanilla-derived constants each one needs.
 *
 * <p>Deliberately free of any Bukkit type. The equipment slot an armor piece occupies
 * and the concrete {@code Material} it is built on are both resolved later, by
 * {@code GearItemFactory} -- this enum only carries plain numbers, so it stays
 * exhaustively unit-testable with no running server.
 *
 * <p>{@link #armorShareNumerator()} values are vanilla's diamond-armor distribution
 * (3/8/6/3 of 20); {@link #durabilityFactor()} values are vanilla's per-slot armor
 * durability multipliers (11/16/15/13).
 */
public enum GearPiece {

    SWORD("sword", "Sword", Kind.WEAPON, 0, 0, 2, 1, 0.0, 0.0),
    AXE("axe", "Axe", Kind.WEAPON, 0, 0, 3, 2, 2.0, -0.6),
    HELMET("helmet", "Helmet", Kind.ARMOR, 3, 11, 5, 0, 0.0, 0.0),
    CHESTPLATE("chestplate", "Chestplate", Kind.ARMOR, 8, 16, 8, 0, 0.0, 0.0),
    LEGGINGS("leggings", "Leggings", Kind.ARMOR, 6, 15, 7, 0, 0.0, 0.0),
    BOOTS("boots", "Boots", Kind.ARMOR, 3, 13, 4, 0, 0.0, 0.0);

    /** Whether a piece is held and swung, or worn. */
    public enum Kind {
        WEAPON,
        ARMOR
    }

    /** Sum of every armor piece's share numerator. */
    public static final int ARMOR_SHARE_DENOMINATOR = 20;

    /**
     * The four armor pieces in <b>largest-remainder tiebreak order</b>.
     *
     * <p>This order is load-bearing, not cosmetic: when two pieces' rounding
     * remainders tie, the earlier one here receives the spare armor point. Fixing the
     * order is what makes {@code GearStatsDeriver.splitArmor} deterministic instead of
     * dependent on enum declaration order, and the order itself (chest, legs, helmet,
     * boots) is vanilla's own descending armor-value order, so spare points land on
     * the pieces that already carry the most.
     */
    private static final List<GearPiece> ARMOR_PIECES =
            List.of(CHESTPLATE, LEGGINGS, HELMET, BOOTS);

    private final String id;
    private final String displayName;
    private final Kind kind;
    private final int armorShareNumerator;
    private final int durabilityFactor;
    private final int ingotCost;
    private final int stickCost;
    private final double attackDamageDelta;
    private final double attackSpeedDelta;

    GearPiece(String id, String displayName, Kind kind, int armorShareNumerator, int durabilityFactor,
            int ingotCost, int stickCost, double attackDamageDelta, double attackSpeedDelta) {
        this.id = id;
        this.displayName = displayName;
        this.kind = kind;
        this.armorShareNumerator = armorShareNumerator;
        this.durabilityFactor = durabilityFactor;
        this.ingotCost = ingotCost;
        this.stickCost = stickCost;
        this.attackDamageDelta = attackDamageDelta;
        this.attackSpeedDelta = attackSpeedDelta;
    }

    /** Stable lowercase identifier, e.g. {@code "chestplate"}. */
    public String id() {
        return id;
    }

    /** Human-readable suffix, e.g. {@code "Chestplate"} in "Steel Chestplate". */
    public String displayName() {
        return displayName;
    }

    public Kind kind() {
        return kind;
    }

    /** This piece's share of the alloy's full-set armor total, over {@link #ARMOR_SHARE_DENOMINATOR}. */
    public int armorShareNumerator() {
        return armorShareNumerator;
    }

    /** Vanilla's per-slot armor durability multiplier; {@code 0} for weapons. */
    public int durabilityFactor() {
        return durabilityFactor;
    }

    /** Alloy ingots consumed by this piece's recipe. */
    public int ingotCost() {
        return ingotCost;
    }

    /** Vanilla sticks consumed by this piece's recipe. */
    public int stickCost() {
        return stickCost;
    }

    /** Added to the alloy's configured attack damage; non-zero only for the axe. */
    public double attackDamageDelta() {
        return attackDamageDelta;
    }

    /** Added to the alloy's configured attack speed; non-zero only for the axe. */
    public double attackSpeedDelta() {
        return attackSpeedDelta;
    }

    /** The four armor pieces, in largest-remainder tiebreak order. */
    public static List<GearPiece> armorPieces() {
        return ARMOR_PIECES;
    }

    /** Resolves a typed token to a piece, case-insensitively. */
    public static Optional<GearPiece> byId(String token) {
        if (token == null) {
            return Optional.empty();
        }
        String normalized = token.toLowerCase(Locale.ROOT);
        for (GearPiece piece : values()) {
            if (piece.id.equals(normalized)) {
                return Optional.of(piece);
            }
        }
        return Optional.empty();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `source ~/.sdkman/bin/sdkman-init.sh && mvn --batch-mode --no-transfer-progress test -Dtest=GearPieceTest`
Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/xpfarm/electricfurnace/gear/GearPiece.java src/test/java/org/xpfarm/electricfurnace/gear/GearPieceTest.java
git commit -m "feat(gear): add GearPiece enum with vanilla-derived constants"
```

---

### Task 2: Armor split — largest-remainder rounding

**Files:**
- Create: `src/main/java/org/xpfarm/electricfurnace/gear/GearStatsDeriver.java`
- Test: `src/test/java/org/xpfarm/electricfurnace/gear/GearStatsDeriverTest.java`

**Interfaces:**
- Consumes: `GearPiece` from Task 1.
- Produces: `public static Map<GearPiece, Integer> splitArmor(int total)` on `GearStatsDeriver`, returning an entry for each of the four armor pieces.

The whole point of this task is that the four pieces sum to **exactly** the configured total for every input. Integer arithmetic only — no floating point, so there is no rounding drift to reason about.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/org/xpfarm/electricfurnace/gear/GearStatsDeriverTest.java` (AGPL header first):

```java
package org.xpfarm.electricfurnace.gear;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link GearStatsDeriver}. Pure -- no running server. */
class GearStatsDeriverTest {

    @Test
    void splitArmor_steelTotalOf16_matchesTheWorkedExampleInTheSpec() {
        Map<GearPiece, Integer> split = GearStatsDeriver.splitArmor(16);

        assertEquals(2, split.get(GearPiece.HELMET));
        assertEquals(7, split.get(GearPiece.CHESTPLATE));
        assertEquals(5, split.get(GearPiece.LEGGINGS));
        assertEquals(2, split.get(GearPiece.BOOTS));
    }

    @Test
    void splitArmor_alwaysSumsToExactlyTheTotal() {
        for (int total = 0; total <= 40; total++) {
            int sum = GearStatsDeriver.splitArmor(total).values().stream()
                    .mapToInt(Integer::intValue)
                    .sum();
            assertEquals(total, sum, "split of " + total + " must sum back to " + total);
        }
    }

    @Test
    void splitArmor_diamondTotalOf20_reproducesVanillaDistribution() {
        Map<GearPiece, Integer> split = GearStatsDeriver.splitArmor(20);

        assertEquals(3, split.get(GearPiece.HELMET));
        assertEquals(8, split.get(GearPiece.CHESTPLATE));
        assertEquals(6, split.get(GearPiece.LEGGINGS));
        assertEquals(3, split.get(GearPiece.BOOTS));
    }

    @Test
    void splitArmor_sparePointsFavourChestplateOverBoots() {
        // Total 1: every piece floors to 0, one spare point, chestplate wins the tiebreak.
        Map<GearPiece, Integer> split = GearStatsDeriver.splitArmor(1);

        assertEquals(1, split.get(GearPiece.CHESTPLATE));
        assertEquals(0, split.get(GearPiece.HELMET));
        assertEquals(0, split.get(GearPiece.LEGGINGS));
        assertEquals(0, split.get(GearPiece.BOOTS));
    }

    @Test
    void splitArmor_isDeterministicAcrossRepeatedCalls() {
        for (int i = 0; i < 50; i++) {
            assertEquals(GearStatsDeriver.splitArmor(17), GearStatsDeriver.splitArmor(17));
        }
    }

    @Test
    void splitArmor_negativeTotalIsTreatedAsZero() {
        Map<GearPiece, Integer> split = GearStatsDeriver.splitArmor(-5);

        assertTrue(split.values().stream().allMatch(value -> value == 0));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `source ~/.sdkman/bin/sdkman-init.sh && mvn --batch-mode --no-transfer-progress test -Dtest=GearStatsDeriverTest`
Expected: FAIL — `cannot find symbol: class GearStatsDeriver`.

- [ ] **Step 3: Write minimal implementation**

Create `src/main/java/org/xpfarm/electricfurnace/gear/GearStatsDeriver.java` (AGPL header first):

```java
package org.xpfarm.electricfurnace.gear;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Derives one gear piece's stats from an alloy's single stat block.
 *
 * <p><b>Entirely pure.</b> No {@code org.bukkit} type appears here or in its tests.
 * Every reference constant comes from {@code AlloyRegistry}, never re-declared.
 */
public final class GearStatsDeriver {

    private GearStatsDeriver() {
    }

    /**
     * Splits an alloy's full-set armor total across the four armor pieces using
     * vanilla's 3/8/6/3 shape with largest-remainder rounding.
     *
     * <p><b>The pieces always sum to exactly {@code total}.</b> That is the entire
     * reason this is largest-remainder rather than plain rounding: rounding each piece
     * independently loses or gains points, so an alloy configured for 16 armor would
     * silently grant 15 or 17. All arithmetic is integer -- {@code numerator * total}
     * is divided by the denominator for the floor and the remainder is taken with
     * {@code %}, so there is no floating-point drift to reason about.
     *
     * <p>Ties in the remainder are broken by {@link GearPiece#armorPieces()} order.
     *
     * @param total the alloy's configured full-set armor points; negative is treated as 0
     * @return an entry for each of the four armor pieces, summing to {@code total}
     */
    public static Map<GearPiece, Integer> splitArmor(int total) {
        int safeTotal = Math.max(0, total);
        Map<GearPiece, Integer> result = new EnumMap<>(GearPiece.class);
        List<GearPiece> pieces = GearPiece.armorPieces();

        int assigned = 0;
        Map<GearPiece, Integer> remainders = new EnumMap<>(GearPiece.class);
        for (GearPiece piece : pieces) {
            int scaled = piece.armorShareNumerator() * safeTotal;
            int floor = scaled / GearPiece.ARMOR_SHARE_DENOMINATOR;
            result.put(piece, floor);
            remainders.put(piece, scaled % GearPiece.ARMOR_SHARE_DENOMINATOR);
            assigned += floor;
        }

        int spare = safeTotal - assigned;
        List<GearPiece> byRemainder = new ArrayList<>(pieces);
        byRemainder.sort(Comparator
                .comparingInt((GearPiece piece) -> remainders.get(piece)).reversed()
                .thenComparingInt(pieces::indexOf));

        for (int i = 0; i < spare; i++) {
            GearPiece piece = byRemainder.get(i);
            result.put(piece, result.get(piece) + 1);
        }
        return result;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `source ~/.sdkman/bin/sdkman-init.sh && mvn --batch-mode --no-transfer-progress test -Dtest=GearStatsDeriverTest`
Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/xpfarm/electricfurnace/gear/GearStatsDeriver.java src/test/java/org/xpfarm/electricfurnace/gear/GearStatsDeriverTest.java
git commit -m "feat(gear): split alloy armor total across pieces with largest-remainder rounding"
```

---

### Task 3: Durability interpolation

**Files:**
- Modify: `src/main/java/org/xpfarm/electricfurnace/gear/GearStatsDeriver.java`
- Test: `src/test/java/org/xpfarm/electricfurnace/gear/GearStatsDeriverTest.java`

**Interfaces:**
- Consumes: `AlloyRegistry.IRON_MAX_DURABILITY` (250), `AlloyRegistry.DIAMOND_MAX_DURABILITY` (1561).
- Produces: `public static int armorBaseUnit(int maxDurability)` on `GearStatsDeriver`, plus public constants `IRON_ARMOR_BASE = 15`, `DIAMOND_ARMOR_BASE = 33`, `NETHERITE_ARMOR_BASE = 37`.

`AlloyStats.maxDurability` is tool-scale (iron 250, diamond 1561). Vanilla armor uses a different base unit (iron 15, diamond 33, netherite 37) multiplied by per-slot factors. The two scales are not proportional — 250/15 ≈ 16.7 against 1561/33 ≈ 47.3 — so a flat ratio would be wrong.

- [ ] **Step 1: Write the failing test**

Append to `GearStatsDeriverTest`:

```java
    @Test
    void armorBaseUnit_atTheIronReference_isVanillaIronArmorBase() {
        assertEquals(GearStatsDeriver.IRON_ARMOR_BASE,
                GearStatsDeriver.armorBaseUnit(AlloyRegistry.IRON_MAX_DURABILITY));
    }

    @Test
    void armorBaseUnit_atTheDiamondReference_isVanillaDiamondArmorBase() {
        assertEquals(GearStatsDeriver.DIAMOND_ARMOR_BASE,
                GearStatsDeriver.armorBaseUnit(AlloyRegistry.DIAMOND_MAX_DURABILITY));
    }

    @Test
    void armorBaseUnit_steel700_derivesTo21() {
        assertEquals(21, GearStatsDeriver.armorBaseUnit(700));
    }

    @Test
    void armorBaseUnit_belowIronReference_clampsToIronBase() {
        assertEquals(GearStatsDeriver.IRON_ARMOR_BASE, GearStatsDeriver.armorBaseUnit(0));
        assertEquals(GearStatsDeriver.IRON_ARMOR_BASE, GearStatsDeriver.armorBaseUnit(100));
    }

    @Test
    void armorBaseUnit_atNetheriteReference_clampsToNetheriteBase() {
        // (2031-250)/1311 * 18 + 15 = 39.45, above the netherite base -- must clamp.
        assertEquals(GearStatsDeriver.NETHERITE_ARMOR_BASE,
                GearStatsDeriver.armorBaseUnit(AlloyRegistry.NETHERITE_MAX_DURABILITY));
    }

    @Test
    void armorBaseUnit_isMonotonic() {
        int previous = GearStatsDeriver.armorBaseUnit(0);
        for (int durability = 0; durability <= 2100; durability += 7) {
            int current = GearStatsDeriver.armorBaseUnit(durability);
            assertTrue(current >= previous,
                    "more durable alloys must never derive a smaller armor base");
            previous = current;
        }
    }
```

Add the import `import org.xpfarm.electricfurnace.alloy.AlloyRegistry;` to the test's import block.

- [ ] **Step 2: Run test to verify it fails**

Run: `source ~/.sdkman/bin/sdkman-init.sh && mvn --batch-mode --no-transfer-progress test -Dtest=GearStatsDeriverTest`
Expected: FAIL — `cannot find symbol: method armorBaseUnit(int)`.

- [ ] **Step 3: Write minimal implementation**

Add to `GearStatsDeriver`, with `import org.xpfarm.electricfurnace.alloy.AlloyRegistry;`:

```java
    /** Vanilla iron armor's durability base unit; pairs with {@code AlloyRegistry.IRON_MAX_DURABILITY}. */
    public static final int IRON_ARMOR_BASE = 15;

    /** Vanilla diamond armor's durability base unit. */
    public static final int DIAMOND_ARMOR_BASE = 33;

    /** Vanilla netherite armor's durability base unit; the ceiling for any derived value. */
    public static final int NETHERITE_ARMOR_BASE = 37;

    /**
     * Converts an alloy's tool-scale {@code maxDurability} into vanilla's armor
     * durability base unit.
     *
     * <p>The two scales are deliberately <b>not</b> treated as proportional: vanilla
     * iron is 250 tool durability against an armor base of 15 (a ratio of ~16.7),
     * while diamond is 1561 against 33 (~47.3). Scaling by a flat ratio would badly
     * overstate armor durability at the top of the range. Instead the alloy's
     * <em>position</em> between the iron and diamond tool references is projected onto
     * the interval between the iron and diamond armor bases.
     *
     * <p>The result is clamped to {@code [IRON_ARMOR_BASE, NETHERITE_ARMOR_BASE]}.
     * The upper clamp is reachable in normal use: {@code AlloyRegistry.clampStats}
     * permits any value up to the netherite reference (2031), which projects to ~39.
     *
     * @param maxDurability the alloy's configured, already-clamped tool-scale durability
     * @return the armor base unit to multiply by {@link GearPiece#durabilityFactor()}
     */
    public static int armorBaseUnit(int maxDurability) {
        double span = AlloyRegistry.DIAMOND_MAX_DURABILITY - AlloyRegistry.IRON_MAX_DURABILITY;
        double position = (maxDurability - AlloyRegistry.IRON_MAX_DURABILITY) / span;
        long projected = Math.round(IRON_ARMOR_BASE + position * (DIAMOND_ARMOR_BASE - IRON_ARMOR_BASE));
        return (int) Math.clamp(projected, IRON_ARMOR_BASE, NETHERITE_ARMOR_BASE);
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `source ~/.sdkman/bin/sdkman-init.sh && mvn --batch-mode --no-transfer-progress test -Dtest=GearStatsDeriverTest`
Expected: PASS, 12 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/xpfarm/electricfurnace/gear/GearStatsDeriver.java src/test/java/org/xpfarm/electricfurnace/gear/GearStatsDeriverTest.java
git commit -m "feat(gear): interpolate armor durability base from tool-scale durability"
```

---

### Task 4: `GearStats` and the full `derive`

**Files:**
- Create: `src/main/java/org/xpfarm/electricfurnace/gear/GearStats.java`
- Modify: `src/main/java/org/xpfarm/electricfurnace/gear/GearStatsDeriver.java`
- Test: `src/test/java/org/xpfarm/electricfurnace/gear/GearStatsDeriverTest.java`

**Interfaces:**
- Consumes: `AlloyStats` (existing record: `attackDamage`, `attackSpeed`, `armor`, `armorToughness`, `maxDurability`, `enchantability`), `GearPiece`, `splitArmor`, `armorBaseUnit`.
- Produces: `record GearStats(double attackDamage, double attackSpeed, int armor, double armorToughness, int maxDurability, int enchantability)` and `public static GearStats derive(AlloyStats stats, GearPiece piece)`.

- [ ] **Step 1: Write the failing test**

Append to `GearStatsDeriverTest`:

```java
    // Steel, as shipped in config.yml.
    private static final AlloyStats STEEL = new AlloyStats(6.5, -2.6, 16, 1.0, 700, 12);

    @Test
    void derive_sword_usesConfiguredCombatStatsAndToolDurability() {
        GearStats stats = GearStatsDeriver.derive(STEEL, GearPiece.SWORD);

        assertEquals(6.5, stats.attackDamage());
        assertEquals(-2.6, stats.attackSpeed());
        assertEquals(700, stats.maxDurability());
        assertEquals(12, stats.enchantability());
        assertEquals(0, stats.armor());
        assertEquals(0.0, stats.armorToughness());
    }

    @Test
    void derive_axe_appliesTheVanillaAxeDelta() {
        GearStats stats = GearStatsDeriver.derive(STEEL, GearPiece.AXE);

        assertEquals(8.5, stats.attackDamage(), 1e-9);
        assertEquals(-3.2, stats.attackSpeed(), 1e-9);
        assertEquals(700, stats.maxDurability());
    }

    @Test
    void derive_armor_matchesTheWorkedExampleInTheSpec() {
        assertEquals(2, GearStatsDeriver.derive(STEEL, GearPiece.HELMET).armor());
        assertEquals(7, GearStatsDeriver.derive(STEEL, GearPiece.CHESTPLATE).armor());
        assertEquals(5, GearStatsDeriver.derive(STEEL, GearPiece.LEGGINGS).armor());
        assertEquals(2, GearStatsDeriver.derive(STEEL, GearPiece.BOOTS).armor());

        assertEquals(231, GearStatsDeriver.derive(STEEL, GearPiece.HELMET).maxDurability());
        assertEquals(336, GearStatsDeriver.derive(STEEL, GearPiece.CHESTPLATE).maxDurability());
        assertEquals(315, GearStatsDeriver.derive(STEEL, GearPiece.LEGGINGS).maxDurability());
        assertEquals(273, GearStatsDeriver.derive(STEEL, GearPiece.BOOTS).maxDurability());
    }

    @Test
    void derive_armor_carriesToughnessPerPieceNotSplit() {
        // Vanilla grants diamond's 2.0 toughness on every piece, not 0.5 each.
        for (GearPiece piece : GearPiece.armorPieces()) {
            assertEquals(1.0, GearStatsDeriver.derive(STEEL, piece).armorToughness());
        }
    }

    @Test
    void derive_armor_hasNoCombatStats() {
        for (GearPiece piece : GearPiece.armorPieces()) {
            GearStats stats = GearStatsDeriver.derive(STEEL, piece);
            assertEquals(0.0, stats.attackDamage());
            assertEquals(0.0, stats.attackSpeed());
        }
    }

    @Test
    void derive_enchantabilityPassesThroughToEveryPiece() {
        for (GearPiece piece : GearPiece.values()) {
            assertEquals(12, GearStatsDeriver.derive(STEEL, piece).enchantability());
        }
    }

    @Test
    void derive_armorAcrossAllPieces_sumsToTheConfiguredTotal() {
        int sum = GearPiece.armorPieces().stream()
                .mapToInt(piece -> GearStatsDeriver.derive(STEEL, piece).armor())
                .sum();
        assertEquals(STEEL.armor(), sum);
    }

    @Test
    void derive_axeDamageMayExceedTheNetheriteSwordReference() {
        // Electrum Steel: 6.8 + 2.0 = 8.8, above NETHERITE_ATTACK_DAMAGE (8.0).
        // Deliberate -- the references are sword-scale, and vanilla's netherite axe
        // does 10.0 against its sword's 8.0. Clamping here would make every alloy axe
        // worse than its own sword is allowed to be.
        AlloyStats electrumSteel = new AlloyStats(6.8, -2.4, 18, 1.5, 900, 16);

        assertEquals(8.8, GearStatsDeriver.derive(electrumSteel, GearPiece.AXE).attackDamage(), 1e-9);
        assertTrue(GearStatsDeriver.derive(electrumSteel, GearPiece.AXE).attackDamage()
                > AlloyRegistry.NETHERITE_ATTACK_DAMAGE);
    }
```

Add `import org.xpfarm.electricfurnace.alloy.AlloyStats;` to the test's import block.

- [ ] **Step 2: Run test to verify it fails**

Run: `source ~/.sdkman/bin/sdkman-init.sh && mvn --batch-mode --no-transfer-progress test -Dtest=GearStatsDeriverTest`
Expected: FAIL — `cannot find symbol: class GearStats`.

- [ ] **Step 3: Write minimal implementation**

Create `src/main/java/org/xpfarm/electricfurnace/gear/GearStats.java` (AGPL header first):

```java
package org.xpfarm.electricfurnace.gear;

/**
 * One gear piece's derived stat block.
 *
 * <p>Unlike {@code AlloyStats}, whose {@code armor} is a full-set total and whose
 * {@code maxDurability} is tool-scale, every value here is the concrete number that
 * belongs on one specific item. Armor pieces carry no combat stats and weapons carry
 * no armor stats; the unused fields are zero rather than absent, so the record stays a
 * plain data holder with no optionality to handle.
 *
 * @param attackDamage    attack damage, or {@code 0.0} for armor
 * @param attackSpeed     attack speed modifier, or {@code 0.0} for armor
 * @param armor           armor points for this one piece, or {@code 0} for weapons
 * @param armorToughness  armor toughness for this one piece, or {@code 0.0} for weapons
 * @param maxDurability   this item's maximum durability
 * @param enchantability  this item's enchantability
 */
public record GearStats(
        double attackDamage,
        double attackSpeed,
        int armor,
        double armorToughness,
        int maxDurability,
        int enchantability
) {
}
```

Add to `GearStatsDeriver`, with `import org.xpfarm.electricfurnace.alloy.AlloyStats;` and `import java.util.Objects;`:

```java
    /**
     * Derives one piece's stats from an alloy's stat block.
     *
     * <p>No balance clamp runs here. {@code AlloyRegistry.clampStats} has already
     * clamped {@code stats} at load time, so gear inherits clamped values for free --
     * and re-clamping the derived axe damage would be wrong, since vanilla's own
     * netherite axe exceeds its netherite sword.
     *
     * @param stats the alloy's already-clamped stat block
     * @param piece the piece being derived
     * @return the concrete stat block for that one item
     */
    public static GearStats derive(AlloyStats stats, GearPiece piece) {
        Objects.requireNonNull(stats, "stats");
        Objects.requireNonNull(piece, "piece");

        if (piece.kind() == GearPiece.Kind.WEAPON) {
            return new GearStats(
                    stats.attackDamage() + piece.attackDamageDelta(),
                    stats.attackSpeed() + piece.attackSpeedDelta(),
                    0,
                    0.0,
                    stats.maxDurability(),
                    stats.enchantability());
        }

        return new GearStats(
                0.0,
                0.0,
                splitArmor(stats.armor()).get(piece),
                stats.armorToughness(),
                armorBaseUnit(stats.maxDurability()) * piece.durabilityFactor(),
                stats.enchantability());
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `source ~/.sdkman/bin/sdkman-init.sh && mvn --batch-mode --no-transfer-progress test -Dtest=GearStatsDeriverTest`
Expected: PASS, 20 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/xpfarm/electricfurnace/gear/GearStats.java src/main/java/org/xpfarm/electricfurnace/gear/GearStatsDeriver.java src/test/java/org/xpfarm/electricfurnace/gear/GearStatsDeriverTest.java
git commit -m "feat(gear): derive per-piece stats from an alloy stat block"
```

---

### Task 5: `GearBase` — base material families

**Files:**
- Create: `src/main/java/org/xpfarm/electricfurnace/gear/GearBase.java`
- Test: `src/test/java/org/xpfarm/electricfurnace/gear/GearBaseTest.java`

**Interfaces:**
- Consumes: `GearPiece`.
- Produces: `enum GearBase { COPPER, IRON, GOLD, DIAMOND, NETHERITE }` with `String id()`, `String materialName(GearPiece)`, `boolean fireImmuneByDefault()`; statics `Optional<GearBase> byId(String)`, `GearBase defaultFor(String alloyId)`.

`materialName` returns a `String` rather than a Bukkit `Material` on purpose. It keeps this enum pure and unit-testable, and it hands `GearItemFactory` exactly what it needs for the `Material.getMaterial(name)` guard that copper equipment requires — those constants only exist from 1.21.9, so a direct reference would throw `NoSuchFieldError` at class-load on an older server.

Note the vanilla naming irregularity: gold's prefix is `GOLDEN_`, not `GOLD_`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/org/xpfarm/electricfurnace/gear/GearBaseTest.java` (AGPL header first):

```java
package org.xpfarm.electricfurnace.gear;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link GearBase}. Pure -- no running server. */
class GearBaseTest {

    @Test
    void materialName_usesTheGoldenPrefixForGold() {
        // Vanilla names gold equipment GOLDEN_*, not GOLD_*. Getting this wrong yields
        // a null Material at runtime and silently drops the alloy's gear.
        assertEquals("GOLDEN_SWORD", GearBase.GOLD.materialName(GearPiece.SWORD));
        assertEquals("GOLDEN_CHESTPLATE", GearBase.GOLD.materialName(GearPiece.CHESTPLATE));
    }

    @Test
    void materialName_coversEveryBaseAndPiece() {
        assertEquals("IRON_SWORD", GearBase.IRON.materialName(GearPiece.SWORD));
        assertEquals("COPPER_AXE", GearBase.COPPER.materialName(GearPiece.AXE));
        assertEquals("DIAMOND_HELMET", GearBase.DIAMOND.materialName(GearPiece.HELMET));
        assertEquals("NETHERITE_BOOTS", GearBase.NETHERITE.materialName(GearPiece.BOOTS));
        assertEquals("IRON_LEGGINGS", GearBase.IRON.materialName(GearPiece.LEGGINGS));
    }

    @Test
    void defaultFor_mapsTheFiveShippedAlloys() {
        assertEquals(GearBase.IRON, GearBase.defaultFor("steel"));
        assertEquals(GearBase.GOLD, GearBase.defaultFor("rose_gold"));
        assertEquals(GearBase.COPPER, GearBase.defaultFor("ferrocopper"));
        assertEquals(GearBase.DIAMOND, GearBase.defaultFor("electrum_steel"));
        assertEquals(GearBase.NETHERITE, GearBase.defaultFor("fused_alloy"));
    }

    @Test
    void defaultFor_unknownAlloyFallsBackToIron() {
        // Operators may add their own alloys; they must still get working gear.
        assertEquals(GearBase.IRON, GearBase.defaultFor("operator_invented_alloy"));
        assertEquals(GearBase.IRON, GearBase.defaultFor(null));
    }

    @Test
    void byId_isCaseInsensitiveAndRejectsUnknown() {
        assertEquals(Optional.of(GearBase.NETHERITE), GearBase.byId("netherite"));
        assertEquals(Optional.of(GearBase.NETHERITE), GearBase.byId("NETHERITE"));
        assertTrue(GearBase.byId("mithril").isEmpty());
        assertTrue(GearBase.byId(null).isEmpty());
    }

    @Test
    void onlyNetheriteIsFireImmuneByDefault() {
        assertTrue(GearBase.NETHERITE.fireImmuneByDefault());
        assertFalse(GearBase.IRON.fireImmuneByDefault());
        assertFalse(GearBase.GOLD.fireImmuneByDefault());
        assertFalse(GearBase.COPPER.fireImmuneByDefault());
        assertFalse(GearBase.DIAMOND.fireImmuneByDefault());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `source ~/.sdkman/bin/sdkman-init.sh && mvn --batch-mode --no-transfer-progress test -Dtest=GearBaseTest`
Expected: FAIL — `cannot find symbol: class GearBase`.

- [ ] **Step 3: Write minimal implementation**

Create `src/main/java/org/xpfarm/electricfurnace/gear/GearBase.java` (AGPL header first):

```java
package org.xpfarm.electricfurnace.gear;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The vanilla base material family a given alloy's gear is built on.
 *
 * <p>With no resource pack, base material is the <b>only</b> way two alloys' gear can
 * look different from each other, on Java and Bedrock alike. These five families are
 * exactly the vanilla tiers that have both weapon and armor forms -- chainmail and
 * leather have no sword -- which is why there are five, matching the five shipped
 * alloys.
 *
 * <p>{@link #materialName(GearPiece)} deliberately returns a {@code String} rather
 * than a Bukkit {@code Material}. That keeps this enum pure and unit-testable, and it
 * lets {@code GearItemFactory} resolve names through {@code Material.getMaterial},
 * which is required anyway: copper equipment only exists from 1.21.9, so a direct
 * {@code Material.COPPER_SWORD} reference would throw {@code NoSuchFieldError} at
 * class-load on an older server.
 */
public enum GearBase {

    COPPER("copper", "COPPER", false),
    IRON("iron", "IRON", false),
    /** Vanilla spells gold equipment {@code GOLDEN_*}, not {@code GOLD_*}. */
    GOLD("gold", "GOLDEN", false),
    DIAMOND("diamond", "DIAMOND", false),
    NETHERITE("netherite", "NETHERITE", true);

    /**
     * Default base per shipped alloy id. Thematic rather than arbitrary: steel is
     * hardened iron, rose gold is a gold blend, ferrocopper is copper, electrum steel
     * is the strongest alloy, and fused alloy's {@code #4B4B4B} reads as netherite.
     */
    private static final Map<String, GearBase> DEFAULTS = Map.of(
            "steel", IRON,
            "rose_gold", GOLD,
            "ferrocopper", COPPER,
            "electrum_steel", DIAMOND,
            "fused_alloy", NETHERITE);

    /** Base used for any alloy an operator added that is not in {@link #DEFAULTS}. */
    private static final GearBase FALLBACK = IRON;

    private final String id;
    private final String materialPrefix;
    private final boolean fireImmuneByDefault;

    GearBase(String id, String materialPrefix, boolean fireImmuneByDefault) {
        this.id = id;
        this.materialPrefix = materialPrefix;
        this.fireImmuneByDefault = fireImmuneByDefault;
    }

    /** The token an operator writes in {@code alloys.<id>.base}. */
    public String id() {
        return id;
    }

    /**
     * Whether items on this base carry vanilla fire immunity that must be stripped.
     *
     * <p>Only netherite does, via the {@code minecraft:damage_resistant} component
     * (renamed from {@code fire_resistant} in 1.21.2).
     */
    public boolean fireImmuneByDefault() {
        return fireImmuneByDefault;
    }

    /** The Bukkit {@code Material} name for this base and piece, e.g. {@code "GOLDEN_SWORD"}. */
    public String materialName(GearPiece piece) {
        return materialPrefix + "_" + piece.name();
    }

    /** Resolves a configured token to a base, case-insensitively. */
    public static Optional<GearBase> byId(String token) {
        if (token == null) {
            return Optional.empty();
        }
        String normalized = token.toLowerCase(Locale.ROOT);
        for (GearBase base : values()) {
            if (base.id.equals(normalized)) {
                return Optional.of(base);
            }
        }
        return Optional.empty();
    }

    /** The default base for an alloy id; {@link #FALLBACK} for anything unrecognized. */
    public static GearBase defaultFor(String alloyId) {
        if (alloyId == null) {
            return FALLBACK;
        }
        return DEFAULTS.getOrDefault(alloyId.toLowerCase(Locale.ROOT), FALLBACK);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `source ~/.sdkman/bin/sdkman-init.sh && mvn --batch-mode --no-transfer-progress test -Dtest=GearBaseTest`
Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/xpfarm/electricfurnace/gear/GearBase.java src/test/java/org/xpfarm/electricfurnace/gear/GearBaseTest.java
git commit -m "feat(gear): add GearBase mapping alloys to vanilla base materials"
```

---

### Task 6: Parse `alloys.<id>.base` into `AlloyDefinition`

**Files:**
- Modify: `src/main/java/org/xpfarm/electricfurnace/alloy/AlloyDefinition.java`
- Modify: `src/main/java/org/xpfarm/electricfurnace/alloy/AlloyRegistry.java`
- Test: `src/test/java/org/xpfarm/electricfurnace/alloy/AlloyRegistryTest.java`

**Interfaces:**
- Consumes: `GearBase` from Task 5.
- Produces: `AlloyDefinition` gains a trailing `GearBase base` component. `AlloyRegistry.load` reads `alloys.<id>.base`, warns and falls back on an unknown value, and defaults via `GearBase.defaultFor(id)` when absent.

**Before writing code, find every existing `new AlloyDefinition(...)` call:**

```bash
grep -rn "new AlloyDefinition(" src/
```

Every one needs the new trailing argument. Most are in `AlloyRegistryTest` and `RecycleResolverTest`.

- [ ] **Step 1: Write the failing test**

Append to `AlloyRegistryTest`:

```java
    @Test
    void load_absentBaseKey_usesTheThematicDefaultForThatAlloy() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("steel.display-name", "Steel");
        yaml.set("steel.color", "#71797E");
        yaml.set("steel.inputs", List.of("iron", "coal"));
        yaml.set("steel.stats.attack-damage", 6.5);
        yaml.set("steel.stats.attack-speed", -2.6);
        yaml.set("steel.stats.armor", 16);
        yaml.set("steel.stats.armor-toughness", 1.0);
        yaml.set("steel.stats.max-durability", 700);
        yaml.set("steel.stats.enchantability", 12);

        AlloyRegistry registry = AlloyRegistry.load(yaml.getConfigurationSection(""), this::warn);

        assertEquals(GearBase.IRON, baseOf(registry, "steel"));
    }

    /**
     * {@code AlloyRegistry} exposes {@code all()} (a {@code Collection}), not a
     * by-id lookup. Find the definition through it rather than widening the registry's
     * API for a test's convenience.
     */
    private static GearBase baseOf(AlloyRegistry registry, String alloyId) {
        return registry.all().stream()
                .filter(definition -> definition.id().equals(alloyId))
                .findFirst()
                .orElseThrow()
                .base();
    }

    @Test
    void load_explicitBaseKey_overridesTheDefault() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("steel.display-name", "Steel");
        yaml.set("steel.color", "#71797E");
        yaml.set("steel.base", "diamond");
        yaml.set("steel.inputs", List.of("iron", "coal"));
        yaml.set("steel.stats.attack-damage", 6.5);
        yaml.set("steel.stats.attack-speed", -2.6);
        yaml.set("steel.stats.armor", 16);
        yaml.set("steel.stats.armor-toughness", 1.0);
        yaml.set("steel.stats.max-durability", 700);
        yaml.set("steel.stats.enchantability", 12);

        AlloyRegistry registry = AlloyRegistry.load(yaml.getConfigurationSection(""), this::warn);

        assertEquals(GearBase.DIAMOND, baseOf(registry, "steel"));
    }

    @Test
    void load_unknownBaseKey_warnsAndFallsBackToTheDefault() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("steel.display-name", "Steel");
        yaml.set("steel.color", "#71797E");
        yaml.set("steel.base", "mithril");
        yaml.set("steel.inputs", List.of("iron", "coal"));
        yaml.set("steel.stats.attack-damage", 6.5);
        yaml.set("steel.stats.attack-speed", -2.6);
        yaml.set("steel.stats.armor", 16);
        yaml.set("steel.stats.armor-toughness", 1.0);
        yaml.set("steel.stats.max-durability", 700);
        yaml.set("steel.stats.enchantability", 12);

        AlloyRegistry registry = AlloyRegistry.load(yaml.getConfigurationSection(""), this::warn);

        assertEquals(GearBase.IRON, baseOf(registry, "steel"),
                "an unusable base must degrade to the default, never disable the alloy");
        assertTrue(warnings.stream().anyMatch(w -> w.contains("steel") && w.contains("mithril")),
                "the warning must name both the alloy and the offending value; warnings were: " + warnings);
    }
```

Add `import org.xpfarm.electricfurnace.gear.GearBase;` to the test's import block.

**Correction (found during execution): the premise behind `baseOf` was wrong.** `AlloyRegistry.get(String id)` returning `Optional<AlloyDefinition>` already exists and is already used by pre-existing tests. The plan's earlier claim that only `all()` was available came from a grep that searched for the wrong method name. Use `registry.get(id).orElseThrow().base()` directly and do not add the `baseOf` helper.

- [ ] **Step 2: Run test to verify it fails**

Run: `source ~/.sdkman/bin/sdkman-init.sh && mvn --batch-mode --no-transfer-progress test -Dtest=AlloyRegistryTest`
Expected: FAIL — `cannot find symbol: method base()`.

- [ ] **Step 3: Write minimal implementation**

In `AlloyDefinition`, add the component and update the javadoc:

```java
public record AlloyDefinition(
        String id,
        String displayName,
        List<String> lore,
        String color,
        Set<String> inputIds,
        AlloyStats stats,
        GearBase base
) {
```

Add `@param base the vanilla base material family this alloy's gear is built on` to the record javadoc, and `import org.xpfarm.electricfurnace.gear.GearBase;`.

In `AlloyRegistry.load`, where each definition is built from its `ConfigurationSection`, parse the base:

```java
        String rawBase = section.getString("base");
        GearBase base;
        if (rawBase == null) {
            base = GearBase.defaultFor(id);
        } else {
            Optional<GearBase> parsed = GearBase.byId(rawBase);
            if (parsed.isPresent()) {
                base = parsed.get();
            } else {
                base = GearBase.defaultFor(id);
                warn.accept("Alloy '" + id + "' has an unrecognized base '" + rawBase
                        + "'; falling back to '" + base.id() + "'.");
            }
        }
```

Then pass `base` as the final argument to the `new AlloyDefinition(...)` call.

Update every other `new AlloyDefinition(...)` in `src/main` and `src/test` to pass a base — `GearBase.IRON` is the right neutral choice for existing tests that do not care. In `AlloyRegistry.fromDefinitions`, the synthesized fallback definition should use `GearBase.defaultFor(SYNTHESIZED_FALLBACK_ID)`.

- [ ] **Step 4: Run the full suite to verify nothing regressed**

Run: `source ~/.sdkman/bin/sdkman-init.sh && mvn --batch-mode --no-transfer-progress test`
Expected: PASS, 334 tests (311 existing + 20 from Tasks 1–5 + 3 new here). If any existing test fails to compile, it is a missed `new AlloyDefinition(...)` call site.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/xpfarm/electricfurnace/alloy/ src/test/java/org/xpfarm/electricfurnace/
git commit -m "feat(gear): parse alloys.<id>.base with warn-and-default on unknown values"
```

---

### Task 7: The `xpfarm:gear_piece` PDC key

**Files:**
- Modify: `src/main/java/org/xpfarm/electricfurnace/item/MaterialContract.java`

**Interfaces:**
- Produces: `public static final NamespacedKey GEAR_PIECE` and `public static Optional<String> readGearPiece(ItemStack)`.

This key is part of the **shared cross-plugin contract**, so it must use the two-argument string `NamespacedKey` constructor like its neighbours — never the `NamespacedKey(Plugin, String)` form.

- [ ] **Step 1: Add the key**

In `MaterialContract`, directly below `MATERIAL_ID`:

```java
    /** STRING: the gear piece id for a minted gear item, e.g. {@code "chestplate"}. */
    public static final NamespacedKey GEAR_PIECE = new NamespacedKey("xpfarm", "gear_piece");
```

And beside `readMaterialId`:

```java
    /** Reads {@link #GEAR_PIECE} off {@code stack}, if present. */
    public static Optional<String> readGearPiece(ItemStack stack) {
        return readString(stack, GEAR_PIECE);
    }
```

- [ ] **Step 2: Verify it compiles and the suite stays green**

Run: `source ~/.sdkman/bin/sdkman-init.sh && mvn --batch-mode --no-transfer-progress test`
Expected: PASS, 334 tests.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/org/xpfarm/electricfurnace/item/MaterialContract.java
git commit -m "feat(gear): add xpfarm:gear_piece to the shared PDC contract"
```

---

### Task 8: Relax `RecycleResolver` rule 2 to all-same-alloy

**Files:**
- Modify: `src/main/java/org/xpfarm/electricfurnace/recycle/RecycleResolver.java`
- Test: `src/test/java/org/xpfarm/electricfurnace/recycle/RecycleResolverTest.java`

**Interfaces:**
- Consumes: existing `RecycleInput`, `RecycleResult`, `RecyclingSettings`.
- Produces: no signature change. Rule 2 becomes "**all** inputs are alloys"; same id → `Remelt(id, n × yieldRemeltAlloy)`; mixed ids → `Rejected("mixed alloys")`.

Today a player recycling a four-piece alloy set is told `"non-metal input"`, which is not merely unhelpful but wrong — the inputs are neither non-metal nor unrecognized. Rule 2 must stay ahead of the slot-count rule, since remelt is still the one path accepting fewer than `slots` items.

**Correction (found during execution): this premise was wrong.** `RecycleResolverTest` has **no** case asserting that multiple alloy items reject as `"non-metal input"`. The closest test, `rule4_alloyItemAmongOthers_isTreatedAsNonMetalInput`, uses four iron plus *one* alloy — not all-alloy, so its assertion stays correct and must **not** be changed to expect `"mixed alloys"`. Doing so would introduce a bug. Rename it and refresh its now-stale comment instead. Locate it with:

```bash
grep -n "non-metal input" src/test/java/org/xpfarm/electricfurnace/recycle/RecycleResolverTest.java
```

- [ ] **Step 1: Write the failing test**

Append to `RecycleResolverTest`, matching that file's existing helper style for building a `RecycleInput` and `RecyclingSettings`:

```java
    @Test
    void allSameAlloy_remeltsAtNTimesTheYield() {
        List<RecycleInput> inputs = List.of(
                alloyInput("steel"), alloyInput("steel"), alloyInput("steel"), alloyInput("steel"));

        RecycleResult result = RecycleResolver.resolve(inputs, settings(), registry());

        assertInstanceOf(RecycleResult.Remelt.class, result);
        RecycleResult.Remelt remelt = (RecycleResult.Remelt) result;
        assertEquals("steel", remelt.alloyId());
        assertEquals(4 * settings().yieldRemeltAlloy(), remelt.amount());
    }

    @Test
    void singleAlloy_stillRemeltsAtTheBaseYield() {
        RecycleResult result = RecycleResolver.resolve(List.of(alloyInput("steel")), settings(), registry());

        assertInstanceOf(RecycleResult.Remelt.class, result);
        assertEquals(settings().yieldRemeltAlloy(), ((RecycleResult.Remelt) result).amount());
    }

    @Test
    void mixedAlloys_rejectWithAMessageNamingTheActualProblem() {
        List<RecycleInput> inputs = List.of(alloyInput("steel"), alloyInput("rose_gold"));

        RecycleResult result = RecycleResolver.resolve(inputs, settings(), registry());

        assertInstanceOf(RecycleResult.Rejected.class, result);
        assertEquals("mixed alloys", ((RecycleResult.Rejected) result).reason());
    }

    @Test
    void alloyMixedWithPlainMetal_isStillANonMetalInput() {
        // Not all-alloy, so rule 2 does not apply; it falls through to rule 4 as before.
        List<RecycleInput> inputs = List.of(
                alloyInput("steel"), ironInput(), ironInput(), ironInput(), ironInput());

        RecycleResult result = RecycleResolver.resolve(inputs, settings(), registry());

        assertInstanceOf(RecycleResult.Rejected.class, result);
        assertEquals("non-metal input", ((RecycleResult.Rejected) result).reason());
    }
```

If `RecycleResolverTest` has no `alloyInput` / `ironInput` / `settings` / `registry` helpers, reuse whatever it does have — do not introduce a parallel set.

- [ ] **Step 2: Run test to verify it fails**

Run: `source ~/.sdkman/bin/sdkman-init.sh && mvn --batch-mode --no-transfer-progress test -Dtest=RecycleResolverTest`
Expected: FAIL — `allSameAlloy_remeltsAtNTimesTheYield` gets `Rejected("non-metal input")` instead of a `Remelt`.

- [ ] **Step 3: Write minimal implementation**

In `RecycleResolver.resolve`, replace the existing rule 2 block:

```java
        // Rule 2: a single alloy item remelts -- the only case accepting fewer than
        // `slots` items, so it must be checked before the slot-count rule below.
        if (inputs.size() == 1) {
            RecycleInput only = inputs.get(0);
            if (only.isAlloy()) {
                return new RecycleResult.Remelt(only.alloyId(), settings.yieldRemeltAlloy());
            }
        }
```

with:

```java
        // Rule 2: every input is an alloy item -- the only case accepting fewer than
        // `slots` items, so it must be checked before the slot-count rule below.
        // Relaxed from "exactly one alloy" so a four-piece alloy armour set remelts the
        // obvious way; previously anything past the first alloy fell through to rule 4
        // and was rejected as "non-metal input", which was actively misleading.
        if (inputs.stream().allMatch(RecycleInput::isAlloy)) {
            Set<String> alloyIds = inputs.stream()
                    .map(RecycleInput::alloyId)
                    .collect(Collectors.toSet());
            if (alloyIds.size() > 1) {
                return new RecycleResult.Rejected("mixed alloys");
            }
            return new RecycleResult.Remelt(
                    inputs.get(0).alloyId(), inputs.size() * settings.yieldRemeltAlloy());
        }
```

Update the class javadoc's numbered rule list: rule 2 now reads "Every input is an alloy item → `REMELT` at `n × yield-remelt-alloy`, or `REJECTED("mixed alloys")` if the alloy ids differ. The **only** case that accepts fewer than `recycling.slots` items." Rule 4's parenthetical should now read "(rule 2 already handled the all-alloy case)".

- [ ] **Step 4: Run the full suite**

Run: `source ~/.sdkman/bin/sdkman-init.sh && mvn --batch-mode --no-transfer-progress test`
Expected: PASS, 338 tests. If the pre-existing multiple-alloy test still asserts `"non-metal input"`, update it to expect `"mixed alloys"` — that is the behaviour change this task exists to make.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/xpfarm/electricfurnace/recycle/RecycleResolver.java src/test/java/org/xpfarm/electricfurnace/recycle/RecycleResolverTest.java
git commit -m "feat(gear): remelt N same-alloy items, reject mixed alloys by name"
```

---

### Task 9: `GearItemFactory`

**Files:**
- Create: `src/main/java/org/xpfarm/electricfurnace/item/GearItemFactory.java`

**Interfaces:**
- Consumes: `AlloyDefinition` (with `base()`), `GearPiece`, `GearStats`, `GearStatsDeriver.derive`, `MaterialContract`.
- Produces: `public static Optional<ItemStack> create(AlloyDefinition definition, GearPiece piece)` — empty when the base material does not exist on this server.

Like `AlloyItemFactory`, this is Bukkit-facing glue with no unit test: constructing a real `ItemStack` needs a live server. Every decision worth testing already lives in Tasks 1–5. Verification is at gate 7a.

Four things here are easy to get wrong and each is called out in the code comments:

1. `Attribute` is not an enum on this version — no `EnumMap`, no `GENERIC_*`.
2. Writing attribute modifiers replaces vanilla defaults, so the item's full modifier set must be written.
3. Netherite's fire immunity is stripped with `unsetData`, not `resetData` — `resetData` restores the type default and puts immunity back.
4. `setMaxDamage` is on `Damageable`, not `ItemMeta`.

- [ ] **Step 1: Write the implementation**

Create `src/main/java/org/xpfarm/electricfurnace/item/GearItemFactory.java` (AGPL header first):

```java
package org.xpfarm.electricfurnace.item;

import io.papermc.paper.datacomponent.DataComponentTypes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.xpfarm.electricfurnace.alloy.AlloyDefinition;
import org.xpfarm.electricfurnace.gear.GearPiece;
import org.xpfarm.electricfurnace.gear.GearStats;
import org.xpfarm.electricfurnace.gear.GearStatsDeriver;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Mints alloy gear {@code ItemStack}s, beside {@link AlloyItemFactory}.
 *
 * <p>Base material comes from the alloy's {@code base}, so Steel gear is iron-shaped
 * and Rose Gold gear is gold-shaped. As everywhere in this plugin, there is <b>no</b>
 * {@code setCustomModelData} and no {@code item_model} component -- those are
 * invisible to Bedrock without an authored resource pack, and base material is the
 * whole visual-identity mechanism instead.
 *
 * <p>Every minted item carries {@code xpfarm:custom_material} and
 * {@code xpfarm:material_id} -- which is exactly what makes gear classify as an alloy
 * remelt in {@code MetalClassifier} with no change there -- plus
 * {@code xpfarm:gear_piece}.
 */
public final class GearItemFactory {

    /** Namespace for this plugin's attribute modifiers, so they are identifiable and replaceable. */
    private static final String MODIFIER_NAMESPACE = "electricfurnace";

    private GearItemFactory() {
    }

    /**
     * Builds one gear {@code ItemStack}.
     *
     * @return the item, or {@link Optional#empty()} if this server has no such base
     *         material -- copper equipment only exists from 1.21.9
     */
    public static Optional<ItemStack> create(AlloyDefinition definition, GearPiece piece) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(piece, "piece");

        // Material.getMaterial, never a direct constant: a hard reference to
        // Material.COPPER_SWORD throws NoSuchFieldError at class-load on <1.21.9.
        Material material = Material.getMaterial(definition.base().materialName(piece));
        if (material == null) {
            return Optional.empty();
        }

        GearStats stats = GearStatsDeriver.derive(definition.stats(), piece);
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();

        meta.displayName(Component.text(definition.displayName() + " " + piece.displayName())
                .color(parseColor(definition.color()))
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(definition.lore().stream()
                .<Component>map(line -> Component.text(line).decoration(TextDecoration.ITALIC, false))
                .toList());

        meta.getPersistentDataContainer().set(MaterialContract.CUSTOM_MATERIAL, PersistentDataType.STRING,
                MaterialContract.OWNING_SYSTEM_ELECTRICFURNACE);
        meta.getPersistentDataContainer().set(MaterialContract.MATERIAL_ID, PersistentDataType.STRING,
                definition.id());
        meta.getPersistentDataContainer().set(MaterialContract.GEAR_PIECE, PersistentDataType.STRING,
                piece.id());

        applyAttributes(meta, piece, stats);

        // setMaxDamage lives on Damageable, not ItemMeta. Java shows this value; Geyser
        // scales the Bedrock durability bar against the base item's vanilla maximum
        // instead -- a recorded, cosmetic-only limitation.
        if (meta instanceof Damageable damageable) {
            damageable.setMaxDamage(stats.maxDurability());
        }

        // Stable API since 1.21.2. Java-only in effect: Geyser reads enchantability
        // from the item type at handshake, never per stack.
        meta.setEnchantable(stats.enchantability());

        stack.setItemMeta(meta);

        // Strip netherite's inherited fire immunity. unsetData, NOT resetData --
        // resetData restores the item type's default, which puts immunity straight back.
        if (definition.base().fireImmuneByDefault()) {
            stack.unsetData(DataComponentTypes.DAMAGE_RESISTANT);
        }
        return Optional.of(stack);
    }

    /**
     * Writes this item's complete attribute modifier set.
     *
     * <p><b>Writing any modifier replaces the item type's vanilla defaults rather than
     * merging with them</b>, so every stat the item should have must be written here.
     * Adding only {@code +2 ATTACK_DAMAGE} to a netherite sword would yield a sword
     * dealing 2 damage, not 10.
     *
     * <p>Netherite's knockback resistance disappears as a welcome side effect of that
     * same replacement: it is simply never written back. Do <b>not</b> write an empty
     * modifier set, which would strip armor points too.
     *
     * <p>Note {@code Attribute} is an interface on this Paper version, not an enum --
     * {@code EnumMap}, {@code Attribute.values()}, and {@code GENERIC_*} constants all
     * fail here.
     */
    private static void applyAttributes(ItemMeta meta, GearPiece piece, GearStats stats) {
        if (piece.kind() == GearPiece.Kind.WEAPON) {
            addModifier(meta, Attribute.ATTACK_DAMAGE, piece.id() + "_attack_damage",
                    stats.attackDamage(), EquipmentSlotGroup.MAINHAND);
            addModifier(meta, Attribute.ATTACK_SPEED, piece.id() + "_attack_speed",
                    stats.attackSpeed(), EquipmentSlotGroup.MAINHAND);
            return;
        }

        EquipmentSlotGroup slot = armorSlotGroup(piece);
        addModifier(meta, Attribute.ARMOR, piece.id() + "_armor", stats.armor(), slot);
        addModifier(meta, Attribute.ARMOR_TOUGHNESS, piece.id() + "_armor_toughness",
                stats.armorToughness(), slot);
    }

    private static void addModifier(ItemMeta meta, Attribute attribute, String name, double amount,
            EquipmentSlotGroup slot) {
        meta.addAttributeModifier(attribute, new AttributeModifier(
                new NamespacedKey(MODIFIER_NAMESPACE, name),
                amount,
                AttributeModifier.Operation.ADD_NUMBER,
                slot));
    }

    /**
     * The slot an armor piece must be worn in for its modifiers to apply.
     *
     * <p>A plain {@code switch} rather than a table on {@link GearPiece}: keeping the
     * Bukkit {@code EquipmentSlotGroup} out of that enum is what lets it stay pure and
     * unit-testable with no running server.
     */
    private static EquipmentSlotGroup armorSlotGroup(GearPiece piece) {
        return switch (piece) {
            case HELMET -> EquipmentSlotGroup.HEAD;
            case CHESTPLATE -> EquipmentSlotGroup.CHEST;
            case LEGGINGS -> EquipmentSlotGroup.LEGS;
            case BOOTS -> EquipmentSlotGroup.FEET;
            case SWORD, AXE -> EquipmentSlotGroup.MAINHAND;
        };
    }

    private static TextColor parseColor(String hex) {
        TextColor color = TextColor.fromHexString(hex);
        return color != null ? color : TextColor.fromHexString("#FFFFFF");
    }

    /** Every piece of one alloy, skipping any whose base material this server lacks. */
    public static List<ItemStack> createAll(AlloyDefinition definition) {
        return java.util.Arrays.stream(GearPiece.values())
                .map(piece -> create(definition, piece))
                .flatMap(Optional::stream)
                .toList();
    }
}
```

- [ ] **Step 2: Verify it compiles and the suite stays green**

Run: `source ~/.sdkman/bin/sdkman-init.sh && mvn --batch-mode --no-transfer-progress clean verify`
Expected: BUILD SUCCESS, 338 tests.

If `meta.setEnchantable` or `stack.unsetData` does not resolve, stop and check the Paper API version rather than working around it — both are confirmed present on `26.1.2.build.74-stable`. `unsetData` may require `@SuppressWarnings` or an experimental-API opt-in; follow whatever the compiler asks for.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/org/xpfarm/electricfurnace/item/GearItemFactory.java
git commit -m "feat(gear): mint gear items with derived stats and stripped netherite immunity"
```

---

### Task 10: `GearRecipes` — registration, craft gate, discovery

**Files:**
- Create: `src/main/java/org/xpfarm/electricfurnace/recipe/GearRecipes.java`

**Interfaces:**
- Consumes: `AlloyRegistry`, `AlloyItemFactory.create`, `GearItemFactory.create`, `GearPiece`, `MaterialContract`.
- Produces: `GearRecipes implements Listener` with `public void register()`, `public void unregister()`, `@EventHandler onPrepareCraft(PrepareItemCraftEvent)`, `@EventHandler onJoin(PlayerJoinEvent)`.

**Read `src/main/java/org/xpfarm/electricfurnace/recipe/MachineRecipe.java` first and follow it exactly** — it already solves duplicate-key registration, `unregister()`, and result-blanking for the machine item. This class is the same shape, thirty times over.

Two constraints from the spec:

- **Ingredients must come from `AlloyItemFactory.create`**, never a locally built stack. `ExactChoice` snapshots the ingredient at registration and compares the whole component map, so two independently constructed "identical" ingots that differ in lore order silently never match.
- **`Bukkit.addRecipe` unlocks nothing.** Since 1.21.2 the server sends only unlocked recipes, so without `discoverRecipe` the recipe book is empty on both Java and Bedrock.

- [ ] **Step 1: Write the implementation**

Create `src/main/java/org/xpfarm/electricfurnace/recipe/GearRecipes.java` (AGPL header first):

```java
package org.xpfarm.electricfurnace.recipe;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.xpfarm.electricfurnace.alloy.AlloyDefinition;
import org.xpfarm.electricfurnace.alloy.AlloyRegistry;
import org.xpfarm.electricfurnace.gear.GearPiece;
import org.xpfarm.electricfurnace.item.AlloyItemFactory;
import org.xpfarm.electricfurnace.item.GearItemFactory;
import org.xpfarm.electricfurnace.item.MaterialContract;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Registers the crafting-table recipes for alloy gear, and guards them.
 *
 * <p>Shapes are vanilla, with the alloy ingot substituted for the metal. Ingredients
 * use {@link RecipeChoice.ExactChoice}, which the server matches with
 * {@code isSameItemSameComponents} -- the full data-component map, including the
 * {@code minecraft:custom_data} where PDC lives. A vanilla netherite ingot therefore
 * cannot satisfy these recipes on Java.
 *
 * <p><b>Ingredients must come from {@link AlloyItemFactory}.</b> {@code ExactChoice}
 * snapshots the ingredient stack at registration time and compares it whole, so an
 * ingot built locally here that differed from a minted one by so much as a lore line
 * would produce recipes that silently never match, with no error anywhere.
 *
 * <p><b>Bedrock.</b> Bedrock's recipe ingredient format carries no NBT at all, so
 * every one of these recipes reaches a Bedrock client as "netherite_ingot xN + stick".
 * Real crafting still works -- Geyser synthesizes a one-off recipe from the actual
 * grid contents once the Java server produces a result -- but a Bedrock player can lay
 * out <em>vanilla</em> netherite ingots in one of these shapes and see a phantom
 * output. {@link #onPrepareCraft} is what makes that phantom fail safe: it blanks the
 * result unless the grid genuinely holds stamped ingots, so the worst case is that
 * nothing happens, never a free item.
 *
 * <p>Registration always removes the key first, like {@link MachineRecipe}, because
 * {@code Bukkit.addRecipe} throws on a duplicate {@link NamespacedKey} and recipes
 * survive a plugin reload -- and nothing in the enable path may throw.
 */
public final class GearRecipes implements Listener {

    private final Supplier<AlloyRegistry> alloysSupplier;
    private final Consumer<String> warn;
    private final List<NamespacedKey> registered = new ArrayList<>();

    public GearRecipes(Supplier<AlloyRegistry> alloysSupplier, Consumer<String> warn) {
        this.alloysSupplier = Objects.requireNonNull(alloysSupplier, "alloysSupplier");
        this.warn = Objects.requireNonNull(warn, "warn");
    }

    /** The recipe key for one alloy and piece, e.g. {@code electricfurnace:steel_sword}. */
    public static NamespacedKey keyFor(String alloyId, GearPiece piece) {
        return new NamespacedKey("electricfurnace", alloyId + "_" + piece.id());
    }

    /** Registers all 30 recipes, replacing any already present under the same keys. */
    public void register() {
        unregister();
        for (AlloyDefinition definition : alloysSupplier.get().all()) {
            for (GearPiece piece : GearPiece.values()) {
                registerOne(definition, piece);
            }
        }
    }

    private void registerOne(AlloyDefinition definition, GearPiece piece) {
        try {
            Optional<ItemStack> result = GearItemFactory.create(definition, piece);
            if (result.isEmpty()) {
                warn.accept("Alloy '" + definition.id() + "' has no " + piece.id()
                        + " on this server (base '" + definition.base().id()
                        + "' is unavailable); skipping that recipe.");
                return;
            }

            NamespacedKey key = keyFor(definition.id(), piece);
            ShapedRecipe recipe = new ShapedRecipe(key, result.get());
            recipe.shape(shapeFor(piece));
            recipe.setIngredient('I', new RecipeChoice.ExactChoice(AlloyItemFactory.create(definition)));
            if (piece.stickCost() > 0) {
                recipe.setIngredient('S', new RecipeChoice.MaterialChoice(Material.STICK));
            }

            Bukkit.addRecipe(recipe);
            registered.add(key);
        } catch (Throwable failure) {
            // One bad alloy must not cost the other four their gear.
            warn.accept("Failed to register the " + piece.id() + " recipe for alloy '"
                    + definition.id() + "': " + failure.getMessage());
        }
    }

    /** Vanilla shapes; {@code I} is the alloy ingot, {@code S} a plain stick. */
    private static String[] shapeFor(GearPiece piece) {
        return switch (piece) {
            case SWORD -> new String[] {"I", "I", "S"};
            case AXE -> new String[] {"II", "IS", " S"};
            case HELMET -> new String[] {"III", "I I"};
            case CHESTPLATE -> new String[] {"I I", "III", "III"};
            case LEGGINGS -> new String[] {"III", "I I", "I I"};
            case BOOTS -> new String[] {"I I", "I I"};
        };
    }

    /** Removes every recipe this instance registered. */
    public void unregister() {
        for (NamespacedKey key : registered) {
            Bukkit.removeRecipe(key);
        }
        registered.clear();
    }

    /**
     * Blanks the craft result unless every ingot in the grid is a genuine alloy ingot.
     *
     * <p>A backstop, not the primary matcher: {@code ExactChoice} already rejects
     * correctly on Java. This exists so the Bedrock false-positive path -- where the
     * client matches a type-only recipe against vanilla netherite ingots -- cannot
     * become an item duplication path. Blanking the result is the correct hook because
     * it cannot consume ingredients, unlike cancelling later in the craft.
     */
    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        if (event.getRecipe() == null || !(event.getRecipe() instanceof org.bukkit.Keyed keyed)) {
            return;
        }
        if (!registered.contains(keyed.getKey())) {
            return;
        }
        for (ItemStack ingredient : event.getInventory().getMatrix()) {
            if (ingredient == null || ingredient.getType() == Material.AIR
                    || ingredient.getType() == Material.STICK) {
                continue;
            }
            if (MaterialContract.readCustomMaterial(ingredient).isEmpty()) {
                event.getInventory().setResult(null);
                return;
            }
        }
    }

    /**
     * Unlocks these recipes so they appear in the recipe book.
     *
     * <p>Required, not cosmetic: since 1.21.2 the server sends only recipes the player
     * has unlocked, and {@code Bukkit.addRecipe} unlocks nothing. Without this the
     * recipe book is empty on <b>both</b> editions.
     */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        for (NamespacedKey key : registered) {
            player.discoverRecipe(key);
        }
    }
}
```

- [ ] **Step 2: Verify it compiles and the suite stays green**

Run: `source ~/.sdkman/bin/sdkman-init.sh && mvn --batch-mode --no-transfer-progress clean verify`
Expected: BUILD SUCCESS, 338 tests.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/org/xpfarm/electricfurnace/recipe/GearRecipes.java
git commit -m "feat(gear): register 30 ExactChoice gear recipes with a craft backstop"
```

---

### Task 11: Wire `GearRecipes` into the plugin lifecycle

**Files:**
- Modify: `src/main/java/org/xpfarm/electricfurnace/ElectricFurnacePlugin.java`

**Interfaces:**
- Consumes: `GearRecipes` from Task 10.
- Produces: a `private GearRecipes gearRecipes` field, registered in `onEnable`, unregistered in `onDisable`, and re-registered at the end of `reload()`.

The reload path is the one that bites. `/electricfurnace reload` swaps the `alloys` field, so recipes built from the *old* registry are now stale — and per the `ExactChoice` snapshot rule they would match nothing at all after an ingot's lore or colour changed. `GearRecipes.register()` already calls `unregister()` first, so re-registering is the whole fix.

- [ ] **Step 1: Add the field and enable-path registration**

Add beside the other fields:

```java
    private GearRecipes gearRecipes;
```

In `onEnable`, inside a `step(...)` guard so a failure cannot abort startup, after the existing `MachineRecipe` registration:

```java
        step("gear recipes", () -> {
            gearRecipes = new GearRecipes(this::alloys, this::warn);
            gearRecipes.register();
            getServer().getPluginManager().registerEvents(gearRecipes, this);
        });
```

Match the exact `step(...)` call style already used in that method.

- [ ] **Step 2: Add the disable-path and reload-path handling**

In `onDisable`, before the existing shutdown work:

```java
        if (gearRecipes != null) {
            gearRecipes.unregister();
        }
```

At the end of `reload()`, after the `alloys` field has been swapped:

```java
        if (gearRecipes != null) {
            // Rebuild against the new registry. ExactChoice ingredients are snapshotted
            // at registration, so recipes built from the previous config would silently
            // stop matching once an ingot's name, lore, or colour changed.
            gearRecipes.register();
        }
```

- [ ] **Step 3: Verify the build**

Run: `source ~/.sdkman/bin/sdkman-init.sh && mvn --batch-mode --no-transfer-progress clean verify`
Expected: BUILD SUCCESS, 338 tests.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/org/xpfarm/electricfurnace/ElectricFurnacePlugin.java
git commit -m "feat(gear): register and reload gear recipes in the plugin lifecycle"
```

---

### Task 12: `piece` argument on `/electricfurnace alloy`

**Files:**
- Modify: `src/main/java/org/xpfarm/electricfurnace/command/ElectricFurnaceCommand.java`
- Test: `src/test/java/org/xpfarm/electricfurnace/command/CommandArgsTest.java`

**Interfaces:**
- Consumes: `GearPiece.byId`.
- Produces: `ParseResult` gains a trailing `GearPiece piece` component (`null` for an ingot). `/electricfurnace alloy <id> [piece] [amount]`.

Permissions do not change: `electricfurnace.give` still gates the whole subcommand. No new node.

The parse is genuinely ambiguous and must be resolved deliberately: in `alloy steel 5`, the third token is an amount; in `alloy steel sword`, it is a piece. Resolve by trying `GearPiece.byId` first and falling back to the existing amount parse.

- [ ] **Step 1: Write the failing test**

Append to `CommandArgsTest`, matching that file's existing style:

```java
    @Test
    void parseAlloy_withoutPiece_stillYieldsAnIngot() {
        ElectricFurnaceCommand.ParseResult result =
                ElectricFurnaceCommand.parse(new String[] {"alloy", "steel"});

        assertEquals(ElectricFurnaceCommand.Sub.ALLOY, result.sub());
        assertEquals("steel", result.alloyId());
        assertNull(result.piece());
    }

    @Test
    void parseAlloy_withPiece_resolvesThePiece() {
        ElectricFurnaceCommand.ParseResult result =
                ElectricFurnaceCommand.parse(new String[] {"alloy", "steel", "chestplate"});

        assertEquals("steel", result.alloyId());
        assertEquals(GearPiece.CHESTPLATE, result.piece());
    }

    @Test
    void parseAlloy_withPieceAndAmount_resolvesBoth() {
        ElectricFurnaceCommand.ParseResult result =
                ElectricFurnaceCommand.parse(new String[] {"alloy", "steel", "sword", "3"});

        assertEquals("steel", result.alloyId());
        assertEquals(GearPiece.SWORD, result.piece());
        assertEquals(3, result.amount());
    }

    @Test
    void parseAlloy_amountInThePieceSlot_isStillAnAmount() {
        // The ambiguous case: "alloy steel 5" must mean five ingots, not a piece.
        ElectricFurnaceCommand.ParseResult result =
                ElectricFurnaceCommand.parse(new String[] {"alloy", "steel", "5"});

        assertEquals("steel", result.alloyId());
        assertNull(result.piece());
        assertEquals(5, result.amount());
    }

    @Test
    void parseAlloy_unknownPiece_isAnError() {
        ElectricFurnaceCommand.ParseResult result =
                ElectricFurnaceCommand.parse(new String[] {"alloy", "steel", "trousers"});

        assertNotNull(result.error());
    }
```

Add `import org.xpfarm.electricfurnace.gear.GearPiece;` and any missing JUnit static imports (`assertNull`, `assertNotNull`).

- [ ] **Step 2: Run test to verify it fails**

Run: `source ~/.sdkman/bin/sdkman-init.sh && mvn --batch-mode --no-transfer-progress test -Dtest=CommandArgsTest`
Expected: FAIL — `cannot find symbol: method piece()`.

- [ ] **Step 3: Write minimal implementation**

Add the component to `ParseResult`:

```java
    public record ParseResult(Sub sub, String targetPlayer, String alloyId, int amount, String error,
            GearPiece piece) {
```

Update the error factory and every other `new ParseResult(...)` call site to pass `null` as the trailing argument. Then replace `parseAlloy`:

```java
    private static ParseResult parseAlloy(String[] args) {
        if (args.length < 2) {
            return error("Usage: /electricfurnace alloy <id> [piece] [amount]");
        }
        String alloyId = args[1].toLowerCase(Locale.ROOT);

        // args[2] is ambiguous: "alloy steel 5" is an amount, "alloy steel sword" is a
        // piece. Try the piece first -- a piece id is never numeric, so this cannot
        // swallow an amount.
        GearPiece piece = null;
        int amountIndex = 2;
        if (args.length > 2) {
            Optional<GearPiece> parsed = GearPiece.byId(args[2]);
            if (parsed.isPresent()) {
                piece = parsed.get();
                amountIndex = 3;
            } else if (!isNumeric(args[2])) {
                return error("Unknown gear piece '" + args[2] + "'.");
            }
        }

        AmountResult amount = parseAmount(args.length > amountIndex ? args[amountIndex] : null);
        if (amount.error() != null) {
            return error(amount.error());
        }
        return new ParseResult(Sub.ALLOY, null, alloyId, amount.value(), null, piece);
    }

    private static boolean isNumeric(String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        for (int i = 0; i < token.length(); i++) {
            if (!Character.isDigit(token.charAt(i))) {
                return false;
            }
        }
        return true;
    }
```

`parseAmount(String)` returning `AmountResult(int value, String error)` is the existing private helper at `ElectricFurnaceCommand.java:220` — verified; reuse it exactly as shown, do not add a parallel one. Update `usage()` to `"Usage: /electricfurnace <give [player] [amount] | alloy <id> [piece] [amount] | reload | info>"`.

Finally, in the command's execution path, when `piece` is non-null mint via `GearItemFactory.create(definition, piece)` instead of `AlloyItemFactory.create(definition)`, and add gear piece ids to the tab completer for the `alloy` subcommand's third argument.

- [ ] **Step 4: Run the full suite**

Run: `source ~/.sdkman/bin/sdkman-init.sh && mvn --batch-mode --no-transfer-progress clean verify`
Expected: BUILD SUCCESS, 343 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/xpfarm/electricfurnace/command/ElectricFurnaceCommand.java src/test/java/org/xpfarm/electricfurnace/command/CommandArgsTest.java
git commit -m "feat(gear): accept an optional piece argument on /electricfurnace alloy"
```

---

### Task 13: Config documentation and `/electricfurnace info`

**Files:**
- Modify: `src/main/resources/config.yml`
- Modify: `src/main/java/org/xpfarm/electricfurnace/command/ElectricFurnaceCommand.java`
- Modify: `pom.xml`

- [ ] **Step 1: Document `base` in `config.yml`**

Extend the comment block above `alloys:` with:

```yaml
# `base` selects the vanilla material family this alloy's gear is built on: one of
# copper, iron, gold, diamond, netherite. It is the only thing that makes two alloys'
# gear look different -- there is no resource pack, so texture comes from the base
# item. Each family is one of the five vanilla tiers that has both weapon and armor
# forms. Omit it to accept the thematic default. An unrecognized value warns and falls
# back to that default.
#
# Two bases carry vanilla behaviour worth knowing about: gold makes piglins neutral
# toward the wearer (driven by an item tag, so it cannot be stripped per item), and
# netherite grants fire immunity and knockback resistance -- both of which this plugin
# removes from minted gear.
```

Then add the `base` key to each of the five shipped alloys: `steel: iron`, `rose_gold: gold`, `ferrocopper: copper`, `electrum_steel: diamond`, `fused_alloy: netherite`. For example, under `steel:`, directly below `color:`:

```yaml
    base: iron
```

- [ ] **Step 2: Add the base material to `/electricfurnace info`**

In the `info` subcommand's alloy listing, append each alloy's base to its line — e.g. `Steel (iron base)`. Read the existing rendering code and match its component-building style; do not introduce a new formatting approach.

- [ ] **Step 3: Bump the version**

In `pom.xml`, change `<version>0.2.1</version>` to `<version>0.3.0</version>`.

- [ ] **Step 4: Verify the build**

Run: `source ~/.sdkman/bin/sdkman-init.sh && mvn --batch-mode --no-transfer-progress clean verify`
Expected: BUILD SUCCESS, 343 tests. Confirm `target/electric-furnace-0.3.0.jar` exists.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/config.yml src/main/java/org/xpfarm/electricfurnace/command/ElectricFurnaceCommand.java pom.xml
git commit -m "feat(gear): document alloys.<id>.base, report it in info, bump to 0.3.0"
```

---

## Runtime verification (gate 7a)

Unit tests cannot reach any of this — every item here needs a live server, and three items need a live *Bedrock* client. These map directly onto acceptance checks 16–28 in `docs/PLUGIN_CHECKLIST.md`. Run them under `minecraft-plugin-dev` on a disposable Legendary stack.

**Java:**

1. `/electricfurnace alloy steel sword` yields an iron-textured sword named "Steel Sword"; its tooltip shows 6.5 attack damage and a 700-durability bar.
2. Crafting a Steel Sword from 2 Steel ingots + 1 stick in a crafting table works.
3. The same shape with **vanilla** netherite ingots yields no result.
4. Steel armor pieces show 2/7/5/2 armor points, summing to 16.
5. A Fused Alloy sword dropped into lava burns up.
6. No alloy armor grants knockback resistance, Fused Alloy included.
7. Recycling 4 Steel armor pieces yields `4 × yield-remelt-alloy` Steel ingots.
8. Recycling a Steel sword together with a Rose Gold sword reports `"mixed alloys"`.
9. `/electricfurnace reload` twice, then confirm exactly 30 gear recipes are registered — not 60.
10. All 30 recipes appear in the Java recipe book after rejoining.

**Bedrock, via Geyser — these settle the three UNRESOLVED items in the spec:**

11. Craft each of the six Steel pieces by manual placement. Time the flicker; confirm the output is takeable.
12. Lay out **vanilla** netherite ingots in the Steel Sword shape. Record what appears, and what happens on click. Expected: no item is received.
13. Place a Steel Sword into an enchanting table. Record whether Bedrock permits it and what options are offered.

Record every result in the checklist, including any that fail.

---

## Self-Review

**Spec coverage.** Every spec section maps to a task: base materials → Tasks 5, 6, 9; per-piece derivation → Tasks 1–4; `ExactChoice` recipes and the craft backstop → Task 10; recipe discovery → Task 10; recycling → Task 8; the `xpfarm:gear_piece` key → Task 7; config → Tasks 6 and 13; the command → Task 12; reload correctness → Task 11. The spec's four implementation landmines (`Attribute` not an enum, modifiers replacing defaults, `unsetData` vs `resetData`, copper's version guard) are each pinned to the task that must handle them. The three UNRESOLVED Bedrock questions are carried into runtime verification rather than being silently dropped.

**Type consistency.** `GearPiece.byId` is used identically in Tasks 1 and 12. `GearBase.materialName(GearPiece)` returns `String` in Task 5 and is consumed as `String` by `Material.getMaterial` in Task 9. `GearItemFactory.create` returns `Optional<ItemStack>` in Task 9 and is unwrapped as an `Optional` in Task 10. `AlloyDefinition.base()` is added in Task 6 and read in Tasks 9 and 10. `GearStatsDeriver.derive(AlloyStats, GearPiece)` has one signature throughout.

**Verified against the real code.** Four assumptions were checked rather than trusted, and two were wrong and are now fixed: `AlloyRegistry` exposes `all()` returning `Collection<AlloyDefinition>` and has **no** by-id lookup (Task 6 uses a local `baseOf` helper); the command's amount helper is `parseAmount(String)` returning `AmountResult(int value, String error)` at `ElectricFurnaceCommand.java:220`, **not** an index-taking overload (Task 12 corrected). Confirmed correct as written: `RecycleResult.Remelt(String alloyId, int amount)` and `RecycleResult.Rejected(String reason)` (Task 8's assertions), and `MachineRecipe` as the registration/unregistration precedent (Task 10).

**Remaining soft spots, flagged rather than hidden.** Three places still depend on local shape not read line-by-line: `AlloyRegistry.load`'s per-alloy parsing block (Task 6), `RecycleResolverTest`'s input-builder helper names (Task 8), and the `info` renderer (Task 13). Each of those steps says to read the surrounding code and match it rather than assuming the shape shown here. Test counts after each task assume no test is deleted; Task 8 changes one existing assertion and says so.
