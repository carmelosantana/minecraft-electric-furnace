/*
 * ElectricFurnace - a redstone-powered smelter that recycles metal gear into ingots.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.electricfurnace.command;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.xpfarm.electricfurnace.command.ElectricFurnaceCommand.ParseResult;
import org.xpfarm.electricfurnace.command.ElectricFurnaceCommand.Sub;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@code /electricfurnace}'s argument parsing, permission resolution, and
 * tab completion entirely as pure functions over {@code String[]} -- no Bukkit types,
 * no running server. This mirrors the pattern established by
 * {@code MetalClassifier.resolveBranch} and {@code GuiLayout.roleOf}: the decisions
 * live in static methods over primitives so they can be pinned exhaustively here,
 * leaving only untestable Bukkit glue in the command class itself.
 */
class CommandArgsTest {

    private static final String GIVE_PERMISSION = ElectricFurnaceCommand.permissionFor(Sub.GIVE);
    private static final String RELOAD_PERMISSION = ElectricFurnaceCommand.permissionFor(Sub.RELOAD);
    private static final String USE_PERMISSION = ElectricFurnaceCommand.permissionFor(Sub.INFO);

    /** Every distinct permission a sender could hold across all four subcommands. */
    private static final Set<String> ALL_PERMISSIONS = Set.of(GIVE_PERMISSION, RELOAD_PERMISSION, USE_PERMISSION);

    @Nested
    @DisplayName("subcommand recognition")
    class SubcommandRecognition {

        @Test
        void noArgsIsAFailureNamingTheUsage() {
            ParseResult result = ElectricFurnaceCommand.parse(new String[0]);
            assertFalse(result.ok());
            assertTrue(result.error().contains("give"), "usage should list the subcommands: " + result.error());
            assertNull(result.sub());
        }

        @Test
        void unknownSubcommandFailsAndNamesTheOffendingToken() {
            ParseResult result = ElectricFurnaceCommand.parse(new String[]{"explode"});
            assertFalse(result.ok());
            assertTrue(result.error().contains("explode"),
                    "error should name the unknown subcommand: " + result.error());
        }

        @Test
        void subcommandsAreCaseInsensitive() {
            assertEquals(Optional.of(Sub.RELOAD), ElectricFurnaceCommand.subOf("RELOAD"));
            assertEquals(Optional.of(Sub.RELOAD), ElectricFurnaceCommand.subOf("ReLoAd"));
            assertEquals(Optional.of(Sub.INFO), ElectricFurnaceCommand.subOf("info"));
        }

        @Test
        void unknownTokenResolvesToEmpty() {
            assertEquals(Optional.empty(), ElectricFurnaceCommand.subOf("nope"));
            assertEquals(Optional.empty(), ElectricFurnaceCommand.subOf(""));
            assertEquals(Optional.empty(), ElectricFurnaceCommand.subOf(null));
        }
    }

    @Nested
    @DisplayName("permission resolution")
    class PermissionResolution {

        @Test
        void eachSubcommandMapsToItsDocumentedPermission() {
            assertEquals("electricfurnace.give", ElectricFurnaceCommand.permissionFor(Sub.GIVE));
            assertEquals("electricfurnace.give", ElectricFurnaceCommand.permissionFor(Sub.ALLOY));
            assertEquals("electricfurnace.reload", ElectricFurnaceCommand.permissionFor(Sub.RELOAD));
            assertEquals("electricfurnace.use", ElectricFurnaceCommand.permissionFor(Sub.INFO));
        }

        @Test
        void everySubcommandHasAPermission() {
            for (Sub sub : Sub.values()) {
                assertNotNull(ElectricFurnaceCommand.permissionFor(sub), "no permission for " + sub);
            }
        }

        @Test
        void aSuccessfulParseCarriesTheRequiredPermission() {
            ParseResult result = ElectricFurnaceCommand.parse(new String[]{"reload"});
            assertTrue(result.ok());
            assertEquals("electricfurnace.reload", result.permission());
        }

