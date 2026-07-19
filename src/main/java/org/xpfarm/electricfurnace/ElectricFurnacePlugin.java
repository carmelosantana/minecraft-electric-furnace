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
 * <h2>Shutdown returns items</h2>
 *
 * <p>{@link #onDisable} calls {@link FurnaceGui#closeAll()}, which returns each
 * viewer's items <em>itself</em> rather than relying on {@code InventoryCloseEvent}.
 * Bukkit clears {@code isEnabled} before invoking {@code onDisable}, and
 * {@code SimplePluginManager} skips listeners belonging to a disabled plugin -- so
 * {@code MachineGuiListener#onClose} never fires here, and anything left to it would
 * be destroyed. See the note on {@code FurnaceGui#closeAll}.
 */
public final class ElectricFurnacePlugin extends JavaPlugin {

    private EfConfig config;
    private AlloyRegistry alloys;
    private MachineRegistry machines;
    private MachineEffects effects;

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

        step("listeners", () -> {
            getServer().getPluginManager().registerEvents(
                    new MachineBlockListener(machines, this::config), this);
            getServer().getPluginManager().registerEvents(
                    new MachineGuiListener(this, this::config, this::alloys), this);
            getServer().getPluginManager().registerEvents(
                    new RedstoneListener(machines, this::config, this::alloys), this);
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

        step("effects", () -> {
            effects = new MachineEffects(this, machines, this::config);
            getServer().getPluginManager().registerEvents(effects, this);
            effects.start();
        });

        getLogger().info("ElectricFurnace enabled (" + alloys.all().size() + " alloys, effects "
                + (effects != null && effects.isRunning() ? "on" : "off") + ").");
    }

    @Override
    public void onDisable() {
        if (effects != null) {
            effects.stop();
        }
        // Returns every open viewer's inputs, fuel, and output directly. Must not be
        // replaced with anything that depends on event dispatch -- see the class note.
        try {
            FurnaceGui.closeAll();
        } catch (Throwable t) {
            warn("ElectricFurnace: failed to close open furnace GUIs during shutdown ("
                    + t.getClass().getName() + ": " + t.getMessage() + ").");
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

    /** The single global effects loop, or {@code null} if it failed to wire. */
    public MachineEffects effects() {
        return effects;
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
