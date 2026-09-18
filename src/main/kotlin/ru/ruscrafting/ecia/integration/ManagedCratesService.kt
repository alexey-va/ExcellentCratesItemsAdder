package ru.ruscrafting.ecia.integration

import org.bukkit.Location
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
import ru.ruscrafting.ecia.ArcExcellentCratesPlugin
import ru.ruscrafting.ecia.CrateAmbientEffectService
import ru.ruscrafting.ecia.CrateVisualSettingsStore
import ru.ruscrafting.ecia.CrateVisualTarget
import ru.ruscrafting.ecia.inventory.NativeItemPayload
import ru.ruscrafting.ecia.inventory.OpeningInventoryTransactions
import ru.ruscrafting.ecia.journal.DurableOpeningStore
import ru.ruscrafting.ecia.journal.OpeningLedger
import ru.ruscrafting.ecia.journal.OpeningRecord
import ru.ruscrafting.ecia.roll.PoolSnapshot
import ru.ruscrafting.ecia.roll.WeightedOfferGenerator
import ru.ruscrafting.ecia.screens.EciaMenuActions
import ru.ruscrafting.ecia.screens.EciaMenuConfiguration
import ru.ruscrafting.ecia.screens.EciaMenuScreens
import su.nightexpress.excellentcrates.CratesAPI
import java.time.Clock
import java.util.random.RandomGenerator

