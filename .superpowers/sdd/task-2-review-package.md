# Review package: d86db74..15fada2

## Commits
15fada2 Task 2: alloy model and the pure recycle resolver

## Stat
 .../electricfurnace/alloy/AlloyDefinition.java     |  55 ++++
 .../electricfurnace/alloy/AlloyRegistry.java       | 215 +++++++++++++
 .../xpfarm/electricfurnace/alloy/AlloyStats.java   |  35 +++
 .../xpfarm/electricfurnace/alloy/MetalType.java    |  25 ++
 .../electricfurnace/recycle/RecycleInput.java      |  52 ++++
 .../electricfurnace/recycle/RecycleResolver.java   | 154 ++++++++++
 .../electricfurnace/recycle/RecycleResult.java     |  72 +++++
 src/main/resources/config.yml                      |  72 ++++-
 .../electricfurnace/alloy/AlloyRegistryTest.java   | 146 +++++++++
 .../recycle/RecycleResolverTest.java               | 339 +++++++++++++++++++++
 10 files changed, 1155 insertions(+), 10 deletions(-)

## Diff
```diff
diff --git a/src/main/java/org/xpfarm/electricfurnace/alloy/AlloyDefinition.java b/src/main/java/org/xpfarm/electricfurnace/alloy/AlloyDefinition.java
new file mode 100644
index 0000000..1d9a14b
--- /dev/null
+++ b/src/main/java/org/xpfarm/electricfurnace/alloy/AlloyDefinition.java
@@ -0,0 +1,55 @@
+/*
+ * ElectricFurnace - a redstone-powered smelter that recycles metal gear into ingots.
+ * Copyright (C) 2026 Carmelo Santana
+ *
+ * This program is free software: you can redistribute it and/or modify it under the
+ * terms of the GNU Affero General Public License as published by the Free Software
+ * Foundation, either version 3 of the License, or (at your option) any later version.
+ * See the LICENSE file at the project root for the full license text.
+ */
+package org.xpfarm.electricfurnace.alloy;
+
+import java.util.List;
+import java.util.Set;
+
+/**
+ * One named alloy recipe: an id, its display metadata, the distinct set of inputs
+ * required to produce it, and its stat block.
+ *
+ * <p>Deliberately free of any Bukkit or Adventure type -- {@code color} is a plain
+ * hex string (e.g. {@code "#71797E"}) and {@code lore} is plain text lines. Task 3's
+ * {@code AlloyItemFactory} is responsible for turning this into an actual
+ * {@code ItemStack} with Adventure {@code Component} name/lore.
+ *
+ * <p>{@code inputIds} is the distinct set of metal/modifier ids required to match
+ * this recipe (e.g. {@code {"iron", "coal"}} for Steel), order- and
+ * quantity-independent -- as long as every listed input is present at least once,
+ * the recipe matches regardless of how many of each. An <b>empty</b> {@code inputIds}
+ * marks the generic fallback recipe (shipped as {@code fused_alloy}): it matches
+ * nothing explicitly and is used only when no named recipe matches.
+ *
+ * @param id          stable identifier, e.g. {@code "steel"}
+ * @param displayName human-readable name, e.g. {@code "Steel"}
+ * @param lore        flavor/description lines
+ * @param color       hex color string used to distinguish this alloy visually
+ * @param inputIds    distinct required input ids; empty marks the generic fallback
+ * @param stats       the stat block, balance-ceiling-clamped by {@link AlloyRegistry}
+ */
+public record AlloyDefinition(
+        String id,
+        String displayName,
+        List<String> lore,
+        String color,
+        Set<String> inputIds,
+        AlloyStats stats
+) {
+    public AlloyDefinition {
+        lore = List.copyOf(lore);
+        inputIds = Set.copyOf(inputIds);
+    }
+
+    /** Whether this definition is the generic fallback (no fixed inputs). */
+    public boolean isFallback() {
+        return inputIds.isEmpty();
+    }
+}
diff --git a/src/main/java/org/xpfarm/electricfurnace/alloy/AlloyRegistry.java b/src/main/java/org/xpfarm/electricfurnace/alloy/AlloyRegistry.java
new file mode 100644
index 0000000..4022e62
--- /dev/null
+++ b/src/main/java/org/xpfarm/electricfurnace/alloy/AlloyRegistry.java
@@ -0,0 +1,215 @@
+/*
+ * ElectricFurnace - a redstone-powered smelter that recycles metal gear into ingots.
+ * Copyright (C) 2026 Carmelo Santana
+ *
+ * This program is free software: you can redistribute it and/or modify it under the
+ * terms of the GNU Affero General Public License as published by the Free Software
+ * Foundation, either version 3 of the License, or (at your option) any later version.
+ * See the LICENSE file at the project root for the full license text.
+ */
+package org.xpfarm.electricfurnace.alloy;
+
+import org.bukkit.configuration.ConfigurationSection;
+
+import java.util.ArrayList;
+import java.util.Collection;
+import java.util.LinkedHashMap;
+import java.util.List;
+import java.util.Map;
+import java.util.Objects;
+import java.util.Optional;
+import java.util.Set;
+import java.util.function.Consumer;
+
+/**
+ * The loaded set of named alloy recipes, keyed by id, with the balance-ceiling clamp
+ * applied to every stat block on load.
+ *
+ * <p>The balance ceiling is a binding, code-enforced constraint -- not merely
+ * documented -- and is applied unconditionally by {@link #fromDefinitions}: there is
+ * deliberately no configuration switch to disable it. Any stat exceeding the
+ * netherite reference is clamped down to the diamond reference and a warning is
+ * logged naming the alloy, the stat, the configured value, and the clamp.
+ *
+ * <p>{@link #fromDefinitions} is the pure, Bukkit-free core: it takes already-parsed
+ * {@link AlloyDefinition}s and is what {@code AlloyRegistryTest} exercises directly,
+ * with no running server required. {@link #load} is the thin glue that parses the
+ * {@code alloys:} section of {@code config.yml} and delegates to it.
+ */
+public final class AlloyRegistry {
+
+    // ---- Balance ceiling reference constants (see design doc "Balance ceiling") ----
+
+    public static final double IRON_ATTACK_DAMAGE = 6.0;
+    public static final int IRON_ARMOR = 15;
+    public static final double IRON_ARMOR_TOUGHNESS = 0.0;
+    public static final int IRON_MAX_DURABILITY = 250;
+
+    public static final double DIAMOND_ATTACK_DAMAGE = 7.0;
+    public static final int DIAMOND_ARMOR = 20;
+    public static final double DIAMOND_ARMOR_TOUGHNESS = 2.0;
+    public static final int DIAMOND_MAX_DURABILITY = 1561;
+
+    public static final double NETHERITE_ATTACK_DAMAGE = 8.0;
+    public static final int NETHERITE_ARMOR = 20;
+    public static final double NETHERITE_ARMOR_TOUGHNESS = 3.0;
+    public static final int NETHERITE_MAX_DURABILITY = 2031;
+
+    /** id of the synthesized fallback used if no configured recipe declares empty inputs. */
+    private static final String SYNTHESIZED_FALLBACK_ID = "fused_alloy";
+
+    private final Map<String, AlloyDefinition> byId;
+    private final String fallbackId;
+
+    private AlloyRegistry(Map<String, AlloyDefinition> byId, String fallbackId) {
+        this.byId = byId;
+        this.fallbackId = fallbackId;
+    }
+
+    /**
+     * Builds a registry from already-parsed alloy definitions, applying the
+     * balance-ceiling clamp to every stat block. Pure -- no Bukkit types involved.
+     *
+     * @param rawDefinitions definitions as configured, stats not yet clamped
+     * @param warn           sink for balance-ceiling clamp warnings; must not be {@code null}
+     */
+    public static AlloyRegistry fromDefinitions(List<AlloyDefinition> rawDefinitions, Consumer<String> warn) {
+        Objects.requireNonNull(rawDefinitions, "rawDefinitions");
+        Objects.requireNonNull(warn, "warn");
+
+        Map<String, AlloyDefinition> clamped = new LinkedHashMap<>();
+        String fallbackId = null;
+        for (AlloyDefinition def : rawDefinitions) {
+            AlloyDefinition safe = new AlloyDefinition(
+                    def.id(), def.displayName(), def.lore(), def.color(), def.inputIds(),
+                    clampStats(def.id(), def.stats(), warn));
+            clamped.put(safe.id(), safe);
+            if (safe.isFallback() && fallbackId == null) {
+                fallbackId = safe.id();
+            }
+        }
+
+        if (fallbackId == null) {
+            warn.accept("ElectricFurnace alloys: no recipe with empty inputs was configured (the generic "
+                    + "fallback); synthesizing a default '" + SYNTHESIZED_FALLBACK_ID + "'.");
+            AlloyDefinition synthesized = new AlloyDefinition(
+                    SYNTHESIZED_FALLBACK_ID, "Fused Alloy", List.of(), "#4B4B4B", Set.of(),
+                    new AlloyStats(IRON_ATTACK_DAMAGE, -2.6, IRON_ARMOR, IRON_ARMOR_TOUGHNESS,
+                            IRON_MAX_DURABILITY, 10));
+            clamped.put(synthesized.id(), synthesized);
+            fallbackId = synthesized.id();
+        }
+
+        return new AlloyRegistry(Map.copyOf(clamped), fallbackId);
+    }
+
+    /**
+     * Parses the {@code alloys:} section of {@code config.yml} and delegates to
+     * {@link #fromDefinitions}. Each child section is read as one alloy: {@code
+     * display-name} (string), {@code lore} (string list, optional), {@code color}
+     * (string, optional), {@code inputs} (string list, may be empty to mark the
+     * fallback), and {@code stats.*} (the six {@link AlloyStats} fields).
+     *
+     * <p>A missing or malformed alloy entry is skipped with a warning rather than
+     * thrown -- consistent with the rest of this plugin's "never fail startup"
+     * config contract.
+     *
+     * @param alloysSection the {@code alloys} configuration section; may be {@code null}
+     * @param warn          sink for warnings, both parsing and balance-ceiling clamp
+     */
+    public static AlloyRegistry load(ConfigurationSection alloysSection, Consumer<String> warn) {
+        Objects.requireNonNull(warn, "warn");
+        List<AlloyDefinition> definitions = new ArrayList<>();
+        if (alloysSection != null) {
+            for (String id : alloysSection.getKeys(false)) {
+                ConfigurationSection section = alloysSection.getConfigurationSection(id);
+                if (section == null) {
+                    warn.accept("ElectricFurnace alloys: entry '" + id + "' is not a section; skipped.");
+                    continue;
+                }
+                definitions.add(parseDefinition(id, section));
+            }
+        }
+        return fromDefinitions(definitions, warn);
+    }
+
+    private static AlloyDefinition parseDefinition(String id, ConfigurationSection section) {
+        String displayName = section.getString("display-name", id);
+        List<String> lore = section.getStringList("lore");
+        String color = section.getString("color", "#FFFFFF");
+        Set<String> inputIds = Set.copyOf(section.getStringList("inputs"));
+
+        ConfigurationSection statsSection = section.getConfigurationSection("stats");
+        AlloyStats stats = new AlloyStats(
+                statsSection == null ? IRON_ATTACK_DAMAGE : statsSection.getDouble("attack-damage", IRON_ATTACK_DAMAGE),
+                statsSection == null ? -2.6 : statsSection.getDouble("attack-speed", -2.6),
+                statsSection == null ? IRON_ARMOR : statsSection.getInt("armor", IRON_ARMOR),
+                statsSection == null ? IRON_ARMOR_TOUGHNESS : statsSection.getDouble("armor-toughness", IRON_ARMOR_TOUGHNESS),
+                statsSection == null ? IRON_MAX_DURABILITY : statsSection.getInt("max-durability", IRON_MAX_DURABILITY),
+                statsSection == null ? 10 : statsSection.getInt("enchantability", 10)
+        );
+
+        return new AlloyDefinition(id, displayName, lore, color, inputIds, stats);
+    }
+
+    /** Looks up a definition by id. */
+    public Optional<AlloyDefinition> get(String id) {
+        return Optional.ofNullable(byId.get(id));
+    }
+
+    /** All loaded definitions, including the fallback. */
+    public Collection<AlloyDefinition> all() {
+        return byId.values();
+    }
+
+    /**
+     * Finds a named (non-fallback) recipe whose required input ids are exactly the
+     * given present ids -- order- and quantity-independent, since both sides are
+     * plain sets.
+     */
+    public Optional<AlloyDefinition> findNamedMatch(Set<String> presentIds) {
+        return byId.values().stream()
+                .filter(def -> !def.isFallback())
+                .filter(def -> def.inputIds().equals(presentIds))
+                .findFirst();
+    }
+
+    /** The generic fallback recipe (empty inputs), used when no named recipe matches. */
+    public AlloyDefinition fallback() {
+        return byId.get(fallbackId);
+    }
+
+    /**
+     * Clamps a stat block against the balance ceiling: any of attack damage, armor,
+     * armor toughness, or max durability that exceeds the netherite reference is
+     * replaced with the diamond reference, and a warning naming the alloy, the stat,
+     * the configured value, and the clamp is sent to {@code warn}. Attack speed and
+     * enchantability have no defined ceiling reference and are passed through
+     * unchanged.
+     */
+    static AlloyStats clampStats(String alloyId, AlloyStats stats, Consumer<String> warn) {
+        double attackDamage = clampToCeiling(alloyId, "attack-damage", stats.attackDamage(),
+                NETHERITE_ATTACK_DAMAGE, DIAMOND_ATTACK_DAMAGE, warn);
+        int armor = (int) clampToCeiling(alloyId, "armor", stats.armor(),
+                NETHERITE_ARMOR, DIAMOND_ARMOR, warn);
+        double armorToughness = clampToCeiling(alloyId, "armor-toughness", stats.armorToughness(),
+                NETHERITE_ARMOR_TOUGHNESS, DIAMOND_ARMOR_TOUGHNESS, warn);
+        int maxDurability = (int) clampToCeiling(alloyId, "max-durability", stats.maxDurability(),
+                NETHERITE_MAX_DURABILITY, DIAMOND_MAX_DURABILITY, warn);
+
+        return new AlloyStats(attackDamage, stats.attackSpeed(), armor, armorToughness,
+                maxDurability, stats.enchantability());
+    }
+
+    private static double clampToCeiling(String alloyId, String statName, double value,
+                                          double netheriteReference, double diamondReference,
+                                          Consumer<String> warn) {
+        if (value > netheriteReference) {
+            warn.accept("ElectricFurnace alloys: alloy '" + alloyId + "' stat '" + statName
+                    + "' value '" + value + "' exceeds the netherite reference '" + netheriteReference
+                    + "'; clamped to the diamond reference '" + diamondReference + "'.");
+            return diamondReference;
+        }
+        return value;
+    }
+}
diff --git a/src/main/java/org/xpfarm/electricfurnace/alloy/AlloyStats.java b/src/main/java/org/xpfarm/electricfurnace/alloy/AlloyStats.java
new file mode 100644
index 0000000..95675d8
--- /dev/null
+++ b/src/main/java/org/xpfarm/electricfurnace/alloy/AlloyStats.java
@@ -0,0 +1,35 @@
+/*
+ * ElectricFurnace - a redstone-powered smelter that recycles metal gear into ingots.
+ * Copyright (C) 2026 Carmelo Santana
+ *
+ * This program is free software: you can redistribute it and/or modify it under the
+ * terms of the GNU Affero General Public License as published by the Free Software
+ * Foundation, either version 3 of the License, or (at your option) any later version.
+ * See the LICENSE file at the project root for the full license text.
+ */
+package org.xpfarm.electricfurnace.alloy;
+
+/**
+ * The complete stat block carried by an alloy definition.
+ *
+ * <p>Per the balance ceiling, every stat here is expected to sit between the iron
+ * and diamond reference points and never exceed the netherite reference point.
+ * {@link AlloyRegistry} enforces this on load by clamping and warning -- this record
+ * itself performs no validation; it is a plain data holder.
+ *
+ * @param attackDamage    base attack damage if this alloy is ever used in a weapon
+ * @param attackSpeed     base attack speed modifier if this alloy is ever used in a weapon
+ * @param armor           armor points if this alloy is ever used in armor
+ * @param armorToughness  armor toughness if this alloy is ever used in armor
+ * @param maxDurability   maximum durability of items made from this alloy
+ * @param enchantability  enchantability of items made from this alloy
+ */
+public record AlloyStats(
+        double attackDamage,
+        double attackSpeed,
+        int armor,
+        double armorToughness,
+        int maxDurability,
+        int enchantability
+) {
+}
diff --git a/src/main/java/org/xpfarm/electricfurnace/alloy/MetalType.java b/src/main/java/org/xpfarm/electricfurnace/alloy/MetalType.java
new file mode 100644
index 0000000..2008bef
--- /dev/null
+++ b/src/main/java/org/xpfarm/electricfurnace/alloy/MetalType.java
@@ -0,0 +1,25 @@
+/*
+ * ElectricFurnace - a redstone-powered smelter that recycles metal gear into ingots.
+ * Copyright (C) 2026 Carmelo Santana
+ *
+ * This program is free software: you can redistribute it and/or modify it under the
+ * terms of the GNU Affero General Public License as published by the Free Software
+ * Foundation, either version 3 of the License, or (at your option) any later version.
+ * See the LICENSE file at the project root for the full license text.
+ */
+package org.xpfarm.electricfurnace.alloy;
+
+/**
+ * The recyclable metals recognized by the resolver.
+ *
+ * <p><b>Coal is deliberately not a member of this enum.</b> Coal (and charcoal) is a
+ * modifier, not a metal -- it cannot be recycled on its own, does not count toward
+ * the "all same metal" check, and only participates as a named-alloy ingredient
+ * (e.g. Steel). See {@link org.xpfarm.electricfurnace.recycle.RecycleInput#isModifier()}.
+ */
+public enum MetalType {
+    IRON,
+    GOLD,
+    COPPER,
+    NETHERITE
+}
diff --git a/src/main/java/org/xpfarm/electricfurnace/recycle/RecycleInput.java b/src/main/java/org/xpfarm/electricfurnace/recycle/RecycleInput.java
new file mode 100644
index 0000000..ad03b75
--- /dev/null
+++ b/src/main/java/org/xpfarm/electricfurnace/recycle/RecycleInput.java
@@ -0,0 +1,52 @@
+/*
+ * ElectricFurnace - a redstone-powered smelter that recycles metal gear into ingots.
+ * Copyright (C) 2026 Carmelo Santana
+ *
+ * This program is free software: you can redistribute it and/or modify it under the
+ * terms of the GNU Affero General Public License as published by the Free Software
+ * Foundation, either version 3 of the License, or (at your option) any later version.
+ * See the LICENSE file at the project root for the full license text.
+ */
+package org.xpfarm.electricfurnace.recycle;
+
+import org.xpfarm.electricfurnace.alloy.MetalType;
+
+/**
+ * One item occupying one recycler input slot, described entirely in plain terms so
+ * that {@link RecycleResolver} never needs to know what an {@code ItemStack} is.
+ *
+ * <p>Task 3's {@code MetalClassifier} is the bridge from Bukkit {@code ItemStack}s to
+ * this record: it decides {@code metal}, {@code isModifier}, {@code isAlloy}, and
+ * {@code ingotValue} from the item's material and PDC, discarding anything the
+ * resolver does not need -- notably durability/damage, since
+ * {@code recycling.accept-damaged} means damaged and undamaged gear must resolve
+ * identically, and that is only guaranteed if this record cannot represent damage at
+ * all.
+ *
+ * <p>Exactly one of {@code metal != null}, {@code isModifier}, or {@code isAlloy}
+ * should hold for any well-formed input; an input where {@code metal == null} and
+ * {@code isModifier == false} (whether or not {@code isAlloy}) is treated by the
+ * resolver as a non-metal input.
+ *
+ * @param materialId a stable identifier for the underlying material/item, used as
+ *                    the modifier's id in named-alloy matching (e.g. {@code "coal"});
+ *                    ignored for metal inputs, whose id is derived from {@code metal}
+ * @param metal       the metal this input counts as, or {@code null} if this input is
+ *                    not a metal (a modifier, an alloy, or unrecognized)
+ * @param isModifier  whether this input is a modifier (e.g. coal/charcoal) rather
+ *                    than a metal
+ * @param isAlloy     whether this input is itself an alloy item (remelt candidate)
+ * @param alloyId     the alloy id if {@code isAlloy}, otherwise {@code null}
+ * @param ingotValue  the ingot-equivalent value of this input; carried through for
+ *                    downstream/display use only -- {@link RecycleResolver} does not
+ *                    consult it, since none of the eight resolution rules depend on it
+ */
+public record RecycleInput(
+        String materialId,
+        MetalType metal,
+        boolean isModifier,
+        boolean isAlloy,
+        String alloyId,
+        int ingotValue
+) {
+}
diff --git a/src/main/java/org/xpfarm/electricfurnace/recycle/RecycleResolver.java b/src/main/java/org/xpfarm/electricfurnace/recycle/RecycleResolver.java
new file mode 100644
index 0000000..24f9c06
--- /dev/null
+++ b/src/main/java/org/xpfarm/electricfurnace/recycle/RecycleResolver.java
@@ -0,0 +1,154 @@
+/*
+ * ElectricFurnace - a redstone-powered smelter that recycles metal gear into ingots.
+ * Copyright (C) 2026 Carmelo Santana
+ *
+ * This program is free software: you can redistribute it and/or modify it under the
+ * terms of the GNU Affero General Public License as published by the Free Software
+ * Foundation, either version 3 of the License, or (at your option) any later version.
+ * See the LICENSE file at the project root for the full license text.
+ */
+package org.xpfarm.electricfurnace.recycle;
+
+import org.xpfarm.electricfurnace.alloy.AlloyDefinition;
+import org.xpfarm.electricfurnace.alloy.AlloyRegistry;
+import org.xpfarm.electricfurnace.alloy.MetalType;
+import org.xpfarm.electricfurnace.config.RecyclingSettings;
+
+import java.util.List;
+import java.util.Locale;
+import java.util.Objects;
+import java.util.Optional;
+import java.util.Set;
+import java.util.stream.Collectors;
+
+/**
+ * The heart of the plugin: given a list of recycler inputs, decides what comes out.
+ *
+ * <p><b>Entirely pure.</b> No {@code org.bukkit} type appears anywhere in this class
+ * or its tests -- every input is a plain {@link RecycleInput}, every yield number
+ * comes from the caller-supplied {@link RecyclingSettings}, never hardcoded here.
+ *
+ * <p>Resolution proceeds through eight rules, in this exact precedence order. Each
+ * rule is checked only after every earlier rule has failed to apply:
+ *
+ * <ol>
+ *   <li>Empty input -&gt; {@code REJECTED("empty")}.</li>
+ *   <li>A single alloy item -&gt; {@code REMELT}. The <b>only</b> case that accepts
+ *       fewer than {@code recycling.slots} items.</li>
+ *   <li>Fewer than {@code recycling.slots} items (any composition) -&gt;
+ *       {@code REJECTED("needs N items")}.</li>
+ *   <li>Any input that is neither a metal nor a modifier -&gt;
+ *       {@code REJECTED("non-metal input")}. This also catches an alloy item that is
+ *       not alone (rule 2 already handled the sole-alloy case).</li>
+ *   <li>Only modifiers, no metals at all (e.g. 5 coal) -&gt;
+ *       {@code REJECTED("no metal")}. Coal alone is never recyclable.</li>
+ *   <li>All metals identical <b>and no modifier present</b> -&gt; {@code SAME_METAL}.</li>
+ *   <li>The input's distinct set of metal/modifier ids matches a named alloy recipe
+ *       -&gt; {@code NAMED_ALLOY}.</li>
+ *   <li>Otherwise -&gt; {@code GENERIC_ALLOY} (the registry's fallback recipe).</li>
+ * </ol>
+ *
+ * <p><b>Rule 6 vs. rule 7, the subtlest interaction:</b> "4 iron + 1 coal" is
+ * <em>not</em> all-same-metal, even though every metal present is iron -- the mere
+ * presence of the coal modifier disqualifies rule 6 and routes the combination to
+ * the named-recipe check in rule 7, where it matches Steel.
+ *
+ * <p>Rule 3 counts <em>every</em> input, modifiers included -- it is purely a slot
+ * headcount, independent of composition. Composition-specific rejections are rules
+ * 4 and 5, checked afterward.
+ */
+public final class RecycleResolver {
+
+    private RecycleResolver() {
+    }
+
+    /**
+     * Resolves one recycler operation.
+     *
+     * @param inputs   the items currently occupying the recycler input slots; a
+     *                 {@code null} or empty list is rule 1 ({@code REJECTED("empty")})
+     * @param settings validated {@code recycling} config; yields are read from here,
+     *                 never hardcoded
+     * @param alloys   the loaded named alloy recipes, including the generic fallback
+     * @return the resolved outcome
+     */
+    public static RecycleResult resolve(List<RecycleInput> inputs, RecyclingSettings settings, AlloyRegistry alloys) {
+        Objects.requireNonNull(settings, "settings");
+        Objects.requireNonNull(alloys, "alloys");
+
+        // Rule 1: empty input.
+        if (inputs == null || inputs.isEmpty()) {
+            return new RecycleResult.Rejected("empty");
+        }
+
+        // Rule 2: a single alloy item remelts -- the only case accepting fewer than
+        // `slots` items, so it must be checked before the slot-count rule below.
+        if (inputs.size() == 1) {
+            RecycleInput only = inputs.get(0);
+            if (only.isAlloy()) {
+                return new RecycleResult.Remelt(only.alloyId(), settings.yieldRemeltAlloy());
+            }
+        }
+
+        // Rule 3: fewer than `slots` items, regardless of composition, is rejected.
+        // This is a plain headcount over ALL inputs (metals and modifiers alike) --
+        // composition is judged only afterward, by rules 4-8. A modifier still
+        // occupies a slot, so "4 iron + 1 coal" at slots=5 passes this check.
+        if (inputs.size() < settings.slots()) {
+            return new RecycleResult.Rejected("needs " + settings.slots() + " items");
+        }
+
+        // Rule 4: every input must be either a metal or a modifier. An alloy item
+        // that is not alone (rule 2 already returned for the sole-alloy case), or any
+        // genuinely unrecognized item, trips this rule.
+        boolean hasNonMetalNonModifier = inputs.stream()
+                .anyMatch(input -> input.metal() == null && !input.isModifier());
+        if (hasNonMetalNonModifier) {
+            return new RecycleResult.Rejected("non-metal input");
+        }
+
+        // Rule 5: only modifiers, no metals at all -- coal alone is never recyclable.
+        boolean anyMetal = inputs.stream().anyMatch(input -> input.metal() != null);
+        if (!anyMetal) {
+            return new RecycleResult.Rejected("no metal");
+        }
+
+        boolean anyModifier = inputs.stream().anyMatch(RecycleInput::isModifier);
+
+        // Rule 6: all metals identical AND no modifier present.
+        if (!anyModifier) {
+            Set<MetalType> distinctMetals = inputs.stream()
+                    .map(RecycleInput::metal)
+                    .collect(Collectors.toSet());
+            if (distinctMetals.size() == 1) {
+                MetalType metal = distinctMetals.iterator().next();
+                return new RecycleResult.SameMetal(metal, settings.yieldSameMetal());
+            }
+        }
+
+        // Rule 7: named alloy recipe match, by the distinct set of present ids.
+        Set<String> presentIds = inputs.stream()
+                .map(RecycleResolver::idOf)
+                .collect(Collectors.toSet());
+        Optional<AlloyDefinition> namedMatch = alloys.findNamedMatch(presentIds);
+        if (namedMatch.isPresent()) {
+            return new RecycleResult.NamedAlloy(namedMatch.get().id(), settings.yieldMixedAlloy());
+        }
+
+        // Rule 8: otherwise, the generic fallback alloy.
+        AlloyDefinition fallback = alloys.fallback();
+        return new RecycleResult.GenericAlloy(fallback.id(), settings.yieldMixedAlloy());
+    }
+
+    /**
+     * The id used for named-recipe matching: a metal's lowercase enum name (e.g.
+     * {@code "iron"}) for metal inputs, or the raw {@code materialId} (e.g.
+     * {@code "coal"}) for modifiers.
+     */
+    private static String idOf(RecycleInput input) {
+        if (input.metal() != null) {
+            return input.metal().name().toLowerCase(Locale.ROOT);
+        }
+        return input.materialId();
+    }
+}
diff --git a/src/main/java/org/xpfarm/electricfurnace/recycle/RecycleResult.java b/src/main/java/org/xpfarm/electricfurnace/recycle/RecycleResult.java
new file mode 100644
index 0000000..818bb57
--- /dev/null
+++ b/src/main/java/org/xpfarm/electricfurnace/recycle/RecycleResult.java
@@ -0,0 +1,72 @@
+/*
+ * ElectricFurnace - a redstone-powered smelter that recycles metal gear into ingots.
+ * Copyright (C) 2026 Carmelo Santana
+ *
+ * This program is free software: you can redistribute it and/or modify it under the
+ * terms of the GNU Affero General Public License as published by the Free Software
+ * Foundation, either version 3 of the License, or (at your option) any later version.
+ * See the LICENSE file at the project root for the full license text.
+ */
+package org.xpfarm.electricfurnace.recycle;
+
+import org.xpfarm.electricfurnace.alloy.MetalType;
+
+/**
+ * The outcome of resolving a set of recycler inputs. Exactly one implementation is
+ * produced per call to {@link RecycleResolver#resolve}.
+ */
+public sealed interface RecycleResult
+        permits RecycleResult.SameMetal, RecycleResult.NamedAlloy, RecycleResult.GenericAlloy,
+        RecycleResult.Remelt, RecycleResult.Rejected {
+
+    /** Discriminator mirroring the sealed permits, for callers that prefer a switch on a plain enum. */
+    enum Kind {
+        SAME_METAL,
+        NAMED_ALLOY,
+        GENERIC_ALLOY,
+        REMELT,
+        REJECTED
+    }
+
+    Kind kind();
+
+    /** All inputs were the same metal, with no modifier present. Yields {@code amount} ingots of {@code metal}. */
+    record SameMetal(MetalType metal, int amount) implements RecycleResult {
+        @Override
+        public Kind kind() {
+            return Kind.SAME_METAL;
+        }
+    }
+
+    /** The input matched a named alloy recipe. Yields {@code amount} ingots of {@code alloyId}. */
+    record NamedAlloy(String alloyId, int amount) implements RecycleResult {
+        @Override
+        public Kind kind() {
+            return Kind.NAMED_ALLOY;
+        }
+    }
+
+    /** The input was a mixed/unrecognized combination. Yields {@code amount} ingots of the generic fallback alloy. */
+    record GenericAlloy(String alloyId, int amount) implements RecycleResult {
+        @Override
+        public Kind kind() {
+            return Kind.GENERIC_ALLOY;
+        }
+    }
+
+    /** A single alloy item was remelted. Yields {@code amount} ingots of {@code alloyId}. */
+    record Remelt(String alloyId, int amount) implements RecycleResult {
+        @Override
+        public Kind kind() {
+            return Kind.REMELT;
+        }
+    }
+
+    /** The input could not be resolved. {@code reason} is a short machine-stable code, e.g. {@code "empty"}. */
+    record Rejected(String reason) implements RecycleResult {
+        @Override
+        public Kind kind() {
+            return Kind.REJECTED;
+        }
+    }
+}
diff --git a/src/main/resources/config.yml b/src/main/resources/config.yml
index de90cd2..bfc3d3b 100644
--- a/src/main/resources/config.yml
+++ b/src/main/resources/config.yml
@@ -47,39 +47,91 @@ recycling:
   # recipe below or falls back to the generic Fused Alloy. Valid range: 0-64.
   yield-mixed-alloy: 2
   # Ingots yielded when remelting a single alloy item. This is the only recipe
   # that accepts fewer than `slots` items. Valid range: 0-64.
   yield-remelt-alloy: 1
   # Whether damaged (partially worn) gear is accepted at full yield. Durability is
   # never scaled into the yield -- giving worn-out gear a worthwhile destination
   # is a primary purpose of this plugin.
   accept-damaged: true
 
-# Alloy recipes. This section is a placeholder for Task 2, which defines the
-# AlloyDefinition/AlloyRegistry model and parses these entries -- no code in this
-# task reads this section. Each entry names the distinct inputs (metals and/or
-# the coal modifier) required to produce that alloy, in any order/quantity as
-# long as every listed input is present. `fused_alloy` is the generic fallback
-# for any mixed-metal combination that matches nothing else.
+# Alloy recipes, parsed by AlloyRegistry#load (Task 2). Each entry names the
+# distinct inputs (metals and/or the coal modifier) required to produce that
+# alloy, in any order/quantity as long as every listed input is present.
+# `fused_alloy` is the generic fallback for any mixed-metal combination that
+# matches nothing else -- it is identified by its empty `inputs` list, not by
+# its id, so renaming it is safe as long as exactly one recipe has empty inputs.
 #
-# Stats (attack damage, attack speed, armor, armor toughness, max durability,
-# enchantability) are added by Task 2. Per the balance ceiling above, no stat may
-# exceed the netherite reference; out-of-range stats are clamped to the diamond
-# reference and logged.
+# `stats` is the full stat block (attack damage, attack speed, armor, armor
+# toughness, max durability, enchantability). Per the balance ceiling above, no
+# stat may exceed the netherite reference; out-of-range stats are clamped to the
+# diamond reference and logged. This clamp runs unconditionally in code -- there
+# is no switch here to disable it.
 alloys:
   steel:
     display-name: "Steel"
+    color: "#71797E"
+    lore:
+      - "A carbon-hardened iron alloy."
+      - "Stronger than iron, cheaper than netherite."
     inputs: [iron, coal]
+    stats:
+      attack-damage: 6.5
+      attack-speed: -2.6
+      armor: 16
+      armor-toughness: 1.0
+      max-durability: 700
+      enchantability: 12
   rose_gold:
     display-name: "Rose Gold"
+    color: "#B76E79"
+    lore:
+      - "A warm copper-gold blend."
     inputs: [copper, gold]
+    stats:
+      attack-damage: 6.2
+      attack-speed: -2.5
+      armor: 15
+      armor-toughness: 0.5
+      max-durability: 500
+      enchantability: 20
   ferrocopper:
     display-name: "Ferrocopper"
+    color: "#B87333"
+    lore:
+      - "Copper toughened with iron."
     inputs: [copper, iron]
+    stats:
+      attack-damage: 6.3
+      attack-speed: -2.5
+      armor: 16
+      armor-toughness: 1.0
+      max-durability: 600
+      enchantability: 14
   electrum_steel:
     display-name: "Electrum Steel"
+    color: "#D4C9A8"
+    lore:
+      - "Gold-veined structural steel."
     inputs: [gold, iron]
+    stats:
+      attack-damage: 6.8
+      attack-speed: -2.4
+      armor: 18
+      armor-toughness: 1.5
+      max-durability: 900
+      enchantability: 16
   fused_alloy:
     display-name: "Fused Alloy"
+    color: "#4B4B4B"
+    lore:
+      - "An unrefined fusion of leftover metals."
     # Fallback: matches any mixed-metal combination not covered by a named recipe
     # above (or 3+ distinct metals). Intentionally has no fixed input list.
     inputs: []
+    stats:
+      attack-damage: 6.0
+      attack-speed: -2.6
+      armor: 15
+      armor-toughness: 0.5
+      max-durability: 400
+      enchantability: 10
diff --git a/src/test/java/org/xpfarm/electricfurnace/alloy/AlloyRegistryTest.java b/src/test/java/org/xpfarm/electricfurnace/alloy/AlloyRegistryTest.java
new file mode 100644
index 0000000..af13770
--- /dev/null
+++ b/src/test/java/org/xpfarm/electricfurnace/alloy/AlloyRegistryTest.java
@@ -0,0 +1,146 @@
+/*
+ * ElectricFurnace - a redstone-powered smelter that recycles metal gear into ingots.
+ * Copyright (C) 2026 Carmelo Santana
+ *
+ * This program is free software: you can redistribute it and/or modify it under the
+ * terms of the GNU Affero General Public License as published by the Free Software
+ * Foundation, either version 3 of the License, or (at your option) any later version.
+ * See the LICENSE file at the project root for the full license text.
+ */
+package org.xpfarm.electricfurnace.alloy;
+
+import org.junit.jupiter.api.Test;
+
+import java.util.ArrayList;
+import java.util.List;
+import java.util.Optional;
+import java.util.Set;
+
+import static org.junit.jupiter.api.Assertions.assertEquals;
+import static org.junit.jupiter.api.Assertions.assertFalse;
+import static org.junit.jupiter.api.Assertions.assertTrue;
+
+/**
+ * Unit tests for {@link AlloyRegistry}. Exercises {@link AlloyRegistry#fromDefinitions},
+ * the pure core -- no {@code ConfigurationSection} or running server is needed.
+ */
+class AlloyRegistryTest {
+
+    private static final AlloyStats BASELINE_STATS = new AlloyStats(6.5, -2.6, 16, 1.0, 700, 12);
+
+    private final List<String> warnings = new ArrayList<>();
+
+    private void warn(String message) {
+        warnings.add(message);
+    }
+
+    // ---- Recipe matching is order-independent ---------------------------------------
+
+    @Test
+    void findNamedMatch_isOrderIndependent() {
+        AlloyDefinition steel = new AlloyDefinition("steel", "Steel", List.of(), "#71797E",
+                Set.of("iron", "coal"), BASELINE_STATS);
+        AlloyRegistry registry = AlloyRegistry.fromDefinitions(List.of(steel, fallback()), this::warn);
+
+        // Set.of(...) is unordered by construction, but build it two different ways to
+        // make the intent explicit: the match must not depend on insertion order.
+        Optional<AlloyDefinition> matchA = registry.findNamedMatch(Set.of("iron", "coal"));
+        Optional<AlloyDefinition> matchB = registry.findNamedMatch(Set.of("coal", "iron"));
+
+        assertTrue(matchA.isPresent());
+        assertTrue(matchB.isPresent());
+        assertEquals("steel", matchA.get().id());
+        assertEquals("steel", matchB.get().id());
+    }
+
+    @Test
+    void findNamedMatch_noMatch_returnsEmpty() {
+        AlloyDefinition steel = new AlloyDefinition("steel", "Steel", List.of(), "#71797E",
+                Set.of("iron", "coal"), BASELINE_STATS);
+        AlloyRegistry registry = AlloyRegistry.fromDefinitions(List.of(steel, fallback()), this::warn);
+
+        Optional<AlloyDefinition> match = registry.findNamedMatch(Set.of("gold", "copper"));
+
+        assertTrue(match.isEmpty());
+    }
+
+    // ---- Unknown mix falls back to fused_alloy --------------------------------------
+
+    @Test
+    void unknownMix_fallsBackToFusedAlloy() {
+        AlloyDefinition steel = new AlloyDefinition("steel", "Steel", List.of(), "#71797E",
+                Set.of("iron", "coal"), BASELINE_STATS);
+        AlloyRegistry registry = AlloyRegistry.fromDefinitions(List.of(steel, fallback()), this::warn);
+
+        assertEquals("fused_alloy", registry.fallback().id());
+        assertTrue(registry.fallback().isFallback());
+    }
+
+    @Test
+    void missingFallbackInConfig_synthesizesDefaultAndWarns() {
+        AlloyDefinition steel = new AlloyDefinition("steel", "Steel", List.of(), "#71797E",
+                Set.of("iron", "coal"), BASELINE_STATS);
+        AlloyRegistry registry = AlloyRegistry.fromDefinitions(List.of(steel), this::warn);
+
+        assertEquals("fused_alloy", registry.fallback().id());
+        assertFalse(warnings.isEmpty(), "a missing fallback recipe should warn");
+    }
+
+    // ---- Balance ceiling: a stat above netherite is clamped and warned --------------
+
+    @Test
+    void statAboveNetherite_isClampedToDiamondAndWarns() {
+        AlloyStats overpowered = new AlloyStats(
+                AlloyRegistry.NETHERITE_ATTACK_DAMAGE + 1.0, -2.6, 16, 1.0, 700, 12);
+        AlloyDefinition busted = new AlloyDefinition("busted", "Busted Alloy", List.of(), "#000000",
+                Set.of("iron", "gold"), overpowered);
+
+        AlloyRegistry registry = AlloyRegistry.fromDefinitions(List.of(busted, fallback()), this::warn);
+
+        AlloyDefinition clamped = registry.get("busted").orElseThrow();
+        assertEquals(AlloyRegistry.DIAMOND_ATTACK_DAMAGE, clamped.stats().attackDamage());
+        assertEquals(1, warnings.size());
+        assertTrue(warnings.get(0).contains("busted"), "warning should name the alloy");
+        assertTrue(warnings.get(0).contains("attack-damage"), "warning should name the stat");
+        assertTrue(warnings.get(0).contains(String.valueOf(overpowered.attackDamage())),
+                "warning should name the configured value");
+    }
+
+    @Test
+    void statAtOrBelowNetherite_passesThroughUnclamped() {
+        AlloyStats atCeiling = new AlloyStats(
+                AlloyRegistry.NETHERITE_ATTACK_DAMAGE, -2.6, 16, 1.0, 700, 12);
+        AlloyDefinition fine = new AlloyDefinition("fine", "Fine Alloy", List.of(), "#000000",
+                Set.of("iron", "gold"), atCeiling);
+
+        AlloyRegistry registry = AlloyRegistry.fromDefinitions(List.of(fine, fallback()), this::warn);
+
+        assertEquals(AlloyRegistry.NETHERITE_ATTACK_DAMAGE, registry.get("fine").orElseThrow().stats().attackDamage());
+        assertTrue(warnings.isEmpty());
+    }
+
+    @Test
+    void multipleStatsAboveNetherite_areAllClampedAndEachWarned() {
+        AlloyStats overpowered = new AlloyStats(
+                AlloyRegistry.NETHERITE_ATTACK_DAMAGE + 1.0,
+                -2.6,
+                AlloyRegistry.NETHERITE_ARMOR,
+                AlloyRegistry.NETHERITE_ARMOR_TOUGHNESS + 1.0,
+                AlloyRegistry.NETHERITE_MAX_DURABILITY + 500,
+                12);
+        AlloyDefinition busted = new AlloyDefinition("busted", "Busted Alloy", List.of(), "#000000",
+                Set.of("iron", "gold"), overpowered);
+
+        AlloyRegistry registry = AlloyRegistry.fromDefinitions(List.of(busted, fallback()), this::warn);
+
+        AlloyStats clamped = registry.get("busted").orElseThrow().stats();
+        assertEquals(AlloyRegistry.DIAMOND_ATTACK_DAMAGE, clamped.attackDamage());
+        assertEquals(AlloyRegistry.DIAMOND_ARMOR_TOUGHNESS, clamped.armorToughness());
+        assertEquals(AlloyRegistry.DIAMOND_MAX_DURABILITY, clamped.maxDurability());
+        assertEquals(3, warnings.size(), "attack-damage, armor-toughness, and max-durability should each warn once");
+    }
+
+    private static AlloyDefinition fallback() {
+        return new AlloyDefinition("fused_alloy", "Fused Alloy", List.of(), "#4B4B4B", Set.of(), BASELINE_STATS);
+    }
+}
diff --git a/src/test/java/org/xpfarm/electricfurnace/recycle/RecycleResolverTest.java b/src/test/java/org/xpfarm/electricfurnace/recycle/RecycleResolverTest.java
new file mode 100644
index 0000000..90a4093
--- /dev/null
+++ b/src/test/java/org/xpfarm/electricfurnace/recycle/RecycleResolverTest.java
@@ -0,0 +1,339 @@
+/*
+ * ElectricFurnace - a redstone-powered smelter that recycles metal gear into ingots.
+ * Copyright (C) 2026 Carmelo Santana
+ *
+ * This program is free software: you can redistribute it and/or modify it under the
+ * terms of the GNU Affero General Public License as published by the Free Software
+ * Foundation, either version 3 of the License, or (at your option) any later version.
+ * See the LICENSE file at the project root for the full license text.
+ */
+package org.xpfarm.electricfurnace.recycle;
+
+import org.junit.jupiter.api.Test;
+import org.xpfarm.electricfurnace.alloy.AlloyDefinition;
+import org.xpfarm.electricfurnace.alloy.AlloyRegistry;
+import org.xpfarm.electricfurnace.alloy.AlloyStats;
+import org.xpfarm.electricfurnace.alloy.MetalType;
+import org.xpfarm.electricfurnace.config.RecyclingSettings;
+
+import java.util.ArrayList;
+import java.util.List;
+import java.util.Set;
+
+import static org.junit.jupiter.api.Assertions.assertEquals;
+import static org.junit.jupiter.api.Assertions.assertInstanceOf;
+import static org.junit.jupiter.api.Assertions.assertTrue;
+
+/**
+ * Pure unit tests for {@link RecycleResolver}. Deliberately imports nothing from
+ * {@code org.bukkit} -- every input is a hand-built {@link RecycleInput}, and the
+ * alloy recipes come from {@link AlloyRegistry#fromDefinitions}, which is itself
+ * Bukkit-free.
+ *
+ * <p>Covers the eight resolution rules in their precedence order, plus the specific
+ * scenarios called out in the plan -- most notably the rule 6 vs. rule 7 interaction:
+ * a modifier's mere presence disqualifies "all same metal" even when every metal
+ * input is identical, routing the combination to the named-recipe check instead.
+ */
+class RecycleResolverTest {
+
+    /** Shipping defaults: slots=5, yield-same-metal=3, yield-mixed-alloy=2, yield-remelt-alloy=1. */
+    private static final RecyclingSettings DEFAULT_SETTINGS = new RecyclingSettings(5, 3, 2, 1, true);
+
+    private static final AlloyRegistry REGISTRY = shippingRegistry();
+
+    // ---- Rule 1: empty input --------------------------------------------------------
+
+    @Test
+    void rule1_emptyInput_rejected() {
+        RecycleResult result = RecycleResolver.resolve(List.of(), DEFAULT_SETTINGS, REGISTRY);
+
+        RecycleResult.Rejected rejected = assertInstanceOf(RecycleResult.Rejected.class, result);
+        assertEquals("empty", rejected.reason());
+    }
+
+    @Test
+    void rule1_nullInput_treatedAsEmpty_rejected() {
+        RecycleResult result = RecycleResolver.resolve(null, DEFAULT_SETTINGS, REGISTRY);
+
+        RecycleResult.Rejected rejected = assertInstanceOf(RecycleResult.Rejected.class, result);
+        assertEquals("empty", rejected.reason());
+    }
+
+    // ---- Rule 2: single alloy item remelts, the only exception to the slot count ----
+
+    @Test
+    void rule2_singleAlloyItem_remelts() {
+        RecycleResult result = RecycleResolver.resolve(List.of(alloy("steel")), DEFAULT_SETTINGS, REGISTRY);
+
+        RecycleResult.Remelt remelt = assertInstanceOf(RecycleResult.Remelt.class, result);
+        assertEquals("steel", remelt.alloyId());
+        assertEquals(1, remelt.amount());
+    }
+
+    @Test
+    void acceptanceCheck_oneAlloyIn_yieldsOneIngotOut() {
+        RecycleResult result = RecycleResolver.resolve(List.of(alloy("fused_alloy")), DEFAULT_SETTINGS, REGISTRY);
+
+        RecycleResult.Remelt remelt = assertInstanceOf(RecycleResult.Remelt.class, result);
+        assertEquals(1, remelt.amount());
+    }
+
+    // ---- Rule 3: fewer than `slots` items is rejected, regardless of composition ----
+
+    @Test
+    void rule3_fourItems_belowSlotCount_rejected() {
+        List<RecycleInput> inputs = List.of(iron(), iron(), iron(), iron());
+
+        RecycleResult result = RecycleResolver.resolve(inputs, DEFAULT_SETTINGS, REGISTRY);
+
+        RecycleResult.Rejected rejected = assertInstanceOf(RecycleResult.Rejected.class, result);
+        assertEquals("needs 5 items", rejected.reason());
+    }
+
+    @Test
+    void rule3_belowSlotCount_appliesEvenToMixedComposition() {
+        List<RecycleInput> inputs = List.of(iron(), iron(), gold(), coal());
+
+        RecycleResult result = RecycleResolver.resolve(inputs, DEFAULT_SETTINGS, REGISTRY);
+
+        assertInstanceOf(RecycleResult.Rejected.class, result);
+    }
+
+    // ---- Rule 4: any non-metal, non-modifier input rejects the whole batch ----------
+
+    @Test
+    void rule4_nonMetalInput_rejected() {
+        List<RecycleInput> inputs = List.of(iron(), iron(), iron(), iron(), nonMetal("dirt"));
+
+        RecycleResult result = RecycleResolver.resolve(inputs, DEFAULT_SETTINGS, REGISTRY);
+
+        RecycleResult.Rejected rejected = assertInstanceOf(RecycleResult.Rejected.class, result);
+        assertEquals("non-metal input", rejected.reason());
+    }
+
+    @Test
+    void rule4_alloyItemAmongOthers_isTreatedAsNonMetalInput() {
+        // A single alloy item alone remelts (rule 2); mixed with anything else it is
+        // neither a metal nor a modifier, so it falls through to rule 4.
+        List<RecycleInput> inputs = List.of(iron(), iron(), iron(), iron(), alloy("steel"));
+
+        RecycleResult result = RecycleResolver.resolve(inputs, DEFAULT_SETTINGS, REGISTRY);
+
+        RecycleResult.Rejected rejected = assertInstanceOf(RecycleResult.Rejected.class, result);
+        assertEquals("non-metal input", rejected.reason());
+    }
+
+    // ---- Rule 5: only modifiers, no metals ------------------------------------------
+
+    @Test
+    void rule5_fiveCoal_rejectedNoMetal() {
+        List<RecycleInput> inputs = List.of(coal(), coal(), coal(), coal(), coal());
+
+        RecycleResult result = RecycleResolver.resolve(inputs, DEFAULT_SETTINGS, REGISTRY);
+
+        RecycleResult.Rejected rejected = assertInstanceOf(RecycleResult.Rejected.class, result);
+        assertEquals("no metal", rejected.reason());
+    }
+
+    // ---- Rule 6: all metals identical AND no modifiers -> SAME_METAL ---------------
+
+    @Test
+    void rule6_fiveIron_sameMetal_yieldsConfiguredAmount() {
+        List<RecycleInput> inputs = List.of(iron(), iron(), iron(), iron(), iron());
+
+        RecycleResult result = RecycleResolver.resolve(inputs, DEFAULT_SETTINGS, REGISTRY);
+
+        RecycleResult.SameMetal sameMetal = assertInstanceOf(RecycleResult.SameMetal.class, result);
+        assertEquals(MetalType.IRON, sameMetal.metal());
+        assertEquals(3, sameMetal.amount());
+    }
+
+    // ---- The subtlest interaction: rule 6 vs. rule 7 --------------------------------
+
+    @Test
+    void rule6vs7_fourIronPlusCoal_isNotSameMetal_matchesSteelInstead() {
+        List<RecycleInput> inputs = List.of(iron(), iron(), iron(), iron(), coal());
+
+        RecycleResult result = RecycleResolver.resolve(inputs, DEFAULT_SETTINGS, REGISTRY);
+
+        // Must NOT be same-metal, even though every metal present is iron.
+        assertTrue(result.kind() != RecycleResult.Kind.SAME_METAL,
+                "a modifier's presence must disqualify the same-metal rule");
+        RecycleResult.NamedAlloy namedAlloy = assertInstanceOf(RecycleResult.NamedAlloy.class, result);
+        assertEquals("steel", namedAlloy.alloyId());
+        assertEquals(2, namedAlloy.amount());
+    }
+
+    // ---- Rule 7: named alloy recipe match, order-independent ------------------------
+
+    @Test
+    void rule7_copperAndGold_matchesRoseGold() {
+        List<RecycleInput> inputs = List.of(copper(), copper(), gold(), gold(), gold());
+
+        RecycleResult result = RecycleResolver.resolve(inputs, DEFAULT_SETTINGS, REGISTRY);
+
+        RecycleResult.NamedAlloy namedAlloy = assertInstanceOf(RecycleResult.NamedAlloy.class, result);
+        assertEquals("rose_gold", namedAlloy.alloyId());
+        assertEquals(2, namedAlloy.amount());
+    }
+
+    // ---- Rule 8: unrecognized mix -> generic Fused Alloy ----------------------------
+
+    @Test
+    void rule8_threeDistinctMetals_genericFusedAlloy() {
+        List<RecycleInput> inputs = List.of(iron(), gold(), copper(), iron(), gold());
+
+        RecycleResult result = RecycleResolver.resolve(inputs, DEFAULT_SETTINGS, REGISTRY);
+
+        RecycleResult.GenericAlloy genericAlloy = assertInstanceOf(RecycleResult.GenericAlloy.class, result);
+        assertEquals("fused_alloy", genericAlloy.alloyId());
+        assertEquals(2, genericAlloy.amount());
+    }
+
+    // ---- Acceptance-check-shaped tests, matching the plan's explicit list -----------
+
+    @Test
+    void acceptanceCheck_fiveIron_yieldsThreeIronIngots() {
+        List<RecycleInput> inputs = List.of(iron(), iron(), iron(), iron(), iron());
+
+        RecycleResult result = RecycleResolver.resolve(inputs, DEFAULT_SETTINGS, REGISTRY);
+
+        RecycleResult.SameMetal sameMetal = assertInstanceOf(RecycleResult.SameMetal.class, result);
+        assertEquals(3, sameMetal.amount());
+    }
+
+    @Test
+    void acceptanceCheck_fiveMixedMetals_yieldsTwoGenericFusedAlloy() {
+        List<RecycleInput> inputs = List.of(iron(), gold(), copper(), netherite(), iron());
+
+        RecycleResult result = RecycleResolver.resolve(inputs, DEFAULT_SETTINGS, REGISTRY);
+
+        RecycleResult.GenericAlloy genericAlloy = assertInstanceOf(RecycleResult.GenericAlloy.class, result);
+        assertEquals("fused_alloy", genericAlloy.alloyId());
+        assertEquals(2, genericAlloy.amount());
+    }
+
+    @Test
+    void acceptanceCheck_fourIronOneCoal_yieldsTwoSteel() {
+        List<RecycleInput> inputs = List.of(iron(), iron(), iron(), iron(), coal());
+
+        RecycleResult result = RecycleResolver.resolve(inputs, DEFAULT_SETTINGS, REGISTRY);
+
+        RecycleResult.NamedAlloy namedAlloy = assertInstanceOf(RecycleResult.NamedAlloy.class, result);
+        assertEquals("steel", namedAlloy.alloyId());
+        assertEquals(2, namedAlloy.amount());
+    }
+
+    @Test
+    void acceptanceCheck_fiveCoal_rejected() {
+        List<RecycleInput> inputs = List.of(coal(), coal(), coal(), coal(), coal());
+
+        RecycleResult result = RecycleResolver.resolve(inputs, DEFAULT_SETTINGS, REGISTRY);
+
+        assertInstanceOf(RecycleResult.Rejected.class, result);
+    }
+
+    @Test
+    void acceptanceCheck_oneAlloy_yieldsOneIngot() {
+        RecycleResult result = RecycleResolver.resolve(List.of(alloy("rose_gold")), DEFAULT_SETTINGS, REGISTRY);
+
+        RecycleResult.Remelt remelt = assertInstanceOf(RecycleResult.Remelt.class, result);
+        assertEquals(1, remelt.amount());
+    }
+
+    @Test
+    void acceptanceCheck_fourItems_rejectedBelowSlotCount() {
+        List<RecycleInput> inputs = List.of(iron(), iron(), iron(), iron());
+
+        RecycleResult result = RecycleResolver.resolve(inputs, DEFAULT_SETTINGS, REGISTRY);
+
+        assertInstanceOf(RecycleResult.Rejected.class, result);
+    }
+
+    @Test
+    void acceptanceCheck_damagedAndUndamagedInputs_produceIdenticalResults() {
+        // RecycleInput has no durability/damage field at all -- accept-damaged means
+        // Task 3's classifier discards damage before ever building a RecycleInput, so
+        // "damaged" and "undamaged" gear map to the exact same records here. Two
+        // independently-built but value-identical input lists must resolve identically.
+        List<RecycleInput> undamaged = List.of(iron(), iron(), iron(), iron(), iron());
+        List<RecycleInput> damaged = List.of(iron(), iron(), iron(), iron(), iron());
+
+        RecycleResult undamagedResult = RecycleResolver.resolve(undamaged, DEFAULT_SETTINGS, REGISTRY);
+        RecycleResult damagedResult = RecycleResolver.resolve(damaged, DEFAULT_SETTINGS, REGISTRY);
+
+        assertEquals(undamagedResult, damagedResult);
+    }
+
+    @Test
+    void acceptanceCheck_yieldsTrackConfig_notHardcodedNumbers() {
+        RecyclingSettings customSettings = new RecyclingSettings(5, 30, 20, 10, true);
+        List<RecycleInput> sameMetalInputs = List.of(iron(), iron(), iron(), iron(), iron());
+        List<RecycleInput> namedAlloyInputs = List.of(iron(), iron(), iron(), iron(), coal());
+        List<RecycleInput> genericInputs = List.of(iron(), gold(), copper(), iron(), gold());
+        List<RecycleInput> remeltInputs = List.of(alloy("steel"));
+
+        RecycleResult.SameMetal sameMetal = assertInstanceOf(RecycleResult.SameMetal.class,
+                RecycleResolver.resolve(sameMetalInputs, customSettings, REGISTRY));
+        RecycleResult.NamedAlloy namedAlloy = assertInstanceOf(RecycleResult.NamedAlloy.class,
+                RecycleResolver.resolve(namedAlloyInputs, customSettings, REGISTRY));
+        RecycleResult.GenericAlloy genericAlloy = assertInstanceOf(RecycleResult.GenericAlloy.class,
+                RecycleResolver.resolve(genericInputs, customSettings, REGISTRY));
+        RecycleResult.Remelt remelt = assertInstanceOf(RecycleResult.Remelt.class,
+                RecycleResolver.resolve(remeltInputs, customSettings, REGISTRY));
+
+        assertEquals(30, sameMetal.amount());
+        assertEquals(20, namedAlloy.amount());
+        assertEquals(20, genericAlloy.amount());
+        assertEquals(10, remelt.amount());
+    }
+
+    // ---- Test fixtures ---------------------------------------------------------------
+
+    private static RecycleInput iron() {
+        return new RecycleInput("iron_ingot", MetalType.IRON, false, false, null, 1);
+    }
+
+    private static RecycleInput gold() {
+        return new RecycleInput("gold_ingot", MetalType.GOLD, false, false, null, 1);
+    }
+
+    private static RecycleInput copper() {
+        return new RecycleInput("copper_ingot", MetalType.COPPER, false, false, null, 1);
+    }
+
+    private static RecycleInput netherite() {
+        return new RecycleInput("netherite_ingot", MetalType.NETHERITE, false, false, null, 1);
+    }
+
+    private static RecycleInput coal() {
+        return new RecycleInput("coal", null, true, false, null, 0);
+    }
+
+    private static RecycleInput nonMetal(String materialId) {
+        return new RecycleInput(materialId, null, false, false, null, 0);
+    }
+
+    private static RecycleInput alloy(String alloyId) {
+        return new RecycleInput(alloyId, null, false, true, alloyId, 1);
+    }
+
+    private static AlloyRegistry shippingRegistry() {
+        List<String> warnings = new ArrayList<>();
+        AlloyStats basicStats = new AlloyStats(6.5, -2.6, 16, 1.0, 700, 12);
+        List<AlloyDefinition> definitions = List.of(
+                new AlloyDefinition("steel", "Steel", List.of("A carbon-hardened iron alloy."),
+                        "#71797E", Set.of("iron", "coal"), basicStats),
+                new AlloyDefinition("rose_gold", "Rose Gold", List.of("A warm copper-gold blend."),
+                        "#B76E79", Set.of("copper", "gold"), basicStats),
+                new AlloyDefinition("ferrocopper", "Ferrocopper", List.of("Copper toughened with iron."),
+                        "#B87333", Set.of("copper", "iron"), basicStats),
+                new AlloyDefinition("electrum_steel", "Electrum Steel", List.of("Gold-veined structural steel."),
+                        "#D4C9A8", Set.of("gold", "iron"), basicStats),
+                new AlloyDefinition("fused_alloy", "Fused Alloy", List.of("An unrefined fusion of leftover metals."),
+                        "#4B4B4B", Set.of(), basicStats)
+        );
+        return AlloyRegistry.fromDefinitions(definitions, warnings::add);
+    }
+}
```
