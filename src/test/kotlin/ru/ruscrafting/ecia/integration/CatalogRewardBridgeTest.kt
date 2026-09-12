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
            val reward = RewardDefinition("old_reward", 1.0, false, payload.write(recipe), "")
            assertEquals(Material.PAPER, bridge.materialize(reward)!!.single().type)
            assertEquals(1, materialized)
            assertThrows(IllegalStateException::class.java) { bridge.freeze("old_case", "old_reward", "source") }
        }
    }
}
