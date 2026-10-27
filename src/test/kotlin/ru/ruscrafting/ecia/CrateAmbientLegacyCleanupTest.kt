package ru.ruscrafting.ecia

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.bukkit.Location
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Chunk
import org.bukkit.Server
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Entity
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.TextDisplay
import org.bukkit.event.world.EntitiesLoadEvent
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.persistence.PersistentDataType
import org.bukkit.inventory.ItemStack
import org.bukkit.scheduler.BukkitScheduler
import org.bukkit.util.Transformation
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.display.PacketItemDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.ruscrafting.ecia.runtime.EciaRuntime
import su.nightexpress.excellentcrates.CratesAPI
import su.nightexpress.excellentcrates.api.crate.Reward
import su.nightexpress.excellentcrates.crate.CrateManager
import su.nightexpress.excellentcrates.crate.impl.Crate
import su.nightexpress.excellentcrates.util.pos.WorldPos
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.joml.Quaternionf
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture

class CrateAmbientLegacyCleanupTest {
    @Test
    fun consecutiveAnimationTicksAuthorCompleteFramesWhileNativeWorkIsPending(@TempDir directory: Path) {
        val scheduler = TestTaskScheduler()
        val scope = LifecycleTaskScope(scheduler)
        val queue = BudgetedTaskQueue(scope, nanoTime = { 0 })
        val plugin = mockk<ArcExcellentCratesPlugin>(relaxed = true)
        val runtime = mockk<EciaRuntime>(relaxed = true)
        val server = mockk<Server>(relaxed = true)
        val bukkitScheduler = mockk<BukkitScheduler>()
        val config = YamlConfiguration()
        every { plugin.name } returns "ArcExcellentCrates"
        every { plugin.server } returns server
        every { plugin.config } returns config
        every { plugin.runtime() } returns runtime
        every { runtime.nativeWork() } returns queue
        every { runtime.submitNativeCleanup(any(), any()) } returns CompletableFuture.completedFuture(null)
        every { server.worlds } returns emptyList()
        every { server.scheduler } returns bukkitScheduler
        lateinit var animationTick: Runnable
        every { bukkitScheduler.runTaskTimer(plugin, any<Runnable>(), any<Long>(), any<Long>()) } answers {
            if (arg<Long>(3) == 1L) {
                assertEquals(1L, arg<Long>(2))
                animationTick = arg(1)
            }
            mockk(relaxed = true)
        }

        val world = mockk<World>(relaxed = true)
        every { world.uid } returns UUID.randomUUID()
        every { world.name } returns "world"
        every { world.isChunkLoaded(0, 0) } returns true
        val anchor = Location(world, 8.0, 64.0, 8.0)
        val position = mockk<WorldPos>(relaxed = true)
        every { position.world } returns world
        every { position.worldName } returns world.name
        every { position.x } returns 8
        every { position.y } returns 64
        every { position.z } returns 8
        every { position.isChunkLoaded } returns true
        every { position.toLocation() } answers { anchor.clone() }
        val block = mockk<Block>()
        every { block.type } returns Material.ENDER_CHEST
        every { position.toBlock() } returns block
        val item = mockk<ItemStack>(relaxed = true)
        every { item.clone() } returns item
        every { item.isEmpty } returns false
        every { item.type } returns Material.PAPER
        every { item.amount } returns 1
        every { item.itemMeta } returns null
        val reward = mockk<Reward>()
        every { reward.previewItem } returns item
        val crate = mockk<Crate>(relaxed = true)
        every { crate.id } returns "test"
        every { crate.blockPositions } returns setOf(position)
        every { crate.rewards } returns setOf(reward)
        val manager = mockk<CrateManager>()
        every { manager.crates } returns setOf(crate)
        val positions = List(8) { mutableListOf<Location>() }
        val transformations = List(8) { mutableListOf<Transformation>() }
        val displays = List(8) { slot ->
            mockk<PacketItemDisplay>(relaxed = true).also { display ->
                every { display.isValid } returns true
                every { display.isVisibleByDefault } returns true
                every { display.audienceGroup } returns null
                every { display.billboard } returns org.bukkit.entity.Display.Billboard.CENTER
                every { display.teleport(any()) } answers { positions[slot].add(firstArg<Location>().clone()); Unit }
                every { display.transformation = any() } answers { transformations[slot].add(firstArg()); Unit }
            }
        }
        val packetDisplays = mockk<PaperPacketDisplays>()
        every { packetDisplays.spawnItem(any(), any()) } returnsMany displays
        val settings = CrateVisualSettingsStore(directory, config)
        mockkStatic(CratesAPI::class)
        mockkStatic(Bukkit::class)
        every { Bukkit.isPrimaryThread() } returns true
        every { CratesAPI.isLoaded() } returns true
        every { CratesAPI.getCrateManager() } returns manager
        try {
            val service = CrateAmbientEffectService(plugin, settings, packetDisplays)
            try {
                scheduler.tick()
                verify(exactly = 8) { packetDisplays.spawnItem(any(), any()) }
                transformations.forEach { it.clear() }
                val unrelatedWork = queue.submit({ true }) { false }

                repeat(2) { tick ->
                    animationTick.run()
                    assertFalse(unrelatedWork.isDone)
                    displays.indices.forEach { slot ->
                        val expected = CrateAmbientEffectService.frame((tick + 1) * .5, slot, settings.defaults().ambient(), 1)
                        assertEquals(tick + 1, positions[slot].size)
                        assertEquals(tick + 1, transformations[slot].size)
                        val actual = positions[slot].last()
                        assertEquals(8.5 + expected.x(), actual.x, 1.0e-9)
                        assertEquals(64.45 + expected.y(), actual.y, 1.0e-9)
                        assertEquals(8.5 + expected.z(), actual.z, 1.0e-9)
                        assertEquals(Quaternionf().rotateZ(expected.rotation()), transformations[slot].last().leftRotation)
                    }
                }
                verify(exactly = 2) { world.isChunkLoaded(0, 0) }
                displays.forEach { display ->
                    verify(exactly = 1) { display.teleportDuration = 2 }
                    verify(exactly = 1) { display.interpolationDuration = 2 }
                }
                val bounds = ru.arc.paper.api.ArcSceneBounds(world.uid, 0.0, 0.0, 0.0, 16.0, 100.0, 16.0)
                val captured = service.displays(bounds, 128)
                assertEquals(8, captured.size)
                assertEquals(21, captured.first().timeline!!.samples.size)
                assertEquals(100, captured.first().timeline!!.samplePeriodMillis)
                assertEquals(1, service.displays(bounds, 1).size)
                every { displays.first().isVisibleByDefault } returns false
                assertEquals(7, service.displays(bounds, 128).size, "default-hidden private handles never export")
                service.setOpening(anchor, true)
                assertEquals(emptyList<ru.arc.paper.api.ArcSceneDisplay>(), service.displays(bounds, 128))
                service.setOpening(anchor, false)
                assertEquals(7, service.displays(bounds, 128).size)
                assertEquals(emptyList<ru.arc.paper.api.ArcSceneDisplay>(), service.displays(
                    ru.arc.paper.api.ArcSceneBounds(UUID(0, 2), 0.0, 0.0, 0.0, 16.0, 100.0, 16.0), 128))
                service.close()
                assertEquals(emptyList<ru.arc.paper.api.ArcSceneDisplay>(), service.displays(bounds, 128))
            } finally { service.close() }
        } finally {
            queue.close()
            scope.close()
            settings.close()
            unmockkStatic(CratesAPI::class)
            unmockkStatic(Bukkit::class)
        }
    }

