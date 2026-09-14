package ru.ruscrafting.ecia.integration

import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.block.Lidded
import java.util.UUID

/** World-visible shell, sound and particle effects around the durable roulette. */
class CrateOpeningEffects(
    private val furniture: ItemsAdderFurnitureAccess,
) : AutoCloseable {
    private val opened = mutableMapOf<Anchor, SharedOpening>()

    fun open(location: Location): Token {
        val block = location.block
        val anchor = Anchor(location.world.uid, block.x, block.y, block.z)
        val existing = opened[anchor]
        if (existing != null) {
            existing.references++
            burst(location, opening = true)
            return Token(anchor)
        }

        val models = furniture.registeredIds()
        val instance = furniture.at(block).orElse(null)
        val openModel = instance?.let { CrateModelPairing.openingModel(it.namespacedId(), models) }
        val changedFurniture = openModel != null && furniture.replace(block, openModel)
        val lid = block.state as? Lidded
        if (!changedFurniture) lid?.open()
        opened[anchor] = SharedOpening(
            location.clone(),
            instance?.namespacedId()?.takeIf { changedFurniture },
            lid != null && !changedFurniture,
            1,
        )
        burst(location, opening = true)
        return Token(anchor)
    }

    fun settle(location: Location) {
        val center = location.clone().add(.5, 1.05, .5)
        location.world.spawnParticle(Particle.END_ROD, center, 28, .65, .55, .65, .035)
        location.world.spawnParticle(Particle.TOTEM_OF_UNDYING, center, 18, .45, .4, .45, .08)
        location.world.playSound(center, Sound.BLOCK_BEACON_POWER_SELECT, SoundCategory.MASTER, .55f, 1.35f)
    }

    fun close(token: Token) {
        val shared = opened[token.anchor] ?: return
        shared.references--
        if (shared.references > 0) return
        opened.remove(token.anchor)
        val block = shared.location.block
        if (shared.closedFurniture != null) furniture.replace(block, shared.closedFurniture)
        if (shared.vanillaLid) (block.state as? Lidded)?.close()
        burst(shared.location, opening = false)
    }

    override fun close() {
        opened.keys.toList().forEach { anchor ->
            val shared = opened.remove(anchor) ?: return@forEach
            val block = shared.location.block
            if (shared.closedFurniture != null) furniture.replace(block, shared.closedFurniture)
            if (shared.vanillaLid) (block.state as? Lidded)?.close()
        }
    }

    private fun burst(location: Location, opening: Boolean) {
        val center = location.clone().add(.5, .85, .5)
        val dust = Particle.DustTransition(
            if (opening) Color.fromRGB(148, 88, 255) else Color.fromRGB(255, 205, 90),
            if (opening) Color.fromRGB(255, 205, 90) else Color.fromRGB(148, 88, 255),
            1.25f,
        )
        location.world.spawnParticle(Particle.DUST_COLOR_TRANSITION, center, 42, .65, .55, .65, .01, dust)
        location.world.spawnParticle(Particle.ENCHANT, center, if (opening) 36 else 16, .7, .45, .7, .12)
        location.world.playSound(
            center,
            if (opening) Sound.BLOCK_ENDER_CHEST_OPEN else Sound.BLOCK_ENDER_CHEST_CLOSE,
            SoundCategory.MASTER,
            .7f,
            if (opening) .82f else 1.15f,
        )
        if (opening) {
            location.world.playSound(center, Sound.BLOCK_VAULT_ACTIVATE, SoundCategory.MASTER, .45f, 1.1f)
        }
    }

    class Token internal constructor(internal val anchor: Anchor)

    internal data class Anchor(val world: UUID, val x: Int, val y: Int, val z: Int)

    private data class SharedOpening(
        val location: Location,
        val closedFurniture: String?,
        val vanillaLid: Boolean,
        var references: Int,
    )
}
