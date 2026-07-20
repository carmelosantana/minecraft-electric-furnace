/*
 * ElectricFurnace - a redstone-powered smelter that recycles metal gear into ingots.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.electricfurnace.effect;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.xpfarm.electricfurnace.config.EfConfig;
import org.xpfarm.electricfurnace.machine.MachineRegistry;
import org.xpfarm.electricfurnace.machine.MachineStore;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The plugin's single global effects loop: sparks, smoke, and a hum for every running
 * Electric Furnace that somebody is close enough to notice.
 *
 * <h2>Performance is the requirement here, not a preference</h2>
 *
 * <p>The sibling plugin CopperKingdom shipped a listener that rescanned 1,331 blocks
 * on every player movement packet. It does not survive a populated server. This class
 * is written specifically to not be that, and every one of the following is load
 * bearing:
 *
 * <ul>
 *   <li><b>Exactly one {@link BukkitTask} for the whole server.</b> Not one per
 *       machine, not one per chunk, not one per world. A server with ten thousand
 *       machines schedules exactly one repeating task, same as a server with one.</li>
 *   <li><b>No world scanning, ever.</b> The set of machines to consider is an
 *       in-memory cache keyed by loaded chunk, maintained incrementally by
 *       {@link ChunkLoadEvent} / {@link ChunkUnloadEvent} and by
 *       {@link MachineRegistry.ChangeListener} notifications for every place, break,
 *       and explosion-salvage that ever adds or removes a machine. A run iterates that
 *       cache directly; it never walks blocks, never queries a chunk it was not told
 *       about, and never reads a PDC on the hot path -- chunk PDC reads happen once
 *       per chunk load or registry change, not once per run.</li>
 *   <li><b>Unloaded chunks cost nothing and are never force-loaded.</b> Entries leave
 *       the cache on chunk unload, so a machine in an unloaded chunk is not merely
 *       skipped, it is not even visited. {@link Block#getChunk()} would load a chunk
 *       on demand, so the cache is keyed by coordinates and validated with
 *       {@link World#isChunkLoaded(int, int)} instead.</li>
 *   <li><b>The nearby-player check gates everything.</b> If
 *       {@link World#getNearbyPlayers(Location, double)} comes back empty, the machine
 *       is abandoned immediately -- before any block state read, before any particle
 *       is constructed. Effects nobody can see are pure waste.</li>
 *   <li><b>Per-player {@code spawnParticle} overloads, never the broadcast ones.</b>
 *       The broadcast overload sends to every player tracking the chunk regardless of
 *       the radius the operator configured, silently defeating
 *       {@code effects.player-radius}.</li>
 * </ul>
 *
 * <h2>Bedrock/Geyser safety</h2>
 *
 * <p>Only {@link Particle#ELECTRIC_SPARK} and {@link Particle#CAMPFIRE_COSY_SMOKE} are
 * emitted -- both confirmed mapped in Geyser. No display entities and no colored
 * {@code DUST} particles appear anywhere in this class: the former are invisible to
 * Bedrock clients, and the latter lose their color in translation.
 * {@link #approvedParticleNames()} exposes the list so a unit test can fail if a third
 * particle is ever added.
 *
 * <h2>Sparks follow power; smoke follows work</h2>
 *
 * <p>{@link #shouldEmitSparks} and {@link #shouldEmitSmoke} are deliberately separate
 * gates, not one combined "is this machine interesting" boolean. A powered machine with
 * nothing queued still sparks -- that is what tells a player the redstone side of the
 * machine is live -- but it does not smoke, and the beacon-hum sound (tied to the same
 * gate as smoke, since both mean "actively smelting") does not play, until a smelt is
 * actually advancing. Splitting them is what makes "energised but idle" and "actively
 * working" visually distinguishable at a glance, without opening the GUI.
 *
 * <h2>Testability</h2>
 *
 * <p>The decisions -- {@link #shouldSchedule}, {@link #machineIsActive},
 * {@link #shouldEmitSparks}, {@link #shouldEmitSmoke} -- are static functions over
 * primitives, so {@code MachineEffectsTest} pins them exhaustively with no running
 * server, following the pattern of {@code FurnaceGui#indicatorStateOf} and
 * {@code GuiLayout#roleOf}. What remains in this class is Bukkit glue thin enough to
 * read.
 *
 * <h2>Staying in sync with the machine registry</h2>
 *
 * <p>This class does not listen for {@code BlockPlaceEvent}, {@code BlockBreakEvent},
 * explosions, or piston moves itself. Registering a second, independent set of
 * listeners for the same registry changes {@code MachineBlockListener} already reacts
 * to is exactly how a cache goes stale: the two listener sets are free to drift apart
 * the moment a new removal path (an explosion, say) is added to one and not the
 * other. Instead, this class subscribes once to {@link MachineRegistry} itself via
 * {@link MachineRegistry.ChangeListener} in its constructor, so every current and
 * future path that calls {@link MachineRegistry#register} or
 * {@link MachineRegistry#unregister} -- place, break, entity/block explosion salvage,
 * or anything added later -- keeps this cache correct automatically, with nothing to
 * remember to wire up on the effects side.
 */
