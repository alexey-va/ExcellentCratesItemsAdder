package ru.ruscrafting.ecia.integration

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.server.ServerLoadEvent
import ru.arc.config.Config
import ru.arc.core.ScheduledTask
import ru.ruscrafting.ecia.analytics.OpeningAnalytics
import ru.ruscrafting.ecia.admin.CrateAdminService
import ru.ruscrafting.ecia.admin.CrateInspection
import ru.ruscrafting.ecia.admin.ItemsAdderGroundingProvider
import ru.ruscrafting.ecia.admin.NativeAdminCrateGateway
import ru.ruscrafting.ecia.admin.PendingMailCounters
import ru.ruscrafting.ecia.ExcellentCratesItemsAdderPlugin
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
import ru.ruscrafting.ecia.screens.OpeningRevealPlan
import ru.ruscrafting.ecia.season.SeasonPoolStore
import su.nightexpress.excellentcrates.CratesAPI
import java.time.Clock
import java.util.random.RandomGenerator

/** Owns managed openings and their player-facing entry points. */
class ManagedCratesService(private val plugin: ExcellentCratesItemsAdderPlugin) : AutoCloseable, Listener {
    private val runtime = plugin.runtime()
    private val root = plugin.dataFolder.toPath()
    private val payload = NativeItemPayload()
    private val inventory = OpeningInventoryTransactions(payload)
    private val keys = NativeSeasonKeys()
    private val rewards = CatalogRewardBridge(payload)
    private var settings = ManagedCratesSettings(false, emptyMap())
    private var configurationFailed = true
    private var pools = emptyMap<String, PoolSnapshot>()
    private var seasons: SeasonPoolStore? = null
    private var ledger: OpeningLedger? = null
    private var engine: ManagedOpeningEngine? = null
    private var screens: EciaMenuScreens? = null
    private var router: NativeCrateInteractionRouter? = null
    private val roulette = WorldRouletteAnimator(plugin, runtime, payload)
    private val reveals = mutableMapOf<java.util.UUID, RevealAnimation>()
    private val openingAnchors = mutableMapOf<java.util.UUID, Location>()
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
        cancelReveals()
        roulette.close()
        openingAnchors.clear()
        ready = false
        configurationFailed = true
        runCatching {
            val candidate = readSettings()
            settings = candidate
            ensureRouter()
            Config(root, EciaMenuConfiguration.RESOURCE).mergeMissingFromBundled(EciaMenuConfiguration.RESOURCE)
            val menuConfiguration = EciaMenuConfiguration.load(root)
            val nextSeasons = SeasonPoolStore(root)
            val nextLedger = OpeningLedger(DurableOpeningStore(root), Clock.systemUTC())
            val nextPools = if (candidate.enabled) NativeRewardPools(nextSeasons, keys, rewards, payload).install(candidate) else emptyMap()
            runtime.installMenu(menuConfiguration)
            screens = EciaMenuScreens(menuConfiguration, payload)
            seasons = nextSeasons
            ledger = nextLedger
            engine = ManagedOpeningEngine(nextLedger, WeightedOfferGenerator(RandomGenerator.getDefault()), inventory, payload, rewards::materialize)
            pools = nextPools
            if (candidate.cases.isNotEmpty()) keys.stampTemplates(candidate.cases.mapValues { it.value.seasonId() })
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
            plugin::isCrateEditMode, this::reload)
    }

    fun command(sender: CommandSender, args: Array<String>): Boolean {
        val operation = args.firstOrNull()?.lowercase() ?: "resume"
        if (operation == "inspect" || operation == "repair") {
            if (!sender.hasPermission("ecia.admin")) message(sender, "no-permission")
            else guarded(sender) { administration(sender, args, operation == "repair") }
            return true
        }
        if (operation == "stats") {
            if (!sender.hasPermission("ecia.admin")) message(sender, "no-permission")
            else guarded(sender) { statistics(sender, args) }
            return true
        }
        if (operation !in setOf("history", "resume", "open", "preview", "reconcile")) return false
        if (operation == "reconcile") {
            if (!sender.hasPermission("ecia.admin")) message(sender, "no-permission")
            else {
                val player = args.getOrNull(1)?.let(Bukkit::getPlayerExact)
                if (player == null) message(sender, "managed.player-unavailable")
                else guarded(sender) {
                    requireEngine().reconcile(player)
                    updateHealth()
                    message(sender, "managed.reconciled")
                }
            }
            return true
        }
        if (sender !is Player) {
            message(sender, "player-only")
            return true
        }
        if (!sender.hasPermission("ecia.use")) {
            message(sender, "no-permission")
            return true
        }
        guarded(sender) {
            when (operation) {
                "history" -> history(sender)
                "resume" -> resume(sender)
                "open", "preview" -> {
                    val id = args.getOrNull(1)
                    if (id == null || id !in settings.cases) message(sender, "managed.usage")
                    else if (operation == "open") open(sender, id) else preview(sender, id)
                }
            }
        }
        return true
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
            if (anchor != null && it.stage() == OpeningRecord.Stage.CHOOSING) openingAnchors[it.id()] = anchor.clone()
            show(player, it)
            return
        }
        val cost = keys.cost(crate)
        val season = keys.selectedSeason(player, cost).orElse(null) ?: return message(player, "managed.no-key")
        val pool = seasons?.find(crateId, season)?.orElse(null) ?: return message(player, "managed.unknown-season")
        if (router?.allowManagedOpen(player, crate) != true) return message(player, "managed.vetoed")
        val record = requireEngine().open(player, pool, keys.matches(cost.keyId(), season), cost.amount()).orElse(null)
            ?: return message(player, "managed.no-key")
        if (anchor != null) openingAnchors[record.id()] = anchor.clone()
        reveal(player, record)
        updateHealth()
    }

    fun preview(player: Player, crateId: String) {
        if (!ready) return message(player, "managed.unavailable")
        val crate = CratesAPI.getCrateManager().getCrateById(crateId) ?: return message(player, "managed.unavailable")
        if (!crate.hasPermission(player)) return message(player, "no-permission")
        val season = keys.selectedSeason(player, keys.cost(crate)).orElse(settings.cases[crateId]?.seasonId())
        val pool = season?.let { seasons?.find(crateId, it)?.orElse(null) } ?: return message(player, "managed.unavailable")
        requireScreens().openPoolPreview(requireMenus(), player, pool, actions(player))
    }

    private fun resume(player: Player) {
        if (roulette.isRolling(player.uniqueId)) return message(player, "managed.busy")
        val record = requireLedger().pending(player.uniqueId).orElse(null)
            ?: return message(player, "managed.nothing-pending")
        val resumed = if (record.stage() == OpeningRecord.Stage.MAIL) {
            requireEngine().claim(player, record.id(), record.revision())
        } else {
            record
        }
        show(player, resumed)
        updateHealth()
    }

    private fun history(player: Player) {
        requireScreens().openHistory(requireMenus(), player, records(player), actions(player))
    }

    private fun records(player: Player): List<OpeningRecord> = requireLedger().snapshot()
        .filter { it.playerId() == player.uniqueId }.sortedByDescending { it.createdAt() }

    private fun show(player: Player, record: OpeningRecord) {
        cancelReveal(player.uniqueId)
        when (record.stage()) {
            OpeningRecord.Stage.CHOOSING -> requireScreens().openChoices(requireMenus(), player, record, actions(player))
            OpeningRecord.Stage.MAIL -> { player.closeInventory(); message(player, "managed.inventory-full") }
            OpeningRecord.Stage.DELIVERED -> { player.closeInventory(); message(player, "managed.delivered") }
            OpeningRecord.Stage.ABORTED -> { player.closeInventory(); message(player, "managed.key-not-consumed") }
            else -> { player.closeInventory(); message(player, "managed.review") }
        }
    }

    private fun reveal(player: Player, record: OpeningRecord) {
        cancelReveal(player.uniqueId)
        var frame = 0
        val screen = requireScreens()
        val menus = requireMenus()
        val session = screen.openReveal(menus, player, record) { frame }
        player.playSound(player.location, Sound.UI_BUTTON_CLICK, SoundCategory.MASTER, 0.25f, 0.8f)
        val task = runtime.tasks().runTimer(REVEAL_PERIOD_TICKS, REVEAL_PERIOD_TICKS) {
            val active = reveals[player.uniqueId]
            if (active == null || active.openingId != record.id()) return@runTimer
            if (!player.isOnline || !session.isOpen) {
                cancelReveal(player.uniqueId)
                return@runTimer
            }
            frame++
            if (frame >= OpeningRevealPlan.FRAME_COUNT) {
                cancelReveal(player.uniqueId)
                player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, SoundCategory.MASTER, 0.35f, 1.35f)
                show(player, record)
                return@runTimer
            }
            session.refresh()
            if (frame >= OpeningRevealPlan.REVEAL_START) {
                player.playSound(player.location, Sound.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.MASTER, 0.3f,
                    0.9f + (frame - OpeningRevealPlan.REVEAL_START) * 0.15f)
            } else {
                player.playSound(player.location, Sound.UI_BUTTON_CLICK, SoundCategory.MASTER, 0.18f, 0.9f + frame * 0.08f)
            }
        }
        if (task == null) {
            show(player, record)
            return
        }
        reveals[player.uniqueId] = RevealAnimation(record.id(), task)
    }

    private fun cancelReveal(playerId: java.util.UUID) {
        reveals.remove(playerId)?.task?.cancel()
    }

    private fun cancelReveals() {
        reveals.values.forEach { it.task.cancel() }
        reveals.clear()
    }

    private fun actions(player: Player) = EciaMenuActions(
        choiceSelect = EciaMenuActions.ChoiceSelect { id, revision, reward -> guarded(player) {
            val selected = requireEngine().select(player, id, revision, reward)
            player.closeInventory()
            val anchor = openingAnchors.remove(id)
            val animated = anchor != null && roulette.start(player, anchor, selected) {
                guarded(player) { finishSelection(player, selected) }
            }
            if (!animated) finishSelection(player, selected)
        } },
        reroll = EciaMenuActions.Reroll { id, revision -> guarded(player) { show(player, requireEngine().reroll(player, id, revision)) } },
    )

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
        cancelReveal(event.player.uniqueId)
        roulette.cancel(event.player.uniqueId)
        ledger?.snapshot()?.filter { it.playerId() == event.player.uniqueId }
            ?.forEach { openingAnchors.remove(it.id()) }
        inventory.playerLeft(event.player.uniqueId)
    }

    @EventHandler
    fun onServerLoad(event: ServerLoadEvent) {
        // ARC can finish provider initialization after its onEnable callback.
        if (!ready && (settings.enabled || configurationFailed)) reload()
    }

    fun available(): Boolean = !configurationFailed && (!settings.enabled || ready)

    fun caseIds(): List<String> = settings.cases.keys.sorted()

    private fun administration(sender: CommandSender, args: Array<String>, repair: Boolean) {
        val crateId = args.getOrNull(1) ?: return message(sender, "managed.admin-usage")
        val configured = settings.cases[crateId] ?: return message(sender, "managed.admin-usage")
        val iaFolder = plugin.server.pluginManager.getPlugin("ItemsAdder")?.dataFolder?.toPath()
            ?: return message(sender, "managed.admin-unavailable")
        val gateway = NativeAdminCrateGateway(CratesAPI.getCrateManager(),
            ItemsAdderGroundingProvider(iaFolder, root.resolve("grounding")))
        val service = CrateAdminService(gateway) { id -> pendingCounters(id) }
        if (repair) {
            val result = service.repair(crateId, configured.furnitureId())
            message(sender, "managed.admin-repair", mapOf("crate" to crateId,
                "status" to result.status().name, "count" to result.repairedPositions().toString(),
                "reasons" to result.reasons().joinToString { it.name }))
            renderInspection(sender, result.after() ?: result.before())
        } else renderInspection(sender, service.inspect(crateId, configured.furnitureId()))
    }

    private fun pendingCounters(crateId: String): PendingMailCounters {
        val records = requireLedger().snapshot().filter { it.pool().crateId() == crateId }
        fun count(stage: OpeningRecord.Stage) = records.count { it.stage() == stage }
        return PendingMailCounters(count(OpeningRecord.Stage.RESERVED), count(OpeningRecord.Stage.CHOOSING),
            count(OpeningRecord.Stage.MAIL), count(OpeningRecord.Stage.DELIVERING), count(OpeningRecord.Stage.REVIEW))
    }

    private fun renderInspection(sender: CommandSender, report: CrateInspection) {
        val lines = mutableListOf("managed.admin-title" to mapOf("crate" to report.crateId(),
            "status" to report.status().name, "expected" to report.expectedModel(),
            "key" to (report.nativeKeyCost()?.keyId() ?: "—"),
            "pending" to report.pendingMail().total().toString()))
        report.positions().forEach { row ->
            val position = row.position()
            lines += "managed.admin-position" to mapOf(
                "position" to "${position.world()} ${position.x()},${position.y()},${position.z()}",
                "world" to row.worldStatus().name, "chunk" to row.chunkStatus().name,
                "block" to row.technicalBlock().material(),
                "model" to (row.actualModel()?.namespacedId() ?: "—"),
                "carriers" to row.nearbyCarrierCount().toString(),
                "geometry" to row.grounding().status().name,
                "reasons" to row.reasons().joinToString { it.name })
        }
        messageBlock(sender, lines)
    }

    private fun statistics(sender: CommandSender, args: Array<String>) {
        val crateId = args.getOrNull(1) ?: return message(sender, "managed.stats-usage")
        val season = settings.cases[crateId]?.seasonId()
            ?: return message(sender, "managed.stats-usage")
        val page = args.getOrNull(2)?.toIntOrNull() ?: 1
        require(page > 0) { "Statistics page must be positive" }
        val groups = OpeningAnalytics.summarize(requireLedger().snapshot().filter {
            it.pool().crateId() == crateId && it.pool().seasonId() == season
        })
        val rows = groups.flatMap { group ->
            val selections = group.selectedCounts().values.sum()
            group.baseWeightShares().map { (rewardId, base) ->
                mapOf("mode" to group.mode().name, "reward" to rewardId,
                    "opened" to group.openingCount().toString(),
                    "offered" to (group.offeredCounts()[rewardId] ?: 0).toString(),
                    "selected" to (group.selectedCounts()[rewardId] ?: 0).toString(),
                    "selections" to selections.toString(),
                    "base" to String.format(java.util.Locale.ROOT, "%.2f", base * 100))
            }
        }
        val pages = maxOf(1, (rows.size + 7) / 8)
        require(page <= pages) { "Statistics page is outside the report" }
        val lines = mutableListOf("managed.stats-title" to mapOf("crate" to crateId,
            "page" to page.toString(), "pages" to pages.toString()))
        if (rows.isEmpty()) lines += "managed.stats-empty" to emptyMap()
        rows.drop((page - 1) * 8).take(8).forEach { lines += "managed.stats-row" to it }
        lines += "managed.stats-note" to emptyMap()
        messageBlock(sender, lines)
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

    private fun messageBlock(sender: CommandSender, lines: List<Pair<String, Map<String, String>>>) {
        sender.sendMessage(runtime.locale().renderBlock(sender, lines))
    }
    private fun updateHealth() { runtime.updateRecoveryBacklog(ledger?.snapshot()?.count { it.pending() } ?: 0) }

    override fun close() {
        if (closed) return
        closed = true
        ready = false
        cancelReveals()
        roulette.close()
        openingAnchors.clear()
        plugin.clearManagedPreviewHandler()
        plugin.clearManagedOpenHandler()
        router?.close()
        keys.close()
        HandlerList.unregisterAll(this)
    }

    private data class RevealAnimation(val openingId: java.util.UUID, val task: ScheduledTask)

    private companion object {
        const val REVEAL_PERIOD_TICKS = 4L
    }
}
