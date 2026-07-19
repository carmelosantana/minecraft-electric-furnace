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

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.xpfarm.electricfurnace.config.EfConfig;
import org.xpfarm.electricfurnace.gui.FurnaceGui;
import org.xpfarm.electricfurnace.item.MachineItemFactory;
import org.xpfarm.electricfurnace.item.MaterialContract;
import org.xpfarm.electricfurnace.machine.MachineRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Registers/unregisters Electric Furnace blocks and opens the custom GUI in place of
 * the native blast furnace's.
 *
 * <p><b>Placing:</b> an item carrying {@link MaterialContract#MACHINE} registers the
 * placed block, gated on {@code electricfurnace.use}.
 *
 * <p><b>Breaking:</b> a registered machine drops the machine item (never a plain
 * blast furnace) and the vanilla drop is cancelled. Anyone currently viewing that
 * block's GUI is force-closed first -- {@link FurnaceGui#closeForBlock} synchronously
 * fires {@code InventoryCloseEvent}, so their items are already back in their
 * inventory (or dropped at their feet) before the block disappears underneath the
 * (now nonexistent) custom inventory.
 *
 * <p><b>Right-click:</b> the event is <em>always</em> cancelled for a registered
 * machine before anything else runs -- if the vanilla blast furnace GUI were also
 * allowed to open, a player could move items through it and bypass every guard in
 * {@link MachineGuiListener}. Only after cancelling do we check permission and open
 * the real GUI.
 */
public final class MachineBlockListener implements Listener {

    private static final String USE_PERMISSION = "electricfurnace.use";

    private final MachineRegistry machines;
    private final Supplier<EfConfig> configSupplier;

    public MachineBlockListener(MachineRegistry machines, Supplier<EfConfig> configSupplier) {
        this.machines = Objects.requireNonNull(machines, "machines");
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        ItemStack placed = event.getItemInHand();
        if (!MaterialContract.isMachine(placed)) {
            return;
        }
        if (!event.getPlayer().hasPermission(USE_PERMISSION)) {
            // Cancel rather than fall through. Letting the place succeed unregistered
            // consumes the machine item and leaves a plain blast furnace behind -- the
            // player permanently loses the item to a permission check. Cancelling keeps
            // the item in their hand.
            event.setCancelled(true);
            event.getPlayer().sendMessage(Component.text(
                            "You don't have permission to place an Electric Furnace.")
                    .color(NamedTextColor.RED));
            return;
        }
        machines.register(event.getBlockPlaced());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!machines.isMachine(block)) {
            return;
        }

        // Return any open viewer's items BEFORE the block (and its virtual inventory)
        // are gone -- never destroy items by breaking the block out from under an open GUI.
        FurnaceGui.closeForBlock(block);

        machines.unregister(block);
        event.setDropItems(false);
        block.getWorld().dropItemNaturally(block.getLocation(), MachineItemFactory.create());
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        salvageExploded(event.blockList());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        salvageExploded(event.blockList());
    }

    /**
     * Removes every registered machine from an explosion's block list and salvages it
     * by hand.
     *
     * <p>Left to vanilla, an exploded machine would be destroyed with its registry entry
     * still pointing at the now-empty location, and would drop -- subject to the
     * explosion's yield roll -- at best a plain blast furnace. Both are item loss.
     * Machines are pulled out of the block list so the explosion does not process them,
     * then broken deliberately: viewers force-closed (returning their contents),
     * registration removed, and the machine item dropped unconditionally.
     */
    private void salvageExploded(List<Block> blockList) {
        List<Block> machineBlocks = new ArrayList<>();
        for (Block block : blockList) {
            if (machines.isMachine(block)) {
                machineBlocks.add(block);
            }
        }
        if (machineBlocks.isEmpty()) {
            return;
        }
        blockList.removeAll(machineBlocks);

        for (Block block : machineBlocks) {
            FurnaceGui.closeForBlock(block);
            machines.unregister(block);
            block.setType(Material.AIR);
            block.getWorld().dropItemNaturally(block.getLocation(), MachineItemFactory.create());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        cancelIfMovingMachine(event, event.getBlocks());
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        cancelIfMovingMachine(event, event.getBlocks());
    }

    /**
     * Refuses any piston move that would displace a registered machine.
     *
     * <p>A machine's registration is keyed to its block location, so a piston sliding it
     * one block leaves the entry pointing at an empty space while the moved block
     * becomes an unregistered plain blast furnace -- the machine item is effectively
     * lost, and any open GUI is left bound to a block that is no longer there.
     *
     * <p>Cancelling is deliberately preferred here over the break-and-drop treatment
     * explosions get. A piston does not destroy the block, so there is nothing to
     * salvage: refusing the move keeps the machine intact, registered, and in the
     * player's build exactly where they put it. Breaking it into a dropped item instead
     * would turn a redstone contraption brushing against a furnace into silent
     * disassembly of the player's base.
     */
    private void cancelIfMovingMachine(Cancellable event, List<Block> movedBlocks) {
        for (Block block : movedBlocks) {
            if (machines.isMachine(block)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null || !machines.isMachine(block)) {
            return;
        }

        // Cancel unconditionally, before the permission check: the native blast
        // furnace GUI must never open for a registered machine regardless of whether
        // this player is allowed to use it.
        event.setCancelled(true);

        Player player = event.getPlayer();
        if (!player.hasPermission(USE_PERMISSION)) {
            player.sendMessage(Component.text("You don't have permission to use this Electric Furnace.")
                    .color(NamedTextColor.RED));
            return;
        }

        boolean powered = block.getBlockPower() > 0;
        FurnaceGui.open(player, block, configSupplier.get(), powered);
    }
}
