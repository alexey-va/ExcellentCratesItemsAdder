package ru.ruscrafting.ecia.integration

import org.bukkit.Sound
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class WorldRouletteAudioTest {
    @Test
    fun crossingARewardSlotProducesAMechanicalClick() {
        val frames = (0 until WorldRouletteTrack.FRAME_COUNT).map(WorldRouletteTrack::frame)
        val crossing = frames.zipWithNext().first { (before, current) -> before.baseIndex != current.baseIndex }

        assertEquals(Sound.BLOCK_LEVER_CLICK, WorldRouletteAudio.slotSound(crossing.first, crossing.second))
    }

    @Test
    fun motionInsideOneRewardSlotStaysSilent() {
        val frames = (0 until WorldRouletteTrack.FRAME_COUNT).map(WorldRouletteTrack::frame)
        val sameSlot = frames.zipWithNext().first { (before, current) -> before.baseIndex == current.baseIndex }

        assertNull(WorldRouletteAudio.slotSound(sameSlot.first, sameSlot.second))
    }
}
