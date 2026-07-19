/*
 * ElectricFurnace - a redstone-powered smelter that recycles metal gear into ingots.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.electricfurnace.listener;

import org.bukkit.event.inventory.InventoryAction;
import org.junit.jupiter.api.Test;
import org.xpfarm.electricfurnace.gui.GuiLayout;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for {@link MachineGuiListener}'s slot-guard decision.
 *
 * <p>{@link InventoryAction} is a plain Bukkit enum -- like {@code Material} in
 * {@code MetalClassifierTest} -- referencing its constants requires no running
 * server, which is exactly what lets {@link #classify_everyActionIsHandled} and
 * {@link #shouldCancel_everyRoleActionCombination} exhaustively cover every
 * combination without a live server, following the same
 * {@code MetalClassifier.resolveBranch} exhaustive-combination pattern.
 *
 * <p>The most important behavior under test: any click that would PLACE an item
 * into the output slot must be cancelled, and any click at all on FILLER or
 * INDICATOR must be cancelled -- regardless of click type (plain click, shift-click,
 * hotbar swap, double-click collect).
 */
class MachineGuiListenerTest {

    // ---- classify(InventoryAction): the click -> (isPlace, isTake) mapping ----------

    @Test
    void classify_plainPickup_isTakeOnly() {
        for (InventoryAction action : new InventoryAction[] {
                InventoryAction.PICKUP_ALL, InventoryAction.PICKUP_SOME,
                InventoryAction.PICKUP_HALF, InventoryAction.PICKUP_ONE}) {
            MachineGuiListener.ClickEffect effect = MachineGuiListener.classify(action);
            assertFalse(effect.isPlace(), action + " should not place");
            assertTrue(effect.isTake(), action + " should take");
        }
    }

    @Test
    void classify_plainPlace_isPlaceOnly() {
        for (InventoryAction action : new InventoryAction[] {
                InventoryAction.PLACE_ALL, InventoryAction.PLACE_SOME, InventoryAction.PLACE_ONE}) {
            MachineGuiListener.ClickEffect effect = MachineGuiListener.classify(action);
            assertTrue(effect.isPlace(), action + " should place");
            assertFalse(effect.isTake(), action + " should not take");
        }
    }

    @Test
    void classify_swapAndHotbarActions_areBothPlaceAndTake() {
        for (InventoryAction action : new InventoryAction[] {
                InventoryAction.SWAP_WITH_CURSOR, InventoryAction.HOTBAR_SWAP,
                InventoryAction.HOTBAR_MOVE_AND_READD}) {
            MachineGuiListener.ClickEffect effect = MachineGuiListener.classify(action);
            assertTrue(effect.isPlace(), action + " should place");
            assertTrue(effect.isTake(), action + " should take");
        }
    }

    @Test
    void classify_shiftClickOut_isTakeOnly() {
        MachineGuiListener.ClickEffect effect = MachineGuiListener.classify(InventoryAction.MOVE_TO_OTHER_INVENTORY);
        assertFalse(effect.isPlace());
        assertTrue(effect.isTake());
    }

    @Test
    void classify_doubleClickCollect_isTakeOnly() {
        MachineGuiListener.ClickEffect effect = MachineGuiListener.classify(InventoryAction.COLLECT_TO_CURSOR);
        assertFalse(effect.isPlace());
        assertTrue(effect.isTake());
    }

    @Test
    void classify_dropFromSlot_isTakeOnly() {
        for (InventoryAction action : new InventoryAction[] {InventoryAction.DROP_ALL_SLOT, InventoryAction.DROP_ONE_SLOT}) {
            MachineGuiListener.ClickEffect effect = MachineGuiListener.classify(action);
            assertFalse(effect.isPlace());
            assertTrue(effect.isTake());
        }
    }

    @Test
    void classify_nothingAndCursorDrops_touchNoSlot() {
        for (InventoryAction action : new InventoryAction[] {
                InventoryAction.NOTHING, InventoryAction.DROP_ALL_CURSOR,
                InventoryAction.DROP_ONE_CURSOR, InventoryAction.CLONE_STACK}) {
            MachineGuiListener.ClickEffect effect = MachineGuiListener.classify(action);
            assertFalse(effect.isPlace(), action + " should not place");
            assertFalse(effect.isTake(), action + " should not take");
        }
    }

