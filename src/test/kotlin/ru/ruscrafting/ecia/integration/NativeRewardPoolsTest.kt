package ru.ruscrafting.ecia.integration

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.ecia.inventory.NativeItemPayload
import su.nightexpress.excellentcrates.CratesAPI
import su.nightexpress.excellentcrates.api.crate.Reward
import su.nightexpress.excellentcrates.crate.CrateManager
import su.nightexpress.excellentcrates.crate.impl.Crate
import su.nightexpress.excellentcrates.crate.reward.impl.CommandReward
import su.nightexpress.nightcore.util.bridge.Software

class NativeRewardPoolsTest {
    @Test
    fun acceptsExcellentCratesNormalizedPlayerPlaceholder() {
        assertTrue(NativeRewardPools.isArcRewardIssue("arc-reward-issue %player_name% case_mounts blaze".split(" ").toTypedArray()))
        assertTrue(NativeRewardPools.isArcRewardIssue("arc-reward-issue %player% case_mounts blaze".split(" ").toTypedArray()))
        assertFalse(NativeRewardPools.isArcRewardIssue("arc-reward-issue player case_mounts blaze".split(" ").toTypedArray()))
    }

    @Test
    fun newlyLoadedPoolsReflectAddedRemovedRewardsAndEditedWeightsWithoutPreparingProviders() {
        MockBukkitTestRuntime.open().use {
            mockkStatic(CratesAPI::class, Software::class)
            try {
                // EC initializes native item defaults while loading Config; its text bridge is unrelated to pool selection.
                every { Software.get() } returns mockk(relaxed = true)
                val crate = mockk<Crate>(relaxed = true)
                val manager = mockk<CrateManager>()
                every { CratesAPI.getCrateManager() } returns manager
                every { manager.getCrateById("case_daily") } returns crate
                every { crate.id } returns "case_daily"
                val keys = mockk<NativeSeasonKeys>()
                every { keys.cost(crate) } returns NativeSeasonKeys.KeyCost("daily", 1)
                fun reward(id: String, weight: Double): Reward = mockk<CommandReward>(relaxed = true).also { reward ->
                    every { reward.id } returns id
                    every { reward.weight } returns weight
                    every { reward.commands } returns listOf("arc-reward-issue %player_name% case_daily $id")
                    every { reward.previewItem } returns ItemStack(Material.PAPER)
                }
                var currentRewards = setOf(reward("removed", 30.0), reward("kept", 70.0))
                every { crate.rewards } answers { currentRewards }
                val payload = NativeItemPayload()
                val issues = mutableListOf<String>()
                val pools = NativeRewardPools(keys, CatalogRewardBridge(payload), payload, issues::add)
                val settings = ManagedCratesSettings.CaseSettings("case_daily", 1, 0, 1, "")

                val before = pools.loadCurrent(settings)!!
                currentRewards = setOf(reward("kept", 40.0), reward("added", 60.0))
                val after = pools.loadCurrent(settings)!!

                assertEquals(listOf("kept", "removed"), before.rewards().map { it.id() })
                assertEquals(listOf("added", "kept"), after.rewards().map { it.id() })
                assertEquals(listOf(60.0, 40.0), after.rewards().map { it.weight() })
                assertTrue(issues.isEmpty())
                assertTrue(after.rewards().all {
                    payload.read(it.deliveryPayload(), CatalogRewardBridge.Recipe::class.java).provider() ==
                        CatalogRewardBridge.Provider.ARC_CURRENT
                })
            } finally {
                unmockkStatic(CratesAPI::class, Software::class)
            }
        }
    }
}
