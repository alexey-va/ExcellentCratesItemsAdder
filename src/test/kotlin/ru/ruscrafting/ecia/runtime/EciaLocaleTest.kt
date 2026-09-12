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
                locale.renderWithLocale("reloaded", "en-US", mapOf("count" to "<red>7")),
            ) shouldBe "Crate positions reloaded: <red>7"
        } finally {
            root.toFile().deleteRecursively()
        }
    }
})
