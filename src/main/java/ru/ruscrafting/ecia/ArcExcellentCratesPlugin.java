package ru.ruscrafting.ecia;

import org.bukkit.entity.Player;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import ru.ruscrafting.ecia.api.CrateLocationService;
import ru.ruscrafting.ecia.runtime.EciaLocale;
import ru.ruscrafting.ecia.runtime.EciaRuntime;
import ru.ruscrafting.ecia.integration.CrateOpeningEffects;
import ru.ruscrafting.ecia.integration.ItemsAdderFurnitureAccess;
import ru.ruscrafting.ecia.integration.ManagedCratesService;
import ru.ruscrafting.ecia.integration.NativePreviewRewardGrantService;
import ru.ruscrafting.ecia.integration.CrateKeyShopService;
import ru.ruscrafting.ecia.integration.ArcActivityTelemetryBridge;
import ru.arc.paper.display.PaperPacketDisplays;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.BiFunction;

public final class ArcExcellentCratesPlugin extends JavaPlugin {
    private static final String LEGACY_DATA_FOLDER = "ExcellentCratesItemsAdder";
    private CrateRegistry registry;
    private CrateProtectionListener protectionListener;
    private EciaRuntime runtime;
    private ManagedCratesService managedCrates;
    private CrateKeyShopService keyShop;
    private CrateHologramService crateHolograms;
    private CrateAmbientEffectService ambientEffects;
    private PaperPacketDisplays packetDisplays;
    private CrateVisualSettingsStore visualSettings;
    private CrateVisualEditor visualEditor;
    private CrateLocationServiceRegistration crateLocationRegistration;

    @Override
    public void onEnable() {
        ArcActivityTelemetryBridge.INSTANCE.install();
        migrateLegacyDataFolder();
        saveDefaultConfig();
        runtime = EciaRuntime.create(this);
        packetDisplays = registerService(new PaperPacketDisplays(this, "crate-visuals"));
        EciaLocale locale = runtime.installLocale(getDataFolder().toPath(), legacyMessages());
        var shopCommand = java.util.Objects.requireNonNull(getCommand("cratekeys"));
        shopCommand.setExecutor((sender, command, label, arguments) -> {
            sender.sendMessage(locale.renderPadded("key-shop.unavailable", sender, Map.of()));
            return true;
        });
        Path configuredDirectory = Path.of(getConfig().getString(
                "excellent-crates-directory",
                "plugins/ExcellentCrates/crates"
        ));
        Path crateDirectory = configuredDirectory.isAbsolute()
                ? configuredDirectory
                : getServer().getWorldContainer().toPath().resolve(configuredDirectory).normalize();

        registry = new CrateRegistry(crateDirectory, message -> runtime.warn("{}", message));
        int count = registry.reload();
        registerCrateLocationService();
        String previewCommand = getConfig().getString(
                "preview-command",
                "excellentcrates preview <crate> <player>"
        );
        protectionListener = new CrateProtectionListener(registry, runtime, locale.render("protected"), previewCommand);
        getServer().getPluginManager().registerEvents(protectionListener, this);
        visualSettings = new CrateVisualSettingsStore(this);

        long refreshTicks = Math.max(20L, getConfig().getLong("registry-refresh-ticks", 100L));
        runtime.scheduleRegistryRefresh(refreshTicks, () -> runtime.updateRegistrySize(registry.reload()));
        runtime.updateRegistrySize(count);
        runtime.info("Protecting {} ExcellentCrates furniture position(s).", count);
        NetworkKeyReceiver networkKeyReceiver = registerService(new NetworkKeyReceiver(this));
        registerNativePreviewRewardGrant();
        if (getServer().getPluginManager().isPluginEnabled("ExcellentCrates")) {
            try {
                crateHolograms = registerService(new CrateHologramService(this, visualSettings));
                ambientEffects = registerService(new CrateAmbientEffectService(this, visualSettings, packetDisplays));
            } catch (RuntimeException | LinkageError failure) {
                runtime.error("Compact crate holograms are unavailable: {}", failure.toString());
            }
        }
        if (getServer().getPluginManager().isPluginEnabled("ExcellentCrates")
                && getServer().getPluginManager().isPluginEnabled("ARC")) {
            try {
                ItemsAdderFurnitureAccess furniture = ItemsAdderFurnitureAccess.create();
                visualEditor = registerService(new CrateVisualEditor(this, visualSettings, furniture, anchor -> {
                    if (crateHolograms != null) crateHolograms.refresh(anchor);
                    if (ambientEffects != null) ambientEffects.refresh();
                    if (keyShop != null) keyShop.reload();
                }, () -> {
                    int registrySize = registry.reload();
                    runtime.updateRegistrySize(registrySize);
                }));
                protectionListener.setVisualEditorHandler(visualEditor::open);
                CrateOpeningEffects openingEffects = registerService(new CrateOpeningEffects(furniture, ambientEffects));
                registerService(new ArcCrateCommand(this, registry, furniture, networkKeyReceiver, () -> {
                    if (crateHolograms != null) crateHolograms.reload();
                    if (ambientEffects != null) ambientEffects.refresh();
                    return kotlin.Unit.INSTANCE;
                }));
                managedCrates = registerService(new ManagedCratesService(
                        this, visualSettings, openingEffects, ambientEffects, packetDisplays, furniture));
                if (getServer().getPluginManager().isPluginEnabled("RedisEconomy")) {
                    try {
                        keyShop = registerService(new CrateKeyShopService(this));
                    } catch (RuntimeException | LinkageError failure) {
                        getLogger().log(java.util.logging.Level.SEVERE,
                                "Crate key shop could not start", failure);
                    }
                }
            } catch (RuntimeException | LinkageError failure) {
                runtime.error("Managed crate service is unavailable; furniture protection remains active: {}", failure.toString());
            }
        }
    }

