package ru.ruscrafting.ecia.integration

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import ru.arc.core.whenCompleteSync
import ru.ruscrafting.ecia.ArcExcellentCratesPlugin
import ru.ruscrafting.ecia.journal.PeriodicKeyLedger
import su.nightexpress.nightcore.util.text.NightMessage
import java.time.Clock
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.function.Predicate

/** Owns online calendar reconciliation, capacity retries and the daily notice gate. */
internal class PeriodicKeysService(
    private val plugin: ArcExcellentCratesPlugin,
    private val keys: NativeSeasonKeys,
    private val issuer: PeriodicKeyIssuer,
    private val ledger: PeriodicKeyLedger,
    private val storage: Executor,
    private val access: PlayerAccess,
    deliveredIds: Set<UUID>,
    private val clock: Clock = Clock.systemUTC(),
) : AutoCloseable {
    interface PlayerAccess {
        fun acquire(player: Player): Long?
        fun current(player: Player, session: Long): Boolean
        fun release(player: Player, session: Long)
    }
    private val runtime = plugin.runtime()
    private val tasks = runtime.tasks()
    private val token = tasks.token()
    private var settings = ManagedCratesSettings(false, emptyMap())
    private var loadedCases = emptySet<String>()
    private val players = mutableMapOf<UUID, Player>()
    private val nextCheck = mutableMapOf<UUID, Long>()
    private val finished = mutableMapOf<UUID, MutableSet<UUID>>()
    private val delivered = deliveredIds.toMutableSet()
    private val awaitingReconnect = mutableSetOf<UUID>()
    private var storageFailed = false
    private var closed = false
    private val timer = tasks.runTimer(20L, 20L) { tick() }

    fun configure(settings: ManagedCratesSettings, loadedCases: Set<String>) {
        this.settings = settings
        this.loadedCases = loadedCases.toSet()
        finished.clear()
    }

    fun playerDataLoaded(player: Player) {
        issuer.playerDataLoaded(player)
        players[player.uniqueId] = player
        finished.remove(player.uniqueId)
        awaitingReconnect.remove(player.uniqueId)
        nextCheck[player.uniqueId] = clock.millis() + 2_000
    }

    fun playerLeft(player: Player) {
        if (players[player.uniqueId] !== player) return
        players.remove(player.uniqueId)
        nextCheck.remove(player.uniqueId)
        finished.remove(player.uniqueId)
        awaitingReconnect.remove(player.uniqueId)
        issuer.playerLeft(player.uniqueId)
    }

    fun matches(player: Player, crateId: String, period: PeriodicVirtualOpening.Period): Predicate<ItemStack> = Predicate { item ->
        PeriodicPhysicalKey.valid(item, player.uniqueId, crateId, period, settings.freeOpeningZone(), clock.instant()) &&
            PeriodicPhysicalKey.identify(item).map { it.id() in delivered }.orElse(false)
    }

    private fun tick() {
        if (closed || storageFailed || !settings.enabled) return
        for (player in players.values.toList()) {
            val id = player.uniqueId
            if (!player.isOnline || player.isDead || !player.hasPermission("ecia.use") || id in awaitingReconnect) continue
            if (clock.millis() < (nextCheck[id] ?: 0)) continue
            nextCheck[id] = clock.millis() + 5_000
            val candidates = settings.cases.values.filter {
                it.freeOpenPeriod() != PeriodicVirtualOpening.Period.NONE && it.crateId() in loadedCases
            }.sortedWith(compareBy({ it.freeOpenPeriod().ordinal }, { it.crateId() }))
            val done = finished.getOrPut(id) { mutableSetOf() }
            val windows = candidates.associateWith { PeriodicVirtualOpening.window(it.freeOpenPeriod(), clock.instant(), settings.freeOpeningZone()) }
            val currentIds = windows.map { (case, window) -> PeriodicVirtualOpening.openingId(id, case.crateId(), window.period(), window) }.toSet()
            done.retainAll(currentIds)
            if (currentIds.all { it in done }) continue
            val session = access.acquire(player) ?: continue
            val received = mutableListOf<Pair<ManagedCratesSettings.CaseSettings, String>>()
            var noticeDay: LocalDate? = null
            var chain = CompletableFuture.completedFuture(true)
            for ((case, window) in windows) {
                val grantId = PeriodicVirtualOpening.openingId(id, case.crateId(), window.period(), window)
                if (grantId in done) continue
                chain = chain.thenCompose { proceed ->
                    if (!proceed) CompletableFuture.completedFuture(false) else onPaper {
                        if (!access.current(player, session) || !eligible(player, case, window)) null else {
                            val crate = su.nightexpress.excellentcrates.CratesAPI.getCrateManager().getCrateById(case.crateId())!!
                            val cost = keys.cost(crate)
                            if (cost.amount() != 1) null else Pair(cost.keyId(), NightMessage.stripAll(crate.name))
                        }
                    }.thenCompose { target ->
                        if (target == null) CompletableFuture.completedFuture(false) else issuer.grant(player, session, case.crateId(), window,
                            { makeKey(player, case, target.first, window) }, { eligible(player, case, window) })
                            .thenCompose { result -> onPaper {
                                if (!access.current(player, session)) false else {
                                    when (result) {
                                        PeriodicKeyIssuer.Result.DELIVERED, PeriodicKeyIssuer.Result.ALREADY_DELIVERED -> {
                                            delivered.add(grantId)
                                            done.add(grantId)
                                            if (player.inventory.contents.any { it != null && matches(player, case.crateId(), case.freeOpenPeriod()).test(it) }) {
                                                received.add(case to target.second)
                                            }
                                        }
                                        PeriodicKeyIssuer.Result.SKIPPED -> done.add(grantId)
                                        PeriodicKeyIssuer.Result.REVIEW -> {
                                            awaitingReconnect.add(id)
                                            runtime.warn("Periodic key delivery awaits player-data reconciliation: player={}, case={}", id, case.crateId())
                                        }
                                        else -> Unit
                                    }
                                    result != PeriodicKeyIssuer.Result.REVIEW && result != PeriodicKeyIssuer.Result.FULL
                                }
                            } }
                    }
                }
            }
            chain.thenCompose { onPaper {
                if (access.current(player, session)) received.toList() else emptyList()
            } }.thenCompose { granted ->
                if (granted.isEmpty()) CompletableFuture.completedFuture(emptyList()) else {
                    val day = clock.instant().atZone(settings.freeOpeningZone()).toLocalDate()
                    noticeDay = day
                    CompletableFuture.supplyAsync({ if (ledger.claimNotice(id, day)) granted else emptyList() }, storage)
                }
            }.whenCompleteSync(tasks, token) { granted, failure ->
                access.release(player, session)
                if (failure != null) {
                    if (access.current(player, session)) {
                        storageFailed = true
                        runtime.error("Periodic key delivery paused until restart: {}", failure.toString())
                    }
                } else if (access.current(player, session) && !granted.isNullOrEmpty()
                    && noticeDay == clock.instant().atZone(settings.freeOpeningZone()).toLocalDate()) {
                    notify(player, granted)
                }
            }
        }
    }

    private fun eligible(player: Player, case: ManagedCratesSettings.CaseSettings, window: PeriodicVirtualOpening.Window): Boolean {
        if (closed || !settings.enabled || case != settings.cases[case.crateId()] || case.crateId() !in loadedCases
            || !player.isOnline || player.isDead || !player.hasPermission("ecia.use")) return false
        if (window != PeriodicVirtualOpening.window(case.freeOpenPeriod(), clock.instant(), settings.freeOpeningZone())) return false
        val crate = su.nightexpress.excellentcrates.CratesAPI.getCrateManager().getCrateById(case.crateId()) ?: return false
        return crate.hasPermission(player) && su.nightexpress.excellentcrates.CratesAPI.plugin().dataManager.isDataLoaded
    }

    private fun makeKey(player: Player, case: ManagedCratesSettings.CaseSettings, keyId: String, window: PeriodicVirtualOpening.Window): ItemStack {
        val item = PeriodicPhysicalKey.stamp(keys.create(keyId, 1), player.uniqueId, case.crateId(), keyId, window)
        val locale = runtime.locale()
        val expiryKey = if (window.period() == PeriodicVirtualOpening.Period.DAILY) "key.expires-daily" else "key.expires-weekly"
        val date = PlainTextComponentSerializer.plainText().serialize(locale.render(expiryKey, player, emptyMap()))
        item.editMeta { meta ->
            val lore = (meta.lore() ?: emptyList()).toMutableList()
            lore.add(Component.empty())
            lore.add(locale.render("key.periodic-owner", player, mapOf("owner" to player.name)).decoration(TextDecoration.ITALIC, false))
            lore.add(locale.render("key.periodic-expires", player, mapOf("date" to date)).decoration(TextDecoration.ITALIC, false))
            meta.lore(lore)
        }
        return item
    }

    private fun notify(player: Player, keys: List<Pair<ManagedCratesSettings.CaseSettings, String>>) {
        val daily = keys.firstOrNull { it.first.freeOpenPeriod() == PeriodicVirtualOpening.Period.DAILY }
        val weekly = keys.firstOrNull { it.first.freeOpenPeriod() == PeriodicVirtualOpening.Period.WEEKLY }
        val key = if (keys.size == 2 && daily != null && weekly != null) "key.periodic-received-both" else "key.periodic-received-one"
        val values = if (key.endsWith("both")) mapOf("daily" to daily!!.second, "weekly" to weekly!!.second)
            else mapOf("crate" to keys.joinToString(", ") { it.second })
        player.sendMessage(runtime.locale().renderPadded(key, player, values))
    }

    private fun <T> onPaper(action: () -> T): CompletableFuture<T> {
        val result = CompletableFuture<T>()
        if (tasks.runSync(token) { runCatching(action).fold(result::complete, result::completeExceptionally) } == null) {
            result.completeExceptionally(IllegalStateException("Periodic key lifecycle closed"))
        }
        return result
    }

    override fun close() {
        closed = true
        timer?.cancel()
        players.clear()
        nextCheck.clear()
        finished.clear()
        delivered.clear()
        awaitingReconnect.clear()
    }
}
