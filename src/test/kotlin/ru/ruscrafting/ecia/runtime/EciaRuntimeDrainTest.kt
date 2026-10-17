package ru.ruscrafting.ecia.runtime

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import ru.arc.config.Config
import ru.arc.core.BoundedAsyncExecutor
import ru.arc.paper.api.ArcNativeCleanupService
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.function.BooleanSupplier

class EciaRuntimeDrainTest : FreeSpec({
    "native entity and transport completion remain actual drain work after an observer cancellation" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("CrateRuntimeDrain")
            val entities = CompletableFuture<Void>()
            val transport = CompletableFuture<Void>()
            val cleanup = object : ArcNativeCleanupService {
                override fun submit(owner: String, step: BooleanSupplier): CompletionStage<Void> = entities
            }
            val worker = BoundedAsyncExecutor("crate-runtime-drain-test")
            val runtime = EciaRuntime.create(plugin, mockk<Config>(), worker, cleanup)
            lateinit var observer: CompletableFuture<Void>
            runtime.registerService(AutoCloseable { observer = runtime.submitNativeCleanup("entities", BooleanSupplier { true }) })
            runtime.registerService(AutoCloseable { runtime.trackNativeCleanup(transport) })
            try {
                runtime.close()
                observer.cancel(false) shouldBe true
                val drain = runtime.nativeCleanupDrain()
                drain.isDone shouldBe false
                entities.complete(null)
                drain.isDone shouldBe false
                transport.complete(null)
                drain.join()
            } finally {
                entities.complete(null)
                transport.complete(null)
                runtime.close()
                worker.closeAsync().join()
            }
        }
    }

    "native cleanup failure is reported only after every accepted cleanup finishes" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("CrateRuntimeFailure")
            val entities = CompletableFuture<Void>()
            val transport = CompletableFuture<Void>()
            val cleanup = object : ArcNativeCleanupService {
                override fun submit(owner: String, step: BooleanSupplier): CompletionStage<Void> = entities
            }
            val worker = BoundedAsyncExecutor("crate-runtime-failure-test")
            val runtime = EciaRuntime.create(plugin, mockk<Config>(), worker, cleanup)
            runtime.registerService(AutoCloseable { runtime.submitNativeCleanup("entities", BooleanSupplier { true }) })
            runtime.registerService(AutoCloseable { runtime.trackNativeCleanup(transport) })
            try {
                runtime.close()
                val drain = runtime.nativeCleanupDrain()
                entities.completeExceptionally(IllegalStateException("entity cleanup failed"))
                drain.isDone shouldBe false
                transport.completeExceptionally(IllegalStateException("transport cleanup failed"))
                drain.isCompletedExceptionally shouldBe true
                val failure = runCatching { drain.join() }.exceptionOrNull()!!.cause!!
                failure.suppressed.size shouldBe 1
            } finally {
                entities.complete(null)
                transport.complete(null)
                runtime.close()
                worker.closeAsync().join()
            }
        }
    }
})