/** Owns managed openings and their player-facing entry points. */
class ManagedCratesService(
    private val plugin: ArcExcellentCratesPlugin,
    visualSettings: CrateVisualSettingsStore,
    openingEffects: CrateOpeningEffects,
    private val ambientEffects: CrateAmbientEffectService,
    furniture: ItemsAdderFurnitureAccess,
) : AutoCloseable, Listener {
    private val runtime = plugin.runtime()
    private val root = plugin.dataFolder.toPath()
    private val payload = NativeItemPayload()
    private val inventory = OpeningInventoryTransactions(payload)
    private val keys = NativeSeasonKeys()
    private val keyGlow = KeyCrateGlowService(plugin, keys, furniture)
    private val rewards = CatalogRewardBridge(payload)
    private var settings = ManagedCratesSettings(false, emptyMap())
    private var configurationFailed = true
    private var pools = emptyMap<String, PoolSnapshot>()
    private var ledger: OpeningLedger? = null
    private var engine: ManagedOpeningEngine? = null
    private var screens: EciaMenuScreens? = null
    private var router: NativeCrateInteractionRouter? = null
    private val roulette = WorldRouletteAnimator(plugin, runtime, payload, visualSettings, openingEffects)
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

    fun reload() {
        roulette.close()
        ambientEffects.setRewards(emptyMap())
        keyGlow.configure(emptyMap())
        ready = false
        configurationFailed = true
        runCatching {
            val candidate = readSettings()
            settings = candidate
            ensureRouter()
            Config(root, EciaMenuConfiguration.RESOURCE).mergeMissingFromBundled(EciaMenuConfiguration.RESOURCE)
            val menuConfiguration = EciaMenuConfiguration.load(root)
            val nextLedger = OpeningLedger(DurableOpeningStore(root), Clock.systemUTC())
            val nextPools = if (candidate.enabled) NativeRewardPools(keys, rewards, payload) { issue ->
                runtime.error("Managed reward excluded: {}", issue)
            }.install(candidate) else emptyMap()
            runtime.installMenu(menuConfiguration)
            screens = EciaMenuScreens(menuConfiguration, payload)
            ledger = nextLedger
            engine = ManagedOpeningEngine(nextLedger, WeightedOfferGenerator(RandomGenerator.getDefault()), inventory, payload, rewards::materialize)
            pools = nextPools
            ambientEffects.setRewards(nextPools.mapValues { (_, pool) ->
                pool.rewards().mapNotNull { reward ->
                    runCatching {
                        payload.items(reward.previewPayload()).firstOrNull()
                            ?.takeUnless(ItemStack::isEmpty)?.clone()
                    }.getOrNull()
                }
            })
            keyGlow.configure(if (candidate.enabled) candidate.cases else emptyMap())
            ready = candidate.enabled
            configurationFailed = false
            updateHealth()
            runtime.info("Managed crate services loaded: cases={}, pending={}", pools.size, nextLedger.snapshot().count { it.pending() })
        }.onFailure { failure ->
            runtime.error("Managed crate initialization rejected: {}", failure.toString())
        }
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
        if (!ready || !player.hasPermission("ecia.use")) return message(player, "managed.unavailable")
        if (roulette.isRolling(player.uniqueId)) return message(player, "managed.busy")
        val crate = CratesAPI.getCrateManager().getCrateById(crateId) ?: return message(player, "managed.unavailable")
        if (!crate.hasPermission(player)) return message(player, "no-permission")
        if (!CratesAPI.plugin().dataManager.isDataLoaded || !CratesAPI.plugin().openingManager.isOpeningAvailable(player)) {
            return message(player, "managed.busy")
        }
        requireLedger().active(player.uniqueId).orElse(null)?.let {
            show(player, it, anchor)
            return
        }
        val cost = keys.cost(crate)
        val pool = pools[crateId] ?: return message(player, "managed.unavailable")
        if (router?.allowManagedOpen(player, crate) != true) return message(player, "managed.vetoed")
        val record = requireEngine().open(player, pool, keys.matches(cost.keyId()), cost.amount()).orElse(null)
            ?: return message(player, "managed.no-key")
        show(player, record, anchor)
        updateHealth()
    }

    fun preview(player: Player, crateId: String) {
        if (!ready) return message(player, "managed.unavailable")
        val crate = CratesAPI.getCrateManager().getCrateById(crateId) ?: return message(player, "managed.unavailable")
        if (!crate.hasPermission(player)) return message(player, "no-permission")
        val pool = pools[crateId] ?: return message(player, "managed.unavailable")
        requireScreens().openPoolPreview(requireMenus(), player, pool, actions())
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
        val reward = opening.offers().firstOrNull()
            ?: throw IllegalStateException("Opening has no rolled reward")
        val selected = requireEngine().select(player, opening.id(), opening.revision(), reward.id())
        player.closeInventory()
        val animated = anchor != null && roulette.start(player, anchor, selected) {
            guarded(player) { finishSelection(player, selected) }
        }
        if (!animated) finishSelection(player, selected)
    }

    private fun actions() = EciaMenuActions()

    private fun finishSelection(player: Player, selected: OpeningRecord) {
        val claimed = requireEngine().claim(player, selected.id(), selected.revision())
        show(player, claimed)
        updateHealth()
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        val current = engine ?: return
        guarded(event.player) {
            current.playerDataLoaded(event.player)
            if (requireLedger().pending(event.player.uniqueId).isPresent) message(event.player, "managed.pending")
            updateHealth()
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        roulette.cancel(event.player.uniqueId)
        inventory.playerLeft(event.player.uniqueId)
    }

    @EventHandler
    fun onServerLoad(event: ServerLoadEvent) {
        // ARC can finish provider initialization after its onEnable callback.
        if (!ready && (settings.enabled || configurationFailed)) reload()
    }

    private fun readSettings(): ManagedCratesSettings {
        Config(root, "features.yml").mergeMissingFromBundled("features.yml")
        return ManagedCratesSettings.read(YamlConfiguration.loadConfiguration(root.resolve("features.yml").toFile()))
    }

    private fun requireLedger() = checkNotNull(ledger) { "Opening storage is not available" }.also { check(it.available()) { "Opening storage needs reconciliation" } }
    private fun requireEngine() = checkNotNull(engine) { "Opening service is not available" }
    private fun requireScreens() = checkNotNull(screens) { "Opening menus are not available" }
    private fun requireMenus() = checkNotNull(runtime.menuOrNull()) { "Opening menus are not available" }.runtime()

    private fun guarded(sender: CommandSender, action: () -> Unit) {
        runCatching(action).onFailure { failure ->
            runtime.warn("Crate operation refused for {}: {}", sender.name, failure.toString())
            message(sender, "managed.operation-refused")
        }
    }

    private fun message(sender: CommandSender, key: String, values: Map<String, String> = emptyMap()) {
        sender.sendMessage(runtime.locale().renderPadded(key, sender, values))
    }

    private fun updateHealth() { runtime.updateRecoveryBacklog(ledger?.snapshot()?.count { it.pending() } ?: 0) }

    override fun close() {
        if (closed) return
        closed = true
        ready = false
        roulette.close()
        ambientEffects.setRewards(emptyMap())
        plugin.clearManagedPreviewHandler()
        plugin.clearManagedOpenHandler()
        router?.close()
        keyGlow.close()
        keys.close()
        HandlerList.unregisterAll(this)
    }

}
