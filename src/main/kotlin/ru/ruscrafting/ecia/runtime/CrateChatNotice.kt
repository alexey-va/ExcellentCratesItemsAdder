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
    private const val OUTER_INSET = 0
    private const val SPACING_CODE_POINT = 0xF0F01
    private const val KEY_GLYPH = "\uE531"
    private val gold = TextColor.color(0xFFD66A)
    private val white = TextColor.color(0xFFFFFF)
    private val spacing = PixelSpacing(Key.key("minecraft:default"), SPACING_CODE_POINT)

    fun render(heading: Component?, body: Component): Component {
        val normalizedBody = normalize(body).colorIfAbsent(white)
        val laidOut = DialogTextLayout.layout(normalizedBody, TextAlignment.LEFT, WRAP_WIDTH)
        val bodyComponent = when (laidOut) {
            is TextLayoutResult.Aligned -> laidOut.component
            is TextLayoutResult.Unsupported -> normalizedBody
        }
        val lineCount = when (laidOut) {
            is TextLayoutResult.Aligned -> laidOut.lineCount
            is TextLayoutResult.Unsupported -> bodyComponent.plainLineCount()
        }
        // The icon spans three rows. Spend them on the message before adding a heading.
        val showHeading = heading != null && lineCount < 3
        var content = if (showHeading) {
            normalize(requireNotNull(heading)).colorIfAbsent(gold)
                .append(Component.newline()).append(bodyComponent)
        } else bodyComponent
        repeat((3 - lineCount - if (showHeading) 1 else 0).coerceAtLeast(0)) {
            content = content.append(Component.newline())
        }

        return Component.newline()
            .append(outerPrefix())
            .append(columnIndent())
            .append(indentRows(content))
            .append(Component.newline())
    }

    private fun indentRows(body: Component): Component {
        var newlineCount = 0
        val replacement = TextReplacementConfig.builder()
            .matchLiteral("\n")
            .replacement { _: TextComponent.Builder ->
                newlineCount += 1
                val indent = if (newlineCount == 2) glyphColumn() else columnIndent()
                Component.newline().append(outerPrefix()).append(indent)
            }
            .build()
        return body.replaceText(replacement)
    }

    fun outerPrefix(): Component = spacing.padding(OUTER_INSET).color(white)
        .decoration(TextDecoration.BOLD, false)

    private fun columnIndent(): Component = spacing.padding(26)
        .color(white)
        .decoration(TextDecoration.BOLD, false)

    private fun glyphColumn(): Component = Component.text(KEY_GLYPH, white)
        .font(Key.key("minecraft:default"))
        .decoration(TextDecoration.BOLD, false)
        .append(spacing.padding(3).color(white).decoration(TextDecoration.BOLD, false))

    private fun normalize(component: Component): Component = component
        .decoration(TextDecoration.BOLD, false)
        .children(component.children().map(::normalize))

    private fun Component.plainLineCount(): Int =
        net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
            .serialize(this)
            .count { it == '\n' } + 1
}
