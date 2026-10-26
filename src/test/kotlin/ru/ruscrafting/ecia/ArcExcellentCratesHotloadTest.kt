package ru.ruscrafting.ecia

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import ru.arc.config.Config
import ru.arc.core.BoundedAsyncExecutor
import ru.arc.paper.api.ArcNativeCleanupService
import ru.arc.paper.runtime.PaperPluginHotload
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import ru.arc.runtime.PluginHotloadState
import ru.ruscrafting.ecia.runtime.EciaRuntime
import java.util.concurrent.CompletableFuture

class ArcExcellentCratesHotloadTest : FreeSpec({
    "prepare drains the existing crate runtime once while native cleanup remains pending" {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open().use { paper ->
                val plugin = paper.loadSimplePlugin(CrateHotloadFixture::class.java)
                fun set(name: String, value: Any) {
                    ArcExcellentCratesPlugin::class.java.getDeclaredField(name).apply { isAccessible = true }.set(plugin, value)
                }
                val hotload = ArcExcellentCratesPlugin::class.java.getDeclaredField("hotload").apply { isAccessible = true }
                    .get(plugin) as PaperPluginHotload
                val worker = BoundedAsyncExecutor("crate-hotload-test")
                val runtime = EciaRuntime.create(plugin, mockk<Config>(), worker, mockk<ArcNativeCleanupService>())
                val native = CompletableFuture<Void>()
                var closes = 0
                runtime.registerService(AutoCloseable { closes++; runtime.trackNativeCleanup(native) })
                set("runtime", runtime)
                set("storage", worker)
                hotload.beginGeneration()
                hotload.register()
                try {
                    repeat(2) {
                        paper.server.dispatchCommand(paper.server.consoleSender, "cratehotloadfixture:hotload prepare") shouldBe true
                    }
                    plugin.isEnabled shouldBe true
                    closes shouldBe 1
                    plugin.runtime() shouldBe null
                    plugin.drained().isDone shouldBe false
                    hotload.status().phases.getValue("storage").state shouldBe PluginHotloadState.READY
                    hotload.status().phases.getValue("native").state shouldBe PluginHotloadState.PREPARING
                    paper.server.dispatchCommand(paper.server.consoleSender, "cratehotloadfixture:hotload status") shouldBe true
                    native.complete(null)
                    plugin.drained().join()
                    hotload.status().state shouldBe PluginHotloadState.READY
                    plugin.onDisable()
                    closes shouldBe 1
                    paper.server.commandMap.getCommand("cratehotloadfixture:hotload") shouldBe null
                } finally {
                    native.complete(null)
                    paper.server.pluginManager.disablePlugin(plugin)
                    worker.closeAsync().join()
                }
            }
        }
    }
})

/** Supplies the real runtime without external registry, ItemsAdder or native ExcellentCrates startup. */
open class CrateHotloadFixture : ArcExcellentCratesPlugin() {
    override fun onEnable() = Unit
}
