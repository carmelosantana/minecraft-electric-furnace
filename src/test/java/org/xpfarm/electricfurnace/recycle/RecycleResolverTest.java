/*
 * ElectricFurnace - a redstone-powered smelter that recycles metal gear into ingots.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.electricfurnace.recycle;

import org.junit.jupiter.api.Test;
import org.xpfarm.electricfurnace.alloy.AlloyDefinition;
import org.xpfarm.electricfurnace.alloy.AlloyRegistry;
import org.xpfarm.electricfurnace.alloy.AlloyStats;
import org.xpfarm.electricfurnace.alloy.MetalType;
import org.xpfarm.electricfurnace.config.RecyclingSettings;
import org.xpfarm.electricfurnace.gear.GearBase;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for {@link RecycleResolver}. Deliberately imports nothing from
 * {@code org.bukkit} -- every input is a hand-built {@link RecycleInput}, and the
 * alloy recipes come from {@link AlloyRegistry#fromDefinitions}, which is itself
 * Bukkit-free.
 *
 * <p>Covers the eight resolution rules in their precedence order, plus the specific
 * scenarios called out in the plan -- most notably the rule 6 vs. rule 7 interaction:
 * a modifier's mere presence disqualifies "all same metal" even when every metal
 * input is identical, routing the combination to the named-recipe check instead.
 */
class RecycleResolverTest {

    /** Shipping defaults: slots=5, yield-same-metal=3, yield-mixed-alloy=2, yield-remelt-alloy=1. */
    private static final RecyclingSettings DEFAULT_SETTINGS = new RecyclingSettings(5, 3, 2, 1, true);

    private static final AlloyRegistry REGISTRY = shippingRegistry();

    // ---- Rule 1: empty input --------------------------------------------------------

    @Test
    void rule1_emptyInput_rejected() {
        RecycleResult result = RecycleResolver.resolve(List.of(), DEFAULT_SETTINGS, REGISTRY);

        RecycleResult.Rejected rejected = assertInstanceOf(RecycleResult.Rejected.class, result);
        assertEquals("empty", rejected.reason());
    }

    @Test
    void rule1_nullInput_treatedAsEmpty_rejected() {
        RecycleResult result = RecycleResolver.resolve(null, DEFAULT_SETTINGS, REGISTRY);

        RecycleResult.Rejected rejected = assertInstanceOf(RecycleResult.Rejected.class, result);
        assertEquals("empty", rejected.reason());
    }

    @Test
    void rule1_beatsRule2_emptyInputIsNotAVacuouslyAllAlloyRemelt() {
        // "Every input is an alloy" is vacuously true of no inputs at all, so rule 2's
        // all-alloy check would happily claim an empty recycler -- and then read element
        // zero of an empty list. Rule 1 must get there first.
        RecycleResult result = RecycleResolver.resolve(List.of(), DEFAULT_SETTINGS, REGISTRY);

        RecycleResult.Rejected rejected = assertInstanceOf(RecycleResult.Rejected.class, result);
        assertEquals("empty", rejected.reason());
    }

    // ---- Rule 2: all-alloy input remelts, the only exception to the slot count ------

    @Test
    void rule2_singleAlloyItem_remelts() {
        RecycleResult result = RecycleResolver.resolve(List.of(alloy("steel")), DEFAULT_SETTINGS, REGISTRY);

        RecycleResult.Remelt remelt = assertInstanceOf(RecycleResult.Remelt.class, result);
        assertEquals("steel", remelt.alloyId());
        assertEquals(1, remelt.amount());
    }

    @Test
    void rule2_fourPieceAlloyArmourSet_remeltsAtFourTimesTheYield() {
        List<RecycleInput> inputs = List.of(alloy("steel"), alloy("steel"), alloy("steel"), alloy("steel"));

        RecycleResult result = RecycleResolver.resolve(inputs, DEFAULT_SETTINGS, REGISTRY);

        RecycleResult.Remelt remelt = assertInstanceOf(RecycleResult.Remelt.class, result);
        assertEquals("steel", remelt.alloyId());
        assertEquals(4 * DEFAULT_SETTINGS.yieldRemeltAlloy(), remelt.amount());
    }

