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

    fun render(path: String, sender: CommandSender?, values: Map<String, String>): Component {
        val tag = localeTag(sender)
        return renderer.render(path, tag, renderedValues(path, tag, values))
    }

    /** Chat presentation shared by player and administrator messages. */
    fun renderPadded(path: String, sender: CommandSender?, values: Map<String, String>): Component {
        if (sender is Player && path in PLAYER_NOTICE_KEYS) {
            return CrateChatNotice.render(
                heading = render(NOTICE_HEADING, sender, emptyMap()),
                body = render(path, sender, values),
            )
        }
        return Component.newline()
            .append(chatIndent(sender))
            .append(render(path, sender, values))
            .append(Component.newline())
    }

    /** One padded chat block without blank gaps between its rows. */
    fun renderBlock(sender: CommandSender?, lines: List<Pair<String, Map<String, String>>>): Component =
        lines.foldIndexed(Component.newline()) { index, result, (path, values) ->
            result
                .append(if (index == 0) Component.empty() else Component.newline())
                .append(chatIndent(sender))
                .append(render(path, sender, values))
        }.append(Component.newline())

    private fun chatIndent(sender: CommandSender?): Component =
        if (sender is Player) CrateChatNotice.outerPrefix() else Component.text(CHAT_INDENT)

    /** Explicit locale-tag entry point for non-Bukkit callers and tests. */
    fun renderWithLocale(path: String, localeTag: String, values: Map<String, String>): Component =
        renderer.render(path, localeTag, renderedValues(path, localeTag, values))

    private fun renderedValues(path: String, localeTag: String, values: Map<String, String>): Map<String, Component> {
        val rendered = literalValues(values).toMutableMap()
        if (path == "key.received") {
            val crateName = values["key"]?.let(::crateNameFromKeyDisplayName)
            if (crateName != null) {
                rendered["key"] = renderer.render(
                    "key.received-name",
                    localeTag,
                    literalValues(mapOf("crate" to crateName)),
                )
            }
        }
        return rendered
    }

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
            NOTICE_HEADING,
            OLD_NOTICE_HEADING,
            NEW_NOTICE_HEADING,
        )
        migrateDefaultValue(
            russian,
            "protected",
            OLD_PROTECTED_DEFAULT,
            NEW_PROTECTED_DEFAULT,
        )
        migrateDefaultValue(
            russian,
            "managed.no-key-until-reset",
            OLD_NO_KEY_RESET_RU,
            NEW_NO_KEY_RESET_RU,
        )
        migrateDefaultValue(
            russian,
            "key.received",
            OLD_KEY_RECEIVED_RU,
            NEW_KEY_RECEIVED_RU,
        )
        migrateDefaultValue(russian, "key.received", INLINE_KEY_RECEIVED_RU, NEW_KEY_RECEIVED_RU)
        migrateDefaultValue(russian, "key.periodic-received-one", INLINE_PERIODIC_KEY_RU, NEW_PERIODIC_KEY_RU)
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
        migrateDefaultValue(
            english,
            "managed.no-key-until-reset",
            OLD_NO_KEY_RESET_EN,
            NEW_NO_KEY_RESET_EN,
        )
        migrateDefaultValue(
            english,
            "key.received",
            OLD_KEY_RECEIVED_EN,
            NEW_KEY_RECEIVED_EN,
        )
        migrateDefaultValue(english, "key.received", INLINE_KEY_RECEIVED_EN, NEW_KEY_RECEIVED_EN)
        migrateDefaultValue(english, "key.periodic-received-one", INLINE_PERIODIC_KEY_EN, NEW_PERIODIC_KEY_EN)
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
        private val HIGHLIGHTED_VALUES = setOf(
            "crate", "key", "amount", "next_reset", "daily", "weekly", "owner", "date",
        )
        private const val KEY_CASE_PREFIX = "Ключ от кейса «"
        private const val KEY_CASE_SUFFIX = "»"
        private const val KEY_DISPLAY_SUFFIX = " · ключ"
        private const val OLD_PROTECTED_DEFAULT = "<red>Этот кейс защищён."
        private const val NEW_PROTECTED_DEFAULT = "<red>Этот сундук защищён."
        private const val OLD_NOTICE_HEADING = "Сундучки RusCrafting"
        private const val NEW_NOTICE_HEADING = "Сундуки RusCrafting"
        private const val OLD_NO_KEY_RESET_RU =
            "Бесплатная попытка уже использована.\nСледующая попытка будет доступна:\n<next_reset>"
        private const val NEW_NO_KEY_RESET_RU =
            "Новый бесплатный ключ будет доступен<newline><next_reset>."
        private const val OLD_NO_KEY_RESET_EN =
            "You have used this free opening.\nThe next one is available at:\n<next_reset>"
        private const val NEW_NO_KEY_RESET_EN =
            "Your next free key is available<newline><next_reset>."
        private const val OLD_KEY_RECEIVED_RU =
            "Вы получили ключ: <amount> × <key>.\nИспользуйте его у подходящего сундука."
        private const val INLINE_KEY_RECEIVED_RU = "Вы получили ключ: <amount> × <key>."
        private const val NEW_KEY_RECEIVED_RU = "Вы получили ключ: <amount> ×<newline><key>."
        private const val OLD_KEY_RECEIVED_EN =
            "You received a key: <amount> × <key>.\nUse it at the matching crate."
        private const val INLINE_KEY_RECEIVED_EN = "You received a key: <amount> × <key>."
        private const val NEW_KEY_RECEIVED_EN = "You received a key: <amount> ×<newline><key>."
        private const val INLINE_PERIODIC_KEY_RU = "Получен ключ: <crate>."
        private const val NEW_PERIODIC_KEY_RU = "Получен ключ:<newline><crate>."
        private const val INLINE_PERIODIC_KEY_EN = "Key received: <crate>."
        private const val NEW_PERIODIC_KEY_EN = "Key received:<newline><crate>."
        private val HTML_ENTITY = Regex("&(?:#[0-9]+|#x[0-9a-f]+|[a-z][a-z0-9]+);", RegexOption.IGNORE_CASE)
        private val PLAYER_NOTICE_KEYS = setOf(
            "no-permission",
            "key.received",
            "key.periodic-expired",
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

        /** Paths used by crate notices, administration and issued-key lore. */
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
            "key.received-name",
            "key.periodic-owner",
            "key.periodic-expires",
            "key.next-daily",
            "key.next-weekly",
            "key.expires-daily",
            "key.expires-weekly",
            "placement.no-target",
            "placement.occupied",
            "placement.unavailable",
            "placement.no-pools",
            "placement.failed",
            "placement.placed",
        )

        private fun migrateKnownLegacyDefaults(messages: Map<String, String>): Map<String, String> =
            messages.mapValues { (path, value) ->
                when {
                    path == "protected" && value == OLD_PROTECTED_DEFAULT -> NEW_PROTECTED_DEFAULT
                    path == NOTICE_HEADING && value == OLD_NOTICE_HEADING -> NEW_NOTICE_HEADING
                    path == "managed.no-key-until-reset" && value == OLD_NO_KEY_RESET_RU -> NEW_NO_KEY_RESET_RU
                    path == "key.received" && value in setOf(OLD_KEY_RECEIVED_RU, INLINE_KEY_RECEIVED_RU) -> NEW_KEY_RECEIVED_RU
                    path == "key.periodic-received-one" && value == INLINE_PERIODIC_KEY_RU -> NEW_PERIODIC_KEY_RU
                    else -> value
                }
            }

        /** Only recognize the literal names emitted by our physical-key configs. */
        private fun crateNameFromKeyDisplayName(value: String): String? {
            val caseName = when {
                value.startsWith(KEY_CASE_PREFIX) && value.endsWith(KEY_CASE_SUFFIX) ->
                    value.substring(KEY_CASE_PREFIX.length, value.length - KEY_CASE_SUFFIX.length)
                value.endsWith(KEY_DISPLAY_SUFFIX) -> value.removeSuffix(KEY_DISPLAY_SUFFIX)
                else -> return null
            }
            return caseName.takeIf(String::isNotBlank)
        }
    }
}
