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

/**
 * Validated {@code effects} settings section of {@code config.yml}.
 *
 * @param enabled      master switch for the particle/sound loop
 * @param periodTicks  how often, in ticks, the single global effects task runs (valid range 10-40)
 * @param playerRadius only players within this many blocks of a machine receive its effects
 *                     (valid range 8-128)
 * @param sound        name of the {@code Sound} to play each effect tick, or {@code null} if the
 *                     configured name did not resolve against the sound registry (sound disabled,
 *                     particles unaffected)
 */
public record EffectSettings(
        boolean enabled,
        int periodTicks,
        int playerRadius,
        String sound
) {
}