    @Test
    void rule2_remeltAmountIsItemCountTimesConfiguredYield_neitherAlone() {
        // yield 10, three items: 30 distinguishes `n * yield` from both a bare item count
        // and a bare per-item yield, which 4 * 1 = 4 under the shipping defaults cannot.
        RecyclingSettings settings = new RecyclingSettings(5, 30, 20, 10, true);
        List<RecycleInput> inputs = List.of(alloy("steel"), alloy("steel"), alloy("steel"));

        RecycleResult result = RecycleResolver.resolve(inputs, settings, REGISTRY);

        RecycleResult.Remelt remelt = assertInstanceOf(RecycleResult.Remelt.class, result);
        assertEquals(30, remelt.amount());
    }

    @Test
    void rule2_beatsRule3_twoAlloysBelowTheSlotCount_stillRemelt() {
        // Remelt remains the one path accepting fewer than `slots` items, so rule 2 must
        // stay ahead of the slot-count rule -- two items at slots=5 must not be rejected.
        List<RecycleInput> inputs = List.of(alloy("rose_gold"), alloy("rose_gold"));

        RecycleResult result = RecycleResolver.resolve(inputs, DEFAULT_SETTINGS, REGISTRY);

        RecycleResult.Remelt remelt = assertInstanceOf(RecycleResult.Remelt.class, result);
        assertEquals("rose_gold", remelt.alloyId());
        assertEquals(2, remelt.amount());
    }

    @Test
    void rule2_mixedAlloyIds_rejectedByName() {
        List<RecycleInput> inputs = List.of(alloy("steel"), alloy("rose_gold"));

        RecycleResult result = RecycleResolver.resolve(inputs, DEFAULT_SETTINGS, REGISTRY);

        RecycleResult.Rejected rejected = assertInstanceOf(RecycleResult.Rejected.class, result);
        assertEquals("mixed alloys", rejected.reason());
    }

    @Test
    void rule2_mixedAlloyIds_rejectedWhicheverIdComesFirst() {
        // A "take the first id and remelt" implementation would silently turn either of
        // these into a full batch of whichever alloy happened to lead the list.
        List<RecycleInput> steelFirst =
                List.of(alloy("steel"), alloy("steel"), alloy("steel"), alloy("steel"), alloy("rose_gold"));
        List<RecycleInput> roseGoldFirst =
                List.of(alloy("rose_gold"), alloy("steel"), alloy("steel"), alloy("steel"), alloy("steel"));

        RecycleResult.Rejected first = assertInstanceOf(RecycleResult.Rejected.class,
                RecycleResolver.resolve(steelFirst, DEFAULT_SETTINGS, REGISTRY));
        RecycleResult.Rejected second = assertInstanceOf(RecycleResult.Rejected.class,
                RecycleResolver.resolve(roseGoldFirst, DEFAULT_SETTINGS, REGISTRY));

        assertEquals("mixed alloys", first.reason());
        assertEquals("mixed alloys", second.reason());
    }

    @Test
    void rule2_mixedAlloyIdsBelowTheSlotCount_rejectAsMixed_notAsTooFewItems() {
        List<RecycleInput> inputs = List.of(alloy("steel"), alloy("ferrocopper"));

        RecycleResult result = RecycleResolver.resolve(inputs, DEFAULT_SETTINGS, REGISTRY);

        RecycleResult.Rejected rejected = assertInstanceOf(RecycleResult.Rejected.class, result);
        assertEquals("mixed alloys", rejected.reason());
    }

