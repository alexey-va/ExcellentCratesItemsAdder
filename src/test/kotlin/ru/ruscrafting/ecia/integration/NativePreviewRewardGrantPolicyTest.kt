package ru.ruscrafting.ecia.integration

import io.mockk.every
import io.mockk.mockk
import org.bukkit.Material
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryView
import org.bukkit.inventory.ItemStack
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.arc.paper.testing.MockBukkitTestRuntime
import su.nightexpress.excellentcrates.api.crate.Reward
import su.nightexpress.excellentcrates.crate.impl.CrateSource
import su.nightexpress.excellentcrates.crate.menu.PreviewMenu
import su.nightexpress.nightcore.ui.menu.MenuViewer
import su.nightexpress.nightcore.ui.menu.item.MenuItem

class NativePreviewRewardGrantPolicyTest {
    @Test
    fun mapsOnlyTheRequestedNativeFillerPageAndSelectsItsCapturedReward() {
        MockBukkitTestRuntime.open().use {
            val rewards = listOf(mockk<Reward>(), mockk<Reward>(), mockk<Reward>())
            val slots = intArrayOf(10, 11)
            val pageTwo = NativePreviewGrantPolicy.projectPage(rewards, slots, page = 2, topSize = 45)

            assertEquals(mapOf(10 to rewards[2]), pageTwo)

            val rendered = ItemStack(Material.DIAMOND)
            val viewer = mockk<MenuViewer>()
            val preview = mockk<PreviewMenu>()
            val source = mockk<CrateSource>()
            val view = mockk<InventoryView>()
            val top = mockk<Inventory>()
            val menuItem = mockk<MenuItem>()
            every { viewer.menu } returns preview
            every { viewer.view } returns view
            every { viewer.page } returns 2
            every { viewer.items } returns setOf(menuItem)
            every { view.topInventory } returns top
            every { top.size } returns 45
            every { top.getItem(10) } returns rendered.clone()
            every { preview.getLink(viewer) } returns source
            every { preview.getItem(viewer, 10) } returns menuItem
            every { menuItem.priority } returns 100
            every { menuItem.slots } returns intArrayOf(10)
            val snapshot = NativePreviewRewardSnapshot(
                viewer = viewer,
                preview = preview,
                source = source,
                view = view,
                topInventory = top,
                page = 2,
                rewardsBySlot = mapOf(10 to NativePreviewDisplayedReward(rewards[2], rendered.clone(), menuItem)),
            )

            val selected = resolve(snapshot, viewer, preview, top, rendered, slot = 10)

            assertSame(rewards[2], selected)
        }
    }

    @Test
    fun rejectsUnauthorizedNonLeftShiftDoubleBottomStaleAndChangedItemClicks() {
        MockBukkitTestRuntime.open().use {
            val reward = mockk<Reward>()
            val rendered = ItemStack(Material.DIAMOND)
            val viewer = mockk<MenuViewer>()
            val preview = mockk<PreviewMenu>()
            val source = mockk<CrateSource>()
            val view = mockk<InventoryView>()
            val top = mockk<Inventory>()
            val bottom = mockk<Inventory>()
            val menuItem = mockk<MenuItem>()
            every { viewer.menu } returns preview
            every { viewer.view } returns view
            every { viewer.page } returns 2
            every { viewer.items } returns setOf(menuItem)
            every { view.topInventory } returns top
            every { top.size } returns 45
            every { top.getItem(10) } returns rendered.clone()
            every { preview.getLink(viewer) } returns source
            every { preview.getItem(viewer, 10) } returns menuItem
            every { menuItem.priority } returns 100
            every { menuItem.slots } returns intArrayOf(10)
            val snapshot = NativePreviewRewardSnapshot(
                viewer,
                preview,
                source,
                view,
                top,
                2,
                mapOf(10 to NativePreviewDisplayedReward(reward, rendered.clone(), menuItem)),
            )

            assertNull(resolve(snapshot, viewer, preview, top, rendered, permitted = false))
            assertNull(resolve(snapshot, viewer, preview, top, rendered, click = ClickType.RIGHT))
            assertNull(resolve(snapshot, viewer, preview, top, rendered, click = ClickType.SHIFT_LEFT, shift = true))
            assertNull(resolve(snapshot, viewer, preview, top, rendered, click = ClickType.DROP))
            assertNull(resolve(snapshot, viewer, preview, top, rendered, click = ClickType.DOUBLE_CLICK))
            assertNull(resolve(snapshot, viewer, preview, bottom, rendered))
            assertNull(resolve(snapshot, viewer, preview, top, rendered, slot = 45))
            assertNull(resolve(snapshot, viewer, preview, top, ItemStack(Material.EMERALD)))
            assertNull(resolve(snapshot, viewer, preview, top, rendered, currentPage = 1))
            assertNull(resolve(snapshot, viewer, preview, top, rendered, currentTop = mockk()))

            val identicalGlobalIcon = rendered.clone()
            val higherPriorityGlobalWinner = mockk<MenuItem>()
            assertTrue(sameNativePreviewStack(rendered, identicalGlobalIcon))
            every { preview.getItem(viewer, 10) } returns higherPriorityGlobalWinner
            assertNull(resolve(snapshot, viewer, preview, top, identicalGlobalIcon))
        }
    }

    private fun resolve(
        snapshot: NativePreviewRewardSnapshot,
        viewer: MenuViewer,
        preview: PreviewMenu,
        clickedInventory: Inventory,
        item: ItemStack,
        slot: Int = 10,
        permitted: Boolean = true,
        click: ClickType = ClickType.LEFT,
        shift: Boolean = false,
        currentPage: Int = 2,
        currentTop: Inventory = snapshot.topInventory,
    ): Reward? = NativePreviewGrantPolicy.resolve(
        permitted = permitted,
        click = click,
        shiftClick = shift,
        rawSlot = slot,
        topSize = 45,
        clickedInventory = clickedInventory,
        eventTopInventory = currentTop,
        currentViewer = viewer,
        currentMenu = preview,
        currentPage = currentPage,
        clickedItem = item,
        snapshot = snapshot,
    )
}
