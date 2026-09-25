package ru.ruscrafting.ecia.integration

import org.bukkit.Location
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.server.ServerLoadEvent
import org.bukkit.inventory.ItemStack
import ru.arc.config.Config
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.menu.PaperMenuConfiguration
import ru.ruscrafting.ecia.ArcExcellentCratesPlugin
import ru.ruscrafting.ecia.CrateAmbientEffectService
import ru.ruscrafting.ecia.CrateVisualSettingsStore
import ru.ruscrafting.ecia.CrateVisualTarget
import ru.ruscrafting.ecia.inventory.NativeItemPayload
import ru.ruscrafting.ecia.inventory.OpeningInventoryTransactions
import ru.ruscrafting.ecia.journal.DurableOpeningStore
import ru.ruscrafting.ecia.journal.OpeningLedger
import ru.ruscrafting.ecia.journal.OpeningRecord
import ru.ruscrafting.ecia.journal.PeriodicKeyLedger
import ru.ruscrafting.ecia.roll.PoolSnapshot
import ru.ruscrafting.ecia.roll.WeightedOfferGenerator
import ru.ruscrafting.ecia.screens.EciaMenuActions
import ru.ruscrafting.ecia.screens.EciaMenuConfiguration
import ru.ruscrafting.ecia.screens.EciaMenuScreens
import su.nightexpress.excellentcrates.CratesAPI
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.random.RandomGenerator

