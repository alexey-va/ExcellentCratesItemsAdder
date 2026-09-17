package ru.ruscrafting.ecia.integration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WorldRouletteMotionTest {
    @Test
    fun positionInterpolationHasBufferBeyondTheOneTickRenderCadence() {
        assertTrue(WorldRouletteMotion.TELEPORT_DURATION_TICKS > WorldRouletteMotion.FRAME_PERIOD_TICKS)
        assertEquals(2, WorldRouletteMotion.TELEPORT_DURATION_TICKS)
    }
}
