package ru.ruscrafting.ecia.integration

import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.Runs
import org.bukkit.Material
import org.bukkit.Location
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.event.server.PluginEnableEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.EventExecutor
import org.bukkit.plugin.RegisteredListener
import org.bukkit.plugin.java.JavaPlugin
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import ru.arc.paper.testing.MockBukkitTestRuntime
import su.nightexpress.excellentcrates.CratesAPI
import su.nightexpress.excellentcrates.CratesPlugin
import su.nightexpress.excellentcrates.api.event.CrateOpenEvent
import su.nightexpress.excellentcrates.crate.CrateManager
import su.nightexpress.excellentcrates.crate.impl.Crate
import su.nightexpress.excellentcrates.crate.listener.CrateListener
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals

class NativeCrateInteractionRouterTest {
    @Test
    fun managedPreflightHonorsExternalCrateOpenVeto() {
        MockBukkitTestRuntime.open().use { paper ->
            val fixture = fixture(paper)
            val veto = paper.createSimplePlugin("RouterVeto")
            paper.server.pluginManager.registerEvent(
                CrateOpenEvent::class.java,
                object : org.bukkit.event.Listener {},
                EventPriority.NORMAL,
                EventExecutor { _, event -> (event as CrateOpenEvent).isCancelled = true },
                veto,
            )
            withRouter(fixture) { router, _, crate ->
                assertFalse(router.allowManagedOpen(fixture.player, crate))
            }
        }
    }

    @Test
    fun managedPreflightDoesNotSelfVeto() {
        MockBukkitTestRuntime.open().use { paper ->
            val fixture = fixture(paper)
            withRouter(fixture) { router, _, crate ->
                assertTrue(router.allowManagedOpen(fixture.player, crate))
            }
        }
    }

    @Test
    fun priorCancelledInteractionDoesNotReachManagedOpening() {
        MockBukkitTestRuntime.open().use { paper ->
            val fixture = fixture(paper)
            val opens = AtomicInteger()
            withRouter(fixture, open = { _, _ -> opens.incrementAndGet() }) { _, _, _ ->
                val event = interaction(fixture.player, fixture.block)
                event.isCancelled = true
                paper.server.pluginManager.callEvent(event)
                assertTrue(event.isCancelled)
                assertTrue(opens.get() == 0)
            }
        }
    }

    @Test
    fun freshMainHandInteractionReachesManagedOpeningOnce() {
        MockBukkitTestRuntime.open().use { paper ->
            val fixture = fixture(paper)
            val opens = AtomicInteger()
            withRouter(fixture, open = { _, _ -> opens.incrementAndGet() }) { _, _, _ ->
                paper.server.pluginManager.callEvent(interaction(fixture.player, fixture.block))
                assertTrue(opens.get() == 1)
            }
        }
    }

    @Test
    fun physicalOpeningKeepsClickedCrateLocation() {
        MockBukkitTestRuntime.open().use { paper ->
            val fixture = fixture(paper)
            var target: ManagedOpenTarget? = null
            withRouter(fixture, open = { _, opened -> target = opened }) { _, _, _ ->
                paper.server.pluginManager.callEvent(interaction(fixture.player, fixture.block))

                assertTrue(target?.crate() === fixture.crate)
                assertEquals(fixture.block.location, target?.anchor())
            }
        }
    }

    @Test
    fun sneakingLeftClickOpensVisualEditorInsteadOfPreviewOrOpening() {
        MockBukkitTestRuntime.open().use { paper ->
            val fixture = fixture(paper)
            fixture.player.isSneaking = true
            val edits = AtomicInteger()
            val opens = AtomicInteger()
            withRouter(
                fixture,
                open = { _, _ -> opens.incrementAndGet() },
                visualEditor = { _, target ->
                    assertEquals(fixture.block.location, target.anchor())
                    edits.incrementAndGet()
                    true
                },
            ) { _, _, _ ->
                val event = PlayerInteractEvent(
                    fixture.player,
                    Action.LEFT_CLICK_BLOCK,
                    ItemStack(Material.TRIPWIRE_HOOK),
                    fixture.block,
                    BlockFace.SELF,
                    EquipmentSlot.HAND,
                )
                paper.server.pluginManager.callEvent(event)

                assertEquals(1, edits.get())
                assertEquals(0, opens.get())
            }
        }
    }

    @Test
    fun sneakingRightClickKeepsTheNormalOpeningRoute() {
        MockBukkitTestRuntime.open().use { paper ->
            val fixture = fixture(paper)
            fixture.player.isSneaking = true
            val edits = AtomicInteger()
            val opens = AtomicInteger()
            withRouter(
                fixture,
                open = { _, _ -> opens.incrementAndGet() },
                visualEditor = { _, _ -> edits.incrementAndGet(); true },
            ) { _, _, _ ->
                paper.server.pluginManager.callEvent(interaction(fixture.player, fixture.block))

                assertEquals(0, edits.get())
                assertEquals(1, opens.get())
            }
        }
    }