    @Test
    void acceptanceCheck_oneAlloyIn_yieldsOneIngotOut() {
        RecycleResult result = RecycleResolver.resolve(List.of(alloy("fused_alloy")), DEFAULT_SETTINGS, REGISTRY);

        RecycleResult.Remelt remelt = assertInstanceOf(RecycleResult.Remelt.class, result);
        assertEquals(1, remelt.amount());
    }

    // ---- Rule 3: fewer than `slots` items is rejected, regardless of composition ----

    @Test
    void rule3_fourItems_belowSlotCount_rejected() {
        List<RecycleInput> inputs = List.of(iron(), iron(), iron(), iron());

        RecycleResult result = RecycleResolver.resolve(inputs, DEFAULT_SETTINGS, REGISTRY);

        RecycleResult.Rejected rejected = assertInstanceOf(RecycleResult.Rejected.class, result);
        assertEquals("needs 5 items", rejected.reason());
    }

    @Test
    void rule3_belowSlotCount_appliesEvenToMixedComposition() {
        List<RecycleInput> inputs = List.of(iron(), iron(), gold(), coal());

        RecycleResult result = RecycleResolver.resolve(inputs, DEFAULT_SETTINGS, REGISTRY);

        assertInstanceOf(RecycleResult.Rejected.class, result);
    }

    // ---- Rule 4: any non-metal, non-modifier input rejects the whole batch ----------

    @Test
    void rule4_nonMetalInput_rejected() {
        List<RecycleInput> inputs = List.of(iron(), iron(), iron(), iron(), nonMetal("dirt"));

        RecycleResult result = RecycleResolver.resolve(inputs, DEFAULT_SETTINGS, REGISTRY);

        RecycleResult.Rejected rejected = assertInstanceOf(RecycleResult.Rejected.class, result);
        assertEquals("non-metal input", rejected.reason());
    }

    @Test
    void rule4_alloyItemMixedWithPlainMetal_isStillANonMetalInput() {
        // Rule 2 now remelts a batch of alloy items, but only when EVERY input is an
        // alloy. One alloy among plain ingots is not all-alloy, so it falls through to
        // rule 4, where an alloy item is neither a metal nor a modifier -- unchanged
        // behaviour, and the reason this rule keeps its "non-metal input" wording.
        List<RecycleInput> inputs = List.of(iron(), iron(), iron(), iron(), alloy("steel"));

        RecycleResult result = RecycleResolver.resolve(inputs, DEFAULT_SETTINGS, REGISTRY);

        RecycleResult.Rejected rejected = assertInstanceOf(RecycleResult.Rejected.class, result);
        assertEquals("non-metal input", rejected.reason());
    }

    @Test
    void rule4_severalAlloyItemsMixedWithPlainMetal_isStillANonMetalInput() {
        // Two alloys of the SAME id among ingots: still not all-alloy, so rule 2 must not
        // fire on the mere presence of alloys, nor on their sharing an id.
        List<RecycleInput> inputs = List.of(alloy("steel"), alloy("steel"), iron(), iron(), iron());

        RecycleResult result = RecycleResolver.resolve(inputs, DEFAULT_SETTINGS, REGISTRY);

        RecycleResult.Rejected rejected = assertInstanceOf(RecycleResult.Rejected.class, result);
        assertEquals("non-metal input", rejected.reason());
    }

    @Test
    void rule4_alloyItemsMixedWithAModifier_isStillANonMetalInput() {
        // Coal is a modifier, not an alloy, so alloys plus coal is not all-alloy either.
        List<RecycleInput> inputs = List.of(alloy("steel"), alloy("steel"), alloy("steel"), alloy("steel"), coal());

        RecycleResult result = RecycleResolver.resolve(inputs, DEFAULT_SETTINGS, REGISTRY);

        RecycleResult.Rejected rejected = assertInstanceOf(RecycleResult.Rejected.class, result);
        assertEquals("non-metal input", rejected.reason());
    }

    // ---- Rule 5: only modifiers, no metals ------------------------------------------

