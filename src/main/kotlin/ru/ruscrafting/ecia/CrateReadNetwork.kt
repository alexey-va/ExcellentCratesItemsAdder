package ru.ruscrafting.ecia

import com.google.gson.Gson
import com.google.gson.JsonElement
import org.bukkit.inventory.ItemStack
import org.slf4j.LoggerFactory
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TaskScheduler
import ru.arc.network.BackendServerId
import ru.arc.paper.playerstate.PaperItemStackBinaryCodec
import ru.arc.redis.RedisOperations
import ru.arc.redis.network.RedisReplayPolicy
import ru.arc.redis.network.RedisRequestReplyChannel
import ru.arc.redis.network.RedisRequestResult
import ru.arc.redis.network.RedisRequestTimeoutScheduler
import ru.arc.redis.safety.BoundedJsonCodec
import ru.arc.redis.safety.JsonArrayContract
import ru.arc.redis.safety.JsonObjectContract
import ru.arc.redis.safety.JsonResourceBounds
import ru.arc.redis.safety.JsonRootContract
import ru.arc.redis.safety.RedisWireCodec
import ru.ruscrafting.ecia.api.CrateAcquisitionSnapshot
import ru.ruscrafting.ecia.integration.PeriodicPhysicalKey
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.BooleanSupplier
import java.util.function.Function
import java.util.function.LongSupplier
import java.util.function.Supplier

internal data class CrateReadMessage(
    val id: String = "",
    val replyTo: String? = null,
    val operation: String = "",
    val keyId: String = "",
    val createdAtMillis: Long = 0L,
    val snapshots: List<CrateAcquisitionSnapshot> = emptyList(),
    val keyPayload: String = "",
)

/**
 * Read-only current crate pools and one ordinary key template from Spawn.
 * Core owns the bounded request/reply and replay state. The transport origin
 * authorizes replies; no backend identity, player or command enters the DTO.
 * Native capture/serialization and destination deserialization run on Paper;
 * Redis requests/publication run asynchronously. Close settles queued callers,
 * invalidates native work, unregisters the channel and releases its Redis lease.
 */
