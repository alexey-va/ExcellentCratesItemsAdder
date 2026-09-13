package ru.ruscrafting.ecia

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.entity.Player
import ru.arc.paper.menu.PaperDialogActionId
import ru.arc.paper.menu.PaperDialogBody
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.menu.PaperDialogClickContext
import ru.arc.paper.menu.PaperDialogInputId
import ru.arc.paper.menu.PaperDialogNumberRangeInput
import ru.arc.paper.menu.PaperDialogRuntime
import ru.arc.paper.menu.PaperDialogScreen
import java.util.function.Consumer

/** Native per-anchor editor entered from Shift + left-click. */
class CrateVisualEditor(
    plugin: ArcExcellentCratesPlugin,
    private val store: CrateVisualSettingsStore,
    private val changed: Consumer<CrateVisualSettingsStore.Anchor>,
) : AutoCloseable {
    private val dialogs = PaperDialogRuntime(plugin)

    fun open(player: Player, target: CrateVisualTarget): Boolean {
        if (!player.hasPermission("ecia.admin")) return false
        dialogs.beginFlow(player)
        openMain(player, target)
        return true
    }

    private fun openMain(player: Player, target: CrateVisualTarget, saved: Boolean = false) {
        val anchor = CrateVisualSettingsStore.Anchor.of(target.anchor())
        val body = buildList {
            add(body("Настройки относятся только к этому установленному сундуку."))
            add(body("${anchor.world()} · ${anchor.x()}, ${anchor.y()}, ${anchor.z()}"))
            if (saved) add(body("Изменения применены сразу.", SUCCESS))
        }
        dialogs.open(player, PaperDialogScreen(
            id = "arc-excellent-crates.visuals",
            title = text("Визуал кейса · ${target.crateId()}", GOLD, bold = true),
            body = body,
            buttons = listOf(
                button("edit_hologram", "Голограмма ›", VIOLET) { openHologram(it.player, target) },
                button("edit_roulette", "Рулетка ›", VIOLET) { openRoulette(it.player, target) },
                button("reset_visuals", "Сбросить настройки", DANGER) {
                    store.reset(anchor)
                    changed.accept(anchor)
                    openMain(it.player, target, saved = true)
                },
            ),
            exitButton = closeButton(),
            columns = 1,
        ))
    }

    private fun openHologram(player: Player, target: CrateVisualTarget, saved: Boolean = false) {
        val anchor = CrateVisualSettingsStore.Anchor.of(target.anchor())
        val current = store.get(anchor)
        val value = current.hologram()
        dialogs.open(player, PaperDialogScreen(
            id = "arc-excellent-crates.visuals.hologram",
            title = text("Голограмма · ${target.crateId()}", VIOLET, bold = true),
            body = listOf(body(if (saved) "Сохранено. Голограмма обновлена." else "Положение считается от центра верхней грани сундука.", if (saved) SUCCESS else BODY)),
            numberInputs = listOf(
                input(HX, "Смещение X", -8f, 8f, value.offsetX().toFloat(), .05f),
                input(HY, "Смещение Y", -4f, 8f, value.offsetY().toFloat(), .05f),
                input(HZ, "Смещение Z", -8f, 8f, value.offsetZ().toFloat(), .05f),
                input(HYAW, "Поворот по горизонтали", -180f, 180f, value.yaw(), 1f),
                input(HPITCH, "Наклон", -90f, 90f, value.pitch(), 1f),
                input(HSCALE, "Размер", .1f, 10f, value.scale(), .05f),
                input(HRANGE, "Дальность отображения", .1f, 64f, value.viewRange(), .1f),
            ),
            buttons = listOf(button("save_hologram", "Сохранить изменения", SUCCESS) { context ->
                val hologram = CrateVisualSettingsStore.Hologram(
                    number(context, HX, value.offsetX().toFloat()).toDouble(),
                    number(context, HY, value.offsetY().toFloat()).toDouble(),
                    number(context, HZ, value.offsetZ().toFloat()).toDouble(),
                    number(context, HYAW, value.yaw()),
                    number(context, HPITCH, value.pitch()),
                    number(context, HSCALE, value.scale()),
                    number(context, HRANGE, value.viewRange()),
                )
                store.save(anchor, CrateVisualSettingsStore.Visuals(hologram, current.roulette()))
                changed.accept(anchor)
                openHologram(context.player, target, saved = true)
            }),
            exitButton = backButton(),
            columns = 1,
        ))
    }

    private fun openRoulette(player: Player, target: CrateVisualTarget, saved: Boolean = false) {
        val anchor = CrateVisualSettingsStore.Anchor.of(target.anchor())
        val current = store.get(anchor)
        val value = current.roulette()
        dialogs.open(player, PaperDialogScreen(
            id = "arc-excellent-crates.visuals.roulette",
            title = text("Рулетка · ${target.crateId()}", VIOLET, bold = true),
            body = listOf(body(if (saved) "Сохранено. Новая геометрия сработает при следующем открытии." else "Положение считается от центра сундука. Интервал задаёт общую ширину ленты.", if (saved) SUCCESS else BODY)),
            numberInputs = listOf(
                input(RX, "Смещение X", -16f, 16f, value.offsetX().toFloat(), .05f),
                input(RY, "Смещение Y", -4f, 16f, value.offsetY().toFloat(), .05f),
                input(RZ, "Смещение Z", -16f, 16f, value.offsetZ().toFloat(), .05f),
                input(RSPACING, "Интервал между призами", .1f, 5f, value.itemSpacing().toFloat(), .05f),
                input(RITEM, "Размер призов", .1f, 10f, value.itemScale(), .05f),
                input(RWINNER, "Размер победителя", .1f, 12f, value.winnerScale(), .05f),
                input(RPOINTER_Y, "Высота указателя", -4f, 8f, value.pointerHeight().toFloat(), .05f),
                input(RPOINTER_SCALE, "Размер указателя", .1f, 10f, value.pointerScale(), .05f),
                input(RRANGE, "Дальность отображения", .1f, 64f, value.viewRange(), .1f),
            ),
            buttons = listOf(button("save_roulette", "Сохранить изменения", SUCCESS) { context ->
                val roulette = CrateVisualSettingsStore.Roulette(
                    number(context, RX, value.offsetX().toFloat()).toDouble(),
                    number(context, RY, value.offsetY().toFloat()).toDouble(),
                    number(context, RZ, value.offsetZ().toFloat()).toDouble(),
                    number(context, RSPACING, value.itemSpacing().toFloat()).toDouble(),
                    number(context, RITEM, value.itemScale()),
                    number(context, RWINNER, value.winnerScale()),
                    number(context, RPOINTER_Y, value.pointerHeight().toFloat()).toDouble(),
                    number(context, RPOINTER_SCALE, value.pointerScale()),
                    number(context, RRANGE, value.viewRange()),
                )
                store.save(anchor, CrateVisualSettingsStore.Visuals(current.hologram(), roulette))
                changed.accept(anchor)
                openRoulette(context.player, target, saved = true)
            }),
            exitButton = backButton(),
            columns = 1,
        ))
    }

    private fun input(id: PaperDialogInputId, label: String, start: Float, end: Float, initial: Float, step: Float) =
        PaperDialogNumberRangeInput(id, text(label, WHITE), start, end, initial, step, 420, "%s: %s")

    private fun number(context: PaperDialogClickContext, id: PaperDialogInputId, fallback: Float): Float =
        context.number(id)?.takeIf(Float::isFinite) ?: fallback

    private fun button(id: String, label: String, color: Int, action: (PaperDialogClickContext) -> Unit) =
        PaperDialogButton(PaperDialogActionId.of(id), text(label, color), width = 260, closeDialogBeforeAction = false, onClick = action)

    private fun closeButton() = PaperDialogButton(PaperDialogActionId.of("close_visuals"), text("Закрыть", WHITE), width = 200, closeDialogBeforeAction = true) {}
    private fun backButton() = PaperDialogButton(PaperDialogActionId.of("back_visuals"), text("‹ Назад", WHITE), width = 200, closeDialogBeforeAction = false) {}
    private fun body(value: String, color: Int = BODY) = PaperDialogBody(text(value, color), 420)

    private fun text(value: String, color: Int, bold: Boolean = false): Component =
        Component.text(value, TextColor.color(color))
            .decoration(TextDecoration.ITALIC, false)
            .decoration(TextDecoration.BOLD, bold)

    override fun close() = dialogs.close()

    private companion object {
        const val BODY = 0xE8DFD2
        const val WHITE = 0xFFFFFF
        const val GOLD = 0xE5BA73
        const val VIOLET = 0xC4A7E7
        const val SUCCESS = 0x9BD48D
        const val DANGER = 0xFF6B61

        val HX = PaperDialogInputId.of("hologram_x")
        val HY = PaperDialogInputId.of("hologram_y")
        val HZ = PaperDialogInputId.of("hologram_z")
        val HYAW = PaperDialogInputId.of("hologram_yaw")
        val HPITCH = PaperDialogInputId.of("hologram_pitch")
        val HSCALE = PaperDialogInputId.of("hologram_scale")
        val HRANGE = PaperDialogInputId.of("hologram_range")
        val RX = PaperDialogInputId.of("roulette_x")
        val RY = PaperDialogInputId.of("roulette_y")
        val RZ = PaperDialogInputId.of("roulette_z")
        val RSPACING = PaperDialogInputId.of("roulette_spacing")
        val RITEM = PaperDialogInputId.of("roulette_item")
        val RWINNER = PaperDialogInputId.of("roulette_winner")
        val RPOINTER_Y = PaperDialogInputId.of("roulette_pointer_y")
        val RPOINTER_SCALE = PaperDialogInputId.of("roulette_pointer_scale")
        val RRANGE = PaperDialogInputId.of("roulette_range")
    }
}