    @Test
    void rule5_fiveCoal_rejectedNoMetal() {
        List<RecycleInput> inputs = List.of(coal(), coal(), coal(), coal(), coal());

        RecycleResult result = RecycleResolver.resolve(inputs, DEFAULT_SETTINGS, REGISTRY);

        RecycleResult.Rejected rejected = assertInstanceOf(RecycleResult.Rejected.class, result);
        assertEquals("no metal", rejected.reason());
    }

    // ---- Rule 6: all metals identical AND no modifiers -> SAME_METAL ---------------

    @Test
    void rule6_fiveIron_sameMetal_yieldsConfiguredAmount() {
        List<RecycleInput> inputs = List.of(iron(), iron(), iron(), iron(), iron());

        RecycleResult result = RecycleResolver.resolve(inputs, DEFAULT_SETTINGS, REGISTRY);

        RecycleResult.SameMetal sameMetal = assertInstanceOf(RecycleResult.SameMetal.class, result);
        assertEquals(MetalType.IRON, sameMetal.metal());
        assertEquals(3, sameMetal.amount());
    }

    // ---- The subtlest interaction: rule 6 vs. rule 7 --------------------------------

    @Test
    void rule6vs7_fourIronPlusCoal_isNotSameMetal_matchesSteelInstead() {
        List<RecycleInput> inputs = List.of(iron(), iron(), iron(), iron(), coal());

        RecycleResult result = RecycleResolver.resolve(inputs, DEFAULT_SETTINGS, REGISTRY);

        // Must NOT be same-metal, even though every metal present is iron.
        assertTrue(result.kind() != RecycleResult.Kind.SAME_METAL,
                "a modifier's presence must disqualify the same-metal rule");
        RecycleResult.NamedAlloy namedAlloy = assertInstanceOf(RecycleResult.NamedAlloy.class, result);
        assertEquals("steel", namedAlloy.alloyId());
        assertEquals(2, namedAlloy.amount());
    }

    // ---- Rule 7: named alloy recipe match, order-independent ------------------------

    @Test
    void rule7_copperAndGold_matchesRoseGold() {
        List<RecycleInput> inputs = List.of(copper(), copper(), gold(), gold(), gold());

        RecycleResult result = RecycleResolver.resolve(inputs, DEFAULT_SETTINGS, REGISTRY);

        RecycleResult.NamedAlloy namedAlloy = assertInstanceOf(RecycleResult.NamedAlloy.class, result);
        assertEquals("rose_gold", namedAlloy.alloyId());
        assertEquals(2, namedAlloy.amount());
    }

    // ---- Rule 8: unrecognized mix -> generic Fused Alloy ----------------------------

    @Test
    void rule8_threeDistinctMetals_genericFusedAlloy() {
        List<RecycleInput> inputs = List.of(iron(), gold(), copper(), iron(), gold());

        RecycleResult result = RecycleResolver.resolve(inputs, DEFAULT_SETTINGS, REGISTRY);

        RecycleResult.GenericAlloy genericAlloy = assertInstanceOf(RecycleResult.GenericAlloy.class, result);
        assertEquals("fused_alloy", genericAlloy.alloyId());
        assertEquals(2, genericAlloy.amount());
    }

    // ---- Acceptance-check-shaped tests, matching the plan's explicit list -----------

    @Test
    void acceptanceCheck_fiveIron_yieldsThreeIronIngots() {
        List<RecycleInput> inputs = List.of(iron(), iron(), iron(), iron(), iron());

        RecycleResult result = RecycleResolver.resolve(inputs, DEFAULT_SETTINGS, REGISTRY);

        RecycleResult.SameMetal sameMetal = assertInstanceOf(RecycleResult.SameMetal.class, result);
        assertEquals(3, sameMetal.amount());
    }

