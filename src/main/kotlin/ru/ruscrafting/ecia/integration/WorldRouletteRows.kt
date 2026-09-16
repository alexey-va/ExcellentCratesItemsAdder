package ru.ruscrafting.ecia.integration

internal data class WorldRouletteRow(
    val index: Int,
    val offsetY: Double,
)

internal object WorldRouletteRows {
    const val SPACING = 1.65

    fun allocate(occupied: Set<Int>): WorldRouletteRow {
        val index = generateSequence(0, Int::inc).first { it !in occupied }
        return WorldRouletteRow(index, index * SPACING)
    }
}
