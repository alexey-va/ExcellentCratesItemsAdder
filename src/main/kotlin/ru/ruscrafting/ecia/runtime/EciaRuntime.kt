package ru.ruscrafting.ecia.runtime

import org.bukkit.plugin.java.JavaPlugin
import ru.arc.config.Config
import ru.arc.core.PaperArcRuntime
import ru.arc.core.ScheduledTask
import ru.arc.core.Tasks
import ru.arc.logging.ArcLogging
import ru.arc.logging.LoggingConfigSource
import ru.arc.logging.paper.PaperLoggingPlatform
import ru.arc.observability.RuntimeHealthContribution
import ru.arc.paper.menu.PaperMenuConfiguration
import ru.arc.paper.runtime.PaperPluginRuntime
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

/**
 * Composition root for the addon's shared lifecycle, scheduler and diagnostics.
 *
 * Paper calls [create] and [close] on its primary thread. The registry and
 * durable stores only publish bounded counters to the health probe; the probe
 * never performs filesystem, Bukkit or provider I/O.
 */
class EciaRuntime private constructor(
    private val plugin: JavaPlugin,
) : AutoCloseable {
    private val registrySize = AtomicInteger()
    private val recoveryBacklog = AtomicInteger()
    private val lifecycle: PaperPluginRuntime
    private var menuRuntime: EciaMenuRuntime? = null
    private var localeRuntime: EciaLocale? = null
    private var closed = false

    init {
        PaperArcRuntime.installScheduling(plugin)
        val loggingConfig = Config(plugin.dataFolder.toPath(), "modules/logging.yml")
        ArcLogging.install(
            platform = PaperLoggingPlatform("ECIA", "ExcellentCratesItemsAdder"),
            configSource = LoggingConfigSource { loggingConfig },
        )
        lifecycle = PaperPluginRuntime(plugin, "excellentcrates-itemsadder")
        lifecycle.start("version" to plugin.pluginMeta.version)
        lifecycle.registerHealth("runtime") {
            RuntimeHealthContribution(
                recoveryBacklog = recoveryBacklog.get(),
                schemas = mapOf("runtime" to 1),
                dependencies = mapOf("paper" to true),
            )
        }
        lifecycle.ready("version" to plugin.pluginMeta.version)
        lifecycle.reportHealthEvery(600L)
    }

    /** The canonical lifecycle task scope; all repeating work is owned here. */
    fun tasks() = lifecycle.tasks

    /** The canonical lifecycle health snapshot used by ops integrations. */
    fun snapshot() = lifecycle.snapshot()

    fun updateRegistrySize(size: Int) {
        require(size >= 0) { "Registry size must not be negative" }
        registrySize.set(size)
    }

    fun updateRecoveryBacklog(size: Int) {
        require(size >= 0) { "Recovery backlog must not be negative" }
        recoveryBacklog.set(size)
    }

    /** Install or replace the localized renderer after configuration is loaded. */
    fun installLocale(dataRoot: Path, legacyMessages: Map<String, String>): EciaLocale {
        check(!closed) { "ECIA runtime is closed" }
        return EciaLocale(dataRoot, legacyMessages).also { localeRuntime = it }
    }

    fun locale(): EciaLocale = checkNotNull(localeRuntime) { "ECIA locale has not been installed" }

    /**
     * Install a fully validated future menu generation. Core closes the old
     * sessions on replacement; this holder owns the runtime until shutdown.
     */
    fun installMenu(configuration: PaperMenuConfiguration): EciaMenuRuntime {
        check(!closed) { "ECIA runtime is closed" }
        menuRuntime?.close()
        return EciaMenuRuntime.create(plugin, configuration).also { menuRuntime = it }
    }

    fun menuOrNull(): EciaMenuRuntime? = menuRuntime

    /** Register an owned service so lifecycle shutdown closes it exactly once. */
    fun <T : AutoCloseable> registerService(service: T): T = lifecycle.own(service)

    /** Schedule asynchronous registry reconciliation inside the lifecycle scope. */
    fun scheduleRegistryRefresh(periodTicks: Long, reload: Runnable): ScheduledTask? {
        require(periodTicks > 0) { "Registry refresh period must be positive" }
        return lifecycle.tasks.runTimerAsync(periodTicks, periodTicks) { reload.run() }
    }

    /** Marshal a provider preview dispatch to the primary server thread. */
    fun runSync(action: Runnable): ScheduledTask? = lifecycle.tasks.runSync { action.run() }

    /** Dispatch a configured console command from a primary-thread callback. */
    fun dispatchConsole(command: String): Boolean =
        plugin.server.dispatchCommand(plugin.server.consoleSender, command)

    fun info(message: String, vararg arguments: Any?) = ArcLogging.info(message, *arguments)

    fun warn(message: String, vararg arguments: Any?) = ArcLogging.warn(message, *arguments)

    fun error(message: String, vararg arguments: Any?) = ArcLogging.error(message, *arguments)

    override fun close() {
        if (closed) return
        closed = true
        runCatching { menuRuntime?.close() }
            .onFailure { plugin.logger.log(java.util.logging.Level.WARNING, "Could not close ECIA menus", it) }
        menuRuntime = null
        runCatching { lifecycle.close() }
            .onFailure { plugin.logger.log(java.util.logging.Level.SEVERE, "Could not close ECIA runtime", it) }
        Tasks.reset()
    }

    companion object {
        @JvmStatic
        fun create(plugin: JavaPlugin): EciaRuntime = EciaRuntime(plugin)
    }
}
