/*
 * ElectricFurnace - a redstone-powered smelter that recycles metal gear into ingots.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.electricfurnace.config;

import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;

import java.util.function.Consumer;

/**
 * Immutable, fully validated snapshot of {@code config.yml}.
 *
 * <p>Built via {@link #load}, which never throws, never disables the plugin, and
 * never fails startup: any missing, out-of-range, or unparseable value is replaced
 * with its documented default via {@link ConfigValidator}, and reported through the
 * supplied warning sink. A server operator with a typo in {@code config.yml} still
 * gets a fully working plugin.
 *
 * @param machine   validated {@code machine} section
 * @param effects   validated {@code effects} section
 * @param recycling validated {@code recycling} section
 */
public record EfConfig(MachineSettings machine, EffectSettings effects, RecyclingSettings recycling) {

    /** Default sound name for {@code effects.sound}, per the shipping {@code config.yml}. */
    public static final String DEFAULT_SOUND = "BLOCK_BEACON_AMBIENT";

    /**
     * Loads and validates configuration from {@code root}.
     *
     * @param root the configuration section to read from (typically the plugin's root
     *             config); may be {@code null}, which is treated as an entirely empty
     *             configuration -- every key then falls back to its default
     * @param warn sink for human-readable warning messages naming the offending key,
     *             value, and substituted default; must not be {@code null}
     * @return a fully validated, immutable configuration snapshot
     */
    public static EfConfig load(ConfigurationSection root, Consumer<String> warn) {
        MachineSettings machine = new MachineSettings(
                ConfigValidator.parseDouble("machine.smelt-speed-multiplier",
                        get(root, "machine.smelt-speed-multiplier"), 1.0, 10.0, 2.5, warn),
                ConfigValidator.parseInt("machine.burn-ticks-per-redstone",
                        get(root, "machine.burn-ticks-per-redstone"), 20, 6000, 200, warn),
                ConfigValidator.parseBoolean("machine.require-redstone-signal",
                        get(root, "machine.require-redstone-signal"), true, warn),
                ConfigValidator.parseBoolean("machine.status-bulb.enabled",
                        get(root, "machine.status-bulb.enabled"), true, warn)
        );

        if (get(root, "machine.fuel-per-operation") != null) {
            warn.accept("machine.fuel-per-operation was removed in this version; redstone is now"
                    + " consumed as burn time. Use machine.burn-ticks-per-redstone instead."
                    + " The old key is being ignored.");
        }

        EffectSettings effects = new EffectSettings(
                ConfigValidator.parseBoolean("effects.enabled",
                        get(root, "effects.enabled"), true, warn),
                ConfigValidator.parseInt("effects.period-ticks",
                        get(root, "effects.period-ticks"), 10, 40, 15, warn),
                ConfigValidator.parseInt("effects.player-radius",
                        get(root, "effects.player-radius"), 8, 128, 32, warn),
                resolveSound(get(root, "effects.sound"), warn)
        );

        // Read out of line, unlike every other key here, because it is the one value this
        // method needs twice: `yield-remelt-alloy` is capped against it (see
        // ConfigValidator#remeltYieldCeiling). It must be the *validated* count -- an
        // out-of-range `slots` falls back to 5, and the yield is judged against 5.
        int slots = ConfigValidator.parseInt("recycling.slots",
                get(root, "recycling.slots"), 1, 9, 5, warn);

        RecyclingSettings recycling = new RecyclingSettings(
                slots,
                // Flat per operation, so no input count multiplies them and a full stack
                // is a safe upper bound.
                ConfigValidator.parseInt("recycling.yield-same-metal",
                        get(root, "recycling.yield-same-metal"), 0, 64, 3, warn),
                ConfigValidator.parseInt("recycling.yield-mixed-alloy",
                        get(root, "recycling.yield-mixed-alloy"), 0, 64, 2, warn),
                ConfigValidator.parseRemeltYield("recycling.yield-remelt-alloy",
                        get(root, "recycling.yield-remelt-alloy"), slots, 1, warn),
                ConfigValidator.parseBoolean("recycling.accept-damaged",
                        get(root, "recycling.accept-damaged"), true, warn)
        );

        return new EfConfig(machine, effects, recycling);
    }

    private static Object get(ConfigurationSection root, String path) {
        return root == null ? null : root.get(path);
    }

    /**
     * Resolves {@code effects.sound} by name against the {@link Sound} registry.
     * Unlike every other key in this file, an unresolvable sound name does not fall
     * back to {@link #DEFAULT_SOUND} -- per the design, it disables sound playback
     * only, leaving the particle effects untouched. A missing key still falls back
     * to the default sound, silently.
     */
    private static String resolveSound(Object raw, Consumer<String> warn) {
        if (raw == null) {
            return DEFAULT_SOUND;
        }
        String candidate = String.valueOf(raw);
        if (resolvesToKnownSound(candidate, warn)) {
            return candidate;
        }
        warn.accept("ElectricFurnace config: key 'effects.sound' has invalid value '" + candidate
                + "' (does not resolve via the Sound registry); sound disabled, particles unaffected.");
        return null;
    }

    /**
     * Whether {@code name} resolves to a constant on the {@link Sound} registry class.
     *
     * <p><b>Catches {@link Throwable}, deliberately.</b> Reflecting on {@code Sound}
     * triggers its static initializer, which on Paper walks the server's sound
     * registry. On a version mismatch, a partially-initialised server, or a shaded
     * classpath problem that fails as an {@link Error} (typically
     * {@code ExceptionInInitializerError}, {@code NoClassDefFoundError}, or
     * {@code LinkageError}), an {@code Error} is not a
     * {@link ReflectiveOperationException} and would escape this method, propagate out
     * of {@link #load}, and fail plugin startup. That directly violates this class's
     * central contract: a configuration problem must never fail startup. So every
     * {@code Throwable} degrades exactly the same way an unknown sound name does --
     * warn, and disable sound only, leaving particles untouched.
     */
    private static boolean resolvesToKnownSound(String name, Consumer<String> warn) {
        try {
            return Sound.class.getField(name).get(null) instanceof Sound;
        } catch (ReflectiveOperationException e) {
            return false;
        } catch (Throwable t) {
            warn.accept("ElectricFurnace config: could not consult the Sound registry while resolving"
                    + " 'effects.sound' (" + t.getClass().getName() + ": " + t.getMessage()
                    + "); sound disabled, particles unaffected.");
            return false;
        }
    }
}
