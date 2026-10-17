package ru.ruscrafting.ecia

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.unmockkConstructor
import io.mockk.unmockkObject
import org.bukkit.Bukkit
import org.bukkit.plugin.ServicePriority
import ru.arc.paper.api.ArcNativeCleanupService
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.loadPlugin
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.nio.file.Files

class ArcExcellentCratesPluginLifecycleTest : FreeSpec({
    "a superseded enable cannot prepare a replacement before accepted registry work drains" {
        MockBukkitTestRuntime.open().use { paper ->
            mockkObject(paper.server)
            every { paper.server.worldContainer } returns Files.createTempDirectory("crate-lifecycle-worlds").toFile()
            val host = paper.createSimplePlugin("ARC")
            paper.createSimplePlugin("packetevents")
            paper.server.servicesManager.register(ArcNativeCleanupService::class.java, mockk<ArcNativeCleanupService>(), host, ServicePriority.Normal)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val preparations = AtomicInteger()
            mockkConstructor(CrateRegistry::class)
            every { anyConstructed<CrateRegistry>().reload() } answers {
                Bukkit.isPrimaryThread() shouldBe false
                if (preparations.incrementAndGet() == 1) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
                throw IllegalStateException("isolated lifecycle preparation")
            }
            var plugin: ArcExcellentCratesPlugin? = null
            try {
                plugin = paper.loadPlugin<ArcExcellentCratesPlugin>()
                awaitCratesLifecycle(paper) { entered.count == 0L }
                paper.server.pluginManager.disablePlugin(plugin)
                plugin.drained().isDone shouldBe false
                plugin.onDisable()
                plugin.drained().isDone shouldBe false
                paper.server.pluginManager.enablePlugin(plugin)
                paper.performTicks(3)
                preparations.get() shouldBe 1
                paper.server.pluginManager.disablePlugin(plugin)
                paper.server.pluginManager.enablePlugin(plugin)
                paper.performTicks(3)
                preparations.get() shouldBe 1
                release.countDown()
                awaitCratesLifecycle(paper) { preparations.get() == 2 }
                awaitCratesLifecycle(paper) { !plugin.isEnabled }
            } finally {
                release.countDown()
                plugin?.let { active -> paper.server.pluginManager.disablePlugin(active); active.drained().get(5, TimeUnit.SECONDS) }
                unmockkConstructor(CrateRegistry::class)
                unmockkObject(paper.server)
            }
        }
    }

    "a terminal store failure retains the predecessor and prevents the next registry acquisition" {
        MockBukkitTestRuntime.open().use { paper ->
            mockkObject(paper.server)
            every { paper.server.worldContainer } returns Files.createTempDirectory("crate-lifecycle-worlds").toFile()
            val host = paper.createSimplePlugin("ARC")
            paper.createSimplePlugin("packetevents")
            paper.server.servicesManager.register(ArcNativeCleanupService::class.java, mockk<ArcNativeCleanupService>(), host, ServicePriority.Normal)
            val prepared = CountDownLatch(1)
            val closeEntered = CountDownLatch(1)
            val releaseClose = CountDownLatch(1)
            val preparations = AtomicInteger()
            mockkConstructor(CrateRegistry::class, CrateVisualSettingsStore::class)
            every { anyConstructed<CrateRegistry>().reload() } answers {
                preparations.incrementAndGet()
                prepared.countDown()
                0
            }
            every { anyConstructed<CrateVisualSettingsStore>().close() } answers {
                Bukkit.isPrimaryThread() shouldBe false
                closeEntered.countDown()
                check(releaseClose.await(5, TimeUnit.SECONDS))
                throw IllegalStateException("terminal visual store cleanup failed")
            }
            var plugin: ArcExcellentCratesPlugin? = null
            try {
                plugin = paper.loadPlugin<ArcExcellentCratesPlugin>()
                paper.performTicks(1)
                prepared.await(5, TimeUnit.SECONDS) shouldBe true
                paper.server.pluginManager.disablePlugin(plugin)
                closeEntered.await(5, TimeUnit.SECONDS) shouldBe true
                plugin.drained().isDone shouldBe false
                paper.server.pluginManager.enablePlugin(plugin)
                paper.performTicks(3)
                preparations.get() shouldBe 1
                releaseClose.countDown()
                awaitCratesLifecycle(paper) { !plugin.isEnabled }
                plugin.drained().isCompletedExceptionally shouldBe true
                preparations.get() shouldBe 1
            } finally {
                releaseClose.countDown()
                plugin?.let { active ->
                    paper.server.pluginManager.disablePlugin(active)
                    runCatching { active.drained().get(5, TimeUnit.SECONDS) }
                }
                unmockkConstructor(CrateRegistry::class, CrateVisualSettingsStore::class)
                unmockkObject(paper.server)
            }
        }
    }
})

private fun awaitCratesLifecycle(paper: MockBukkitTestRuntime, ready: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (!ready() && System.nanoTime() < deadline) { paper.performTicks(1); Thread.sleep(1) }
    ready() shouldBe true
}
