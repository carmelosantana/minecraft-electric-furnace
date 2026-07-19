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

import java.util.function.Consumer;

/**
 * Pure, Bukkit-free validation and fallback logic for {@code config.yml} values.
 *
 * <p>Every method here operates on primitives (or raw {@link Object} values pulled
 * straight from a YAML parser) and reports problems through a caller-supplied
 * {@link Consumer} rather than logging directly. This is deliberate: it lets
 * {@code ConfigValidatorTest} exercise the full validation contract with zero
 * {@code org.bukkit} types and no running server.
 *
 * <p><b>Contract:</b> a value that is missing is not an error -- it silently falls
 * back to the default. A value that is present but out of range or unparseable
 * <em>is</em> an error: it logs exactly one warning naming the key, the offending
 * value, and the default it was replaced with, then falls back to that default. This
 * class never throws.
 */
public final class ConfigValidator {

    private ConfigValidator() {
    }

    /**
     * Clamps an already-parsed {@code int} to {@code [min, max]}. Out-of-range values
     * are not clamped to the nearest bound -- they are replaced entirely by
     * {@code fallback}, and a warning naming {@code key}, {@code value}, and
     * {@code fallback} is sent to {@code warn}.
     */
    public static int clampInt(String key, int value, int min, int max, int fallback, Consumer<String> warn) {
        if (value < min || value > max) {
            warn.accept(outOfRangeMessage(key, value, min, max, fallback));
            return fallback;
        }
        return value;
    }

    /**
     * Clamps an already-parsed {@code double} to {@code [min, max]}, with the same
     * substitute-the-default semantics as {@link #clampInt}.
     */
    public static double clampDouble(String key, double value, double min, double max, double fallback, Consumer<String> warn) {
        if (value < min || value > max) {
            warn.accept(outOfRangeMessage(key, value, min, max, fallback));
            return fallback;
        }
        return value;
    }

    /**
     * Parses and range-checks a raw YAML value as an {@code int}.
     *
     * <ul>
     *   <li>{@code raw == null} (key missing): returns {@code fallback}, no warning.</li>
     *   <li>{@code raw} not parseable as a number: warns and returns {@code fallback}.</li>
     *   <li>{@code raw} parses but is out of range: delegates to {@link #clampInt}.</li>
     * </ul>
     */
    public static int parseInt(String key, Object raw, int min, int max, int fallback, Consumer<String> warn) {
        if (raw == null) {
            return fallback;
        }
        Integer parsed = asInt(raw);
        if (parsed == null) {
            warn.accept(unparseableMessage(key, raw, fallback));
            return fallback;
        }
        return clampInt(key, parsed, min, max, fallback, warn);
    }

    /**
     * Parses and range-checks a raw YAML value as a {@code double}, with the same
     * missing/unparseable/out-of-range handling as {@link #parseInt}.
     */
    public static double parseDouble(String key, Object raw, double min, double max, double fallback, Consumer<String> warn) {
        if (raw == null) {
            return fallback;
        }
        Double parsed = asDouble(raw);
        if (parsed == null) {
            warn.accept(unparseableMessage(key, raw, fallback));
            return fallback;
        }
        return clampDouble(key, parsed, min, max, fallback, warn);
    }

    /**
     * Parses a raw YAML value as a {@code boolean}. Booleans have no range to clamp;
     * a value that is present but neither a {@link Boolean} nor a "true"/"false"
     * string (case-insensitive) warns and falls back.
     */
    public static boolean parseBoolean(String key, Object raw, boolean fallback, Consumer<String> warn) {
        if (raw == null) {
            return fallback;
        }
        if (raw instanceof Boolean bool) {
            return bool;
        }
        if (raw instanceof String str) {
            if (str.equalsIgnoreCase("true")) {
                return true;
            }
            if (str.equalsIgnoreCase("false")) {
                return false;
            }
        }
        warn.accept(unparseableMessage(key, raw, fallback));
        return fallback;
    }

    private static Integer asInt(Object raw) {
        if (raw instanceof Number number) {
            return number.intValue();
        }
        if (raw instanceof String str) {
            try {
                return Integer.parseInt(str.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static Double asDouble(Object raw) {
        if (raw instanceof Number number) {
            return number.doubleValue();
        }
        if (raw instanceof String str) {
            try {
                return Double.parseDouble(str.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static String outOfRangeMessage(String key, Object value, Object min, Object max, Object fallback) {
        return "ElectricFurnace config: key '" + key + "' has out-of-range value '" + value
                + "' (must be between " + min + " and " + max + "); using default '" + fallback + "' instead.";
    }

    private static String unparseableMessage(String key, Object raw, Object fallback) {
        return "ElectricFurnace config: key '" + key + "' has unparseable value '" + raw
                + "'; using default '" + fallback + "' instead.";
    }
}
