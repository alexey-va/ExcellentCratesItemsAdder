package ru.ruscrafting.ecia.integration

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.verify
import org.bukkit.entity.Player
import org.bukkit.permissions.PermissionAttachmentInfo
import org.bukkit.plugin.ServicesManager
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.arc.core.BoundedAsyncExecutor
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.api.ArcAfkProvider
import ru.ruscrafting.ecia.ArcExcellentCratesPlugin
import ru.ruscrafting.ecia.runtime.EciaRuntime
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.random.Random

class PeriodicKeysServiceTest {
    @Test
    fun `settler donor staff and wildcard do not qualify without a normal rank`() {
        val player = player()
        every { player.isOp } returns true
        setPermissions(player, "group.default", "group.vip", "group.admin", "*", "group.rank_*")
        assertFalse(hasPeriodicKeyRank(player))

        every { player.effectivePermissions } returns setOf(
            PermissionAttachmentInfo(player, "group.rank_peasant", null, false),
        )
        assertFalse(hasPeriodicKeyRank(player))

        for (rank in listOf("peasant", "citizen", "artisan", "knight", "baron", "count", "prince", "caesar")) {
            setPermissions(player, "group.default", "group.admin", "group.rank_$rank")
            assertTrue(hasPeriodicKeyRank(player), rank)
        }
    }

    @Test
    fun `waiting pauses during AFK and the next poll checks the player's current rank`() {
        Fixture().use { fixture ->
            val player = player()
            setPermissions(player, "group.default")
            fixture.service.playerDataLoaded(player)
            fixture.play(260)
            fixture.afk = true
            fixture.play(180)
            fixture.afk = false
            setPermissions(player, "group.rank_peasant")
            fixture.play(8)
            verify(exactly = 0) { fixture.access.acquire(any()) }

            setPermissions(player, "group.default")
            fixture.play(90)
            verify(exactly = 0) { fixture.access.acquire(any()) }

            setPermissions(player, "group.rank_citizen")
            fixture.play(6)
            verify(atLeast = 1) { fixture.access.acquire(player) }
        }
    }

    @Test
    fun `a quit and rejoin starts a new wait and stale departure cannot clear it`() = mockkObject(Random.Default) {
        every { Random.nextLong(any(), any()) } answers { firstArg() }
        Fixture().use { fixture ->
            val id = UUID.randomUUID()
            val original = player(id)
            setPermissions(original, "group.rank_peasant")
            fixture.service.playerDataLoaded(original)
            fixture.play(299)
            verify(exactly = 0) { fixture.access.acquire(any()) }

            fixture.service.playerLeft(original)
            fixture.play(20)
            val returned = player(id)
            setPermissions(returned, "group.rank_peasant")
            fixture.service.playerDataLoaded(returned)
            fixture.service.playerLeft(original)
            fixture.play(299)
            verify(exactly = 0) { fixture.access.acquire(any()) }

            fixture.service.playerDataLoaded(returned)
            fixture.play(6)
            verify(exactly = 0) { fixture.access.acquire(original) }
            verify(exactly = 1) { fixture.issuer.playerLeft(id) }
            verify(atLeast = 1) { fixture.access.acquire(returned) }
        }
        verify(exactly = 2) { Random.nextLong(300L, 331L) }
    }

    private fun player(id: UUID = UUID.randomUUID()) = mockk<Player> {
        every { uniqueId } returns id
        every { isOnline } returns true
        every { isDead } returns false
        every { idleDuration } returns Duration.ZERO
        every { hasPermission("ecia.use") } returns true
    }

    private fun setPermissions(player: Player, vararg permissions: String) {
        every { player.effectivePermissions } returns permissions.mapTo(mutableSetOf()) {
            PermissionAttachmentInfo(player, it, null, true)
        }
    }

    private class Fixture : AutoCloseable {
        val access = mockk<PeriodicKeysService.PlayerAccess>()
        val issuer = mockk<PeriodicKeyIssuer>(relaxed = true)
        var afk = false
        private val scheduler = TestTaskScheduler()
        private val scope = LifecycleTaskScope(scheduler)
        private val queue = BudgetedTaskQueue(scope, nanoTime = { 0L })
        private var timeNanos = 0L
        val service: PeriodicKeysService

        init {
            every { access.acquire(any()) } returns null
            val services = mockk<ServicesManager>()
            every { services.load(ArcAfkProvider::class.java) } returns ArcAfkProvider { afk }
            val settingsStorage = mockk<BoundedAsyncExecutor>()
            every { settingsStorage.submit<List<ManagedCratesSettings.CaseSettings>>(any()) } answers {
                CompletableFuture.completedFuture(firstArg<() -> List<ManagedCratesSettings.CaseSettings>>()())
            }
            val runtime = mockk<EciaRuntime>()
            every { runtime.tasks() } returns scope
            every { runtime.nativeWork() } returns queue
            every { runtime.storage() } returns settingsStorage
            val plugin = mockk<ArcExcellentCratesPlugin>()
            every { plugin.runtime() } returns runtime
            every { plugin.server } returns mockk { every { servicesManager } returns services }
            service = PeriodicKeysService(plugin, mockk(), issuer, mockk(), Runnable::run, access, emptySet(),
                nanoTime = { timeNanos })
            val daily = ManagedCratesSettings.CaseSettings("case_daily", 1, 0, 1, "", PeriodicVirtualOpening.Period.DAILY)
            service.configure(ManagedCratesSettings(true, mapOf(daily.crateId() to daily)), setOf(daily.crateId()))
        }

        fun play(seconds: Int) {
            repeat(seconds) {
                timeNanos += SECONDS.toNanos(1)
                scheduler.tick(20L)
            }
        }

        override fun close() {
            service.close()
            queue.close()
            scope.close()
        }
    }
}
