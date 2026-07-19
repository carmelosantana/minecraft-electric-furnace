/*
 * ElectricFurnace - a redstone-powered smelter that recycles metal gear into ingots.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.electricfurnace.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.xpfarm.electricfurnace.alloy.AlloyDefinition;
import org.xpfarm.electricfurnace.alloy.AlloyRegistry;
import org.xpfarm.electricfurnace.config.EfConfig;
import org.xpfarm.electricfurnace.item.AlloyItemFactory;
import org.xpfarm.electricfurnace.item.MachineItemFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * {@code /electricfurnace <give|alloy|reload|info>}, with tab completion.
 *
 * <p><b>Every decision in this command is a pure static function.</b> Argument
 * parsing, permission resolution, and tab completion take and return plain
 * {@code String}s and enums, so {@code CommandArgsTest} covers unknown subcommands,
 * missing arguments, non-numeric amounts, and out-of-range amounts exhaustively with
 * no running server -- the pattern established by {@code MetalClassifier.resolveBranch}
 * and {@code GuiLayout.roleOf}. What is left in {@link #onCommand} is dispatch and
 * message formatting.
 *
 * <p>Parsing is deliberately strict about surplus arguments: {@code /ef give Notch 4
 * stacks} fails rather than quietly handing over four items, because silently ignoring
 * a token hides typos in exactly the commands an operator runs least often.
 */
public final class ElectricFurnaceCommand implements CommandExecutor, TabCompleter {

    /** Maximum items a single {@code give}/{@code alloy} may hand out. */
    public static final int MAX_AMOUNT = 64;

    /** Minimum items a single {@code give}/{@code alloy} may hand out. */
    public static final int MIN_AMOUNT = 1;

    private final Supplier<EfConfig> configSupplier;
    private final Supplier<AlloyRegistry> alloysSupplier;
    private final Runnable reloadAction;

    /**
     * @param configSupplier live config accessor
     * @param alloysSupplier live alloy registry accessor
     * @param reloadAction   re-reads config and re-applies it live, including
     *                       restarting the effects task on the new period
     */
    public ElectricFurnaceCommand(Supplier<EfConfig> configSupplier, Supplier<AlloyRegistry> alloysSupplier,
            Runnable reloadAction) {
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
        this.alloysSupplier = Objects.requireNonNull(alloysSupplier, "alloysSupplier");
        this.reloadAction = Objects.requireNonNull(reloadAction, "reloadAction");
    }

    // =================================================================================
    // Pure parsing -- see CommandArgsTest
    // =================================================================================

    /** The four subcommands. */
    public enum Sub {
        GIVE("give"),
        ALLOY("alloy"),
        RELOAD("reload"),
        INFO("info");

        private final String token;

        Sub(String token) {
            this.token = token;
        }

        /** The lowercase token a player types. */
        public String token() {
            return token;
        }
    }

    /**
     * The outcome of parsing an argument array: either a fully resolved invocation or
     * a single human-readable error.
     *
     * @param sub          the resolved subcommand, or {@code null} on failure
     * @param targetPlayer the {@code give} target's name, or {@code null} for "the sender"
     * @param alloyId      the {@code alloy} id, lowercased, or {@code null}
     * @param amount       the resolved amount, always within {@link #MIN_AMOUNT}..{@link #MAX_AMOUNT}
     * @param error        the failure message, or {@code null} on success
     */
    public record ParseResult(Sub sub, String targetPlayer, String alloyId, int amount, String error) {

        /** Whether parsing succeeded. */
        public boolean ok() {
            return error == null;
        }

        /**
         * The permission required to run this invocation, or {@code null} if parsing
         * failed. A failed parse deliberately carries no permission: callers check
         * permission only after a successful parse, and returning one here would invite
         * a check against a subcommand that was never resolved.
         */
        public String permission() {
            return sub == null ? null : permissionFor(sub);
        }

        static ParseResult failure(String message) {
            return new ParseResult(null, null, null, 0, message);
        }
    }

