/*
 * ElectricFurnace - a redstone-powered smelter that recycles metal gear into ingots.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.electricfurnace.machine;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.xpfarm.electricfurnace.alloy.AlloyDefinition;
import org.xpfarm.electricfurnace.alloy.AlloyRegistry;
import org.xpfarm.electricfurnace.alloy.MetalType;
import org.xpfarm.electricfurnace.config.EfConfig;
import org.xpfarm.electricfurnace.gui.FurnaceGui;
import org.xpfarm.electricfurnace.item.AlloyItemFactory;
import org.xpfarm.electricfurnace.item.MetalClassifier;
import org.xpfarm.electricfurnace.recycle.RecycleInput;
import org.xpfarm.electricfurnace.recycle.RecycleResolver;
import org.xpfarm.electricfurnace.recycle.RecycleResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Advances every machine in a loaded chunk, one tick at a time.
 *
 * <p>{@link #step} is the entire decision, expressed as a pure transition over
 * primitives -- no {@code org.bukkit} type -- so every stall, resume, and completion
 * path is table-tested with no running server. It is the sole authority for what one
 * tick does to one machine; the runner below only gathers facts for it and applies its
 * answer.
 *
 * <h2>The Bukkit-facing runner</h2>
 *
 * <p>An instance owns exactly one {@link BukkitTask}, started by {@link #start()} and
 * running every tick. Each pass iterates {@link MachineStore#liveStates()}, never
 * {@code MachineRegistry.machinesIn}, which would mean a chunk-PDC read every tick for
 * every loaded chunk. That is correct because {@code MachineStore} hydrates every
 * registered machine in a chunk as the chunk loads (and every already-resident chunk at
 * plugin enable), so "in a loaded chunk" and "in {@code liveStates()}" are the same
 * set. A machine missing from the live map would silently not advance -- exactly the
 * "load it, walk away, come back to nothing having happened" failure this plugin exists
 * to prevent -- so that hydration is load-bearing, not an optimization.
 *
 * <h2>Never destroy an item, never duplicate one</h2>
 *
 * <ul>
 *   <li><b>One bad machine cannot cost every other machine.</b> {@link #run()} wraps
 *       each machine's pass in its own {@code try}/{@code catch}: an exception inside a
 *       scheduled task is otherwise swallowed by the scheduler, aborting the pass
 *       partway through, which is how items get destroyed. It also snapshots
 *       {@link MachineStore#liveStates()}'s entries into a plain list before iterating,
 *       under an outer {@code try}/{@code catch}. {@code liveStates()} is an
 *       unmodifiable <em>view</em>, not a copy, so a concurrent structural change to
 *       the backing map would throw {@code ConcurrentModificationException} from
 *       outside every per-machine guard.</li>
 *   <li><b>Slots are read and written, never aliased.</b> A machine's items live in a
 *       Bukkit {@code Inventory} now, and {@code Inventory#getItem} is treated as
 *       returning a snapshot: every consumption and deposit below is
 *       read-modify-write-back. See {@link MachineState}'s note on why mutating what
 *       {@code getItem} returned is correct only by implementation accident.</li>
 * </ul>
 */
public final class MachineTicker {

    private final Plugin plugin;
    private final MachineStore store;
    private final Supplier<EfConfig> configSupplier;
    private final Supplier<AlloyRegistry> alloysSupplier;

    private BukkitTask task;

