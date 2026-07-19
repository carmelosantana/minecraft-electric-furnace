# Review package: 22b8a5e..d86db74

## Commits
d86db74 Task 1: configuration layer with pure validation contract

## Stat
 .superpowers/sdd/progress.md                       |  18 ++
 .superpowers/sdd/task-1-report.md                  | 132 ++++++++++++
 .../electricfurnace/config/ConfigValidator.java    | 158 ++++++++++++++
 .../xpfarm/electricfurnace/config/EfConfig.java    | 114 +++++++++++
 .../electricfurnace/config/EffectSettings.java     |  29 +++
 .../electricfurnace/config/MachineSettings.java    |  26 +++
 .../electricfurnace/config/RecyclingSettings.java  |  30 +++
 src/main/resources/config.yml                      |  85 ++++++++
 .../config/ConfigValidatorTest.java                | 227 +++++++++++++++++++++
 9 files changed, 819 insertions(+)

## Diff
```diff
diff --git a/.superpowers/sdd/progress.md b/.superpowers/sdd/progress.md
new file mode 100644
index 0000000..b33c84f
--- /dev/null
+++ b/.superpowers/sdd/progress.md
@@ -0,0 +1,18 @@
+# ElectricFurnace SDD Progress Ledger
+
+Plan: `docs/superpowers/plans/2026-07-19-electric-furnace-plan.md`
+Branch: `main` (xpfarm lifecycle convention; autonomy = autonomous)
+Scaffold base: `22b8a5e`
+
+## Tasks
+
+- [x] Task 1 — Configuration layer
+- [ ] Task 2 — Alloy model and pure recycle resolver
+- [ ] Task 3 — Item layer and cross-plugin PDC contract
+- [ ] Task 4 — Machine persistence
+- [ ] Task 5 — GUI and machine listeners
+- [ ] Task 6 — Effects, command, and plugin wiring
+
+## Minor findings roll-up (for final whole-branch review)
+
+_(none yet)_
diff --git a/.superpowers/sdd/task-1-report.md b/.superpowers/sdd/task-1-report.md
new file mode 100644
index 0000000..3d81cbd
--- /dev/null
+++ b/.superpowers/sdd/task-1-report.md
@@ -0,0 +1,132 @@
+# Task 1 — Configuration layer — Report
+
+## What was implemented
+
+The configuration layer for ElectricFurnace: typed, immutable config records, a
+pure/Bukkit-free validation helper, a loader that composes them from a Bukkit
+`ConfigurationSection`, and the shipping `config.yml` (including a Task-2
+placeholder `alloys:` section, not parsed by any code in this task).
+
+Followed TDD: `ConfigValidatorTest` was written first (and fails to compile
+without `ConfigValidator`, i.e. red), then `ConfigValidator` was implemented to
+make it pass (green).
+
+### Design notes / how the validation contract works
+
+- `ConfigValidator` is a pure, static, zero-`org.bukkit`-import class. It exposes:
+  - `clampInt` / `clampDouble` — given an *already-parsed* primitive, if it is
+    outside `[min, max]` it logs one warning naming the key, the offending value,
+    and the value it substitutes (the configured default — not the nearest
+    boundary), then returns that default. In-range values pass through
+    unchanged, silently.
+  - `parseInt` / `parseDouble` / `parseBoolean` — given the raw `Object` pulled
+    straight out of YAML (as `ConfigurationSection#get` returns it), these
+    additionally handle the "missing key" and "wrong type" cases: a `null` raw
+    value (key absent) falls back to the default *silently* (omitting a key is
+    not an error); a present-but-unparseable value (wrong type, e.g. a string
+    where a number is expected) warns and falls back, then delegates in-range
+    values to the corresponding `clampX` for range checking.
+  - All warnings go through a caller-supplied `Consumer<String>`, never a
+    logger directly — this is what lets `ConfigValidatorTest` run with zero
+    Bukkit types and no server.
+- `EfConfig.load(ConfigurationSection root, Consumer<String> warn)` reads every
+  key in the requirements table via dotted paths (Bukkit's default
+  `ConfigurationSection` path separator resolves `machine.smelt-speed-multiplier`
+  directly against the root section) and builds the three settings records.
+  `root == null` is tolerated (treated as an empty config — everything falls
+  back to defaults).
+- `effects.sound` is a special case per the spec ("unresolvable → warn, disable
+  sound only" — not "fall back to the default sound"). `EfConfig` resolves it by
+  reflectively looking up the named public static field on `org.bukkit.Sound`
+  (e.g. `Sound.BLOCK_BEACON_AMBIENT`), which is the exact, unambiguous encoding
+  of the Sound registry's Java-side names (a manual underscore→dot conversion
+  was investigated and rejected — decompiling `Sound.class` showed sound keys
+  like `ambient.basalt_deltas.additions` retain underscores *inside* segments,
+  so no blind substitution rule is correct). A missing key still silently
+  defaults to `BLOCK_BEACON_AMBIENT`; a present-but-unresolvable name warns and
+  sets `EffectSettings.sound` to `null` (effects stay enabled, particles are
+  unaffected, only the sound is skipped — left to Task 6 to interpret).
+  Note: touching `Sound.class` triggers Bukkit's registry-backed static
+  initializer, which requires a live server — this code path is therefore only
+  exercised at runtime, never during `mvn test`, and no test in this task
+  touches it (consistent with the plan, which only requires `ConfigValidatorTest`
+  to run headless).
+
+## Files
+
+- `src/main/java/org/xpfarm/electricfurnace/config/EfConfig.java` (new)
+- `src/main/java/org/xpfarm/electricfurnace/config/MachineSettings.java` (new)
+- `src/main/java/org/xpfarm/electricfurnace/config/EffectSettings.java` (new)
+- `src/main/java/org/xpfarm/electricfurnace/config/RecyclingSettings.java` (new)
+- `src/main/java/org/xpfarm/electricfurnace/config/ConfigValidator.java` (new)
+- `src/main/resources/config.yml` (new)
+- `src/test/java/org/xpfarm/electricfurnace/config/ConfigValidatorTest.java` (new)
+
+No files outside Task 1's list were created or modified.
+
+## Build/test command and actual output
+
+Command run (Java 25 / Maven 3.9.16 via SDKMAN — neither was on `PATH` by
+default in this environment, sourced `~/.sdkman/bin/sdkman-init.sh` first):
+
+```
+mvn --batch-mode --no-transfer-progress clean verify
+```
+
+Actual summary output:
+
+```
+[INFO] Compiling 5 source files with javac [debug release 25] to target/classes
+[INFO] Compiling 1 source file with javac [debug release 25] to target/test-classes
+[INFO] Running org.xpfarm.electricfurnace.config.ConfigValidatorTest
+[INFO] Tests run: 20, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.110 s -- in org.xpfarm.electricfurnace.config.ConfigValidatorTest
+[INFO] Results:
+[INFO] Tests run: 20, Failures: 0, Errors: 0, Skipped: 0
+[INFO] Building jar: .../target/electric-furnace-0.1.0.jar
+[INFO] Replacing original artifact with shaded artifact.
+[INFO] BUILD SUCCESS
+```
+
+20/20 `ConfigValidatorTest` cases pass: in-range passthrough (int + double, incl.
+boundary values), below-min clamp, above-max clamp, missing key (silent
+fallback, no warning) for int/double/boolean, wrong type (string, boolean-where-
+int-expected) for int/double/boolean, numeric-string parsing, boolean
+"true"/"FALSE" string parsing, and an explicit warning-message-contract test
+asserting the message names the key, the bad value, and the substituted
+default.
+
+`config.yml` was also independently validated as syntactically correct YAML
+(parsed with Python's `yaml.safe_load`, structure and values checked against the
+requirements table and Task 2's alloy table).
+
+## Deviations from the plan
+
+None. All five config file paths, record shapes, field names, defaults, and
+valid ranges match the plan's table verbatim. `effects.sound`'s "disable sound
+only" behavior (returning `null` rather than the default sound name on an
+unresolvable value) is implemented as specified, not as a deviation.
+
+No dependencies were added to `pom.xml` — `junit-jupiter` and `paper-api` were
+already present from the scaffold.
+
+## Concerns
+
+- This environment does not have `java`/`mvn` on `PATH` by default; both are
+  installed under SDKMAN (`~/.sdkman/candidates/java/25.0.3-tem`,
+  `~/.sdkman/candidates/maven/3.9.16`). Later tasks' verification runs will need
+  to source `~/.sdkman/bin/sdkman-init.sh` (or otherwise put SDKMAN's shims on
+  `PATH`) before invoking `mvn`.
+- `effects.sound` resolution (`EfConfig.resolveSound`) is untested by any
+  automated test in this task, because exercising it requires
+  `org.bukkit.Sound`'s registry-backed static initializer, which needs a live
+  Paper server and cannot run in a plain JVM unit test. This is consistent with
+  the plan (only `ConfigValidatorTest` is required to run headless) but is worth
+  flagging: it will only be exercised for the first time against a real server
+  in a later gate (runtime verification), not by `mvn verify`.
+- `EfConfig.load` itself has no dedicated unit test (it isn't required to be
+  Bukkit-free per the plan, and a real `ConfigurationSection` needs at least a
+  lightweight Bukkit config implementation to construct). If a future task wants
+  coverage here, `YamlConfiguration` from paper-api can be instantiated directly
+  in a JVM test without a running server (only `Sound` resolution needs one) —
+  left out here to stay strictly inside Task 1's file list, which does not
+  include an `EfConfigTest`.
diff --git a/src/main/java/org/xpfarm/electricfurnace/config/ConfigValidator.java b/src/main/java/org/xpfarm/electricfurnace/config/ConfigValidator.java
new file mode 100644
index 0000000..908e78d
--- /dev/null
+++ b/src/main/java/org/xpfarm/electricfurnace/config/ConfigValidator.java
@@ -0,0 +1,158 @@
+/*
+ * ElectricFurnace - a redstone-powered smelter that recycles metal gear into ingots.
+ * Copyright (C) 2026 Carmelo Santana
+ *
+ * This program is free software: you can redistribute it and/or modify it under the
+ * terms of the GNU Affero General Public License as published by the Free Software
+ * Foundation, either version 3 of the License, or (at your option) any later version.
+ * See the LICENSE file at the project root for the full license text.
+ */
+package org.xpfarm.electricfurnace.config;
+
+import java.util.function.Consumer;
+
+/**
+ * Pure, Bukkit-free validation and fallback logic for {@code config.yml} values.
+ *
+ * <p>Every method here operates on primitives (or raw {@link Object} values pulled
+ * straight from a YAML parser) and reports problems through a caller-supplied
+ * {@link Consumer} rather than logging directly. This is deliberate: it lets
+ * {@code ConfigValidatorTest} exercise the full validation contract with zero
+ * {@code org.bukkit} types and no running server.
+ *
+ * <p><b>Contract:</b> a value that is missing is not an error -- it silently falls
+ * back to the default. A value that is present but out of range or unparseable
+ * <em>is</em> an error: it logs exactly one warning naming the key, the offending
+ * value, and the default it was replaced with, then falls back to that default. This
+ * class never throws.
+ */
+public final class ConfigValidator {
+
+    private ConfigValidator() {
+    }
+
+    /**
+     * Clamps an already-parsed {@code int} to {@code [min, max]}. Out-of-range values
+     * are not clamped to the nearest bound -- they are replaced entirely by
+     * {@code fallback}, and a warning naming {@code key}, {@code value}, and
+     * {@code fallback} is sent to {@code warn}.
+     */
+    public static int clampInt(String key, int value, int min, int max, int fallback, Consumer<String> warn) {
+        if (value < min || value > max) {
+            warn.accept(outOfRangeMessage(key, value, min, max, fallback));
+            return fallback;
+        }
+        return value;
+    }
+
+    /**
+     * Clamps an already-parsed {@code double} to {@code [min, max]}, with the same
+     * substitute-the-default semantics as {@link #clampInt}.
+     */
+    public static double clampDouble(String key, double value, double min, double max, double fallback, Consumer<String> warn) {
+        if (value < min || value > max) {
+            warn.accept(outOfRangeMessage(key, value, min, max, fallback));
+            return fallback;
+        }
+        return value;
+    }
+
+    /**
+     * Parses and range-checks a raw YAML value as an {@code int}.
+     *
+     * <ul>
+     *   <li>{@code raw == null} (key missing): returns {@code fallback}, no warning.</li>
+     *   <li>{@code raw} not parseable as a number: warns and returns {@code fallback}.</li>
+     *   <li>{@code raw} parses but is out of range: delegates to {@link #clampInt}.</li>
+     * </ul>
+     */
+    public static int parseInt(String key, Object raw, int min, int max, int fallback, Consumer<String> warn) {
+        if (raw == null) {
+            return fallback;
+        }
+        Integer parsed = asInt(raw);
+        if (parsed == null) {
+            warn.accept(unparseableMessage(key, raw, fallback));
+            return fallback;
+        }
+        return clampInt(key, parsed, min, max, fallback, warn);
+    }
+
+    /**
+     * Parses and range-checks a raw YAML value as a {@code double}, with the same
+     * missing/unparseable/out-of-range handling as {@link #parseInt}.
+     */
+    public static double parseDouble(String key, Object raw, double min, double max, double fallback, Consumer<String> warn) {
+        if (raw == null) {
+            return fallback;
+        }
+        Double parsed = asDouble(raw);
+        if (parsed == null) {
+            warn.accept(unparseableMessage(key, raw, fallback));
+            return fallback;
+        }
+        return clampDouble(key, parsed, min, max, fallback, warn);
+    }
+
+    /**
+     * Parses a raw YAML value as a {@code boolean}. Booleans have no range to clamp;
+     * a value that is present but neither a {@link Boolean} nor a "true"/"false"
+     * string (case-insensitive) warns and falls back.
+     */
+    public static boolean parseBoolean(String key, Object raw, boolean fallback, Consumer<String> warn) {
+        if (raw == null) {
+            return fallback;
+        }
+        if (raw instanceof Boolean bool) {
+            return bool;
+        }
+        if (raw instanceof String str) {
+            if (str.equalsIgnoreCase("true")) {
+                return true;
+            }
+            if (str.equalsIgnoreCase("false")) {
+                return false;
+            }
+        }
+        warn.accept(unparseableMessage(key, raw, fallback));
+        return fallback;
+    }
+
+    private static Integer asInt(Object raw) {
+        if (raw instanceof Number number) {
+            return number.intValue();
+        }
+        if (raw instanceof String str) {
+            try {
+                return Integer.parseInt(str.trim());
+            } catch (NumberFormatException e) {
+                return null;
+            }
+        }
+        return null;
+    }
+
+    private static Double asDouble(Object raw) {
+        if (raw instanceof Number number) {
+            return number.doubleValue();
+        }
+        if (raw instanceof String str) {
+            try {
+                return Double.parseDouble(str.trim());
+            } catch (NumberFormatException e) {
+                return null;
+            }
+        }
+        return null;
+    }
+
+    private static String outOfRangeMessage(String key, Object value, Object min, Object max, Object fallback) {
+        return "ElectricFurnace config: key '" + key + "' has out-of-range value '" + value
+                + "' (must be between " + min + " and " + max + "); using default '" + fallback + "' instead.";
+    }
+
+    private static String unparseableMessage(String key, Object raw, Object fallback) {
+        return "ElectricFurnace config: key '" + key + "' has unparseable value '" + raw
+                + "'; using default '" + fallback + "' instead.";
+    }
+}
diff --git a/src/main/java/org/xpfarm/electricfurnace/config/EfConfig.java b/src/main/java/org/xpfarm/electricfurnace/config/EfConfig.java
new file mode 100644
index 0000000..e9390a5
--- /dev/null
+++ b/src/main/java/org/xpfarm/electricfurnace/config/EfConfig.java
@@ -0,0 +1,114 @@
+/*
+ * ElectricFurnace - a redstone-powered smelter that recycles metal gear into ingots.
+ * Copyright (C) 2026 Carmelo Santana
+ *
+ * This program is free software: you can redistribute it and/or modify it under the
+ * terms of the GNU Affero General Public License as published by the Free Software
+ * Foundation, either version 3 of the License, or (at your option) any later version.
+ * See the LICENSE file at the project root for the full license text.
+ */
+package org.xpfarm.electricfurnace.config;
+
+import org.bukkit.Sound;
+import org.bukkit.configuration.ConfigurationSection;
+
+import java.util.function.Consumer;
+
+/**
+ * Immutable, fully validated snapshot of {@code config.yml}.
+ *
+ * <p>Built via {@link #load}, which never throws, never disables the plugin, and
+ * never fails startup: any missing, out-of-range, or unparseable value is replaced
+ * with its documented default via {@link ConfigValidator}, and reported through the
+ * supplied warning sink. A server operator with a typo in {@code config.yml} still
+ * gets a fully working plugin.
+ *
+ * @param machine   validated {@code machine} section
+ * @param effects   validated {@code effects} section
+ * @param recycling validated {@code recycling} section
+ */
+public record EfConfig(MachineSettings machine, EffectSettings effects, RecyclingSettings recycling) {
+
+    /** Default sound name for {@code effects.sound}, per the shipping {@code config.yml}. */
+    public static final String DEFAULT_SOUND = "BLOCK_BEACON_AMBIENT";
+
+    /**
+     * Loads and validates configuration from {@code root}.
+     *
+     * @param root the configuration section to read from (typically the plugin's root
+     *             config); may be {@code null}, which is treated as an entirely empty
+     *             configuration -- every key then falls back to its default
+     * @param warn sink for human-readable warning messages naming the offending key,
+     *             value, and substituted default; must not be {@code null}
+     * @return a fully validated, immutable configuration snapshot
+     */
+    public static EfConfig load(ConfigurationSection root, Consumer<String> warn) {
+        MachineSettings machine = new MachineSettings(
+                ConfigValidator.parseDouble("machine.smelt-speed-multiplier",
+                        get(root, "machine.smelt-speed-multiplier"), 1.0, 10.0, 2.0, warn),
+                ConfigValidator.parseInt("machine.fuel-per-operation",
+                        get(root, "machine.fuel-per-operation"), 1, 64, 1, warn),
+                ConfigValidator.parseBoolean("machine.require-redstone-signal",
+                        get(root, "machine.require-redstone-signal"), true, warn),
+                ConfigValidator.parseBoolean("machine.status-bulb.enabled",
+                        get(root, "machine.status-bulb.enabled"), true, warn)
+        );
+
+        EffectSettings effects = new EffectSettings(
+                ConfigValidator.parseBoolean("effects.enabled",
+                        get(root, "effects.enabled"), true, warn),
+                ConfigValidator.parseInt("effects.period-ticks",
+                        get(root, "effects.period-ticks"), 10, 40, 15, warn),
+                ConfigValidator.parseInt("effects.player-radius",
+                        get(root, "effects.player-radius"), 8, 128, 32, warn),
+                resolveSound(get(root, "effects.sound"), warn)
+        );
+
+        RecyclingSettings recycling = new RecyclingSettings(
+                ConfigValidator.parseInt("recycling.slots",
+                        get(root, "recycling.slots"), 1, 9, 5, warn),
+                ConfigValidator.parseInt("recycling.yield-same-metal",
+                        get(root, "recycling.yield-same-metal"), 0, 64, 3, warn),
+                ConfigValidator.parseInt("recycling.yield-mixed-alloy",
+                        get(root, "recycling.yield-mixed-alloy"), 0, 64, 2, warn),
+                ConfigValidator.parseInt("recycling.yield-remelt-alloy",
+                        get(root, "recycling.yield-remelt-alloy"), 0, 64, 1, warn),
+                ConfigValidator.parseBoolean("recycling.accept-damaged",
+                        get(root, "recycling.accept-damaged"), true, warn)
+        );
+
+        return new EfConfig(machine, effects, recycling);
+    }
+
+    private static Object get(ConfigurationSection root, String path) {
+        return root == null ? null : root.get(path);
+    }
+
+    /**
+     * Resolves {@code effects.sound} by name against the {@link Sound} registry.
+     * Unlike every other key in this file, an unresolvable sound name does not fall
+     * back to {@link #DEFAULT_SOUND} -- per the design, it disables sound playback
+     * only, leaving the particle effects untouched. A missing key still falls back
+     * to the default sound, silently.
+     */
+    private static String resolveSound(Object raw, Consumer<String> warn) {
+        if (raw == null) {
+            return DEFAULT_SOUND;
+        }
+        String candidate = String.valueOf(raw);
+        if (resolvesToKnownSound(candidate)) {
+            return candidate;
+        }
+        warn.accept("ElectricFurnace config: key 'effects.sound' has invalid value '" + candidate
+                + "' (does not resolve via the Sound registry); sound disabled, particles unaffected.");
+        return null;
+    }
+
+    private static boolean resolvesToKnownSound(String name) {
+        try {
+            return Sound.class.getField(name).get(null) instanceof Sound;
+        } catch (ReflectiveOperationException e) {
+            return false;
+        }
+    }
+}
diff --git a/src/main/java/org/xpfarm/electricfurnace/config/EffectSettings.java b/src/main/java/org/xpfarm/electricfurnace/config/EffectSettings.java
new file mode 100644
index 0000000..b60282a
--- /dev/null
+++ b/src/main/java/org/xpfarm/electricfurnace/config/EffectSettings.java
@@ -0,0 +1,29 @@
+/*
+ * ElectricFurnace - a redstone-powered smelter that recycles metal gear into ingots.
+ * Copyright (C) 2026 Carmelo Santana
+ *
+ * This program is free software: you can redistribute it and/or modify it under the
+ * terms of the GNU Affero General Public License as published by the Free Software
+ * Foundation, either version 3 of the License, or (at your option) any later version.
+ * See the LICENSE file at the project root for the full license text.
+ */
+package org.xpfarm.electricfurnace.config;
+
+/**
+ * Validated {@code effects} settings section of {@code config.yml}.
+ *
+ * @param enabled      master switch for the particle/sound loop
+ * @param periodTicks  how often, in ticks, the single global effects task runs (valid range 10-40)
+ * @param playerRadius only players within this many blocks of a machine receive its effects
+ *                     (valid range 8-128)
+ * @param sound        name of the {@code Sound} to play each effect tick, or {@code null} if the
+ *                     configured name did not resolve against the sound registry (sound disabled,
+ *                     particles unaffected)
+ */
+public record EffectSettings(
+        boolean enabled,
+        int periodTicks,
+        int playerRadius,
+        String sound
+) {
+}
diff --git a/src/main/java/org/xpfarm/electricfurnace/config/MachineSettings.java b/src/main/java/org/xpfarm/electricfurnace/config/MachineSettings.java
new file mode 100644
index 0000000..e76ce7e
--- /dev/null
+++ b/src/main/java/org/xpfarm/electricfurnace/config/MachineSettings.java
@@ -0,0 +1,26 @@
+/*
+ * ElectricFurnace - a redstone-powered smelter that recycles metal gear into ingots.
+ * Copyright (C) 2026 Carmelo Santana
+ *
+ * This program is free software: you can redistribute it and/or modify it under the
+ * terms of the GNU Affero General Public License as published by the Free Software
+ * Foundation, either version 3 of the License, or (at your option) any later version.
+ * See the LICENSE file at the project root for the full license text.
+ */
+package org.xpfarm.electricfurnace.config;
+
+/**
+ * Validated {@code machine} settings section of {@code config.yml}.
+ *
+ * @param smeltSpeedMultiplier multiplier applied to the base smelt speed (valid range 1.0-10.0)
+ * @param fuelPerOperation     redstone dust consumed per completed operation (valid range 1-64)
+ * @param requireRedstoneSignal whether a redstone signal is required for the machine to run at all
+ * @param statusBulbEnabled    whether an adjacent {@code COPPER_BULB} is driven to reflect machine state
+ */
+public record MachineSettings(
+        double smeltSpeedMultiplier,
+        int fuelPerOperation,
+        boolean requireRedstoneSignal,
+        boolean statusBulbEnabled
+) {
+}
diff --git a/src/main/java/org/xpfarm/electricfurnace/config/RecyclingSettings.java b/src/main/java/org/xpfarm/electricfurnace/config/RecyclingSettings.java
new file mode 100644
index 0000000..bd51277
--- /dev/null
+++ b/src/main/java/org/xpfarm/electricfurnace/config/RecyclingSettings.java
@@ -0,0 +1,30 @@
+/*
+ * ElectricFurnace - a redstone-powered smelter that recycles metal gear into ingots.
+ * Copyright (C) 2026 Carmelo Santana
+ *
+ * This program is free software: you can redistribute it and/or modify it under the
+ * terms of the GNU Affero General Public License as published by the Free Software
+ * Foundation, either version 3 of the License, or (at your option) any later version.
+ * See the LICENSE file at the project root for the full license text.
+ */
+package org.xpfarm.electricfurnace.config;
+
+/**
+ * Validated {@code recycling} settings section of {@code config.yml}.
+ *
+ * @param slots            number of recycler input slots (valid range 1-9)
+ * @param yieldSameMetal   ingots yielded when all inputs are the same metal with no
+ *                         modifier present (valid range 0-64)
+ * @param yieldMixedAlloy  alloy ingots yielded for a mixed-metal input, whether a named
+ *                         recipe or the generic fallback (valid range 0-64)
+ * @param yieldRemeltAlloy ingots yielded when remelting a single alloy item (valid range 0-64)
+ * @param acceptDamaged    whether damaged (partially worn) gear is accepted at full yield
+ */
+public record RecyclingSettings(
+        int slots,
+        int yieldSameMetal,
+        int yieldMixedAlloy,
+        int yieldRemeltAlloy,
+        boolean acceptDamaged
+) {
+}
diff --git a/src/main/resources/config.yml b/src/main/resources/config.yml
new file mode 100644
index 0000000..de90cd2
--- /dev/null
+++ b/src/main/resources/config.yml
@@ -0,0 +1,85 @@
+# ElectricFurnace configuration
+#
+# Balance ceiling (enforced in code, not just documented here): alloy stats must
+# sit between iron and diamond, and must never exceed netherite. Any alloy stat
+# configured above the netherite reference is clamped down to the diamond
+# reference automatically, and a warning is logged naming the alloy, the stat,
+# the configured value, and the clamp.
+#
+# Every key below is validated on load. An out-of-range or unparseable value logs
+# a warning naming the key, the offending value, and the default it was replaced
+# with -- a typo in this file degrades gracefully to defaults, it never stops the
+# plugin from starting.
+
+machine:
+  # Multiplier applied to the base smelt speed. Valid range: 1.0-10.0.
+  smelt-speed-multiplier: 2.0
+  # Redstone dust consumed per completed operation. Valid range: 1-64.
+  fuel-per-operation: 1
+  # Whether the machine requires a redstone signal to run at all. The signal is
+  # the on/off switch; redstone dust is the fuel. See status-bulb below.
+  require-redstone-signal: true
+  status-bulb:
+    # Whether an adjacent COPPER_BULB is driven (via CopperBulb#setLit) to reflect
+    # the machine's current power/fuel state.
+    enabled: true
+
+effects:
+  # Master switch for the particle/sound loop.
+  enabled: true
+  # How often, in ticks, the single global effects task runs. One task serves the
+  # whole server -- there is no per-block scheduled task. Valid range: 10-40.
+  period-ticks: 15
+  # Only players within this many blocks of a running machine receive its effects.
+  # Valid range: 8-128.
+  player-radius: 32
+  # Sound played each effect tick, resolved by name against the Sound registry.
+  # An unresolvable name disables sound only; particles keep playing regardless.
+  sound: BLOCK_BEACON_AMBIENT
+
+recycling:
+  # Number of recycler input slots. Valid range: 1-9.
+  slots: 5
+  # Ingots yielded when all inputs are the same metal, with no modifier (e.g. coal)
+  # present. Valid range: 0-64.
+  yield-same-metal: 3
+  # Alloy ingots yielded for a mixed-metal input -- whether it matches a named
+  # recipe below or falls back to the generic Fused Alloy. Valid range: 0-64.
+  yield-mixed-alloy: 2
+  # Ingots yielded when remelting a single alloy item. This is the only recipe
+  # that accepts fewer than `slots` items. Valid range: 0-64.
+  yield-remelt-alloy: 1
+  # Whether damaged (partially worn) gear is accepted at full yield. Durability is
+  # never scaled into the yield -- giving worn-out gear a worthwhile destination
+  # is a primary purpose of this plugin.
+  accept-damaged: true
+
+# Alloy recipes. This section is a placeholder for Task 2, which defines the
+# AlloyDefinition/AlloyRegistry model and parses these entries -- no code in this
+# task reads this section. Each entry names the distinct inputs (metals and/or
+# the coal modifier) required to produce that alloy, in any order/quantity as
+# long as every listed input is present. `fused_alloy` is the generic fallback
+# for any mixed-metal combination that matches nothing else.
+#
+# Stats (attack damage, attack speed, armor, armor toughness, max durability,
+# enchantability) are added by Task 2. Per the balance ceiling above, no stat may
+# exceed the netherite reference; out-of-range stats are clamped to the diamond
+# reference and logged.
+alloys:
+  steel:
+    display-name: "Steel"
+    inputs: [iron, coal]
+  rose_gold:
+    display-name: "Rose Gold"
+    inputs: [copper, gold]
+  ferrocopper:
+    display-name: "Ferrocopper"
+    inputs: [copper, iron]
+  electrum_steel:
+    display-name: "Electrum Steel"
+    inputs: [gold, iron]
+  fused_alloy:
+    display-name: "Fused Alloy"
+    # Fallback: matches any mixed-metal combination not covered by a named recipe
+    # above (or 3+ distinct metals). Intentionally has no fixed input list.
+    inputs: []
diff --git a/src/test/java/org/xpfarm/electricfurnace/config/ConfigValidatorTest.java b/src/test/java/org/xpfarm/electricfurnace/config/ConfigValidatorTest.java
new file mode 100644
index 0000000..3c0eaf6
--- /dev/null
+++ b/src/test/java/org/xpfarm/electricfurnace/config/ConfigValidatorTest.java
@@ -0,0 +1,227 @@
+/*
+ * ElectricFurnace - a redstone-powered smelter that recycles metal gear into ingots.
+ * Copyright (C) 2026 Carmelo Santana
+ *
+ * This program is free software: you can redistribute it and/or modify it under the
+ * terms of the GNU Affero General Public License as published by the Free Software
+ * Foundation, either version 3 of the License, or (at your option) any later version.
+ * See the LICENSE file at the project root for the full license text.
+ */
+package org.xpfarm.electricfurnace.config;
+
+import org.junit.jupiter.api.Test;
+
+import java.util.ArrayList;
+import java.util.List;
+
+import static org.junit.jupiter.api.Assertions.assertEquals;
+import static org.junit.jupiter.api.Assertions.assertTrue;
+
+/**
+ * Pure unit tests for {@link ConfigValidator}. Deliberately imports nothing from
+ * {@code org.bukkit} and requires no running server -- every case here exercises the
+ * clamping/fallback contract with a plain {@link List} standing in for a logger.
+ */
+class ConfigValidatorTest {
+
+    private final List<String> warnings = new ArrayList<>();
+
+    private void warn(String message) {
+        warnings.add(message);
+    }
+
+    // ---- clampInt ----------------------------------------------------------------
+
+    @Test
+    void clampInt_inRange_passesThroughUnchanged() {
+        int result = ConfigValidator.clampInt("recycling.slots", 5, 1, 9, 5, this::warn);
+
+        assertEquals(5, result);
+        assertTrue(warnings.isEmpty(), "in-range value must not warn");
+    }
+
+    @Test
+    void clampInt_belowMin_fallsBackAndWarns() {
+        int result = ConfigValidator.clampInt("recycling.slots", 0, 1, 9, 5, this::warn);
+
+        assertEquals(5, result);
+        assertEquals(1, warnings.size());
+        assertWarningNames(warnings.get(0), "recycling.slots", "0", "5");
+    }
+
+    @Test
+    void clampInt_aboveMax_fallsBackAndWarns() {
+        int result = ConfigValidator.clampInt("recycling.slots", 42, 1, 9, 5, this::warn);
+
+        assertEquals(5, result);
+        assertEquals(1, warnings.size());
+        assertWarningNames(warnings.get(0), "recycling.slots", "42", "5");
+    }
+
+    @Test
+    void clampInt_atBoundaries_passesThrough() {
+        assertEquals(1, ConfigValidator.clampInt("recycling.slots", 1, 1, 9, 5, this::warn));
+        assertEquals(9, ConfigValidator.clampInt("recycling.slots", 9, 1, 9, 5, this::warn));
+        assertTrue(warnings.isEmpty());
+    }
+
+    // ---- clampDouble ---------------------------------------------------------------
+
+    @Test
+    void clampDouble_inRange_passesThroughUnchanged() {
+        double result = ConfigValidator.clampDouble(
+                "machine.smelt-speed-multiplier", 4.5, 1.0, 10.0, 2.0, this::warn);
+
+        assertEquals(4.5, result);
+        assertTrue(warnings.isEmpty());
+    }
+
+    @Test
+    void clampDouble_belowMin_fallsBackAndWarns() {
+        double result = ConfigValidator.clampDouble(
+                "machine.smelt-speed-multiplier", 0.5, 1.0, 10.0, 2.0, this::warn);
+
+        assertEquals(2.0, result);
+        assertEquals(1, warnings.size());
+        assertWarningNames(warnings.get(0), "machine.smelt-speed-multiplier", "0.5", "2.0");
+    }
+
+    @Test
+    void clampDouble_aboveMax_fallsBackAndWarns() {
+        double result = ConfigValidator.clampDouble(
+                "machine.smelt-speed-multiplier", 99.9, 1.0, 10.0, 2.0, this::warn);
+
+        assertEquals(2.0, result);
+        assertEquals(1, warnings.size());
+        assertWarningNames(warnings.get(0), "machine.smelt-speed-multiplier", "99.9", "2.0");
+    }
+
+    // ---- parseInt (raw config Object -> validated int) ------------------------------
+
+    @Test
+    void parseInt_missingKey_fallsBackSilently() {
+        int result = ConfigValidator.parseInt("recycling.slots", null, 1, 9, 5, this::warn);
+
+        assertEquals(5, result);
+        assertTrue(warnings.isEmpty(), "a missing key is not an error and must not warn");
+    }
+
+    @Test
+    void parseInt_wrongType_fallsBackAndWarns() {
+        int result = ConfigValidator.parseInt("recycling.slots", "five", 1, 9, 5, this::warn);
+
+        assertEquals(5, result);
+        assertEquals(1, warnings.size());
+        assertWarningNames(warnings.get(0), "recycling.slots", "five", "5");
+    }
+
+    @Test
+    void parseInt_wrongType_booleanValue_fallsBackAndWarns() {
+        int result = ConfigValidator.parseInt("recycling.slots", Boolean.TRUE, 1, 9, 5, this::warn);
+
+        assertEquals(5, result);
+        assertEquals(1, warnings.size());
+    }
+
+    @Test
+    void parseInt_numericString_isParsedAndClamped() {
+        int result = ConfigValidator.parseInt("recycling.slots", "3", 1, 9, 5, this::warn);
+
+        assertEquals(3, result);
+        assertTrue(warnings.isEmpty());
+    }
+
+    @Test
+    void parseInt_outOfRangeAfterParsing_fallsBackAndWarns() {
+        int result = ConfigValidator.parseInt("recycling.slots", 20, 1, 9, 5, this::warn);
+
+        assertEquals(5, result);
+        assertEquals(1, warnings.size());
+        assertWarningNames(warnings.get(0), "recycling.slots", "20", "5");
+    }
+
+    // ---- parseDouble -----------------------------------------------------------------
+
+    @Test
+    void parseDouble_missingKey_fallsBackSilently() {
+        double result = ConfigValidator.parseDouble(
+                "machine.smelt-speed-multiplier", null, 1.0, 10.0, 2.0, this::warn);
+
+        assertEquals(2.0, result);
+        assertTrue(warnings.isEmpty());
+    }
+
+    @Test
+    void parseDouble_wrongType_fallsBackAndWarns() {
+        double result = ConfigValidator.parseDouble(
+                "machine.smelt-speed-multiplier", "fast", 1.0, 10.0, 2.0, this::warn);
+
+        assertEquals(2.0, result);
+        assertEquals(1, warnings.size());
+        assertWarningNames(warnings.get(0), "machine.smelt-speed-multiplier", "fast", "2.0");
+    }
+
+    @Test
+    void parseDouble_integerYamlValue_isAcceptedAsDouble() {
+        double result = ConfigValidator.parseDouble(
+                "machine.smelt-speed-multiplier", 3, 1.0, 10.0, 2.0, this::warn);
+
+        assertEquals(3.0, result);
+        assertTrue(warnings.isEmpty());
+    }
+
+    // ---- parseBoolean ------------------------------------------------------------------
+
+    @Test
+    void parseBoolean_missingKey_fallsBackSilently() {
+        boolean result = ConfigValidator.parseBoolean(
+                "machine.require-redstone-signal", null, true, this::warn);
+
+        assertTrue(result);
+        assertTrue(warnings.isEmpty());
+    }
+
+    @Test
+    void parseBoolean_actualBoolean_passesThrough() {
+        boolean result = ConfigValidator.parseBoolean(
+                "machine.require-redstone-signal", Boolean.FALSE, true, this::warn);
+
+        assertEquals(false, result);
+        assertTrue(warnings.isEmpty());
+    }
+
+    @Test
+    void parseBoolean_stringTrueFalse_isParsed() {
+        assertEquals(true, ConfigValidator.parseBoolean(
+                "recycling.accept-damaged", "true", false, this::warn));
+        assertEquals(false, ConfigValidator.parseBoolean(
+                "recycling.accept-damaged", "FALSE", true, this::warn));
+        assertTrue(warnings.isEmpty());
+    }
+
+    @Test
+    void parseBoolean_wrongType_fallsBackAndWarns() {
+        boolean result = ConfigValidator.parseBoolean(
+                "machine.require-redstone-signal", "maybe", true, this::warn);
+
+        assertTrue(result);
+        assertEquals(1, warnings.size());
+        assertWarningNames(warnings.get(0), "machine.require-redstone-signal", "maybe", "true");
+    }
+
+    // ---- warning message contract --------------------------------------------------
+
+    @Test
+    void warningMessage_namesKeyBadValueAndSubstitute() {
+        ConfigValidator.clampInt("effects.period-ticks", 999, 10, 40, 15, this::warn);
+
+        assertEquals(1, warnings.size());
+        assertWarningNames(warnings.get(0), "effects.period-ticks", "999", "15");
+    }
+
+    private static void assertWarningNames(String message, String key, String badValue, String substitute) {
+        assertTrue(message.contains(key), () -> "warning should name the key '" + key + "': " + message);
+        assertTrue(message.contains(badValue), () -> "warning should name the offending value '" + badValue + "': " + message);
+        assertTrue(message.contains(substitute), () -> "warning should name the substituted default '" + substitute + "': " + message);
+    }
+}
```
