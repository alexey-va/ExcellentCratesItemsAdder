package ru.ruscrafting.ecia;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import ru.ruscrafting.ecia.runtime.EciaLocale;
import ru.ruscrafting.ecia.runtime.EciaRuntime;
import ru.ruscrafting.ecia.integration.CrateOpeningEffects;
import ru.ruscrafting.ecia.integration.ItemsAdderFurnitureAccess;
import ru.ruscrafting.ecia.integration.ManagedCratesService;

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
    private CrateHologramService crateHolograms;
    private CrateAmbientEffectService ambientEffects;
    private CrateVisualSettingsStore visualSettings;
    private CrateVisualEditor visualEditor;

    @Override
    public void onEnable() {
        migrateLegacyDataFolder();
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
        visualSettings = new CrateVisualSettingsStore(this);

        long refreshTicks = Math.max(20L, getConfig().getLong("registry-refresh-ticks", 100L));
        runtime.scheduleRegistryRefresh(refreshTicks, () -> runtime.updateRegistrySize(registry.reload()));
        runtime.updateRegistrySize(count);
        runtime.info("Protecting {} ExcellentCrates furniture position(s).", count);
        if (getServer().getPluginManager().isPluginEnabled("ExcellentCrates")) {
            try {
                crateHolograms = registerService(new CrateHologramService(this, visualSettings));
                ambientEffects = registerService(new CrateAmbientEffectService(this));
            } catch (RuntimeException | LinkageError failure) {
                runtime.error("Compact crate holograms are unavailable: {}", failure.toString());
            }
        }
        if (getServer().getPluginManager().isPluginEnabled("ExcellentCrates")
                && getServer().getPluginManager().isPluginEnabled("ARC")) {
            try {
                visualEditor = registerService(new CrateVisualEditor(this, visualSettings, anchor -> {
                    if (crateHolograms != null) crateHolograms.refresh(anchor);
                }));
                protectionListener.setVisualEditorHandler(visualEditor::open);
                ItemsAdderFurnitureAccess furniture = ItemsAdderFurnitureAccess.create();
                CrateOpeningEffects openingEffects = registerService(new CrateOpeningEffects(furniture));
                registerService(new CasePlacementEditor(this, registry, furniture, () -> {
                    if (crateHolograms != null) crateHolograms.reload();
                    if (ambientEffects != null) ambientEffects.refresh();
                    return kotlin.Unit.INSTANCE;
                }));
                managedCrates = registerService(new ManagedCratesService(this, visualSettings, openingEffects));
            } catch (RuntimeException | LinkageError failure) {
                runtime.error("Managed crate service is unavailable; furniture protection remains active: {}", failure.toString());
            }
        }
    }

    @Override
    public void onDisable() {
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