    /** Resolves a typed token to a subcommand, case-insensitively. */
    public static Optional<Sub> subOf(String token) {
        if (token == null) {
            return Optional.empty();
        }
        String normalized = token.toLowerCase(Locale.ROOT);
        for (Sub sub : Sub.values()) {
            if (sub.token().equals(normalized)) {
                return Optional.of(sub);
            }
        }
        return Optional.empty();
    }

    /** The permission each subcommand requires. */
    public static String permissionFor(Sub sub) {
        return switch (sub) {
            case GIVE, ALLOY -> "electricfurnace.give";
            case RELOAD -> "electricfurnace.reload";
            case INFO -> "electricfurnace.use";
        };
    }

    /** The usage line shown for an empty or unrecognized invocation. */
    public static String usage() {
        return "Usage: /electricfurnace <give [player] [amount] | alloy <id> [amount] | reload | info>";
    }

    /** Parses a raw argument array into an invocation or an error. Never throws. */
    public static ParseResult parse(String[] args) {
        if (args == null || args.length == 0) {
            return ParseResult.failure(usage());
        }
        Optional<Sub> resolved = subOf(args[0]);
        if (resolved.isEmpty()) {
            return ParseResult.failure("Unknown subcommand '" + args[0] + "'. " + usage());
        }

        return switch (resolved.get()) {
            case GIVE -> parseGive(args);
            case ALLOY -> parseAlloy(args);
            case RELOAD -> parseNoArgs(Sub.RELOAD, args);
            case INFO -> parseNoArgs(Sub.INFO, args);
        };
    }

    private static ParseResult parseGive(String[] args) {
        if (args.length > 3) {
            return ParseResult.failure("Too many arguments. " + usage());
        }
        String target = args.length >= 2 ? args[1] : null;
        AmountResult amount = parseAmount(args.length >= 3 ? args[2] : null);
        if (amount.error() != null) {
            return ParseResult.failure(amount.error());
        }
        return new ParseResult(Sub.GIVE, target, null, amount.value(), null);
    }

    private static ParseResult parseAlloy(String[] args) {
        if (args.length < 2) {
            return ParseResult.failure("Missing required argument <id>. " + usage());
        }
        if (args.length > 3) {
            return ParseResult.failure("Too many arguments. " + usage());
        }
        AmountResult amount = parseAmount(args.length >= 3 ? args[2] : null);
        if (amount.error() != null) {
            return ParseResult.failure(amount.error());
        }
        return new ParseResult(Sub.ALLOY, null, args[1].toLowerCase(Locale.ROOT), amount.value(), null);
    }

    private static ParseResult parseNoArgs(Sub sub, String[] args) {
        if (args.length > 1) {
            return ParseResult.failure("'" + sub.token() + "' takes no arguments. " + usage());
        }
        return new ParseResult(sub, null, null, 0, null);
    }

    /** A parsed amount, or the reason it could not be parsed. */
    private record AmountResult(int value, String error) {
    }

    /**
     * Parses an optional amount token. Absent means {@link #MIN_AMOUNT}. A non-numeric
     * token names the offending value; an out-of-range one names both bounds.
     */
    private static AmountResult parseAmount(String raw) {
        if (raw == null) {
            return new AmountResult(MIN_AMOUNT, null);
        }
        int parsed;
        try {
            parsed = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return new AmountResult(0, "'" + raw + "' is not a number.");
        }
        if (parsed < MIN_AMOUNT || parsed > MAX_AMOUNT) {
            return new AmountResult(0, "Amount must be between " + MIN_AMOUNT + " and " + MAX_AMOUNT
                    + " (got " + parsed + ").");
        }
        return new AmountResult(parsed, null);
    }

    /**
     * The subcommand tokens a sender holding exactly {@code heldPermissions} is allowed
     * to run, in {@link Sub} declaration order.
     *
     * <p>This is the pure decision behind tab completion's permission filter: a default
     * player must not see {@code give}, {@code alloy}, or {@code reload} offered as
     * completions merely because {@link #complete} used to return every subcommand
     * unconditionally.
     *
     * @param heldPermissions the permission nodes the sender currently holds
     */
    public static List<String> allowedSubcommandTokens(Set<String> heldPermissions) {
        Objects.requireNonNull(heldPermissions, "heldPermissions");
        List<String> tokens = new ArrayList<>();
        for (Sub sub : Sub.values()) {
            if (heldPermissions.contains(permissionFor(sub))) {
                tokens.add(sub.token());
            }
        }
        return List.copyOf(tokens);
    }

