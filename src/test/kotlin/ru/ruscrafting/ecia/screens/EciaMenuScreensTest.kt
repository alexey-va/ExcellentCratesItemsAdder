package ru.ruscrafting.ecia.screens

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.bukkit.Material
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.inventory.ItemStack
import ru.arc.core.BukkitTaskScheduler
import ru.arc.menu.MenuElementId
import ru.arc.menu.MenuTemplateId
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.menu.PaperMenuItemSource
import ru.arc.paper.menu.PaperMenuItemTemplate
import ru.arc.paper.menu.PaperMenuRuntime
import ru.ruscrafting.ecia.roll.PoolSnapshot
import ru.ruscrafting.ecia.roll.RewardDefinition
import ru.ruscrafting.ecia.inventory.NativeItemPayload

class EciaMenuScreensTest {
    private lateinit var paper: MockBukkitTestRuntime

    @BeforeEach
    fun setUp() {
        paper = MockBukkitTestRuntime.open()
    }

    @AfterEach
    fun tearDown() {
        paper.close()
    }

    @Test
    fun bundledCatalogKeepsHistoryAndPoolLayoutsAndRequiredModelData() {
        val configuration = EciaMenuConfiguration.loadResource()

        assertEquals(6, configuration.catalog.require(EciaMenuConfiguration.HISTORY).rows)
        assertEquals(6, configuration.catalog.require(EciaMenuConfiguration.POOL_PREVIEW).rows)
        assertEquals((2..4).toList(), EciaMenuConfiguration.POOL_PREVIEWS.keys.toList())
        EciaMenuConfiguration.POOL_PREVIEWS.forEach { (rows, menu) ->
            val layout = configuration.catalog.require(menu)
            assertEquals(rows, layout.rows)
            assertEquals((rows - 1) * 9, layout.region(EciaMenuConfiguration.REWARDS).size)
            assertEquals(7, layout.region(EciaMenuConfiguration.FOOTER).size)
        }
        assertEquals(11000, configuration.template(MenuTemplateId.of("background")).customModelData)
        assertEquals(Material.GRAY_STAINED_GLASS_PANE, material(configuration.template(MenuTemplateId.of("background"))))
        assertEquals(11013, configuration.template(MenuTemplateId.of("previous")).customModelData)
        assertEquals(Material.BLUE_STAINED_GLASS_PANE, material(configuration.template(MenuTemplateId.of("previous"))))
        assertEquals(11012, configuration.template(MenuTemplateId.of("next")).customModelData)
        assertEquals(Material.BLUE_STAINED_GLASS_PANE, material(configuration.template(MenuTemplateId.of("next"))))
        assertTrue(configuration.templates.values.all { it.customModelData != 11001 })
        assertTrue(configuration.templates.values.none { it.customModelData == 11001 })
    }

    @Test
    fun nativePreviewNameSurvivesWithoutShowingRewardId() {
        val configuration = EciaMenuConfiguration.loadResource()
        val payload = NativeItemPayload()
        val native = ItemStack(Material.LEATHER)
        native.editMeta { it.displayName(Component.text("Кожаная перчатка", NamedTextColor.GREEN)) }
        val reward = RewardDefinition("reward-uuid", 1.0, "delivery", payload.items(arrayOf(native)))
        val entry = EciaMenuScreens(configuration, payload)
            .renderPoolPreview(PoolSnapshot("case_daily", "summer", listOf(reward), 1, 0))
            .regions.getValue(EciaMenuConfiguration.REWARDS)
            .single()
        val displayName = entry.item.itemMeta.displayName()?.toString().orEmpty()
        assertFalse(entry.enabled)
        assertTrue(displayName.contains("Кожаная перчатка"))
        assertFalse(displayName.contains("reward-uuid"))
        assertTrue(entry.item.itemMeta.lore().toString().contains("100.0%"))
        assertFalse(entry.item.itemMeta.lore().toString().contains("reward_roll_chance"))
    }

    @Test
    fun pageCallbackKeepsMenuAndDelta() {
        val calls = mutableListOf<Pair<ru.arc.menu.MenuId, Int>>()
        val actions = EciaMenuActions(
            page = EciaMenuActions.Page { menu, delta -> calls += menu to delta },
        )
        actions.page.invoke(EciaMenuConfiguration.POOL_PREVIEW, 1)
        assertEquals(EciaMenuConfiguration.POOL_PREVIEW to 1, calls.single())
    }

    @Test
    fun poolPreviewOrdersByExactChanceThenVisibleNameWithoutFormatting() {
        val screens = EciaMenuScreens(EciaMenuConfiguration.loadResource())
        val rewards = listOf(
            namedReward("low", 1.0, "Алмаз", 1),
            namedReward("equal-last", 5.0, "Яблоко", 2),
            namedReward("equal-middle", 5.0, "Книга", 3),
            namedReward("equal-first", 5.0, "алмаз", 4, NamedTextColor.RED),
            namedReward("almost-highest", 10.0001, "Алмаз", 5),
            namedReward("highest", 10.0002, "Яблоко", 6),
        )
        val pool = PoolSnapshot("case_daily", "current", rewards, 1, 0)

        val entries = screens.renderPoolPreview(pool).regions.getValue(EciaMenuConfiguration.REWARDS)

        assertEquals(listOf(6, 5, 4, 3, 2, 1), entries.map { it.item.itemMeta.customModelData })
        assertEquals(rewards, pool.rewards(), "Preview must not reorder the rolling pool")
    }

