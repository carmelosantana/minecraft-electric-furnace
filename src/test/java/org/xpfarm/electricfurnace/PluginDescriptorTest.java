/*
 * ElectricFurnace - a redstone-powered smelter that recycles metal gear into ingots.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.electricfurnace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.xpfarm.electricfurnace.gear.GearBase;
import org.xpfarm.electricfurnace.recipe.MachineRecipe;
import org.yaml.snakeyaml.Yaml;

/**
 * Parses the shipped resource YAML with the same SnakeYAML the server uses.
 *
 * <h2>Why this exists</h2>
 *
 * <p>A malformed {@code plugin.yml} is not a compile error, is not a test failure, and does not
 * fail {@code mvn verify} — Maven copies the file into the JAR and it is only parsed when a real
 * Paper server boots. Magic Carpet shipped a descriptor whose unquoted {@code ": "} inside the
 * description made SnakeYAML read the rest of the line as a nested mapping and throw
 * {@code ScannerException: mapping values are not allowed here}. Paper logged
 * {@code InvalidDescriptionException} and never registered the plugin at all — it was absent from
 * {@code /plugins} rather than present-and-disabled, a materially more confusing symptom. The
 * defect survived every per-task review, an adversarial whole-branch review, and a green CI run,
 * because nothing in the pipeline ever parsed the file as YAML.
 *
 * <p>These tests close that gap at gate 6 instead of gate 7a.
 */
final class PluginDescriptorTest {

    private static final Path PLUGIN_YML = descriptor("plugin.yml");
    private static final Path CONFIG_YML = descriptor("config.yml");

    /**
     * Prefers the Maven-filtered copy in {@code target/classes} — that is the file that actually
     * ships, and property substitution can inject YAML metacharacters the source file never had.
     * Falls back to the source tree so the test still runs before {@code process-resources}.
     */
    private static Path descriptor(String name) {
        Path filtered = Path.of("target", "classes", name);
        return Files.exists(filtered) ? filtered : Path.of("src", "main", "resources", name);
    }

    private static Map<String, Object> parse(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            return new Yaml().load(in);
        }
    }

    @Test
    void pluginYmlIsValidYaml() throws IOException {
        assertNotNull(parse(PLUGIN_YML), "plugin.yml parsed to null — the file is empty or malformed");
    }

    @Test
    void configYmlIsValidYaml() throws IOException {
        assertNotNull(parse(CONFIG_YML), "config.yml parsed to null — the file is empty or malformed");
    }

    @Test
    void pluginYmlDeclaresTheFieldsPaperRequires() throws IOException {
        Map<String, Object> parsed = parse(PLUGIN_YML);

        assertEquals("ElectricFurnace", parsed.get("name"));
        assertEquals("org.xpfarm.electricfurnace.ElectricFurnacePlugin", parsed.get("main"));
        assertInstanceOf(String.class, parsed.get("api-version"),
                "api-version must be quoted; unquoted it parses as a double and 1.20 becomes 1.2");
        assertEquals("1.21", parsed.get("api-version"));
        assertNotNull(parsed.get("description"), "description is required");

        Object version = parsed.get("version");
        assertNotNull(version, "version is required");
        assertFalse(version.toString().contains("${"),
                "version still holds an unresolved Maven property: " + version);
    }

    /**
     * Every shipped alloy names a base, and every name is one the loader resolves.
     *
     * <p>Same class of defect as the descriptor tests above: a typo here
     * ({@code golden}, {@code Iron }, a stray quote) is not a compile error and not a
     * test failure anywhere else. {@code AlloyRegistry.load} would log one warning and
     * silently fall back to the thematic default, so the only symptom in play is that
     * an alloy's gear wears the wrong vanilla texture -- which is precisely the thing
     * base exists to control, and the thing nobody would think to re-check.
     */
    @Test
    void everyShippedAlloyDeclaresAResolvableBase() throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> alloys = (Map<String, Object>) parse(CONFIG_YML).get("alloys");
        assertNotNull(alloys, "alloys section is required");
        assertFalse(alloys.isEmpty(), "alloys section is empty");

        for (Map.Entry<String, Object> entry : alloys.entrySet()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> alloy = (Map<String, Object>) entry.getValue();
            Object base = alloy.get("base");
            assertNotNull(base, "alloy '" + entry.getKey() + "' declares no base");
            assertTrue(GearBase.byId(base.toString()).isPresent(),
                    "alloy '" + entry.getKey() + "' declares base '" + base
                            + "', which GearBase.byId does not resolve");
        }
    }

    @Test
    void pluginYmlDeclaresEveryCommandTheCodeLooksUp() throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> commands = (Map<String, Object>) parse(PLUGIN_YML).get("commands");
        assertNotNull(commands, "commands section is required");
        assertTrue(commands.containsKey("electricfurnace"),
                "the electricfurnace command must be declared or getCommand(\"electricfurnace\") returns null");
    }

    @Test
    void pluginYmlDeclaresEveryPermissionTheCodeChecks() throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> permissions = (Map<String, Object>) parse(PLUGIN_YML).get("permissions");
        assertNotNull(permissions, "permissions section is required");
        assertTrue(permissions.containsKey("electricfurnace.craft"), "electricfurnace.craft must be declared");
        assertTrue(permissions.containsKey("electricfurnace.give"), "electricfurnace.give must be declared");
        assertTrue(permissions.containsKey("electricfurnace.reload"), "electricfurnace.reload must be declared");
        assertTrue(permissions.containsKey("electricfurnace.use"), "electricfurnace.use must be declared");
    }

    /**
     * Binds the node the code actually checks to the node the descriptor actually
     * declares.
     *
     * <p>The test above hardcodes the four strings, so it stays green if the constant
     * drifts. Both {@link MachineRecipe#onPrepareCraft} and
     * {@link org.xpfarm.electricfurnace.recipe.GearRecipes#onPrepareCraft} gate on
     * {@link MachineRecipe#CRAFT_PERMISSION}, and an undeclared node has no default, so
     * a typo there silently denies every craft to every non-op instead of the
     * {@code default: true} the spec calls for.
     */
    @Test
    void theCraftPermissionTheRecipeHandlersEnforceIsTheOnePluginYmlDeclares() throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> permissions = (Map<String, Object>) parse(PLUGIN_YML).get("permissions");
        assertNotNull(permissions, "permissions section is required");

        assertTrue(permissions.containsKey(MachineRecipe.CRAFT_PERMISSION),
                "the recipe handlers enforce '" + MachineRecipe.CRAFT_PERMISSION
                        + "', which plugin.yml does not declare");

        @SuppressWarnings("unchecked")
        Map<String, Object> craft = (Map<String, Object>) permissions.get(MachineRecipe.CRAFT_PERMISSION);
        assertEquals(true, craft.get("default"),
                "the craft permission must default to true -- it gates crafting for everyone");
    }
}
