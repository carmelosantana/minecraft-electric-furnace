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
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldSaveEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds every currently-in-memory {@link MachineState}, hydrating it from a machine
 * block's own {@code PersistentDataContainer} on first access and flushing it back on
 * every path a machine can leave memory.
 *
 * <p>Unlike {@link MachineRegistry}, which persists machine <b>locations</b> in the
 * owning chunk's PDC, this class persists each machine's <b>contents and run state</b>
 * in the machine <em>block's own</em> PDC, via {@link MachineStateCodec}. The two are
 * deliberately separate: a machine's location rarely changes and is cheap to keep
 * fully in the chunk PDC, while a machine's contents change every tick it is running
 * and only need to reach disk when the machine is about to leave memory.
 *
 * <h2>The one line that decides whether this class works at all</h2>
 *
 * <p>{@link #flush(Block)} writes the encoded state into the {@link TileState}'s PDC
 * and then calls {@code TileState.update(false, false)}. Skipping that call is the
 * single most common way this class of code silently loses every item in every
 * machine: a {@link TileState} snapshot's PDC edits are otherwise never written back
 * to the actual block, so the change is discarded the moment the snapshot is
 * garbage-collected, and the next hydration reads whatever was there before. See
 * {@code MachineStoreTest} -- to the extent this class's behavior is not itself
 * Bukkit glue, that discipline is documented there and enforced by review, since a
 * {@link TileState} cannot be constructed without a running server.
 *
 * <h2>Every path a machine can leave memory</h2>
 *
 * <ul>
 *   <li><b>Chunk unload.</b> {@link #onChunkUnload} flushes every machine
 *       {@link MachineRegistry#machinesIn} that chunk, one at a time, then drops each
 *       from the live map -- mirroring {@code MachineEffects}'s use of the same
 *       registry query to stay chunk-accurate without ever scanning a world.</li>
 *   <li><b>World save.</b> {@link #onWorldSave} calls {@link #flushAll()} so a machine
 *       that never unloads (a player parked nearby) still reaches disk on every
 *       autosave, not only on a clean shutdown.</li>
 *   <li><b>Plugin shutdown.</b> {@code ElectricFurnacePlugin#onDisable} calls
 *       {@link #flushAll()} directly -- <em>before</em> {@code FurnaceGui.closeAll()}.
 *       By the time {@code onDisable} runs, {@code isEnabled} is already {@code false}
 *       and Bukkit's {@code SimplePluginManager} skips event dispatch to a disabled
 *       plugin, so nothing that depends on an event ever fires here. {@code flushAll}
 *       is a direct method call, not an event handler, so it is unaffected.</li>
 *   <li><b>Block break.</b> {@code MachineBlockListener} drops the machine's live
 *       contents at the block and calls {@link #forget(Block)} -- there is nothing to
 *       flush, because the block (and its PDC) is about to stop being a machine.</li>
 * </ul>
 *
 * <h2>Every path a machine can (re-)enter memory</h2>
 *
 * <p>A machine leaving memory above does not mean its run stops -- its contents and
 * run state are on disk in the block's own PDC, and {@link MachineTicker} only ever
 * ticks what is in this store's {@link #live} map. A machine's PDC being correct is
 * therefore not enough by itself; something must also call {@link #get} to bring it
 * back into that map, or it silently sits idle forever even though its persisted
 * state says it should be smelting.
 *
 * <ul>
 *   <li><b>Chunk load.</b> {@link #onChunkLoad} hydrates every machine
 *       {@link MachineRegistry#machinesIn} the newly-loaded chunk, one at a time, so a
 *       machine mid-run when its chunk unloaded resumes ticking the moment the chunk
 *       loads again -- without waiting for a GUI open, a redstone change, or
 *       {@code MachineEffects}'s nearby-player-gated read to happen to touch it
 *       first.</li>
 *   <li><b>Plugin enable.</b> {@code ElectricFurnacePlugin#onEnable} calls
 *       {@link #hydrateLoadedChunks()} once, directly, mirroring
 *       {@code MachineEffects#seedLoadedChunks}. {@link #onChunkLoad} alone only
 *       covers chunks that load <em>after</em> this listener is registered; every
 *       chunk already resident in memory when the plugin enables -- the common case on
 *       a server restart, since a chunk a machine sits in is usually already loaded by
 *       the time plugins finish enabling -- would otherwise never hydrate at all. This
 *       is the fix for the walk-away case this store exists to serve: load the
 *       machine, leave, come back to find it still running.</li>
 * </ul>
 *
 * <h2>One bad machine must not cost every other machine in its chunk</h2>
 *
 * <p>{@link #onChunkUnload} and {@link #flushAll()} each wrap every individual
 * machine's flush in its own {@code try}/{@code catch}: a corrupt block state or a
 * PDC write failure for one machine is logged and skipped, never allowed to abort the
 * loop and strand the rest of that chunk's (or the server's) machines unflushed.
 * Nothing in any handler here may throw -- an exception escaping a chunk-unload path
 * breaks the server's chunk handling for everyone, not just this plugin.
 */
public final class MachineStore implements Listener {

    private final Plugin plugin;
    private final MachineRegistry machines;

    /**
     * Every machine currently held in memory, keyed by block. {@link Block} equality
     * is world+coordinate based (see {@link MachineRegistry#machinesIn}), so the same
     * physical machine always maps to the same entry regardless of which {@code Block}
     * instance is used to look it up.
     *
     * <h3>Why this is concurrent</h3>
     *
     * <p>{@code MachineEffects#byChunk} documents that "chunk load/unload events may
     * arrive off the main thread on Paper" and uses a {@link ConcurrentHashMap} for
     * exactly that reason. {@link #onChunkUnload} reacts to that very same
     * {@link ChunkUnloadEvent} and removes from this map -- so if that javadoc is
     * right, this map is written from the same off-main-thread path {@code byChunk}
     * was built to survive. A plain {@link java.util.LinkedHashMap} here previously
     * disagreed with that assumption while sharing the same event: {@link #get} (via
     * {@code computeIfAbsent}) and {@link #liveStates} could then race a concurrent
     * {@link #onChunkUnload} removal and throw {@code ConcurrentModificationException}
     * (or worse, corrupt the map's internal structure, since {@code LinkedHashMap} is
     * not safe for concurrent structural modification even without an active
     * iterator). Rather than have two collaborators in this plugin assert
     * contradictory things about the same events, this map now matches
     * {@code MachineEffects}'s documented assumption. {@link MachineTicker#run()}
     * additionally snapshots {@link #liveStates()}'s entries before iterating, as a
     * second, independent guard -- the two mitigations are complementary: this field
     * being concurrent stops the map itself from being corrupted by a racing write,
     * while the ticker's snapshot stops a mid-pass structural change (even a
     * perfectly safe one) from skipping or double-visiting an entry within one tick.
     */
    private final Map<Block, MachineState> live = new ConcurrentHashMap<>();

    public MachineStore(Plugin plugin, MachineRegistry machines) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.machines = Objects.requireNonNull(machines, "machines");
    }

    /**
     * The live state for {@code block}, hydrating it from the block's PDC on first
     * access. Never {@code null}: a block that is not (or is no longer) a
     * {@link TileState}, or that has never been written to, yields
     * {@link MachineState#empty(Block)}.
     */
    public MachineState get(Block block) {
        Objects.requireNonNull(block, "block");
        return live.computeIfAbsent(block, this::hydrate);
    }

    private MachineState hydrate(Block block) {
        if (!(block.getState() instanceof TileState tile)) {
            return MachineState.empty(block);
        }
        try {
            PersistentDataContainer pdc = tile.getPersistentDataContainer();
            byte[] bytes = pdc.get(MachineStateCodec.KEY, PersistentDataType.BYTE_ARRAY);
            return MachineStateCodec.decode(block, bytes);
        } catch (RuntimeException e) {
            // Mirrors MachineRegistry's defensive stance on chunk PDC reads: a value
            // written under this key by something other than MachineStateCodec must
            // degrade this one machine to empty, never propagate into a block-access
            // path.
            warn("failed to hydrate machine state at " + describe(block) + " ("
                    + e.getClass().getSimpleName() + ": " + e.getMessage() + "); treating it as empty.");
            return MachineState.empty(block);
        }
    }

    /**
     * Writes {@code block}'s live state back to its PDC, if it has one in memory. A
     * no-op for a block that was never {@link #get(Block) accessed} and for a block
     * that is not a {@link TileState}.
     *
     * <h3>There is no open-GUI window to close any more</h3>
     *
     * <p>This method used to reach into {@code FurnaceGui}, find any currently-open GUI
     * for {@code block}, and fold it into {@code state} before encoding -- because a
     * click's effect reached the GUI inventory a tick before it reached
     * {@link MachineState}, and a {@link WorldSaveEvent} or {@link ChunkUnloadEvent}
     * landing in between would have persisted state one edit behind what the player could
     * see. That is gone, along with the dependency on the view package that came with it:
     * a machine's items live in exactly one inventory, which is what the player clicked
     * and what {@link MachineStateCodec} reads here. A flush can no longer be stale.
     */
    public void flush(Block block) {
        Objects.requireNonNull(block, "block");
        MachineState state = live.get(block);
        if (state == null) {
            return;
        }
        if (!(block.getState() instanceof TileState tile)) {
            // Another plugin (or a world edit) replaced the block while it was still
            // registered as a machine. The in-memory state has nowhere to go -- this is
            // silent item loss unless it is at least logged, since nothing else observes
            // this path. See the class-level "one line that decides whether this class
            // works at all" note: a lost write here is exactly as invisible as a missed
            // TileState#update call, just from the opposite direction.
            warn("cannot flush machine state at " + describe(block) + ": block is no longer a TileState "
                    + "(now " + block.getType() + "); its in-memory contents could not be persisted.");
            return;
        }
        PersistentDataContainer pdc = tile.getPersistentDataContainer();
        pdc.set(MachineStateCodec.KEY, PersistentDataType.BYTE_ARRAY, MachineStateCodec.encode(state));
        // Without this call the PDC write above is discarded -- see the class note.
        tile.update(false, false);
    }

    /**
     * Flushes every currently live machine. Each machine's flush is independently
     * guarded, so one bad block cannot prevent the rest from reaching disk.
     */
    public void flushAll() {
        for (Block block : live.keySet()) {
            try {
                flush(block);
            } catch (Throwable t) {
                warn("failed to flush machine state at " + describe(block) + " ("
                        + t.getClass().getName() + ": " + t.getMessage() + "); its in-memory state "
                        + "is unchanged, but this flush did not reach disk.");
            }
        }
    }

    /**
     * Drops {@code block}'s live state and clears its persisted PDC entry. Intended
     * for block break: the block is about to stop being a machine, so there is
     * nothing left to flush.
     */
    public void forget(Block block) {
        Objects.requireNonNull(block, "block");
        live.remove(block);
        if (!(block.getState() instanceof TileState tile)) {
            return;
        }
        try {
            tile.getPersistentDataContainer().remove(MachineStateCodec.KEY);
            tile.update(false, false);
        } catch (RuntimeException e) {
            warn("failed to clear persisted machine state at " + describe(block) + " ("
                    + e.getClass().getSimpleName() + ": " + e.getMessage() + ").");
        }
    }

    /** An unmodifiable view of every currently live machine, for the ticker. */
    public Map<Block, MachineState> liveStates() {
        return Collections.unmodifiableMap(live);
    }

    /**
     * Hydrates every machine {@link MachineRegistry#machinesIn} the newly-loaded
     * chunk, so a machine mid-run resumes ticking the instant its chunk is available
     * again -- see the class-level "Every path a machine can (re-)enter memory" note.
     * Each machine's hydration is independently guarded, mirroring
     * {@link #onChunkUnload}: one corrupt block must not stop the rest of the chunk's
     * machines from coming back to life.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        hydrateChunk(event.getChunk());
    }

    /**
     * Hydrates every registered machine in every already-loaded chunk, across every
     * world. Called once from {@code ElectricFurnacePlugin#onEnable}, mirroring
     * {@code MachineEffects#seedLoadedChunks} -- {@link #onChunkLoad} only covers
     * chunks that load <em>after</em> this listener is registered, so without this a
     * chunk already resident in memory at plugin enable (the common case on a server
     * restart) would never hydrate at all. Never throws: a failure walking one world
     * or one chunk must not stop the rest from being seeded.
     */
    public void hydrateLoadedChunks() {
        try {
            for (World world : Bukkit.getWorlds()) {
                for (Chunk chunk : world.getLoadedChunks()) {
                    hydrateChunk(chunk);
                }
            }
        } catch (Throwable t) {
            warn("failed to hydrate already-loaded chunks at startup (" + t.getClass().getName() + ": "
                    + t.getMessage() + "); machines in those chunks will not resume until something else "
                    + "touches them (a GUI open, a redstone change, or their chunk unloading and reloading).");
        }
    }

    /**
     * Hydrates every machine {@link MachineRegistry#machinesIn} one chunk, guarding
     * each individually. Shared by {@link #onChunkLoad} and
     * {@link #hydrateLoadedChunks()} so both paths hydrate exactly the same way.
     */
    private void hydrateChunk(Chunk chunk) {
        for (Block block : machines.machinesIn(chunk)) {
            try {
                get(block);
            } catch (Throwable t) {
                warn("failed to hydrate machine state at " + describe(block) + " during chunk load ("
                        + t.getClass().getName() + ": " + t.getMessage() + "); it will not resume until "
                        + "something else touches it.");
            }
        }
    }

    /**
     * Flushes every machine {@link MachineRegistry#machinesIn} the unloading chunk, then
     * drops each from the live map -- the chunk (and the block state it backs) is about
     * to stop being addressable until it loads again.
     *
     * <h3>A machine somebody is still looking at is not evicted</h3>
     *
     * <p>{@link #evictable} is the guard, and it exists because a machine's items now
     * live in the inventory its viewers have open. Evicting a viewed machine would let
     * the next {@link #get} hydrate a <em>second</em> {@link MachineState}, with a second
     * inventory, from the PDC this method just wrote -- while the viewer carried on
     * editing the first one. Every item in that machine would then exist twice: once in
     * the orphaned inventory the player can still take from, and once in the state
     * everything else now uses. Keeping a viewed machine live keeps it a single object.
     *
     * <p>The machine simply stays in the map until a later unload finds it unviewed;
     * {@link MachineTicker} already declines to advance a machine whose chunk is not
     * loaded, so a retained entry costs a map slot and nothing else. This is close to
     * unreachable in practice -- a player with a GUI open is standing next to the block,
     * holding its chunk loaded -- which is exactly why it is worth guarding rather than
     * arguing about.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent event) {
        for (Block block : machines.machinesIn(event.getChunk())) {
            try {
                flush(block);
            } catch (Throwable t) {
                warn("failed to flush machine state at " + describe(block) + " during chunk unload ("
                        + t.getClass().getName() + ": " + t.getMessage() + "); this machine's most "
                        + "recent in-memory progress may be lost, but the rest of the chunk is unaffected.");
            } finally {
                if (evictable(block)) {
                    live.remove(block);
                }
            }
        }
    }

    /**
     * Whether {@code block}'s machine can safely leave the live map: it has no state, or
     * its inventory has no viewers. See {@link #onChunkUnload}.
     *
     * <p>Never throws, and fails <em>closed</em> (keeping the machine live): a failure to
     * establish that nobody is watching must not be read as "nobody is watching."
     */
    private boolean evictable(Block block) {
        MachineState state = live.get(block);
        if (state == null) {
            return true;
        }
        try {
            return state.getInventory().getViewers().isEmpty();
        } catch (Throwable t) {
            warn("could not determine whether anyone is viewing the machine at " + describe(block)
                    + " (" + t.getClass().getName() + ": " + t.getMessage() + "); keeping it in memory "
                    + "rather than risk splitting its contents across two inventories.");
            return false;
        }
    }

    /** Reaches disk on every autosave, not only on a clean shutdown. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldSave(WorldSaveEvent event) {
        try {
            flushAll();
        } catch (Throwable t) {
            warn("flushAll failed during world save (" + t.getClass().getName() + ": "
                    + t.getMessage() + ").");
        }
    }

    private void warn(String message) {
        plugin.getLogger().warning("ElectricFurnace: " + message);
    }

    private static String describe(Block block) {
        return block.getWorld().getName() + " " + block.getX() + "," + block.getY() + "," + block.getZ();
    }
}
