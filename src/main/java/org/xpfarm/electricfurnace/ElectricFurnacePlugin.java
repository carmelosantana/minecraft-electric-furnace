/*
 * ElectricFurnace - a redstone-powered smelter that recycles metal gear into ingots.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.electricfurnace;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.xpfarm.electricfurnace.alloy.AlloyRegistry;
import org.xpfarm.electricfurnace.command.ElectricFurnaceCommand;
import org.xpfarm.electricfurnace.config.EfConfig;
import org.xpfarm.electricfurnace.effect.MachineEffects;
import org.xpfarm.electricfurnace.gui.FurnaceGui;
import org.xpfarm.electricfurnace.listener.MachineBlockListener;
import org.xpfarm.electricfurnace.listener.MachineGuiListener;
import org.xpfarm.electricfurnace.listener.RedstoneListener;
import org.xpfarm.electricfurnace.machine.MachineRegistry;
import org.xpfarm.electricfurnace.machine.MachineStore;
import org.xpfarm.electricfurnace.machine.MachineTicker;
import org.xpfarm.electricfurnace.recipe.MachineRecipe;

import java.util.List;

/**
 * Plugin entry point: wires the config layer, alloy registry, item layer, chunk
 * persistence, GUI/listeners, effects loop, command, and crafting recipe together.
 *
 * <h2>Startup never throws</h2>
 *
 * <p>This is a binding contract, not a nicety, and it is why {@link #onEnable} runs
 * each wiring step inside {@link #step}: a server operator with a typo in
 * {@code config.yml} -- or a Paper build where one API call has moved -- gets a
 * warning and a degraded feature, never a dead plugin and never a dead server. The
 * config layer already guarantees this for values ({@link EfConfig#load} substitutes
 * defaults and warns); {@link #step} extends the same guarantee to the wiring itself.
 *
 * <h2>Live state, held once</h2>
 *
 * <p>{@link #config} and {@link #alloys} are mutable fields handed to collaborators as
 * {@code Supplier}s rather than as values. That indirection is what makes
 * {@code /electricfurnace reload} work: {@link #reload()} swaps the fields and every
 * listener, the GUI, and the effects loop immediately see the new settings. Passing
 * the values directly would have pinned each collaborator to the config it was
 * constructed with, and a reload would silently do nothing to them.
 *
 * <h2>Shutdown never loses items</h2>
 *
 * <p>{@link #onDisable} stops {@link MachineTicker} <em>first</em>, then calls
 * {@link MachineStore#flushAll()}, then {@link FurnaceGui#closeAll(MachineStore)}, then
 * stops {@link MachineEffects} last. The ticker is stopped before anything else touches
 * machine state so nothing mutates a {@link org.xpfarm.electricfurnace.machine.MachineState}
 * out from under {@code flushAll}/{@code closeAll} mid-shutdown. {@code flushAll} then
 * runs <em>before</em> {@code closeAll} for a second, independent reason: Bukkit clears
 * {@code isEnabled} before invoking {@code onDisable}, and {@code SimplePluginManager}
 * skips listeners belonging to a disabled plugin -- so neither
 * {@code MachineGuiListener#onClose} nor {@link MachineStore}'s own
 * {@code ChunkUnloadEvent}/{@code WorldSaveEvent} handlers ever fire here. Each class
 * works around this the same way: by calling its item-safety logic directly as a
 * plain method rather than relying on event dispatch. {@code flushAll} runs first
 * because it persists every live machine's state to its own PDC; {@code closeAll}
 * runs second and makes one more per-open-GUI attempt to sync and flush that specific
 * machine, only returning items directly to the viewing player if that attempt fails.
 * See the notes on {@code MachineStore#flushAll} and {@code FurnaceGui#closeAll}. All
 * three steps -- stopping the ticker, {@code flushAll}, and {@code closeAll} -- are
 * individually wrapped in their own {@code try}/{@code catch}: an unguarded throw
 * from any one of them would abort {@code onDisable} before the steps after it ever
 * ran, and for the ticker in particular, which runs first, that would mean losing
 * every live machine's state before {@code flushAll} got a chance to persist it.
 */
