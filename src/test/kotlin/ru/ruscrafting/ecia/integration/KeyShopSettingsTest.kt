package ru.ruscrafting.ecia.integration

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.configuration.file.YamlConfiguration
import java.io.StringReader
import java.nio.file.Files

class KeyShopSettingsTest : FunSpec({
    test("bundled sale defaults stay disabled and expose the approved whole-token grid") {
        val directory = Files.createTempDirectory("ecia-key-shop-defaults-")
        try {
            val bundled = checkNotNull(javaClass.getResource("/key-shop.yml")).readText()
            val settings = KeyShopSettings.load(directory.resolve("key-shop.yml").toFile(), bundled)

            settings.enabled shouldBe false
            settings.world shouldBe "rc_origin_spawn"
            settings.x shouldBe -8.5
            settings.y shouldBe 70.0
            settings.z shouldBe -3.5
            settings.maxDistance shouldBe 6.0
            settings.prices shouldBe mapOf(
                "case_daily" to 3L,
                "case_weekly" to 9L,
                "case_rank_artisan" to 12L,
                "case_rank_knight" to 15L,
                "case_furniture" to 30L,
                "case_collections" to 45L,
                "case_mounts" to 60L,
            )
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    test("reload merges new defaults without replacing operator prices") {
        val directory = Files.createTempDirectory("ecia-key-shop-merge-")
        try {
            val file = directory.resolve("key-shop.yml")
            Files.writeString(file, """
                enabled: false
                world: rc_origin_spawn
                anchor:
                  x: -8.5
                  y: 70.0
                  z: -3.5
                max-distance: 6.0
                offers:
                  case_daily: 5
            """.trimIndent())
            val bundled = checkNotNull(javaClass.getResource("/key-shop.yml")).readText()

            val settings = KeyShopSettings.load(file.toFile(), bundled)

            settings.prices["case_daily"] shouldBe 5L
            settings.prices["case_weekly"] shouldBe 9L
            YamlConfiguration.loadConfiguration(file.toFile()).getBoolean("enabled") shouldBe false
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    test("enabled configuration must define a whole-token price for every supported crate") {
        val yaml = YamlConfiguration.loadConfiguration(StringReader("""
            enabled: true
            world: rc_origin_spawn
            anchor:
              x: -8.5
              y: 70.0
              z: -3.5
            max-distance: 6.0
            offers:
              case_daily: 3
        """.trimIndent()))

        runCatching { KeyShopSettings.read(yaml) }.isFailure shouldBe true
    }
})
