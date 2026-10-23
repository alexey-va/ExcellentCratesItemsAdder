package ru.ruscrafting.ecia.integration

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import io.mockk.verifyOrder
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Server
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.data.BlockData
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Display
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.event.world.EntitiesLoadEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.scheduler.BukkitScheduler
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import org.junit.jupiter.api.Test
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.display.PacketBlockDisplay
import ru.arc.paper.display.PacketItemDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.ruscrafting.ecia.ArcExcellentCratesPlugin
import ru.ruscrafting.ecia.runtime.EciaRuntime
import su.nightexpress.excellentcrates.CratesAPI
import su.nightexpress.excellentcrates.crate.CrateManager
import su.nightexpress.excellentcrates.crate.impl.Crate
import su.nightexpress.excellentcrates.util.pos.WorldPos
import java.util.Optional
import java.util.UUID
import java.util.logging.Logger

class KeyCrateGlowPacketDisplaysTest {
    @Test
    fun blockAndFurnitureMarkersStayPrivateAndStableUntilTheKeyIsLost() {
        val scheduler = TestTaskScheduler()
        val scope = LifecycleTaskScope(scheduler)
        val queue = BudgetedTaskQueue(scope, nanoTime = { 0 })
        val plugin = mockk<ArcExcellentCratesPlugin>(relaxed = true)
        val runtime = mockk<EciaRuntime>()
        val server = mockk<Server>(relaxed = true)
        val bukkitScheduler = mockk<BukkitScheduler>()
        every { plugin.name } returns "ArcExcellentCrates"
        every { plugin.server } returns server
        every { plugin.config } returns YamlConfiguration()
        every { plugin.runtime() } returns runtime
        every { plugin.logger } returns Logger.getLogger(javaClass.name)
        every { runtime.nativeWork() } returns queue
        every { server.worlds } returns emptyList()
        every { server.scheduler } returns bukkitScheduler
        lateinit var refresh: Runnable
        every { bukkitScheduler.runTaskTimer(plugin, any<Runnable>(), 5L, 5L) } answers {
            refresh = arg(1)
            mockk(relaxed = true)
        }

        val world = mockk<World>()
        every { world.name } returns "world"
        every { world.isChunkLoaded(0, 0) } returns true
        every { server.getWorld("world") } returns world
        val viewer = mockk<Player>(relaxed = true)
        val observer = mockk<Player>(relaxed = true)
        val viewerId = UUID.randomUUID()
        val observerId = UUID.randomUUID()
        listOf(viewer to viewerId, observer to observerId).forEach { (player, id) ->
            every { player.uniqueId } returns id
            every { player.isOnline } returns true
            every { player.world } returns world
            every { player.location } returns Location(world, 8.0, 65.0, 10.0)
            every { server.getPlayer(id) } returns player
        }
        every { server.onlinePlayers } returns listOf(viewer, observer)
        val keys = mockk<NativeSeasonKeys>()
        var holdingKey = true
        every { keys.identifyForPlayer(any(), any(), any(), any()) } answers {
            if (holdingKey && secondArg<UUID>() == viewerId) {
                Optional.of(NativeSeasonKeys.KeyIdentity("test_key", "launch", "test"))
            } else Optional.empty()
        }
        val crate = mockk<Crate>()
        every { crate.id } returns "test"
        every { keys.cost(crate) } returns NativeSeasonKeys.KeyCost("test_key", 1)
        val positions = listOf(8, 12).map { x ->
            mockk<WorldPos>().also { position ->
                every { position.worldName } returns "world"
                every { position.x } returns x
                every { position.y } returns 64
                every { position.z } returns 8
            }
        }
        every { crate.blockPositions } returns positions.toSet()
        val crates = mockk<CrateManager>()
        every { crates.getCrateById("test") } returns crate

        val furniture = mockk<ItemsAdderFurnitureAccess>()
        val furnitureBlock = mockk<Block>()
        val block = mockk<Block>()
        val source = mockk<ItemDisplay>()
        val item = mockk<ItemStack>()
        every { item.clone() } returns item
        val furnitureLocation = Location(world, 8.5, 64.0, 8.5, 30f, 0f)
        val transform = Transformation(Vector3f(.25f, -.5f, .75f), Quaternionf().rotateY(.4f),
            Vector3f(.8f, 1.2f, 1.6f), Quaternionf().rotateX(-.2f))
        every { source.location } returns furnitureLocation
        every { source.itemStack } returns item
        every { source.itemDisplayTransform } returns ItemDisplay.ItemDisplayTransform.FIXED
        every { source.billboard } returns Display.Billboard.FIXED
        every { source.transformation } returns transform
        every { source.displayWidth } returns 1.8f
        every { source.displayHeight } returns 2.1f
        every { world.getBlockAt(8, 64, 8) } returns furnitureBlock
        every { furniture.at(furnitureBlock) } returns Optional.of(ItemsAdderFurnitureAccess.Instance("test:crate", source))
        every { world.getBlockAt(12, 64, 8) } returns block
        every { furniture.at(block) } returns Optional.empty()
        every { block.type } returns Material.ENDER_CHEST
        val blockData = mockk<BlockData>()
        every { blockData.clone() } returns blockData
        every { block.blockData } returns blockData
        val blockLocation = Location(world, 12.0, 64.0, 8.0)
        every { block.location } returns blockLocation
        val itemHandles = List(2) { mockk<PacketItemDisplay>(relaxed = true).also { every { it.isValid } returns true } }
        val blockHandles = List(2) { mockk<PacketBlockDisplay>(relaxed = true).also { every { it.isValid } returns true } }
        val packetDisplays = mockk<PaperPacketDisplays>()
        every { packetDisplays.spawnItem(furnitureLocation, item) } returnsMany itemHandles
        every { packetDisplays.spawnBlock(blockLocation, blockData) } returnsMany blockHandles

        mockkStatic(CratesAPI::class)
        every { CratesAPI.isLoaded() } returns true
        every { CratesAPI.getCrateManager() } returns crates
        try {
            val service = KeyCrateGlowService(plugin, keys, furniture, packetDisplays)
            try {
                service.configure(mapOf("test" to ManagedCratesSettings.CaseSettings("test", 3, 1, 1, "test:crate")))
                scheduler.tick(2)
                refresh.run()
                scheduler.tick(2)
                verify(exactly = 1) { packetDisplays.spawnItem(furnitureLocation, item); packetDisplays.spawnBlock(blockLocation, blockData) }
                listOf(itemHandles[0], blockHandles[0]).forEach { handle ->
                    verify(exactly = 1) { handle.isVisibleByDefault = false; handle.showTo(viewer) }
                    verifyOrder { handle.isVisibleByDefault = false; handle.showTo(viewer) }
                    verify(exactly = 0) { handle.showTo(observer); handle.remove() }
                    verify { handle.isGlowing = true; handle.glowColorOverride = Color.fromRGB(255, 213, 103) }
                    verify { handle.brightness = Display.Brightness(15, 15); handle.viewRange = 48f }
                    verify { handle.interpolationDuration = 0; handle.teleportDuration = 0 }
                }
                verify { itemHandles[0].transformation = KeyCrateGlowService.inflateCentered(transform) }
                verify { itemHandles[0].itemDisplayTransform = ItemDisplay.ItemDisplayTransform.FIXED }
                verify { itemHandles[0].displayWidth = 1.8f; itemHandles[0].displayHeight = 2.1f }
                verify { blockHandles[0].transformation = KeyCrateGlowService.blockShellTransformation() }
                holdingKey = false
                refresh.run()
                scheduler.tick(2)
                verify(exactly = 1) { itemHandles[0].remove(); blockHandles[0].remove() }
                holdingKey = true
                refresh.run()
                scheduler.tick(2)
                verify(exactly = 2) { packetDisplays.spawnItem(furnitureLocation, item); packetDisplays.spawnBlock(blockLocation, blockData) }
                verify(exactly = 1) { itemHandles[1].showTo(viewer); blockHandles[1].showTo(viewer) }

                val marker = NamespacedKey(plugin, "key_crate_glow")
                val oldItem = mockk<ItemDisplay>(relaxed = true)
                val oldBlock = mockk<BlockDisplay>(relaxed = true)
                listOf(oldItem, oldBlock).forEach { every { it.persistentDataContainer.has(marker, PersistentDataType.BYTE) } returns true }
                val unrelated = mockk<TextDisplay>(relaxed = true)
                service.onEntitiesLoad(EntitiesLoadEvent(mockk(relaxed = true), listOf(oldItem, oldBlock, unrelated)))
                scheduler.tick()
                verify(exactly = 1) { oldItem.remove(); oldBlock.remove() }
                verify(exactly = 0) { unrelated.remove() }
            } finally { service.close(); service.close() }
            (itemHandles + blockHandles).forEach { verify(exactly = 1) { it.remove() } }
            verify(exactly = 0) { packetDisplays.close(); packetDisplays.closeAsync() }
        } finally {
            queue.close()
            scope.close()
            unmockkStatic(CratesAPI::class)
        }
    }
}
