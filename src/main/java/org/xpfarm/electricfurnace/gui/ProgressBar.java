/*
 * ElectricFurnace - a redstone-powered smelter that recycles metal gear into ingots.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.electricfurnace.gui;

/**
 * Renders smelt progress as lore text.
 *
 * <p>Text rather than an animated item model: lore updates on an open inventory render
 * correctly for Bedrock players through Geyser, while item-model driven progress
 * animation does not.
 */
public final class ProgressBar {

    /** Number of bar segments rendered. */
    public static final int SEGMENTS = 10;

    private static final char FILLED = '▰';
    private static final char EMPTY = '▱';

    private ProgressBar() {
    }

    /**
     * Renders {@code progressTicks} of {@code smeltTicks} as e.g. {@code "▰▰▰▰▰▱▱▱▱▱ 50%"}.
     *
     * <p>Always emits exactly {@link #SEGMENTS} segments, and never reports 100% -- a bar
     * reading 100% while the item has not appeared yet reads as a bug to a player. A
     * non-positive {@code smeltTicks} renders as empty rather than dividing by zero.
     */
    public static String render(int progressTicks, int smeltTicks) {
        int percent = 0;
        if (smeltTicks > 0 && progressTicks > 0) {
            percent = Math.min(99, (int) ((progressTicks * 100L) / smeltTicks));
        }
        int filled = Math.min(SEGMENTS, (percent * SEGMENTS) / 100);

        StringBuilder bar = new StringBuilder(SEGMENTS + 5);
        for (int i = 0; i < SEGMENTS; i++) {
            bar.append(i < filled ? FILLED : EMPTY);
        }
        return bar.append(' ').append(percent).append('%').toString();
    }
}