    @Test
    void classify_unknownAndBundleActions_areConservativelyBoth() {
        // Fail-safe: an action whose exact item-movement direction is not worth
        // getting wrong is treated as capable of both placing and taking, so the
        // guard still protects FILLER/INDICATOR/OUTPUT regardless.
        for (InventoryAction action : new InventoryAction[] {
                InventoryAction.UNKNOWN, InventoryAction.PICKUP_FROM_BUNDLE,
                InventoryAction.PICKUP_ALL_INTO_BUNDLE, InventoryAction.PICKUP_SOME_INTO_BUNDLE,
                InventoryAction.PLACE_FROM_BUNDLE, InventoryAction.PLACE_ALL_INTO_BUNDLE,
                InventoryAction.PLACE_SOME_INTO_BUNDLE}) {
            MachineGuiListener.ClickEffect effect = MachineGuiListener.classify(action);
            assertTrue(effect.isPlace(), action + " should conservatively place");
            assertTrue(effect.isTake(), action + " should conservatively take");
        }
    }

    @Test
    void classify_everyActionIsHandled() {
        // Exhaustive: classify() must never throw for any InventoryAction constant,
        // present or future-proofed by the exhaustive switch's compile-time check.
        for (InventoryAction action : InventoryAction.values()) {
            MachineGuiListener.ClickEffect effect = MachineGuiListener.classify(action);
            assertEquals(effect, effect); // merely proves classify() didn't throw
        }
    }

    // ---- shouldCancel(role, isPlace, isTake): the core guard decision ---------------

    @Test
    void filler_alwaysCancelled_regardlessOfPlaceOrTake() {
        assertTrue(MachineGuiListener.shouldCancel(GuiLayout.SlotRole.FILLER, true, false));
        assertTrue(MachineGuiListener.shouldCancel(GuiLayout.SlotRole.FILLER, false, true));
        assertTrue(MachineGuiListener.shouldCancel(GuiLayout.SlotRole.FILLER, false, false));
        assertTrue(MachineGuiListener.shouldCancel(GuiLayout.SlotRole.FILLER, true, true));
    }

    @Test
    void indicator_alwaysCancelled_regardlessOfPlaceOrTake() {
        assertTrue(MachineGuiListener.shouldCancel(GuiLayout.SlotRole.INDICATOR, true, false));
        assertTrue(MachineGuiListener.shouldCancel(GuiLayout.SlotRole.INDICATOR, false, true));
        assertTrue(MachineGuiListener.shouldCancel(GuiLayout.SlotRole.INDICATOR, false, false));
        assertTrue(MachineGuiListener.shouldCancel(GuiLayout.SlotRole.INDICATOR, true, true));
    }

    @Test
    void output_cancelledOnlyWhenPlacing() {
        assertTrue(MachineGuiListener.shouldCancel(GuiLayout.SlotRole.OUTPUT, true, false),
                "placing into output must be cancelled");
        assertTrue(MachineGuiListener.shouldCancel(GuiLayout.SlotRole.OUTPUT, true, true),
                "a swap that places into output must be cancelled even though it also takes");
        assertFalse(MachineGuiListener.shouldCancel(GuiLayout.SlotRole.OUTPUT, false, true),
                "taking from output must be allowed");
        assertFalse(MachineGuiListener.shouldCancel(GuiLayout.SlotRole.OUTPUT, false, false));
    }

    @Test
    void input_neverCancelledByThisGuard() {
        assertFalse(MachineGuiListener.shouldCancel(GuiLayout.SlotRole.INPUT, true, false));
        assertFalse(MachineGuiListener.shouldCancel(GuiLayout.SlotRole.INPUT, false, true));
        assertFalse(MachineGuiListener.shouldCancel(GuiLayout.SlotRole.INPUT, false, false));
        assertFalse(MachineGuiListener.shouldCancel(GuiLayout.SlotRole.INPUT, true, true));
    }

    @Test
    void fuel_neverCancelledByThisGuard() {
        assertFalse(MachineGuiListener.shouldCancel(GuiLayout.SlotRole.FUEL, true, false));
        assertFalse(MachineGuiListener.shouldCancel(GuiLayout.SlotRole.FUEL, false, true));
        assertFalse(MachineGuiListener.shouldCancel(GuiLayout.SlotRole.FUEL, false, false));
        assertFalse(MachineGuiListener.shouldCancel(GuiLayout.SlotRole.FUEL, true, true));
    }

