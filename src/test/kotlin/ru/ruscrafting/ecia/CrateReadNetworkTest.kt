package ru.ruscrafting.ecia

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.playerstate.NativePaperItemStackBinaryCodec
import ru.arc.paper.playerstate.PaperItemStackBinaryCodec
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.redis.InMemoryRedis
import ru.arc.redis.ServerIdentity
import ru.arc.redis.network.RedisRequestTimeoutHandle
import ru.arc.redis.network.RedisRequestTimeoutScheduler
import ru.ruscrafting.ecia.api.CrateAcquisitionReward
import ru.ruscrafting.ecia.api.CrateAcquisitionSnapshot
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class CrateReadNetworkTest : FreeSpec({
    "source captures current full native pools on its scheduler and remote consumes them without native EC" {
        val sourceRedis = InMemoryRedis(ServerIdentity { "spawn" })
        val remoteRedis = InMemoryRedis(ServerIdentity { "survival" })
        val sourceTasks = TestTaskScheduler()
        val remoteTasks = TestTaskScheduler()
        var captures = 0
        var current = loadedCrate(0.25)
        reader(sourceRedis, sourceTasks, source = true, capture = {
            captures++
            CompletableFuture.completedFuture(listOf(current))
        }).use {
            reader(remoteRedis, remoteTasks).use { remote ->
                repeat(2) { index ->
                    val pending = remote.acquisitionSnapshotsAsync()
                    remoteTasks.executeImmediate()
                    val query = remoteRedis.getPublishedMessages().last().message
                    sourceRedis.simulateExternalMessage(CrateReadWire.CHANNEL, query, "survival")
                    captures shouldBe index
                    sourceTasks.executeImmediate()
                    captures shouldBe index + 1
                    sourceTasks.executeImmediate()
                    val reply = sourceRedis.getPublishedMessages().last().message
                    remoteRedis.simulateExternalMessage(CrateReadWire.CHANNEL, reply, "spawn")
                    pending.get(1, TimeUnit.SECONDS) shouldBe listOf(current)
                    current = loadedCrate(0.75)
                }
            }
        }
    }

    "key template is one current ordinary stack with its native and ItemsAdder metadata on destination Paper" {
        MockBukkitTestRuntime.open().use {
            val sourceRedis = InMemoryRedis(ServerIdentity { "spawn" })
            val remoteRedis = InMemoryRedis(ServerIdentity { "survival" })
            val sourceTasks = TestTaskScheduler()
            val remoteTasks = TestTaskScheduler()
            val nativeId = NamespacedKey("excellentcrates", "key_id")
            val modelId = NamespacedKey("itemsadder", "id")
            val template = ItemStack(Material.PAPER).also { item ->
                item.editMeta { meta ->
                    meta.persistentDataContainer.set(nativeId, PersistentDataType.STRING, "daily")
                    meta.persistentDataContainer.set(modelId, PersistentDataType.STRING, "crate_key")
                    meta.setCustomModelData(12236)
                }
            }
            val native = NativePaperItemStackBinaryCodec
            val checkedCodec = object : PaperItemStackBinaryCodec by native {
                override fun encodeItem(item: ItemStack): ByteArray {
                    Bukkit.isPrimaryThread() shouldBe true
                    return native.encodeItem(item)
                }
                override fun decodeItem(payload: ByteArray): ItemStack {
                    Bukkit.isPrimaryThread() shouldBe true
                    return native.decodeItem(payload)
                }
            }
            var captured = false
            reader(sourceRedis, sourceTasks, source = true, items = checkedCodec, key = { id ->
                Bukkit.isPrimaryThread() shouldBe true
                id shouldBe "daily"
                captured = true
                template.clone()
            }).use {
                reader(remoteRedis, remoteTasks, items = checkedCodec).use { remote ->
                    val pending = remote.keyTemplateAsync("daily")
                    remoteTasks.executeImmediate()
                    sourceRedis.simulateExternalMessage(CrateReadWire.CHANNEL, remoteRedis.getPublishedMessages().single().message, "survival")
                    captured shouldBe false
                    sourceTasks.executeImmediate()
                    captured shouldBe true
                    sourceTasks.executeImmediate()
                    remoteRedis.simulateExternalMessage(CrateReadWire.CHANNEL, sourceRedis.getPublishedMessages().single().message, "spawn")
                    pending.isDone shouldBe false
                    remoteTasks.executeImmediate()
                    val received = pending.get(1, TimeUnit.SECONDS)!!
                    received.amount shouldBe 1
                    received.itemMeta.persistentDataContainer.get(nativeId, PersistentDataType.STRING) shouldBe "daily"
                    received.itemMeta.persistentDataContainer.get(modelId, PersistentDataType.STRING) shouldBe "crate_key"
                    received.itemMeta.customModelData shouldBe 12236
                    received.amount = 9
                    template.amount shouldBe 1
                }
            }
        }
    }

    "spoofed mismatched and stale replies never consume the exact pending request" {
        val redis = InMemoryRedis(ServerIdentity { "survival" })
        val tasks = TestTaskScheduler()
        reader(redis, tasks).use { remote ->
            val pending = remote.keyTemplateAsync("daily")
            tasks.executeImmediate()
            val query = CrateReadWire.codec.decode(redis.getPublishedMessages().single().message)
            fun reply() = query.copy(id = UUID.randomUUID().toString(), replyTo = query.id)
            fun deliver(message: CrateReadMessage, origin: String = "spawn") {
                redis.simulateExternalMessage(CrateReadWire.CHANNEL, CrateReadWire.codec.encode(message), origin)
                pending.isDone shouldBe false
            }
            deliver(reply(), "survival")
            deliver(reply().copy(keyId = "weekly"))
            deliver(reply().copy(operation = "SNAPSHOTS", keyId = ""))
            deliver(reply().copy(replyTo = UUID.randomUUID().toString()))
            deliver(reply().copy(createdAtMillis = query.createdAtMillis - 1L))
            redis.simulateExternalMessage(CrateReadWire.CHANNEL, CrateReadWire.codec.encode(reply()), "spawn")
            pending.get(1, TimeUnit.SECONDS) shouldBe null
        }
    }

    "timeouts capacity and close degrade to unknown and release every lifecycle request" {
        val redis = InMemoryRedis(ServerIdentity { "survival" })
        val tasks = TestTaskScheduler()
        val timeouts = ReadTimeouts()
        val remote = reader(redis, tasks, timeouts = timeouts)
        val timedOut = remote.acquisitionSnapshotsAsync()
        tasks.executeImmediate()
        timeouts.fire()
        timedOut.get(1, TimeUnit.SECONDS) shouldBe emptyList()
        val pending = List(32) { remote.acquisitionSnapshotsAsync() }
        remote.acquisitionSnapshotsAsync().isDone shouldBe true
        remote.close()
        remote.close()
        pending.forEach { it.get(1, TimeUnit.SECONDS) shouldBe emptyList() }
        tasks.executeImmediate()
        redis.getPublishedMessages().size shouldBe 1
        redis.listenerCount(CrateReadWire.CHANNEL) shouldBe 0
        remote.keyTemplateAsync("daily").get(1, TimeUnit.SECONDS) shouldBe null
    }

    "source absence expiry and closure do not capture or publish native state" {
        val redis = InMemoryRedis(ServerIdentity { "survival" })
        val tasks = TestTaskScheduler()
        var captures = 0
        val network = reader(redis, tasks, capture = { captures++; CompletableFuture.completedFuture(emptyList()) })
        val query = CrateReadMessage(id = UUID.randomUUID().toString(), operation = "SNAPSHOTS", createdAtMillis = 10_000L)
        redis.simulateExternalMessage(CrateReadWire.CHANNEL, CrateReadWire.codec.encode(query), "spawn")
        tasks.executeImmediate()
        captures shouldBe 0
        network.close()
        tasks.executeImmediate()
        redis.getPublishedMessages() shouldBe emptyList()
        CrateReadWire.isQuery(query, 20_001L) shouldBe false
    }

    "wire rejects malformed nested fields nonfinite odds incomplete denominators and oversized keys" {
        val reply = CrateReadMessage(id = UUID.randomUUID().toString(), replyTo = UUID.randomUUID().toString(),
            operation = "SNAPSHOTS", createdAtMillis = 10_000L, snapshots = listOf(loadedCrate(0.25)))
        val encoded = CrateReadWire.codec.encode(reply)
        CrateReadWire.codec.decode(encoded) shouldBe reply
        listOf(encoded + "{}", encoded.replace("\"weight\":", "\"playerId\":\"bad\",\"weight\":"),
            encoded.replace("\"createdAtMillis\":10000", "\"createdAtMillis\":\"10000\""),
            encoded.replace("\"probability\":0.25", "\"probability\":\"0.25\""),
            encoded.replace("\"probability\":0.25", "\"probability\":\"NaN\""),
            encoded.replace("\"probability\":0.25", "\"probability\":0.2"),
            encoded.replace("\"snapshots\":[", "\"snapshots\":[null,"),
        ).forEach { raw -> runCatching { CrateReadWire.codec.decode(raw) }.isFailure shouldBe true }
        val key = reply.copy(operation = "KEY_TEMPLATE", keyId = "daily", snapshots = emptyList(),
            keyPayload = Base64.getEncoder().encodeToString(ByteArray(CrateReadWire.MAX_ITEM_BYTES + 1)))
        runCatching { CrateReadWire.codec.encode(key) }.isFailure shouldBe true
        runCatching { CrateReadWire.codec.encode(key.copy(keyPayload = "bad base64")) }.isFailure shouldBe true
    }
})

