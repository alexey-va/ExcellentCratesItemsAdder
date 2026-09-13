package ru.ruscrafting.ecia;

import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import ru.ruscrafting.ecia.runtime.EciaLocale;
import ru.ruscrafting.ecia.runtime.EciaRuntime;
import ru.ruscrafting.ecia.integration.ManagedCratesService;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;

public final class ExcellentCratesItemsAdderPlugin extends JavaPlugin implements TabExecutor {
    private static final String ADMIN_PERMISSION = "ecia.admin";
    private CrateRegistry registry;
    private CrateProtectionListener protectionListener;
    private EciaRuntime runtime;
    private BiFunction<CommandSender, String[], Boolean> delegatedCommandHandler;
    private ManagedCratesService managedCrates;
    private CrateHologramService crateHolograms;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        runtime = EciaRuntime.create(this);
        EciaLocale locale = runtime.installLocale(getDataFolder().toPath(), legacyMessages());
        Path configuredDirectory = Path.of(getConfig().getString(
                "excellent-crates-directory",
                "plugins/ExcellentCrates/crates"
        ));
        Path crateDirectory = configuredDirectory.isAbsolute()
                ? configuredDirectory
                : getServer().getWorldContainer().toPath().resolve(configuredDirectory).normalize();

        registry = new CrateRegistry(crateDirectory, message -> runtime.warn("{}", message));
        int count = registry.reload();
        String previewCommand = getConfig().getString(
                "preview-command",
                "excellentcrates preview <crate> <player>"
        );
        protectionListener = new CrateProtectionListener(registry, runtime, locale.render("protected"), previewCommand);
        getServer().getPluginManager().registerEvents(protectionListener, this);

        var command = getCommand("ecia");
        if (command == null) {
            throw new IllegalStateException("Command ecia is missing from plugin.yml");
        }
        command.setExecutor(this);
        command.setTabCompleter(this);

