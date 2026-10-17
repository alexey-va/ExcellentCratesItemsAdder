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
import ru.arc.paper.api.ArcNativeCleanupService
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.BooleanSupplier

/**
 * Composition root for the addon's shared lifecycle, scheduler and diagnostics.
 *
 * Paper calls [create] and [close] on its primary thread. The registry and
 * durable stores only publish bounded counters to the health probe; the probe
 * never performs filesystem, Bukkit or provider I/O.
 */
class EciaRuntime private constructor(
    private val plugin: JavaPlugin,
    loggingConfig: Config,
    private val storage: ru.arc.core.BoundedAsyncExecutor,
    private val nativeCleanup: ArcNativeCleanupService,
) : AutoCloseable {
    private val registrySize = AtomicInteger()
    private val recoveryBacklog = AtomicInteger()
    private val lifecycle: PaperPluginRuntime
    private var menuRuntime: EciaMenuRuntime? = null
    private var localeRuntime: EciaLocale? = null
    private var closed = false
    private val storageFlushes = java.util.concurrent.CopyOnWriteArrayList<Runnable>()
    private val nativeWork: ru.arc.core.BudgetedTaskQueue
    private val nativeCleanups = mutableListOf<CompletableFuture<Void>>()
    private var acceptingNativeCleanup = true

    init {
        PaperArcRuntime.installScheduling(plugin)
        ArcLogging.install(
            platform = PaperLoggingPlatform("AEC", "ArcExcellentCrates"),
            configSource = LoggingConfigSource { loggingConfig },
        )
        lifecycle = PaperPluginRuntime(plugin, "arc-excellent-crates")
        nativeWork = lifecycle.own(ru.arc.core.BudgetedTaskQueue(lifecycle.tasks))
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
    fun storage() = storage
    fun registerStorageFlush(flush: Runnable) { storageFlushes += flush }
    fun nativeWork() = nativeWork

    /** Services submit detached cursors while native shutdown still owns their handles. */
    fun submitNativeCleanup(owner: String, step: BooleanSupplier): CompletableFuture<Void> {
        check(acceptingNativeCleanup) { "Crate native cleanup admission is closed" }
        val submitted = try { nativeCleanup.submit("arc-excellent-crates:$owner", step) }
            catch (failure: Throwable) { CompletableFuture.failedFuture<Void>(failure) }
        return trackNativeCleanup(submitted)
    }

    /** Retains actual transport shutdown as well as detached entity cleanup. */
    fun trackNativeCleanup(completion: CompletionStage<Void>): CompletableFuture<Void> {
        check(acceptingNativeCleanup) { "Crate native cleanup admission is closed" }
        val actual = CompletableFuture<Void>()
        nativeCleanups += actual
        completion.whenComplete { _, failure ->
            if (failure == null) actual.complete(null) else actual.completeExceptionally(failure)
        }
        // A service may cancel its observation, never the generation's actual completion.
        return actual.copy()
    }

    /** Captured only after all native service closers have submitted their cursors. */
    fun nativeCleanupDrain(): CompletableFuture<Void> {
        check(!acceptingNativeCleanup) { "Crate native services are still open" }
        val pending = nativeCleanups.toList()
        val result = CompletableFuture<Void>()
        CompletableFuture.allOf(*pending.toTypedArray()).whenComplete { _, _ ->
            val failures = pending.mapNotNull { runCatching { it.join() }.exceptionOrNull()?.let { error -> error.cause ?: error } }
            if (failures.isEmpty()) result.complete(null) else {
                val failure = failures.first()
                failures.drop(1).filterNot { it === failure }.forEach(failure::addSuppressed)
                result.completeExceptionally(failure)
            }
        }
        return result
    }

    /** Worker-only terminal snapshot, after accepted storage tasks and sealed native admission. */
    fun flushStorage() {
        var failure: Throwable? = null
        storageFlushes.forEach { flush ->
            try { flush.run() } catch (error: Throwable) {
                if (failure == null) failure = error else if (failure !== error) failure?.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }

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

    fun installPreparedLocale(locale: EciaLocale): EciaLocale {
        check(!closed) { "ECIA runtime is closed" }
        localeRuntime = locale
        return locale
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
        var failure: Throwable? = null
        fun attempt(close: () -> Unit) {
            try { close() } catch (error: Throwable) {
                if (failure == null) failure = error else if (failure !== error) failure?.addSuppressed(error)
            }
        }
        attempt { nativeWork.close() }
        attempt { menuRuntime?.close() }
        menuRuntime = null
        attempt { lifecycle.close() }
        acceptingNativeCleanup = false
        Tasks.reset()
        failure?.let { throw IllegalStateException("Could not close every crate native resource", it) }
    }

    companion object {
        @JvmStatic
        fun create(plugin: JavaPlugin, config: Config, storage: ru.arc.core.BoundedAsyncExecutor,
                   nativeCleanup: ArcNativeCleanupService): EciaRuntime =
            EciaRuntime(plugin, config, storage, nativeCleanup)
    }
}
