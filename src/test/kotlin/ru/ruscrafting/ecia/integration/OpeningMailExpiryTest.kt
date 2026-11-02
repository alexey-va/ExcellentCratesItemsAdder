package ru.ruscrafting.ecia.integration

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.arc.redis.InMemoryRedis
import ru.arc.redis.RedisOperations
import ru.arc.redis.ServerIdentity
import ru.arc.redis.activity.PlayerActivityStore
import ru.ruscrafting.ecia.journal.OpeningLedger
import ru.ruscrafting.ecia.journal.OpeningRecord
import ru.ruscrafting.ecia.journal.OpeningStore
import ru.ruscrafting.ecia.roll.PoolSnapshot
import ru.ruscrafting.ecia.roll.RewardDefinition
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class OpeningMailExpiryTest {
    @Test fun `actual activity hash and verified empty proxy roster expire only old mail`() {
        Fixture().use { fixture ->
            fixture.activity(fixture.created)
            fixture.roster("[]")
            assertEquals(1, fixture.sweep())
            assertEquals(OpeningRecord.Stage.EXPIRED, fixture.ledger.get(fixture.mail.id(), PLAYER).stage())
            assertEquals(PlayerActivityStore.REDIS_KEY, fixture.redis.loadOperations.single().key)
            assertEquals(0, fixture.ledger.pendingCount())
            assertFalse(fixture.ledger.canSpendPeriodicKey(fixture.mail.id()))
        }
    }

    @Test fun `missing stale foreign or invalid rosters never establish absence`() {
        Fixture().use { fixture ->
            fixture.activity(fixture.created)
            assertEquals(0, fixture.sweep())
            fixture.roster("[]", "velocity")
            assertEquals(0, fixture.sweep())
            fixture.roster("[]")
            fixture.now.addAndGet(OpeningMailExpiry.ROSTER_FRESH_MILLIS + 1)
            assertEquals(0, fixture.sweep())
            fixture.now.set(fixture.readyAt)
            for (invalid in listOf("{}", "[{}]", "null", "[] trailing", "[${player()},${player()}]",
                """[{"username":"name","server":"survival","uuid":"1-1-1-1-1","joinTime":1}]""")) {
                fixture.roster("[]")
                fixture.roster(invalid)
                assertEquals(0, fixture.sweep(), invalid)
            }
            assertEquals(OpeningRecord.Stage.MAIL, fixture.ledger.get(fixture.mail.id(), PLAYER).stage())
            assertTrue(fixture.redis.loadOperations.isEmpty())
        }
    }

    @Test fun `player online on survival is excluded even with an old activity timestamp`() {
        Fixture().use { fixture ->
            fixture.activity(fixture.created)
            fixture.roster("[${player()}]")
            assertEquals(0, fixture.sweep())
            assertEquals(OpeningRecord.Stage.MAIL, fixture.ledger.get(fixture.mail.id(), PLAYER).stage())
        }
    }

    @Test fun `missing malformed future and recent activity preserve rewards`() {
        for (lastSeen in listOf<String?>(null, "broken", "0", "-1", (READY + 1).toString(), (READY - 1).toString())) {
            Fixture().use { fixture ->
                fixture.redis.setHash(PlayerActivityStore.REDIS_KEY, buildMap {
                    put("_coverage_started_at", "1")
                    if (lastSeen != null) put(PLAYER.toString(), lastSeen)
                })
                fixture.roster("[]")
                assertEquals(0, fixture.sweep(), lastSeen)
                assertEquals(OpeningRecord.Stage.MAIL, fixture.ledger.get(fixture.mail.id(), PLAYER).stage())
            }
        }
    }

    @Test fun `roster and revision are revalidated after the asynchronous activity read`() {
        for (change in listOf("online", "stale", "revision", "delivering", "review", "close")) {
            val deferred = CompletableFuture<Map<String, String>>()
            Fixture(deferred).use { fixture ->
                fixture.roster("[]")
                val sweep = fixture.expiry.sweep()
                assertFalse(sweep.isDone())
                when (change) {
                    "online" -> fixture.roster("[${player()}]")
                    "stale" -> fixture.now.addAndGet(OpeningMailExpiry.ROSTER_FRESH_MILLIS + 1)
                    "close" -> fixture.expiry.close()
                    else -> {
                        val prepared = fixture.ledger.prepared(fixture.mail.id(), PLAYER, fixture.mail.revision(), "native-prize")
                        if (change != "revision") {
                            val delivering = fixture.ledger.deliveryStarted(prepared.id(), PLAYER, prepared.revision(), "native-witness")
                            if (change == "review") fixture.ledger.review(delivering.id(), PLAYER, delivering.revision(), "unknown-save")
                        }
                    }
                }
                deferred.complete(mapOf(PLAYER.toString() to fixture.created.toString()))
                assertEquals(0, sweep.get(5, TimeUnit.SECONDS), change)
                assertNotEquals(OpeningRecord.Stage.EXPIRED, fixture.ledger.get(fixture.mail.id(), PLAYER).stage())
            }
        }
    }

    @Test fun `activity read failure retains mail and a later healthy sweep can retry`() {
        Fixture().use { fixture ->
            fixture.activity(fixture.created)
            fixture.roster("[]")
            fixture.redis.failOnLoad = true
            assertThrows(CompletionException::class.java) { fixture.expiry.sweep().join() }
            assertEquals(OpeningRecord.Stage.MAIL, fixture.ledger.get(fixture.mail.id(), PLAYER).stage())
            assertTrue(fixture.ledger.available())
            fixture.redis.failOnLoad = false
            assertEquals(1, fixture.sweep())
            assertTrue("mail-expiry-failed" in fixture.reports)
            assertTrue("mail-expiry-recovered" in fixture.reports)
        }
    }

    @Test fun `an old activity result is rejected even after a new empty roster arrives`() {
        for (elapsed in listOf(OpeningMailExpiry.ROSTER_FRESH_MILLIS + 1, -1L)) {
            val deferred = CompletableFuture<Map<String, String>>()
            Fixture(deferred).use { fixture ->
                fixture.roster("[]")
                val sweep = fixture.expiry.sweep()
                fixture.now.addAndGet(elapsed)
                fixture.roster("[]")
                deferred.complete(mapOf(PLAYER.toString() to fixture.created.toString()))
                assertEquals(0, sweep.get(5, TimeUnit.SECONDS))
                assertEquals(OpeningRecord.Stage.MAIL, fixture.ledger.get(fixture.mail.id(), PLAYER).stage())
            }
        }
    }

    @Test fun `boot timer is owned and close removes its subscription and late writes`() {
        val activity = CompletableFuture.completedFuture(mapOf(PLAYER.toString() to (READY - OpeningLedger.MAIL_INACTIVITY_MILLIS).toString()))
        Fixture(activity).use { fixture ->
            fixture.roster("[]")
            assertEquals(1, fixture.scheduler.timerCount())
            fixture.scheduler.tick(20)
            assertEquals(OpeningRecord.Stage.EXPIRED, fixture.ledger.get(fixture.mail.id(), PLAYER).stage())
            fixture.expiry.close()
            assertEquals(0, fixture.scheduler.timerCount())
            assertEquals(0, fixture.redis.listenerCount(OpeningMailExpiry.CHANNEL))
        }
    }

    @Test fun `a boot check without a roster retries at the next minute`() {
        val activity = CompletableFuture.completedFuture(mapOf(PLAYER.toString() to (READY - OpeningLedger.MAIL_INACTIVITY_MILLIS).toString()))
        Fixture(activity).use { fixture ->
            fixture.scheduler.tick(20)
            assertEquals(OpeningRecord.Stage.MAIL, fixture.ledger.get(fixture.mail.id(), PLAYER).stage())
            fixture.roster("[]")
            fixture.scheduler.tick(1199)
            assertEquals(OpeningRecord.Stage.MAIL, fixture.ledger.get(fixture.mail.id(), PLAYER).stage())
            fixture.scheduler.tick(1)
            assertEquals(OpeningRecord.Stage.EXPIRED, fixture.ledger.get(fixture.mail.id(), PLAYER).stage())
        }
    }

    private class Fixture(deferred: CompletableFuture<Map<String, String>>? = null) : AutoCloseable {
        val readyAt = READY
        val created = READY - OpeningLedger.MAIL_INACTIVITY_MILLIS
        val now = AtomicLong(created)
        val redis = InMemoryRedis(ServerIdentity { "spawn" })
        val scheduler = TestTaskScheduler()
        val tasks = LifecycleTaskScope(scheduler, true)
        val reports = mutableListOf<String>()
        private val values = mutableMapOf<UUID, OpeningRecord>()
        private val clock = object : Clock() {
            override fun instant(): Instant = Instant.ofEpochMilli(now.get())
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = this
        }
        val ledger = OpeningLedger(object : OpeningStore {
            override fun load(): List<OpeningRecord> = values.values.toList()
            override fun commit(record: OpeningRecord): OpeningRecord = record.also { values[it.id()] = it }
        }, clock)
        val mail: OpeningRecord
        val expiry: OpeningMailExpiry
        init {
            val reward = RewardDefinition("prize", 1.0, "frozen", "preview")
            val pool = PoolSnapshot("weekly", "launch", listOf(reward), 1, 0)
            val reserved = ledger.reserveVirtual(UUID.randomUUID(), PLAYER, pool, { listOf(reward) }, "virtual:v1:window").record()
            mail = ledger.select(reserved.id(), PLAYER, reserved.revision(), reward.id())
            now.set(READY)
            val connection = if (deferred == null) redis else object : RedisOperations by redis {
                override fun loadMap(key: String): CompletableFuture<Map<String, String>> = deferred
            }
            expiry = OpeningMailExpiry(connection, ledger, Executor(Runnable::run), tasks,
                clockMillis = now::get, report = { operation, _ -> reports += operation })
        }
        fun activity(lastSeen: Long) { redis.setHash(PlayerActivityStore.REDIS_KEY, mapOf(PLAYER.toString() to lastSeen.toString())) }
        fun roster(payload: String, origin: String = "proxy") { redis.simulateExternalMessage(OpeningMailExpiry.CHANNEL, payload, origin) }
        fun sweep(): Int = expiry.sweep().get(5, TimeUnit.SECONDS)
        override fun close() { expiry.close(); tasks.close() }
    }

    companion object {
        private const val READY = 1_800_000_000_000L
        private val PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000728")
        private fun player() = """{"username":"CrateQA","server":"survival","uuid":"$PLAYER","joinTime":1}"""
    }
}
