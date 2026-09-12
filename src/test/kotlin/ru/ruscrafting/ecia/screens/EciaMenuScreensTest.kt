package ru.ruscrafting.ecia.screens

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import ru.arc.menu.MenuElementId
import ru.arc.menu.MenuTemplateId
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.menu.PaperMenuItemSource
import ru.arc.paper.menu.PaperMenuItemTemplate
import ru.ruscrafting.ecia.journal.OpeningRecord
import ru.ruscrafting.ecia.roll.PoolSnapshot
import ru.ruscrafting.ecia.roll.RewardDefinition
import ru.ruscrafting.ecia.inventory.NativeItemPayload
import java.util.UUID

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
    fun bundledCatalogKeepsSixRowsAndRequiredModelData() {
        val configuration = EciaMenuConfiguration.loadResource()

        assertEquals(6, configuration.catalog.require(EciaMenuConfiguration.CHOICES).rows)
        assertEquals(6, configuration.catalog.require(EciaMenuConfiguration.MAIL).rows)
        assertEquals(6, configuration.catalog.require(EciaMenuConfiguration.HISTORY).rows)
        assertEquals(6, configuration.catalog.require(EciaMenuConfiguration.POOL_PREVIEW).rows)
        assertEquals(3, configuration.catalog.require(EciaMenuConfiguration.CHOICES).region(EciaMenuConfiguration.OFFERS).size)
        assertEquals(11000, configuration.template(MenuTemplateId.of("background")).customModelData)
        assertEquals(Material.GRAY_STAINED_GLASS_PANE, material(configuration.template(MenuTemplateId.of("background"))))
        assertEquals(11009, configuration.template(MenuTemplateId.of("previous")).customModelData)
        assertEquals(Material.BLUE_STAINED_GLASS_PANE, material(configuration.template(MenuTemplateId.of("previous"))))
        assertEquals(11008, configuration.template(MenuTemplateId.of("next")).customModelData)
        assertEquals(Material.BLUE_STAINED_GLASS_PANE, material(configuration.template(MenuTemplateId.of("next"))))
        assertTrue(configuration.templates.values.all { it.customModelData != 11001 })
        assertTrue(configuration.templates.values.none { it.customModelData == 11001 })
    }

    @Test
    fun reviewMailEntryIsReadOnlyAndChoicesStayVisible() {
        val configuration = EciaMenuConfiguration.loadResource()
        val screens = EciaMenuScreens(configuration)
        val opening = opening(OpeningRecord.Stage.REVIEW, selected = "rare")

        val mail = screens.renderMail(listOf(opening))
        val mailEntry = mail.regions.getValue(EciaMenuConfiguration.ENTRIES).single()
        assertFalse(mailEntry.enabled)
        assertTrue(!mailEntry.item.itemMeta.hasCustomModelData() || mailEntry.item.itemMeta.customModelData != 11001)

        val choices = screens.renderChoices(opening(OpeningRecord.Stage.CHOOSING), misses = 2)
        assertEquals(2, choices.regions.getValue(EciaMenuConfiguration.OFFERS).size)
        assertTrue(choices.elements.getValue(MenuElementId.of("reroll")).enabled)
    }

    @Test
    fun nativePreviewNameSurvivesWithoutShowingRewardId() {
        val configuration = EciaMenuConfiguration.loadResource()
        val payload = NativeItemPayload()
        val native = ItemStack(Material.LEATHER)
        native.editMeta { it.displayName(Component.text("Кожаная перчатка", NamedTextColor.GREEN)) }
        val reward = RewardDefinition("reward-uuid", 1.0, true, "delivery", payload.items(arrayOf(native)))
        val opening = OpeningRecord(
            UUID.randomUUID(), UUID.randomUUID(), PoolSnapshot("case_daily", "summer", listOf(reward), 4, 3, 1),
            1L, 1L, 12L, OpeningRecord.Stage.CHOOSING, listOf(reward), false, 0, "",
            "key-witness", "", "", "",
        )

        val entry = EciaMenuScreens(configuration, payload)
            .renderChoices(opening)
            .regions.getValue(EciaMenuConfiguration.OFFERS)
            .single()
        val displayName = entry.item.itemMeta.displayName()?.toString().orEmpty()
        assertTrue(entry.enabled)
        assertTrue(displayName.contains("Кожаная перчатка"))
        assertFalse(displayName.contains("reward-uuid"))
    }

    @Test
    fun callbacksKeepOpeningRevisionAndRewardId() {
        val openingId = UUID.randomUUID()
        val calls = mutableListOf<String>()
        val actions = EciaMenuActions(
            choiceSelect = EciaMenuActions.ChoiceSelect { id, revision, reward -> calls += "$id:$revision:$reward" },
        )
        actions.choiceSelect.invoke(openingId, 7L, "rare")
        assertEquals("$openingId:7:rare", calls.single())
    }

    @Test
    fun revealCyclesSealsThenUncoversEachOffer() {
        assertEquals(OpeningRevealFrame(0, 0), OpeningRevealPlan.frame(0, 3))
        assertEquals(OpeningRevealFrame(2, 0), OpeningRevealPlan.frame(5, 3))
        assertEquals(OpeningRevealFrame(null, 1), OpeningRevealPlan.frame(6, 3))
        assertEquals(OpeningRevealFrame(null, 3), OpeningRevealPlan.frame(8, 3))

        val content = EciaMenuScreens(EciaMenuConfiguration.loadResource())
            .renderReveal(opening(OpeningRecord.Stage.CHOOSING), 6)
        assertEquals(2, content.regions.getValue(EciaMenuConfiguration.OFFERS).size)
        assertTrue(content.regions.getValue(EciaMenuConfiguration.OFFERS).none { it.enabled })
        assertEquals(null, content.background)
    }

    @Test
    fun poolPreviewHasNoFrameAndShowsOnlyUsefulPageArrows() {
        val configuration = EciaMenuConfiguration.loadResource()
        val screens = EciaMenuScreens(configuration)
        val rewards = (1..35).map { RewardDefinition("reward-$it", 1.0, it == 1, "delivery-$it", "") }
        val pool = PoolSnapshot("case_daily", "summer", rewards, 4, 3, 1)

        val first = screens.renderPoolPreview(pool, misses = 0, page = 0)
        val second = screens.renderPoolPreview(pool, misses = 0, page = 1)
        val expectedTitle = Component.text("Ежедневный тайник", NamedTextColor.DARK_GRAY)
            .decoration(TextDecoration.BOLD, false)
            .decoration(TextDecoration.ITALIC, false)

        assertEquals(expectedTitle, first.title)
        assertEquals(null, first.background)
        assertFalse(first.elements.containsKey(MenuElementId.of("previous")))
        assertTrue(first.elements.containsKey(MenuElementId.of("next")))
        assertTrue(second.elements.containsKey(MenuElementId.of("previous")))
        assertFalse(second.elements.containsKey(MenuElementId.of("next")))
    }

    private fun opening(stage: OpeningRecord.Stage, selected: String = ""): OpeningRecord {
        val common = RewardDefinition("common", 9.0, false, "deliver-common", "")
        val rare = RewardDefinition("rare", 1.0, true, "deliver-rare", "")
        return OpeningRecord(
            UUID.randomUUID(), UUID.randomUUID(), PoolSnapshot("crate", "summer", listOf(common, rare), 4, 3, 1),
            1L, 1L, 12L, stage, listOf(common, rare), false, 0, selected,
            "key-witness", "", "", "",
        )
    }

    private fun material(template: PaperMenuItemTemplate): Material =
        (template.source as PaperMenuItemSource.MaterialItem).material
}
