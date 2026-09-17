package ru.ruscrafting.ecia.integration

/** Timing used by the world reel: render a new target every tick, interpolate position over two. */
internal object WorldRouletteMotion {
    const val FRAME_PERIOD_TICKS = 1L
    const val TELEPORT_DURATION_TICKS = 2
}