public final class ElectricFurnacePlugin extends JavaPlugin {

    private EfConfig config;
    private AlloyRegistry alloys;
    private MachineRegistry machines;
    private MachineStore store;
    private MachineEffects effects;
    private MachineTicker ticker;

    @Override
    public void onEnable() {
        step("configuration", () -> {
            saveDefaultConfig();
            loadConfiguration();
        });

        // Every later step depends on config/alloys being non-null. If the step above
        // somehow failed anyway, substitute the all-defaults configuration rather than
        // continuing with nulls -- degraded, but running.
        if (config == null) {
            config = EfConfig.load(null, this::warn);
        }
        if (alloys == null) {
            alloys = AlloyRegistry.fromDefinitions(List.of(), this::warn);
        }

        step("machine registry", () -> machines = new MachineRegistry(this::warn));

        step("machine store", () -> {
            store = new MachineStore(this, machines);
            getServer().getPluginManager().registerEvents(store, this);
            // MachineStore#onChunkLoad only covers chunks that load AFTER the line
            // above registers it. A chunk already resident in memory when the plugin
            // enables -- the common case on a server restart -- would otherwise never
            // hydrate at all, and a mid-smelt machine in it would silently stop
            // running until something incidental (a GUI open, a redstone change)
            // happened to touch it. See MachineStore's class-level "Every path a
            // machine can (re-)enter memory" note.
            store.hydrateLoadedChunks();
        });

        // Ordering below this point follows the task spec exactly: config -> registry
        // -> store -> effects -> ticker -> listeners. Effects and the ticker are both
        // schedule-only until start()/registerEvents() run, so nothing observable
        // depends on this order today, but keeping it matches the documented contract
        // and keeps onDisable's mirrored (reverse-ish) order easy to reason about.
        step("effects", () -> {
            effects = new MachineEffects(this, machines, store, this::config);
            getServer().getPluginManager().registerEvents(effects, this);
            effects.start();
        });

        step("ticker", () -> {
            ticker = new MachineTicker(this, store, this::config, this::alloys);
            ticker.start();
        });

        step("listeners", () -> {
            getServer().getPluginManager().registerEvents(
                    new MachineBlockListener(machines, store, this::config), this);
            getServer().getPluginManager().registerEvents(
                    new MachineGuiListener(this::config), this);
            getServer().getPluginManager().registerEvents(
                    new RedstoneListener(machines, store, this::config), this);
        });

        step("command", () -> {
            ElectricFurnaceCommand executor =
                    new ElectricFurnaceCommand(this::config, this::alloys, this::reload);
            PluginCommand command = getCommand("electricfurnace");
            if (command == null) {
                warn("ElectricFurnace: command 'electricfurnace' is missing from plugin.yml; "
                        + "the command will not be available.");
                return;
            }
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        });

        step("crafting recipe", () -> {
            MachineRecipe.register();
            getServer().getPluginManager().registerEvents(new MachineRecipe(), this);
        });

        getLogger().info("ElectricFurnace enabled (" + alloys.all().size() + " alloys, effects "
                + (effects != null && effects.isRunning() ? "on" : "off") + ", ticker "
                + (ticker != null && ticker.isRunning() ? "on" : "off") + ").");
    }