    @Test
    void acceptanceCheck_fiveMixedMetals_yieldsTwoGenericFusedAlloy() {
        List<RecycleInput> inputs = List.of(iron(), gold(), copper(), netherite(), iron());

        RecycleResult result = RecycleResolver.resolve(inputs, DEFAULT_SETTINGS, REGISTRY);

        RecycleResult.GenericAlloy genericAlloy = assertInstanceOf(RecycleResult.GenericAlloy.class, result);
        assertEquals("fused_alloy", genericAlloy.alloyId());
        assertEquals(2, genericAlloy.amount());
    }

    @Test
    void acceptanceCheck_fourIronOneCoal_yieldsTwoSteel() {
        List<RecycleInput> inputs = List.of(iron(), iron(), iron(), iron(), coal());

        RecycleResult result = RecycleResolver.resolve(inputs, DEFAULT_SETTINGS, REGISTRY);

        RecycleResult.NamedAlloy namedAlloy = assertInstanceOf(RecycleResult.NamedAlloy.class, result);
        assertEquals("steel", namedAlloy.alloyId());
        assertEquals(2, namedAlloy.amount());
    }

    @Test
    void acceptanceCheck_fiveCoal_rejected() {
        List<RecycleInput> inputs = List.of(coal(), coal(), coal(), coal(), coal());

        RecycleResult result = RecycleResolver.resolve(inputs, DEFAULT_SETTINGS, REGISTRY);

        assertInstanceOf(RecycleResult.Rejected.class, result);
    }

    @Test
    void acceptanceCheck_oneAlloy_yieldsOneIngot() {
        RecycleResult result = RecycleResolver.resolve(List.of(alloy("rose_gold")), DEFAULT_SETTINGS, REGISTRY);

        RecycleResult.Remelt remelt = assertInstanceOf(RecycleResult.Remelt.class, result);
        assertEquals(1, remelt.amount());
    }

    @Test
    void acceptanceCheck_fourItems_rejectedBelowSlotCount() {
        List<RecycleInput> inputs = List.of(iron(), iron(), iron(), iron());

        RecycleResult result = RecycleResolver.resolve(inputs, DEFAULT_SETTINGS, REGISTRY);

        assertInstanceOf(RecycleResult.Rejected.class, result);
    }

    @Test
    void acceptanceCheck_yieldsTrackConfig_notHardcodedNumbers() {
        RecyclingSettings customSettings = new RecyclingSettings(5, 30, 20, 10, true);
        List<RecycleInput> sameMetalInputs = List.of(iron(), iron(), iron(), iron(), iron());
        List<RecycleInput> namedAlloyInputs = List.of(iron(), iron(), iron(), iron(), coal());
        List<RecycleInput> genericInputs = List.of(iron(), gold(), copper(), iron(), gold());
        List<RecycleInput> remeltInputs = List.of(alloy("steel"));

        RecycleResult.SameMetal sameMetal = assertInstanceOf(RecycleResult.SameMetal.class,
                RecycleResolver.resolve(sameMetalInputs, customSettings, REGISTRY));
        RecycleResult.NamedAlloy namedAlloy = assertInstanceOf(RecycleResult.NamedAlloy.class,
                RecycleResolver.resolve(namedAlloyInputs, customSettings, REGISTRY));
        RecycleResult.GenericAlloy genericAlloy = assertInstanceOf(RecycleResult.GenericAlloy.class,
                RecycleResolver.resolve(genericInputs, customSettings, REGISTRY));
        RecycleResult.Remelt remelt = assertInstanceOf(RecycleResult.Remelt.class,
                RecycleResolver.resolve(remeltInputs, customSettings, REGISTRY));

        assertEquals(30, sameMetal.amount());
        assertEquals(20, namedAlloy.amount());
        assertEquals(20, genericAlloy.amount());
        assertEquals(10, remelt.amount());
    }

    // ---- Rule 9: a zero computed yield rejects rather than destroying the inputs ------

