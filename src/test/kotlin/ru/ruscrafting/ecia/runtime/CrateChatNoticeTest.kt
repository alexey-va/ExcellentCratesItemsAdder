package ru.ruscrafting.ecia.runtime

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import ru.arc.text.PixelSpacing
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Locale

class CrateChatNoticeTest : FunSpec({
    val paths = listOf(
        "no-permission",
        "key.received",
        "key.periodic-received-one",
        "key.periodic-received-both",
        "managed.unavailable",
        "managed.busy",
        "managed.vetoed",
        "managed.no-key",
        "managed.no-key-until-reset",
        "managed.native-command",
        "managed.key-not-consumed",
        "managed.review",
        "managed.pending",
        "managed.operation-refused",
        "managed.inventory-full",
    )

    test("renders all 15 Russian and English notices with the same frame and glyph row") {
        val root = Files.createTempDirectory("ecia-chat-notices-")
        try {
            val locale = EciaLocale(root, emptyMap())
            val values = noticeValues()

            listOf("ru-RU", "en-US").forEach { localeTag ->
                paths.forEach { path ->
                    val nextReset = if (localeTag == "ru-RU") "в полночь" else "at midnight"
                    val replacements = values[path].orEmpty() +
                        if (path == "managed.no-key-until-reset") mapOf("next_reset" to nextReset) else emptyMap()
                    val output = locale.renderPadded(path, player(localeTag), replacements)
                    val plain = PlainTextComponentSerializer.plainText().serialize(output)
                    val visibleRows = plain.trim('\n').split('\n')

                    plain.startsWith("\n") shouldBe true
                    plain.startsWith("\n\n") shouldBe false
                    plain.endsWith("\n") shouldBe true
                    plain.endsWith("\n\n") shouldBe false
                    visibleRows.size shouldBe 3
                    visibleRows.all { !it.startsWith(" ") } shouldBe true
                    if (path in setOf("key.received", "key.periodic-received-one", "key.periodic-received-both")) {
                        val expectedHeading = if (localeTag == "ru-RU") "Сундуки RusCrafting" else "RusCrafting Crates"
                        plain shouldContain expectedHeading
                    }
                    visibleRows[2].contains("\uE531") shouldBe true
                    plain.count { it == '\uE531' } shouldBe 1
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("uses white unbolded glyph and consistent pixel column prefixes") {
        val root = Files.createTempDirectory("ecia-chat-notice-style-")
        try {
            val locale = EciaLocale(root, emptyMap())
            val output = locale.renderPadded("managed.busy", player("ru-RU"), emptyMap())
            val texts = output.descendants().filterIsInstance<TextComponent>().toList()
            val heading = texts.single { it.content() == "Сундуки RusCrafting" }
            val glyph = texts.single { it.content() == "\uE531" }

            heading.style().color() shouldBe TextColor.color(0xFFD66A)
            heading.style().decoration(TextDecoration.BOLD) shouldBe TextDecoration.State.FALSE
            glyph.style().color() shouldBe TextColor.color(0xFFFFFF)
            glyph.style().decoration(TextDecoration.BOLD) shouldBe TextDecoration.State.FALSE
            glyph.style().font() shouldBe Key.key("minecraft:default")

            val spacing = PixelSpacing(Key.key("minecraft:default"), 0xF0F01)
            val plain = PlainTextComponentSerializer.plainText()
            val fullColumn = plain.serialize(spacing.padding(26))
            val glyphColumn = "\uE531" + plain.serialize(spacing.padding(3))
            plain.serialize(output).trim('\n').split('\n').forEachIndexed { index, row ->
                row.startsWith(plain.serialize(spacing.padding(2)) + if (index == 2) glyphColumn else fullColumn) shouldBe true
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("wraps long placeholder text without parsing markup or dropping its words") {
        val root = Files.createTempDirectory("ecia-chat-notice-long-key-")
        try {
            val locale = EciaLocale(root, emptyMap())
            val longKey = "<gold> emerald_key " + (0..24).joinToString(" ") { "segment_$it" }
            val output = locale.renderPadded(
                "key.received",
                player("ru-RU"),
                mapOf("amount" to "64", "key" to longKey),
            )
            val plain = PlainTextComponentSerializer.plainText().serialize(output)
            val bodyRows = plain.trim('\n').split('\n')
            val recovered = bodyRows.joinToString(" ") { row ->
                row.removePrefix("  ").filterNot { it == '\uE531' || it.code == 0xF0F01 }
            }.replace(Regex("\\s+"), " ").trim()

            plain shouldContain "<gold>"
            recovered shouldContain "emerald_key"
            (0..24).forEach { index -> recovered shouldContain "segment_$index" }
            plain.contains("Используйте его у подходящего сундука.") shouldBe false
            bodyRows.all { !it.startsWith(" ") } shouldBe true
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("daily key receipt keeps three rows and highlights literal values across wrapping") {
        val root = Files.createTempDirectory("ecia-chat-key-highlight-")
        try {
            val locale = EciaLocale(root, emptyMap())
            val key = "Ключ от кейса «Ежедневный тайник»"
            val output = locale.renderPadded("key.received", player("ru-RU"),
                mapOf("amount" to "1", "key" to key))
            val plain = PlainTextComponentSerializer.plainText().serialize(output)
            val rows = plain.trim('\n').split('\n')

            rows.size shouldBe 3
            plain shouldContain "Сундуки RusCrafting"
            plain.contains("Используйте его у подходящего сундука.") shouldBe false
            rows[2].contains("\uE531") shouldBe true
            val runs = output.coloredText().toList()
            val goldText = runs.filter { it.second == TextColor.color(0xFFD66A) }.joinToString("") { it.first }
            goldText shouldContain "1"
            goldText shouldContain "Ежедневный тайник"
            val whiteText = runs.filter { it.second == TextColor.color(0xFFFFFF) }.joinToString("") { it.first }
            whiteText shouldContain "Вы получили ключ:"

            val custom = CrateChatNotice.render(null, Component.text("Custom accent", TextColor.color(0x70F0A5)))
            custom.coloredText().single { it.first == "Custom accent" }.second shouldBe TextColor.color(0x70F0A5)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("writes production components for visual review") {
        val root = Files.createTempDirectory("ecia-chat-notice-fixtures-")
        try {
            val locale = EciaLocale(root, emptyMap())
            val russianFile = root.resolve("lang/ru.yml")
            val russianSource = Files.readString(russianFile)
            val ruYaml = YamlConfiguration.loadConfiguration(russianFile.toFile())
            val ruKeys = (listOf("notice.heading") + paths).associateWith { path ->
                requireNotNull(ruYaml.getString(path)) { "Missing fixture locale key $path" }
            }
            val sourceHash = sha256(russianSource)
            val ampersand = LegacyComponentSerializer.builder().character('&').hexColors().build()
            val section = LegacyComponentSerializer.legacySection()
            val samples = buildList {
                paths.forEach { path ->
                    add(FixtureSample(path.replace('.', '-') , path, valuesFor(path), "ru-RU"))
                }
                add(FixtureSample(
                    "key-received-long-literal",
                    "key.received",
                    mapOf("amount" to "64", "key" to "<gold> emerald_key " + (0..24).joinToString(" ") { "segment_$it" }),
                    "ru-RU",
                ))
            }
            val gsonComponents = GsonComponentSerializer.gson()
            val outputFile = Path.of("build/reports/crate-chat-notices.json")
            Files.createDirectories(outputFile.parent)
            val json = samples.joinToString(prefix = "[\n", postfix = "\n]\n", separator = ",\n") { sample ->
                val component = locale.renderPadded(sample.key, player(sample.locale), sample.values)
                val plain = PlainTextComponentSerializer.plainText().serialize(component)
                """
                {
                  "id": ${sample.id.jsonQuoted()},
                  "locale": ${sample.locale.jsonQuoted()},
                  "key": ${sample.key.jsonQuoted()},
                  "values": ${sample.values.toJsonObject()},
                  "legacy": ${section.serialize(component).jsonQuoted()},
                  "legacyAmpersand": ${ampersand.serialize(component).jsonQuoted()},
                  "plain": ${plain.jsonQuoted()},
                  "component": ${gsonComponents.serialize(component)},
                  "sourceSha256": ${sourceHash.jsonQuoted()},
                  "ruKeys": ${ruKeys.toJsonObject()}
                }
                """.trimIndent()
            }
            Files.writeString(outputFile, json)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
})

private data class FixtureSample(
    val id: String,
    val key: String,
    val values: Map<String, String>,
    val locale: String,
)

private fun noticeValues(): Map<String, Map<String, String>> = mapOf(
    "key.received" to mapOf("amount" to "1", "key" to "Ключ от кейса «Ежедневный тайник»"),
    "key.periodic-received-one" to mapOf("crate" to "Ежедневный тайник"),
    "key.periodic-received-both" to mapOf("daily" to "Ежедневный тайник", "weekly" to "Недельная реликвия"),
    "managed.no-key-until-reset" to mapOf("next_reset" to "в полночь"),
)

private fun valuesFor(path: String): Map<String, String> = noticeValues()[path].orEmpty()

private fun player(localeTag: String): Player {
    val locale = Locale.forLanguageTag(localeTag)
    return Proxy.newProxyInstance(
        Player::class.java.classLoader,
        arrayOf(Player::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            "locale" -> locale
            "toString" -> "PlayerProxy($localeTag)"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            else -> throw UnsupportedOperationException("Unexpected Player method in locale test: ${method.name}")
        }
    } as Player
}

private fun Component.descendants(): Sequence<Component> = sequence {
    yield(this@descendants)
    children().forEach { yieldAll(it.descendants()) }
}

private fun Component.coloredText(inherited: TextColor? = null): Sequence<Pair<String, TextColor?>> = sequence {
    val effective = color() ?: inherited
    if (this@coloredText is TextComponent) yield(content() to effective)
    children().forEach { yieldAll(it.coloredText(effective)) }
}

private fun Map<String, String>.toJsonObject(): String = entries.joinToString(
    prefix = "{",
    postfix = "}",
    separator = ",",
) { (key, value) -> "${key.jsonQuoted()}:${value.jsonQuoted()}" }

private fun String.jsonQuoted(): String = buildString {
    append('"')
    this@jsonQuoted.forEach { char ->
        when (char) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
        }
    }
    append('"')
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