private class ReadTimeouts : RedisRequestTimeoutScheduler {
    private val pending = linkedSetOf<() -> Unit>()
    override fun schedule(delayMillis: Long, action: () -> Unit): RedisRequestTimeoutHandle {
        delayMillis shouldBe 10_000L
        pending += action
        return RedisRequestTimeoutHandle { pending.remove(action) }
    }
    fun fire() { pending.toList().also { pending.clear() }.forEach { it() } }
}

private fun reader(
    redis: InMemoryRedis, tasks: TestTaskScheduler, source: Boolean = false,
    capture: () -> CompletableFuture<List<CrateAcquisitionSnapshot>> = { CompletableFuture.completedFuture(emptyList()) },
    key: (String) -> ItemStack? = { null }, items: PaperItemStackBinaryCodec = mockk(),
    timeouts: ReadTimeouts = ReadTimeouts(),
) = CrateReadNetwork(redis, { source }, { capture() }, { key(it) }, items, { 10_000L }, tasks, timeouts)

private fun loadedCrate(chance: Double) = CrateAcquisitionSnapshot("case_daily", "<gold>Ежедневный тайник", true, listOf(
    CrateAcquisitionReward("ae_box", "AE box", chance * 100.0, chance, "case_daily", "ae_box"),
    CrateAcquisitionReward("native", "Native reward", (1.0 - chance) * 100.0, 1.0 - chance, "", ""),
))
