package ru.ruscrafting.ecia;

import org.bukkit.entity.Player;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import ru.ruscrafting.ecia.api.CrateLocationService;
import ru.ruscrafting.ecia.runtime.EciaLocale;
import ru.ruscrafting.ecia.runtime.EciaRuntime;
import ru.ruscrafting.ecia.integration.CrateOpeningEffects;
import ru.ruscrafting.ecia.integration.ItemsAdderFurnitureAccess;
import ru.ruscrafting.ecia.integration.ManagedCratesService;
import ru.ruscrafting.ecia.integration.CrateKeyShopService;
import ru.ruscrafting.ecia.integration.ArcActivityTelemetryBridge;
import ru.arc.paper.display.PaperPacketDisplays;
import ru.arc.paper.api.ArcNativeCleanupService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;

public class ArcExcellentCratesPlugin extends JavaPlugin {
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
    private ru.arc.core.LifecycleTaskScope bootstrapTasks;
    private static final class StorageGeneration {
        final ru.arc.core.BoundedAsyncExecutor worker = new ru.arc.core.BoundedAsyncExecutor("arc-excellent-crates", 1, 256);
        final CompletableFuture<kotlin.Unit> drain = new CompletableFuture<>();
        final ArcNativeCleanupService nativeCleanup;
        EciaRuntime runtime;
        CrateVisualSettingsStore visuals;
        StorageGeneration(ArcNativeCleanupService nativeCleanup) { this.nativeCleanup = nativeCleanup; }
    }
    private StorageGeneration generation;
    private CompletableFuture<kotlin.Unit> predecessor = CompletableFuture.completedFuture(kotlin.Unit.INSTANCE);

    CompletableFuture<kotlin.Unit> drained() { return (generation == null ? predecessor : generation.drain).copy(); }

    @Override
    public void onEnable() {
        var scope = new ru.arc.core.LifecycleTaskScope(new ru.arc.core.BukkitTaskScheduler(this), true);
        bootstrapTasks = scope;
        var token = scope.token();
        predecessor.whenComplete((ignored, failure) -> scope.callSync(token, () -> {
            if (bootstrapTasks != scope) return kotlin.Unit.INSTANCE;
            if (failure != null) failStartup("Previous crate generation could not drain", failure);
            else beginGeneration(scope);
            return kotlin.Unit.INSTANCE;
        }));
    }

    private void beginGeneration(ru.arc.core.LifecycleTaskScope scope) {
        try {
            var cleanup = java.util.Objects.requireNonNull(getServer().getServicesManager().load(ArcNativeCleanupService.class),
                    "Required ARC native cleanup provider is not registered");
            ArcActivityTelemetryBridge.INSTANCE.install();
            ru.arc.core.PaperArcRuntime.installScheduling(this);
            var owner = new StorageGeneration(cleanup);
            generation = owner;
            Path worldContainer = getServer().getWorldContainer().toPath();
            var token = scope.token();
            owner.worker.submit(() -> prepare(owner, worldContainer)).whenComplete((prepared, failure) ->
                    scope.callSync(token, () -> {
                        if (generation != owner || bootstrapTasks != scope) return kotlin.Unit.INSTANCE;
                        if (failure != null) failStartup("Could not prepare ArcExcellentCrates configuration", failure);
                        else {
                            try { finishEnable(owner, prepared); }
                            catch (RuntimeException | LinkageError startupFailure) {
                                failStartup("Could not install ArcExcellentCrates runtime", startupFailure);
                            }
                        }
                        return kotlin.Unit.INSTANCE;
                    }));
        } catch (RuntimeException | LinkageError failure) { failStartup("Could not start ArcExcellentCrates generation", failure); }
    }

    private void failStartup(String message, Throwable failure) {
        getLogger().log(java.util.logging.Level.SEVERE, message, failure);
        getServer().getPluginManager().disablePlugin(this);
    }

    private Prepared prepare(StorageGeneration owner, Path worldContainer) {
        migrateLegacyDataFolder();
        saveDefaultConfig();
        reloadConfig();
        var logging = new ru.arc.config.Config(getDataFolder().toPath(), "modules/logging.yml");
        var locale = new EciaLocale(getDataFolder().toPath(), legacyMessages());
        Path configured = Path.of(getConfig().getString("excellent-crates-directory", "plugins/ExcellentCrates/crates"));
        Path directory = configured.isAbsolute() ? configured : worldContainer.resolve(configured).normalize();
        var loadedRegistry = new CrateRegistry(directory, getLogger()::warning);
        int count = loadedRegistry.reload();
        var visuals = new CrateVisualSettingsStore(this, owner.worker);
        owner.visuals = visuals;
        return new Prepared(logging, locale, loadedRegistry, visuals, count);
    }

