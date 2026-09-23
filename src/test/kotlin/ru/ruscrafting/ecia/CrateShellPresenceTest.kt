package ru.ruscrafting.ecia

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Material
import org.bukkit.block.Block
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.ruscrafting.ecia.integration.ItemsAdderFurnitureAccess
import su.nightexpress.excellentcrates.util.pos.WorldPos
import java.util.Optional

class CrateShellPresenceTest {
    @Test
    fun deletedShellHidesEffectsButEntityOnlyFurnitureStillRenders() {
        val block = mockk<Block>()
        val position = mockk<WorldPos>()
        val furniture = mockk<ItemsAdderFurnitureAccess>()
        every { position.world } returns mockk()
        every { position.isChunkLoaded } returns true
        every { position.toBlock() } returns block
        every { block.type } returns Material.AIR
        every { furniture.at(block) } returns Optional.empty()
        assertFalse(CrateShellPresence.isPresent(position, furniture))
        every { furniture.at(block) } returns Optional.of(mockk())
        assertTrue(CrateShellPresence.isPresent(position, furniture))
        every { block.type } returns Material.CHEST
        assertTrue(CrateShellPresence.isPresent(position, furniture))
    }

    @Test
    fun unloadedChunkNeverReadsBlockOrLoadsFurniture() {
        val position = mockk<WorldPos>()
        val furniture = mockk<ItemsAdderFurnitureAccess>()
        every { position.world } returns mockk()
        every { position.isChunkLoaded } returns false
        assertFalse(CrateShellPresence.isPresent(position, furniture))
        verify(exactly = 0) { position.toBlock() }
        verify(exactly = 0) { furniture.at(any()) }
    }
}
