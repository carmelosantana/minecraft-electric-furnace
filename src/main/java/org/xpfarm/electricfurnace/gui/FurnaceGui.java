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

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.xpfarm.electricfurnace.alloy.AlloyDefinition;
import org.xpfarm.electricfurnace.alloy.AlloyRegistry;
import org.xpfarm.electricfurnace.alloy.MetalType;
import org.xpfarm.electricfurnace.config.EfConfig;
import org.xpfarm.electricfurnace.item.AlloyItemFactory;
import org.xpfarm.electricfurnace.item.MetalClassifier;
import org.xpfarm.electricfurnace.recycle.RecycleInput;
import org.xpfarm.electricfurnace.recycle.RecycleResolver;
import org.xpfarm.electricfurnace.recycle.RecycleResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Owns the Electric Furnace's 27-slot custom {@link Inventory}: rendering the filler
 * panes and status indicator, running the recycler resolution, and returning items to
 * players -- never destroying them.
 *
 * <p><b>No cross-session persistence, by design.</b> Every {@link #open} call creates
 * a brand-new, empty backing {@code Inventory}; nothing is ever written to a chunk or
 * block PDC for slot contents (Task 4's {@code MachineRegistry} persists only machine
 * <em>locations</em>, never item state). This is exactly what makes the highest-stakes
 * requirement tractable: {@link #returnAllItems} unconditionally drains input, fuel,
 * <b>and</b> output back to the viewing player on every close, so nothing is ever left
 * sitting in a plain in-memory {@code Map} that would silently evaporate on server
 * restart. The plan only requires returning input and fuel on close; this class
 * deliberately also returns any unclaimed output, since leaving it behind would be an
 * item-loss bug the moment the server restarts before that player reopens the GUI.
 *
 * <p>The processing gate ({@link #mayRun}) and the status-indicator decision
 * ({@link #indicatorStateOf}) are pure functions over primitives/enums -- no
 * {@code org.bukkit} type -- so {@code FurnaceGuiTest} exercises every combination
 * with no running server, following the same pattern as
 * {@code MetalClassifier.resolveBranch}.
 */
public final class FurnaceGui {

    private FurnaceGui() {
    }

    // =================================================================================
    // Pure decision logic
    // =================================================================================

    /** What the non-interactive status indicator (slot {@link GuiLayout#INDICATOR_SLOT}) should show. */
    public enum IndicatorState {
        /** Powered (or signal not required) and fuel present: the machine will process on the next attempt. */
        RUNNING,
        /** {@code machine.require-redstone-signal} is {@code true} and the machine is not currently powered. */
        NO_SIGNAL,
        /** Powered (or signal not required), but the fuel slot lacks enough redstone. */
        NO_FUEL
    }

    /**
     * Decides the status indicator's state from plain booleans. {@code NO_SIGNAL}
     * takes precedence over {@code NO_FUEL} when both apply: the more fundamental
     * blocker is reported first.
     */
    public static IndicatorState indicatorStateOf(boolean powered, boolean requireSignal, boolean hasFuel) {
        boolean effectivePowered = !requireSignal || powered;
        if (!effectivePowered) {
            return IndicatorState.NO_SIGNAL;
        }
        if (!hasFuel) {
            return IndicatorState.NO_FUEL;
        }
        return IndicatorState.RUNNING;
    }

    /** What the output slot currently holds, relative to the item an operation would produce. */
    public enum OutputSlotState {
        /** The output slot is empty. */
        EMPTY,
        /** The output slot holds an item that matches what would be produced, with room to merge. */
        SAME_ITEM,
        /** The output slot holds something else -- or the same item with no room left -- blocking the run. */
        DIFFERENT_ITEM
    }

    /**
     * The processing gate: an operation may run only when effectively powered
     * (powered, or {@code requireSignal} is {@code false}), fuel is present, and the
     * output slot does not block it. An output slot occupied by a different item (or
     * the same item with no stacking room left) never runs and never consumes fuel --
     * a player's output is never silently overwritten or corrupted.
     */
    public static boolean mayRun(boolean powered, boolean requireSignal, boolean hasFuel, OutputSlotState outputSlotState) {
        boolean effectivePowered = !requireSignal || powered;
        return effectivePowered && hasFuel && outputSlotState != OutputSlotState.DIFFERENT_ITEM;
    }

    // =================================================================================
    // Bukkit-facing glue
    // =================================================================================

    /** Marks a custom inventory as belonging to one specific Electric Furnace block. */
    public static final class Holder implements InventoryHolder {
        private final Block block;
        private Inventory inventory;

        private Holder(Block block) {
            this.block = Objects.requireNonNull(block, "block");
        }

        /** The Electric Furnace block this GUI instance belongs to. */
        public Block block() {
            return block;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    /** Whether {@code inventory} is an Electric Furnace GUI. */
    public static boolean isFurnaceGui(Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof Holder;
    }

    /** The block a given Electric Furnace GUI inventory belongs to, if it is one. */
    public static Optional<Block> blockOf(Inventory inventory) {
        if (inventory != null && inventory.getHolder() instanceof Holder holder) {
            return Optional.of(holder.block());
        }
        return Optional.empty();
    }

    /**
     * Opens a brand-new Electric Furnace GUI for {@code player} at {@code block}.
     * Always starts empty -- see the class-level note on why nothing persists across
     * sessions.
     */
    public static Inventory open(Player player, Block block, EfConfig config, boolean powered) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(block, "block");
        Objects.requireNonNull(config, "config");

        Holder holder = new Holder(block);
        Inventory inventory = Bukkit.createInventory(holder, GuiLayout.SIZE, Component.text(GuiLayout.TITLE_TEXT));
        holder.inventory = inventory;

        for (int slot = 0; slot < GuiLayout.SIZE; slot++) {
            if (GuiLayout.roleOf(slot) == GuiLayout.SlotRole.FILLER) {
                inventory.setItem(slot, buildFillerItem());
            }
        }
        refreshIndicator(inventory, config, powered);

        player.openInventory(inventory);
        return inventory;
    }

    /** Recomputes and redraws the status indicator item from the inventory's current fuel slot. */
    public static void refreshIndicator(Inventory inventory, EfConfig config, boolean powered) {
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(config, "config");

        boolean hasFuel = hasSufficientFuel(inventory, config.machine().fuelPerOperation());
        IndicatorState state = indicatorStateOf(powered, config.machine().requireRedstoneSignal(), hasFuel);
        inventory.setItem(GuiLayout.INDICATOR_SLOT, indicatorItem(state));
    }

    private static boolean hasSufficientFuel(Inventory inventory, int fuelPerOperation) {
        ItemStack fuel = inventory.getItem(GuiLayout.FUEL_SLOT);
        return fuel != null && fuel.getType() == Material.REDSTONE && fuel.getAmount() >= fuelPerOperation;
    }

    /**
     * Attempts one recycler operation against {@code inventory}'s current input/fuel
     * slots. Always redraws the status indicator, whether or not the attempt
     * succeeds.
     *
     * @return {@code true} if an operation ran (fuel consumed, output produced)
     */
    public static boolean tryProcess(Inventory inventory, EfConfig config, AlloyRegistry alloys, boolean powered) {
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(alloys, "alloys");

        List<RecycleInput> inputs = collectInputs(inventory, config);
        RecycleResult result = RecycleResolver.resolve(inputs, config.recycling(), alloys);

        refreshIndicator(inventory, config, powered);

        if (result instanceof RecycleResult.Rejected) {
            return false;
        }

        boolean hasFuel = hasSufficientFuel(inventory, config.machine().fuelPerOperation());
        ItemStack candidateOutput = candidateItemFor(result, alloys);
        ItemStack currentOutput = inventory.getItem(GuiLayout.OUTPUT_SLOT);
        OutputSlotState outputState = classifyOutputSlot(currentOutput, candidateOutput);

        if (!mayRun(powered, config.machine().requireRedstoneSignal(), hasFuel, outputState)) {
            return false;
        }

        consumeFuel(inventory, config.machine().fuelPerOperation());
        depositOutput(inventory, currentOutput, candidateOutput);
        consumeOneFromEachOccupiedInputSlot(inventory);

        refreshIndicator(inventory, config, powered);
        return true;
    }

    private static List<RecycleInput> collectInputs(Inventory inventory, EfConfig config) {
        List<RecycleInput> inputs = new ArrayList<>();
        for (int slot : GuiLayout.INPUT_SLOTS) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.getType() == Material.AIR) {
                continue;
            }
            inputs.add(MetalClassifier.classify(item, config.recycling())
                    .orElseGet(() -> new RecycleInput(item.getType().name(), null, false, false, null, 0)));
        }
        return inputs;
    }

    private static void consumeFuel(Inventory inventory, int fuelPerOperation) {
        ItemStack fuel = inventory.getItem(GuiLayout.FUEL_SLOT);
        int remaining = fuel.getAmount() - fuelPerOperation;
        if (remaining <= 0) {
            inventory.setItem(GuiLayout.FUEL_SLOT, null);
        } else {
            fuel.setAmount(remaining);
        }
    }

    private static void depositOutput(Inventory inventory, ItemStack currentOutput, ItemStack candidateOutput) {
        if (currentOutput == null || currentOutput.getType() == Material.AIR) {
            inventory.setItem(GuiLayout.OUTPUT_SLOT, candidateOutput);
        } else {
            currentOutput.setAmount(currentOutput.getAmount() + candidateOutput.getAmount());
        }
    }

    /**
     * Consumes exactly one item from each currently-occupied input slot -- never the
     * whole stack. A slot holding more than one item (e.g. a player queued up a stack
     * of 5 iron ingots in one slot) keeps its remaining items for the next operation;
     * clearing the whole slot here would silently destroy them.
     */
    private static void consumeOneFromEachOccupiedInputSlot(Inventory inventory) {
        for (int slot : GuiLayout.INPUT_SLOTS) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.getType() == Material.AIR) {
                continue;
            }
            int remaining = item.getAmount() - 1;
            if (remaining <= 0) {
                inventory.setItem(slot, null);
            } else {
                item.setAmount(remaining);
            }
        }
    }

    /**
     * Compares the output slot's current contents against what an operation would
     * produce. Blocks the run (returns {@link OutputSlotState#DIFFERENT_ITEM}) not
     * only when a genuinely different item occupies the slot, but also when merging
     * would exceed the max stack size -- an overflowing stack is exactly the kind of
     * silent corruption this plugin must never cause.
     */
    static OutputSlotState classifyOutputSlot(ItemStack current, ItemStack candidate) {
        if (current == null || current.getType() == Material.AIR) {
            return OutputSlotState.EMPTY;
        }
        if (candidate == null || !current.isSimilar(candidate)) {
            return OutputSlotState.DIFFERENT_ITEM;
        }
        if (current.getAmount() + candidate.getAmount() > current.getMaxStackSize()) {
            return OutputSlotState.DIFFERENT_ITEM;
        }
        return OutputSlotState.SAME_ITEM;
    }

    private static ItemStack candidateItemFor(RecycleResult result, AlloyRegistry alloys) {
        if (result instanceof RecycleResult.SameMetal sameMetal) {
            ItemStack stack = new ItemStack(ingotMaterialOf(sameMetal.metal()));
            stack.setAmount(sameMetal.amount());
            return stack;
        }
        if (result instanceof RecycleResult.NamedAlloy namedAlloy) {
            return alloyStack(namedAlloy.alloyId(), namedAlloy.amount(), alloys);
        }
        if (result instanceof RecycleResult.GenericAlloy genericAlloy) {
            return alloyStack(genericAlloy.alloyId(), genericAlloy.amount(), alloys);
        }
        if (result instanceof RecycleResult.Remelt remelt) {
            return alloyStack(remelt.alloyId(), remelt.amount(), alloys);
        }
        // Rejected: never reached, callers return early on Rejected before calling this.
        throw new IllegalStateException("candidateItemFor called with a Rejected result");
    }

    private static ItemStack alloyStack(String alloyId, int amount, AlloyRegistry alloys) {
        AlloyDefinition definition = alloys.get(alloyId)
                .orElseThrow(() -> new IllegalStateException(
                        "RecycleResolver referenced unknown alloy id '" + alloyId + "'"));
        ItemStack stack = AlloyItemFactory.create(definition);
        stack.setAmount(amount);
        return stack;
    }

    private static Material ingotMaterialOf(MetalType metal) {
        return switch (metal) {
            case IRON -> Material.IRON_INGOT;
            case GOLD -> Material.GOLD_INGOT;
            case COPPER -> Material.COPPER_INGOT;
            case NETHERITE -> Material.NETHERITE_INGOT;
        };
    }

    // ---- Item-safety: returning contents, and force-closing viewers ----------------

    /**
     * Unconditionally returns every input, fuel, and output item currently in
     * {@code inventory} to {@code player}, dropping at the player's location whatever
     * does not fit in their inventory. Nothing is ever silently destroyed.
     */
    public static void returnAllItems(Inventory inventory, Player player) {
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(player, "player");

        Set<Integer> slots = new HashSet<>(GuiLayout.INPUT_SLOTS);
        slots.add(GuiLayout.FUEL_SLOT);
        slots.add(GuiLayout.OUTPUT_SLOT);

        for (int slot : slots) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.getType() == Material.AIR) {
                continue;
            }
            inventory.setItem(slot, null);
            giveOrDrop(player, item);
        }
    }

    private static void giveOrDrop(Player player, ItemStack item) {
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
        for (ItemStack overflow : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), overflow);
        }
    }

    /**
     * Force-closes any online player currently viewing {@code block}'s Electric
     * Furnace GUI. Closing synchronously fires {@code InventoryCloseEvent}, so the
     * normal close handler ({@code MachineGuiListener#onClose}) returns their items
     * before this method returns -- callers (e.g. a block break) can safely proceed
     * immediately afterward.
     */
    public static void closeForBlock(Block block) {
        Objects.requireNonNull(block, "block");
        for (Player player : Bukkit.getOnlinePlayers()) {
            Inventory top = player.getOpenInventory().getTopInventory();
            if (top.getHolder() instanceof Holder holder && holder.block().equals(block)) {
                player.closeInventory();
            }
        }
    }

    /**
     * Force-closes every online player currently viewing any Electric Furnace GUI.
     * Intended for the plugin's {@code onDisable}, so a server shutdown never leaves
     * a player's input/fuel/output stranded in a GUI that is about to vanish.
     */
    public static void closeAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Inventory top = player.getOpenInventory().getTopInventory();
            if (top.getHolder() instanceof Holder) {
                player.closeInventory();
            }
        }
    }

    private static ItemStack indicatorItem(IndicatorState state) {
        Material material = switch (state) {
            case RUNNING -> Material.REDSTONE_TORCH;
            case NO_SIGNAL -> Material.LEVER;
            case NO_FUEL -> Material.REDSTONE;
        };
        String label = switch (state) {
            case RUNNING -> "Running";
            case NO_SIGNAL -> "No redstone signal";
            case NO_FUEL -> "No fuel";
        };
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text(label).decoration(TextDecoration.ITALIC, false));
        stack.setItemMeta(meta);
        return stack;
    }

    private static ItemStack buildFillerItem() {
        ItemStack stack = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text(" ").decoration(TextDecoration.ITALIC, false));
        stack.setItemMeta(meta);
        return stack;
    }
}
