package ru.ruscrafting.ecia.integration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WorldRouletteRowsTest {
    @Test
    fun simultaneousRouletteRowsStackVertically() {
        val occupied = mutableSetOf<Int>()

        val rows = List(3) {
            WorldRouletteRows.allocate(occupied).also { occupied += it.index }
        }

        assertEquals(listOf(0, 1, 2), rows.map(WorldRouletteRow::index))
        assertEquals(listOf(0.0, 1.65, 3.3), rows.map(WorldRouletteRow::offsetY))
    }

    @Test
    fun releasedRowIsReusedWithoutRenumberingActiveRows() {
        val row = WorldRouletteRows.allocate(setOf(0, 2))

        assertEquals(1, row.index)
        assertEquals(1.65, row.offsetY)
    }
}