        long refreshTicks = Math.max(20L, getConfig().getLong("registry-refresh-ticks", 100L));
        runtime.scheduleRegistryRefresh(refreshTicks, () -> runtime.updateRegistrySize(registry.reload()));
        runtime.updateRegistrySize(count);
        runtime.info("Protecting {} ExcellentCrates furniture position(s).", count);
        if (getServer().getPluginManager().isPluginEnabled("ExcellentCrates")) {
            try {
                crateHolograms = registerService(new CrateHologramService(this));
            } catch (RuntimeException | LinkageError failure) {
                runtime.error("Compact crate holograms are unavailable: {}", failure.toString());
            }
        }
        if (getServer().getPluginManager().isPluginEnabled("ExcellentCrates")
                && getServer().getPluginManager().isPluginEnabled("ARC")) {
            try {
                managedCrates = registerService(new ManagedCratesService(this));
                setDelegatedCommandHandler(managedCrates::command);
            } catch (RuntimeException | LinkageError failure) {
                runtime.error("Managed crate service is unavailable; furniture protection remains active: {}", failure.toString());
            }
        }
    }

    @Override
    public void onDisable() {
        if (protectionListener != null) {
            protectionListener.clearManagedPreviewHandler();
            protectionListener.clear();
        }
        if (runtime != null) {
            runtime.close();
            runtime = null;
        }
    }

    /** Shared runtime for later feature-service composition. */
    public EciaRuntime runtime() {
        return runtime;
    }

    public boolean isCrateEditMode(Player player) {
        return protectionListener != null && protectionListener.isEditMode(player);
    }

    /** Register a managed preview route without coupling protection to domain services. */
    public void setManagedPreviewHandler(BiFunction<Player, String, Boolean> handler) {
        if (protectionListener == null) {
            throw new IllegalStateException("Crate protection is not enabled");
        }
        protectionListener.setManagedPreviewHandler(handler);
    }

    public void clearManagedPreviewHandler() {
        if (protectionListener != null) {
            protectionListener.clearManagedPreviewHandler();
        }
    }

    public void setManagedOpenHandler(BiFunction<Player, String, Boolean> handler) {
        if (protectionListener == null) throw new IllegalStateException("Crate protection is not enabled");
        protectionListener.setManagedOpenHandler(handler);
    }

    public void clearManagedOpenHandler() {
        if (protectionListener != null) protectionListener.clearManagedOpenHandler();
    }

    /** Register a feature command handler for subcommands unknown to this foundation. */
    public void setDelegatedCommandHandler(BiFunction<CommandSender, String[], Boolean> handler) {
        delegatedCommandHandler = handler;
    }

    /** Register an AutoCloseable feature service under the shared lifecycle. */
    public <T extends AutoCloseable> T registerService(T service) {
        if (runtime == null) {
            throw new IllegalStateException("ECIA runtime is not enabled");
        }
        return runtime.registerService(service);
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] args
    ) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission(ADMIN_PERMISSION)) {
                sender.sendMessage(message(sender, "no-permission"));
                return true;
            }
            reloadConfig();
            runtime.locale().reload(legacyMessages());
            protectionListener.setProtectedMessage(runtime.locale().render("protected"));
            int count = registry.reload();
            runtime.updateRegistrySize(count);
            if (managedCrates != null) managedCrates.reload();
            if (crateHolograms != null) crateHolograms.reload();
            sender.sendMessage(message(sender, "reloaded", "<count>", Integer.toString(count)));
            if (managedCrates != null && !managedCrates.available()) {
                sender.sendMessage(message(sender, "managed.reload-failed"));
            }
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("edit")) {
            if (!sender.hasPermission(ADMIN_PERMISSION)) {
                sender.sendMessage(message(sender, "no-permission"));
                return true;
            }
            if (!(sender instanceof Player player)) {
                sender.sendMessage(message(sender, "player-only"));
                return true;
            }
            if (args.length != 2) {
                player.sendMessage(message(player, "usage"));
                return true;
            }
            return handleEdit(player, args[1]);
        }
        if (delegatedCommandHandler != null
                && Boolean.TRUE.equals(delegatedCommandHandler.apply(sender, args))) {
            return true;
        }
        sender.sendMessage(message(sender, "usage"));
        return true;
    }

    private boolean handleEdit(Player player, String argument) {
        return switch (argument.toLowerCase(Locale.ROOT)) {
            case "on" -> {
                protectionListener.setEditMode(player, true);
                player.sendMessage(message(player, "edit-enabled"));
                yield true;
            }
            case "off" -> {
                protectionListener.setEditMode(player, false);
                player.sendMessage(message(player, "edit-disabled"));
                yield true;
            }
            case "status" -> {
                player.sendMessage(message(player, protectionListener.isEditMode(player) ? "edit-status-on" : "edit-status-off"));
                yield true;
            }
            default -> {
                player.sendMessage(message(player, "usage"));
                yield true;
            }
        };
    }

    @Override
    public @NotNull List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            @NotNull String[] args
    ) {
        if (args.length == 1) {
            var options = new java.util.ArrayList<>(List.of("history", "resume", "open", "preview"));
            if (sender.hasPermission(ADMIN_PERMISSION)) {
                options.addAll(List.of("edit", "reload", "stats", "reconcile", "inspect", "repair"));
            }
            return options.stream()
                    .filter(value -> value.startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("edit")) {
            if (!sender.hasPermission(ADMIN_PERMISSION)) return List.of();
            return List.of("on", "off", "status").stream()
                    .filter(value -> value.startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (args.length == 2 && managedCrates != null
                && List.of("open", "preview", "stats", "inspect", "repair").contains(args[0].toLowerCase(Locale.ROOT))) {
            if (!sender.hasPermission(ADMIN_PERMISSION) && !List.of("open", "preview").contains(args[0].toLowerCase(Locale.ROOT))) return List.of();
            return managedCrates.caseIds().stream()
                    .filter(value -> value.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        return List.of();
    }

    private Component message(String key, String... replacements) {
        return message(null, key, replacements);
    }

    private Component message(CommandSender audience, String key, String... replacements) {
        Map<String, String> values = new java.util.HashMap<>();
        for (int index = 0; index + 1 < replacements.length; index += 2) {
            String placeholder = replacements[index];
            if (placeholder.startsWith("<") && placeholder.endsWith(">")) {
                placeholder = placeholder.substring(1, placeholder.length() - 1);
            }
            values.put(placeholder, replacements[index + 1]);
        }
        return runtime.locale().renderPadded(key, audience, values);
    }

    private Map<String, String> legacyMessages() {
        Map<String, String> messages = new java.util.HashMap<>();
        for (String key : EciaLocale.REQUIRED_KEYS) {
            String value = getConfig().getString("messages." + key);
            if (value != null && !value.isBlank()) {
                messages.put(key, value);
            }
        }
        return Map.copyOf(messages);
    }
}
