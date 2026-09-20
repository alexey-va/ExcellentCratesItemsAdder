package ru.ruscrafting.ecia.integration

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.entity.Display
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Transformation
import org.bukkit.util.Vector
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.core.ScheduledTask
import ru.arc.paper.display.PacketItemDisplay
import ru.arc.paper.display.PacketTextDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.ruscrafting.ecia.ArcExcellentCratesPlugin
import ru.ruscrafting.ecia.CrateVisualSettingsStore
import ru.ruscrafting.ecia.ItemDisplayPresentation
import ru.ruscrafting.ecia.inventory.NativeItemPayload
import ru.ruscrafting.ecia.journal.OpeningRecord
import ru.ruscrafting.ecia.roll.RewardDefinition
import ru.ruscrafting.ecia.runtime.EciaRuntime
import java.util.UUID
import kotlin.random.Random

/** Per-viewer packet-display reel rendered above the physical crate. */
internal class WorldRouletteAnimator(
    private val plugin: ArcExcellentCratesPlugin,
    private val runtime: EciaRuntime,
    private val payload: NativeItemPayload,
    private val visualSettings: CrateVisualSettingsStore,
    private val openingEffects: CrateOpeningEffects,
    private val packetDisplays: PaperPacketDisplays,
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

        val anchorKey = CrateVisualSettingsStore.Anchor.of(anchor)
        val visual = visualSettings.get(anchorKey).roulette()
        val presentation = ItemDisplayPresentation.from(plugin.config, "case-roulette.flat-item-displays")
        val occupiedRows = sessions.values.asSequence()
            .filter { it.anchorKey == anchorKey }
            .map { it.row.index }
            .toSet()
        val row = WorldRouletteRows.allocate(occupiedRows)
        val center = anchor.clone().add(
            0.5 + visual.offsetX(),
            visual.offsetY() + row.offsetY,
            0.5 + visual.offsetZ(),
        )
        val viewers = if (visual.visibleToNearby()) {
            plugin.server.onlinePlayers.filter {
                it.world == center.world && it.location.distanceSquared(center) <= visual.viewRange() * visual.viewRange()
            }.ifEmpty { listOf(player) }
        } else listOf(player)
        val views = mutableListOf<View>()
        val created = mutableListOf<() -> Unit>()
        var effect: CrateOpeningEffects.Token? = null
        return runCatching {
            val initial = WorldRouletteTrack.frame(0)
            viewers.forEach { viewer ->
                val axis = reelAxis(center, viewer.eyeLocation)
                val displays = mutableListOf<PacketItemDisplay>()
                sequence.forEachIndexed { index, item ->
                    val position = position(center, axis, WorldRouletteTrack.cells(index, initial), visual.itemSpacing())
                    val display = packetDisplays.spawnItem(position, item.clone())
                    created += display::remove
                    configure(display, visual.viewRange(), presentation)
                    display.transformation = transformation(visual.itemScale(), presentation)
                    displays += display
                }
                val pointer = packetDisplays.spawnText(
                    center.clone().add(0.0, visual.pointerHeight(), 0.0),
                    Component.text("▼", NamedTextColor.GOLD)
                        .decoration(TextDecoration.BOLD, false)
                        .decoration(TextDecoration.ITALIC, false),
                )
                created += pointer::remove
                configure(pointer, visual.viewRange())
                pointer.transformation = transformation(visual.pointerScale())
                pointer.showTo(viewer)
                views += View(viewer, axis.clone(), displays, pointer, BooleanArray(displays.size))
            }

            effect = openingEffects.open(anchor)
            val session = Session(player, views, center, visual, presentation,
                anchorKey, row, anchor.clone(), checkNotNull(effect), onComplete)
            sessions[player.uniqueId] = session
            effect = null
            render(session, WorldRouletteTrack.frame(0))
            session.reelTask = runtime.tasks().runTimer(
                WorldRouletteMotion.FRAME_PERIOD_TICKS,
                WorldRouletteMotion.FRAME_PERIOD_TICKS,
            ) {
                advance(player.uniqueId)
            }
            if (session.reelTask == null) {
                finish(player.uniqueId, complete = false)
                false
            } else {
                created.clear()
                true
            }
        }.getOrElse {
            created.forEach { remove -> runCatching(remove) }
            sessions.remove(player.uniqueId)?.let { session -> openingEffects.close(session.effect) }
                ?: effect?.let(openingEffects::close)
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
        WorldRouletteAudio.slotSound(before, current)?.let { sound ->
            val pitch = (0.75f + current.progress.toFloat() * 0.65f).coerceAtMost(1.4f)
            session.views.forEach { view ->
                if (view.viewer.isOnline) {
                    view.viewer.playSound(session.center, sound, SoundCategory.MASTER, 0.18f, pitch)
                }
            }
        }
    }

    private fun render(session: Session, frame: WorldRouletteFrame) {
        session.views.forEach { view ->
            if (!view.viewer.isOnline || view.viewer.world != session.center.world) return@forEach
            view.displays.forEachIndexed { sequenceIndex, display ->
                val visible = WorldRouletteTrack.visible(sequenceIndex, frame)
                if (!visible) {
                    if (view.visible[sequenceIndex]) display.hideFrom(view.viewer)
                    view.visible[sequenceIndex] = false
                    return@forEachIndexed
                }
                display.teleport(position(session.center, view.axis, WorldRouletteTrack.cells(sequenceIndex, frame), session.visual.itemSpacing()))
                if (!view.visible[sequenceIndex]) display.showTo(view.viewer)
                view.visible[sequenceIndex] = true
            }
        }
    }

    private fun settle(session: Session) {
        val frame = WorldRouletteTrack.frame(WorldRouletteTrack.FRAME_COUNT - 1)
        render(session, frame)
        session.views.forEach { view ->
            val winner = view.displays[WorldRouletteTrack.SELECTED_INDEX]
            winner.isGlowing = true
            winner.glowColorOverride = Color.fromRGB(255, 213, 103)
            winner.transformation = transformation(session.visual.winnerScale(), session.presentation)
            if (view.viewer.isOnline) {
                view.viewer.playSound(session.center, Sound.ENTITY_PLAYER_LEVELUP, SoundCategory.MASTER, 0.55f, 1.25f)
            }
        }
        openingEffects.settle(session.anchor)
    }

    private fun finish(playerId: UUID, complete: Boolean) {
        val session = sessions.remove(playerId) ?: return
        session.reelTask?.cancel()
        session.holdTask?.cancel()
        session.views.forEach { view ->
            view.displays.forEach { display -> runCatching(display::remove) }
            runCatching(view.pointer::remove)
        }
        openingEffects.close(session.effect)
        if (complete && session.player.isOnline) session.onComplete()
    }

    private fun preview(reward: RewardDefinition): ItemStack? =
        runCatching { payload.items(reward.previewPayload()).firstOrNull()?.takeUnless(ItemStack::isEmpty) }.getOrNull()

    private fun configure(display: PacketItemDisplay, viewRange: Float, presentation: ItemDisplayPresentation) {
        display.isVisibleByDefault = false
        display.billboard = Display.Billboard.CENTER
        display.brightness = Display.Brightness(15, 15)
        display.teleportDuration = WorldRouletteMotion.TELEPORT_DURATION_TICKS
        display.interpolationDuration = WorldRouletteMotion.FRAME_PERIOD_TICKS.toInt()
        display.viewRange = viewRange
        display.shadowRadius = 0.15f
        display.shadowStrength = 0.6f
        display.itemDisplayTransform = presentation.transform()
    }

    private fun configure(display: PacketTextDisplay, viewRange: Float) {
        display.isVisibleByDefault = false
        display.billboard = Display.Billboard.CENTER
        display.brightness = Display.Brightness(15, 15)
        display.teleportDuration = WorldRouletteMotion.TELEPORT_DURATION_TICKS
        display.interpolationDuration = WorldRouletteMotion.FRAME_PERIOD_TICKS.toInt()
        display.viewRange = viewRange
        display.shadowRadius = 0.15f
        display.shadowStrength = 0.6f
        display.alignment = TextDisplay.TextAlignment.CENTER
        display.isDefaultBackground = false
        display.backgroundColor = Color.fromARGB(0, 0, 0, 0)
        display.isSeeThrough = true
        display.isShadowed = true
    }

    private fun reelAxis(center: Location, player: Location): Vector {
        val towardPlayer = player.toVector().subtract(center.toVector()).setY(0)
        if (towardPlayer.lengthSquared() < 1.0e-6) towardPlayer.setZ(1)
        towardPlayer.normalize()
        return Vector(-towardPlayer.z, 0.0, towardPlayer.x)
    }

    private fun position(center: Location, axis: Vector, cells: Double, itemSpacing: Double): Location =
        center.clone().add(axis.clone().multiply(cells * itemSpacing))

    private fun transformation(
        scale: Float,
        presentation: ItemDisplayPresentation = ItemDisplayPresentation.THREE_DIMENSIONAL,
    ) = Transformation(
        Vector3f(),
        Quaternionf(),
        presentation.scale(scale),
        Quaternionf(),
    )

    override fun close() {
        sessions.keys.toList().forEach(::cancel)
    }

    private data class Session(
        val player: Player,
        val views: List<View>,
        val center: Location,
        val visual: CrateVisualSettingsStore.Roulette,
        val presentation: ItemDisplayPresentation,
        val anchorKey: CrateVisualSettingsStore.Anchor,
        val row: WorldRouletteRow,
        val anchor: Location,
        val effect: CrateOpeningEffects.Token,
        val onComplete: () -> Unit,
        var frame: Int = 0,
        var reelTask: ScheduledTask? = null,
        var holdTask: ScheduledTask? = null,
    )

    private data class View(
        val viewer: Player,
        val axis: Vector,
        val displays: List<PacketItemDisplay>,
        val pointer: PacketTextDisplay,
        val visible: BooleanArray,
    )

    private companion object {
        const val HOLD_TICKS = 24L
    }
}