    private void finishEnable(StorageGeneration owner, Prepared prepared) {
        runtime = EciaRuntime.create(this, prepared.logging(), owner.worker, owner.nativeCleanup);
        owner.runtime = runtime;
        var activeRuntime = runtime;
        packetDisplays = new PaperPacketDisplays(this, "crate-visuals", runtime.nativeWork());
        var activeDisplays = packetDisplays;
        runtime.registerService(() -> { activeRuntime.trackNativeCleanup(activeDisplays.closeAsync()); });
        EciaLocale locale = runtime.installPreparedLocale(prepared.locale());
        var shopCommand = java.util.Objects.requireNonNull(getCommand("cratekeys"));
        shopCommand.setTabCompleter((sender, command, alias, arguments) -> java.util.List.of());
        shopCommand.setExecutor((sender, command, label, arguments) -> {
            sender.sendMessage(locale.renderPadded("key-shop.unavailable", sender, Map.of()));
            return true;
        });
        registry = prepared.registry();
        int count = prepared.count();
        registerCrateLocationService();
        String previewCommand = getConfig().getString(
                "preview-command",
                "excellentcrates preview <crate> <player>"
        );
        protectionListener = new CrateProtectionListener(registry, runtime, locale.render("protected"), previewCommand);
        getServer().getPluginManager().registerEvents(protectionListener, this);
        visualSettings = registerService(prepared.visuals());
        runtime.registerStorageFlush(visualSettings::flushLatest);

        long refreshTicks = Math.max(20L, getConfig().getLong("registry-refresh-ticks", 100L));
        var activeRegistry = registry;
        runtime.scheduleRegistryRefresh(refreshTicks, () -> activeRuntime.updateRegistrySize(activeRegistry.reload()));
        runtime.updateRegistrySize(count);
        runtime.info("Protecting {} ExcellentCrates furniture position(s).", count);
        NetworkKeyReceiver networkKeyReceiver = registerService(new NetworkKeyReceiver(this));
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
                    refreshRegistry();
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
        var owner = generation;
        generation = null;
        if (owner != null) predecessor = owner.drain;
        var scope = bootstrapTasks;
        bootstrapTasks = null;
        var failures = new java.util.ArrayList<Throwable>();
        attempt(failures, () -> { if (scope != null) scope.close(); });
        attempt(failures, this::unregisterCrateLocationService);
        if (protectionListener != null) {
            attempt(failures, protectionListener::clearManagedPreviewHandler);
            attempt(failures, protectionListener::clearVisualEditorHandler);
            attempt(failures, protectionListener::clear);
        }
        var active = owner == null ? runtime : owner.runtime;
        attempt(failures, () -> { if (active != null) active.close(); });
        runtime = null;
        if (owner != null) closeStorage(owner, failures);
        registry = null;
        protectionListener = null;
        managedCrates = null;
        keyShop = null;
        crateHolograms = null;
        ambientEffects = null;
        packetDisplays = null;
        visualSettings = null;
        visualEditor = null;
    }

    private void closeStorage(StorageGeneration owner, java.util.List<Throwable> nativeFailures) {
        CompletableFuture<Void> nativeDrain;
        try { nativeDrain = owner.runtime == null ? CompletableFuture.completedFuture(null) : owner.runtime.nativeCleanupDrain(); }
        catch (Throwable failure) { nativeDrain = CompletableFuture.failedFuture(failure); }
        var persistenceDrain = owner.worker.closeAsync(() -> {
            var failures = new java.util.ArrayList<>(nativeFailures);
            attempt(failures, () -> { if (owner.visuals != null) owner.visuals.close(); });
            attempt(failures, () -> { if (owner.runtime != null) owner.runtime.flushStorage(); });
            if (!failures.isEmpty()) throw aggregate(failures);
            return kotlin.Unit.INSTANCE;
        });
        var nativeCompletion = nativeDrain;
        CompletableFuture.allOf(persistenceDrain, nativeCompletion).whenComplete((ignored, ignoredFailure) -> {
            var failures = new java.util.ArrayList<Throwable>();
            attempt(failures, persistenceDrain::join);
            attempt(failures, nativeCompletion::join);
            if (failures.isEmpty()) owner.drain.complete(kotlin.Unit.INSTANCE);
            else {
                var failure = aggregate(failures);
                owner.drain.completeExceptionally(failure);
                getLogger().log(java.util.logging.Level.SEVERE, "Could not drain ArcExcellentCrates generation", failure);
            }
        });
    }

    private static void attempt(java.util.List<Throwable> failures, Runnable close) {
        try { close.run(); } catch (Throwable failure) { failures.add(failure); }
    }

    private static RuntimeException aggregate(java.util.List<Throwable> failures) {
        var result = new IllegalStateException("Could not close every ArcExcellentCrates resource", failures.getFirst());
        failures.stream().skip(1).filter(error -> error != failures.getFirst()).forEach(result::addSuppressed);
        return result;
    }

    public void refreshRegistry() {
        var active = runtime;
        if (active == null) return;
        var activeRegistry = registry;
        active.storage().submit(activeRegistry::reload).whenComplete((size, failure) -> {
            if (failure != null) getLogger().log(java.util.logging.Level.WARNING, "Could not refresh crate registry", failure);
            else active.updateRegistrySize(size);
        });
    }

    private record Prepared(ru.arc.config.Config logging, EciaLocale locale, CrateRegistry registry,
                            CrateVisualSettingsStore visuals, int count) { }

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
