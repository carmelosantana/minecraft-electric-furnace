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

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for {@link ConfigValidator}. Deliberately imports nothing from
 * {@code org.bukkit} and requires no running server -- every case here exercises the
 * clamping/fallback contract with a plain {@link List} standing in for a logger.
 */
class ConfigValidatorTest {

    private final List<String> warnings = new ArrayList<>();

    private void warn(String message) {
        warnings.add(message);
    }

    // ---- clampInt ----------------------------------------------------------------

    @Test
    void clampInt_inRange_passesThroughUnchanged() {
        int result = ConfigValidator.clampInt("recycling.slots", 5, 1, 9, 5, this::warn);

        assertEquals(5, result);
        assertTrue(warnings.isEmpty(), "in-range value must not warn");
    }

    @Test
    void clampInt_belowMin_fallsBackAndWarns() {
        int result = ConfigValidator.clampInt("recycling.slots", 0, 1, 9, 5, this::warn);

        assertEquals(5, result);
        assertEquals(1, warnings.size());
        assertWarningNames(warnings.get(0), "recycling.slots", "0", "5");
    }

    @Test
    void clampInt_aboveMax_fallsBackAndWarns() {
        int result = ConfigValidator.clampInt("recycling.slots", 42, 1, 9, 5, this::warn);

        assertEquals(5, result);
        assertEquals(1, warnings.size());
        assertWarningNames(warnings.get(0), "recycling.slots", "42", "5");
    }

    @Test
    void clampInt_atBoundaries_passesThrough() {
        assertEquals(1, ConfigValidator.clampInt("recycling.slots", 1, 1, 9, 5, this::warn));
        assertEquals(9, ConfigValidator.clampInt("recycling.slots", 9, 1, 9, 5, this::warn));
        assertTrue(warnings.isEmpty());
    }

    // ---- clampDouble ---------------------------------------------------------------

    @Test
    void clampDouble_inRange_passesThroughUnchanged() {
        double result = ConfigValidator.clampDouble(
                "machine.smelt-speed-multiplier", 4.5, 1.0, 10.0, 2.5, this::warn);

        assertEquals(4.5, result);
        assertTrue(warnings.isEmpty());
    }

    @Test
    void clampDouble_belowMin_fallsBackAndWarns() {
        double result = ConfigValidator.clampDouble(
                "machine.smelt-speed-multiplier", 0.5, 1.0, 10.0, 2.5, this::warn);

        assertEquals(2.5, result);
        assertEquals(1, warnings.size());
        assertWarningNames(warnings.get(0), "machine.smelt-speed-multiplier", "0.5", "2.5");
    }

    @Test
    void clampDouble_aboveMax_fallsBackAndWarns() {
        double result = ConfigValidator.clampDouble(
                "machine.smelt-speed-multiplier", 99.9, 1.0, 10.0, 2.5, this::warn);

        assertEquals(2.5, result);
        assertEquals(1, warnings.size());
        assertWarningNames(warnings.get(0), "machine.smelt-speed-multiplier", "99.9", "2.5");
    }

    // ---- parseInt (raw config Object -> validated int) ------------------------------

    @Test
    void parseInt_missingKey_fallsBackSilently() {
        int result = ConfigValidator.parseInt("recycling.slots", null, 1, 9, 5, this::warn);

        assertEquals(5, result);
        assertTrue(warnings.isEmpty(), "a missing key is not an error and must not warn");
    }

    @Test
    void parseInt_wrongType_fallsBackAndWarns() {
        int result = ConfigValidator.parseInt("recycling.slots", "five", 1, 9, 5, this::warn);

        assertEquals(5, result);
        assertEquals(1, warnings.size());
        assertWarningNames(warnings.get(0), "recycling.slots", "five", "5");
    }

    @Test
    void parseInt_wrongType_booleanValue_fallsBackAndWarns() {
        int result = ConfigValidator.parseInt("recycling.slots", Boolean.TRUE, 1, 9, 5, this::warn);

        assertEquals(5, result);
        assertEquals(1, warnings.size());
    }

