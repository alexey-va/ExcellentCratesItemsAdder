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
import ru.arc.paper.menu.PaperDialogTextInput
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

    private fun openHologram(player: Player, target: CrateVisualTarget) {
        val anchor = CrateVisualSettingsStore.Anchor.of(target.anchor())
        val value = store.get(anchor).hologram()
        dialogs.open(player, PaperDialogScreen(
            id = "arc-excellent-crates.visuals.hologram",
            title = text("Голограмма · ${target.crateId()}", VIOLET, bold = true),
            body = listOf(
                body("Каждое нажатие − или + сразу меняет голограмму в мире."),
                body("Положение считается от центра верхней грани сундука."),
            ),
            buttons = listOf(
                button("hologram_text", "Текст ›", GOLD) { openHologramText(it.player, target) },
                hologramFieldButton(target, HologramField.X, value),
                hologramFieldButton(target, HologramField.Y, value),
                hologramFieldButton(target, HologramField.Z, value),
                hologramFieldButton(target, HologramField.YAW, value),
                hologramFieldButton(target, HologramField.PITCH, value),
                hologramFieldButton(target, HologramField.SCALE, value),
                hologramFieldButton(target, HologramField.RANGE, value),
            ),
            exitButton = backButton(),
            columns = 2,
        ))
    }

    private fun openHologramText(player: Player, target: CrateVisualTarget, applied: Boolean = false) {
        val anchor = CrateVisualSettingsStore.Anchor.of(target.anchor())
        val value = store.get(anchor).hologram()
        dialogs.open(player, PaperDialogScreen(
            id = "arc-excellent-crates.visuals.hologram.text",
            title = text("Текст голограммы", VIOLET, bold = true),
            body = listOf(body(if (applied) "Текст сразу обновлён в мире." else "%crate_name% подставляет название этого кейса.", if (applied) SUCCESS else BODY)),
            inputs = listOf(PaperDialogTextInput(
                id = HTEXT,
                label = text("MiniMessage-шаблон", WHITE),
                initial = value.textTemplate(),
                width = 420,
                maxLength = 512,
            )),
            buttons = listOf(button("apply_hologram_text", "Применить текст", SUCCESS) { context ->
                val current = store.get(anchor)
                val template = context.text(HTEXT) ?: current.hologram().textTemplate()
                val hologram = replaceHologram(current.hologram(), textTemplate = template)
                store.save(anchor, CrateVisualSettingsStore.Visuals(hologram, current.roulette()))
                changed.accept(anchor)
                openHologramText(context.player, target, applied = true)
            }),
            exitButton = backButton(),
            columns = 1,
        ))
    }

    private fun hologramFieldButton(
        target: CrateVisualTarget,
        field: HologramField,
        hologram: CrateVisualSettingsStore.Hologram,
    ) = button("hologram_${field.key}", "${field.label}: ${field.format(readHologram(hologram, field))} ›", VIOLET) {
        openHologramField(it.player, target, field)
    }

    private fun openHologramField(player: Player, target: CrateVisualTarget, field: HologramField) {
        val anchor = CrateVisualSettingsStore.Anchor.of(target.anchor())
        val value = readHologram(store.get(anchor).hologram(), field)
        dialogs.open(player, PaperDialogScreen(
            id = "arc-excellent-crates.visuals.hologram.${field.key}",
            title = text(field.label, VIOLET, bold = true),
            body = listOf(
                body("Сейчас: ${field.format(value)}", WHITE),
                body("Изменение применяется сразу. Малый шаг: ${field.formatStep(field.smallStep)}, большой: ${field.formatStep(field.largeStep)}."),
            ),
            buttons = listOf(
                adjustmentButton(target, field, -field.largeStep, "− ${field.formatStep(field.largeStep)}"),
                adjustmentButton(target, field, -field.smallStep, "− ${field.formatStep(field.smallStep)}"),
                adjustmentButton(target, field, field.smallStep, "+ ${field.formatStep(field.smallStep)}"),
                adjustmentButton(target, field, field.largeStep, "+ ${field.formatStep(field.largeStep)}"),
            ),
            exitButton = backButton(),
            columns = 2,
        ))
    }

    private fun adjustmentButton(target: CrateVisualTarget, field: HologramField, delta: Double, label: String) =
        button("h_${field.key}_${if (delta < 0) "m" else "p"}_${if (kotlin.math.abs(delta) == field.smallStep) "s" else "l"}", label, if (delta < 0) DANGER else SUCCESS) { context ->
            val anchor = CrateVisualSettingsStore.Anchor.of(target.anchor())
            val current = store.get(anchor)
            val old = readHologram(current.hologram(), field)
            val next = (old + delta).coerceIn(field.minimum, field.maximum)
            val hologram = replaceHologram(current.hologram(), field = field, number = next)
            store.save(anchor, CrateVisualSettingsStore.Visuals(hologram, current.roulette()))
            changed.accept(anchor)
            openHologramField(context.player, target, field)
        }

    private fun readHologram(value: CrateVisualSettingsStore.Hologram, field: HologramField): Double = when (field) {
        HologramField.X -> value.offsetX()
        HologramField.Y -> value.offsetY()
        HologramField.Z -> value.offsetZ()
        HologramField.YAW -> value.yaw().toDouble()
        HologramField.PITCH -> value.pitch().toDouble()
        HologramField.SCALE -> value.scale().toDouble()
        HologramField.RANGE -> value.viewRange().toDouble()
    }

    private fun replaceHologram(
        value: CrateVisualSettingsStore.Hologram,
        textTemplate: String = value.textTemplate(),
        field: HologramField? = null,
        number: Double = 0.0,
    ): CrateVisualSettingsStore.Hologram = CrateVisualSettingsStore.Hologram(
        textTemplate,
        if (field == HologramField.X) number else value.offsetX(),
        if (field == HologramField.Y) number else value.offsetY(),
        if (field == HologramField.Z) number else value.offsetZ(),
        (if (field == HologramField.YAW) number else value.yaw().toDouble()).toFloat(),
        (if (field == HologramField.PITCH) number else value.pitch().toDouble()).toFloat(),
        (if (field == HologramField.SCALE) number else value.scale().toDouble()).toFloat(),
        (if (field == HologramField.RANGE) number else value.viewRange().toDouble()).toFloat(),
    )

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

        val HTEXT = PaperDialogInputId.of("hologram_text")
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

    private enum class HologramField(
        val key: String,
        val label: String,
        val minimum: Double,
        val maximum: Double,
        val smallStep: Double,
        val largeStep: Double,
        private val decimals: Int,
    ) {
        X("x", "Смещение X", -8.0, 8.0, .05, .5, 2),
        Y("y", "Смещение Y", -4.0, 8.0, .05, .5, 2),
        Z("z", "Смещение Z", -8.0, 8.0, .05, .5, 2),
        YAW("yaw", "Поворот", -180.0, 180.0, 1.0, 15.0, 0),
        PITCH("pitch", "Наклон", -90.0, 90.0, 1.0, 10.0, 0),
        SCALE("scale", "Размер", .1, 10.0, .05, .5, 2),
        RANGE("range", "Дальность", .1, 64.0, .5, 5.0, 1),
        ;

        fun format(value: Double): String = "% .${decimals}f".format(java.util.Locale.ROOT, value).trim()
        fun formatStep(value: Double): String = format(value)
    }
}
