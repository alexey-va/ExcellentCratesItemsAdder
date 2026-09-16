package ru.ruscrafting.ecia.integration

import org.bukkit.Sound

internal object WorldRouletteAudio {
    fun slotSound(before: WorldRouletteFrame, current: WorldRouletteFrame): Sound? =
        if (current.baseIndex != before.baseIndex) Sound.BLOCK_LEVER_CLICK else null
}