    /**
     * @param plugin         owning plugin, used only to schedule the single task and to
     *                       log per-machine tick failures
     * @param store          the block-PDC-backed machine contents/run-state store;
     *                       {@link MachineStore#liveStates()} is this ticker's entire
     *                       working set every pass
     * @param configSupplier live config accessor, so {@code /electricfurnace reload} is
     *                       picked up without restarting this task
     * @param alloysSupplier live alloy registry accessor, for the same reason
     */
    public MachineTicker(Plugin plugin, MachineStore store, Supplier<EfConfig> configSupplier,
            Supplier<AlloyRegistry> alloysSupplier) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.store = Objects.requireNonNull(store, "store");
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
        this.alloysSupplier = Objects.requireNonNull(alloysSupplier, "alloysSupplier");
    }

    // =================================================================================
    // Pure decision logic
    // =================================================================================

    /** What one tick did to a machine. */
    public enum Outcome {
        /** A redstone signal is required and absent. Progress and burn are held. */
        STALLED_NO_POWER,
        /** The current inputs resolve to nothing smeltable. Progress is reset. */
        STALLED_NO_RECIPE,
        /** The output slot cannot accept the result. Progress is held so the run resumes. */
        STALLED_OUTPUT_BLOCKED,
        /** No burn time left and no redstone to buy more. Progress is held. */
        STALLED_NO_FUEL,
        /** Progress moved forward by one tick. */
        ADVANCED,
        /** This tick finished the run: deposit output and consume inputs. */
        COMPLETED
    }

    /** Everything the step needs to know about the world, as plain booleans. */
    public record Conditions(
            boolean powered,
            boolean requireSignal,
            boolean recipeValid,
            boolean outputBlocked,
            boolean fuelAvailable
    ) {
    }

    /** The result of one tick: the new counters, and whether to take a dust. */
    public record Step(Outcome outcome, int progressTicks, int burnTicksRemaining, boolean consumeOneFuel) {
    }

    /**
     * Advances one machine by one tick.
     *
     * <p>Ordering matters and is deliberate. Power, recipe, and output are all checked
     * <b>before</b> fuel is purchased, so a machine that cannot smelt never consumes a
     * dust it will not use. Only an invalid recipe resets progress -- every other stall
     * holds it, so a run interrupted by a lost signal or a full output slot resumes
     * exactly where it left off once the blocker clears.
     */
    public static Step step(Conditions conditions, int progressTicks, int burnTicksRemaining,
                            int smeltTicks, int burnTicksPerRedstone) {
        boolean effectivePowered = !conditions.requireSignal() || conditions.powered();
        if (!effectivePowered) {
            return new Step(Outcome.STALLED_NO_POWER, progressTicks, burnTicksRemaining, false);
        }
        if (!conditions.recipeValid()) {
            return new Step(Outcome.STALLED_NO_RECIPE, 0, burnTicksRemaining, false);
        }
        if (conditions.outputBlocked()) {
            return new Step(Outcome.STALLED_OUTPUT_BLOCKED, progressTicks, burnTicksRemaining, false);
        }

        int burn = burnTicksRemaining;
        boolean consumeOneFuel = false;
        if (burn <= 0) {
            if (!conditions.fuelAvailable()) {
                return new Step(Outcome.STALLED_NO_FUEL, progressTicks, 0, false);
            }
            burn = burnTicksPerRedstone;
            consumeOneFuel = true;
        }

        int progress = progressTicks + 1;
        burn = burn - 1;

        if (progress >= smeltTicks) {
            return new Step(Outcome.COMPLETED, 0, burn, consumeOneFuel);
        }
        return new Step(Outcome.ADVANCED, progress, burn, consumeOneFuel);
    }

    // =================================================================================
    // Lifecycle
    // =================================================================================

    /**
     * Starts the single global ticking task, if not already running. Never throws -- a
     * failure here must degrade to "machines do not advance until the next successful
     * start," never take the plugin down with it.
     */
    public void start() {
        if (task != null) {
            return;
        }
        try {
            task = Bukkit.getScheduler().runTaskTimer(plugin, this::run, 1L, 1L);
        } catch (Throwable t) {
            task = null;
            plugin.getLogger().warning("ElectricFurnace: machine ticker failed to start ("
                    + t.getClass().getName() + ": " + t.getMessage() + "). Machines will not advance "
                    + "until the next successful restart.");
        }
    }

    /** Cancels the global task, if running. Idempotent. */
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /** Whether the single global task is currently scheduled. */
    public boolean isRunning() {
        return task != null;
    }

    // =================================================================================
    // The single global task
    // =================================================================================

    private void run() {
        EfConfig config = configSupplier.get();
        AlloyRegistry alloys = alloysSupplier.get();

        try {
            // One online-player scan for the whole pass, not one (or two) per machine.
            // Near-always empty; a machine not in it simply has no GUI to keep in sync.
            Map<Block, Inventory> openGuis = FurnaceGui.openInventoriesByBlock();

            // Snapshot the entries before iterating. liveStates() is an unmodifiable
            // *view* over MachineStore's live map, not a copy, so a concurrent
            // structural change to it (onChunkUnload can run off the main thread on
            // Paper) would throw ConcurrentModificationException out of the iterator
            // itself -- outside the per-machine try/catch below -- aborting the pass for
            // every machine after that point. That is how items get destroyed: some
            // machines this tick, none the next.
            List<Map.Entry<Block, MachineState>> snapshot = new ArrayList<>(store.liveStates().entrySet());
            for (Map.Entry<Block, MachineState> entry : snapshot) {
                Block block = entry.getKey();
                try {
                    tickOne(block, entry.getValue(), config, alloys, openGuis.get(block));
                } catch (Throwable t) {
                    // Never let one corrupt/unlucky machine abort the pass for the rest of
                    // the server's machines -- a mid-pass throw here is swallowed by the
                    // scheduler with no further handling of its own, which is exactly how
                    // items get destroyed partway through a batch operation.
                    plugin.getLogger().warning("ElectricFurnace: failed to tick machine at " + describe(block)
                            + " (" + t.getClass().getName() + ": " + t.getMessage() + "); skipping it this tick.");
                }
            }
        } catch (Throwable t) {
            // Backstop over the snapshot step and the loop machinery itself, not just
            // each machine's tick -- belt-and-braces alongside the snapshot above, so
            // even a failure this class did not anticipate degrades to "skip the rest
            // of this tick" rather than escaping into the scheduler.
            plugin.getLogger().warning("ElectricFurnace: machine ticker pass failed ("
                    + t.getClass().getName() + ": " + t.getMessage() + "); skipping the rest of this tick.");
        }
    }

    private void tickOne(Block block, MachineState state, EfConfig config, AlloyRegistry alloys,
            Inventory openGui) {
        if (!isChunkLoaded(block)) {
            // Never force-load a chunk to tick a machine in it -- see MachineEffects's
            // identical discipline. It is ticked again once its chunk is loaded.
            return;
        }
        boolean powered = block.getBlockPower() > 0;
        boolean requireSignal = config.machine().requireRedstoneSignal();

        resolveIfStale(state, config, alloys, block);
        boolean recipeValid = state.recipeCache().recipeValid();
        ItemStack candidateOutput = state.recipeCache().candidate();

        boolean outputBlocked = recipeValid
                && MachineRules.classifyOutputSlot(state.output(), candidateOutput)
                        == MachineRules.OutputSlotState.DIFFERENT_ITEM;

        boolean fuelAvailable = MachineRules.hasFuel(state.fuel());

        Conditions conditions = new Conditions(powered, requireSignal, recipeValid, outputBlocked, fuelAvailable);
        Step stepResult = step(conditions, state.progressTicks(), state.burnTicksRemaining(),
                config.machine().smeltTicks(), config.machine().burnTicksPerRedstone());

        state.setProgressTicks(stepResult.progressTicks());
        state.setBurnTicksRemaining(stepResult.burnTicksRemaining());

        if (stepResult.consumeOneFuel()) {
            consumeOneFuel(state);
        }

        if (stepResult.outcome() == Outcome.COMPLETED) {
            if (candidateOutput != null) {
                depositOutput(state, candidateOutput);
                consumeOneFromEachOccupiedInputSlot(state);
            } else {
                // Unreachable in practice -- COMPLETED can only be reached past the
                // recipeValid check above, which requires a non-null candidateOutput.
                // Guarded anyway rather than trusting that invariant under a throw.
                plugin.getLogger().warning("ElectricFurnace: machine at " + describe(block)
                        + " completed a run with no resolved output; leaving inputs and fuel untouched.");
            }
        }

        // The items a viewer sees are the items this tick just changed -- one inventory,
        // no copy to push. Only the status indicator is this plugin's own drawing, so
        // only the status indicator needs repainting, and only when somebody is looking.
        // Null when nobody has this block's GUI open, which is the common case.
        if (openGui != null) {
            FurnaceGui.refreshIndicator(openGui, config, powered, !state.isIdle(),
                    state.progressTicks(), config.machine().smeltTicks());
        }
    }

    /**
     * Ensures {@code state}'s {@link RecipeCache} describes its current inputs,
     * re-running resolution only when it does not.
     *
     * <p>This is the expensive half of a tick -- classifying five slots, resolving a
     * recipe, and building a candidate {@code ItemStack} with {@code ItemMeta} and a PDC
     * -- and a machine's inputs change on a player's timescale, not the server's. Doing
     * it unconditionally meant every machine paid it twenty times a second, including
     * machines sitting stalled with no fuel for hours.
     */
    private void resolveIfStale(MachineState state, EfConfig config, AlloyRegistry alloys, Block block) {
        RecipeCache cache = state.recipeCache();
        // Read the slots once: state.inputs() now snapshots a live inventory, so calling
        // it three times would be three reads of something that must not change between
        // the validity check, the resolution, and the fingerprint stored for next tick.
        ItemStack[] slots = state.inputs();
        if (cache.isValidFor(slots, config.recycling(), alloys)) {
            return;
        }

        List<RecycleInput> inputs = collectInputs(slots, config);
        RecycleResult result = RecycleResolver.resolve(inputs, config.recycling(), alloys);

        ItemStack candidate = result.kind() == RecycleResult.Kind.REJECTED
                ? null
                : candidateItemFor(result, alloys, block);
        // A null candidate past a non-rejected result means an unknown alloy id (already
        // logged by candidateItemFor) -- degrade exactly like a rejected recipe: nothing
        // consumed, nothing produced.
        cache.store(slots, config.recycling(), alloys, candidate != null, candidate);
    }

    private static boolean isChunkLoaded(Block block) {
        return block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4);
    }

    private static List<RecycleInput> collectInputs(ItemStack[] inputs, EfConfig config) {
        List<RecycleInput> collected = new ArrayList<>();
        for (ItemStack item : inputs) {
            if (item == null || item.getType() == Material.AIR) {
                continue;
            }
            collected.add(MetalClassifier.classify(item, config.recycling())
                    .orElseGet(() -> new RecycleInput(item.getType().name(), null, false, false, null, 0)));
        }
        return collected;
    }

    /**
     * Builds the {@code ItemStack} a resolved {@link RecycleResult} would deposit, or
     * {@code null} if the result names an alloy id absent from the registry (a
     * configuration error, not a reason to fail the tick -- see {@link #alloyStack}).
     */
    private ItemStack candidateItemFor(RecycleResult result, AlloyRegistry alloys, Block block) {
        if (result instanceof RecycleResult.SameMetal sameMetal) {
            ItemStack stack = new ItemStack(ingotMaterialOf(sameMetal.metal()));
            stack.setAmount(sameMetal.amount());
            return stack;
        }
        if (result instanceof RecycleResult.NamedAlloy namedAlloy) {
            return alloyStack(namedAlloy.alloyId(), namedAlloy.amount(), alloys, block);
        }
        if (result instanceof RecycleResult.GenericAlloy genericAlloy) {
            return alloyStack(genericAlloy.alloyId(), genericAlloy.amount(), alloys, block);
        }
        if (result instanceof RecycleResult.Remelt remelt) {
            return alloyStack(remelt.alloyId(), remelt.amount(), alloys, block);
        }
        // Rejected: the only caller (tickOne) only reaches here when recipeValid is
        // already true, so this is unreachable in practice; null keeps the contract
        // simple rather than throwing on a result variant that should never arrive.
        return null;
    }

    /**
     * Builds the item for a resolved alloy id, or {@code null} if the registry does not
     * know that id.
     *
     * <p><b>Never throws.</b> This runs inside the per-machine {@code try}/{@code catch}
     * in {@link #run()}, but degrading locally (returning {@code null}, treated by the
     * caller exactly like a rejected recipe) names the specific offending alloy id in
     * the warning, which a generic outer catch could not do as precisely.
     */
    private ItemStack alloyStack(String alloyId, int amount, AlloyRegistry alloys, Block block) {
        Optional<AlloyDefinition> definition = alloys.get(alloyId);
        if (definition.isEmpty()) {
            plugin.getLogger().warning("ElectricFurnace: recycler at " + describe(block) + " resolved to "
                    + "unknown alloy id '" + alloyId + "'; skipping this tick's completion. Check the "
                    + "'alloys' section of config.yml.");
            return null;
        }
        ItemStack stack = AlloyItemFactory.create(definition.get());
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

    /**
     * Adds a completed run's result to the output slot.
     *
     * <p>{@code candidateOutput} belongs to the machine's {@link RecipeCache} and is
     * reused across ticks, so an empty slot is filled with a <em>clone</em>. Storing the
     * cached instance itself would alias the output slot to the cache, and the next
     * completion's merge would then grow the cache's own stack.
     */
    private static void depositOutput(MachineState state, ItemStack candidateOutput) {
        ItemStack current = state.output();
        if (current == null || current.getType() == Material.AIR) {
            state.setOutput(candidateOutput.clone());
        } else {
            current.setAmount(current.getAmount() + candidateOutput.getAmount());
            state.setOutput(current);
        }
    }

    /**
     * Consumes exactly one item from each currently-occupied input slot -- never the
     * whole stack. A slot holding more than one item keeps its remaining items for the
     * next run; clearing the whole slot here would silently destroy them.
     */
    private static void consumeOneFromEachOccupiedInputSlot(MachineState state) {
        ItemStack[] inputs = state.inputs();
        for (int i = 0; i < inputs.length; i++) {
            ItemStack item = inputs[i];
            if (item == null || item.getType() == Material.AIR) {
                continue;
            }
            int remaining = item.getAmount() - 1;
            if (remaining <= 0) {
                state.setInput(i, null);
            } else {
                item.setAmount(remaining);
                state.setInput(i, item);
            }
        }
    }

    /** Consumes exactly one redstone dust from the fuel slot -- see {@link Step#consumeOneFuel()}. */
    private static void consumeOneFuel(MachineState state) {
        ItemStack fuel = state.fuel();
        if (fuel == null || fuel.getType() == Material.AIR) {
            // Defensive: step() only signals consumeOneFuel when fuelAvailable was true
            // for this exact fuel stack, but guard anyway rather than NPE mid-tick.
            return;
        }
        int remaining = fuel.getAmount() - 1;
        if (remaining <= 0) {
            state.setFuel(null);
        } else {
            fuel.setAmount(remaining);
            state.setFuel(fuel);
        }
    }

    private static String describe(Block block) {
        return block.getWorld().getName() + " " + block.getX() + "," + block.getY() + "," + block.getZ();
    }
}