    @Test
    fun excellentCratesRestartRebindsCurrentNativeHandler() {
        MockBukkitTestRuntime.open().use { paper ->
            val oldPlugin = mockk<CratesPlugin>(relaxed = true)
            val newPlugin = mockk<CratesPlugin>(relaxed = true)
            every { oldPlugin.getName() } returns "ExcellentCrates"
            every { newPlugin.getName() } returns "ExcellentCrates"
            every { oldPlugin.isEnabled } returns true
            every { newPlugin.isEnabled } returns true
            every { oldPlugin.getAddons() } returns mutableListOf()
            every { newPlugin.getAddons() } returns mutableListOf()
            val current = arrayOf<CratesPlugin>(oldPlugin)
            mockkStatic(CratesAPI::class)
            every { CratesAPI.isLoaded() } returns true
            every { CratesAPI.plugin() } answers { current[0] }
            every { CratesAPI.registerAddon(any()) } just Runs
            try {
                val oldNative = registerNative(oldPlugin)
                val crate = mockk<Crate>(relaxed = true)
                every { crate.id } returns "case_daily"
                val plugin = paper.createSimplePlugin("RouterLifecycle")
                val router = NativeCrateInteractionRouter(
                    plugin,
                    { true },
                    { _, _ -> },
                    { _, _ -> },
                    { },
                    { _, _ -> false },
                    { false },
                    { },
                )
                try {
                    assertTrue(router.allowManagedOpen(paper.addPlayer("before"), crate))
                    paper.server.pluginManager.callEvent(PluginDisableEvent(oldPlugin))
                    assertFalse(router.allowManagedOpen(paper.addPlayer("during"), crate))

                    current[0] = newPlugin
                    PlayerInteractEvent.getHandlerList().unregister(oldNative)
                    registerNative(newPlugin)
                    paper.server.pluginManager.callEvent(PluginEnableEvent(newPlugin))
                    assertTrue(router.allowManagedOpen(paper.addPlayer("after"), crate))
                } finally {
                    router.close()
                }
            } finally {
                unmockkStatic(CratesAPI::class)
                HandlerList.unregisterAll(oldPlugin)
                HandlerList.unregisterAll(newPlugin)
            }
        }
    }

    private data class Fixture(
        val plugin: JavaPlugin,
        val player: Player,
        val block: Block,
        val crate: Crate,
        val manager: CrateManager,
        val cratesPlugin: CratesPlugin,
    )

    private fun fixture(paper: MockBukkitTestRuntime): Fixture {
        val cratesPlugin = mockk<CratesPlugin>(relaxed = true)
        every { cratesPlugin.getName() } returns "ExcellentCrates"
        every { cratesPlugin.isEnabled } returns true
        every { cratesPlugin.getAddons() } returns mutableListOf()
        val manager = mockk<CrateManager>(relaxed = true)
        val crate = mockk<Crate>(relaxed = true)
        every { crate.id } returns "case_daily"
        val player = paper.addPlayer("RouterPlayer")
        val block = mockk<Block>(relaxed = true)
        every { block.location } returns Location(player.world, 3.0, 4.0, 5.0)
        every { manager.getCrateByItem(any()) } returns null
        every { manager.getCrateByBlock(block) } returns crate
        mockkStatic(CratesAPI::class)
        every { CratesAPI.isLoaded() } returns true
        every { CratesAPI.plugin() } returns cratesPlugin
        every { CratesAPI.getCrateManager() } returns manager
        every { CratesAPI.registerAddon(any()) } just Runs
        registerNative(cratesPlugin)
        return Fixture(paper.createSimplePlugin("RouterTest"), player, block, crate, manager, cratesPlugin)
    }

    private fun withRouter(
        fixture: Fixture,
        open: (Player, ManagedOpenTarget) -> Unit = { _, _ -> },
        visualEditor: (Player, ManagedOpenTarget) -> Boolean = { _, _ -> false },
        body: (NativeCrateInteractionRouter, Player, Crate) -> Unit,
    ) {
        val router = NativeCrateInteractionRouter(
            fixture.plugin,
            { true },
            open,
            { _, _ -> },
            { },
            visualEditor,
            { false },
            { },
        )
        try {
            body(router, fixture.player, fixture.crate)
        } finally {
            router.close()
            unmockkStatic(CratesAPI::class)
            HandlerList.unregisterAll(fixture.cratesPlugin)
        }
    }

    private fun interaction(player: Player, block: Block): PlayerInteractEvent =
        PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, ItemStack(Material.TRIPWIRE_HOOK), block, BlockFace.SELF, EquipmentSlot.HAND)

    private fun registerNative(plugin: CratesPlugin): RegisteredListener {
        val listener = CrateListener(plugin, mockk(relaxed = true))
        return RegisteredListener(
            listener,
            EventExecutor { _, _ -> },
            EventPriority.HIGH,
            plugin,
            false,
        ).also { PlayerInteractEvent.getHandlerList().register(it) }
    }
}
