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

import org.xpfarm.electricfurnace.alloy.MetalType;

/**
 * The outcome of resolving a set of recycler inputs. Exactly one implementation is
 * produced per call to {@link RecycleResolver#resolve}.
 */
public sealed interface RecycleResult
        permits RecycleResult.SameMetal, RecycleResult.NamedAlloy, RecycleResult.GenericAlloy,
        RecycleResult.Remelt, RecycleResult.Rejected {

    /** Discriminator mirroring the sealed permits, for callers that prefer a switch on a plain enum. */
    enum Kind {
        SAME_METAL,
        NAMED_ALLOY,
        GENERIC_ALLOY,
        REMELT,
        REJECTED
    }

    Kind kind();

    /** All inputs were the same metal, with no modifier present. Yields {@code amount} ingots of {@code metal}. */
    record SameMetal(MetalType metal, int amount) implements RecycleResult {
        @Override
        public Kind kind() {
            return Kind.SAME_METAL;
        }
    }

    /** The input matched a named alloy recipe. Yields {@code amount} ingots of {@code alloyId}. */
    record NamedAlloy(String alloyId, int amount) implements RecycleResult {
        @Override
        public Kind kind() {
            return Kind.NAMED_ALLOY;
        }
    }

    /** The input was a mixed/unrecognized combination. Yields {@code amount} ingots of the generic fallback alloy. */
    record GenericAlloy(String alloyId, int amount) implements RecycleResult {
        @Override
        public Kind kind() {
            return Kind.GENERIC_ALLOY;
        }
    }

    /** A single alloy item was remelted. Yields {@code amount} ingots of {@code alloyId}. */
    record Remelt(String alloyId, int amount) implements RecycleResult {
        @Override
        public Kind kind() {
            return Kind.REMELT;
        }
    }

    /** The input could not be resolved. {@code reason} is a short machine-stable code, e.g. {@code "empty"}. */
    record Rejected(String reason) implements RecycleResult {
        @Override
        public Kind kind() {
            return Kind.REJECTED;
        }
    }
}