    @Test
    fun lateEntityLoadRemovesOnlyLegacyAmbientDisplays() {
        val marker = NamespacedKey("arc-excellent-crates", "case_ambient")
        val ambientItem = taggedDisplay<ItemDisplay>(marker, true)
        val hologram = taggedDisplay<TextDisplay>(NamespacedKey("arc-excellent-crates", "case_hologram"), true)
        val ambientBlock = taggedDisplay<BlockDisplay>(marker, true)
        val ordinary = mockk<Entity>(relaxed = true)

        val scheduler = TestTaskScheduler()
        val scope = LifecycleTaskScope(scheduler)
        val queue = BudgetedTaskQueue(scope, maximumSteps = 2, nanoTime = { 0 })
        CrateAmbientEffectService.LegacyAmbientCleanup(marker, queue, mockk(relaxed = true))
            .onEntitiesLoad(EntitiesLoadEvent(mockk<Chunk>(relaxed = true), listOf(
                ambientItem, hologram, ambientBlock, ordinary,
            )))

        verify(exactly = 0) { ambientItem.remove() }
        scheduler.tick()
        verify(exactly = 0) { ambientBlock.remove() }
        scheduler.tick(2)
        verify(exactly = 1) { ambientItem.remove() }
        verify(exactly = 1) { ambientBlock.remove() }
        verify(exactly = 0) { hologram.remove() }
        verify(exactly = 0) { ordinary.remove() }
        queue.close()
        scope.close()
    }

    @Test
    fun largeEntityLoadsShareOneBudgetAndCloseStopsUnfinishedCleanup() {
        val marker = NamespacedKey("arc-excellent-crates", "case_ambient")
        val scheduler = TestTaskScheduler()
        val scope = LifecycleTaskScope(scheduler)
        val queue = BudgetedTaskQueue(scope, maximumSteps = 128, nanoTime = { 0 })
        val display = taggedDisplay<ItemDisplay>(marker, true)
        var removed = 0
        every { display.remove() } answers { removed++; Unit }
        val listener = CrateAmbientEffectService.LegacyAmbientCleanup(marker, queue, mockk(relaxed = true))
        repeat(2) { listener.onEntitiesLoad(EntitiesLoadEvent(mockk(relaxed = true), List(300) { display })) }
        assertEquals(0, removed)
        scheduler.tick()
        assertEquals(128, removed)
        queue.close()
        scheduler.tick(10)
        assertEquals(128, removed)
        scope.close()
    }

    private inline fun <reified T : Entity> taggedDisplay(marker: NamespacedKey, tagged: Boolean): T {
        val display = mockk<T>(relaxed = true)
        val pdc = mockk<PersistentDataContainer>()
        every { display.persistentDataContainer } returns pdc
        every { pdc.has(marker, PersistentDataType.BYTE) } returns tagged
        return display
    }
}
