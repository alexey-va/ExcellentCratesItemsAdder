package ru.ruscrafting.ecia

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import io.mockk.verifyOrder
import net.kyori.adventure.text.Component
import org.bukkit.Chunk
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Server
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Display
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.event.world.EntitiesLoadEvent
import org.bukkit.persistence.PersistentDataType
import org.bukkit.scheduler.BukkitScheduler
import org.bukkit.util.BoundingBox
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.display.PacketTextDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.ruscrafting.ecia.runtime.EciaRuntime
import su.nightexpress.excellentcrates.CratesAPI
import su.nightexpress.excellentcrates.CratesPlugin
import su.nightexpress.excellentcrates.crate.CrateManager
import su.nightexpress.excellentcrates.crate.impl.Crate
import su.nightexpress.excellentcrates.hologram.HologramManager
import su.nightexpress.excellentcrates.util.pos.WorldPos
import java.nio.file.Path
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.function.BooleanSupplier
import java.util.logging.Logger

class CrateHologramPacketDisplaysTest {
    @Test
    fun privateFacesKeepTheirHandlesAcrossSideChangesAndReload(@TempDir directory: Path) {
        val scheduler = TestTaskScheduler()
        val scope = LifecycleTaskScope(scheduler)
        val queue = BudgetedTaskQueue(scope, nanoTime = { 0 })
        val plugin = mockk<ArcExcellentCratesPlugin>(relaxed = true)
        val runtime = mockk<EciaRuntime>()
        val server = mockk<Server>(relaxed = true)
        val timers = mutableMapOf<Long, Runnable>()
        val bukkitScheduler = mockk<BukkitScheduler>()
        val config = YamlConfiguration()
        every { plugin.name } returns "ArcExcellentCrates"
        every { plugin.server } returns server
        every { plugin.config } returns config
        every { plugin.runtime() } returns runtime
        every { plugin.logger } returns Logger.getLogger(javaClass.name)
        every { runtime.nativeWork() } returns queue
        val nativeCleanup = mutableListOf<BooleanSupplier>()
        every { runtime.submitNativeCleanup(any(), any()) } answers {
            nativeCleanup += secondArg<BooleanSupplier>()
            CompletableFuture.completedFuture(null)
        }
        every { server.scheduler } returns bukkitScheduler
        every { bukkitScheduler.runTaskTimer(plugin, any<Runnable>(), any<Long>(), any<Long>()) } answers {
            timers[arg(3)] = arg(1)
            mockk(relaxed = true)
        }

        val world = mockk<World>()
        every { world.name } returns "world"
        val position = mockk<WorldPos>()
        every { position.world } returns world
        every { position.worldName } returns "world"
        every { position.x } returns 8
        every { position.y } returns 64
        every { position.z } returns 8
        every { position.isChunkLoaded } returns true
        val block = mockk<Block>()
        every { block.type } returns Material.ENDER_CHEST
        every { block.boundingBox } returns BoundingBox(8.0, 64.0, 8.0, 9.0, 65.0, 9.0)
        every { position.toBlock() } returns block
        val crate = mockk<Crate>()
        every { crate.id } returns "test"
        every { crate.name } returns "Test crate"
        every { crate.isHologramEnabled } returns true
        every { crate.blockPositions } returns setOf(position)
        val crates = mockk<CrateManager>()
        every { crates.crates } returns setOf(crate)
        every { crates.getCrateById("test") } returns crate
        val nativeHolograms = mockk<HologramManager>(relaxed = true)
        val provider = mockk<CratesPlugin>()
        every { provider.hologramManager } returns Optional.of(nativeHolograms)
        every { provider.isEnabled } returns true

        val player = mockk<Player>()
        val viewerId = UUID.randomUUID()
        var eye = Location(world, 8.5, 66.0, 12.0)
        every { player.uniqueId } returns viewerId
        every { player.isOnline } returns true
        every { player.world } returns world
        every { player.eyeLocation } answers { eye }
        every { server.onlinePlayers } returns listOf(player)
        every { server.getPlayer(viewerId) } returns player

        val marker = NamespacedKey(plugin, "case_hologram")
        val legacy = mockk<TextDisplay>(relaxed = true)
        every { legacy.persistentDataContainer.has(marker, PersistentDataType.STRING) } returns true
        val unrelated = mockk<TextDisplay>(relaxed = true)
        every { unrelated.persistentDataContainer.has(marker, PersistentDataType.STRING) } returns false
        val otherKind = mockk<ItemDisplay>(relaxed = true)
        val chunk = mockk<Chunk>()
        every { chunk.world } returns world
        every { chunk.isLoaded } returns true
        every { chunk.isEntitiesLoaded } returns true
        every { chunk.entities } returns arrayOf(legacy, unrelated, otherKind)
        every { world.loadedChunks } returns arrayOf(chunk)
        every { server.worlds } returns listOf(world)

        val locations = mutableMapOf<PacketTextDisplay, Location>()
        val faces = List(2) {
            mockk<PacketTextDisplay>(relaxed = true).also { display ->
                every { display.isValid } returns true
                every { display.location } answers { locations.getValue(display).clone() }
                every { display.teleport(any()) } answers { locations[display] = firstArg<Location>().clone(); Unit }
                every { display.transformation } returns Transformation(Vector3f(), Quaternionf(), Vector3f(1f), Quaternionf())
            }
        }
        val packetDisplays = mockk<PaperPacketDisplays>()
        var nextFace = 0
        every { packetDisplays.spawnText(any(), any()) } answers {
            faces[nextFace++].also { locations[it] = firstArg<Location>().clone() }
        }
        val settings = CrateVisualSettingsStore(directory, config)
        mockkStatic(CratesAPI::class)
        every { CratesAPI.isLoaded() } returns true
        every { CratesAPI.getCrateManager() } returns crates
        every { CratesAPI.getPlugin() } returns provider
        try {
            val service = CrateHologramService(plugin, settings, packetDisplays)
            try {
                scheduler.tick(2)
                timers.getValue(2L).run()
                scheduler.tick()
                verify(exactly = 1) { faces[0].showTo(player); faces[1].hideFrom(player) }
                faces.forEach { face ->
                    assertEquals(Location(world, 8.5, 65.58, 8.5), locations.getValue(face))
                    verify { face.isVisibleByDefault = false; face.text(Component.text("Test crate")) }
                    verify { face.billboard = Display.Billboard.CENTER; face.lineWidth = 512 }
                    verify { face.backgroundColor = Color.fromARGB(0); face.isShadowed = true; face.isSeeThrough = false }
                    verify { face.transformation = match { it.scale == Vector3f(2f) } }
                }

                timers.getValue(20L).run()
                timers.getValue(2L).run()
                scheduler.tick(2)
                verify(exactly = 1) { faces[0].showTo(player); faces[1].hideFrom(player) }
                eye = Location(world, 8.5, 66.0, 4.0)
                timers.getValue(2L).run()
                scheduler.tick()
                verify(exactly = 1) { faces[0].hideFrom(player); faces[1].showTo(player) }
                faces.forEach { face -> verifyOrder { face.isVisibleByDefault = false; face.showTo(player) } }

                config.set("case-holograms.yaw", 35.0)
                config.set("case-holograms.pitch", 10.0)
                settings.reload()
                service.reload()
                service.onEntitiesLoad(EntitiesLoadEvent(chunk, listOf(legacy, unrelated, otherKind)))
                scheduler.tick(2)
                faces.forEach { face ->
                    assertEquals(35f, locations.getValue(face).yaw)
                    assertEquals(10f, locations.getValue(face).pitch)
                }
                verify(exactly = 2) { packetDisplays.spawnText(any(), any()) }
                verify(exactly = 0) { faces[0].remove(); faces[1].remove() }
                verify(exactly = 3) { legacy.remove() }
                verify(exactly = 0) { unrelated.remove(); otherKind.remove() }
            } finally { service.close(); service.close() }
            faces.forEach { verify(exactly = 1) { it.remove() } }
            nativeCleanup.forEach { while (!it.asBoolean) { } }
            verify(exactly = 1) { nativeHolograms.enableBlockHologram(crate, position) }
            verify(exactly = 0) { packetDisplays.close(); packetDisplays.closeAsync() }
        } finally {
            queue.close()
            scope.close()
            settings.close()
            unmockkStatic(CratesAPI::class)
        }
    }
}
