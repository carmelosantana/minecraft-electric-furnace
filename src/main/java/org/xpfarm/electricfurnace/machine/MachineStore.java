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

import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldSaveEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.xpfarm.electricfurnace.gui.FurnaceGui;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

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
     */
    private final Map<Block, MachineState> live = new LinkedHashMap<>();

    public MachineStore(Plugin plugin, MachineRegistry machines) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.machines = Objects.requireNonNull(machines, "machines");
    }

    /**
     * The live state for {@code block}, hydrating it from the block's PDC on first
     * access. Never {@code null}: a block that is not (or is no longer) a
     * {@link TileState}, or that has never been written to, yields
     * {@link MachineState#empty()}.
     */
    public MachineState get(Block block) {
        Objects.requireNonNull(block, "block");
        return live.computeIfAbsent(block, this::hydrate);
    }

    private MachineState hydrate(Block block) {
        if (!(block.getState() instanceof TileState tile)) {
            return MachineState.empty();
        }
        try {
            PersistentDataContainer pdc = tile.getPersistentDataContainer();
            byte[] bytes = pdc.get(MachineStateCodec.KEY, PersistentDataType.BYTE_ARRAY);
            return MachineStateCodec.decode(bytes);
        } catch (RuntimeException e) {
            // Mirrors MachineRegistry's defensive stance on chunk PDC reads: a value
            // written under this key by something other than MachineStateCodec must
            // degrade this one machine to empty, never propagate into a block-access
            // path.
            warn("failed to hydrate machine state at " + describe(block) + " ("
                    + e.getClass().getSimpleName() + ": " + e.getMessage() + "); treating it as empty.");
            return MachineState.empty();
        }
    }

    /**
     * Writes {@code block}'s live state back to its PDC, if it has one in memory. A
     * no-op for a block that was never {@link #get(Block) accessed} and for a block
     * that is not a {@link TileState}.
     *
     * <h3>Closing the deferred-sync window</h3>
     *
     * <p>{@code MachineGuiListener} folds a click's item movement into a machine's
     * {@link MachineState} one tick <em>after</em> the click, because Bukkit only
     * finishes applying the move once its own event handler returns. A
     * {@link WorldSaveEvent} or {@link ChunkUnloadEvent} landing inside that one-tick
     * window would otherwise flush state one edit behind whatever is actually sitting
     * in the open GUI -- an item just placed into an input slot (already gone from the
     * player's inventory) could exist nowhere; an item just taken from the output slot
     * (an OUTPUT take is always permitted) could exist both in the player's inventory
     * and, stale, in this flush. Before encoding, this method folds any
     * currently-open GUI for {@code block} into {@code state} directly (see
     * {@link FurnaceGui#findOpenInventory}/{@link FurnaceGui#syncToState}), closing
     * that window without changing the one-tick deferral and without a polling task.
     * That sync attempt is best-effort: a failure there is logged and does not stop
     * the (possibly one-tick-stale, but still valid) in-memory state from reaching
     * disk -- this method must stay fail-soft and non-throwing, since it runs on the
     * chunk-unload path.
     */
    public void flush(Block block) {
        Objects.requireNonNull(block, "block");
        MachineState state = live.get(block);
        if (state == null) {
            return;
        }
        if (!(block.getState() instanceof TileState tile)) {
            return;
        }
        try {
            FurnaceGui.findOpenInventory(block).ifPresent(inventory -> FurnaceGui.syncToState(inventory, state));
        } catch (Throwable t) {
            warn("failed to sync an open GUI into machine state before flushing at " + describe(block) + " ("
                    + t.getClass().getName() + ": " + t.getMessage() + "); flushing the last known state instead.");
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
     * Flushes every machine {@link MachineRegistry#machinesIn} the unloading chunk,
     * then drops each from the live map -- the chunk (and the block state it backs)
     * is about to stop being addressable until it loads again.
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
                live.remove(block);
            }
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