    @Override
    public void onDisable() {
        // Stopped FIRST, before anything else touches machine state: once this
        // returns, nothing on the server is still mutating a MachineState, so
        // flushAll/closeAll below see a value that will not change out from under them
        // mid-shutdown. Guarded like the two steps below it: task.cancel() throwing
        // (unwrapped) would abort onDisable before flushAll ever runs, losing every
        // live machine's state -- the one thing this ordering exists to prevent.
        if (ticker != null) {
            try {
                ticker.stop();
            } catch (Throwable t) {
                warn("ElectricFurnace: failed to stop the machine ticker during shutdown ("
                        + t.getClass().getName() + ": " + t.getMessage() + ").");
            }
        }
        // Persists every live machine's contents to its own block PDC directly. Must
        // run BEFORE FurnaceGui.closeAll() and must not be replaced with anything that
        // depends on event dispatch -- see the class note.
        //
        // The unloaded-chunk-including variant, deliberately: a machine retained by
        // MachineStore#evictable can sit in an unloaded chunk with state its viewer
        // changed after the unload flush, and there is no later flush to catch it. See
        // MachineStore#flushAllIncludingUnloadedChunks.
        if (store != null) {
            try {
                store.flushAllIncludingUnloadedChunks();
            } catch (Throwable t) {
                warn("ElectricFurnace: failed to flush machine states during shutdown ("
                        + t.getClass().getName() + ": " + t.getMessage() + ").");
            }
        }
        // Makes one more per-viewer attempt to sync and flush each open GUI's contents,
        // falling back to returning items directly only if that fails. Must not be
        // replaced with anything that depends on event dispatch -- see the class note.
        try {
            FurnaceGui.closeAll(store);
        } catch (Throwable t) {
            warn("ElectricFurnace: failed to close open furnace GUIs during shutdown ("
                    + t.getClass().getName() + ": " + t.getMessage() + ").");
        }
        // Stopped last: nothing above depends on effects still running, and stopping
        // it earlier would buy nothing -- effects only ever read state, never mutate
        // it, so it was never part of the item-safety ordering above.
        if (effects != null) {
            effects.stop();
        }
        MachineRecipe.unregister();
    }

    /**
     * Re-reads {@code config.yml} and re-applies it live: new values reach every
     * collaborator through the suppliers, and the effects task is restarted so a
     * changed {@code effects.period-ticks} takes effect without a server restart.
     *
     * <p>Invoked by {@code /electricfurnace reload}. If reloading throws, the previous
     * config and alloy registry stay in place -- the fields are only swapped after both
     * parse successfully.
     */
    public void reload() {
        reloadConfig();
        loadConfiguration();
        if (effects != null) {
            effects.restart();
        }
        MachineRecipe.register();
    }

    private void loadConfiguration() {
        EfConfig loadedConfig = EfConfig.load(getConfig(), this::warn);
        AlloyRegistry loadedAlloys = AlloyRegistry.load(getConfig().getConfigurationSection("alloys"), this::warn);
        // Swap only once both have been built, so a failure part-way leaves the
        // previously-working pair intact rather than a mismatched half-update.
        this.config = loadedConfig;
        this.alloys = loadedAlloys;
    }

    /** The current, live configuration. */
    public EfConfig config() {
        return config;
    }

    /** The current, live alloy registry. */
    public AlloyRegistry alloys() {
        return alloys;
    }

    /** The chunk-backed machine location registry. */
    public MachineRegistry machines() {
        return machines;
    }

    /** The block-PDC-backed machine contents/run-state store, or {@code null} if it failed to wire. */
    public MachineStore store() {
        return store;
    }

    /** The single global effects loop, or {@code null} if it failed to wire. */
    public MachineEffects effects() {
        return effects;
    }

    /** The single global machine ticker, or {@code null} if it failed to wire. */
    public MachineTicker ticker() {
        return ticker;
    }

    private void warn(String message) {
        getLogger().warning(message);
    }

    /**
     * Runs one wiring step, converting any failure into a warning.
     *
     * <p>Catches {@link Throwable} on purpose. A {@code NoSuchMethodError} or
     * {@code NoClassDefFoundError} from a Paper API change is an {@link Error}, not an
     * {@link Exception}, and is exactly the kind of failure most likely to hit this
     * path on a server upgrade. Losing one subsystem with a clear log line beats
     * failing to enable at all.
     */
    private void step(String name, Runnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            warn("ElectricFurnace: failed to initialise " + name + " (" + t.getClass().getName()
                    + ": " + t.getMessage() + "). That feature is unavailable; the plugin is still enabled.");
        }
    }
}