public final class MachineEffects implements Listener, MachineRegistry.ChangeListener {

    /**
     * The only particles this plugin may ever emit. Both are confirmed mapped in
     * Geyser; see the class-level Bedrock note before touching this.
     */
    private static final Particle[] APPROVED_PARTICLES = {
            Particle.ELECTRIC_SPARK, Particle.CAMPFIRE_COSY_SMOKE
    };

    private static final int SPARK_COUNT = 4;
    private static final int SMOKE_COUNT = 2;
    private static final double SPREAD = 0.25D;
    private static final float SOUND_VOLUME = 0.35F;
    private static final float SOUND_PITCH = 1.6F;

    private final Plugin plugin;
    private final MachineRegistry machines;
    private final MachineStore store;
    private final Supplier<EfConfig> configSupplier;

    /**
     * Machine block coordinates per loaded chunk. Keyed by {@link ChunkId} rather than
     * by {@link Chunk} so nothing here can keep an unloaded chunk alive, and so a
     * lookup never triggers a chunk load. Concurrent because chunk load/unload events
     * may arrive off the main thread on Paper, while the effects task reads on it.
     */
    private final Map<ChunkId, Set<BlockPos>> byChunk = new ConcurrentHashMap<>();

    private BukkitTask task;

    /**
     * @param plugin         owning plugin, used only to schedule the single task
     * @param machines       the machine registry, consulted on chunk load -- never on
     *                       the hot path
     * @param store          the block-PDC-backed machine contents/run-state store,
     *                       consulted once per candidate machine per run (only after
     *                       the nearby-player gate already passed) to decide
     *                       {@link #shouldEmitSmoke}'s {@code smelting} fact
     * @param configSupplier live config accessor, so {@code /electricfurnace reload}
     *                       is picked up without rebuilding this object
     */
    public MachineEffects(Plugin plugin, MachineRegistry machines, MachineStore store,
            Supplier<EfConfig> configSupplier) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.machines = Objects.requireNonNull(machines, "machines");
        this.store = Objects.requireNonNull(store, "store");
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
        // The single choke point for every place/break/explosion-salvage that ever adds
        // or removes a machine -- see the class-level "Staying in sync" note.
        this.machines.addChangeListener(this);
    }

    // =================================================================================
    // Pure decision logic -- see MachineEffectsTest
    // =================================================================================

    /**
     * Whether the single global task should be scheduled at all. A disabled effects
     * section must cost exactly zero: no task that wakes up only to decide it has
     * nothing to do.
     */
    public static boolean shouldSchedule(boolean enabled, int periodTicks) {
        return enabled && periodTicks > 0;
    }

    /**
     * Whether a machine counts as running for effects purposes. With
     * {@code machine.require-redstone-signal} disabled the machine is always on, so it
     * always looks on.
     */
    public static boolean machineIsActive(boolean powered, boolean requireSignal) {
        return !requireSignal || powered;
    }

    /**
     * Whether sparks should be emitted for a machine: sparks show that the machine is
     * energised, whether or not it currently has work to do. Distinct from
     * {@link #shouldEmitSmoke} on purpose -- see the class-level "Sparks follow power;
     * smoke follows work" note.
     *
     * @param enabled           {@code effects.enabled}
     * @param nearbyPlayerCount how many players are within {@code effects.player-radius}
     * @param powered           per {@link #machineIsActive}
     */
    public static boolean shouldEmitSparks(boolean enabled, int nearbyPlayerCount, boolean powered) {
        return enabled && nearbyPlayerCount > 0 && powered;
    }

    /**
     * Whether smoke -- and the beacon-hum sound, gated identically -- should be emitted
     * for a machine: smoke shows actual work, appearing only while a smelt is actively
     * advancing. A machine that is powered but idle (nothing queued, or stalled on
     * fuel/output) sparks but never smokes.
     *
     * @param enabled           {@code effects.enabled}
     * @param nearbyPlayerCount how many players are within {@code effects.player-radius}
     * @param smelting          whether the machine currently has a run advancing
     *                          ({@code progressTicks > 0})
     */
    public static boolean shouldEmitSmoke(boolean enabled, int nearbyPlayerCount, boolean smelting) {
        return enabled && nearbyPlayerCount > 0 && smelting;
    }

    /** Names of the only particles this class emits; asserted by {@code MachineEffectsTest}. */
    public static List<String> approvedParticleNames() {
        List<String> names = new ArrayList<>(APPROVED_PARTICLES.length);
        for (Particle particle : APPROVED_PARTICLES) {
            names.add(particle.name());
        }
        return List.copyOf(names);
    }

    // =================================================================================
    // Lifecycle
    // =================================================================================

    /**
     * Seeds the cache from every already-loaded chunk and starts the single global
     * task, if the current config calls for one.
     *
     * <p>Safe to call repeatedly: {@code /electricfurnace reload} calls it after
     * {@link #stop()} to pick up a changed {@code effects.period-ticks} without a
     * server restart. Never throws -- an effects failure must not take the plugin
     * down with it, and on the {@code reload} path {@link #restart()} has already
     * cancelled the previous task by the time this runs, so a throw here would leave
     * effects off until the next successful reload or a full server restart.
     */
    public void start() {
        try {
            EfConfig config = configSupplier.get();
            seedLoadedChunks();

            if (!shouldSchedule(config.effects().enabled(), config.effects().periodTicks())) {
                return;
            }
            int period = config.effects().periodTicks();
            task = Bukkit.getScheduler().runTaskTimer(plugin, this::run, period, period);
        } catch (Throwable t) {
            // seedLoadedChunks() walks every already-loaded chunk's machine registry;
            // a failure there (or reading the config) must degrade to "effects off"
            // rather than propagate -- consistent with the "startup/reload never
            // throws" contract enforced everywhere else in this plugin.
            task = null;
            Bukkit.getLogger().warning("ElectricFurnace: effects failed to start (" + t.getClass().getName()
                    + ": " + t.getMessage() + "). Effects are off until the next successful reload or restart.");
        }
    }

    /** Cancels the global task, if running. Idempotent. */
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /** Stops and restarts the task, applying the current config's period. */
    public void restart() {
        stop();
        start();
    }

    /** Whether the single global task is currently scheduled. */
    public boolean isRunning() {
        return task != null;
    }

    /** How many machines are currently cached across all loaded chunks. */
    public int trackedMachineCount() {
        return byChunk.values().stream().mapToInt(Set::size).sum();
    }

    private void seedLoadedChunks() {
        byChunk.clear();
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                indexChunk(chunk);
            }
        }
    }

    // =================================================================================
    // Cache maintenance -- the only place a chunk PDC is ever read
    // =================================================================================

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        indexChunk(event.getChunk());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent event) {
        byChunk.remove(ChunkId.of(event.getChunk()));
    }

    /**
     * Re-indexes only the affected chunk whenever {@link MachineRegistry} reports that
     * a machine's registration changed, by any path -- place, break, entity/block
     * explosion salvage, or anything added to that registry later. This is the fix for
     * the cache going stale on removal paths {@code MachineBlockListener} handles but
     * this class previously did not listen for directly (explosions in particular): by
     * reacting to the registry's own notification instead of duplicating its callers'
     * event subscriptions, every current and future removal path stays in sync for
     * free.
     */
    @Override
    public void onMachineChanged(Block block) {
        indexChunk(block.getChunk());
    }

    /**
     * Rebuilds one chunk's cache entry from the registry. This is the only path that
     * reads a chunk PDC, and it runs once per chunk load or machine place/break --
     * never on the effects task's hot path.
     */
    private void indexChunk(Chunk chunk) {
        ChunkId id = ChunkId.of(chunk);
        Set<BlockPos> positions = new LinkedHashSet<>();
        for (Block block : machines.machinesIn(chunk)) {
            positions.add(new BlockPos(block.getX(), block.getY(), block.getZ()));
        }
        if (positions.isEmpty()) {
            byChunk.remove(id);
        } else {
            byChunk.put(id, positions);
        }
    }

    // =================================================================================
    // The single global task
    // =================================================================================

    private void run() {
        EfConfig config = configSupplier.get();
        boolean enabled = config.effects().enabled();
        int period = config.effects().periodTicks();
        if (!shouldSchedule(enabled, period)) {
            // Config was reloaded to disabled between restarts; do nothing rather than
            // relying solely on the task having been cancelled.
            return;
        }
        int radius = config.effects().playerRadius();
        boolean requireSignal = config.machine().requireRedstoneSignal();
        String soundName = config.effects().sound();

        for (Map.Entry<ChunkId, Set<BlockPos>> entry : byChunk.entrySet()) {
            ChunkId id = entry.getKey();
            World world = Bukkit.getWorld(id.worldName());
            if (world == null || !world.isChunkLoaded(id.x(), id.z())) {
                // The chunk went away without an unload event we saw (world unloaded,
                // for instance). Drop it rather than touching it -- reading a block here
                // would force the chunk back into memory.
                byChunk.remove(id);
                continue;
            }
            for (BlockPos pos : entry.getValue()) {
                emitFor(world, pos, radius, requireSignal, enabled, soundName);
            }
        }
    }

    private void emitFor(World world, BlockPos pos, int radius, boolean requireSignal,
            boolean enabled, String soundName) {
        Location center = new Location(world, pos.x() + 0.5D, pos.y() + 1.05D, pos.z() + 0.5D);

        // Cheapest gate first: no observer, no work. Everything below this line --
        // including the block state read -- is skipped for an unwatched machine.
        Collection<Player> audience = world.getNearbyPlayers(center, radius);
        if (audience.isEmpty()) {
            return;
        }

        Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
        boolean powered = machineIsActive(block.getBlockPower() > 0, requireSignal);
        boolean smelting = isSmelting(block);

        boolean emitSparks = shouldEmitSparks(enabled, audience.size(), powered);
        boolean emitSmoke = shouldEmitSmoke(enabled, audience.size(), smelting);
        if (!emitSparks && !emitSmoke) {
            return;
        }

        for (Player player : audience) {
            // Per-player overloads, deliberately: the broadcast overloads would send to
            // everyone tracking the chunk and quietly ignore effects.player-radius.
            if (emitSparks) {
                player.spawnParticle(Particle.ELECTRIC_SPARK, center, SPARK_COUNT, SPREAD, SPREAD, SPREAD, 0.0D);
            }
            if (emitSmoke) {
                player.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, center, SMOKE_COUNT, SPREAD, SPREAD, SPREAD, 0.0D);
                if (soundName != null) {
                    // Null means the configured sound name did not resolve; per EfConfig
                    // that disables sound only, leaving particles playing.
                    player.playSound(center, soundName, SoundCategory.BLOCKS, SOUND_VOLUME, SOUND_PITCH);
                }
            }
        }
    }

    /**
     * Whether the machine at {@code block} currently has a smelt actively advancing
     * ({@code progressTicks > 0}), per its live {@link org.xpfarm.electricfurnace.machine.MachineState}.
     * Reads through {@link MachineStore#get}, which hydrates from the block's own PDC
     * on first access and is an in-memory lookup thereafter -- this is only ever called
     * once the nearby-player gate above has already passed, so it never runs for a
     * machine nobody is close enough to see.
     */
    private boolean isSmelting(Block block) {
        return !store.get(block).isIdle();
    }

    // =================================================================================
    // Value keys
    // =================================================================================

    /**
     * A loaded chunk's identity, by world name and chunk coordinates.
     *
     * <p>Deliberately not a {@link Chunk} reference: holding one would pin an unloaded
     * chunk in memory, and going back through {@code Chunk} to reach a block would
     * risk loading it.
     */
    private record ChunkId(String worldName, int x, int z) {
        static ChunkId of(Chunk chunk) {
            return new ChunkId(chunk.getWorld().getName(), chunk.getX(), chunk.getZ());
        }
    }

    /** Absolute block coordinates of one machine. */
    private record BlockPos(int x, int y, int z) {
    }
}
