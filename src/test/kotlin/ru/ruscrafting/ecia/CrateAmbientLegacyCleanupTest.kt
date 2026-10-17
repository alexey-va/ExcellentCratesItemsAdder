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
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import org.junit.jupiter.api.Assertions.assertEquals

class CrateAmbientLegacyCleanupTest {
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
        val queue = BudgetedTaskQueue(scope, nanoTime = { 0 })
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
