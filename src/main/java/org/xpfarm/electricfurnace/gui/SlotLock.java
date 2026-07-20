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
 * Decides whether a player may interact with a given GUI slot right now.
 *
 * <p>Pure, so the whole policy is pinned by a table test with no running server. The
 * listener's job is reduced to classifying a click into a
 * {@code (SlotRole, Action)} pair and asking this class.
 */
public final class SlotLock {

    private SlotLock() {
    }

    /** What the player is trying to do to a slot. */
    public enum Action {
        /** Put an item into the slot. */
        INSERT,
        /** Take an item out of the slot. */
        REMOVE
    }

    /**
     * Whether {@code action} is permitted on a slot of {@code role}.
     *
     * <p>Inputs lock against insertion as well as removal while a run is in progress:
     * changing the inputs mid-run would invalidate the recipe the run already resolved.
     * Fuel may be topped up but not withdrawn, so a player can refill a running machine
     * without being able to strand it. Output is always takable -- a completed ingot is
     * the player's, running or not -- and never accepts insertion.
     */
    public static boolean allows(GuiLayout.SlotRole role, Action action, boolean running) {
        return switch (role) {
            case INPUT -> !running;
            case FUEL -> action == Action.INSERT || !running;
            case OUTPUT -> action == Action.REMOVE;
            case FILLER, INDICATOR -> false;
        };
    }
}