    @Test
    void parseInt_numericString_isParsedAndClamped() {
        int result = ConfigValidator.parseInt("recycling.slots", "3", 1, 9, 5, this::warn);

        assertEquals(3, result);
        assertTrue(warnings.isEmpty());
    }

    @Test
    void parseInt_outOfRangeAfterParsing_fallsBackAndWarns() {
        int result = ConfigValidator.parseInt("recycling.slots", 20, 1, 9, 5, this::warn);

        assertEquals(5, result);
        assertEquals(1, warnings.size());
        assertWarningNames(warnings.get(0), "recycling.slots", "20", "5");
    }

    // ---- parseDouble -----------------------------------------------------------------

    @Test
    void parseDouble_missingKey_fallsBackSilently() {
        double result = ConfigValidator.parseDouble(
                "machine.smelt-speed-multiplier", null, 1.0, 10.0, 2.5, this::warn);

        assertEquals(2.5, result);
        assertTrue(warnings.isEmpty());
    }

    @Test
    void parseDouble_wrongType_fallsBackAndWarns() {
        double result = ConfigValidator.parseDouble(
                "machine.smelt-speed-multiplier", "fast", 1.0, 10.0, 2.5, this::warn);

        assertEquals(2.5, result);
        assertEquals(1, warnings.size());
        assertWarningNames(warnings.get(0), "machine.smelt-speed-multiplier", "fast", "2.5");
    }

    @Test
    void parseDouble_integerYamlValue_isAcceptedAsDouble() {
        double result = ConfigValidator.parseDouble(
                "machine.smelt-speed-multiplier", 3, 1.0, 10.0, 2.5, this::warn);

        assertEquals(3.0, result);
        assertTrue(warnings.isEmpty());
    }

    // ---- parseBoolean ------------------------------------------------------------------

    @Test
    void parseBoolean_missingKey_fallsBackSilently() {
        boolean result = ConfigValidator.parseBoolean(
                "machine.require-redstone-signal", null, true, this::warn);

        assertTrue(result);
        assertTrue(warnings.isEmpty());
    }

    @Test
    void parseBoolean_actualBoolean_passesThrough() {
        boolean result = ConfigValidator.parseBoolean(
                "machine.require-redstone-signal", Boolean.FALSE, true, this::warn);

        assertEquals(false, result);
        assertTrue(warnings.isEmpty());
    }

    @Test
    void parseBoolean_stringTrueFalse_isParsed() {
        assertEquals(true, ConfigValidator.parseBoolean(
                "recycling.accept-damaged", "true", false, this::warn));
        assertEquals(false, ConfigValidator.parseBoolean(
                "recycling.accept-damaged", "FALSE", true, this::warn));
        assertTrue(warnings.isEmpty());
    }

    @Test
    void parseBoolean_wrongType_fallsBackAndWarns() {
        boolean result = ConfigValidator.parseBoolean(
                "machine.require-redstone-signal", "maybe", true, this::warn);

        assertTrue(result);
        assertEquals(1, warnings.size());
        assertWarningNames(warnings.get(0), "machine.require-redstone-signal", "maybe", "true");
    }

    // ---- warning message contract --------------------------------------------------

    @Test
    void warningMessage_namesKeyBadValueAndSubstitute() {
        ConfigValidator.clampInt("effects.period-ticks", 999, 10, 40, 15, this::warn);

        assertEquals(1, warnings.size());
        assertWarningNames(warnings.get(0), "effects.period-ticks", "999", "15");
    }

    private static void assertWarningNames(String message, String key, String badValue, String substitute) {
        assertTrue(message.contains(key), () -> "warning should name the key '" + key + "': " + message);
        assertTrue(message.contains(badValue), () -> "warning should name the offending value '" + badValue + "': " + message);
        assertTrue(message.contains(substitute), () -> "warning should name the substituted default '" + substitute + "': " + message);
    }
}