    @Test
    void rule9_zeroRemeltYield_rejected_ratherThanConsumingEveryAlloyItem() {
        // yield-remelt-alloy: 0 is inside the documented 0-64 range. Without this rule the
        // downstream machine deposits an empty stack and still consumes every input slot,
        // silently destroying up to `slots` alloy items.
        RecyclingSettings settings = new RecyclingSettings(5, 3, 2, 0, true);
        List<RecycleInput> inputs = List.of(alloy("steel"), alloy("steel"), alloy("steel"),
                alloy("steel"), alloy("steel"));

        RecycleResult result = RecycleResolver.resolve(inputs, settings, REGISTRY);

        RecycleResult.Rejected rejected = assertInstanceOf(RecycleResult.Rejected.class, result);
        assertEquals("zero yield", rejected.reason());
    }

    @Test
    void rule9_zeroRemeltYield_rejectsASingleAlloyItemToo() {
        RecyclingSettings settings = new RecyclingSettings(5, 3, 2, 0, true);

        RecycleResult result = RecycleResolver.resolve(List.of(alloy("rose_gold")), settings, REGISTRY);

        RecycleResult.Rejected rejected = assertInstanceOf(RecycleResult.Rejected.class, result);
        assertEquals("zero yield", rejected.reason());
    }

    @Test
    void rule9_zeroSameMetalYield_rejected() {
        RecyclingSettings settings = new RecyclingSettings(5, 0, 2, 1, true);
        List<RecycleInput> inputs = List.of(iron(), iron(), iron(), iron(), iron());

        RecycleResult result = RecycleResolver.resolve(inputs, settings, REGISTRY);

        RecycleResult.Rejected rejected = assertInstanceOf(RecycleResult.Rejected.class, result);
        assertEquals("zero yield", rejected.reason());
    }

    @Test
    void rule9_zeroMixedAlloyYield_rejectsANamedAlloyMatch() {
        RecyclingSettings settings = new RecyclingSettings(5, 3, 0, 1, true);
        List<RecycleInput> inputs = List.of(iron(), iron(), iron(), iron(), coal());

        RecycleResult result = RecycleResolver.resolve(inputs, settings, REGISTRY);

        RecycleResult.Rejected rejected = assertInstanceOf(RecycleResult.Rejected.class, result);
        assertEquals("zero yield", rejected.reason());
    }

    @Test
    void rule9_zeroMixedAlloyYield_rejectsTheGenericFallbackToo() {
        RecyclingSettings settings = new RecyclingSettings(5, 3, 0, 1, true);
        List<RecycleInput> inputs = List.of(iron(), gold(), copper(), iron(), gold());

        RecycleResult result = RecycleResolver.resolve(inputs, settings, REGISTRY);

        RecycleResult.Rejected rejected = assertInstanceOf(RecycleResult.Rejected.class, result);
        assertEquals("zero yield", rejected.reason());
    }

    @Test
    void rule9_zeroYieldOnOneOutcomeDoesNotRejectTheOthers() {
        // Only the yield that is actually consulted for this input may reject it: zeroing
        // the remelt yield must leave same-metal and alloy recycling working normally.
        RecyclingSettings settings = new RecyclingSettings(5, 3, 2, 0, true);

        RecycleResult.SameMetal sameMetal = assertInstanceOf(RecycleResult.SameMetal.class,
                RecycleResolver.resolve(List.of(iron(), iron(), iron(), iron(), iron()), settings, REGISTRY));
        RecycleResult.NamedAlloy namedAlloy = assertInstanceOf(RecycleResult.NamedAlloy.class,
                RecycleResolver.resolve(List.of(iron(), iron(), iron(), iron(), coal()), settings, REGISTRY));

        assertEquals(3, sameMetal.amount());
        assertEquals(2, namedAlloy.amount());
    }

