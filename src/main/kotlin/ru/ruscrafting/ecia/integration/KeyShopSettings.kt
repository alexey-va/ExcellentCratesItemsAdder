package ru.ruscrafting.ecia.integration

import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.io.StringReader

/** Validated operator settings for the public key seller. */
internal data class KeyShopSettings(
    val enabled: Boolean,
    val world: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val maxDistance: Double,
    val prices: Map<String, Long>,
) {
    companion object {
        val SUPPORTED_CASES = listOf(
            "case_daily", "case_weekly", "case_rank_artisan", "case_rank_knight",
            "case_furniture", "case_collections", "case_mounts",
        )

        /** Merge bundled defaults forward without replacing existing operator values. */
        fun load(file: File, bundledDefaults: String): KeyShopSettings {
            file.parentFile?.mkdirs()
            val defaults = YamlConfiguration.loadConfiguration(StringReader(bundledDefaults))
            val config = YamlConfiguration.loadConfiguration(file)
            config.addDefaults(defaults)
            config.options().copyDefaults(true)
            config.save(file)
            return read(config)
        }

        fun read(config: ConfigurationSection): KeyShopSettings {
            val enabledValue = config.get("enabled")
            require(enabledValue is Boolean) { "enabled must be a boolean" }
            val world = config.getString("world")?.trim().orEmpty()
            require(world.matches(Regex("[A-Za-z0-9_:-]{1,64}"))) { "world must be a simple world name" }
            val anchor = requireNotNull(config.getConfigurationSection("anchor")) { "anchor section is required" }
            val x = finite(anchor, "x")
            val y = finite(anchor, "y")
            val z = finite(anchor, "z")
            val distance = finite(config, "max-distance")
            require(distance in 1.0..32.0) { "max-distance must be between 1 and 32 blocks" }

            val offers = requireNotNull(config.getConfigurationSection("offers")) { "offers section is required" }
            val prices = LinkedHashMap<String, Long>()
            for (id in offers.getKeys(false)) {
                require(id in SUPPORTED_CASES) { "unsupported crate in offers: $id" }
                val raw = offers.get(id)
                require(raw is Number) { "offers.$id must be a whole token price" }
                val value = raw.toDouble()
                require(value.isFinite() && value >= 1.0 && value <= MAX_PRICE && value % 1.0 == 0.0) {
                    "offers.$id must be a positive whole token price no greater than $MAX_PRICE"
                }
                prices[id] = value.toLong()
            }
            if (enabledValue && prices.keys != SUPPORTED_CASES.toSet()) {
                throw IllegalArgumentException("enabled key shop requires prices for all seven supported crates")
            }
            return KeyShopSettings(enabledValue, world, x, y, z, distance, prices.toMap())
        }

        private fun finite(section: ConfigurationSection, path: String): Double {
            val raw = section.get(path)
            require(raw is Number) { "$path must be numeric" }
            return raw.toDouble().also { require(it.isFinite()) { "$path must be finite" } }
        }

        private const val MAX_PRICE = 1_000_000.0
    }
}
