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

    private static final BlockFace[] ADJACENT_FACES = {
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

    @EventHandler
    public void onRedstoneChange(BlockRedstoneEvent event) {
        Block block = event.getBlock();
        if (!machines.isMachine(block)) {
            return;
        }

        boolean powered = event.getNewCurrent() > 0;
        EfConfig config = configSupplier.get();

        if (config.machine().statusBulbEnabled()) {
            updateAdjacentBulb(block, powered);
        }

        reattemptProcessingForViewers(block, config, powered);
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
