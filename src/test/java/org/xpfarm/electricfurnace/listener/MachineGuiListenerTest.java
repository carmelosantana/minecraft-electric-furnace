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
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        // Exhaustive: classify() must return a non-null effect for every
        // InventoryAction constant -- no action falls through unclassified.
        for (InventoryAction action : InventoryAction.values()) {
            assertNotNull(MachineGuiListener.classify(action), action + " must be classified");
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

    /**
     * The full truth table for {@code shouldCancel(role, isPlace, isTake)}, written out
     * by hand rather than derived from the implementation's own expression.
     *
     * <p>Each row is {@code {role, isPlace, isTake, expectedCancel}}. Restating the
     * implementation formula as the "expected" value would make this test pass against
     * any implementation matching that restated formula -- including a wrong one -- so
     * every one of the 20 outcomes is spelled out literally instead.
     */
    private static final Object[][] SHOULD_CANCEL_TABLE = {
            {GuiLayout.SlotRole.FILLER, false, false, true},
            {GuiLayout.SlotRole.FILLER, false, true, true},
            {GuiLayout.SlotRole.FILLER, true, false, true},
            {GuiLayout.SlotRole.FILLER, true, true, true},

            {GuiLayout.SlotRole.INDICATOR, false, false, true},
            {GuiLayout.SlotRole.INDICATOR, false, true, true},
            {GuiLayout.SlotRole.INDICATOR, true, false, true},
            {GuiLayout.SlotRole.INDICATOR, true, true, true},

            {GuiLayout.SlotRole.OUTPUT, false, false, false},
            {GuiLayout.SlotRole.OUTPUT, false, true, false},
            {GuiLayout.SlotRole.OUTPUT, true, false, true},
            {GuiLayout.SlotRole.OUTPUT, true, true, true},

            {GuiLayout.SlotRole.INPUT, false, false, false},
            {GuiLayout.SlotRole.INPUT, false, true, false},
            {GuiLayout.SlotRole.INPUT, true, false, false},
            {GuiLayout.SlotRole.INPUT, true, true, false},

            {GuiLayout.SlotRole.FUEL, false, false, false},
            {GuiLayout.SlotRole.FUEL, false, true, false},
            {GuiLayout.SlotRole.FUEL, true, false, false},
            {GuiLayout.SlotRole.FUEL, true, true, false},
    };

    @Test
    void shouldCancel_matchesTheHandWrittenTruthTable() {
        for (Object[] row : SHOULD_CANCEL_TABLE) {
            GuiLayout.SlotRole role = (GuiLayout.SlotRole) row[0];
            boolean isPlace = (Boolean) row[1];
            boolean isTake = (Boolean) row[2];
            boolean expected = (Boolean) row[3];
            assertEquals(expected, MachineGuiListener.shouldCancel(role, isPlace, isTake),
                    () -> "role=" + role + " isPlace=" + isPlace + " isTake=" + isTake);
        }
    }

    @Test
    void shouldCancel_truthTableCoversEveryRoleAndCombination() {
        // Guards the table above against silently going stale if a SlotRole is added.
        assertEquals(GuiLayout.SlotRole.values().length * 4, SHOULD_CANCEL_TABLE.length);
    }

    // ---- shouldCancel(role, action): the composed, action-level guard --------------

    /**
     * Every {@link InventoryAction} that places into the clicked slot, listed
     * independently of {@link MachineGuiListener#classify}. The action-level guard is
     * checked against this hand-maintained list rather than against {@code classify}'s
     * own output, so a mistake in {@code classify} shows up as a failure here instead of
     * being mirrored into the expectation.
     */
    private static final Set<InventoryAction> PLACING_ACTIONS = EnumSet.of(
            InventoryAction.PLACE_ALL, InventoryAction.PLACE_SOME, InventoryAction.PLACE_ONE,
            InventoryAction.SWAP_WITH_CURSOR, InventoryAction.HOTBAR_SWAP, InventoryAction.HOTBAR_MOVE_AND_READD,
            InventoryAction.UNKNOWN, InventoryAction.PICKUP_FROM_BUNDLE,
            InventoryAction.PICKUP_ALL_INTO_BUNDLE, InventoryAction.PICKUP_SOME_INTO_BUNDLE,
            InventoryAction.PLACE_FROM_BUNDLE, InventoryAction.PLACE_ALL_INTO_BUNDLE,
            InventoryAction.PLACE_SOME_INTO_BUNDLE);

    @Test
    void shouldCancel_everyRoleActionCombination() {
        for (GuiLayout.SlotRole role : GuiLayout.SlotRole.values()) {
            for (InventoryAction action : InventoryAction.values()) {
                boolean expected = switch (role) {
                    case FILLER, INDICATOR -> true;
                    case OUTPUT -> PLACING_ACTIONS.contains(action);
                    case INPUT, FUEL -> false;
                };
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

    // ---- C1 regression: view-wide double-click collect ------------------------------

    @Test
    void collectToCursor_isCancelledViewWide() {
        // Regression for the infinite-filler-pane / infinite-indicator duplication bug.
        // COLLECT_TO_CURSOR vacuums matching stacks from the ENTIRE view, including top
        // slots never clicked, so it must be cancelled purely on "the top inventory is a
        // furnace GUI" -- before any per-slot or which-inventory-was-clicked reasoning.
        assertTrue(MachineGuiListener.shouldCancelViewWide(InventoryAction.COLLECT_TO_CURSOR));
    }

    @Test
    void collectToCursor_isTheOnlyViewWideCancelledAction() {
        for (InventoryAction action : InventoryAction.values()) {
            boolean expected = action == InventoryAction.COLLECT_TO_CURSOR;
            assertEquals(expected, MachineGuiListener.shouldCancelViewWide(action),
                    () -> "action=" + action);
        }
    }

    @Test
    void collectToCursor_wouldNotBeCaughtByThePerSlotGuardAlone() {
        // Pins exactly why the view-wide guard has to exist: the per-slot guard lets
        // COLLECT_TO_CURSOR through on INPUT and FUEL (it is classified take-only), and
        // the clicked slot during the exploit is in the player's own inventory anyway.
        assertFalse(MachineGuiListener.shouldCancel(GuiLayout.SlotRole.INPUT, InventoryAction.COLLECT_TO_CURSOR));
        assertFalse(MachineGuiListener.shouldCancel(GuiLayout.SlotRole.FUEL, InventoryAction.COLLECT_TO_CURSOR));
        assertFalse(MachineGuiListener.shouldCancel(GuiLayout.SlotRole.OUTPUT, InventoryAction.COLLECT_TO_CURSOR));
    }

    // ---- I4: manual shift-click routing --------------------------------------------

    @Test
    void shiftTarget_redstone_goesToFuel() {
        assertEquals(MachineGuiListener.ShiftTarget.FUEL, MachineGuiListener.shiftTargetOf(true, false));
    }

    @Test
    void shiftTarget_recyclableMetal_goesToInput() {
        assertEquals(MachineGuiListener.ShiftTarget.INPUT, MachineGuiListener.shiftTargetOf(false, true));
    }

    @Test
    void shiftTarget_unrelatedItem_goesNowhere() {
        assertEquals(MachineGuiListener.ShiftTarget.NONE, MachineGuiListener.shiftTargetOf(false, false));
    }

    @Test
    void shiftTarget_redstoneWinsOverRecyclable() {
        // Redstone is fuel first: if anything ever classified it as recyclable too, a
        // player shift-clicking redstone must still get fuel, not an input slot.
        assertEquals(MachineGuiListener.ShiftTarget.FUEL, MachineGuiListener.shiftTargetOf(true, true));
    }

    @Test
    void shiftTarget_neverRoutesToOutputOrFillerOrIndicator() {
        // Structural: the routing decision's codomain contains only FUEL/INPUT/NONE, so
        // no shift-click can ever be routed into a guarded slot.
        for (boolean isRedstone : new boolean[] {true, false}) {
            for (boolean isRecyclable : new boolean[] {true, false}) {
                MachineGuiListener.ShiftTarget target =
                        MachineGuiListener.shiftTargetOf(isRedstone, isRecyclable);
                assertTrue(target == MachineGuiListener.ShiftTarget.FUEL
                                || target == MachineGuiListener.ShiftTarget.INPUT
                                || target == MachineGuiListener.ShiftTarget.NONE,
                        () -> "isRedstone=" + isRedstone + " isRecyclable=" + isRecyclable);
            }
        }
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
