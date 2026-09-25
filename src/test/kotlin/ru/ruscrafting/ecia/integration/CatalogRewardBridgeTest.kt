package ru.ruscrafting.ecia.integration

import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.ServicePriority
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import ru.arc.paper.api.ArcItemMaterializationReference
import ru.arc.paper.api.ArcItemMaterializationRequest
import ru.arc.paper.api.ArcItemMaterializer
import ru.arc.paper.api.ArcItemMaterializerCapabilitySnapshot
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.ecia.inventory.NativeItemPayload
import ru.ruscrafting.ecia.roll.RewardDefinition

class CatalogRewardBridgeTest {
    @Test
    fun historicalReferenceSurvivesRemovalOfCurrentCatalog() {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("ArchivedRewardProvider")
            var materialized = 0
            val provider = object : ArcItemMaterializer {
                override fun capability() = ArcItemMaterializerCapabilitySnapshot(false, null)
                override fun prepare(request: ArcItemMaterializationRequest): ArcItemMaterializationReference? = null
                override fun materialize(reference: ArcItemMaterializationReference): List<ItemStack> {
                    assertEquals("frozen:archived", (reference as ArcItemMaterializationReference.FreshVoucher).sourceKey)
                    materialized++
                    return listOf(ItemStack(Material.PAPER))
                }
            }
            paper.server.servicesManager.register(ArcItemMaterializer::class.java, provider, plugin, ServicePriority.Normal)
            val payload = NativeItemPayload()
            val bridge = CatalogRewardBridge(payload)
            val recipe = CatalogRewardBridge.Recipe(1, CatalogRewardBridge.Provider.ARC_VOUCHER,
                "old_case", "old_reward", "fingerprint", "frozen:archived", emptyList(), "source")
            val reward = RewardDefinition("old_reward", 1.0, payload.write(recipe), "")
            assertEquals(Material.PAPER, bridge.materialize(reward)!!.single().type)
            assertEquals(1, materialized)
            val current = RewardDefinition("old_reward", 1.0,
                bridge.catalogReward("old_case", "old_reward", "source"), "")
            assertNull(bridge.materialize(current))
        }
    }

    @Test
    fun catalogLoadingDoesNotPrepareOrMaterializeAnyReward() {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("StartingRewardProvider")
            val provider = object : ArcItemMaterializer {
                override fun capability(): ArcItemMaterializerCapabilitySnapshot = error("Not ready at startup")
                override fun prepare(request: ArcItemMaterializationRequest): ArcItemMaterializationReference? =
                    error("Catalog loading must not prepare prizes")
                override fun materialize(reference: ArcItemMaterializationReference): List<ItemStack>? =
                    error("Catalog loading must not create prizes")
            }
            paper.server.servicesManager.register(ArcItemMaterializer::class.java, provider, plugin, ServicePriority.Normal)
            val payload = NativeItemPayload()
            val bridge = CatalogRewardBridge(payload)

            val recipe = payload.read(bridge.catalogReward("case_weekly", "workday", "source"),
                CatalogRewardBridge.Recipe::class.java)

            assertEquals(CatalogRewardBridge.Provider.ARC_CURRENT, recipe.provider())
            assertEquals("case_weekly", recipe.categoryId())
            assertEquals("workday", recipe.entryId())
            assertTrue(recipe.nativeItems().isEmpty())
            assertEquals("", recipe.sourceKey())
            assertEquals("", recipe.providerFingerprint())
        }
    }

    @Test
    fun currentRewardResolvesTheLiveCatalogAtClaimAndRetriesAfterProviderReturns() {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("CurrentRewardProvider")
            var available = false
            var current: Material? = Material.IRON_INGOT
            var prepared = 0
            var delivered = 0
            val provider = object : ArcItemMaterializer {
                override fun capability() = ArcItemMaterializerCapabilitySnapshot(available, if (available) "current" else null)
                override fun prepare(request: ArcItemMaterializationRequest): ArcItemMaterializationReference? {
                    prepared++
                    assertEquals("case_daily", request.categoryId)
                    assertEquals("prize", request.entryId)
                    return current?.let { ArcItemMaterializationReference.FrozenItems(request, "current", listOf(ItemStack(it))) }
                }
                override fun materialize(reference: ArcItemMaterializationReference): List<ItemStack> {
                    delivered++
                    return (reference as ArcItemMaterializationReference.FrozenItems).templates.map(ItemStack::clone)
                }
            }
            paper.server.servicesManager.register(ArcItemMaterializer::class.java, provider, plugin, ServicePriority.Normal)
            val bridge = CatalogRewardBridge(NativeItemPayload())
            val reward = RewardDefinition("prize", 1.0, bridge.catalogReward("case_daily", "prize", "source"), "")
            assertNull(bridge.materialize(reward))
            assertEquals(0, prepared)

            available = true
            current = Material.DIAMOND
            assertEquals(Material.DIAMOND, bridge.materialize(reward)!!.single().type)
            current = null
            assertNull(bridge.materialize(reward))
            current = Material.EMERALD
            assertEquals(Material.EMERALD, bridge.materialize(reward)!!.single().type)
            assertEquals(3, prepared)
            assertEquals(2, delivered)
        }
    }
}