    @Test
    void rule9_aPositiveYieldIsStillAccepted_atTheSmallestNonZeroValue() {
        // Guards against a `<= 0` check drifting to `< 1`'s neighbour `<= 1`.
        RecyclingSettings settings = new RecyclingSettings(5, 1, 1, 1, true);

        RecycleResult.SameMetal sameMetal = assertInstanceOf(RecycleResult.SameMetal.class,
                RecycleResolver.resolve(List.of(iron(), iron(), iron(), iron(), iron()), settings, REGISTRY));
        RecycleResult.Remelt remelt = assertInstanceOf(RecycleResult.Remelt.class,
                RecycleResolver.resolve(List.of(alloy("steel")), settings, REGISTRY));

        assertEquals(1, sameMetal.amount());
        assertEquals(1, remelt.amount());
    }

    @Test
    void rule9_zeroYieldRejectionBeatsNothingEarlier_mixedAlloysStillRejectAsMixed() {
        // The zero-yield guard sits inside each outcome, so an input that never reaches an
        // outcome keeps its own, more specific rejection reason.
        RecyclingSettings settings = new RecyclingSettings(5, 0, 0, 0, true);

        RecycleResult.Rejected mixed = assertInstanceOf(RecycleResult.Rejected.class,
                RecycleResolver.resolve(List.of(alloy("steel"), alloy("rose_gold")), settings, REGISTRY));
        RecycleResult.Rejected empty = assertInstanceOf(RecycleResult.Rejected.class,
                RecycleResolver.resolve(List.of(), settings, REGISTRY));
        RecycleResult.Rejected noMetal = assertInstanceOf(RecycleResult.Rejected.class,
                RecycleResolver.resolve(List.of(coal(), coal(), coal(), coal(), coal()), settings, REGISTRY));

        assertEquals("mixed alloys", mixed.reason());
        assertEquals("empty", empty.reason());
        assertEquals("no metal", noMetal.reason());
    }

    // ---- Test fixtures ---------------------------------------------------------------

    private static RecycleInput iron() {
        return new RecycleInput("iron_ingot", MetalType.IRON, false, false, null, 1);
    }

    private static RecycleInput gold() {
        return new RecycleInput("gold_ingot", MetalType.GOLD, false, false, null, 1);
    }

    private static RecycleInput copper() {
        return new RecycleInput("copper_ingot", MetalType.COPPER, false, false, null, 1);
    }

    private static RecycleInput netherite() {
        return new RecycleInput("netherite_ingot", MetalType.NETHERITE, false, false, null, 1);
    }

    private static RecycleInput coal() {
        return new RecycleInput("coal", null, true, false, null, 0);
    }

    private static RecycleInput nonMetal(String materialId) {
        return new RecycleInput(materialId, null, false, false, null, 0);
    }

    private static RecycleInput alloy(String alloyId) {
        return new RecycleInput(alloyId, null, false, true, alloyId, 1);
    }

    private static AlloyRegistry shippingRegistry() {
        List<String> warnings = new ArrayList<>();
        AlloyStats basicStats = new AlloyStats(6.5, -2.6, 16, 1.0, 700, 12);
        List<AlloyDefinition> definitions = List.of(
                new AlloyDefinition("steel", "Steel", List.of("A carbon-hardened iron alloy."),
                        "#71797E", Set.of("iron", "coal"), basicStats, GearBase.IRON),
                new AlloyDefinition("rose_gold", "Rose Gold", List.of("A warm copper-gold blend."),
                        "#B76E79", Set.of("copper", "gold"), basicStats, GearBase.GOLD),
                new AlloyDefinition("ferrocopper", "Ferrocopper", List.of("Copper toughened with iron."),
                        "#B87333", Set.of("copper", "iron"), basicStats, GearBase.COPPER),
                new AlloyDefinition("electrum_steel", "Electrum Steel", List.of("Gold-veined structural steel."),
                        "#D4C9A8", Set.of("gold", "iron"), basicStats, GearBase.DIAMOND),
                new AlloyDefinition("fused_alloy", "Fused Alloy", List.of("An unrefined fusion of leftover metals."),
                        "#4B4B4B", Set.of(), basicStats, GearBase.NETHERITE)
        );
        return AlloyRegistry.fromDefinitions(definitions, warnings::add);
    }
}
