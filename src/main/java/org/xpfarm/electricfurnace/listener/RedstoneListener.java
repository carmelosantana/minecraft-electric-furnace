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

import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.CopperBulb;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.inventory.Inventory;
import org.xpfarm.electricfurnace.alloy.AlloyRegistry;
import org.xpfarm.electricfurnace.config.EfConfig;
import org.xpfarm.electricfurnace.gui.FurnaceGui;
import org.xpfarm.electricfurnace.machine.MachineRegistry;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Tracks powered state for registered Electric Furnace blocks and drives an adjacent
 * {@code COPPER_BULB} indicator, reacting to {@link BlockRedstoneEvent}.
 *
 * <p>Deliberately holds no separate "is this block powered" map: {@link
 * Block#getBlockPower()} already answers that synchronously and correctly at any
 * time (used by {@code MachineGuiListener} and {@code MachineBlockListener} too), so
 * there is no cached state that could ever drift out of sync with the world. This
 * class's job is purely reactive -- when a registered machine's redstone current
 * changes, update its status bulb and re-attempt processing for anyone currently
 * viewing its GUI, since the redstone change may be exactly what was blocking it.
 */
public final class RedstoneListener implements Listener {

    /**
     * The six axis-aligned faces scanned for adjacent machines. Package-private so
     * {@code RedstoneListenerTest} can assert it enumerates exactly
     * {@link #neighbourOffsets}.
     */
    static final BlockFace[] ADJACENT_FACES = {
            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN
    };

    private final MachineRegistry machines;
    private final Supplier<EfConfig> configSupplier;
    private final Supplier<AlloyRegistry> alloysSupplier;

    public RedstoneListener(MachineRegistry machines, Supplier<EfConfig> configSupplier,
            Supplier<AlloyRegistry> alloysSupplier) {
        this.machines = Objects.requireNonNull(machines, "machines");
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
        this.alloysSupplier = Objects.requireNonNull(alloysSupplier, "alloysSupplier");
    }

    /**
     * Reacts to a redstone current change by updating every <em>adjacent</em> registered
     * machine.
     *
     * <p><b>The event block is the redstone component, never the machine.</b>
     * {@code BlockRedstoneEvent} fires on the wire, torch, or repeater whose current
     * changed. A blast furnace is not a redstone-sensitive block, so it never fires this
     * event itself -- testing {@code machines.isMachine(event.getBlock())} could
     * therefore never be true, making the whole handler dead code: the copper bulb never
     * lit and the "redstone changed, re-attempt processing" path never ran. The machine
     * to act on is a <em>neighbour</em> of the event block, so all six neighbours are
     * scanned (see {@link #neighbourOffsets}).
     */
    @EventHandler
    public void onRedstoneChange(BlockRedstoneEvent event) {
        Block source = event.getBlock();
        EfConfig config = configSupplier.get();

        for (BlockFace face : ADJACENT_FACES) {
            Block machine = source.getRelative(face);
            if (!machines.isMachine(machine)) {
                continue;
            }

            boolean powered = poweredAfterChange(event.getNewCurrent(), machine.getBlockPower());

            if (config.machine().statusBulbEnabled()) {
                updateAdjacentBulb(machine, powered);
            }
            reattemptProcessingForViewers(machine, config, powered);
        }
    }

    /**
     * Whether an adjacent machine should be treated as powered, given the changing
     * component's new current and the machine's own currently-reported block power.
     *
     * <p>The OR matters on the rising edge: {@code BlockRedstoneEvent} fires
     * <em>before</em> the world applies the new current, so
     * {@link Block#getBlockPower()} on the machine can still report the stale value and
     * would miss the change that just powered it. Conversely, on a falling edge the
     * machine may still be powered by some other adjacent source, which is exactly what
     * {@code otherPower} reports. Pure integer logic, unit tested.
     */
    static boolean poweredAfterChange(int newCurrent, int otherPower) {
        return newCurrent > 0 || otherPower > 0;
    }

    /**
     * A block offset by one along a single axis.
     *
     * @param dx x offset
     * @param dy y offset
     * @param dz z offset
     */
    record Offset(int dx, int dy, int dz) {
    }

    /**
     * The six axis-aligned neighbour offsets ({@code +-x, +-y, +-z}) that
     * {@link #onRedstoneChange} scans for registered machines.
     *
     * <p>Exists as a pure function over plain ints so the neighbour enumeration -- the
     * part of the S1 fix that is easy to get subtly wrong (a missing face, a duplicated
     * one, a diagonal) -- is verifiable with no running server. {@link #ADJACENT_FACES}
     * is asserted against this set in {@code RedstoneListenerTest}, which is what ties
     * the tested enumeration to the {@code BlockFace} array actually used at runtime.
     */
    static List<Offset> neighbourOffsets() {
        return List.of(
                new Offset(1, 0, 0), new Offset(-1, 0, 0),
                new Offset(0, 1, 0), new Offset(0, -1, 0),
                new Offset(0, 0, 1), new Offset(0, 0, -1));
    }

    private void updateAdjacentBulb(Block machine, boolean powered) {
        for (BlockFace face : ADJACENT_FACES) {
            Block neighbor = machine.getRelative(face);
            BlockData data = neighbor.getBlockData();
            if (data instanceof CopperBulb bulb) {
                bulb.setLit(powered);
                neighbor.setBlockData(bulb);
            }
        }
    }

    private void reattemptProcessingForViewers(Block block, EfConfig config, boolean powered) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Inventory top = player.getOpenInventory().getTopInventory();
            FurnaceGui.blockOf(top)
                    .filter(block::equals)
                    .ifPresent(b -> FurnaceGui.tryProcess(top, config, alloysSupplier.get(), powered));
        }
    }
}
