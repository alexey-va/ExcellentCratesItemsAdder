package ru.ruscrafting.ecia.integration

import com.google.gson.Gson
import com.google.gson.JsonElement
import ru.arc.core.LifecycleTaskScope
import ru.arc.redis.ChannelListener
import ru.arc.redis.RedisOperations
import ru.arc.redis.activity.PlayerActivityStore
import ru.arc.redis.safety.BoundedJsonCodec
import ru.arc.redis.safety.JsonArrayContract
import ru.arc.redis.safety.JsonObjectContract
import ru.arc.redis.safety.JsonResourceBounds
import ru.arc.redis.safety.JsonRootContract
import ru.ruscrafting.ecia.journal.OpeningLedger
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Owns the MAIL sweep and its proxy-wide absence proof; all journal work uses storage. */
class OpeningMailExpiry(
    private val redis: RedisOperations,
    private val ledger: OpeningLedger,
    private val storage: Executor,
    tasks: LifecycleTaskScope,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val onExpired: (Int) -> Unit = {},
    private val report: (String, Throwable?) -> Unit = { _, _ -> },
) : AutoCloseable {
    private data class Roster(val players: Set<UUID>, val receivedAt: Long)
    private val activities = PlayerActivityStore(redis)
    private val roster = AtomicReference<Roster?>()
    private val closed = AtomicBoolean()
    private val sweeping = AtomicBoolean()
    private val rosterRejected = AtomicBoolean()
    private val sweepFailed = AtomicBoolean()
    private val listener = ChannelListener { channel, message, origin ->
        if (!closed.get() && channel == CHANNEL && origin == PROXY_ORIGIN) {
            val receivedAt = clockMillis()
            try {
                val players = RosterWire.decode(message).map { UUID.fromString(it.uuid) }.toSet()
                roster.set(Roster(players, receivedAt))
                if (rosterRejected.getAndSet(false)) report("proxy-roster-recovered", null)
            } catch (failure: RuntimeException) {
                roster.set(null)
                if (rosterRejected.compareAndSet(false, true)) report("proxy-roster-rejected", failure)
            }
        }
    }
    init { redis.registerChannelUnique(CHANNEL, listener) }
    private val timer = tasks.runTimerAsync(20L, 1_200L) { sweep() }

    /** Unknown/stale presence and missing/future activity preserve every reward. */
    fun sweep(): CompletableFuture<Int> {
        if (closed.get() || !sweeping.compareAndSet(false, true)) return CompletableFuture.completedFuture(0)
        val result: CompletableFuture<Int> = try {
            CompletableFuture.supplyAsync({
                if (closed.get() || freshRoster(clockMillis()) == null) emptyList() else ledger.mail()
            }, storage).thenCompose { candidates ->
                if (closed.get() || candidates.isEmpty()) CompletableFuture.completedFuture(0)
                else {
                    val requestedAt = clockMillis()
                    activities.load().thenApplyAsync({ activity ->
                        var expired = 0
                        for (record in candidates) {
                            if (closed.get()) break
                            val now = clockMillis()
                            if (now < requestedAt || now - requestedAt > ROSTER_FRESH_MILLIS) break
                            val online = freshRoster(now) ?: break
                            if (record.playerId() in online.players) continue
                            val lastSeen = activity.lastSeen[record.playerId()] ?: continue
                            if (ledger.expireMail(record.id(), record.playerId(), record.revision(), lastSeen, now)) expired++
                        }
                        expired
                    }, storage)
                }
            }
        } catch (failure: RuntimeException) {
            CompletableFuture.failedFuture(failure)
        }
        return result.whenComplete { count, failure ->
            sweeping.set(false)
            if (!closed.get()) {
                if (failure != null) {
                    if (sweepFailed.compareAndSet(false, true)) report("mail-expiry-failed", failure)
                } else {
                    if (sweepFailed.getAndSet(false)) report("mail-expiry-recovered", null)
                    if (count > 0) onExpired(count)
                }
            }
        }
    }

    private fun freshRoster(now: Long): Roster? = roster.get()?.takeIf {
        now >= it.receivedAt && now - it.receivedAt <= ROSTER_FRESH_MILLIS
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        timer?.cancel()
        roster.set(null)
        try { redis.unregisterChannel(CHANNEL, listener) } finally { redis.close() }
    }

    companion object {
        const val CHANNEL = "arc.proxy_player_list"
        const val PROXY_ORIGIN = "proxy"
        const val ROSTER_FRESH_MILLIS = 10_000L
    }
}

private data class ProxyPlayer(val username: String, val server: String, val uuid: String, val joinTime: Long)

private object RosterWire {
    private fun checked(check: (JsonElement) -> Unit) = object : JsonRootContract {
        override fun validate(value: JsonElement) = check(value)
    }
    private val text = checked { require(it.isJsonPrimitive && it.asJsonPrimitive.isString) }
    private val timestamp = checked {
        require(it.isJsonPrimitive && it.asJsonPrimitive.isNumber && it.asBigDecimal.longValueExact() > 0)
    }
    private val codec = BoundedJsonCodec(
        gson = Gson(), type = Array<ProxyPlayer>::class.java,
        rootContract = JsonArrayContract(maxEntries = 4096, elementContract = JsonObjectContract(
            allowedFields = setOf("username", "server", "uuid", "joinTime"),
            fieldContracts = mapOf("username" to text, "server" to text, "uuid" to text, "joinTime" to timestamp),
        )),
        bounds = JsonResourceBounds(1_048_576, 3, 4096, 40_961, 128),
        validate = { players ->
            require(players.map { it.uuid }.distinct().size == players.size) { "Duplicate proxy player identity" }
            players.forEach {
                require(it.username.isNotBlank() && it.server.isNotBlank() && it.joinTime > 0)
                require(it.uuid.length == 36 && UUID.fromString(it.uuid).toString() == it.uuid) { "Invalid proxy player identity" }
            }
        },
    )
    fun decode(raw: String): Array<ProxyPlayer> = codec.decode(raw)
}
