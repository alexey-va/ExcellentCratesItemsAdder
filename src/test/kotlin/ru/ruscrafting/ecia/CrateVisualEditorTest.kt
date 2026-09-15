package ru.ruscrafting.ecia

import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkStatic
import org.bukkit.Material
import org.bukkit.configuration.MemoryConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.ecia.integration.ItemsAdderFurnitureAccess
import su.nightexpress.excellentcrates.CratesAPI
import su.nightexpress.excellentcrates.crate.CrateManager
import su.nightexpress.excellentcrates.crate.impl.Crate
import su.nightexpress.excellentcrates.util.pos.WorldPos
import java.nio.file.Path
import java.util.Optional
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer

class CrateVisualEditorTest {
    @TempDir lateinit var directory: Path

    @Test
    fun deletionRemovesOnlyTheSelectedAnchorAndPhysicalShell() {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("CrateVisualEditorTest")
            val player = paper.addPlayer("Editor")
            val block = player.world.getBlockAt(7, 65, -4)
            block.type = Material.ENDER_CHEST
            val retained = WorldPos(player.world.name, 9, 65, -4)
            val selected = WorldPos(player.world.name, block.x, block.y, block.z)
            val positions = mutableSetOf(selected, retained)
            val crate = mockk<Crate>(relaxed = true)
            val manager = mockk<CrateManager>(relaxed = true)
            val furniture = mockk<ItemsAdderFurnitureAccess>(relaxed = true)
            val refreshed = AtomicInteger()
            val changed = AtomicInteger()
            every { crate.blockPositions } returns positions
            every { manager.getCrateById("case_daily") } returns crate
            every { manager.removeCratePositions(crate) } just runs
            every { manager.addCratePositions(crate) } just runs
            every { crate.saveForce() } just runs
            every { crate.recreateHologram() } just runs
            every { furniture.at(block) } returns Optional.empty()
            mockkStatic(CratesAPI::class)
            every { CratesAPI.getCrateManager() } returns manager
            val editor = CrateVisualEditor(
                plugin,
                CrateVisualSettingsStore(directory, MemoryConfiguration()),
                furniture,
                Consumer { changed.incrementAndGet() },
                Runnable { refreshed.incrementAndGet() },
            )
            try {
                assertTrue(editor.deleteCrate(player, CrateVisualTarget("case_daily", block.location)))
                assertEquals(Material.AIR, block.type)
                assertEquals(setOf(retained), positions)
                assertEquals(1, refreshed.get())
                assertEquals(1, changed.get())
            } finally {
                editor.close()
                unmockkStatic(CratesAPI::class)
            }
        }
    }
}
