package ru.ruscrafting.ecia.integration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WorldRouletteTrackTest {
    @Test
    fun selectedRewardStopsUnderCenterPointer() {
        val final = WorldRouletteTrack.frame(WorldRouletteTrack.FRAME_COUNT - 1)

        assertEquals(WorldRouletteTrack.SELECTED_INDEX - WorldRouletteTrack.CENTER_SLOT, final.baseIndex)
        assertEquals(0.0, final.offsetCells, 1.0e-9)
        assertEquals(WorldRouletteTrack.SELECTED_INDEX, final.baseIndex + WorldRouletteTrack.CENTER_SLOT)
    }

    @Test
    fun reelMovesForwardAndDecelerates() {
        val frames = (0 until WorldRouletteTrack.FRAME_COUNT).map(WorldRouletteTrack::frame)
        val travelled = frames.map { it.baseIndex - it.offsetCells }
        val steps = travelled.zipWithNext { left, right -> right - left }

        assertTrue(travelled.zipWithNext().all { (left, right) -> right >= left })
        assertTrue(steps.first() > steps.last())
        assertTrue(frames.all { it.offsetCells in -1.0..0.0 })
    }

    @Test
    fun eachSequenceItemMovesContinuouslyAndIsHiddenOutsideTheWindow() {
        val frames = (0 until WorldRouletteTrack.FRAME_COUNT).map(WorldRouletteTrack::frame)

        for (sequenceIndex in 0 until WorldRouletteTrack.SELECTED_INDEX + WorldRouletteTrack.VISIBLE_ITEMS) {
            val positions = frames.map { WorldRouletteTrack.cells(sequenceIndex, it) }
            assertTrue(positions.zipWithNext().all { (left, right) -> right <= left })
            val visibleFrames = frames.indices.filter { WorldRouletteTrack.visible(sequenceIndex, frames[it]) }
            assertTrue(visibleFrames.zipWithNext().all { (left, right) -> right == left + 1 })
        }
    }
}
