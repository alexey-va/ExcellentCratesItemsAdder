package ru.ruscrafting.ecia.runtime

import net.kyori.adventure.text.Component
import org.bukkit.command.CommandSender
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
    private var legacyMessages = legacyMessages.toMap()
    @Volatile
    private var renderer: LocalizedMiniMessage

    init {
        synchronizeFiles()
        renderer = prepare(this.legacyMessages)
        renderer.validate(LocaleRequirements(REQUIRED_KEYS, emptySet()))
    }

    fun render(path: String, sender: CommandSender?, values: Map<String, String>): Component =
        renderer.render(path, localeTag(sender), values.mapValues { renderer.literal(it.value) })

    /** Chat presentation shared by player and administrator messages. */
    fun renderPadded(path: String, sender: CommandSender?, values: Map<String, String>): Component =
        Component.newline()
            .append(Component.text(CHAT_INDENT))
            .append(render(path, sender, values))
            .append(Component.newline())

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
        renderer.render(path, localeTag, values.mapValues { renderer.literal(it.value) })

    fun render(path: String): Component = render(path, null, emptyMap())

    fun text(value: Any?): Component = renderer.literal(value)

    /** Reload both catalog files and the legacy Russian overlay atomically. */
    @Synchronized
    fun reload(legacyMessages: Map<String, String>) {
        synchronizeFiles()
        val candidate = prepare(legacyMessages)
        candidate.validate(LocaleRequirements(REQUIRED_KEYS, emptySet()))
        this.legacyMessages = legacyMessages.toMap()
        renderer = candidate
    }

    private fun prepare(legacy: Map<String, String>): LocalizedMiniMessage {
        val russian = ConfigLocaleCatalog(Config(dataRoot, "lang/ru.yml"))
        val english = ConfigLocaleCatalog(Config(dataRoot, "lang/en.yml"))
        return LocalizedMiniMessage(
            catalogs = mapOf(
                "ru" to LegacyOverlayCatalog(russian, legacy),
                "en" to english,
            ),
            defaultLocale = { "ru" },
        )
    }

    private fun synchronizeFiles() {
        Config(dataRoot, "lang/ru.yml").mergeMissingFromBundled("lang/ru.yml")
        Config(dataRoot, "lang/en.yml").mergeMissingFromBundled("lang/en.yml")
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

        /** Keys used by the currently shipped protection and administration flow. */
        @JvmField
        val REQUIRED_KEYS: Set<String> = setOf(
            "protected",
            "edit-enabled",
            "edit-disabled",
            "edit-status-on",
            "edit-status-off",
            "reloaded",
            "player-only",
            "no-permission",
            "usage",
        )
    }
}
