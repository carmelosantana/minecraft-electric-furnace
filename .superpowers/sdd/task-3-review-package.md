# Review package: a4b1ba0..84862ac

84862ac Task 3: item layer and cross-plugin PDC contract

 .superpowers/sdd/task-3-report.md                  | 144 ++++++++++++++
 .../electricfurnace/item/AlloyItemFactory.java     |  71 +++++++
 .../electricfurnace/item/MachineItemFactory.java   |  61 ++++++
 .../electricfurnace/item/MaterialContract.java     |  94 +++++++++
 .../electricfurnace/item/MetalClassifier.java      | 210 ++++++++++++++++++++
 .../electricfurnace/item/MetalClassifierTest.java  | 214 +++++++++++++++++++++
 6 files changed, 794 insertions(+)

```diff
diff --git a/.superpowers/sdd/task-3-report.md b/.superpowers/sdd/task-3-report.md
new file mode 100644
index 0000000..8ec6e53
--- /dev/null
+++ b/.superpowers/sdd/task-3-report.md
@@ -0,0 +1,144 @@
+# Task 3 — Item layer and the cross-plugin PDC contract — Report
+
+## What was implemented
+
+Followed TDD: wrote `MetalClassifierTest` first (confirmed it failed to compile with
+no implementation present), then implemented the four production classes.
+
+- **`MaterialContract`** — the shared `xpfarm:` PDC namespace. `CUSTOM_MATERIAL`
+  (`xpfarm:custom_material`, STRING), `MATERIAL_ID` (`xpfarm:material_id`, STRING),
+  and `MACHINE` (`electricfurnace:machine`, BYTE), all built with the
+  `NamespacedKey(String, String)` constructor as required. Read helpers
+  (`readCustomMaterial`, `readMaterialId`, `isMachine`) return `Optional<String>`/
+  `boolean`. Also exposes read-only recognition of CopperKingdom's foreign keys
+  (`copperkingdom:copper_armor`, `copperkingdom:copper_weapon`, both STRING) —
+  never written to.
+
+- **`MetalClassifier`** — the Bukkit↔pure-model bridge, split into two layers:
+  - A pure core (`metalOf(Material)`, `isModifier(Material)`, and a primitive overload
+    `classify(Material, String materialId, boolean damaged, boolean isAlloyStamped,
+    String alloyId, RecyclingSettings)`) touching nothing but `Material` and plain
+    values.
+  - `classify(ItemStack, RecyclingSettings)`, the thin Bukkit-facing overload that
+    reads type, PDC (`xpfarm:` keys, then CopperKingdom's keys as a fallback for
+    copper gear), and durability (`Damageable#hasDamage()`), then delegates to the
+    pure core.
+
+  The static `METAL_TABLE` (package-private, exhaustively asserted by the test) maps:
+  IRON (ingot, raw, sword/spear/pickaxe/axe/shovel/hoe, helmet/chestplate/leggings/
+  boots, plus all four chainmail pieces), GOLD, COPPER, and NETHERITE the same way
+  (no raw/chainmail equivalent for netherite). Nuggets (iron/gold/copper — no
+  netherite nugget exists) are explicitly rejected as sub-ingot value. Coal and
+  charcoal are both modifiers sharing the same named-alloy id `"coal"` so charcoal
+  also satisfies the Steel recipe. Unrecognized materials (dirt, stick, etc.)
+  classify to `Optional.empty()`.
+
+  **Accept-damaged (the carried-over obligation from Task 2):** the pure core takes
+  `damaged` as an explicit boolean parameter — no `ItemStack` needed to prove this
+  behavior. When `acceptDamaged=true`, `damaged` is not consulted at all beyond the
+  gate check, so a pristine and a heavily-damaged item of the same material produce
+  `.equals()`-identical `RecycleInput` values (tested directly). When
+  `acceptDamaged=false`, a damaged item classifies to `Optional.empty()` (rejected
+  outright) while a pristine item of the same type still classifies normally (also
+  tested). Alloy items bypass this gate entirely — remelting isn't a "damaged gear"
+  concept.
+
+- **`AlloyItemFactory`** — builds an alloy `ItemStack` on base `Material.NETHERITE_INGOT`,
+  name/lore from `AlloyDefinition` as Adventure `Component`s (`.decoration(ITALIC,
+  false)`, color from the definition's hex string via `TextColor.fromHexString`,
+  falling back to white if unparseable), stamped with both `xpfarm:` keys. No custom
+  model data anywhere.
+
+- **`MachineItemFactory`** — builds the machine item on base `Material.BLAST_FURNACE`,
+  named "Electric Furnace" with lore explaining the redstone requirement, stamped
+  with `electricfurnace:machine` (BYTE marker `1`).
+
+## Files
+
+- `src/main/java/org/xpfarm/electricfurnace/item/MaterialContract.java` (new)
+- `src/main/java/org/xpfarm/electricfurnace/item/MetalClassifier.java` (new)
+- `src/main/java/org/xpfarm/electricfurnace/item/AlloyItemFactory.java` (new)
+- `src/main/java/org/xpfarm/electricfurnace/item/MachineItemFactory.java` (new)
+- `src/test/java/org/xpfarm/electricfurnace/item/MetalClassifierTest.java` (new)
+
+No files outside Task 3's list were created or modified.
+
+## Build verification
+
+Command: `mvn --batch-mode --no-transfer-progress clean verify`
+
+Result: **BUILD SUCCESS**. 62 tests total, 0 failures, 0 errors, 0 skipped:
+
+```
+Running org.xpfarm.electricfurnace.recycle.RecycleResolverTest    -> 20 tests, all pass
+Running org.xpfarm.electricfurnace.item.MetalClassifierTest       -> 15 tests, all pass (new)
+Running org.xpfarm.electricfurnace.config.ConfigValidatorTest     -> 20 tests, all pass
+Running org.xpfarm.electricfurnace.alloy.AlloyRegistryTest        ->  7 tests, all pass
+Tests run: 62, Failures: 0, Errors: 0, Skipped: 0
+BUILD SUCCESS
+```
+
+Shaded JAR built successfully at `target/electric-furnace-0.1.0.jar`.
+
+Environment: sourced `~/.sdkman/bin/sdkman-init.sh` for Java 25.0.3-tem / Maven
+3.9.16, since `java`/`mvn` are not on PATH by default.
+
+## Key investigation before writing code
+
+Verified empirically (compiled and ran a throwaway `ItemStack` construction against
+the resolved `paper-api-26.1.2.build.74-stable.jar` with no server bootstrapped) that
+**no `ItemStack` can be constructed at all outside a live server** — `new
+ItemStack(Material.IRON_INGOT)` throws `IllegalStateException: No RegistryAccess
+implementation found` at construction time, before any meta/PDC access is even
+attempted. This confirms `MetalClassifierTest` genuinely cannot touch `ItemStack`
+directly (matching the plan's own instruction to keep the mapping lookup testable
+independently of ItemStack/ItemMeta construction), and is why the accept-damaged
+tests are written against the primitive-parameter core rather than real items. No
+new test dependency (Mockito/MockBukkit) was added to reach this coverage — the
+existing `RecyclingSettings`/`Material`/`RecycleInput` primitives were sufficient
+once the classifier was structured with a pure core.
+
+Also discovered the resolved Paper API jar is a newer build that already ships
+native vanilla copper tools/armor/weapons (`COPPER_SWORD`, `COPPER_HELMET`, etc.,
+plus a new `SPEAR` weapon variant for all four metals) and `COPPER_NUGGET`. Task 3's
+text says "iron/gold/copper/netherite/chainmail tools, weapons, and armor," so these
+were included in the static table for symmetry across all four metals. Excluded
+from the table by design judgment (not explicitly requested, kept the mapping
+bounded and parallel across metals): horse armor and "nautilus armor" variants,
+which exist for all four metals in this Paper build but are not standard player
+equipment slots.
+
+## Deviations from the plan
+
+- No literal deviation from file list, key names, or constructors. One judgment call
+  documented above (SPEAR included, horse/nautilus armor excluded from the gear
+  table) since the plan names the gear categories generically ("tools, weapons, and
+  armor") without an exhaustive material list.
+- Ingot-equivalent value: the plan only specifies nuggets must be < 1 (rejected).
+  All other recognized materials (ingots, raw ore, gear) were assigned a flat
+  `ingotValue = 1`, since `RecycleInput.ingotValue()` is documented as
+  display/downstream-only and not consulted by `RecycleResolver`. Modifiers (coal/
+  charcoal) get `ingotValue = 0`.
+- Damaged-gear-when-disabled behavior (classify to `Optional.empty()`, i.e. rejected
+  outright) was not explicitly specified by the plan beyond "test the accept-damaged
+  = false path if you implement one" — this is the implementation choice made and
+  is exactly what's tested.
+
+## Dependencies
+
+**No dependencies were added to `pom.xml`.** All classes and tests build against the
+existing `paper-api` (provided) and `junit-jupiter` (test) dependencies already
+present.
+
+## Concerns
+
+- `AlloyItemFactory` and `MachineItemFactory` have no dedicated unit tests, per the
+  plan's file list (only `MetalClassifierTest` is listed for Task 3) — they cannot be
+  unit tested without a live server for the same `RegistryAccess` reason documented
+  above (item construction itself fails). Their correctness (PDC stamping, no custom
+  model data, Adventure Component usage) was verified by careful reading against the
+  decompiled Paper API surface, and should be exercised in gate 7a runtime
+  verification once the plugin is wireable end-to-end (Tasks 4–6).
+- The exhaustive metal-table test hardcodes an expected count (51 entries: 16 iron +
+  12 gold + 12 copper + 11 netherite) as a guard against silent drift; if a future
+  task extends the table, that count must be updated deliberately alongside it.
diff --git a/src/main/java/org/xpfarm/electricfurnace/item/AlloyItemFactory.java b/src/main/java/org/xpfarm/electricfurnace/item/AlloyItemFactory.java
new file mode 100644
index 0000000..3fb7d0c
--- /dev/null
+++ b/src/main/java/org/xpfarm/electricfurnace/item/AlloyItemFactory.java
@@ -0,0 +1,71 @@
+/*
+ * ElectricFurnace - a redstone-powered smelter that recycles metal gear into ingots.
+ * Copyright (C) 2026 Carmelo Santana
+ *
+ * This program is free software: you can redistribute it and/or modify it under the
+ * terms of the GNU Affero General Public License as published by the Free Software
+ * Foundation, either version 3 of the License, or (at your option) any later version.
+ * See the LICENSE file at the project root for the full license text.
+ */
+package org.xpfarm.electricfurnace.item;
+
+import net.kyori.adventure.text.Component;
+import net.kyori.adventure.text.format.TextColor;
+import net.kyori.adventure.text.format.TextDecoration;
+import org.bukkit.Material;
+import org.bukkit.inventory.ItemStack;
+import org.bukkit.inventory.meta.ItemMeta;
+import org.bukkit.persistence.PersistentDataType;
+import org.xpfarm.electricfurnace.alloy.AlloyDefinition;
+
+import java.util.List;
+import java.util.Objects;
+
+/**
+ * Mints alloy ingot {@code ItemStack}s from an {@link AlloyDefinition}.
+ *
+ * <p>Base material is always {@link Material#NETHERITE_INGOT} -- <b>never</b>
+ * {@code setCustomModelData} or the {@code item_model} component, which are invisible
+ * to Bedrock clients without an authored Bedrock resource pack. Visual distinction
+ * between alloys comes entirely from name/lore color, not from the model.
+ *
+ * <p>Every minted item is stamped with both {@code xpfarm:} keys from
+ * {@link MaterialContract} so any plugin sharing that contract -- including this one's
+ * own {@link MetalClassifier} -- can recognize it purely by PDC.
+ */
+public final class AlloyItemFactory {
+
+    private AlloyItemFactory() {
+    }
+
+    /** Builds one alloy ingot {@code ItemStack} for {@code definition}. */
+    public static ItemStack create(AlloyDefinition definition) {
+        Objects.requireNonNull(definition, "definition");
+
+        ItemStack stack = new ItemStack(Material.NETHERITE_INGOT);
+        ItemMeta meta = stack.getItemMeta();
+
+        Component name = Component.text(definition.displayName())
+                .color(parseColor(definition.color()))
+                .decoration(TextDecoration.ITALIC, false);
+        meta.displayName(name);
+
+        List<Component> lore = definition.lore().stream()
+                .<Component>map(line -> Component.text(line).decoration(TextDecoration.ITALIC, false))
+                .toList();
+        meta.lore(lore);
+
+        meta.getPersistentDataContainer().set(MaterialContract.CUSTOM_MATERIAL, PersistentDataType.STRING,
+                MaterialContract.OWNING_SYSTEM_ELECTRICFURNACE);
+        meta.getPersistentDataContainer().set(MaterialContract.MATERIAL_ID, PersistentDataType.STRING,
+                definition.id());
+
+        stack.setItemMeta(meta);
+        return stack;
+    }
+
+    private static TextColor parseColor(String hex) {
+        TextColor color = TextColor.fromHexString(hex);
+        return color != null ? color : TextColor.fromHexString("#FFFFFF");
+    }
+}
diff --git a/src/main/java/org/xpfarm/electricfurnace/item/MachineItemFactory.java b/src/main/java/org/xpfarm/electricfurnace/item/MachineItemFactory.java
new file mode 100644
index 0000000..ddb3e9e
--- /dev/null
+++ b/src/main/java/org/xpfarm/electricfurnace/item/MachineItemFactory.java
@@ -0,0 +1,61 @@
+/*
+ * ElectricFurnace - a redstone-powered smelter that recycles metal gear into ingots.
+ * Copyright (C) 2026 Carmelo Santana
+ *
+ * This program is free software: you can redistribute it and/or modify it under the
+ * terms of the GNU Affero General Public License as published by the Free Software
+ * Foundation, either version 3 of the License, or (at your option) any later version.
+ * See the LICENSE file at the project root for the full license text.
+ */
+package org.xpfarm.electricfurnace.item;
+
+import net.kyori.adventure.text.Component;
+import net.kyori.adventure.text.format.NamedTextColor;
+import net.kyori.adventure.text.format.TextDecoration;
+import org.bukkit.Material;
+import org.bukkit.inventory.ItemStack;
+import org.bukkit.inventory.meta.ItemMeta;
+import org.bukkit.persistence.PersistentDataType;
+
+import java.util.List;
+
+/**
+ * Mints the Electric Furnace machine item: base {@link Material#BLAST_FURNACE}, named
+ * "Electric Furnace", stamped with {@link MaterialContract#MACHINE} so
+ * {@code MachineBlockListener} (Task 5) can recognize a placed block's origin purely
+ * by PDC -- never by display name or lore.
+ *
+ * <p><b>No custom model data, no {@code item_model} component.</b> The base material
+ * itself communicates the item on Bedrock without an authored resource pack.
+ */
+public final class MachineItemFactory {
+
+    private static final Component DISPLAY_NAME = Component.text("Electric Furnace")
+            .color(NamedTextColor.YELLOW)
+            .decoration(TextDecoration.ITALIC, false);
+
+    private static final List<Component> LORE = List.of(
+            Component.text("Recycles metal gear into ingots").color(NamedTextColor.GRAY)
+                    .decoration(TextDecoration.ITALIC, false),
+            Component.text("and fuses mixed metals into alloys.").color(NamedTextColor.GRAY)
+                    .decoration(TextDecoration.ITALIC, false),
+            Component.text("Requires a redstone signal to run.").color(NamedTextColor.DARK_GRAY)
+                    .decoration(TextDecoration.ITALIC, false));
+
+    private MachineItemFactory() {
+    }
+
+    /** Builds one Electric Furnace machine {@code ItemStack}. */
+    public static ItemStack create() {
+        ItemStack stack = new ItemStack(Material.BLAST_FURNACE);
+        ItemMeta meta = stack.getItemMeta();
+
+        meta.displayName(DISPLAY_NAME);
+        meta.lore(LORE);
+        meta.getPersistentDataContainer().set(MaterialContract.MACHINE, PersistentDataType.BYTE,
+                MaterialContract.MACHINE_MARKER);
+
+        stack.setItemMeta(meta);
+        return stack;
+    }
+}
diff --git a/src/main/java/org/xpfarm/electricfurnace/item/MaterialContract.java b/src/main/java/org/xpfarm/electricfurnace/item/MaterialContract.java
new file mode 100644
index 0000000..b58a91a
--- /dev/null
+++ b/src/main/java/org/xpfarm/electricfurnace/item/MaterialContract.java
@@ -0,0 +1,94 @@
+/*
+ * ElectricFurnace - a redstone-powered smelter that recycles metal gear into ingots.
+ * Copyright (C) 2026 Carmelo Santana
+ *
+ * This program is free software: you can redistribute it and/or modify it under the
+ * terms of the GNU Affero General Public License as published by the Free Software
+ * Foundation, either version 3 of the License, or (at your option) any later version.
+ * See the LICENSE file at the project root for the full license text.
+ */
+package org.xpfarm.electricfurnace.item;
+
+import org.bukkit.NamespacedKey;
+import org.bukkit.inventory.ItemStack;
+import org.bukkit.persistence.PersistentDataType;
+
+import java.util.Objects;
+import java.util.Optional;
+
+/**
+ * The shared cross-plugin {@code PersistentDataContainer} namespace.
+ *
+ * <p>Every key here is built with the {@link NamespacedKey#NamespacedKey(String, String)}
+ * <b>string</b> constructor, deliberately -- it needs no plugin instance, which is
+ * exactly what lets other plugins on the same server read (and, for their own
+ * namespace, write) compatible items without a jar dependency in either direction.
+ * Do not switch these to the {@code NamespacedKey(Plugin, String)} constructor.
+ *
+ * <p>Items are identified by these keys ONLY -- never by display name or lore
+ * substring matching, per the plugin's Bedrock/Geyser safety rules.
+ *
+ * <p>This class also exposes read-only helpers for CopperKingdom's own keys so the
+ * recycler can recognize copper gear minted by that sibling plugin. Those keys belong
+ * to CopperKingdom's namespace: this class never writes to them.
+ */
+public final class MaterialContract {
+
+    /** Owning-system marker used by {@link #CUSTOM_MATERIAL}, e.g. {@code "electricfurnace"}. */
+    public static final String OWNING_SYSTEM_ELECTRICFURNACE = "electricfurnace";
+
+    /** STRING: the owning system that minted this item, e.g. {@code "electricfurnace"}. */
+    public static final NamespacedKey CUSTOM_MATERIAL = new NamespacedKey("xpfarm", "custom_material");
+
+    /** STRING: the specific material id within the owning system, e.g. {@code "steel"}. */
+    public static final NamespacedKey MATERIAL_ID = new NamespacedKey("xpfarm", "material_id");
+
+    /** BYTE: marks an item as the Electric Furnace machine item. */
+    public static final NamespacedKey MACHINE = new NamespacedKey("electricfurnace", "machine");
+
+    /** The byte value stamped under {@link #MACHINE} to mark a machine item. */
+    public static final byte MACHINE_MARKER = 1;
+
+    // ---- CopperKingdom's namespace -- read only, never written to here ----------------
+
+    /** STRING (foreign, CopperKingdom): marks an item as CopperKingdom copper armor. */
+    public static final NamespacedKey COPPERKINGDOM_COPPER_ARMOR = new NamespacedKey("copperkingdom", "copper_armor");
+
+    /** STRING (foreign, CopperKingdom): marks an item as a CopperKingdom copper weapon. */
+    public static final NamespacedKey COPPERKINGDOM_COPPER_WEAPON = new NamespacedKey("copperkingdom", "copper_weapon");
+
+    private MaterialContract() {
+    }
+
+    /** Reads {@link #CUSTOM_MATERIAL} off {@code stack}, if present. */
+    public static Optional<String> readCustomMaterial(ItemStack stack) {
+        return readString(stack, CUSTOM_MATERIAL);
+    }
+
+    /** Reads {@link #MATERIAL_ID} off {@code stack}, if present. */
+    public static Optional<String> readMaterialId(ItemStack stack) {
+        return readString(stack, MATERIAL_ID);
+    }
+
+    /** Whether {@code stack} carries the {@link #MACHINE} marker. */
+    public static boolean isMachine(ItemStack stack) {
+        Objects.requireNonNull(stack, "stack");
+        Byte value = stack.getPersistentDataContainer().get(MACHINE, PersistentDataType.BYTE);
+        return value != null && value == MACHINE_MARKER;
+    }
+
+    /** Read-only: whether {@code stack} carries CopperKingdom's copper-armor marker. */
+    public static boolean isCopperKingdomCopperArmor(ItemStack stack) {
+        return readString(stack, COPPERKINGDOM_COPPER_ARMOR).isPresent();
+    }
+
+    /** Read-only: whether {@code stack} carries CopperKingdom's copper-weapon marker. */
+    public static boolean isCopperKingdomCopperWeapon(ItemStack stack) {
+        return readString(stack, COPPERKINGDOM_COPPER_WEAPON).isPresent();
+    }
+
+    private static Optional<String> readString(ItemStack stack, NamespacedKey key) {
+        Objects.requireNonNull(stack, "stack");
+        return Optional.ofNullable(stack.getPersistentDataContainer().get(key, PersistentDataType.STRING));
+    }
+}
diff --git a/src/main/java/org/xpfarm/electricfurnace/item/MetalClassifier.java b/src/main/java/org/xpfarm/electricfurnace/item/MetalClassifier.java
new file mode 100644
index 0000000..f8b163c
--- /dev/null
+++ b/src/main/java/org/xpfarm/electricfurnace/item/MetalClassifier.java
@@ -0,0 +1,210 @@
+/*
+ * ElectricFurnace - a redstone-powered smelter that recycles metal gear into ingots.
+ * Copyright (C) 2026 Carmelo Santana
+ *
+ * This program is free software: you can redistribute it and/or modify it under the
+ * terms of the GNU Affero General Public License as published by the Free Software
+ * Foundation, either version 3 of the License, or (at your option) any later version.
+ * See the LICENSE file at the project root for the full license text.
+ */
+package org.xpfarm.electricfurnace.item;
+
+import org.bukkit.Material;
+import org.bukkit.inventory.ItemStack;
+import org.bukkit.inventory.meta.Damageable;
+import org.bukkit.inventory.meta.ItemMeta;
+import org.xpfarm.electricfurnace.alloy.MetalType;
+import org.xpfarm.electricfurnace.config.RecyclingSettings;
+import org.xpfarm.electricfurnace.recycle.RecycleInput;
+
+import java.util.EnumMap;
+import java.util.Locale;
+import java.util.Map;
+import java.util.Objects;
+import java.util.Optional;
+import java.util.Set;
+
+/**
+ * The bridge from Bukkit {@link Material}/{@link ItemStack} to Task 2's pure recycle
+ * model: maps a material to an {@link Optional} {@link MetalType}, identifies
+ * modifiers (coal, charcoal), recognizes alloy items by PDC, and turns all of that
+ * into a {@link RecycleInput} the resolver can consume.
+ *
+ * <p><b>Two layers, deliberately split.</b> {@link #METAL_TABLE}, {@link #metalOf},
+ * {@link #isModifier}, and the primitive overload of {@link #classify} touch nothing
+ * but {@code Material} and plain values -- they are exhaustively unit-testable with
+ * no running server. The {@link #classify(ItemStack, RecyclingSettings)} overload is
+ * the thin Bukkit-facing glue that reads a real item's type, PDC, and damage state and
+ * delegates to the pure core; constructing a real {@code ItemStack} requires a live
+ * server (Paper resolves item types through {@code RegistryAccess}), so that overload
+ * is exercised only at runtime, not by {@code MetalClassifierTest}.
+ *
+ * <p><b>Accept-damaged.</b> {@link RecycleInput} has no damage field: durability is
+ * never scaled into the yield, and damaged/undamaged gear of the same type must
+ * classify identically when {@code recycling.accept-damaged} is {@code true}. When it
+ * is {@code false}, damaged gear is rejected outright (classifies to
+ * {@link Optional#empty()}) rather than being partially accepted -- pristine gear of
+ * the same type still classifies normally. This gate does not apply to alloy items:
+ * remelting is not a "damaged gear" concept.
+ */
+public final class MetalClassifier {
+
+    /**
+     * Exhaustive {@code Material -> MetalType} table for every recognized ingot, raw
+     * ore, and gear (tools, weapons, armor) material. Chainmail counts as
+     * {@link MetalType#IRON}. Package-private so {@code MetalClassifierTest} can
+     * assert it exhaustively.
+     */
+    static final Map<Material, MetalType> METAL_TABLE = buildMetalTable();
+
+    /** Materials treated as modifiers -- never metals, never recyclable alone. */
+    static final Set<Material> MODIFIER_MATERIALS = Set.of(Material.COAL, Material.CHARCOAL);
+
+    /** The shared named-alloy-matching id for every modifier material (see class docs). */
+    private static final String MODIFIER_ID = "coal";
+
+    /**
+     * Nuggets: their ingot-equivalent value is below 1 (9 nuggets = 1 ingot), so they
+     * are treated as non-recyclable and rejected outright rather than assigned a
+     * fractional or rounded value.
+     */
+    private static final Set<Material> SUB_INGOT_MATERIALS =
+            Set.of(Material.IRON_NUGGET, Material.GOLD_NUGGET, Material.COPPER_NUGGET);
+
+    /** Ingot-equivalent value assigned to every recognized metal/gear/alloy input. */
+    private static final int STANDARD_INGOT_VALUE = 1;
+
+    private MetalClassifier() {
+    }
+
+    private static Map<Material, MetalType> buildMetalTable() {
+        Map<Material, MetalType> table = new EnumMap<>(Material.class);
+        putAll(table, MetalType.IRON,
+                Material.IRON_INGOT, Material.RAW_IRON,
+                Material.IRON_SWORD, Material.IRON_SPEAR, Material.IRON_PICKAXE, Material.IRON_AXE,
+                Material.IRON_SHOVEL, Material.IRON_HOE,
+                Material.IRON_HELMET, Material.IRON_CHESTPLATE, Material.IRON_LEGGINGS, Material.IRON_BOOTS,
+                Material.CHAINMAIL_HELMET, Material.CHAINMAIL_CHESTPLATE,
+                Material.CHAINMAIL_LEGGINGS, Material.CHAINMAIL_BOOTS);
+        putAll(table, MetalType.GOLD,
+                Material.GOLD_INGOT, Material.RAW_GOLD,
+                Material.GOLDEN_SWORD, Material.GOLDEN_SPEAR, Material.GOLDEN_PICKAXE, Material.GOLDEN_AXE,
+                Material.GOLDEN_SHOVEL, Material.GOLDEN_HOE,
+                Material.GOLDEN_HELMET, Material.GOLDEN_CHESTPLATE, Material.GOLDEN_LEGGINGS, Material.GOLDEN_BOOTS);
+        putAll(table, MetalType.COPPER,
+                Material.COPPER_INGOT, Material.RAW_COPPER,
+                Material.COPPER_SWORD, Material.COPPER_SPEAR, Material.COPPER_PICKAXE, Material.COPPER_AXE,
+                Material.COPPER_SHOVEL, Material.COPPER_HOE,
+                Material.COPPER_HELMET, Material.COPPER_CHESTPLATE, Material.COPPER_LEGGINGS, Material.COPPER_BOOTS);
+        putAll(table, MetalType.NETHERITE,
+                Material.NETHERITE_INGOT,
+                Material.NETHERITE_SWORD, Material.NETHERITE_SPEAR, Material.NETHERITE_PICKAXE, Material.NETHERITE_AXE,
+                Material.NETHERITE_SHOVEL, Material.NETHERITE_HOE,
+                Material.NETHERITE_HELMET, Material.NETHERITE_CHESTPLATE, Material.NETHERITE_LEGGINGS,
+                Material.NETHERITE_BOOTS);
+        return Map.copyOf(table);
+    }
+
+    private static void putAll(Map<Material, MetalType> table, MetalType metal, Material... materials) {
+        for (Material material : materials) {
+            table.put(material, metal);
+        }
+    }
+
+    /** The metal this material counts as, or {@link Optional#empty()} if it is not one. */
+    public static Optional<MetalType> metalOf(Material material) {
+        return Optional.ofNullable(METAL_TABLE.get(material));
+    }
+
+    /** Whether this material is a modifier (coal/charcoal) rather than a metal. */
+    public static boolean isModifier(Material material) {
+        return MODIFIER_MATERIALS.contains(material);
+    }
+
+    /**
+     * The pure classification core: given already-extracted primitives, decides the
+     * {@link RecycleInput} this item represents. No {@code org.bukkit} type beyond
+     * {@link Material} appears here, and no live server is required to call it.
+     *
+     * @param material       the item's material
+     * @param materialId     a stable id for this material, used verbatim as the
+     *                       modifier id for named-alloy matching; ignored for metals
+     *                       (whose id is derived from {@code metal}) and for alloys
+     * @param damaged        whether the underlying item currently has any durability
+     *                       damage; ignored entirely for alloy items
+     * @param isAlloyStamped whether the item carries the {@code xpfarm:custom_material}
+     *                       PDC marker (i.e. is itself a minted alloy item)
+     * @param alloyId        the alloy id read from {@code xpfarm:material_id} if
+     *                       {@code isAlloyStamped}, otherwise ignored
+     * @param settings       validated {@code recycling} config; only
+     *                       {@link RecyclingSettings#acceptDamaged()} is consulted here
+     * @return the classified input, or {@link Optional#empty()} if this item cannot be
+     *         used as a recycler input at all (unrecognized material, a sub-ingot
+     *         nugget, or damaged gear while {@code accept-damaged} is disabled)
+     */
+    public static Optional<RecycleInput> classify(Material material, String materialId, boolean damaged,
+            boolean isAlloyStamped, String alloyId, RecyclingSettings settings) {
+        Objects.requireNonNull(material, "material");
+        Objects.requireNonNull(settings, "settings");
+
+        if (isAlloyStamped) {
+            return Optional.of(new RecycleInput(materialId, null, false, true, alloyId, STANDARD_INGOT_VALUE));
+        }
+
+        if (damaged && !settings.acceptDamaged()) {
+            return Optional.empty();
+        }
+
+        if (SUB_INGOT_MATERIALS.contains(material)) {
+            return Optional.empty();
+        }
+
+        if (isModifier(material)) {
+            return Optional.of(new RecycleInput(MODIFIER_ID, null, true, false, null, 0));
+        }
+
+        Optional<MetalType> metal = metalOf(material);
+        if (metal.isEmpty()) {
+            return Optional.empty();
+        }
+
+        return Optional.of(new RecycleInput(materialId, metal.get(), false, false, null, STANDARD_INGOT_VALUE));
+    }
+
+    /**
+     * Classifies a real {@code ItemStack} into a {@link RecycleInput}, or
+     * {@link Optional#empty()} if it cannot be used as a recycler input at all.
+     *
+     * <p>Reads, in order: the {@code xpfarm:} alloy-stamp PDC keys (an item stamped by
+     * this plugin or another minting alloys under the same contract), the material
+     * table above, CopperKingdom's foreign copper-armor/copper-weapon PDC keys as a
+     * fallback recognition path for that sibling plugin's own copper gear, and
+     * finally durability via {@link Damageable} if the item's meta supports it.
+     */
+    public static Optional<RecycleInput> classify(ItemStack stack, RecyclingSettings settings) {
+        Objects.requireNonNull(stack, "stack");
+        Objects.requireNonNull(settings, "settings");
+
+        Material material = stack.getType();
+        String materialId = material.name().toLowerCase(Locale.ROOT);
+        boolean isAlloyStamped = MaterialContract.readCustomMaterial(stack).isPresent();
+        String alloyId = MaterialContract.readMaterialId(stack).orElse(null);
+        boolean damaged = readDamaged(stack);
+
+        if (!isAlloyStamped && metalOf(material).isEmpty() && !isModifier(material)
+                && (MaterialContract.isCopperKingdomCopperArmor(stack)
+                    || MaterialContract.isCopperKingdomCopperWeapon(stack))) {
+            if (damaged && !settings.acceptDamaged()) {
+                return Optional.empty();
+            }
+            return Optional.of(new RecycleInput(materialId, MetalType.COPPER, false, false, null, STANDARD_INGOT_VALUE));
+        }
+
+        return classify(material, materialId, damaged, isAlloyStamped, alloyId, settings);
+    }
+
+    private static boolean readDamaged(ItemStack stack) {
+        ItemMeta meta = stack.getItemMeta();
+        return meta instanceof Damageable damageable && damageable.hasDamage();
+    }
+}
diff --git a/src/test/java/org/xpfarm/electricfurnace/item/MetalClassifierTest.java b/src/test/java/org/xpfarm/electricfurnace/item/MetalClassifierTest.java
new file mode 100644
index 0000000..35be702
--- /dev/null
+++ b/src/test/java/org/xpfarm/electricfurnace/item/MetalClassifierTest.java
@@ -0,0 +1,214 @@
+/*
+ * ElectricFurnace - a redstone-powered smelter that recycles metal gear into ingots.
+ * Copyright (C) 2026 Carmelo Santana
+ *
+ * This program is free software: you can redistribute it and/or modify it under the
+ * terms of the GNU Affero General Public License as published by the Free Software
+ * Foundation, either version 3 of the License, or (at your option) any later version.
+ * See the LICENSE file at the project root for the full license text.
+ */
+package org.xpfarm.electricfurnace.item;
+
+import org.bukkit.Material;
+import org.junit.jupiter.api.Test;
+import org.xpfarm.electricfurnace.alloy.MetalType;
+import org.xpfarm.electricfurnace.config.RecyclingSettings;
+import org.xpfarm.electricfurnace.recycle.RecycleInput;
+
+import java.util.List;
+import java.util.Optional;
+
+import static org.junit.jupiter.api.Assertions.assertEquals;
+import static org.junit.jupiter.api.Assertions.assertFalse;
+import static org.junit.jupiter.api.Assertions.assertNull;
+import static org.junit.jupiter.api.Assertions.assertTrue;
+
+/**
+ * Unit tests for {@link MetalClassifier}.
+ *
+ * <p>Everything here exercises the pure, {@code Material}/primitive-based surface of
+ * the classifier -- {@link MetalClassifier#metalOf}, {@link MetalClassifier#isModifier},
+ * and the primitive overload of {@link MetalClassifier#classify}. None of it
+ * constructs a real {@code ItemStack}: doing so requires a live server (Paper's
+ * {@code ItemStack} construction resolves item types through {@code RegistryAccess},
+ * which throws {@code IllegalStateException} with no server bootstrapped), which is
+ * exactly why the classifier is split this way -- the mapping table and the
+ * accept-damaged decision are fully testable here, and the {@code ItemStack}-facing
+ * overload is exercised only at runtime (gate 7a), not in this suite.
+ */
+class MetalClassifierTest {
+
+    private static final RecyclingSettings ACCEPT_DAMAGED =
+            new RecyclingSettings(5, 3, 2, 1, true);
+    private static final RecyclingSettings REJECT_DAMAGED =
+            new RecyclingSettings(5, 3, 2, 1, false);
+
+    // ---- Exhaustive Material -> MetalType mapping, asserted without a running server ----
+
+    @Test
+    void ironFamily_mapsToIron() {
+        for (Material material : List.of(
+                Material.IRON_INGOT, Material.RAW_IRON,
+                Material.IRON_SWORD, Material.IRON_SPEAR, Material.IRON_PICKAXE, Material.IRON_AXE,
+                Material.IRON_SHOVEL, Material.IRON_HOE,
+                Material.IRON_HELMET, Material.IRON_CHESTPLATE, Material.IRON_LEGGINGS, Material.IRON_BOOTS)) {
+            assertEquals(Optional.of(MetalType.IRON), MetalClassifier.metalOf(material), material.name());
+        }
+    }
+
+    @Test
+    void chainmail_mapsToIron() {
+        for (Material material : List.of(
+                Material.CHAINMAIL_HELMET, Material.CHAINMAIL_CHESTPLATE,
+                Material.CHAINMAIL_LEGGINGS, Material.CHAINMAIL_BOOTS)) {
+            assertEquals(Optional.of(MetalType.IRON), MetalClassifier.metalOf(material), material.name());
+        }
+    }
+
+    @Test
+    void goldFamily_mapsToGold() {
+        for (Material material : List.of(
+                Material.GOLD_INGOT, Material.RAW_GOLD,
+                Material.GOLDEN_SWORD, Material.GOLDEN_SPEAR, Material.GOLDEN_PICKAXE, Material.GOLDEN_AXE,
+                Material.GOLDEN_SHOVEL, Material.GOLDEN_HOE,
+                Material.GOLDEN_HELMET, Material.GOLDEN_CHESTPLATE, Material.GOLDEN_LEGGINGS, Material.GOLDEN_BOOTS)) {
+            assertEquals(Optional.of(MetalType.GOLD), MetalClassifier.metalOf(material), material.name());
+        }
+    }
+
+    @Test
+    void copperFamily_mapsToCopper() {
+        for (Material material : List.of(
+                Material.COPPER_INGOT, Material.RAW_COPPER,
+                Material.COPPER_SWORD, Material.COPPER_SPEAR, Material.COPPER_PICKAXE, Material.COPPER_AXE,
+                Material.COPPER_SHOVEL, Material.COPPER_HOE,
+                Material.COPPER_HELMET, Material.COPPER_CHESTPLATE, Material.COPPER_LEGGINGS, Material.COPPER_BOOTS)) {
+            assertEquals(Optional.of(MetalType.COPPER), MetalClassifier.metalOf(material), material.name());
+        }
+    }
+
+    @Test
+    void netheriteFamily_mapsToNetherite() {
+        for (Material material : List.of(
+                Material.NETHERITE_INGOT,
+                Material.NETHERITE_SWORD, Material.NETHERITE_SPEAR, Material.NETHERITE_PICKAXE, Material.NETHERITE_AXE,
+                Material.NETHERITE_SHOVEL, Material.NETHERITE_HOE,
+                Material.NETHERITE_HELMET, Material.NETHERITE_CHESTPLATE, Material.NETHERITE_LEGGINGS,
+                Material.NETHERITE_BOOTS)) {
+            assertEquals(Optional.of(MetalType.NETHERITE), MetalClassifier.metalOf(material), material.name());
+        }
+    }
+
+    @Test
+    void metalTable_hasExactlyTheExpectedEntries_noMoreNoLess() {
+        // 16 iron-family (incl. 4 chainmail) + 12 gold + 12 copper + 11 netherite.
+        assertEquals(16 + 12 + 12 + 11, MetalClassifier.METAL_TABLE.size());
+    }
+
+    // ---- Coal and charcoal are modifiers, not metals ---------------------------------
+
+    @Test
+    void coalAndCharcoal_areModifiers() {
+        assertTrue(MetalClassifier.isModifier(Material.COAL));
+        assertTrue(MetalClassifier.isModifier(Material.CHARCOAL));
+        assertTrue(MetalClassifier.metalOf(Material.COAL).isEmpty());
+        assertTrue(MetalClassifier.metalOf(Material.CHARCOAL).isEmpty());
+    }
+
+    @Test
+    void coalAndCharcoal_classifyAsModifier_sharingTheSameNamedAlloyId() {
+        Optional<RecycleInput> coal =
+                MetalClassifier.classify(Material.COAL, "coal", false, false, null, ACCEPT_DAMAGED);
+        Optional<RecycleInput> charcoal =
+                MetalClassifier.classify(Material.CHARCOAL, "charcoal", false, false, null, ACCEPT_DAMAGED);
+
+        assertTrue(coal.isPresent());
+        assertTrue(coal.get().isModifier());
+        assertNull(coal.get().metal());
+        assertTrue(charcoal.isPresent());
+        assertTrue(charcoal.get().isModifier());
+        assertEquals("coal", coal.get().materialId());
+        assertEquals("coal", charcoal.get().materialId(),
+                "charcoal must share coal's id so it also matches recipes like Steel");
+    }
+
+    @Test
+    void nonMetalMaterials_areNotModifiers() {
+        assertFalse(MetalClassifier.isModifier(Material.IRON_INGOT));
+        assertFalse(MetalClassifier.isModifier(Material.DIRT));
+    }
+
+    // ---- Unrecognized items classify to empty -----------------------------------------
+
+    @Test
+    void dirtAndStick_classifyAsEmpty() {
+        assertTrue(MetalClassifier.metalOf(Material.DIRT).isEmpty());
+        assertTrue(MetalClassifier.metalOf(Material.STICK).isEmpty());
+        assertTrue(MetalClassifier.classify(Material.DIRT, "dirt", false, false, null, ACCEPT_DAMAGED).isEmpty());
+        assertTrue(MetalClassifier.classify(Material.STICK, "stick", false, false, null, ACCEPT_DAMAGED).isEmpty());
+    }
+
+    // ---- Nuggets have ingot value < 1 and are rejected outright -----------------------
+
+    @Test
+    void nuggets_areRejected() {
+        assertTrue(MetalClassifier.classify(Material.IRON_NUGGET, "iron_nugget", false, false, null, ACCEPT_DAMAGED)
+                .isEmpty());
+        assertTrue(MetalClassifier.classify(Material.GOLD_NUGGET, "gold_nugget", false, false, null, ACCEPT_DAMAGED)
+                .isEmpty());
+        assertTrue(MetalClassifier.classify(Material.COPPER_NUGGET, "copper_nugget", false, false, null, ACCEPT_DAMAGED)
+                .isEmpty());
+    }
+
+    // ---- An alloy-stamped stack classifies as isAlloy ---------------------------------
+
+    @Test
+    void alloyStampedInput_classifiesAsAlloy() {
+        Optional<RecycleInput> result =
+                MetalClassifier.classify(Material.NETHERITE_INGOT, "steel_ingot", false, true, "steel", ACCEPT_DAMAGED);
+
+        assertTrue(result.isPresent());
+        assertTrue(result.get().isAlloy());
+        assertEquals("steel", result.get().alloyId());
+        assertNull(result.get().metal());
+        assertFalse(result.get().isModifier());
+    }
+
+    // ---- Accept-damaged coverage, owed by Task 3 --------------------------------------
+    // RecycleInput has no damage field; the resolver cannot fail this on its own. This is
+    // where Bukkit ItemStack damage actually exists, so this is where it must be tested.
+
+    @Test
+    void damagedAndPristineGear_classifyIdentically_whenAcceptDamagedIsTrue() {
+        Optional<RecycleInput> pristine =
+                MetalClassifier.classify(Material.IRON_SWORD, "iron_sword", false, false, null, ACCEPT_DAMAGED);
+        Optional<RecycleInput> heavilyDamaged =
+                MetalClassifier.classify(Material.IRON_SWORD, "iron_sword", true, false, null, ACCEPT_DAMAGED);
+
+        assertTrue(pristine.isPresent());
+        assertEquals(pristine, heavilyDamaged,
+                "accept-damaged=true must yield identical RecycleInput regardless of durability");
+    }
+
+    @Test
+    void damagedGear_isRejected_whenAcceptDamagedIsFalse() {
+        Optional<RecycleInput> heavilyDamaged =
+                MetalClassifier.classify(Material.IRON_SWORD, "iron_sword", true, false, null, REJECT_DAMAGED);
+        Optional<RecycleInput> pristine =
+                MetalClassifier.classify(Material.IRON_SWORD, "iron_sword", false, false, null, REJECT_DAMAGED);
+
+        assertTrue(heavilyDamaged.isEmpty(), "accept-damaged=false must reject damaged gear outright");
+        assertTrue(pristine.isPresent(), "accept-damaged=false must still accept pristine gear");
+    }
+
+    @Test
+    void damageIsIgnored_forAlloyItems_evenWhenAcceptDamagedIsFalse() {
+        // An alloy remelt is not "gear" in the accept-damaged sense; alloy items are not
+        // subject to the accept-damaged gate at all.
+        Optional<RecycleInput> result =
+                MetalClassifier.classify(Material.NETHERITE_INGOT, "steel_ingot", true, true, "steel", REJECT_DAMAGED);
+
+        assertTrue(result.isPresent());
+        assertTrue(result.get().isAlloy());
+    }
+}
```