class CrateReadNetwork(
    private val redis: RedisOperations,
    private val nativeAvailable: BooleanSupplier,
    private val localSnapshots: Supplier<CompletableFuture<List<CrateAcquisitionSnapshot>>>,
    private val localKeyTemplate: Function<String, ItemStack?>,
    private val items: PaperItemStackBinaryCodec,
    private val nowMillis: LongSupplier,
    scheduler: TaskScheduler,
    timeoutScheduler: RedisRequestTimeoutScheduler,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(CrateReadNetwork::class.java)
    private val tasks = LifecycleTaskScope(scheduler, initiallyActive = true)
    private val token = tasks.token()
    private val closed = AtomicBoolean(false)
    private val failures = ConcurrentHashMap.newKeySet<String>()
    // Lifecycle promises include work queued before core can register its request.
    private val callers = mutableSetOf<CompletableFuture<CrateReadMessage?>>()
    private val channel = RedisRequestReplyChannel(
        redis = redis, channel = CrateReadWire.CHANNEL, codec = CrateReadWire.codec,
        originAllowed = { BackendServerId.parseOrNull(it) != null },
        requestId = CrateReadMessage::id, replyTo = CrateReadMessage::replyTo,
        replyAllowed = { request, reply, origin -> CrateReadWire.isExactReply(request, reply, origin, nowMillis.asLong) },
        timeoutMillis = CrateReadWire.TTL_MILLIS, maxPending = MAX_CALLERS,
        timeoutScheduler = timeoutScheduler, embeddedOrigin = null,
        replay = RedisReplayPolicy(CrateReadMessage::id, 60_000L, 4096),
        clockMillis = { nowMillis.asLong }, onMessage = ::receive,
        onRejected = {}, onReplyRejected = {}, onHandlerFailure = { report("redis-handler", it) },
    )

    fun acquisitionSnapshotsAsync(): CompletableFuture<List<CrateAcquisitionSnapshot>> =
        request("SNAPSHOTS", "").thenApply { it?.snapshots ?: emptyList() }

    fun keyTemplateAsync(keyId: String): CompletableFuture<ItemStack?> {
        if (!KeyGrantRequest.safeKeyId(keyId)) return CompletableFuture.completedFuture(null)
        return request("KEY_TEMPLATE", keyId).thenCompose { reply ->
            if (reply == null || reply.keyPayload.isEmpty()) CompletableFuture.completedFuture(null)
            else tasks.callSync(token) {
                if (closed.get() || !CrateReadWire.isFresh(reply.createdAtMillis, nowMillis.asLong)) null
                else items.decodeItem(Base64.getDecoder().decode(reply.keyPayload)).also(::validateKey)
            }
        }.exceptionally { failure -> report("decode-key", failure); null }
    }

    private fun request(operation: String, keyId: String): CompletableFuture<CrateReadMessage?> {
        val now = nowMillis.asLong
        if (closed.get() || now < 0L) return CompletableFuture.completedFuture(null)
        val message = CrateReadMessage(id = UUID.randomUUID().toString(), operation = operation, keyId = keyId, createdAtMillis = now)
        val result = CompletableFuture<CrateReadMessage?>()
        synchronized(callers) {
            if (closed.get() || callers.size >= MAX_CALLERS) return CompletableFuture.completedFuture(null)
            callers += result
        }
        result.whenComplete { _, _ -> synchronized(callers) { callers.remove(result) } }
        try {
            val scheduled = tasks.runAsync(token) {
                if (closed.get() || !CrateReadWire.isFresh(now, nowMillis.asLong)) {
                    result.complete(null)
                    return@runAsync
                }
                channel.request(message).whenComplete { reply, failure ->
                    if (failure != null) report("request", failure)
                    if (reply is RedisRequestResult.InfrastructureFailure) report("request", reply.cause)
                    val accepted = if (!closed.get() && failure == null && reply is RedisRequestResult.Reply &&
                        CrateReadWire.isFresh(now, nowMillis.asLong)) reply.message else null
                    if (accepted != null && failures.remove("request")) log.info("CRATE_READ_NETWORK phase=request recovered")
                    result.complete(accepted)
                }
            }
            if (scheduled == null) result.complete(null)
        } catch (failure: RuntimeException) { report("dispatch-request", failure); result.complete(null) }
        return result
    }

    private fun receive(message: CrateReadMessage, origin: String) {
        if (closed.get() || !CrateReadWire.isQuery(message, nowMillis.asLong)) return
        tasks.callSync(token) {
            if (closed.get() || !CrateReadWire.isQuery(message, nowMillis.asLong) || !nativeAvailable.asBoolean) {
                CompletableFuture.completedFuture<CrateReadMessage?>(null)
            } else if (message.operation == "SNAPSHOTS") {
                localSnapshots.get().thenApply<CrateReadMessage?> { snapshots -> reply(message).copy(snapshots = snapshots) }
            } else {
                val key = localKeyTemplate.apply(message.keyId)
                val payload = if (key == null) "" else {
                    validateKey(key)
                    val bytes = items.encodeItem(key)
                    require(bytes.size in 1..CrateReadWire.MAX_ITEM_BYTES) { "Native key template is too large" }
                    Base64.getEncoder().encodeToString(bytes)
                }
                CompletableFuture.completedFuture(reply(message).copy(keyPayload = payload))
            }
        }.thenCompose { it }.whenComplete { response, failure ->
            if (closed.get()) return@whenComplete
            if (failure != null) { report("source-capture", failure); return@whenComplete }
            if (response == null) return@whenComplete
            try {
                tasks.runAsync(token) {
                    if (!closed.get() && CrateReadWire.isFresh(message.createdAtMillis, nowMillis.asLong)) {
                        try { channel.publish(response) }
                        catch (publishFailure: RuntimeException) { report("publish-reply", publishFailure) }
                    }
                }
            } catch (dispatchFailure: RuntimeException) { report("dispatch-reply", dispatchFailure) }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try { channel.close() }
        finally {
            try { tasks.close() }
            finally {
                val abandoned = synchronized(callers) { callers.toList().also { callers.clear() } }
                abandoned.forEach { it.complete(null) }
                redis.close()
            }
        }
    }

    private fun report(phase: String, failure: Throwable) {
        if (!closed.get() && failures.add(phase)) log.warn("CRATE_READ_NETWORK phase={}", phase, failure)
    }

    private fun reply(request: CrateReadMessage) = request.copy(id = UUID.randomUUID().toString(), replyTo = request.id)
    private fun validateKey(item: ItemStack) {
        require(!item.isEmpty && item.amount == 1 && !PeriodicPhysicalKey.isMarked(item)) { "An ordinary single physical key is required" }
    }

    private companion object { const val MAX_CALLERS = 32 }
}

/** Domain-only bounds; the shared core transport retains correlation and replay ownership. */
internal object CrateReadWire {
    const val CHANNEL = "arc:crates:read:v1"
    const val TTL_MILLIS = 10_000L
    const val MAX_ITEM_BYTES = 64 * 1024
    private const val MAX_CASES = 64
    private const val MAX_REWARDS_PER_CASE = 512
    private const val MAX_TOTAL_REWARDS = 8192
    private const val MAX_PAYLOAD = 1024 * 1024
    private val text = checked { require(it.isJsonPrimitive && it.asJsonPrimitive.isString) }
    private val timestamp = checked {
        require(it.isJsonPrimitive && it.asJsonPrimitive.isNumber)
        require(it.asBigDecimal.longValueExact() >= 0L)
    }
    private val number = checked { require(it.isJsonPrimitive && it.asJsonPrimitive.isNumber && it.asDouble.isFinite()) }
    private val flag = checked { require(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean) }
    private val rewardContract = JsonObjectContract(
        setOf("rewardId", "displayName", "weight", "probability", "catalogCategoryId", "catalogEntryId"),
        fieldContracts = mapOf("rewardId" to text, "displayName" to text, "weight" to number,
            "probability" to number, "catalogCategoryId" to text, "catalogEntryId" to text),
    )
    private val snapshotContract = JsonObjectContract(
        allowedFields = setOf("crateId", "displayName", "available", "rewards"),
        fieldContracts = mapOf("crateId" to text, "displayName" to text, "available" to flag,
            "rewards" to JsonArrayContract(maxEntries = MAX_REWARDS_PER_CASE, elementContract = rewardContract)),
    )
    private val json = BoundedJsonCodec(
        gson = Gson(), type = CrateReadMessage::class.java,
        rootContract = JsonObjectContract(
            allowedFields = setOf("id", "replyTo", "operation", "keyId", "createdAtMillis", "snapshots", "keyPayload"),
            requiredFields = setOf("id", "operation", "keyId", "createdAtMillis", "snapshots", "keyPayload"),
            fieldContracts = mapOf("id" to text, "replyTo" to checked { if (!it.isJsonNull) text.validate(it) },
                "operation" to text, "keyId" to text, "createdAtMillis" to timestamp, "keyPayload" to text,
                "snapshots" to JsonArrayContract(maxEntries = MAX_CASES, elementContract = snapshotContract)),
        ),
        bounds = JsonResourceBounds(MAX_PAYLOAD, 7, MAX_TOTAL_REWARDS, 100_000, (MAX_ITEM_BYTES + 2) / 3 * 4),
        validate = ::validate,
    )
    val codec: RedisWireCodec<CrateReadMessage> = object : RedisWireCodec<CrateReadMessage> {
        override fun encode(value: CrateReadMessage): String = json.encode(value).also(::boundBytes)
        override fun decode(raw: String): CrateReadMessage { boundBytes(raw); return json.decode(raw) }
    }

    fun isFresh(created: Long, now: Long): Boolean = created >= 0L && now >= created && now - created <= TTL_MILLIS
    fun isQuery(message: CrateReadMessage, now: Long): Boolean = message.replyTo == null && isFresh(message.createdAtMillis, now)
    fun isExactReply(request: CrateReadMessage, reply: CrateReadMessage, origin: String, now: Long): Boolean =
        origin == "spawn" && request.replyTo == null && reply.replyTo == request.id && reply.id != request.id &&
            reply.operation == request.operation && reply.keyId == request.keyId &&
            reply.createdAtMillis == request.createdAtMillis && isFresh(request.createdAtMillis, now)

    private fun validate(message: CrateReadMessage) {
        require(uuid(message.id) && (message.replyTo == null || uuid(message.replyTo))) { "Invalid crate read correlation" }
        require(message.createdAtMillis >= 0L) { "Invalid crate read timestamp" }
        when (message.operation) {
            "SNAPSHOTS" -> require(message.keyId.isEmpty() && message.keyPayload.isEmpty()) { "Invalid snapshot query fields" }
            "KEY_TEMPLATE" -> require(KeyGrantRequest.safeKeyId(message.keyId) && message.snapshots.isEmpty()) { "Invalid key query fields" }
            else -> error("Unknown crate read operation")
        }
        if (message.replyTo == null) require(message.snapshots.isEmpty() && message.keyPayload.isEmpty()) { "A query cannot contain a result" }
        if (message.keyPayload.isNotEmpty()) {
            require(message.keyPayload.length <= (MAX_ITEM_BYTES + 2) / 3 * 4) { "Key template payload exceeds its limit" }
            val decoded = Base64.getDecoder().decode(message.keyPayload)
            require(decoded.size in 1..MAX_ITEM_BYTES && Base64.getEncoder().encodeToString(decoded) == message.keyPayload) { "Invalid key template payload" }
        }
        require(message.snapshots.size <= MAX_CASES && message.snapshots.map { it.crateId() }.distinct().size == message.snapshots.size) { "Invalid crate snapshot collection" }
        var totalRewards = 0
        message.snapshots.forEach { snapshot ->
            require(safeText(snapshot.crateId(), 128) && safeText(snapshot.displayName(), 512)) { "Invalid crate snapshot identity" }
            val rewards = snapshot.rewards()
            totalRewards += rewards.size
            require(rewards.size <= MAX_REWARDS_PER_CASE && totalRewards <= MAX_TOTAL_REWARDS) { "Too many crate reward rows" }
            require(snapshot.available() || rewards.isEmpty()) { "Unavailable crate contains rewards" }
            require(rewards.map { it.rewardId() }.distinct().size == rewards.size) { "Duplicate crate reward identity" }
            rewards.forEach { reward ->
                require(safeText(reward.rewardId(), 128) && safeText(reward.displayName(), 512)) { "Invalid crate reward identity" }
                require(reward.weight().isFinite() && reward.weight() > 0.0 && reward.probability().isFinite() && reward.probability() in 0.0..1.0) { "Invalid crate reward probability" }
                require(reward.catalogCategoryId().isEmpty() == reward.catalogEntryId().isEmpty() &&
                    (reward.catalogCategoryId().isEmpty() || safeText(reward.catalogCategoryId(), 128) && safeText(reward.catalogEntryId(), 128))) { "Invalid reward catalogue reference" }
            }
            require(rewards.isEmpty() || kotlin.math.abs(rewards.sumOf { it.probability() } - 1.0) <= 0.000001) { "Crate reward denominator is incomplete" }
        }
    }

    private fun boundBytes(raw: String) { require(raw.length <= MAX_PAYLOAD && raw.toByteArray(Charsets.UTF_8).size <= MAX_PAYLOAD) { "Crate read payload is too large" } }
    private fun checked(check: (JsonElement) -> Unit) = object : JsonRootContract {
        override fun validate(value: JsonElement) = check(value)
    }
    private fun safeText(value: String, max: Int) = value.length in 1..max && value.none(Char::isISOControl)
    private fun uuid(value: String) = value.length == 36 && runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
}