    @Test
    fun equalChanceAndNameUseRewardIdIndependentlyOfInputOrder() {
        val screens = EciaMenuScreens(EciaMenuConfiguration.loadResource())
        val rewards = listOf(
            namedReward("key-b", 5.0, "Ключ", 2, NamedTextColor.RED),
            namedReward("key-a", 5.0, "ключ", 1, NamedTextColor.GREEN),
        )
        for (input in listOf(rewards, rewards.reversed())) {
            val pool = PoolSnapshot("case_daily", "current", input, 1, 0)
            val entries = screens.renderPoolPreview(pool).regions.getValue(EciaMenuConfiguration.REWARDS)
            assertEquals(listOf(1, 2), entries.map { it.item.itemMeta.customModelData })
        }
    }

    @Test
    fun sortingHappensBeforePaginationAndPlacesTheHighestChanceInTheTopLeftSlot() {
        val configuration = EciaMenuConfiguration.loadResource()
        val screens = EciaMenuScreens(configuration)
        val plugin = paper.createSimplePlugin("SortedPoolPreview")
        val player = paper.addPlayer("SortedPreviewer")
        val runtime = PaperMenuRuntime(plugin, BukkitTaskScheduler(plugin), configuration)
        val rewards = (1..35).map { index -> namedReward("reward-$index", index.toDouble(), "Приз $index", index) }
        val pool = PoolSnapshot("case_daily", "current", rewards, 1, 0)
        try {
            val first = screens.openPoolPreview(runtime, player, pool)
            assertEquals(35, first.inventory.getItem(0)!!.itemMeta.customModelData)
            assertEquals(9, first.inventory.getItem(26)!!.itemMeta.customModelData)
            val last = screens.renderPoolPreview(pool, page = 1).regions.getValue(EciaMenuConfiguration.REWARDS)
            assertEquals(listOf(8, 7, 6, 5, 4, 3, 2, 1), last.map { it.item.itemMeta.customModelData })
            val reversed = PoolSnapshot("case_daily", "current", rewards.reversed(), 1, 0)
            assertEquals(last.map { it.item }, screens.renderPoolPreview(reversed, page = 1)
                .regions.getValue(EciaMenuConfiguration.REWARDS).map { it.item })
            assertEquals(rewards, pool.rewards())
        } finally {
            runtime.close()
        }
    }

    @Test
    fun poolPreviewUsesDynamicRowsAndFixedFooterNavigation() {
        val configuration = EciaMenuConfiguration.loadResource()
        val screens = EciaMenuScreens(configuration)
        val rewards = (1..46).map { RewardDefinition("reward-$it", 1.0, "delivery-$it", "") }
        val pool = PoolSnapshot("case_daily", "summer", rewards, 3, 1)

        val first = screens.renderPoolPreview(pool, page = 0)
        val second = screens.renderPoolPreview(pool, page = 1)
        val expectedTitle = Component.text("Ежедневный тайник", NamedTextColor.DARK_GRAY)
            .decoration(TextDecoration.BOLD, false)
            .decoration(TextDecoration.ITALIC, false)

        assertEquals(expectedTitle, first.title)
        assertEquals(null, first.background)
        assertEquals(27, configuration.catalog.require(EciaMenuConfiguration.poolPreview(4))
            .region(EciaMenuConfiguration.REWARDS).size)
        assertEquals(7, configuration.catalog.require(EciaMenuConfiguration.poolPreview(4))
            .region(EciaMenuConfiguration.FOOTER).size)
        assertEquals(EciaMenuConfiguration.poolPreview(4), screens.poolPreviewMenu(46, 0))
        assertEquals(EciaMenuConfiguration.poolPreview(4), screens.poolPreviewMenu(46, 1))
        assertEquals(EciaMenuConfiguration.poolPreview(2), screens.poolPreviewMenu(35, 1))
        assertEquals(EciaMenuConfiguration.singlePagePreview(3), screens.poolPreviewMenu(21, 0))
        assertFalse(first.elements.getValue(MenuElementId.of("previous")).enabled)
        assertTrue(first.elements.getValue(MenuElementId.of("next")).enabled)
        assertTrue(second.elements.getValue(MenuElementId.of("previous")).enabled)
        assertFalse(second.elements.getValue(MenuElementId.of("next")).enabled)
    }