        @Test
        void aFailedParseHasNoPermissionToCheck() {
            // A parse failure must not claim a permission -- callers check permission
            // only on a successful parse, and a null here would NPE if they did not.
            assertNull(ElectricFurnaceCommand.parse(new String[]{"bogus"}).permission());
        }
    }

    @Nested
    @DisplayName("give")
    class Give {

        @Test
        void bareGiveDefaultsToSelfAndOne() {
            ParseResult result = ElectricFurnaceCommand.parse(new String[]{"give"});
            assertTrue(result.ok(), result.error());
            assertEquals(Sub.GIVE, result.sub());
            assertNull(result.targetPlayer(), "no player argument means 'the sender'");
            assertEquals(1, result.amount());
        }

        @Test
        void giveWithAPlayerDefaultsAmountToOne() {
            ParseResult result = ElectricFurnaceCommand.parse(new String[]{"give", "Notch"});
            assertTrue(result.ok(), result.error());
            assertEquals("Notch", result.targetPlayer());
            assertEquals(1, result.amount());
        }

        @Test
        void giveWithPlayerAndAmountParsesBoth() {
            ParseResult result = ElectricFurnaceCommand.parse(new String[]{"give", "Notch", "16"});
            assertTrue(result.ok(), result.error());
            assertEquals("Notch", result.targetPlayer());
            assertEquals(16, result.amount());
        }

        @Test
        void nonNumericAmountFailsAndNamesTheValue() {
            ParseResult result = ElectricFurnaceCommand.parse(new String[]{"give", "Notch", "lots"});
            assertFalse(result.ok());
            assertTrue(result.error().contains("lots"), result.error());
        }

        @Test
        void zeroAndNegativeAmountsAreRejected() {
            assertFalse(ElectricFurnaceCommand.parse(new String[]{"give", "Notch", "0"}).ok());
            assertFalse(ElectricFurnaceCommand.parse(new String[]{"give", "Notch", "-4"}).ok());
        }

        @Test
        void amountAboveAStackIsRejected() {
            assertTrue(ElectricFurnaceCommand.parse(new String[]{"give", "Notch", "64"}).ok());
            assertFalse(ElectricFurnaceCommand.parse(new String[]{"give", "Notch", "65"}).ok());
        }

        @Test
        void amountBoundsErrorNamesBothLimits() {
            String error = ElectricFurnaceCommand.parse(new String[]{"give", "Notch", "999"}).error();
            assertTrue(error.contains("1") && error.contains("64"),
                    "bounds error should name the valid range: " + error);
        }

        @Test
        void extraArgumentsAreRejectedRatherThanSilentlyIgnored() {
            // Silently dropping an argument hides typos: `/ef give Notch 4 stacks`
            // should not quietly hand over 4 items with no complaint.
            assertFalse(ElectricFurnaceCommand.parse(new String[]{"give", "Notch", "4", "stacks"}).ok());
        }
    }

    @Nested
    @DisplayName("alloy")
    class Alloy {

        @Test
        void missingRequiredIdFails() {
            ParseResult result = ElectricFurnaceCommand.parse(new String[]{"alloy"});
            assertFalse(result.ok());
            assertTrue(result.error().toLowerCase().contains("id"),
                    "error should name the missing argument: " + result.error());
        }

        @Test
        void idAloneDefaultsAmountToOne() {
            ParseResult result = ElectricFurnaceCommand.parse(new String[]{"alloy", "steel"});
            assertTrue(result.ok(), result.error());
            assertEquals(Sub.ALLOY, result.sub());
            assertEquals("steel", result.alloyId());
            assertEquals(1, result.amount());
        }

        @Test
        void idAndAmountParseTogether() {
            ParseResult result = ElectricFurnaceCommand.parse(new String[]{"alloy", "rose_gold", "8"});
            assertTrue(result.ok(), result.error());
            assertEquals("rose_gold", result.alloyId());
            assertEquals(8, result.amount());
        }

