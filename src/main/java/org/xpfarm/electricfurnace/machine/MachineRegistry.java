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

import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.xpfarm.electricfurnace.item.MaterialContract;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Remembers which block locations are Electric Furnaces, across server restarts.
 *
 * <p>Machine locations are stored in the owning <b>chunk's</b>
 * {@code PersistentDataContainer} under {@link MaterialContract#MACHINES}, encoded by
 * the pure {@link MachineKey}. There is no flat file and no database -- machines
 * load and unload naturally with their chunks.
 *
 * <p>This class is the thin Bukkit-facing glue: it converts a {@link Block} to and
 * from a chunk-relative {@link MachineKey.Coord} and reads/writes the chunk PDC.
 * All encode/decode logic -- including the "never throw, skip malformed entries"
 * contract -- lives in {@link MachineKey}, which is why that class (not this one) is
 * unit tested without a server. A malformed persisted entry is skipped with a
 * warning by {@link MachineKey#decode}; it is never allowed to propagate as an
 * exception into a Bukkit chunk-load path.
 *
 * <h2>Change notification -- the single choke point for cache consumers</h2>
 *
 * <p>{@link #register} and {@link #unregister} are the only two places a machine's
 * registration ever actually changes, no matter which of this plugin's several
 * listeners triggered the call -- placing, breaking, an explosion salvage, or any
 * future removal path. Anything that needs to stay in sync with the registered set
 * (such as {@code MachineEffects}'s per-chunk cache) subscribes once via
 * {@link #addChangeListener} instead of adding its own parallel set of
 * {@code BlockPlaceEvent}/{@code BlockBreakEvent}/explosion/piston handlers. Two
 * independently maintained listener sets drifting apart -- one noticing a removal
 * path the other does not -- is exactly the class of bug this indirection exists to
 * prevent; see {@code MachineBlockListener}'s explosion and piston handling, all of
 * which reach {@code MachineEffects} through this one path rather than through
 * duplicated event subscriptions.
 */
public final class MachineRegistry {

    private final Consumer<String> warn;
    private final List<ChangeListener> changeListeners = new CopyOnWriteArrayList<>();

    /**
     * @param warn sink for warnings about malformed persisted data; must not be
     *             {@code null}. Wire this to the owning plugin's logger.
     */
    public MachineRegistry(Consumer<String> warn) {
        this.warn = Objects.requireNonNull(warn, "warn");
    }

    /**
     * Notified whenever a machine's registration changes -- added or removed -- by
     * whichever caller triggered {@link #register} or {@link #unregister}.
     */
    public interface ChangeListener {
        /**
         * {@code block}'s registration just changed. The listener does not need to
         * know whether this was an add or a remove: the registry is already the
         * source of truth, so re-reading it (e.g. re-indexing the block's chunk) is
         * always correct and simpler than tracking the two cases separately.
         */
        void onMachineChanged(Block block);
    }

    /** Subscribes {@code listener} to every future {@link #register}/{@link #unregister}. */
    public void addChangeListener(ChangeListener listener) {
        changeListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /** Whether {@code block}'s location is registered as an Electric Furnace. */
    public boolean isMachine(Block block) {
        Objects.requireNonNull(block, "block");
        return readCoords(block.getChunk()).contains(relativeOf(block));
    }

    /** Registers {@code block}'s location as an Electric Furnace. */
    public void register(Block block) {
        Objects.requireNonNull(block, "block");
        Chunk chunk = block.getChunk();
        Set<MachineKey.Coord> coords = readCoords(chunk);
        if (coords.add(relativeOf(block))) {
            writeCoords(chunk, coords);
            notifyChanged(block);
        }
    }

    /** Removes {@code block}'s location from the registered set, if present. */
    public void unregister(Block block) {
        Objects.requireNonNull(block, "block");
        Chunk chunk = block.getChunk();
        Set<MachineKey.Coord> coords = readCoords(chunk);
        if (coords.remove(relativeOf(block))) {
            writeCoords(chunk, coords);
            notifyChanged(block);
        }
    }

    private void notifyChanged(Block block) {
        for (ChangeListener listener : changeListeners) {
            listener.onMachineChanged(block);
        }
    }

    /** All registered machine blocks in {@code chunk}. */
    public Set<Block> machinesIn(Chunk chunk) {
        Objects.requireNonNull(chunk, "chunk");
        World world = chunk.getWorld();
        int baseX = chunk.getX() << 4;
        int baseZ = chunk.getZ() << 4;

        Set<Block> blocks = new HashSet<>();
        for (MachineKey.Coord coord : readCoords(chunk)) {
            blocks.add(world.getBlockAt(baseX + coord.x(), coord.y(), baseZ + coord.z()));
        }
        return blocks;
    }

    private static MachineKey.Coord relativeOf(Block block) {
        return new MachineKey.Coord(block.getX() & 15, block.getY(), block.getZ() & 15);
    }

    private Set<MachineKey.Coord> readCoords(Chunk chunk) {
        PersistentDataContainer pdc = chunk.getPersistentDataContainer();
        String raw;
        try {
            if (!pdc.has(MaterialContract.MACHINES, PersistentDataType.STRING)) {
                return new HashSet<>();
            }
            raw = pdc.get(MaterialContract.MACHINES, PersistentDataType.STRING);
        } catch (RuntimeException e) {
            // Some PersistentDataContainer implementations throw IllegalArgumentException
            // (rather than returning null) when the stored NBT primitive under this key is
            // not a STRING -- e.g. written by another plugin, a bug, or hand-edited chunk
            // data. That exception must never escape into Bukkit's chunk-load path: losing
            // this one chunk's machine registrations degrades the plugin, but throwing here
            // breaks the world. Degrade, never throw.
            warn.accept("ElectricFurnace machine PDC: chunk [" + chunk.getWorld().getName() + " "
                    + chunk.getX() + "," + chunk.getZ() + "] has a non-STRING value under '"
                    + MaterialContract.MACHINES + "'; treating chunk as having no machines ("
                    + e.getClass().getSimpleName() + ": " + e.getMessage() + ").");
            return new HashSet<>();
        }
        return MachineKey.decode(raw, warn);
    }

    private void writeCoords(Chunk chunk, Set<MachineKey.Coord> coords) {
        PersistentDataContainer pdc = chunk.getPersistentDataContainer();
        try {
            if (coords.isEmpty()) {
                pdc.remove(MaterialContract.MACHINES);
            } else {
                pdc.set(MaterialContract.MACHINES, PersistentDataType.STRING, MachineKey.encode(coords));
            }
        } catch (RuntimeException e) {
            // Mirror the defensive stance of readCoords: a write failure (e.g. an
            // incompatible existing NBT primitive under this key) must degrade this
            // plugin's bookkeeping for the chunk, not propagate into the caller's
            // block-place/break event handling.
            warn.accept("ElectricFurnace machine PDC: failed to write machine set for chunk ["
                    + chunk.getWorld().getName() + " " + chunk.getX() + "," + chunk.getZ() + "] ("
                    + e.getClass().getSimpleName() + ": " + e.getMessage() + ").");
        }
    }
}
