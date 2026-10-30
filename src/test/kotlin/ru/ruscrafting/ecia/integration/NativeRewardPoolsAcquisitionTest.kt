package ru.ruscrafting.ecia.integration

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkStatic
import io.mockk.verify
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.ecia.inventory.NativeItemPayload
import su.nightexpress.excellentcrates.CratesAPI
import su.nightexpress.excellentcrates.crate.CrateManager
import su.nightexpress.excellentcrates.crate.impl.Crate
import su.nightexpress.excellentcrates.crate.reward.impl.CommandReward
import su.nightexpress.nightcore.util.bridge.Software
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor

class NativeRewardPoolsAcquisitionTest {
    @Test
    fun acquisitionUsesCurrentEligibleWeightsWithoutRollingOrPreparingRewardsAndRejectsNativeReload() {
        MockBukkitTestRuntime.open().use {
            mockkStatic(CratesAPI::class, Software::class)
            val scheduler = TestTaskScheduler()
            val scope = LifecycleTaskScope(scheduler)
            val work = BudgetedTaskQueue(scope, nanoTime = { 0L })
            try {
                every { Software.get() } returns mockk(relaxed = true)
                val crate = mockk<Crate>(relaxed = true)
                every { crate.id } returns "case_daily"
                every { crate.name } returns "<gold>Daily crate</gold>"
                var loadedCrate: Crate? = crate
                val manager = mockk<CrateManager>()
                every { CratesAPI.getCrateManager() } returns manager
                every { manager.getCrateById("case_daily") } answers { loadedCrate }
                val keys = mockk<NativeSeasonKeys>()
                every { keys.cost(crate) } returns NativeSeasonKeys.KeyCost("daily", 1)
                fun reward(id: String, weight: Double) = mockk<CommandReward>(relaxed = true).also { reward ->
                    every { reward.id } returns id
                    every { reward.name } returns "<yellow>$id</yellow>"
                    every { reward.weight } returns weight
                    every { reward.commands } returns listOf("arc-reward-issue %player_name% case_daily $id")
                    every { reward.previewItem } returns ItemStack(Material.PAPER)
                }
                val first = reward("ae_box", 30.0)
                val second = reward("sf_box", 70.0)
                val excluded = reward("permission_reward", 900.0)
                every { excluded.requiredPermissions } returns setOf("restricted.reward")
                var nativeRewards = setOf(first, second, excluded)
                every { crate.rewards } answers { nativeRewards }
                val payload = NativeItemPayload()
                val bridge = spyk(CatalogRewardBridge(payload))
                val issues = mutableListOf<String>()
                val pools = NativeRewardPools(keys, bridge, payload, issues::add)
                val settings = ManagedCratesSettings.CaseSettings("case_daily", 1, 0, 1, "")
                val worker = Executor { it.run() }

                val pending = pools.loadCurrentWithAcquisitionAsync(settings, work, worker)
                assertFalse(pending.isDone, "Native capture must yield through the shared tick budget")
                scheduler.tick()
                val loaded = pending.join()!!
                val snapshot = loaded.acquisition()
                assertTrue(snapshot.available())
                assertEquals("<gold>Daily crate</gold>", snapshot.displayName())
                assertEquals(loaded.pool().rewards().map { it.id() }, snapshot.rewards().map { it.rewardId() })
                assertEquals(listOf("ae_box", "sf_box"), snapshot.rewards().map { it.rewardId() })
                assertEquals(listOf(30.0, 70.0), snapshot.rewards().map { it.weight() })
                assertEquals(0.3, snapshot.rewards()[0].probability(), 1e-12)
                assertEquals(0.7, snapshot.rewards()[1].probability(), 1e-12)
                assertEquals("<yellow>ae_box</yellow>", snapshot.rewards()[0].displayName())
                assertEquals("case_daily", snapshot.rewards()[0].catalogCategoryId())
                assertEquals("ae_box", snapshot.rewards()[0].catalogEntryId())
                assertEquals(1, issues.size)
                assertTrue(issues.single().contains("unsupported reward rules"))

                nativeRewards = setOf(reward("added", Double.MAX_VALUE), reward("kept", Double.MAX_VALUE / 3.0))
                val reloaded = pools.loadCurrentWithAcquisitionAsync(settings, work, worker)
                scheduler.tick()
                val updated = reloaded.join()!!.acquisition()
                assertEquals(listOf("added", "kept"), updated.rewards().map { it.rewardId() })
                assertEquals(0.75, updated.rewards()[0].probability(), 1e-12)
                assertEquals(0.25, updated.rewards()[1].probability(), 1e-12)

                val invalidated = pools.loadCurrentWithAcquisitionAsync(settings, work, worker)
                loadedCrate = null
                scheduler.tick()
                val failure = assertThrows(CompletionException::class.java) { invalidated.join() }
                assertInstanceOf(CancellationException::class.java, failure.cause)
                verify(exactly = 0) { first.rollChance; second.rollChance; first.give(any()); second.give(any()) }
                verify(exactly = 0) { bridge.materialize(any()) }
                verify(exactly = 0) { keys.create(any<String>(), any<Int>()) }
            } finally {
                work.close()
                scope.close()
                unmockkStatic(CratesAPI::class, Software::class)
            }
        }
    }
}