        @Test
        void alloyIdIsLowercasedToMatchConfigKeys() {
            assertEquals("steel", ElectricFurnaceCommand.parse(new String[]{"alloy", "STEEL"}).alloyId());
        }

        @Test
        void badAmountFailsTheSameWayAsForGive() {
            assertFalse(ElectricFurnaceCommand.parse(new String[]{"alloy", "steel", "0"}).ok());
            assertFalse(ElectricFurnaceCommand.parse(new String[]{"alloy", "steel", "65"}).ok());
            assertFalse(ElectricFurnaceCommand.parse(new String[]{"alloy", "steel", "x"}).ok());
        }
    }

    @Nested
    @DisplayName("reload and info")
    class NoArgSubcommands {

        @Test
        void reloadParsesWithNoArguments() {
            ParseResult result = ElectricFurnaceCommand.parse(new String[]{"reload"});
            assertTrue(result.ok(), result.error());
            assertEquals(Sub.RELOAD, result.sub());
        }

        @Test
        void infoParsesWithNoArguments() {
            ParseResult result = ElectricFurnaceCommand.parse(new String[]{"info"});
            assertTrue(result.ok(), result.error());
            assertEquals(Sub.INFO, result.sub());
        }

        @Test
        void trailingArgumentsAreRejected() {
            assertFalse(ElectricFurnaceCommand.parse(new String[]{"reload", "now"}).ok());
            assertFalse(ElectricFurnaceCommand.parse(new String[]{"info", "please"}).ok());
        }
    }

    @Nested
    @DisplayName("tab completion")
    class TabCompletion {

        @Test
        void firstArgumentOffersEverySubcommandWhenAllPermissionsAreHeld() {
            List<String> completions =
                    ElectricFurnaceCommand.complete(new String[]{""}, List.of("steel"), ALL_PERMISSIONS);
            for (Sub sub : Sub.values()) {
                assertTrue(completions.contains(sub.token()), "missing completion for " + sub);
            }
        }

        @Test
        void firstArgumentFiltersByPrefixCaseInsensitively() {
            assertEquals(List.of("reload"),
                    ElectricFurnaceCommand.complete(new String[]{"rel"}, List.of(), ALL_PERMISSIONS));
            assertEquals(List.of("reload"),
                    ElectricFurnaceCommand.complete(new String[]{"REL"}, List.of(), ALL_PERMISSIONS));
        }

        @Test
        void alloySecondArgumentOffersKnownAlloyIds() {
            List<String> completions = ElectricFurnaceCommand.complete(
                    new String[]{"alloy", ""}, List.of("steel", "rose_gold"), ALL_PERMISSIONS);
            assertTrue(completions.contains("steel"));
            assertTrue(completions.contains("rose_gold"));
        }

        @Test
        void alloySecondArgumentFiltersByPrefix() {
            assertEquals(List.of("steel"), ElectricFurnaceCommand.complete(
                    new String[]{"alloy", "st"}, List.of("steel", "rose_gold"), ALL_PERMISSIONS));
        }

        @Test
        void noArgumentSubcommandsOfferNothing() {
            assertTrue(ElectricFurnaceCommand.complete(
                    new String[]{"reload", ""}, List.of("steel"), ALL_PERMISSIONS).isEmpty());
            assertTrue(ElectricFurnaceCommand.complete(
                    new String[]{"info", ""}, List.of("steel"), ALL_PERMISSIONS).isEmpty());
        }

        @Test
        void mostCompletionsAreNeverNull() {
            assertNotNull(ElectricFurnaceCommand.complete(new String[0], List.of(), ALL_PERMISSIONS));
            assertNotNull(ElectricFurnaceCommand.complete(
                    new String[]{"give", "a", "b", "c"}, List.of(), ALL_PERMISSIONS));
        }

