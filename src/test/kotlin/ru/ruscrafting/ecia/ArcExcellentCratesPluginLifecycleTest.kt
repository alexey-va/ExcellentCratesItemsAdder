package ru.ruscrafting.ecia

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import org.bukkit.Bukkit
import org.bukkit.plugin.ServicePriority
import org.mockbukkit.mockbukkit.exception.UnimplementedOperationException
import org.opentest4j.TestAbortedException
import ru.arc.paper.api.ArcNativeCleanupService
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.loadPlugin

class ArcExcellentCratesPluginLifecycleTest : FreeSpec({
    "maintenance registry preparation completes before enable failure returns" {
        MockBukkitTestRuntime.open().use { paper ->
            try { paper.server.worldContainer } catch (unsupported: UnimplementedOperationException) {
                throw TestAbortedException("MockBukkit does not implement Server.getWorldContainer required by the real crate entrypoint; verify lifecycle on Paper", unsupported)
            }
            val host = paper.createSimplePlugin("ARC")
            paper.createSimplePlugin("packetevents")
            paper.server.servicesManager.register(ArcNativeCleanupService::class.java, mockk<ArcNativeCleanupService>(), host, ServicePriority.Normal)
            var preparations = 0
            mockkConstructor(CrateRegistry::class)
            every { anyConstructed<CrateRegistry>().reload() } answers {
                Bukkit.isPrimaryThread() shouldBe false
                preparations++
                throw IllegalStateException("isolated registry failure")
            }
            try {
                val plugin = paper.loadPlugin<ArcExcellentCratesPlugin>()
                preparations shouldBe 1
                plugin.isEnabled shouldBe false
                plugin.onDisable()
                preparations shouldBe 1
            } finally { unmockkConstructor(CrateRegistry::class) }
        }
    }
})
