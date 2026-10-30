package ru.ruscrafting.ecia

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.Component
import org.bukkit.command.PluginCommand
import org.junit.jupiter.api.Test
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID

class ArcCrateRecoveryCommandTest {
    @Test
    fun resolutionRequiresAdminPermissionAndTheLocalConsole() {
        MockBukkitTestRuntime.open().use { paper ->
            val host = paper.createSimplePlugin("RecoveryCommandTest")
            val plugin = mockk<ArcExcellentCratesPlugin>(relaxed = true)
            val command = mockk<PluginCommand>(relaxed = true)
            every { plugin.name } returns host.name
            every { plugin.server } returns paper.server
            every { plugin.isEnabled } returns true
            every { plugin.getCommand("arc-crate") } returns command
            every { plugin.runtime().locale().renderPadded(any(), any(), any()) } returns Component.empty()
            val route = ArcCrateCommand(plugin, mockk(), mockk(), mockk()) { }
            try {
                val id = UUID.randomUUID()
                val player = paper.addPlayer("CrateAdmin")
                route.onCommand(player, command, "arc-crate", arrayOf("resolve", id.toString()))
                player.isOp = true
                route.onCommand(player, command, "arc-crate", arrayOf("resolve", id.toString()))
                route.onCommand(paper.server.consoleSender, command, "arc-crate", arrayOf("resolve", "invalid"))
                route.onCommand(paper.server.consoleSender, command, "arc-crate", arrayOf("resolve", id.toString(), "extra"))
                verify(exactly = 0) { plugin.resolveCompensatedOpening(any()) }

                route.onCommand(paper.server.consoleSender, command, "arc-crate", arrayOf("resolve", id.toString()))
                verify(exactly = 1) { plugin.resolveCompensatedOpening(id) }
            } finally {
                route.close()
            }
        }
    }
}
