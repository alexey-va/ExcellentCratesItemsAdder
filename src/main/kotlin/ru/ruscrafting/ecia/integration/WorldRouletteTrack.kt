package ru.ruscrafting.ecia.integration

import kotlin.math.floor

internal data class WorldRouletteFrame(
    val baseIndex: Int,
    val offsetCells: Double,
    val progress: Double,
)

/** Pure reel motion: fast at the start, then eases the selected reward under the pointer. */
internal object WorldRouletteTrack {
    const val VISIBLE_ITEMS = 7
    const val CENTER_SLOT = 3
    const val SELECTED_INDEX = 24
    const val FRAME_COUNT = 85

    private const val TRAVEL_CELLS = SELECTED_INDEX - CENTER_SLOT

    fun frame(index: Int): WorldRouletteFrame {
        require(index in 0 until FRAME_COUNT)
        val progress = index.toDouble() / (FRAME_COUNT - 1)
        val eased = 1.0 - (1.0 - progress) * (1.0 - progress)
        val travelled = TRAVEL_CELLS * eased
        val base = floor(travelled + 1.0e-9).toInt().coerceAtMost(TRAVEL_CELLS)
        return WorldRouletteFrame(base, base - travelled, progress)
    }

    fun cells(sequenceIndex: Int, frame: WorldRouletteFrame): Double =
        sequenceIndex - frame.baseIndex - CENTER_SLOT + frame.offsetCells

    fun visible(sequenceIndex: Int, frame: WorldRouletteFrame): Boolean =
        cells(sequenceIndex, frame) in -VISIBLE_EDGE..VISIBLE_EDGE

    private const val VISIBLE_EDGE = CENTER_SLOT + 0.5
}
