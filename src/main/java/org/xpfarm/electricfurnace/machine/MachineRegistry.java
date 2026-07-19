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
import java.util.Objects;
import java.util.Set;
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
 */
public final class MachineRegistry {

    private final Consumer<String> warn;

    /**
     * @param warn sink for warnings about malformed persisted data; must not be
     *             {@code null}. Wire this to the owning plugin's logger.
     */
    public MachineRegistry(Consumer<String> warn) {
        this.warn = Objects.requireNonNull(warn, "warn");
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
        }
    }

    /** Removes {@code block}'s location from the registered set, if present. */
    public void unregister(Block block) {
        Objects.requireNonNull(block, "block");
        Chunk chunk = block.getChunk();
        Set<MachineKey.Coord> coords = readCoords(chunk);
        if (coords.remove(relativeOf(block))) {
            writeCoords(chunk, coords);
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
        String raw = pdc.get(MaterialContract.MACHINES, PersistentDataType.STRING);
        return MachineKey.decode(raw, warn);
    }

    private static void writeCoords(Chunk chunk, Set<MachineKey.Coord> coords) {
        PersistentDataContainer pdc = chunk.getPersistentDataContainer();
        if (coords.isEmpty()) {
            pdc.remove(MaterialContract.MACHINES);
        } else {
            pdc.set(MaterialContract.MACHINES, PersistentDataType.STRING, MachineKey.encode(coords));
        }
    }
}
