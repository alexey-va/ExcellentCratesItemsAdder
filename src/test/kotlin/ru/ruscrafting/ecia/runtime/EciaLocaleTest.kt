package ru.ruscrafting.ecia.runtime

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import java.nio.file.Files

class EciaLocaleTest : FunSpec({
    test("merges bundled locales and keeps legacy Russian overrides") {
        val root = Files.createTempDirectory("ecia-locale-")
        try {
            val locale = EciaLocale(root, mapOf("protected" to "<gold>Operator override"))

            Files.exists(root.resolve("lang/ru.yml")) shouldBe true
            Files.exists(root.resolve("lang/en.yml")) shouldBe true
            PlainTextComponentSerializer.plainText().serialize(locale.render("protected")) shouldBe
                "Operator override"
            PlainTextComponentSerializer.plainText().serialize(locale.render("protected", null, emptyMap())) shouldBe
                "Operator override"
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
            ) shouldBe "\n   Недостаточно прав.\n"
            PlainTextComponentSerializer.plainText().serialize(
                locale.renderBlock(null, listOf(
                    "no-permission" to emptyMap(),
                    "placement.no-target" to emptyMap(),
                )),
            ) shouldBe "\n   Недостаточно прав.\n   Посмотрите на грань блока, рядом с которой нужно поставить кейс.\n"
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
                "Этот кейс защищён."
            Files.readString(root.resolve("lang/ru.yml")).contains("/ecia") shouldBe false
        } finally {
            root.toFile().deleteRecursively()
        }
    }
})
