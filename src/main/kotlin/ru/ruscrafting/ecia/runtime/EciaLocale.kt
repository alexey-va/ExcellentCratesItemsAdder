package ru.ruscrafting.ecia.runtime

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import ru.arc.config.Config
import ru.arc.text.ConfigLocaleCatalog
import ru.arc.text.LocaleCatalog
import ru.arc.text.LocaleRequirements
import ru.arc.text.LocalizedMiniMessage
import java.nio.file.Path

/**
 * Localized message boundary for ECIA. New keys are merged into both language
 * files; legacy `config.yml/messages` values remain authoritative for Russian
 * so existing operator customizations survive the migration.
 */
class EciaLocale(
    private val dataRoot: Path,
    legacyMessages: Map<String, String>,
) {
    private var legacyMessages = migrateKnownLegacyDefaults(legacyMessages)
    @Volatile
    private var renderer: LocalizedMiniMessage

    init {
        synchronizeFiles()
        renderer = prepare(this.legacyMessages)
        renderer.validate(LocaleRequirements(REQUIRED_KEYS, emptySet()))
    }

    fun render(path: String, sender: CommandSender?, values: Map<String, String>): Component =
        renderer.render(path, localeTag(sender), literalValues(values))

    /** Chat presentation shared by player and administrator messages. */
    fun renderPadded(path: String, sender: CommandSender?, values: Map<String, String>): Component {
        if (sender is Player && path in PLAYER_NOTICE_KEYS) {
            return CrateChatNotice.render(
                heading = if (path == "key.received") null else render(NOTICE_HEADING, sender, emptyMap()),
                body = render(path, sender, values),
            )
        }
        return Component.newline()
            .append(Component.text(CHAT_INDENT))
            .append(render(path, sender, values))
            .append(Component.newline())
    }

    /** One padded chat block without blank gaps between its rows. */
    fun renderBlock(sender: CommandSender?, lines: List<Pair<String, Map<String, String>>>): Component =
        lines.foldIndexed(Component.newline()) { index, result, (path, values) ->
            result
                .append(if (index == 0) Component.empty() else Component.newline())
                .append(Component.text(CHAT_INDENT))
                .append(render(path, sender, values))
        }.append(Component.newline())

    /** Explicit locale-tag entry point for non-Bukkit callers and tests. */
    fun renderWithLocale(path: String, localeTag: String, values: Map<String, String>): Component =
        renderer.render(path, localeTag, literalValues(values))

    private fun literalValues(values: Map<String, String>): Map<String, Component> =
        values.mapValues { (key, value) ->
            val literal = renderer.literal(value)
            if (key in HIGHLIGHTED_VALUES) literal.color(TextColor.color(0xFFD66A)) else literal
        }

    fun render(path: String): Component = render(path, null, emptyMap())

    fun text(value: Any?): Component = renderer.literal(value)

    /** Reload both catalog files and the legacy Russian overlay atomically. */
    @Synchronized
    fun reload(legacyMessages: Map<String, String>) {
        synchronizeFiles()
        val migratedLegacyMessages = migrateKnownLegacyDefaults(legacyMessages)
        val candidate = prepare(migratedLegacyMessages)
        candidate.validate(LocaleRequirements(REQUIRED_KEYS, emptySet()))
        this.legacyMessages = migratedLegacyMessages
        renderer = candidate
    }

    private fun prepare(legacy: Map<String, String>): LocalizedMiniMessage {
        val russian = ConfigLocaleCatalog(Config(dataRoot, "lang/ru.yml"))
        val english = ConfigLocaleCatalog(Config(dataRoot, "lang/en.yml"))
        val russianWithLegacy = LegacyOverlayCatalog(russian, legacy)
        rejectHtmlEntities("ru", "lang/ru.yml", legacy)
        rejectHtmlEntities("en", "lang/en.yml")
        return LocalizedMiniMessage(
            catalogs = mapOf(
                "ru" to russianWithLegacy,
                "en" to english,
            ),
            defaultLocale = { "ru" },
        )
    }

    private fun synchronizeFiles() {
        val russian = Config(dataRoot, "lang/ru.yml")
        russian.mergeMissingFromBundled("lang/ru.yml")
        migrateDefaultValue(
            russian,
            "protected",
            OLD_PROTECTED_DEFAULT,
            NEW_PROTECTED_DEFAULT,
        )
        migrateRemovedCommand(russian, mapOf(
            "protected" to NEW_PROTECTED_DEFAULT,
            "managed.native-command" to "<#ffd567>Откройте кейс ключом на его пьедестале.\n   <#fff2df>Призы и ключи учитывает аддон.",
            "managed.pending" to "<#ffd567>Есть незавершённое открытие.\n   <#fff2df>Нажмите ПКМ по кейсу, чтобы продолжить.",
            "managed.operation-refused" to "<#ffcc80>Действие не завершено.\n   <#fff2df>Нажмите ПКМ по кейсу, чтобы продолжить.",
            "managed.inventory-full" to "<#ffcc80>В инвентаре не хватает места.\n   <#fff2df>Освободите слоты и снова нажмите ПКМ по кейсу.",
        ))
        migrateBrokenUsage(
            russian,
            "<#ffd567>Использование: <white>/arc-crate</white>, <white>/arc-crate place</white> или <white>/arc-crate key игрок ключ [количество] [сервер]</white>.",
        )
        val english = Config(dataRoot, "lang/en.yml")
        english.mergeMissingFromBundled("lang/en.yml")
        migrateRemovedCommand(english, mapOf(
            "protected" to "<red>This crate is protected.",
            "managed.native-command" to "<#ffd567>Use the key on this crate pedestal. The addon tracks keys and rewards.",
            "managed.pending" to "<#ffd567>You have an unfinished opening. Right-click a crate to continue.",
            "managed.operation-refused" to "<#ffcc80>The action did not finish. Right-click a crate to continue.",
            "managed.inventory-full" to "<#ffcc80>Your inventory is full. Free some slots and right-click a crate again.",
        ))
        migrateBrokenUsage(
            english,
            "<#ffd567>Usage: <white>/arc-crate</white>, <white>/arc-crate place</white>, or <white>/arc-crate key player key [amount] [server]</white>.",
        )
    }

    private fun migrateRemovedCommand(config: Config, replacements: Map<String, String>) {
        var changed = false
        replacements.forEach { (path, replacement) ->
            if (config.string(path).contains("/ecia", ignoreCase = true)) {
                config.setString(path, replacement)
                changed = true
            }
        }
        if (changed) config.save()
    }

    private fun migrateDefaultValue(config: Config, path: String, previous: String, replacement: String) {
        if (config.string(path) == previous) {
            config.setString(path, replacement)
            config.save()
        }
    }

    private fun migrateBrokenUsage(config: Config, replacement: String) {
        if (HTML_ENTITY.containsMatchIn(config.string("command.usage"))) {
            config.setString("command.usage", replacement)
            config.save()
        }
    }

    private fun rejectHtmlEntities(locale: String, relativePath: String, overlay: Map<String, String> = emptyMap()) {
        val yaml = YamlConfiguration.loadConfiguration(dataRoot.resolve(relativePath).toFile())
        yaml.getValues(true).forEach { (path, value) -> rejectHtmlEntities(locale, path, value) }
        overlay.forEach { (path, value) -> rejectHtmlEntities(locale, path, value) }
    }

    private fun rejectHtmlEntities(locale: String, path: String, value: Any?) {
        when (value) {
            is String -> require(!HTML_ENTITY.containsMatchIn(value)) {
                "Locale $locale contains an HTML entity at $path; use player-readable text or MiniMessage"
            }
            is Iterable<*> -> value.forEachIndexed { index, entry -> rejectHtmlEntities(locale, "$path[$index]", entry) }
            is Map<*, *> -> value.forEach { (key, entry) -> rejectHtmlEntities(locale, "$path.$key", entry) }
        }
    }

    private fun localeTag(sender: CommandSender?): String =
        if (sender is Player) sender.locale().toLanguageTag() else "ru"

    private class LegacyOverlayCatalog(
        private val modern: LocaleCatalog,
        private val legacy: Map<String, String>,
    ) : LocaleCatalog {
        override fun scalar(path: String): String? = legacy[path] ?: modern.scalar(path)

        override fun lines(path: String): List<String>? = modern.lines(path)
    }

    companion object {
        private const val CHAT_INDENT = "   "
        private const val NOTICE_HEADING = "notice.heading"
        private val HIGHLIGHTED_VALUES = setOf("crate", "key", "amount", "next_reset")
        private const val OLD_PROTECTED_DEFAULT = "<red>Этот кейс защищён."
        private const val NEW_PROTECTED_DEFAULT = "<red>Этот сундук защищён."
        private val HTML_ENTITY = Regex("&(?:#[0-9]+|#x[0-9a-f]+|[a-z][a-z0-9]+);", RegexOption.IGNORE_CASE)
        private val PLAYER_NOTICE_KEYS = setOf(
            "no-permission",
            "key.received",
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

        /** Keys used by the currently shipped protection and administration flow. */
        @JvmField
        val REQUIRED_KEYS: Set<String> = PLAYER_NOTICE_KEYS + setOf(
            NOTICE_HEADING,
            "protected",
            "command.player-only",
            "command.source-only",
            "command.usage",
            "key.no-keys",
            "key.unavailable",
            "key.no-backends",
            "key.invalid-player",
            "key.invalid-amount",
            "key.invalid-server",
            "key.player-not-here",
            "key.unknown",
            "key.dispatch-failed",
            "key.sent",
            "placement.no-target",
            "placement.occupied",
            "placement.unavailable",
            "placement.no-pools",
            "placement.failed",
            "placement.placed",
        )

        private fun migrateKnownLegacyDefaults(messages: Map<String, String>): Map<String, String> =
            messages.mapValues { (path, value) ->
                if (path == "protected" && value == OLD_PROTECTED_DEFAULT) NEW_PROTECTED_DEFAULT else value
            }
    }
}
