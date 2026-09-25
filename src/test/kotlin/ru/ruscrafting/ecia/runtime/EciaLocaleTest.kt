package ru.ruscrafting.ecia.runtime

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.string.shouldContain
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import java.nio.file.Files

class EciaLocaleTest : FunSpec({
    test("merges bundled locales and migrates only the exact legacy protected default") {
        val root = Files.createTempDirectory("ecia-locale-")
        try {
            val locale = EciaLocale(root, mapOf("protected" to "<gold>Operator override"))

            Files.exists(root.resolve("lang/ru.yml")) shouldBe true
            Files.exists(root.resolve("lang/en.yml")) shouldBe true
            PlainTextComponentSerializer.plainText().serialize(locale.render("protected")) shouldBe
                "Operator override"
            PlainTextComponentSerializer.plainText().serialize(locale.render("protected", null, emptyMap())) shouldBe
                "Operator override"

            locale.reload(mapOf("protected" to "<red>Этот кейс защищён."))
            PlainTextComponentSerializer.plainText().serialize(locale.render("protected")) shouldBe
                "Этот сундук защищён."
            locale.reload(mapOf("protected" to "<gold>Custom protected message"))
            PlainTextComponentSerializer.plainText().serialize(locale.render("protected")) shouldBe
                "Custom protected message"
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("migrates the exact previous crate heading and preserves custom headings") {
        val root = Files.createTempDirectory("ecia-locale-heading-migration-")
        try {
            Files.createDirectories(root.resolve("lang"))
            val russianFile = root.resolve("lang/ru.yml")
            Files.writeString(russianFile, "notice:\n  heading: Сундучки RusCrafting\n")
            val locale = EciaLocale(root, mapOf("notice.heading" to "Сундучки RusCrafting"))

            PlainTextComponentSerializer.plainText().serialize(
                locale.renderWithLocale("notice.heading", "ru-RU", emptyMap()),
            ) shouldBe "Сундуки RusCrafting"
            Files.readString(russianFile) shouldContain "heading: Сундуки RusCrafting"
            Files.readString(russianFile).contains("Сундучки RusCrafting") shouldBe false
            locale.reload(mapOf("notice.heading" to "Личные сундуки"))
            PlainTextComponentSerializer.plainText().serialize(
                locale.renderWithLocale("notice.heading", "ru-RU", emptyMap()),
            ) shouldBe "Личные сундуки"

            val customRoot = Files.createTempDirectory("ecia-locale-custom-heading-")
            try {
                Files.createDirectories(customRoot.resolve("lang"))
                val customFile = customRoot.resolve("lang/ru.yml")
                Files.writeString(customFile, "notice:\n  heading: Личные сундуки\n")
                val customLocale = EciaLocale(customRoot, emptyMap())
                PlainTextComponentSerializer.plainText().serialize(
                    customLocale.renderWithLocale("notice.heading", "ru-RU", emptyMap()),
                ) shouldBe "Личные сундуки"
            } finally {
                customRoot.toFile().deleteRecursively()
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("renders periodic key copy and highlights the case, owner and expiry values") {
        val root = Files.createTempDirectory("ecia-locale-periodic-key-")
        try {
            val locale = EciaLocale(root, emptyMap())
            val one = locale.renderWithLocale(
                "key.periodic-received-one", "ru-RU", mapOf("crate" to "Ежедневный тайник"),
            )
            val both = locale.renderWithLocale(
                "key.periodic-received-both", "ru-RU",
                mapOf("daily" to "Ежедневный тайник", "weekly" to "Недельная реликвия"),
            )
            val owner = locale.renderWithLocale("key.periodic-owner", "ru-RU", mapOf("owner" to "Alex"))
            val expiry = locale.renderWithLocale(
                "key.periodic-expires", "ru-RU", mapOf("date" to "полуночи понедельника"),
            )
            val plain = PlainTextComponentSerializer.plainText()
            val gold = TextColor.color(0xFFD66A)

            plain.serialize(one) shouldBe "Получен ключ: Ежедневный тайник."
            plain.serialize(both) shouldBe "Получены ключи: Ежедневный тайник\nНедельная реликвия"
            plain.serialize(owner) shouldBe "Владелец: Alex"
            plain.serialize(expiry) shouldBe "Действует до полуночи понедельника"
            one.textAndColorRuns().single { it.first == "Ежедневный тайник" }.second shouldBe gold
            both.textAndColorRuns().single { it.first == "Ежедневный тайник" }.second shouldBe gold
            both.textAndColorRuns().single { it.first == "Недельная реликвия" }.second shouldBe gold
            owner.textAndColorRuns().single { it.first == "Alex" }.second shouldBe gold
            expiry.textAndColorRuns().single { it.first == "полуночи понедельника" }.second shouldBe gold

            plain.serialize(locale.renderWithLocale("key.next-daily", "ru-RU", emptyMap())) shouldBe "в полночь"
            plain.serialize(locale.renderWithLocale("key.next-weekly", "ru-RU", emptyMap())) shouldBe
                "в понедельник, в полночь"
            plain.serialize(locale.renderWithLocale("key.expires-daily", "ru-RU", emptyMap())) shouldBe "полуночи"
            plain.serialize(locale.renderWithLocale("key.expires-weekly", "ru-RU", emptyMap())) shouldBe
                "полуночи понедельника"

            val english = locale.renderWithLocale(
                "key.periodic-received-both", "en-US", mapOf("daily" to "Daily Cache", "weekly" to "Weekly Relic"),
            )
            plain.serialize(english) shouldBe "Keys received: Daily Cache\nWeekly Relic"
            plain.serialize(locale.renderWithLocale("key.periodic-owner", "en-US", mapOf("owner" to "Alex"))) shouldBe
                "Owner: Alex"
            plain.serialize(locale.renderWithLocale("key.periodic-expires", "en-US", mapOf("date" to "midnight on Monday"))) shouldBe
                "Valid until midnight on Monday"
            plain.serialize(locale.renderWithLocale("key.next-daily", "en-US", emptyMap())) shouldBe "at midnight"
            plain.serialize(locale.renderWithLocale("key.next-weekly", "en-US", emptyMap())) shouldBe
                "on Monday at midnight"
            plain.serialize(locale.renderWithLocale("key.expires-daily", "en-US", emptyMap())) shouldBe "midnight"
            plain.serialize(locale.renderWithLocale("key.expires-weekly", "en-US", emptyMap())) shouldBe
                "midnight on Monday"
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("renders the English catalog and treats replacement values as literal text") {
        val root = Files.createTempDirectory("ecia-locale-en-")
        try {
            val locale = EciaLocale(root, emptyMap())
            PlainTextComponentSerializer.plainText().serialize(
                locale.renderWithLocale("placement.placed", "en-US", mapOf(
                    "crate" to "<red>case_daily",
                    "model" to "minecraft:chest",
                    "position" to "world 1, 2, 3",
                )),
            ) shouldBe "Crate <red>case_daily placed as minecraft:chest\n   world 1, 2, 3"
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("renders chat messages with vertical padding and a three-space indent") {
        val root = Files.createTempDirectory("ecia-locale-padding-")
        try {
            val locale = EciaLocale(root, emptyMap())
            PlainTextComponentSerializer.plainText().serialize(
                locale.renderPadded("no-permission", null, emptyMap()),
            ) shouldBe "\n   Для этого действия нужно дополнительное разрешение.\nВы можете открыть доступные сундуки.\n"
            PlainTextComponentSerializer.plainText().serialize(
                locale.renderBlock(null, listOf(
                    "no-permission" to emptyMap(),
                    "placement.no-target" to emptyMap(),
                )),
            ) shouldBe "\n   Для этого действия нужно дополнительное разрешение.\nВы можете открыть доступные сундуки.\n   Посмотрите на грань блока, рядом с которой нужно поставить кейс.\n"
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("renders a human reset time phrase in both locales") {
        val root = Files.createTempDirectory("ecia-locale-reset-date-")
        try {
            val locale = EciaLocale(root, emptyMap())
            val english = PlainTextComponentSerializer.plainText().serialize(
                locale.renderWithLocale("managed.no-key-until-reset", "en-US", mapOf("next_reset" to "at midnight")),
            )
            english shouldBe "Your next free key is available\nat midnight."
            english.contains("<reset>") shouldBe false
            english.contains("<next_reset>") shouldBe false

            val russian = PlainTextComponentSerializer.plainText().serialize(
                locale.renderWithLocale("managed.no-key-until-reset", "ru-RU", mapOf("next_reset" to "в понедельник, в полночь")),
            )
            russian shouldBe "Новый бесплатный ключ будет доступен\nв понедельник, в полночь."
            russian.contains("<reset>") shouldBe false
            russian.contains("<next_reset>") shouldBe false
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("migrates only the old reset notice defaults in both language files") {
        val root = Files.createTempDirectory("ecia-locale-reset-migration-")
        try {
            EciaLocale(root, emptyMap())
            fun replaceBundledDefault(file: java.nio.file.Path, current: String, previous: String) {
                val currentYaml = current
                val previousYaml = previous.replace("\n", "\\n")
                val contents = Files.readString(file)
                contents.contains(currentYaml) shouldBe true
                Files.writeString(file, contents.replace(currentYaml, previousYaml))
            }
            val russianFile = root.resolve("lang/ru.yml")
            val englishFile = root.resolve("lang/en.yml")
            replaceBundledDefault(
                russianFile,
                "Новый бесплатный ключ будет доступен<newline><next_reset>.",
                "Бесплатная попытка уже использована.\nСледующая попытка будет доступна:\n<next_reset>",
            )
            replaceBundledDefault(
                englishFile,
                "Your next free key is available<newline><next_reset>.",
                "You have used this free opening.\nThe next one is available at:\n<next_reset>",
            )
            val locale = EciaLocale(root, mapOf(
                "managed.no-key-until-reset" to
                    "Бесплатная попытка уже использована.\nСледующая попытка будет доступна:\n<next_reset>",
            ))
            val reset = "в полночь"

            PlainTextComponentSerializer.plainText().serialize(
                locale.renderWithLocale("managed.no-key-until-reset", "ru-RU", mapOf("next_reset" to reset)),
            ) shouldBe "Новый бесплатный ключ будет доступен\n$reset."
            PlainTextComponentSerializer.plainText().serialize(
                locale.renderWithLocale("managed.no-key-until-reset", "en-US", mapOf("next_reset" to "at midnight")),
            ) shouldBe "Your next free key is available\nat midnight."

            Files.readString(russianFile) shouldContain "Новый бесплатный ключ будет доступен"
            Files.readString(englishFile) shouldContain "Your next free key is available"
            Files.readString(russianFile).contains("Бесплатная попытка уже использована.") shouldBe false
            Files.readString(englishFile).contains("You have used this free opening.") shouldBe false

            locale.reload(mapOf(
                "managed.no-key-until-reset" to "Обратитесь к администратору: <next_reset>",
            ))
            PlainTextComponentSerializer.plainText().serialize(
                locale.renderWithLocale("managed.no-key-until-reset", "ru-RU", mapOf("next_reset" to reset)),
            ) shouldBe "Обратитесь к администратору: $reset"

            val customRoot = Files.createTempDirectory("ecia-locale-reset-custom-")
            try {
                EciaLocale(customRoot, emptyMap())
                replaceBundledDefault(
                    customRoot.resolve("lang/ru.yml"),
                    "Новый бесплатный ключ будет доступен<newline><next_reset>.",
                    "Нужен ключ от сундука:\n<next_reset>",
                )
                replaceBundledDefault(
                    customRoot.resolve("lang/en.yml"),
                    "Your next free key is available<newline><next_reset>.",
                    "A matching key is needed:\n<next_reset>",
                )
                val custom = EciaLocale(customRoot, emptyMap())
                PlainTextComponentSerializer.plainText().serialize(
                    custom.renderWithLocale("managed.no-key-until-reset", "ru-RU", mapOf("next_reset" to reset)),
                ) shouldBe "Нужен ключ от сундука:\n$reset"
                PlainTextComponentSerializer.plainText().serialize(
                    custom.renderWithLocale("managed.no-key-until-reset", "en-US", mapOf("next_reset" to reset)),
                ) shouldBe "A matching key is needed:\n$reset"
            } finally {
                customRoot.toFile().deleteRecursively()
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("migrates prior key-received text from locale files and the legacy reload overlay") {
        val root = Files.createTempDirectory("ecia-locale-key-received-migration-")
        try {
            Files.createDirectories(root.resolve("lang"))
            Files.writeString(root.resolve("lang/ru.yml"), """
                key:
                  received: "Вы получили ключ: <amount> × <key>.\nИспользуйте его у подходящего сундука."
            """.trimIndent())
            Files.writeString(root.resolve("lang/en.yml"), """
                key:
                  received: "You received a key: <amount> × <key>.\nUse it at the matching crate."
            """.trimIndent())
            val locale = EciaLocale(root, emptyMap())
            val plain = PlainTextComponentSerializer.plainText()
            val values = mapOf("amount" to "1", "key" to "Daily Cache")

            plain.serialize(locale.renderWithLocale("key.received", "ru-RU", values)) shouldBe
                "Вы получили ключ: 1 × Daily Cache."
            plain.serialize(locale.renderWithLocale("key.received", "en-US", values)) shouldBe
                "You received a key: 1 × Daily Cache."
            Files.readString(root.resolve("lang/ru.yml")) shouldContain "Вы получили ключ: <amount> × <key>."
            Files.readString(root.resolve("lang/ru.yml")).contains("Используйте его у подходящего сундука.") shouldBe false
            Files.readString(root.resolve("lang/en.yml")) shouldContain "You received a key: <amount> × <key>."
            Files.readString(root.resolve("lang/en.yml")).contains("Use it at the matching crate.") shouldBe false

            locale.reload(mapOf(
                "key.received" to "Вы получили ключ: <amount> × <key>.\nИспользуйте его у подходящего сундука.",
            ))
            plain.serialize(locale.renderWithLocale("key.received", "ru-RU", values)) shouldBe
                "Вы получили ключ: 1 × Daily Cache."
            locale.reload(mapOf("key.received" to "Custom key receipt: <key>"))
            plain.serialize(locale.renderWithLocale("key.received", "ru-RU", values)) shouldBe
                "Custom key receipt: Daily Cache"
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("migrates removed ecia command hints from an existing catalog") {
        val root = Files.createTempDirectory("ecia-locale-migration-")
        try {
            Files.createDirectories(root.resolve("lang"))
            Files.writeString(root.resolve("lang/ru.yml"), "protected: '<red>/ecia edit on'\n")
            val locale = EciaLocale(root, emptyMap())
            PlainTextComponentSerializer.plainText().serialize(locale.render("protected")) shouldBe
                "Этот сундук защищён."
            Files.readString(root.resolve("lang/ru.yml")).contains("/ecia") shouldBe false
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("migrates html-escaped command usage to player-readable notation") {
        val root = Files.createTempDirectory("ecia-locale-usage-migration-")
        try {
            EciaLocale(root, emptyMap())
            val file = root.resolve("lang/ru.yml")
            val broken = Files.readString(file).replace(
                "key [игрок] [ключ] [количество] [сервер]",
                "key &lt;игрок&gt; &lt;ключ&gt; &lt;1-64&gt; &lt;сервер&gt;",
            )
            Files.writeString(file, broken)

            val locale = EciaLocale(root, emptyMap())
            val usage = PlainTextComponentSerializer.plainText().serialize(locale.render("command.usage"))
            usage.contains("&lt;") shouldBe false
            usage.contains("[количество]") shouldBe true
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("rejects html entities in validated player-facing messages") {
        val root = Files.createTempDirectory("ecia-locale-html-validation-")
        try {
            EciaLocale(root, emptyMap())
            val file = root.resolve("lang/ru.yml")
            Files.writeString(
                file,
                Files.readString(file).replace(
                    "Укажите корректный ник Minecraft.",
                    "Ник &lt;игрок&gt; недопустим.",
                ),
            )

            shouldThrow<IllegalArgumentException> { EciaLocale(root, emptyMap()) }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("keeps console padding on administrator output") {
        val root = Files.createTempDirectory("ecia-locale-admin-padding-")
        try {
            val locale = EciaLocale(root, emptyMap())
            val rendered = PlainTextComponentSerializer.plainText().serialize(
                locale.renderPadded("command.usage", null, emptyMap()),
            )

            rendered shouldBe "\n   Использование: /arc-crate, /arc-crate place или /arc-crate key игрок ключ [количество] [сервер].\n"
            rendered.contains("Сундуки RusCrafting") shouldBe false
            rendered.contains("\uE531") shouldBe false
        } finally {
            root.toFile().deleteRecursively()
        }
    }
})

private fun Component.textAndColorRuns(inheritedColor: TextColor? = null): Sequence<Pair<String, TextColor?>> = sequence {
    val effectiveColor = color() ?: inheritedColor
    if (this@textAndColorRuns is TextComponent) yield(this@textAndColorRuns.content() to effectiveColor)
    children().forEach { yieldAll(it.textAndColorRuns(effectiveColor)) }
}