    @Test
    fun singlePagePoolsHaveOnlyTheRewardRowsAndNoNavigationItems() {
        val configuration = EciaMenuConfiguration.loadResource()
        val plugin = paper.createSimplePlugin("SinglePagePreview")
        val player = paper.addPlayer("SinglePreviewer")
        val runtime = PaperMenuRuntime(plugin, BukkitTaskScheduler(plugin), configuration)
        val screens = EciaMenuScreens(configuration)
        val payload = NativeItemPayload()
        try {
            for (count in listOf(1, 9, 10, 18, 19, 27)) {
                val rewards = (1..count).map { index ->
                    RewardDefinition("reward-$index", 1.0, "delivery-$index",
                        payload.items(arrayOf(ItemStack(Material.DIAMOND))))
                }
                val pool = PoolSnapshot("case_daily", "current", rewards, 1, 0)
                val content = screens.renderPoolPreview(pool)
                assertTrue(content.elements.isEmpty(), "Unexpected arrows for $count rewards")
                assertFalse(content.regions.containsKey(EciaMenuConfiguration.FOOTER))
                val session = screens.openPoolPreview(runtime, player, pool)
                assertEquals(maxOf(1, (count + 8) / 9) * 9, session.inventory.size)
                assertEquals(count, session.inventory.contents.filterNotNull().size)
            }
        } finally {
            runtime.close()
        }
    }

    @Test
    fun rewardDescriptionAndUseSurviveAboveOneChanceLineWithoutMutatingThePrize() {
        val configuration = EciaMenuConfiguration.loadResource()
        val payload = NativeItemPayload()
        val native = ItemStack(Material.CHEST, 3)
        native.editMeta { meta ->
            meta.displayName(Component.text("Таинственная шкатулка чар", NamedTextColor.LIGHT_PURPLE))
            meta.setCustomModelData(12345)
            meta.lore(listOf(
                Component.empty(),
                Component.text("Одна случайная книга или расходник для чар", NamedTextColor.WHITE),
                Component.empty(),
                Component.text("После получения: ПКМ — открыть шкатулку", NamedTextColor.GREEN),
                Component.empty(),
            ))
        }
        val original = native.clone()
        val encoded = payload.items(arrayOf(native))
        val reward = RewardDefinition("mystery", 1.0, "delivery", encoded)
        val second = RewardDefinition("other", 3.0, "delivery-other", encoded)
        val pool = PoolSnapshot("case_daily", "current", listOf(reward, second), 1, 0)
        val item = EciaMenuScreens(configuration, payload).renderPoolPreview(pool)
            .regions.getValue(EciaMenuConfiguration.REWARDS).last().item
        val lore = item.itemMeta.lore()!!
        val plain = PlainTextComponentSerializer.plainText()

        assertEquals(listOf("", "Одна случайная книга или расходник для чар", "",
            "После получения: ПКМ — открыть шкатулку", "", "Шанс: 25.0%"), lore.map(plain::serialize))
        assertTrue(lore.all { it.decoration(TextDecoration.ITALIC) == TextDecoration.State.FALSE })
        assertEquals(TextDecoration.State.FALSE, item.itemMeta.displayName()!!.decoration(TextDecoration.ITALIC))
        assertEquals(3, item.amount)
        assertEquals(12345, item.itemMeta.customModelData)
        assertEquals(original, native)
        assertEquals(original, payload.items(reward.previewPayload()).single())
    }

    @Test
    fun nextButtonReopensTheLastPageAtItsSmallerInventorySize() {
        val configuration = EciaMenuConfiguration.loadResource()
        val plugin = paper.createSimplePlugin("DynamicPoolPreview")
        val player = paper.addPlayer("Previewer")
        val runtime = PaperMenuRuntime(plugin, BukkitTaskScheduler(plugin), configuration)
        val rewards = (1..35).map { index ->
            RewardDefinition("reward-$index", 1.0, "delivery-$index", "")
        }
        val pool = PoolSnapshot("case_daily", "summer", rewards, 3, 1)

        try {
            val first = EciaMenuScreens(configuration).openPoolPreview(runtime, player, pool)
            assertEquals(36, first.inventory.size)
            assertEquals(Material.BLUE_STAINED_GLASS_PANE, first.inventory.getItem(27)?.type)
            assertEquals(Material.BLUE_STAINED_GLASS_PANE, first.inventory.getItem(35)?.type)

            paper.server.pluginManager.callEvent(InventoryClickEvent(
                player.openInventory,
                InventoryType.SlotType.CONTAINER,
                35,
                ClickType.LEFT,
                InventoryAction.PICKUP_ALL,
            ))

            val second = runtime.session(player)!!
            assertFalse(first.isOpen)
            assertEquals(18, second.inventory.size)
            assertEquals(Material.BLUE_STAINED_GLASS_PANE, second.inventory.getItem(9)?.type)
            assertEquals(Material.BLUE_STAINED_GLASS_PANE, second.inventory.getItem(17)?.type)
        } finally {
            runtime.close()
        }
    }

    private fun material(template: PaperMenuItemTemplate): Material =
        (template.source as PaperMenuItemSource.MaterialItem).material

    private fun namedReward(
        id: String,
        weight: Double,
        name: String,
        modelData: Int,
        color: NamedTextColor = NamedTextColor.WHITE,
    ): RewardDefinition {
        val item = ItemStack(Material.DIAMOND)
        item.editMeta {
            it.displayName(Component.text(name, color))
            it.setCustomModelData(modelData)
        }
        return RewardDefinition(id, weight, "delivery-$id", NativeItemPayload().items(arrayOf(item)))
    }
}
