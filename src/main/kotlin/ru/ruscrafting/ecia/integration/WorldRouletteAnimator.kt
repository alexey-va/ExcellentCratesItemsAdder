package ru.ruscrafting.ecia.integration

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.entity.Display
import org.bukkit.entity.Entity
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Transformation
import org.bukkit.util.Vector
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.core.ScheduledTask
import ru.ruscrafting.ecia.ArcExcellentCratesPlugin
import ru.ruscrafting.ecia.CrateVisualSettingsStore
import ru.ruscrafting.ecia.inventory.NativeItemPayload
import ru.ruscrafting.ecia.journal.OpeningRecord
import ru.ruscrafting.ecia.roll.RewardDefinition
import ru.ruscrafting.ecia.runtime.EciaRuntime
import java.util.UUID
import kotlin.random.Random

/** Player-only display-entity reel rendered above the physical crate. */
internal class WorldRouletteAnimator(
    private val plugin: ArcExcellentCratesPlugin,
    private val runtime: EciaRuntime,
    private val payload: NativeItemPayload,
    private val visualSettings: CrateVisualSettingsStore,
    private val openingEffects: CrateOpeningEffects,
) : AutoCloseable {
    private val sessions = mutableMapOf<UUID, Session>()

    fun isRolling(playerId: UUID): Boolean = playerId in sessions

    fun start(player: Player, anchor: Location, opening: OpeningRecord, onComplete: () -> Unit): Boolean {
        if (isRolling(player.uniqueId) || anchor.world == null || anchor.world != player.world) return false
        val selected = opening.selectedReward()
        val selectedItem = preview(selected) ?: return false
        val candidates = opening.pool().rewards().mapNotNull(::preview).ifEmpty { listOf(selectedItem) }
        val sequence = MutableList(WorldRouletteTrack.SELECTED_INDEX + WorldRouletteTrack.VISIBLE_ITEMS) {
            candidates[Random.nextInt(candidates.size)].clone()
        }
        sequence[WorldRouletteTrack.SELECTED_INDEX] = selectedItem.clone()

        val visual = visualSettings.get(CrateVisualSettingsStore.Anchor.of(anchor)).roulette()
        val center = anchor.clone().add(0.5 + visual.offsetX(), visual.offsetY(), 0.5 + visual.offsetZ())
        val axis = reelAxis(center, player.location)
        val displays = mutableListOf<ItemDisplay>()
        val entities = mutableListOf<Entity>()
        return runCatching {
            val initial = WorldRouletteTrack.frame(0)
            sequence.forEachIndexed { index, item ->
                val position = position(center, axis, WorldRouletteTrack.cells(index, initial), visual.itemSpacing())
                val display = player.world.spawn(position, ItemDisplay::class.java) { entity ->
                    configure(entity, visual.viewRange())
                    entity.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.GUI
                    entity.transformation = transformation(visual.itemScale())
                    entity.setItemStack(item.clone())
                }
                displays += display
                entities += display
            }
            val pointer = player.world.spawn(center.clone().add(0.0, visual.pointerHeight(), 0.0), TextDisplay::class.java) { entity ->
                configure(entity, visual.viewRange())
                entity.text(
                    Component.text("▼", NamedTextColor.GOLD)
                        .decoration(TextDecoration.BOLD, false)
                        .decoration(TextDecoration.ITALIC, false),
                )
                entity.alignment = TextDisplay.TextAlignment.CENTER
                entity.isDefaultBackground = false
                entity.backgroundColor = Color.fromARGB(0, 0, 0, 0)
                entity.isSeeThrough = true
                entity.isShadowed = true
                entity.transformation = transformation(visual.pointerScale())
            }
            player.showEntity(plugin, pointer)
            entities += pointer

            val effect = openingEffects.open(anchor)
            val session = Session(player, displays, entities, BooleanArray(displays.size), center, axis, visual,
                anchor.clone(), effect, onComplete)
            sessions[player.uniqueId] = session
            render(session, WorldRouletteTrack.frame(0))
            session.reelTask = runtime.tasks().runTimer(FRAME_PERIOD_TICKS, FRAME_PERIOD_TICKS) {
                advance(player.uniqueId)
            }
            if (session.reelTask == null) {
                finish(player.uniqueId, complete = false)
                false
            } else {
                true
            }
        }.getOrElse {
            entities.forEach { entity -> runCatching(entity::remove) }
            sessions.remove(player.uniqueId)?.let { session -> openingEffects.close(session.effect) }
            runtime.warn("World roulette could not start for {}: {}", player.name, it.toString())
            false
        }
    }

    fun cancel(playerId: UUID) {
        finish(playerId, complete = false)
    }

    private fun advance(playerId: UUID) {
        val session = sessions[playerId] ?: return
        if (!session.player.isOnline) return finish(playerId, complete = false)
        session.frame++
        if (session.frame >= WorldRouletteTrack.FRAME_COUNT) {
            session.reelTask?.cancel()
            session.reelTask = null
            settle(session)
            session.holdTask = runtime.tasks().runLater(HOLD_TICKS) { finish(playerId, complete = true) }
            if (session.holdTask == null) finish(playerId, complete = true)
            return
        }
        val before = WorldRouletteTrack.frame(session.frame - 1)
        val current = WorldRouletteTrack.frame(session.frame)
        render(session, current)
        if (current.baseIndex != before.baseIndex) {
            val pitch = (0.75f + current.progress.toFloat() * 0.65f).coerceAtMost(1.4f)
            session.player.playSound(session.center, Sound.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.MASTER, 0.18f, pitch)
        }
    }

    private fun render(session: Session, frame: WorldRouletteFrame) {
        session.displays.forEachIndexed { sequenceIndex, display ->
            val visible = WorldRouletteTrack.visible(sequenceIndex, frame)
            if (!visible) {
                if (session.visible[sequenceIndex]) session.player.hideEntity(plugin, display)
                session.visible[sequenceIndex] = false
                return@forEachIndexed
            }
            display.teleport(position(session.center, session.axis, WorldRouletteTrack.cells(sequenceIndex, frame), session.visual.itemSpacing()))
            if (!session.visible[sequenceIndex]) session.player.showEntity(plugin, display)
            session.visible[sequenceIndex] = visible
        }
    }

    private fun settle(session: Session) {
        val frame = WorldRouletteTrack.frame(WorldRouletteTrack.FRAME_COUNT - 1)
        render(session, frame)
        val winner = session.displays[WorldRouletteTrack.SELECTED_INDEX]
        winner.isGlowing = true
        winner.glowColorOverride = Color.fromRGB(255, 213, 103)
        winner.transformation = transformation(session.visual.winnerScale())
        session.player.playSound(session.center, Sound.ENTITY_PLAYER_LEVELUP, SoundCategory.MASTER, 0.55f, 1.25f)
        openingEffects.settle(session.anchor)
    }

    private fun finish(playerId: UUID, complete: Boolean) {
        val session = sessions.remove(playerId) ?: return
        session.reelTask?.cancel()
        session.holdTask?.cancel()
        session.entities.forEach { entity -> runCatching(entity::remove) }
        openingEffects.close(session.effect)
        if (complete && session.player.isOnline) session.onComplete()
    }

    private fun preview(reward: RewardDefinition): ItemStack? =
        runCatching { payload.items(reward.previewPayload()).firstOrNull()?.takeUnless(ItemStack::isEmpty) }.getOrNull()

    private fun configure(display: Display, viewRange: Float) {
        display.isPersistent = false
        display.isVisibleByDefault = false
        display.billboard = Display.Billboard.CENTER
        display.brightness = Display.Brightness(15, 15)
        display.teleportDuration = FRAME_PERIOD_TICKS.toInt()
        display.interpolationDuration = FRAME_PERIOD_TICKS.toInt()
        display.viewRange = viewRange
        display.shadowRadius = 0.15f
        display.shadowStrength = 0.6f
    }

    private fun reelAxis(center: Location, player: Location): Vector {
        val towardPlayer = player.toVector().subtract(center.toVector()).setY(0)
        if (towardPlayer.lengthSquared() < 1.0e-6) towardPlayer.setZ(1)
        towardPlayer.normalize()
        return Vector(-towardPlayer.z, 0.0, towardPlayer.x)
    }

    private fun position(center: Location, axis: Vector, cells: Double, itemSpacing: Double): Location =
        center.clone().add(axis.clone().multiply(cells * itemSpacing))

    private fun transformation(scale: Float) = Transformation(
        Vector3f(),
        Quaternionf(),
        Vector3f(scale, scale, scale),
        Quaternionf(),
    )

    override fun close() {
        sessions.keys.toList().forEach(::cancel)
    }

    private data class Session(
        val player: Player,
        val displays: List<ItemDisplay>,
        val entities: List<Entity>,
        val visible: BooleanArray,
        val center: Location,
        val axis: Vector,
        val visual: CrateVisualSettingsStore.Roulette,
        val anchor: Location,
        val effect: CrateOpeningEffects.Token,
        val onComplete: () -> Unit,
        var frame: Int = 0,
        var reelTask: ScheduledTask? = null,
        var holdTask: ScheduledTask? = null,
    )

    private companion object {
        const val FRAME_PERIOD_TICKS = 1L
        const val HOLD_TICKS = 24L
    }
}
