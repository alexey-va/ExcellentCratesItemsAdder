package ru.ruscrafting.ecia;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

public final class ExcellentCratesItemsAdderPlugin extends JavaPlugin implements TabExecutor {
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private CrateRegistry registry;
    private CrateProtectionListener protectionListener;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Path configuredDirectory = Path.of(getConfig().getString(
                "excellent-crates-directory",
                "plugins/ExcellentCrates/crates"
        ));
        Path crateDirectory = configuredDirectory.isAbsolute()
                ? configuredDirectory
                : getServer().getWorldContainer().toPath().resolve(configuredDirectory).normalize();

        registry = new CrateRegistry(crateDirectory, message -> getLogger().warning(message));
        int count = registry.reload();
        String previewCommand = getConfig().getString(
                "preview-command",
                "excellentcrates preview <crate> <player>"
        );
        protectionListener = new CrateProtectionListener(registry, this, message("protected"), previewCommand);
        getServer().getPluginManager().registerEvents(protectionListener, this);

        var command = getCommand("ecia");
        if (command == null) {
            throw new IllegalStateException("Command ecia is missing from plugin.yml");
        }
        command.setExecutor(this);
        command.setTabCompleter(this);

        long refreshTicks = Math.max(20L, getConfig().getLong("registry-refresh-ticks", 100L));
        getServer().getScheduler().runTaskTimerAsynchronously(this, registry::reload, refreshTicks, refreshTicks);
        getLogger().info("Protecting " + count + " ExcellentCrates furniture position(s).");
    }

    @Override
    public void onDisable() {
        if (protectionListener != null) {
            protectionListener.clear();
        }
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] args
    ) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            int count = registry.reload();
            sender.sendMessage(message("reloaded", "<count>", Integer.toString(count)));
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("edit")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(message("player-only"));
                return true;
            }
            if (args.length != 2) {
                player.sendMessage(message("usage"));
                return true;
            }
            return handleEdit(player, args[1]);
        }
        sender.sendMessage(message("usage"));
        return true;
    }

    private boolean handleEdit(Player player, String argument) {
        return switch (argument.toLowerCase(Locale.ROOT)) {
            case "on" -> {
                protectionListener.setEditMode(player, true);
                player.sendMessage(message("edit-enabled"));
                yield true;
            }
            case "off" -> {
                protectionListener.setEditMode(player, false);
                player.sendMessage(message("edit-disabled"));
                yield true;
            }
            case "status" -> {
                player.sendMessage(message(protectionListener.isEditMode(player) ? "edit-status-on" : "edit-status-off"));
                yield true;
            }
            default -> {
                player.sendMessage(message("usage"));
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
            return List.of("edit", "reload").stream()
                    .filter(value -> value.startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("edit")) {
            return List.of("on", "off", "status").stream()
                    .filter(value -> value.startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        return List.of();
    }

    private Component message(String key, String... replacements) {
        String raw = getConfig().getString("messages." + key, "<red>Missing message: " + key);
        for (int index = 0; index + 1 < replacements.length; index += 2) {
            raw = raw.replace(replacements[index], replacements[index + 1]);
        }
        return miniMessage.deserialize(raw);
    }
}
