package ru.ruscrafting.ecia

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.NamespacedKey
import org.bukkit.Chunk
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Entity
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.TextDisplay
import org.bukkit.event.world.EntitiesLoadEvent
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.persistence.PersistentDataType
import org.junit.jupiter.api.Test

class CrateAmbientLegacyCleanupTest {
    @Test
    fun lateEntityLoadRemovesOnlyLegacyAmbientDisplays() {
        val marker = NamespacedKey("arc-excellent-crates", "case_ambient")
        val ambientItem = taggedDisplay<ItemDisplay>(marker, true)
        val hologram = taggedDisplay<TextDisplay>(NamespacedKey("arc-excellent-crates", "case_hologram"), true)
        val ambientBlock = taggedDisplay<BlockDisplay>(marker, true)
        val ordinary = mockk<Entity>(relaxed = true)

        CrateAmbientEffectService.LegacyAmbientCleanup(marker)
            .onEntitiesLoad(EntitiesLoadEvent(mockk<Chunk>(relaxed = true), listOf(
                ambientItem, hologram, ambientBlock, ordinary,
            )))

        verify(exactly = 1) { ambientItem.remove() }
        verify(exactly = 1) { ambientBlock.remove() }
        verify(exactly = 0) { hologram.remove() }
        verify(exactly = 0) { ordinary.remove() }
    }

    private inline fun <reified T : Entity> taggedDisplay(marker: NamespacedKey, tagged: Boolean): T {
        val display = mockk<T>(relaxed = true)
        val pdc = mockk<PersistentDataContainer>()
        every { display.persistentDataContainer } returns pdc
        every { pdc.has(marker, PersistentDataType.BYTE) } returns tagged
        return display
    }
}
