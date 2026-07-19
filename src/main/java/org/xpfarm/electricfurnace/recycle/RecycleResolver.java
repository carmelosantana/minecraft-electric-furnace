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

import org.xpfarm.electricfurnace.alloy.AlloyDefinition;
import org.xpfarm.electricfurnace.alloy.AlloyRegistry;
import org.xpfarm.electricfurnace.alloy.MetalType;
import org.xpfarm.electricfurnace.config.RecyclingSettings;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The heart of the plugin: given a list of recycler inputs, decides what comes out.
 *
 * <p><b>Entirely pure.</b> No {@code org.bukkit} type appears anywhere in this class
 * or its tests -- every input is a plain {@link RecycleInput}, every yield number
 * comes from the caller-supplied {@link RecyclingSettings}, never hardcoded here.
 *
 * <p>Resolution proceeds through eight rules, in this exact precedence order. Each
 * rule is checked only after every earlier rule has failed to apply:
 *
 * <ol>
 *   <li>Empty input -&gt; {@code REJECTED("empty")}.</li>
 *   <li>A single alloy item -&gt; {@code REMELT}. The <b>only</b> case that accepts
 *       fewer than {@code recycling.slots} items.</li>
 *   <li>Fewer than {@code recycling.slots} items (any composition) -&gt;
 *       {@code REJECTED("needs N items")}.</li>
 *   <li>Any input that is neither a metal nor a modifier -&gt;
 *       {@code REJECTED("non-metal input")}. This also catches an alloy item that is
 *       not alone (rule 2 already handled the sole-alloy case).</li>
 *   <li>Only modifiers, no metals at all (e.g. 5 coal) -&gt;
 *       {@code REJECTED("no metal")}. Coal alone is never recyclable.</li>
 *   <li>All metals identical <b>and no modifier present</b> -&gt; {@code SAME_METAL}.</li>
 *   <li>The input's distinct set of metal/modifier ids matches a named alloy recipe
 *       -&gt; {@code NAMED_ALLOY}.</li>
 *   <li>Otherwise -&gt; {@code GENERIC_ALLOY} (the registry's fallback recipe).</li>
 * </ol>
 *
 * <p><b>Rule 6 vs. rule 7, the subtlest interaction:</b> "4 iron + 1 coal" is
 * <em>not</em> all-same-metal, even though every metal present is iron -- the mere
 * presence of the coal modifier disqualifies rule 6 and routes the combination to
 * the named-recipe check in rule 7, where it matches Steel.
 *
 * <p>Rule 3 counts <em>every</em> input, modifiers included -- it is purely a slot
 * headcount, independent of composition. Composition-specific rejections are rules
 * 4 and 5, checked afterward.
 */
public final class RecycleResolver {

    private RecycleResolver() {
    }

    /**
     * Resolves one recycler operation.
     *
     * @param inputs   the items currently occupying the recycler input slots; a
     *                 {@code null} or empty list is rule 1 ({@code REJECTED("empty")})
     * @param settings validated {@code recycling} config; yields are read from here,
     *                 never hardcoded
     * @param alloys   the loaded named alloy recipes, including the generic fallback
     * @return the resolved outcome
     */
    public static RecycleResult resolve(List<RecycleInput> inputs, RecyclingSettings settings, AlloyRegistry alloys) {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(alloys, "alloys");

        // Rule 1: empty input.
        if (inputs == null || inputs.isEmpty()) {
            return new RecycleResult.Rejected("empty");
        }

        // Rule 2: a single alloy item remelts -- the only case accepting fewer than
        // `slots` items, so it must be checked before the slot-count rule below.
        if (inputs.size() == 1) {
            RecycleInput only = inputs.get(0);
            if (only.isAlloy()) {
                return new RecycleResult.Remelt(only.alloyId(), settings.yieldRemeltAlloy());
            }
        }

        // Rule 3: fewer than `slots` items, regardless of composition, is rejected.
        // This is a plain headcount over ALL inputs (metals and modifiers alike) --
        // composition is judged only afterward, by rules 4-8. A modifier still
        // occupies a slot, so "4 iron + 1 coal" at slots=5 passes this check.
        if (inputs.size() < settings.slots()) {
            return new RecycleResult.Rejected("needs " + settings.slots() + " items");
        }

        // Rule 4: every input must be either a metal or a modifier. An alloy item
        // that is not alone (rule 2 already returned for the sole-alloy case), or any
        // genuinely unrecognized item, trips this rule.
        boolean hasNonMetalNonModifier = inputs.stream()
                .anyMatch(input -> input.metal() == null && !input.isModifier());
        if (hasNonMetalNonModifier) {
            return new RecycleResult.Rejected("non-metal input");
        }

        // Rule 5: only modifiers, no metals at all -- coal alone is never recyclable.
        boolean anyMetal = inputs.stream().anyMatch(input -> input.metal() != null);
        if (!anyMetal) {
            return new RecycleResult.Rejected("no metal");
        }

        boolean anyModifier = inputs.stream().anyMatch(RecycleInput::isModifier);

        // Rule 6: all metals identical AND no modifier present.
        if (!anyModifier) {
            Set<MetalType> distinctMetals = inputs.stream()
                    .map(RecycleInput::metal)
                    .collect(Collectors.toSet());
            if (distinctMetals.size() == 1) {
                MetalType metal = distinctMetals.iterator().next();
                return new RecycleResult.SameMetal(metal, settings.yieldSameMetal());
            }
        }

        // Rule 7: named alloy recipe match, by the distinct set of present ids.
        Set<String> presentIds = inputs.stream()
                .map(RecycleResolver::idOf)
                .collect(Collectors.toSet());
        Optional<AlloyDefinition> namedMatch = alloys.findNamedMatch(presentIds);
        if (namedMatch.isPresent()) {
            return new RecycleResult.NamedAlloy(namedMatch.get().id(), settings.yieldMixedAlloy());
        }

        // Rule 8: otherwise, the generic fallback alloy.
        AlloyDefinition fallback = alloys.fallback();
        return new RecycleResult.GenericAlloy(fallback.id(), settings.yieldMixedAlloy());
    }

    /**
     * The id used for named-recipe matching: a metal's lowercase enum name (e.g.
     * {@code "iron"}) for metal inputs, or the raw {@code materialId} (e.g.
     * {@code "coal"}) for modifiers.
     */
    private static String idOf(RecycleInput input) {
        if (input.metal() != null) {
            return input.metal().name().toLowerCase(Locale.ROOT);
        }
        return input.materialId();
    }
}