    @Override
    public void onDisable() {
        unregisterCrateLocationService();
        if (protectionListener != null) {
            protectionListener.clearManagedPreviewHandler();
            protectionListener.clearVisualEditorHandler();
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

    public boolean openCrateVisualEditor(Player player, CrateVisualTarget target) {
        return visualEditor != null && visualEditor.open(player, target);
    }

    /** Stable read-only boundary used by optional player-facing integrations. */
    public boolean isCrateLocation(Location location) {
        return registry != null && location != null && location.getWorld() != null
                && registry.contains(CratePosition.from(location));
    }

    private void registerCrateLocationService() {
        crateLocationRegistration = registerService(
                new CrateLocationServiceRegistration(this, this::isManagedCrateLocation)
        );
    }

    private void unregisterCrateLocationService() {
        if (crateLocationRegistration == null) return;
        crateLocationRegistration.close();
        crateLocationRegistration = null;
    }

    private boolean isManagedCrateLocation(Location location) {
        if (location == null || location.getWorld() == null) return false;

        boolean firstPartyLocation = isCrateLocation(location);
        Plugin nativeCrates = getServer().getPluginManager().getPlugin("ExcellentCrates");
        if (nativeCrates == null) return firstPartyLocation;
        if (!nativeCrates.isEnabled()) {
            throw new IllegalStateException("ExcellentCrates is installed but disabled");
        }
        return firstPartyLocation || NativeExcellentCratesLocationLookup.isCrateAt(location);
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

    /** Register an AutoCloseable feature service under the shared lifecycle. */
    public <T extends AutoCloseable> T registerService(T service) {
        if (runtime == null) {
            throw new IllegalStateException("ECIA runtime is not enabled");
        }
        return runtime.registerService(service);
    }

    private void registerNativePreviewRewardGrant() {
        PluginManager pluginManager = getServer().getPluginManager();
        Plugin excellentCrates = pluginManager.getPlugin("ExcellentCrates");
        if (excellentCrates == null || !excellentCrates.isEnabled()) return;
        Plugin nightCore = pluginManager.getPlugin("nightcore");
        if (nightCore == null || !nightCore.isEnabled()
                || !"6.6.1".equals(excellentCrates.getDescription().getVersion())
                || !"2.16.4".equals(nightCore.getDescription().getVersion())) {
            runtime.warn("Admin native-preview reward grants disabled: expected ExcellentCrates 6.6.1 and nightcore 2.16.4.");
            return;
        }
        try {
            registerService(new NativePreviewRewardGrantService(this));
        } catch (RuntimeException | LinkageError failure) {
            runtime.warn("Admin native-preview reward grants disabled: {}", failure.toString());
        }
    }

    private Map<String, String> legacyMessages() {
        Map<String, String> messages = new java.util.HashMap<>();
        for (String key : EciaLocale.REQUIRED_KEYS) {
            String value = getConfig().getString("messages." + key);
            if (value != null && !value.isBlank() && !value.toLowerCase(java.util.Locale.ROOT).contains("/ecia")) {
                messages.put(key, value);
            }
        }
        return Map.copyOf(messages);
    }

    private void migrateLegacyDataFolder() {
        Path current = getDataFolder().toPath();
        Path parent = current.getParent();
        if (parent == null) return;
        Path legacy = parent.resolve(LEGACY_DATA_FOLDER);
        if (!Files.isDirectory(legacy) || legacy.equals(current)) return;

        try {
            if (Files.exists(current)) {
                try (var entries = Files.list(current)) {
                    if (entries.findAny().isPresent()) {
                        throw new IllegalStateException("Both legacy and ArcExcellentCrates data folders contain files");
                    }
                }
                Files.delete(current);
            }
            Files.move(legacy, current);
            getLogger().info("Migrated data folder from " + LEGACY_DATA_FOLDER + " to ArcExcellentCrates.");
        } catch (IOException exception) {
            throw new IllegalStateException("Could not migrate the legacy plugin data folder", exception);
        }
    }
}
