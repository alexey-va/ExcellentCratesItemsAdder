package ru.ruscrafting.ecia.runtime

import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.TextReplacementConfig
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import ru.arc.paper.menu.DialogTextLayout
import ru.arc.text.PixelSpacing
import ru.arc.text.TextAlignment
import ru.arc.text.TextLayoutResult

/** Three-row, player-only chat notice composition for managed crate feedback. */
internal object CrateChatNotice {
    private const val WRAP_WIDTH = 253
    private const val OUTER_PREFIX = "  "
    private const val SPACING_CODE_POINT = 0xF0F01
    private const val KEY_GLYPH = "\uE531"
    private val gold = TextColor.color(0xFFD66A)
    private val white = TextColor.color(0xFFFFFF)
    private val spacing = PixelSpacing(Key.key("minecraft:default"), SPACING_CODE_POINT)

    fun render(heading: Component, body: Component): Component {
        val laidOut = DialogTextLayout.layout(normalize(body, white), TextAlignment.LEFT, WRAP_WIDTH)
        val bodyComponent = when (laidOut) {
            is TextLayoutResult.Aligned -> laidOut.component
            is TextLayoutResult.Unsupported -> normalize(body, white)
        }
        val lineCount = when (laidOut) {
            is TextLayoutResult.Aligned -> laidOut.lineCount
            is TextLayoutResult.Unsupported -> bodyComponent.plainLineCount()
        }
        val twoRowBody = if (lineCount < 2) bodyComponent.append(Component.newline()) else bodyComponent

        return Component.newline()
            .append(outerPrefix())
            .append(columnIndent())
            .append(normalize(heading, gold))
            .append(Component.newline())
            .append(outerPrefix())
            .append(columnIndent())
            .append(indentBodyRows(twoRowBody))
            .append(Component.newline())
    }

    private fun indentBodyRows(body: Component): Component {
        var newlineCount = 0
        val replacement = TextReplacementConfig.builder()
            .matchLiteral("\n")
            .replacement { _: TextComponent.Builder ->
                newlineCount += 1
                val indent = if (newlineCount == 1) glyphColumn() else columnIndent()
                Component.newline().append(outerPrefix()).append(indent)
            }
            .build()
        return body.replaceText(replacement)
    }

    private fun outerPrefix(): Component = Component.text(OUTER_PREFIX, white)
        .decoration(TextDecoration.BOLD, false)

    private fun columnIndent(): Component = spacing.padding(27)
        .color(white)
        .decoration(TextDecoration.BOLD, false)

    private fun glyphColumn(): Component = Component.text(KEY_GLYPH, white)
        .font(Key.key("minecraft:default"))
        .decoration(TextDecoration.BOLD, false)
        .append(spacing.padding(4).color(white).decoration(TextDecoration.BOLD, false))

    private fun normalize(component: Component, color: TextColor): Component = component
        .color(color)
        .decoration(TextDecoration.BOLD, false)
        .children(component.children().map { child -> normalize(child, color) })

    private fun Component.plainLineCount(): Int =
        net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
            .serialize(this)
            .count { it == '\n' } + 1
}