/** Owns managed openings and their player-facing entry points. */
class ManagedCratesService(
    private val plugin: ArcExcellentCratesPlugin,
    visualSettings: CrateVisualSettingsStore,
    openingEffects: CrateOpeningEffects,
    private val ambientEffects: CrateAmbientEffectService,
    packetDisplays: PaperPacketDisplays,
    furniture: ItemsAdderFurnitureAccess,
) : AutoCloseable, Listener {
    private val runtime = plugin.runtime()
    private val root = plugin.dataFolder.toPath()
    private val payload = NativeItemPayload()
    private val inventory = OpeningInventoryTransactions(payload)
    private val keys = NativeSeasonKeys()
    private val keyGlow = KeyCrateGlowService(plugin, keys, furniture)
    private val rewards = CatalogRewardBridge(payload)
    private val lifecycleTasks = runtime.tasks()
    private val lifecycleToken = lifecycleTasks.token()
    private val storageExecutor = Executor { command ->
        if (lifecycleTasks.runAsync(lifecycleToken) { command.run() } == null) {
            throw RejectedExecutionException("Crate storage lifecycle is closed")
        }
    }
    private val paperExecutor = Executor { command ->
        if (lifecycleTasks.runSync(lifecycleToken) { command.run() } == null) {
            throw RejectedExecutionException("Crate Paper lifecycle is closed")
        }
    }
    private var settings = ManagedCratesSettings(false, emptyMap())
    private var configurationFailed = true
    private var pools = emptyMap<String, PoolSnapshot>()
    private var ledger: OpeningLedger? = null
    private var engine: ManagedOpeningEngine? = null
    private var screens: EciaMenuScreens? = null
    private var router: NativeCrateInteractionRouter? = null
    private val roulette = WorldRouletteAnimator(plugin, runtime, payload, visualSettings, openingEffects, packetDisplays)
    private val inFlight = mutableSetOf<UUID>()
    private val playerSessions = mutableMapOf<UUID, Long>()
    private val activePlayers = mutableMapOf<UUID, Player>()
    private var periodicKeys: PeriodicKeysService? = null
    @Volatile private var storageHealthy = false
    @Volatile private var pendingOpeningCount = 0
    private var pendingConfiguration: Configuration? = null
    private var ledgerLoadInProgress = false
    private var reloadGeneration = 0L
    private var ready = false
    private var closed = false

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
        ensureRouter()
        reload()
        plugin.setManagedPreviewHandler { player, id ->
            if (!configurationFailed && id !in settings.cases) false else {
                guarded(player) { preview(player, id) }
                true
            }
        }
        plugin.setManagedOpenHandler { player, id ->
            if (!configurationFailed && id !in settings.cases) false else {
                guarded(player) { open(player, id) }
                true
            }
        }
    }

    /** Configuration/native providers load on Paper; the durable opening store loads off-thread once. */
    fun reload() {
        roulette.close()
        ambientEffects.setRewards(emptyMap())
        keyGlow.configure(emptyMap(), settings.freeOpeningZone())
        ready = false
        configurationFailed = true
        pendingConfiguration = null
        val generation = ++reloadGeneration
        val candidate = runCatching {
            val nextSettings = readSettings()
            val menuConfiguration = EciaMenuConfiguration.load(root)
            val nextPools = if (nextSettings.enabled) NativeRewardPools(keys, rewards, payload) { issue ->
                runtime.error("Managed reward excluded: {}", issue)
            }.install(nextSettings) else emptyMap()
            Configuration(nextSettings, menuConfiguration, nextPools)
        }.onFailure { failure ->
            runtime.error("Managed crate configuration rejected: {}", failure.toString())
        }.getOrNull() ?: return
        settings = candidate.settings
        pendingConfiguration = candidate
        if (ledger != null) {
            install(candidate, generation, storageHealthy)
            return
        }
        if (ledgerLoadInProgress) return
        ledgerLoadInProgress = true
        asyncStorage {
            val opened = OpeningLedger(DurableOpeningStore(root), Clock.systemUTC())
            val grants = PeriodicKeyLedger(root)
            LoadedLedger(opened, grants, grants.deliveredIds(),
                opened.snapshot().count { it.pending() }, opened.available())
        }.whenCompleteSync(lifecycleTasks, lifecycleToken) { loaded, failure ->
            ledgerLoadInProgress = false
            if (failure != null) {
                runtime.error("Managed opening storage could not load: {}", failure.toString())
                return@whenCompleteSync
            }
            val value = loaded ?: return@whenCompleteSync
            ledger = value.ledger
            val periodicInventory = OpeningInventoryTransactions(payload)
            val periodicIssuer = PeriodicKeyIssuer(value.periodicKeys, value.ledger, payload, periodicInventory,
                Clock.systemUTC(), storageExecutor, paperExecutor, this::isCurrentSession)
            periodicKeys = PeriodicKeysService(plugin, keys, periodicIssuer, value.periodicKeys, storageExecutor,
                object : PeriodicKeysService.PlayerAccess {
                    override fun acquire(player: Player): Long? {
                        if (!ready || !storageHealthy || roulette.isRolling(player.uniqueId)
                            || activePlayers[player.uniqueId] !== player || !inFlight.add(player.uniqueId)) return null
                        return session(player.uniqueId)
                    }
                    override fun current(player: Player, session: Long) = isCurrentSession(player, session)
                    override fun release(player: Player, session: Long) {
                        if (isCurrentSession(player, session)) inFlight.remove(player.uniqueId)
                    }
                }, value.deliveredIds)
            storageHealthy = value.available
            runtime.updateRecoveryBacklog(value.pendingCount)
            pendingOpeningCount = value.pendingCount
            val latest = pendingConfiguration
            if (!closed && latest != null) {
                install(latest, reloadGeneration, value.available)
                recoverOnlinePlayersAfterInitialLedgerLoad()
            }
        }
    }

    private fun install(candidate: Configuration, generation: Long, available: Boolean) {
        if (closed || generation != reloadGeneration) return
        val activeLedger = checkNotNull(ledger)
        runtime.installMenu(candidate.menuConfiguration)
        screens = EciaMenuScreens(candidate.menuConfiguration, payload)
        engine = ManagedOpeningEngine(activeLedger, WeightedOfferGenerator(RandomGenerator.getDefault()),
            inventory, payload, rewards::materialize, storageExecutor, paperExecutor) { player, session ->
            isCurrentSession(player, session)
        }
        pools = candidate.pools
        settings = candidate.settings
        storageHealthy = available
        ambientEffects.setRewards(candidate.pools.mapValues { (_, pool) ->
            pool.rewards().mapNotNull { reward ->
                runCatching {
                    payload.items(reward.previewPayload()).firstOrNull()
                        ?.takeUnless(ItemStack::isEmpty)?.clone()
                }.getOrNull()
            }
        })
        keyGlow.configure(if (candidate.settings.enabled) candidate.settings.cases else emptyMap(),
            candidate.settings.freeOpeningZone())
        periodicKeys?.configure(candidate.settings, candidate.pools.keys)
        ready = candidate.settings.enabled && available
        configurationFailed = false
        updateHealth()
        runtime.info("Managed crate services loaded: cases={}, pending={}", pools.size, pendingOpeningCount)
    }

    private fun ensureRouter() {
        if (router != null) return
        router = NativeCrateInteractionRouter(plugin, { configurationFailed || it in settings.cases },
            { player, target -> guarded(player) { open(player, target.crate().id, target.anchor()) } },
            { player, crate -> guarded(player) { preview(player, crate.id) } },
            { player -> message(player, "managed.native-command") },
            { player, target -> plugin.openCrateVisualEditor(player, CrateVisualTarget(target.crate().id, target.anchor())) },
            plugin::isCrateEditMode, this::reload)
    }

    fun open(player: Player, crateId: String) = open(player, crateId, null)

    private fun open(player: Player, crateId: String, anchor: Location?) {
        if (!ready || !storageHealthy || !player.hasPermission("ecia.use")) return message(player, "managed.unavailable")
        if (roulette.isRolling(player.uniqueId)) return message(player, "managed.busy")
        val playerId = player.uniqueId
        val connected = activePlayers[playerId]
        if (connected != null && connected !== player) return message(player, "managed.busy")
        activePlayers[playerId] = player
        if (!inFlight.add(player.uniqueId)) return message(player, "managed.busy")
        val session = session(playerId)
        try {
        val crate = CratesAPI.getCrateManager().getCrateById(crateId)
        if (crate == null || !crate.hasPermission(player)) {
            inFlight.remove(player.uniqueId)
            return message(player, if (crate == null) "managed.unavailable" else "no-permission")
        }
        if (!CratesAPI.plugin().dataManager.isDataLoaded || !CratesAPI.plugin().openingManager.isOpeningAvailable(player)) {
            inFlight.remove(player.uniqueId)
            return message(player, "managed.busy")
        }
        val pool = pools[crateId]
        if (pool == null) {
            inFlight.remove(player.uniqueId)
            return message(player, "managed.unavailable")
        }
        if (router?.allowManagedOpen(player, crate) != true) {
            inFlight.remove(player.uniqueId)
            return message(player, "managed.vetoed")
        }
        val cost = keys.cost(crate)
        val current = checkNotNull(engine)
        val caseRules = settings.cases[crateId]
        val period = caseRules?.freeOpenPeriod() ?: PeriodicVirtualOpening.Period.NONE
        val zone = settings.freeOpeningZone()
        val window = if (period == PeriodicVirtualOpening.Period.NONE) null
            else PeriodicVirtualOpening.window(period, Instant.now(), zone)
        val physicalAttempt: () -> CompletableFuture<OpeningRecord?> = {
            current.openPhysical(player, session, pool, keys.matches(cost.keyId()), cost.amount())
                .thenApply { opening -> opening.orElse(null) }
        }
        val matcher = periodicKeys?.matches(player, crateId, period)
        val attempt = if (window != null && matcher != null && cost.amount() == 1) {
            val id = PeriodicVirtualOpening.openingId(playerId, crateId, period, window)
            val exactKey = matcher.and { item -> PeriodicPhysicalKey.identify(item).map { it.id() == id }.orElse(false) }
            current.openPeriodicKey(player, session, pool, exactKey, id).thenCompose { opening ->
                if (opening.isPresent) CompletableFuture.completedFuture(opening.get()) else physicalAttempt()
            }
        } else physicalAttempt()
        observe(player, session, attempt) { record ->
            if (record != null) {
                if (record.stage() == OpeningRecord.Stage.MAIL) finishSelection(player, record)
                else show(player, record, anchor)
            } else {
                messageNoKeyUntilReset(player, period)
            }
        }
        } catch (failure: Throwable) {
            inFlight.remove(player.uniqueId)
            throw failure
        }
    }

    fun preview(player: Player, crateId: String) {
        if (!ready) return message(player, "managed.unavailable")
        val crate = CratesAPI.getCrateManager().getCrateById(crateId) ?: return message(player, "managed.unavailable")
        if (!crate.hasPermission(player)) return message(player, "no-permission")
        val pool = pools[crateId] ?: return message(player, "managed.unavailable")
        checkNotNull(screens).openPoolPreview(checkNotNull(runtime.menuOrNull()).runtime(), player, pool, EciaMenuActions())
    }

    private fun show(player: Player, record: OpeningRecord, anchor: Location? = null) {
        when (record.stage()) {
            OpeningRecord.Stage.CHOOSING -> selectAndAnimate(player, record, anchor)
            OpeningRecord.Stage.MAIL -> { player.closeInventory(); message(player, "managed.inventory-full") }
            OpeningRecord.Stage.DELIVERED -> player.closeInventory()
            OpeningRecord.Stage.ABORTED -> { player.closeInventory(); message(player, "managed.key-not-consumed") }
            else -> { player.closeInventory(); message(player, "managed.review") }
        }
    }

    private fun selectAndAnimate(player: Player, opening: OpeningRecord, anchor: Location?) {
        if (!inFlight.add(player.uniqueId)) return message(player, "managed.busy")
        val session = session(player.uniqueId)
        val reward = opening.offers().firstOrNull()
        if (reward == null) {
            inFlight.remove(player.uniqueId)
            return message(player, "managed.operation-refused")
        }
        observe(player, session, checkNotNull(engine).select(player, opening.id(), opening.revision(), reward.id())) { selected ->
            player.closeInventory()
            val animated = anchor != null && roulette.start(player, anchor, selected) {
                guarded(player) { finishSelection(player, selected) }
            }
            if (!animated) finishSelection(player, selected)
        }
    }

    private fun finishSelection(player: Player, selected: OpeningRecord) {
        if (!player.isOnline) return
        val session = session(player.uniqueId)
        if (!inFlight.add(player.uniqueId)) return message(player, "managed.busy")
        observe(player, session, checkNotNull(engine).claim(player, session, selected.id(), selected.revision())) { claimed ->
            show(player, claimed)
        }
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        val id = player.uniqueId
        playerSessions[id] = (playerSessions[id] ?: 0L) + 1L
        activePlayers[id] = player
        recoverJoinedPlayer(player)
    }

    /** A join can precede asynchronous ledger loading; recover it once installation completes. */
    private fun recoverOnlinePlayersAfterInitialLedgerLoad() {
        for (player in plugin.server.onlinePlayers) {
            val id = player.uniqueId
            val current = activePlayers[id]
            if (current != null && current !== player) continue
            activePlayers[id] = player
            playerSessions.putIfAbsent(id, 1L)
            recoverJoinedPlayer(player)
        }
    }

    private fun recoverJoinedPlayer(player: Player) {
        val current = engine ?: return
        val id = player.uniqueId
        if (!player.isOnline || activePlayers[id] !== player) return
        if (!inFlight.add(id)) return
        val session = session(id)
        current.playerDataLoaded(player, session).thenCompose { current.pending(id) }.also { future ->
            observe(player, session, future) { pending ->
                if (pending.isPresent) message(player, "managed.pending")
                periodicKeys?.playerDataLoaded(player)
            }
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val id = event.player.uniqueId
        roulette.cancel(id)
        periodicKeys?.playerLeft(event.player)
        inventory.playerLeft(id)
        inFlight.remove(id)
        playerSessions[id] = (playerSessions[id] ?: 0L) + 1L
        if (activePlayers[id] === event.player) activePlayers.remove(id)
    }

    @EventHandler
    fun onServerLoad(event: ServerLoadEvent) {
        reloadManagedCratesAfterServerLoad(settings.enabled, configurationFailed, lifecycleTasks) {
            if (!closed) reload()
        }
    }

    private fun readSettings(): ManagedCratesSettings {
        Config(root, "features.yml").mergeMissingFromBundled("features.yml")
        return ManagedCratesSettings.read(
            YamlConfiguration.loadConfiguration(root.resolve("features.yml").toFile()),
        ) { warning -> runtime.warn("Managed crate setting: {}", warning) }
    }

    private fun messageNoKeyUntilReset(player: Player, period: PeriodicVirtualOpening.Period) {
        if (period == PeriodicVirtualOpening.Period.NONE) return message(player, "managed.no-key")
        val whenKey = if (period == PeriodicVirtualOpening.Period.DAILY) "key.next-daily" else "key.next-weekly"
        val reset = PlainTextComponentSerializer.plainText().serialize(runtime.locale().render(whenKey, player, emptyMap()))
        message(player, "managed.no-key-until-reset", mapOf("next_reset" to reset))
    }

    private fun <T> observe(player: Player, session: Long, future: CompletableFuture<T>, success: (T) -> Unit) {
        future.whenCompleteSync(lifecycleTasks, lifecycleToken) { value, failure ->
            val id = player.uniqueId
            if (!isCurrentSession(player, session)) return@whenCompleteSync
            inFlight.remove(id)
            if (failure != null) {
                runtime.warn("Managed crate operation refused for {}: {}", player.name, failure.toString())
                refreshStorageHealth()
                message(player, "managed.operation-refused")
                updateHealth()
                return@whenCompleteSync
            }
            if (closed) return@whenCompleteSync
            try {
                @Suppress("UNCHECKED_CAST")
                success(value as T)
            } catch (thrown: Throwable) {
                runtime.warn("Managed crate completion refused for {}: {}", player.name, thrown.toString())
                message(player, "managed.operation-refused")
                refreshStorageHealth()
            }
            updateHealth()
        }
    }

    private fun refreshStorageHealth() {
        val current = engine ?: return
        current.available().whenCompleteSync(lifecycleTasks, lifecycleToken) { available, failure ->
            if (failure != null || available == null) return@whenCompleteSync
            storageHealthy = available
            if (!available) ready = false
        }
    }

    private fun updateHealth() {
        engine?.pendingCount()?.whenCompleteSync(lifecycleTasks, lifecycleToken) { count, failure ->
            if (failure == null && count != null) {
                pendingOpeningCount = count
                runtime.updateRecoveryBacklog(count)
            }
        }
    }

    private fun session(playerId: UUID): Long = playerSessions.getOrPut(playerId) { 1L }

    private fun isCurrentSession(player: Player, expected: Long): Boolean =
        player.isOnline && playerSessions[player.uniqueId] == expected && activePlayers[player.uniqueId] === player

    private fun <T> asyncStorage(action: () -> T): CompletableFuture<T> {
        val result = CompletableFuture<T>()
        try {
            storageExecutor.execute {
                try {
                    result.complete(action())
                } catch (failure: Throwable) {
                    result.completeExceptionally(failure)
                }
            }
        } catch (failure: Throwable) {
            result.completeExceptionally(failure)
        }
        return result
    }

    private fun guarded(sender: CommandSender, action: () -> Unit) {
        runCatching(action).onFailure { failure ->
            runtime.warn("Crate operation refused for {}: {}", sender.name, failure.toString())
            message(sender, "managed.operation-refused")
        }
    }

    private fun message(sender: CommandSender, key: String, values: Map<String, String> = emptyMap()) {
        sender.sendMessage(runtime.locale().renderPadded(key, sender, values))
    }

    override fun close() {
        if (closed) return
        closed = true
        ready = false
        roulette.close()
        ambientEffects.setRewards(emptyMap())
        plugin.clearManagedPreviewHandler()
        plugin.clearManagedOpenHandler()
        router?.close()
        periodicKeys?.close()
        keyGlow.close()
        keys.close()
        HandlerList.unregisterAll(this)
    }

    private data class Configuration(
        val settings: ManagedCratesSettings,
        val menuConfiguration: PaperMenuConfiguration,
        val pools: Map<String, PoolSnapshot>,
    )

    private data class LoadedLedger(
        val ledger: OpeningLedger,
        val periodicKeys: PeriodicKeyLedger,
        val deliveredIds: Set<UUID>,
        val pendingCount: Int,
        val available: Boolean,
    )

}

internal fun reloadManagedCratesAfterServerLoad(
    settingsEnabled: Boolean,
    configurationFailed: Boolean,
    tasks: LifecycleTaskScope,
    reload: () -> Unit,
) {
    // PlayerParticles 8.13 parses preset groups three ticks after enable, after ServerLoadEvent.
    if (settingsEnabled || configurationFailed) tasks.runLater(4L, reload)
}
