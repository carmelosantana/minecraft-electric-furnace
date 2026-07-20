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
 * primitives -- no {@code org.bukkit} type -- so every stall, resume, and completion path
 * is table-tested with no running server, following the same pattern as
 * {@code FurnaceGui.mayRun}. Nothing about {@link #step}'s logic changed to add the
 * runner below; it is still the sole authority for what one tick does to one machine.
 *
 * <h2>The Bukkit-facing runner</h2>
 *
 * <p>An instance of this class owns exactly one {@link BukkitTask}, started by
 * {@link #start()} and running every single tick ({@code runTaskTimer(plugin, 1L,
 * 1L)}) -- the same one-task-for-the-whole-server discipline {@code MachineEffects}
 * uses, just at a much higher frequency, since progress and burn time are ticked here,
 * not merely displayed. Each pass iterates {@link MachineStore#liveStates()} --
 * deliberately never {@code MachineRegistry.machinesIn}, which would mean a chunk-PDC
 * read every single tick for every loaded chunk. Only machines already resident in
 * memory (opened at least once this session, or read by {@code MachineEffects} for a
 * nearby player) are ticked; a machine that has never been touched is provably at rest
 * ({@link MachineState#empty()}), and ticking it would resolve to
 * {@code STALLED_NO_RECIPE} and change nothing, so skipping it is behaviorally
 * identical to ticking it -- just without the PDC read.
 *
 * <h2>Never destroy an item, never duplicate one</h2>
 *
 * <p>Two disciplines make that true here:
 *
 * <ul>
 *   <li><b>One bad machine cannot cost every other machine.</b> {@link #run()} wraps
 *       each machine's pass in its own {@code try}/{@code catch}, exactly like
 *       {@code MachineStore#flushAll} and {@code MachineEffects} already do for their
 *       own per-machine loops -- an exception inside a scheduled task is otherwise
 *       swallowed by the scheduler, aborting the pass partway through, which is exactly
 *       how items get destroyed.</li>
 *   <li><b>A machine mid-deferred-sync is skipped entirely, not merely under-refreshed.</b>
 *       {@code MachineGuiListener#scheduleSync} defers folding a click's item movement
 *       into {@link MachineState} by one tick. {@code FurnaceGui#shouldSkipRefresh}
 *       alone (Task 6) only stopped this ticker from repainting a stale GUI during that
 *       window -- it did not stop this ticker from mutating {@code MachineState}
 *       itself. Mutating state during that window and then having the deferred sync
 *       overwrite it with the stale, pre-tick inventory contents restores whatever fuel
 *       and inputs this tick just consumed, while leaving {@code progressTicks}
 *       advanced: duplicated fuel. {@link #shouldSkipMachine} is the pure guard that
 *       closes this -- see its own javadoc.</li>
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

    /**
     * Whether a machine must be skipped <b>entirely</b> for this tick -- not merely
     * have its GUI refresh skipped -- because a {@code MachineGuiListener#scheduleSync}
     * deferred callback for it has not run yet.
     *
     * <p>Carried forward from Task 6's review. {@code FurnaceGui#shouldSkipRefresh}
     * closes the collision from the GUI-repaint side only: it stops this ticker from
     * overwriting an open inventory with stale state mid-window, but says nothing about
     * this ticker mutating {@link MachineState} itself during that same window. If it
     * did, the sequence is: this tick consumes fuel and/or inputs from {@code state}
     * (the refresh is skipped, so the open inventory still shows the pre-tick
     * contents); one tick later, the deferred sync folds that same stale, still-pre-
     * tick inventory back over {@code state} -- restoring the fuel and inputs this tick
     * just consumed, while {@code progressTicks} stays wherever this tick left it. That
     * is duplicated fuel (and duplicated inputs): consumed once by the tick, then
     * un-consumed by the stale sync.
     *
     * <p>The fix is for this ticker to never touch such a machine's state at all while
     * a sync is in flight -- skip it outright, and pick it up again on the very next
     * tick once {@code FurnaceGui#clearPendingSync} has run. A one-tick pause per player
     * click is harmless; un-consuming already-spent fuel is not. Pure over the same
     * {@code pendingSyncCount} primitive {@code FurnaceGui#shouldSkipRefresh} guards,
     * with the same polarity, so both sides of the collision close on the same fact.
     */
    public static boolean shouldSkipMachine(int pendingSyncCount) {
        return pendingSyncCount > 0;
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

        for (Map.Entry<Block, MachineState> entry : store.liveStates().entrySet()) {
            Block block = entry.getKey();
            try {
                tickOne(block, entry.getValue(), config, alloys);
            } catch (Throwable t) {
                // Never let one corrupt/unlucky machine abort the pass for the rest of
                // the server's machines -- a mid-pass throw here is swallowed by the
                // scheduler with no further handling of its own, which is exactly how
                // items get destroyed partway through a batch operation.
                plugin.getLogger().warning("ElectricFurnace: failed to tick machine at " + describe(block)
                        + " (" + t.getClass().getName() + ": " + t.getMessage() + "); skipping it this tick.");
            }
        }
    }

    private void tickOne(Block block, MachineState state, EfConfig config, AlloyRegistry alloys) {
        if (!isChunkLoaded(block)) {
            // Never force-load a chunk to tick a machine in it -- see MachineEffects's
            // identical discipline. It is ticked again once its chunk is loaded.
            return;
        }
        if (shouldSkipMachine(FurnaceGui.pendingSyncCount(block))) {
            return;
        }

        boolean powered = block.getBlockPower() > 0;
        boolean requireSignal = config.machine().requireRedstoneSignal();

        List<RecycleInput> inputs = collectInputs(state.inputs(), config);
        RecycleResult result = RecycleResolver.resolve(inputs, config.recycling(), alloys);
        boolean recipeValid = result.kind() != RecycleResult.Kind.REJECTED;

        ItemStack candidateOutput = null;
        boolean outputBlocked = false;
        if (recipeValid) {
            candidateOutput = candidateItemFor(result, alloys, block);
            if (candidateOutput == null) {
                // Unknown alloy id (already logged by candidateItemFor) -- degrade
                // exactly like a rejected recipe: nothing consumed, nothing produced.
                recipeValid = false;
            } else {
                outputBlocked = FurnaceGui.classifyOutputSlot(state.output(), candidateOutput)
                        == FurnaceGui.OutputSlotState.DIFFERENT_ITEM;
            }
        }

        boolean fuelAvailable = FurnaceGui.hasFuel(state.fuel());

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

        // Keep any open viewer's GUI in sync with the state this tick just changed.
        // A no-op if nobody has this block's GUI open.
        FurnaceGui.findOpenInventory(block)
                .ifPresent(inventory -> FurnaceGui.refreshFromState(inventory, state, config, powered));
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

    private static void depositOutput(MachineState state, ItemStack candidateOutput) {
        ItemStack current = state.output();
        if (current == null || current.getType() == Material.AIR) {
            state.setOutput(candidateOutput);
        } else {
            current.setAmount(current.getAmount() + candidateOutput.getAmount());
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
                inputs[i] = null;
            } else {
                item.setAmount(remaining);
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
        }
    }

    private static String describe(Block block) {
        return block.getWorld().getName() + " " + block.getX() + "," + block.getY() + "," + block.getZ();
    }
}
