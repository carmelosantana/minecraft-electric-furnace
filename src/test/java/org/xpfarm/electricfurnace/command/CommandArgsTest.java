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
import org.xpfarm.electricfurnace.alloy.AlloyDefinition;
import org.xpfarm.electricfurnace.alloy.AlloyStats;
import org.xpfarm.electricfurnace.command.ElectricFurnaceCommand.ParseResult;
import org.xpfarm.electricfurnace.command.ElectricFurnaceCommand.Sub;
import org.xpfarm.electricfurnace.gear.GearBase;
import org.xpfarm.electricfurnace.gear.GearPiece;

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

    /**
     * The optional {@code piece} argument: {@code /electricfurnace alloy <id> [piece] [amount]}.
     *
     * <p>The third token is genuinely ambiguous -- {@code alloy steel 5} means five
     * ingots, {@code alloy steel sword} means one sword -- so both readings are pinned
     * here, along with the token that is neither.
     */
    @Nested
    @DisplayName("alloy piece argument")
    class AlloyPiece {

        @Test
        void parseAlloy_withoutPiece_stillYieldsAnIngot() {
            ParseResult result = ElectricFurnaceCommand.parse(new String[]{"alloy", "steel"});

            assertTrue(result.ok(), result.error());
            assertEquals(Sub.ALLOY, result.sub());
            assertEquals("steel", result.alloyId());
            assertNull(result.piece());
        }

        @Test
        void parseAlloy_withPiece_resolvesThePiece() {
            ParseResult result = ElectricFurnaceCommand.parse(new String[]{"alloy", "steel", "chestplate"});

            assertTrue(result.ok(), result.error());
            assertEquals("steel", result.alloyId());
            assertEquals(GearPiece.CHESTPLATE, result.piece());
        }

        @Test
        void parseAlloy_withPieceAndAmount_resolvesBoth() {
            ParseResult result = ElectricFurnaceCommand.parse(new String[]{"alloy", "steel", "sword", "3"});

            assertTrue(result.ok(), result.error());
            assertEquals("steel", result.alloyId());
            assertEquals(GearPiece.SWORD, result.piece());
            assertEquals(3, result.amount());
        }

        @Test
        void parseAlloy_amountInThePieceSlot_isStillAnAmount() {
            // The ambiguous case: "alloy steel 5" must mean five ingots, not a piece.
            ParseResult result = ElectricFurnaceCommand.parse(new String[]{"alloy", "steel", "5"});

            assertTrue(result.ok(), result.error());
            assertEquals("steel", result.alloyId());
            assertNull(result.piece());
            assertEquals(5, result.amount());
        }

        @Test
        void parseAlloy_unknownPiece_isAnError() {
            ParseResult result = ElectricFurnaceCommand.parse(new String[]{"alloy", "steel", "trousers"});

            assertFalse(result.ok());
            assertNotNull(result.error());
            assertTrue(result.error().contains("trousers"),
                    "the error should name the offending token: " + result.error());
            // Diagnosed as a piece typo, not as a bad number: a word in this slot was
            // plainly meant as a piece, and "'trousers' is not a number" would describe
            // the wrong argument entirely.
            // "gear piece", not just "piece": usage() itself contains the literal
            // "[piece]", so the looser assertion would pass on any error that happens to
            // append usage() -- including a "not a number" one this test exists to reject.
            assertTrue(result.error().toLowerCase(java.util.Locale.ROOT).contains("gear piece"),
                    "the error should say what kind of token was wrong: " + result.error());
        }

        @Test
        @DisplayName("a piece with no amount still defaults to one, exactly as an id alone does")
        void pieceWithoutAmountDefaultsToOne() {
            // The amount index moves when a piece is consumed; if it moved wrongly the
            // default would silently differ between "alloy steel" and "alloy steel sword".
            assertEquals(1, ElectricFurnaceCommand.parse(new String[]{"alloy", "steel"}).amount());
            assertEquals(1, ElectricFurnaceCommand.parse(new String[]{"alloy", "steel", "sword"}).amount());
        }

        @Test
        @DisplayName("every gear piece id is accepted, not just the two the tests name")
        void everyPieceIdResolves() {
            for (GearPiece piece : GearPiece.values()) {
                ParseResult result =
                        ElectricFurnaceCommand.parse(new String[]{"alloy", "steel", piece.id()});
                assertTrue(result.ok(), piece.id() + ": " + result.error());
                assertEquals(piece, result.piece(), "wrong piece for token " + piece.id());
                assertEquals(1, result.amount(), "amount default broke for " + piece.id());
            }
        }

        @Test
        @DisplayName("piece ids are case-insensitive, like alloy ids and subcommands")
        void pieceIdIsCaseInsensitive() {
            assertEquals(GearPiece.SWORD,
                    ElectricFurnaceCommand.parse(new String[]{"alloy", "steel", "SWORD"}).piece());
            assertEquals(GearPiece.CHESTPLATE,
                    ElectricFurnaceCommand.parse(new String[]{"alloy", "STEEL", "ChestPlate"}).piece());
        }

        @Test
        @DisplayName("a bad amount after a piece fails the same way as one without a piece")
        void badAmountAfterAPieceIsRejected() {
            assertFalse(ElectricFurnaceCommand.parse(new String[]{"alloy", "steel", "sword", "0"}).ok());
            assertFalse(ElectricFurnaceCommand.parse(new String[]{"alloy", "steel", "sword", "65"}).ok());
            assertFalse(ElectricFurnaceCommand.parse(new String[]{"alloy", "steel", "sword", "x"}).ok());
        }

        @Test
        @DisplayName("an out-of-range amount in the piece slot reports the range, not 'unknown piece'")
        void negativeAmountInThePieceSlotIsReportedAsAnAmount() {
            // "-4" is not a piece id, but calling it an unknown gear piece would send an
            // operator hunting for a typo in a word they never typed.
            String error = ElectricFurnaceCommand.parse(new String[]{"alloy", "steel", "-4"}).error();
            assertNotNull(error);
            assertTrue(error.contains("1") && error.contains("64"),
                    "should name the amount bounds: " + error);
        }

        @Test
        @DisplayName("surplus arguments are rejected here too, with or without a piece")
        void surplusArgumentsAreRejected() {
            assertFalse(ElectricFurnaceCommand.parse(
                    new String[]{"alloy", "steel", "sword", "3", "stacks"}).ok());
            assertFalse(ElectricFurnaceCommand.parse(new String[]{"alloy", "steel", "5", "3"}).ok());
        }

        @Test
        @DisplayName("no other invocation carries a piece")
        void everyOtherInvocationHasANullPiece() {
            assertNull(ElectricFurnaceCommand.parse(new String[]{"give", "Notch", "4"}).piece());
            assertNull(ElectricFurnaceCommand.parse(new String[]{"reload"}).piece());
            assertNull(ElectricFurnaceCommand.parse(new String[]{"info"}).piece());
            assertNull(ElectricFurnaceCommand.parse(new String[]{"bogus"}).piece());
        }

        @Test
        @DisplayName("issuing a piece needs no permission beyond the one alloy already required")
        void pieceRequiresNoNewPermission() {
            assertEquals("electricfurnace.give",
                    ElectricFurnaceCommand.parse(new String[]{"alloy", "steel", "sword"}).permission());
        }

        @Test
        @DisplayName("the usage line advertises the optional piece")
        void usageMentionsThePiece() {
            assertTrue(ElectricFurnaceCommand.usage().contains("[piece]"), ElectricFurnaceCommand.usage());
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
        @DisplayName("alloy's third argument offers every gear piece id")
        void alloyThirdArgumentOffersGearPieceIds() {
            List<String> completions = ElectricFurnaceCommand.complete(
                    new String[]{"alloy", "steel", ""}, List.of("steel", "rose_gold"), ALL_PERMISSIONS);
            for (GearPiece piece : GearPiece.values()) {
                assertTrue(completions.contains(piece.id()), "missing completion for " + piece.id());
            }
        }

        @Test
        @DisplayName("alloy's third argument filters piece ids by prefix")
        void alloyThirdArgumentFiltersByPrefix() {
            assertEquals(List.of("chestplate"), ElectricFurnaceCommand.complete(
                    new String[]{"alloy", "steel", "ch"}, List.of("steel"), ALL_PERMISSIONS));
            assertEquals(List.of("sword"), ElectricFurnaceCommand.complete(
                    new String[]{"alloy", "steel", "SW"}, List.of("steel"), ALL_PERMISSIONS));
        }

        @Test
        @DisplayName("only alloy offers piece ids, and only in the third argument")
        void pieceIdsAreNotOfferedElsewhere() {
            assertTrue(ElectricFurnaceCommand.complete(
                    new String[]{"alloy", "steel", "sword", ""}, List.of("steel"), ALL_PERMISSIONS).isEmpty(),
                    "the amount argument is free-form");
            assertTrue(ElectricFurnaceCommand.complete(
                    new String[]{"give", "Notch", ""}, List.of("steel"), ALL_PERMISSIONS).isEmpty(),
                    "give's amount argument is free-form");
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

    /**
     * Resolving a typed {@code give} target name.
     *
     * <p>The operator who first ran this command in production typed a bare Java-style
     * name for a Floodgate Bedrock account, whose real username carries a {@code .}
     * prefix. {@code Bukkit.getPlayerExact} matched nothing and the only feedback was
     * "not online", which reads as the command doing nothing at all. These cases pin
     * the candidate list and the failure message that replaced it.
     */
    @Nested
    @DisplayName("give target resolution")
    class TargetResolution {

        @Test
        @DisplayName("a bare name also tries the Floodgate '.' prefix")
        void bareNameTriesFloodgatePrefix() {
            assertEquals(List.of("carm", ".carm"),
                    ElectricFurnaceCommand.targetNameCandidates("carm"));
        }

        @Test
        @DisplayName("an already-prefixed name is not prefixed twice")
        void prefixedNameIsNotDoubled() {
            assertEquals(List.of(".acarm"),
                    ElectricFurnaceCommand.targetNameCandidates(".acarm"));
        }

        @Test
        @DisplayName("surrounding whitespace is trimmed")
        void whitespaceIsTrimmed() {
            assertEquals(List.of("carm", ".carm"),
                    ElectricFurnaceCommand.targetNameCandidates("  carm  "));
        }

        @Test
        @DisplayName("null and blank yield no candidates")
        void nullAndBlankYieldNothing() {
            assertTrue(ElectricFurnaceCommand.targetNameCandidates(null).isEmpty());
            assertTrue(ElectricFurnaceCommand.targetNameCandidates("   ").isEmpty());
        }

        @Test
        @DisplayName("the failure message lists who is actually online")
        void failureMessageListsOnlinePlayers() {
            String message = ElectricFurnaceCommand.noSuchPlayerMessage("carm",
                    List.of(".acarm", "Steve"));
            assertTrue(message.contains("carm"), message);
            assertTrue(message.contains(".acarm"), message);
            assertTrue(message.contains("Steve"), message);
        }

        @Test
        @DisplayName("the failure message says so plainly when nobody is online")
        void failureMessageWhenNobodyOnline() {
            String message = ElectricFurnaceCommand.noSuchPlayerMessage("carm", List.of());
            assertTrue(message.contains("carm"), message);
            assertTrue(message.toLowerCase(java.util.Locale.ROOT).contains("no players"), message);
        }
    }

    @Nested
    @DisplayName("machineInfoLines")
    class MachineInfoLines {

        private String joined(double multiplier, int smeltTicks, int burnTicks, boolean requireSignal) {
            return String.join("\n",
                    ElectricFurnaceCommand.machineInfoLines(multiplier, smeltTicks, burnTicks, requireSignal));
        }

        @Test
        @DisplayName("reports the multiplier alongside the seconds it actually works out to")
        void defaults_reportSpeedInTicksAndSeconds() {
            String out = joined(2.5D, 80, 200, true);

            assertTrue(out.contains("2.5x"), out);
            assertTrue(out.contains("80 ticks"), out);
            // 80 ticks is 4 seconds. The seconds figure is the point of the line: it is
            // what an operator compares against a vanilla furnace's 10s.
            assertTrue(out.contains("4.0s"), out);
        }

        @Test
        @DisplayName("derives items-per-dust, which no single config key states")
        void defaults_reportItemsPerDust() {
            // 200 burn ticks / 80 ticks per item = 2.5 items from one redstone dust.
            String out = joined(2.5D, 80, 200, true);

            assertTrue(out.contains("200 ticks per dust"), out);
            assertTrue(out.contains("2.5 items"), out);
        }

        @Test
        @DisplayName("items-per-dust tracks both keys, not just the burn setting")
        void slowerSmelt_yieldsFewerItemsPerDust() {
            // Same dust, half the speed: one dust must now buy half as many items.
            String fast = joined(2.5D, 80, 200, true);
            String slow = joined(1.25D, 160, 200, true);

            assertTrue(fast.contains("2.5 items"), fast);
            assertTrue(slow.contains("1.3 items"), slow);
        }

        @Test
        @DisplayName("a whole-number multiplier drops its trailing zero")
        void wholeMultiplier_rendersWithoutDecimal() {
            String out = joined(3.0D, 67, 200, true);

            assertTrue(out.contains("3x"), out);
            assertFalse(out.contains("3.0x"), out);
        }

        @Test
        @DisplayName("reports whether a redstone signal is required, both ways")
        void signalRequirement_isReportedBothWays() {
            assertTrue(joined(2.5D, 80, 200, true).contains("required"), "required case");

            String off = joined(2.5D, 80, 200, false);
            assertTrue(off.contains("not required"), off);
        }

        @Test
        @DisplayName("a zero smelt duration omits items-per-dust instead of dividing by zero")
        void zeroSmeltTicks_omitsItemsPerDustRatherThanThrowing() {
            // Double division by zero yields Infinity rather than throwing, so the
            // failure this guards against is "Infinity items" printed as though it were
            // a real number -- worse than showing nothing, because a player would read it.
            String out = joined(2.5D, 0, 200, true);

            assertTrue(out.contains("200 ticks per dust"), out);
            assertFalse(out.contains("items"), out);
        }
    }

    @Nested
    @DisplayName("alloyInfoLine")
    class AlloyInfoLine {

        private static final AlloyStats STATS = new AlloyStats(6.5D, -2.6D, 16, 1.0D, 700, 12);

        private static AlloyDefinition definition(String id, String displayName,
                                                  Set<String> inputs, GearBase base) {
            return new AlloyDefinition(id, displayName, List.of(), "#71797E", inputs, STATS, base);
        }

        @Test
        @DisplayName("names the configured base, not the alloy id's thematic default")
        void namesTheConfiguredBase() {
            // The whole point of the base being configurable: an operator who set
            // `steel: base: diamond` must see diamond here, not the iron default that
            // GearBase.defaultFor("steel") would hand back.
            String line = ElectricFurnaceCommand.alloyInfoLine(
                    definition("steel", "Steel", Set.of("iron", "coal"), GearBase.DIAMOND));

            assertTrue(line.contains("diamond base"), line);
            assertFalse(line.contains("iron base"), line);
        }

        @Test
        @DisplayName("the base is reported for every base, using the config token")
        void everyBaseIsReportedByItsConfigToken() {
            // The token must be the one an operator writes in config.yml -- gold, not
            // GOLD or GOLDEN (the material prefix), or the listing cannot be copied back
            // into `alloys.<id>.base`.
            for (GearBase base : GearBase.values()) {
                String line = ElectricFurnaceCommand.alloyInfoLine(
                        definition("steel", "Steel", Set.of("iron"), base));
                assertTrue(line.contains(base.id() + " base"),
                        "base " + base + " should render as its config token: " + line);
            }
        }

        @Test
        @DisplayName("the base does not appear as one of the recipe's inputs")
        void baseIsNotRenderedAsAnInput() {
            // The base sits before the arrow, the inputs after it. If it drifted to the
            // right of `<-` an operator would read it as a required ingredient.
            String line = ElectricFurnaceCommand.alloyInfoLine(
                    definition("rose_gold", "Rose Gold", Set.of("copper", "gold"), GearBase.NETHERITE));

            String afterArrow = line.substring(line.indexOf("<-"));
            assertFalse(afterArrow.contains("netherite"), afterArrow);
            assertTrue(afterArrow.contains("copper + gold"), afterArrow);
        }

        @Test
        @DisplayName("still reports the id, display name, and sorted inputs")
        void reportsIdDisplayNameAndSortedInputs() {
            String line = ElectricFurnaceCommand.alloyInfoLine(
                    definition("steel", "Steel", Set.of("iron", "coal"), GearBase.IRON));

            assertTrue(line.contains("steel"), line);
            assertTrue(line.contains("Steel"), line);
            // Set.of is unordered; the listing must not be, so this pins the sort.
            assertTrue(line.contains("coal + iron"), line);
        }

        @Test
        @DisplayName("the fallback describes its match rather than listing no inputs")
        void fallbackDescribesItsMatch() {
            String line = ElectricFurnaceCommand.alloyInfoLine(
                    definition("fused_alloy", "Fused Alloy", Set.of(), GearBase.NETHERITE));

            assertTrue(line.contains("any unmatched mix of metals"), line);
            assertTrue(line.contains("netherite base"), line);
        }
    }
}