    /**
     * Tab completions for a partially typed argument array.
     *
     * @param args            the arguments so far; the last element is the partial token
     * @param alloyIds        known alloy ids, for {@code /electricfurnace alloy <tab>}
     * @param heldPermissions the permission nodes the sender currently holds, used to
     *                        keep an unauthorized subcommand out of the first argument's
     *                        completions
     * @return matching completions; {@code null} to defer to the server's own default
     *         completion, used for {@code give}'s player-name argument -- Bukkit only
     *         falls back to its default (online player names) on a literal {@code null},
     *         not on an empty list
     */
    public static List<String> complete(String[] args, List<String> alloyIds, Set<String> heldPermissions) {
        if (args == null || args.length == 0) {
            return List.of();
        }
        if (args.length == 1) {
            return filterByPrefix(allowedSubcommandTokens(heldPermissions), args[0]);
        }
        Sub sub = subOf(args[0]).orElse(null);
        if (args.length == 2 && sub == Sub.ALLOY) {
            return filterByPrefix(alloyIds == null ? List.of() : alloyIds, args[1]);
        }
        if (args.length == 2 && sub == Sub.GIVE) {
            return null;
        }
        // amounts are free-form; everything else takes no arguments.
        return List.of();
    }

    private static List<String> filterByPrefix(List<String> candidates, String partial) {
        String prefix = partial == null ? "" : partial.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String candidate : candidates) {
            if (candidate.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                matches.add(candidate);
            }
        }
        return List.copyOf(matches);
    }

    // =================================================================================
    // Bukkit glue
    // =================================================================================

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, @NotNull String[] args) {
        ParseResult parsed = parse(args);
        if (!parsed.ok()) {
            sender.sendMessage(Component.text(parsed.error()).color(NamedTextColor.RED));
            return true;
        }
        if (!sender.hasPermission(parsed.permission())) {
            sender.sendMessage(Component.text("You don't have permission to do that.")
                    .color(NamedTextColor.RED));
            return true;
        }

        switch (parsed.sub()) {
            case GIVE -> handleGive(sender, parsed);
            case ALLOY -> handleAlloy(sender, parsed);
            case RELOAD -> handleReload(sender);
            case INFO -> handleInfo(sender);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, @NotNull String[] args) {
        List<String> alloyIds = new ArrayList<>();
        for (AlloyDefinition definition : alloysSupplier.get().all()) {
            alloyIds.add(definition.id());
        }
        return complete(args, alloyIds, heldPermissions(sender));
    }

    /** The subset of the three distinct subcommand permissions {@code sender} holds. */
    private static Set<String> heldPermissions(CommandSender sender) {
        Set<String> held = new HashSet<>();
        for (Sub sub : Sub.values()) {
            String permission = permissionFor(sub);
            if (sender.hasPermission(permission)) {
                held.add(permission);
            }
        }
        return held;
    }

    private void handleGive(CommandSender sender, ParseResult parsed) {
        Optional<Player> target = resolveTarget(sender, parsed.targetPlayer());
        if (target.isEmpty()) {
            return;
        }
        ItemStack stack = MachineItemFactory.create();
        stack.setAmount(parsed.amount());
        giveOrDrop(target.get(), stack);

        sender.sendMessage(Component.text("Gave " + parsed.amount() + " Electric Furnace to "
                + target.get().getName() + ".").color(NamedTextColor.GREEN));
    }

    private void handleAlloy(CommandSender sender, ParseResult parsed) {
        Optional<AlloyDefinition> definition = alloysSupplier.get().get(parsed.alloyId());
        if (definition.isEmpty()) {
            sender.sendMessage(Component.text("Unknown alloy '" + parsed.alloyId()
                    + "'. Try /electricfurnace info.").color(NamedTextColor.RED));
            return;
        }
        Optional<Player> target = resolveTarget(sender, null);
        if (target.isEmpty()) {
            return;
        }
        ItemStack stack = AlloyItemFactory.create(definition.get());
        stack.setAmount(parsed.amount());
        giveOrDrop(target.get(), stack);

        sender.sendMessage(Component.text("Gave " + parsed.amount() + " "
                + definition.get().displayName() + ".").color(NamedTextColor.GREEN));
    }

    private void handleReload(CommandSender sender) {
        try {
            reloadAction.run();
            sender.sendMessage(Component.text("ElectricFurnace configuration reloaded.")
                    .color(NamedTextColor.GREEN));
        } catch (Throwable t) {
            // A reload failure must leave the plugin running on its previous config, not
            // take the server down. The same "config problems never break anything"
            // contract that governs startup applies here.
            sender.sendMessage(Component.text("Reload failed; the previous configuration is still active. "
                    + "See the server log.").color(NamedTextColor.RED));
            Bukkit.getLogger().warning("ElectricFurnace: reload failed (" + t.getClass().getName()
                    + ": " + t.getMessage() + ").");
        }
    }

    /**
     * Prints the alloy recipe table and the currently configured yields.
     *
     * <p><b>Deliberately does not print per-material ingot values.</b>
     * {@code MetalClassifier} currently assigns a flat value of 1 to every material,
     * including gear that costs up to eight ingots to craft. The resolver does not
     * consult that number today, so it is latent rather than wrong in play -- but
     * surfacing it here would turn a dormant inaccuracy into a number players read and
     * plan around. Showing no number is strictly better than showing one known to be
     * wrong.
     */
    private void handleInfo(CommandSender sender) {
        EfConfig config = configSupplier.get();
        AlloyRegistry alloys = alloysSupplier.get();

        sender.sendMessage(Component.text("Electric Furnace").color(NamedTextColor.AQUA));
        sender.sendMessage(Component.text("Yields (from config.yml):").color(NamedTextColor.GRAY));
        sender.sendMessage(Component.text("  same metal    " + config.recycling().yieldSameMetal()
                + " ingots").color(NamedTextColor.WHITE));
        sender.sendMessage(Component.text("  mixed alloy   " + config.recycling().yieldMixedAlloy()
                + " ingots").color(NamedTextColor.WHITE));
        sender.sendMessage(Component.text("  alloy remelt  " + config.recycling().yieldRemeltAlloy()
                + " ingots").color(NamedTextColor.WHITE));
        sender.sendMessage(Component.text("  input slots   " + config.recycling().slots())
                .color(NamedTextColor.WHITE));

        sender.sendMessage(Component.text("Alloy recipes:").color(NamedTextColor.GRAY));
        for (AlloyDefinition definition : alloys.all()) {
            String inputs = definition.isFallback()
                    ? "any unmatched mix of metals"
                    : String.join(" + ", new java.util.TreeSet<>(definition.inputIds()));
            sender.sendMessage(Component.text("  " + definition.id() + "  ("
                    + definition.displayName() + ")  <- " + inputs).color(NamedTextColor.WHITE));
        }
    }

    /**
     * Resolves the player who should receive an item: the named target if given, else
     * the sender when the sender is a player. Reports the problem and returns empty
     * when neither applies -- the console has no inventory to put anything in.
     */
    private Optional<Player> resolveTarget(CommandSender sender, String namedTarget) {
        if (namedTarget != null) {
            Player player = Bukkit.getPlayerExact(namedTarget);
            if (player == null) {
                sender.sendMessage(Component.text("Player '" + namedTarget + "' is not online.")
                        .color(NamedTextColor.RED));
                return Optional.empty();
            }
            return Optional.of(player);
        }
        if (sender instanceof Player player) {
            return Optional.of(player);
        }
        sender.sendMessage(Component.text("Console must name a player to give to.")
                .color(NamedTextColor.RED));
        return Optional.empty();
    }

    /** Adds to the player's inventory, dropping at their feet whatever does not fit. */
    private static void giveOrDrop(Player player, ItemStack stack) {
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(stack);
        for (ItemStack overflow : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), overflow);
        }
    }
}