    @Test
    void shouldCancel_everyRoleAndPlaceTakeCombination() {
        for (GuiLayout.SlotRole role : GuiLayout.SlotRole.values()) {
            for (boolean isPlace : new boolean[] {true, false}) {
                for (boolean isTake : new boolean[] {true, false}) {
                    boolean expected = switch (role) {
                        case FILLER, INDICATOR -> true;
                        case OUTPUT -> isPlace;
                        case INPUT, FUEL -> false;
                    };
                    assertEquals(expected, MachineGuiListener.shouldCancel(role, isPlace, isTake),
                            () -> "role=" + role + " isPlace=" + isPlace + " isTake=" + isTake);
                }
            }
        }
    }

    // ---- shouldCancel(role, action): the composed, action-level guard --------------

    @Test
    void shouldCancel_everyRoleActionCombination() {
        for (GuiLayout.SlotRole role : GuiLayout.SlotRole.values()) {
            for (InventoryAction action : InventoryAction.values()) {
                MachineGuiListener.ClickEffect effect = MachineGuiListener.classify(action);
                boolean expected = MachineGuiListener.shouldCancel(role, effect.isPlace(), effect.isTake());
                assertEquals(expected, MachineGuiListener.shouldCancel(role, action),
                        () -> "role=" + role + " action=" + action);
            }
        }
    }

    @Test
    void placingIntoOutput_viaPlaceAll_isCancelled() {
        assertTrue(MachineGuiListener.shouldCancel(GuiLayout.SlotRole.OUTPUT, InventoryAction.PLACE_ALL));
    }

    @Test
    void takingFromOutput_viaShiftClick_isAllowed() {
        assertFalse(MachineGuiListener.shouldCancel(GuiLayout.SlotRole.OUTPUT, InventoryAction.MOVE_TO_OTHER_INVENTORY));
    }

    @Test
    void hotbarSwapIntoOutput_isCancelled() {
        assertTrue(MachineGuiListener.shouldCancel(GuiLayout.SlotRole.OUTPUT, InventoryAction.HOTBAR_SWAP));
    }

    @Test
    void clickingFiller_withAnyAction_isCancelled() {
        for (InventoryAction action : InventoryAction.values()) {
            assertTrue(MachineGuiListener.shouldCancel(GuiLayout.SlotRole.FILLER, action),
                    "filler + " + action + " should always cancel");
        }
    }

    // ---- shouldCancelDrag(Set<SlotRole>): drag spanning multiple slots --------------

    @Test
    void drag_touchingOnlyInputAndFuel_isAllowed() {
        assertFalse(MachineGuiListener.shouldCancelDrag(Set.of(GuiLayout.SlotRole.INPUT, GuiLayout.SlotRole.FUEL)));
    }

    @Test
    void drag_touchingOutput_isCancelled() {
        assertTrue(MachineGuiListener.shouldCancelDrag(Set.of(GuiLayout.SlotRole.INPUT, GuiLayout.SlotRole.OUTPUT)));
    }

    @Test
    void drag_touchingFiller_isCancelled() {
        assertTrue(MachineGuiListener.shouldCancelDrag(Set.of(GuiLayout.SlotRole.FILLER)));
    }

    @Test
    void drag_touchingIndicator_isCancelled() {
        assertTrue(MachineGuiListener.shouldCancelDrag(Set.of(GuiLayout.SlotRole.INDICATOR)));
    }

    @Test
    void drag_touchingNoTopSlots_isAllowed() {
        assertFalse(MachineGuiListener.shouldCancelDrag(Set.of()));
    }

    @Test
    void drag_everyNonEmptySubsetOfRoles_matchesContainsAnyGuardedRole() {
        Set<GuiLayout.SlotRole> allRoles = EnumSet.allOf(GuiLayout.SlotRole.class);
        // Iterate every subset of the 5 roles (2^5 = 32) via a bitmask.
        GuiLayout.SlotRole[] roles = allRoles.toArray(new GuiLayout.SlotRole[0]);
        for (int mask = 0; mask < (1 << roles.length); mask++) {
            Set<GuiLayout.SlotRole> subset = new HashSet<>();
            for (int i = 0; i < roles.length; i++) {
                if ((mask & (1 << i)) != 0) {
                    subset.add(roles[i]);
                }
            }
            boolean expected = subset.contains(GuiLayout.SlotRole.FILLER)
                    || subset.contains(GuiLayout.SlotRole.INDICATOR)
                    || subset.contains(GuiLayout.SlotRole.OUTPUT);
            assertEquals(expected, MachineGuiListener.shouldCancelDrag(subset), "subset=" + subset);
        }
    }
}