        @Test
        void givesPlayerArgumentDefersToTheServerWithANullReturn() {
            // M2: an empty list is NOT equivalent to null here. Bukkit only falls back
            // to its own default completion (online player names) on a literal null
            // return; an empty list is taken as "no completions" and suppresses it,
            // silently breaking `/ef give <tab>` player-name completion.
            assertNull(ElectricFurnaceCommand.complete(
                    new String[]{"give", ""}, List.of(), ALL_PERMISSIONS));
            assertNull(ElectricFurnaceCommand.complete(
                    new String[]{"give", "No"}, List.of(), ALL_PERMISSIONS));
        }
    }

    @Nested
    @DisplayName("I1 -- tab completion is filtered by the sender's held permissions")
    class PermissionFilteredCompletion {

        @Test
        void noPermissionsYieldsNoSubcommands() {
            assertEquals(List.of(), ElectricFurnaceCommand.allowedSubcommandTokens(Set.of()));
        }

        @Test
        void givePermissionYieldsGiveAndAlloyOnly() {
            List<String> tokens = ElectricFurnaceCommand.allowedSubcommandTokens(Set.of(GIVE_PERMISSION));
            assertEquals(Set.of("give", "alloy"), Set.copyOf(tokens));
        }

        @Test
        void reloadPermissionYieldsReloadOnly() {
            assertEquals(List.of("reload"),
                    ElectricFurnaceCommand.allowedSubcommandTokens(Set.of(RELOAD_PERMISSION)));
        }

        @Test
        void usePermissionYieldsInfoOnly() {
            assertEquals(List.of("info"), ElectricFurnaceCommand.allowedSubcommandTokens(Set.of(USE_PERMISSION)));
        }

        @Test
        void allPermissionsYieldAllFourTokens() {
            List<String> tokens = ElectricFurnaceCommand.allowedSubcommandTokens(ALL_PERMISSIONS);
            assertEquals(4, tokens.size());
            for (Sub sub : Sub.values()) {
                assertTrue(tokens.contains(sub.token()), "missing " + sub.token());
            }
        }

        /**
         * A default player -- no permissions at all -- must not see {@code give},
         * {@code alloy}, or {@code reload} merely by tab-completing {@code /ef}. This
         * is the exact scenario the finding described.
         */
        @Test
        void aDefaultPlayerWithNoPermissionsSeesNothingViaFullTabCompletion() {
            List<String> completions =
                    ElectricFurnaceCommand.complete(new String[]{""}, List.of("steel"), Set.of());
            assertTrue(completions.isEmpty(), "a permissionless sender should see no subcommands: " + completions);
        }

        @Test
        void aPlayerWithOnlyUsePermissionSeesOnlyInfoViaFullTabCompletion() {
            List<String> completions = ElectricFurnaceCommand.complete(
                    new String[]{""}, List.of("steel"), Set.of(USE_PERMISSION));
            assertEquals(List.of("info"), completions);
        }

        @Test
        void exhaustiveOverEveryHeldPermissionSubset() {
            // Every one of the 2^3 subsets of the three distinct permission strings:
            // for each, every subcommand's presence/absence in the completion list must
            // match exactly whether that subcommand's own permission was held.
            List<String> distinctPermissions = List.of(GIVE_PERMISSION, RELOAD_PERMISSION, USE_PERMISSION);
            int subsetCount = 1 << distinctPermissions.size();
            for (int mask = 0; mask < subsetCount; mask++) {
                Set<String> held = new HashSet<>();
                for (int bit = 0; bit < distinctPermissions.size(); bit++) {
                    if ((mask & (1 << bit)) != 0) {
                        held.add(distinctPermissions.get(bit));
                    }
                }
                List<String> tokens = ElectricFurnaceCommand.allowedSubcommandTokens(held);
                for (Sub sub : Sub.values()) {
                    boolean expectedVisible = held.contains(ElectricFurnaceCommand.permissionFor(sub));
                    assertEquals(expectedVisible, tokens.contains(sub.token()),
                            "mask=" + mask + " held=" + held + " sub=" + sub);
                }
            }
        }
    }
}
