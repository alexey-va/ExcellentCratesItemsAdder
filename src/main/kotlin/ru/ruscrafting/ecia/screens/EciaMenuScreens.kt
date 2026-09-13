package ru.ruscrafting.ecia.screens

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import ru.arc.menu.MenuElementId
import ru.arc.menu.MenuId
import ru.arc.paper.menu.PaperMenuClickHandler
import ru.arc.paper.menu.PaperMenuConfiguration
import ru.arc.paper.menu.PaperMenuContent
import ru.arc.paper.menu.PaperMenuEntry
import ru.arc.paper.menu.PaperMenuItemFactory
import ru.arc.paper.menu.PaperMenuItemRenderContext
import ru.arc.paper.menu.PaperMenuRuntime
import ru.arc.paper.menu.PaperMenuSession
import ru.arc.paper.menu.PaperMenuSessionResult
import ru.ruscrafting.ecia.inventory.NativeItemPayload
import ru.ruscrafting.ecia.journal.OpeningRecord
import ru.ruscrafting.ecia.roll.PoolSnapshot
import ru.ruscrafting.ecia.roll.RewardDefinition
import java.util.Locale

/**
 * Typed actions emitted by ECIA screens. Page values are signed deltas:
 * `-1` means previous page and `+1` means next page. The open helpers apply
 * the delta to the core session before forwarding it to the owner.
 */
data class EciaMenuActions(
    val page: Page = Page { _, _ -> },
) {
    fun interface Page {
        fun invoke(menu: MenuId, delta: Int)
    }

}

/**
 * Paper inventory views for opening history and a frozen pool preview.
 * It only renders content and invokes typed callbacks;
 * durable opening mutations remain in the parent service.
 */
class EciaMenuScreens(
    private val configuration: PaperMenuConfiguration,
    private val nativeItems: NativeItemPayload = NativeItemPayload(),
    private val itemFactory: PaperMenuItemFactory = PaperMenuItemFactory(),
    private val caseNames: Map<String, String> = EciaMenuConfiguration.caseNames(configuration),
    private val caseNameFallback: String = EciaMenuConfiguration.caseNameFallback(configuration),
    private val labels: Map<String, String> = EciaMenuConfiguration.labels(configuration),
) {
    init {
        require(labels.keys.containsAll(EciaMenuConfiguration.requiredLabels)) { "Menu labels are incomplete" }
    }

    fun renderHistory(
        openings: List<OpeningRecord>,
        actions: EciaMenuActions = EciaMenuActions(),
        page: Int = 0,
    ): PaperMenuContent {
        val entries = openings.map { opening ->
            val reward = selectedRewardOrNull(opening)
            val rendered = preview(
                reward,
                "history-entry",
                values = mapOf(
                    "state" to stateLabel(opening.stage()),
                    "revision" to opening.revision().toString(),
                ),
            )
            PaperMenuEntry(
                item = rendered.item,
                enabled = false,
            )
        }
        return pagedContent(
            menu = EciaMenuConfiguration.HISTORY,
            title = darkTitle(label("title-history")),
            info = configured("history-info", mapOf("count" to openings.size.toString())),
            entries = entries,
            region = EciaMenuConfiguration.ENTRIES,
            actions = actions,
            page = page,
        )
    }

    fun renderPoolPreview(
        pool: PoolSnapshot,
        actions: EciaMenuActions = EciaMenuActions(),
        page: Int = 0,
    ): PaperMenuContent {
        val entries = pool.rewards().map { reward ->
            val rendered = preview(
                reward,
                "pool-reward",
                values = mapOf("weight" to weightShare(reward, pool)),
            )
            PaperMenuEntry(
                item = rendered.item,
                enabled = false,
            )
        }
        val menu = EciaMenuConfiguration.POOL_PREVIEW
        require(page >= 0) { "Menu page must not be negative" }
        val capacity = configuration.catalog.require(menu).region(EciaMenuConfiguration.REWARDS).size
        val lastPage = if (entries.isEmpty()) 0 else (entries.size - 1) / capacity
        val previous = page > 0
        val next = page < lastPage
        return PaperMenuContent(
            title = darkTitle(label("title-pool", "crate" to caseName(pool.crateId()))),
            elements = mapOf(
                element("previous") to actionEntry("previous", previous) {
                    if (previous) actions.page.invoke(menu, -1)
                },
                element("next") to actionEntry("next", next) {
                    if (next) actions.page.invoke(menu, 1)
                },
            ),
            regions = mapOf(
                EciaMenuConfiguration.REWARDS to entries,
                EciaMenuConfiguration.FOOTER to List(7) { PaperMenuEntry(background(), enabled = false) },
            ),
        )
    }

    fun openHistory(
        runtime: PaperMenuRuntime,
        player: Player,
        openings: List<OpeningRecord>,
        actions: EciaMenuActions = EciaMenuActions(),
    ): PaperMenuSession = openPaged(runtime, player, EciaMenuConfiguration.HISTORY, EciaMenuConfiguration.ENTRIES,
        openings.size, actions) { effective, page ->
        renderHistory(openings, effective, page)
    }

    fun openPoolPreview(
        runtime: PaperMenuRuntime,
        player: Player,
        pool: PoolSnapshot,
        actions: EciaMenuActions = EciaMenuActions(),
    ): PaperMenuSession = openPaged(runtime, player, EciaMenuConfiguration.POOL_PREVIEW, EciaMenuConfiguration.REWARDS,
        pool.rewards().size, actions) { effective, page ->
        renderPoolPreview(pool, effective, page)
    }

    private fun openPaged(
        runtime: PaperMenuRuntime,
        player: Player,
        menu: MenuId,
        region: ru.arc.menu.MenuRegionId,
        entryCount: Int,
        actions: EciaMenuActions,
        content: (EciaMenuActions, Int) -> PaperMenuContent,
    ): PaperMenuSession {
        val capacity = configuration.catalog.require(menu).region(region).size
        val lastPage = if (entryCount == 0) 0 else (entryCount - 1) / capacity
        var page = 0
        lateinit var session: PaperMenuSession
        val effective = actions.copy(
            page = EciaMenuActions.Page { id, delta ->
                val target = (page + delta).coerceIn(0, lastPage)
                if (target == page) return@Page
                page = target
                val result = session.setPage(page)
                if (result != PaperMenuSessionResult.UNCHANGED && result != PaperMenuSessionResult.NO_PAGINATION) {
                    actions.page.invoke(id, delta)
                }
            },
        )
        session = runtime.open(player, menu) { content(effective, page) }
        return session
    }

    private fun pagedContent(
        menu: MenuId,
        title: Component,
        info: ItemStack,
        entries: List<PaperMenuEntry>,
        region: ru.arc.menu.MenuRegionId,
        actions: EciaMenuActions,
        page: Int,
        menuBackground: ItemStack? = background(),
    ): PaperMenuContent {
        require(page >= 0) { "Menu page must not be negative" }
        val capacity = configuration.catalog.require(menu).region(region).size
        val lastPage = if (entries.isEmpty()) 0 else (entries.size - 1) / capacity
        val elements = linkedMapOf(
            element("info") to PaperMenuEntry(info, enabled = false),
        )
        if (page > 0) elements[element("previous")] = actionEntry("previous") { actions.page.invoke(menu, -1) }
        if (page < lastPage) elements[element("next")] = actionEntry("next") { actions.page.invoke(menu, 1) }
        return PaperMenuContent(
            title = title,
            background = menuBackground,
            elements = elements,
            regions = mapOf(region to entries),
        )
    }

    private fun actionEntry(template: String, action: () -> Unit): PaperMenuEntry = PaperMenuEntry(
        item = configured(template),
        onClick = PaperMenuClickHandler { action() },
    )

    private fun actionEntry(template: String, enabled: Boolean, action: () -> Unit): PaperMenuEntry = PaperMenuEntry(
        item = configured(template),
        enabled = enabled,
        onClick = PaperMenuClickHandler { if (enabled) action() },
    )

    private fun configured(
        template: String,
        values: Map<String, String> = emptyMap(),
        flags: Set<String> = emptySet(),
    ): ItemStack = itemFactory.create(
        configuration.template(ru.arc.menu.MenuTemplateId.of(template)),
        PaperMenuItemRenderContext(values.mapValues { (_, value) -> Component.text(value) }, flags, emptyMap()),
    )

    private fun background(): ItemStack = itemFactory.create(
        configuration.template(ru.arc.menu.MenuTemplateId.of("background")),
        Component.text(" "),
        emptyList(),
    )

    private fun preview(
        reward: RewardDefinition?,
        fallbackTemplate: String,
        values: Map<String, String> = emptyMap(),
        flags: Set<String> = emptySet(),
    ): Preview {
        val styled = configured(fallbackTemplate, values, flags)
        val decoded = reward?.let { current ->
            runCatching { nativeItems.items(current.previewPayload()).firstOrNull() }.getOrNull()
        }
        if (decoded == null) return Preview(configured("unavailable"), available = false)

        val styleMeta = styled.itemMeta
        val item = decoded.clone()
        val hasNativeName = item.itemMeta.hasDisplayName()
        item.editMeta { meta ->
            if (!hasNativeName) styleMeta.displayName()?.let(meta::displayName)
            styleMeta.lore()?.let(meta::lore)
        }
        return Preview(item, available = true)
    }

    private fun selectedRewardOrNull(opening: OpeningRecord): RewardDefinition? =
        opening.selectedRewardId().takeIf(String::isNotEmpty)?.let { id ->
            opening.offers().firstOrNull { it.id() == id }
        }

    private fun element(id: String) = MenuElementId.of(id)

    private fun caseName(crateId: String): String = caseNames[crateId.lowercase(Locale.ROOT)] ?: caseNameFallback

    private fun darkTitle(text: String): Component = Component.text(text, NamedTextColor.DARK_GRAY)
        .decoration(TextDecoration.BOLD, false)
        .decoration(TextDecoration.ITALIC, false)

    private fun weightShare(reward: RewardDefinition, pool: PoolSnapshot): String {
        val total = pool.rewards().sumOf(RewardDefinition::weight)
        if (total <= 0.0) return "0.0%"
        return String.format(Locale.ROOT, "%.1f%%", reward.weight() / total * 100.0)
    }

    private fun stateLabel(stage: OpeningRecord.Stage): String = when (stage) {
        OpeningRecord.Stage.RESERVED -> label("status-reserved")
        OpeningRecord.Stage.CHOOSING -> label("status-choosing")
        OpeningRecord.Stage.MAIL -> label("status-mail")
        OpeningRecord.Stage.DELIVERING -> label("status-delivering")
        OpeningRecord.Stage.DELIVERED -> label("status-delivered")
        OpeningRecord.Stage.REVIEW -> label("status-review")
        OpeningRecord.Stage.ABORTED -> label("status-aborted")
    }

    private fun label(key: String, vararg values: Pair<String, String>): String =
        values.fold(requireNotNull(labels[key]) { "Missing menu label '$key'" }) { text, (name, value) ->
            text.replace("<$name>", value)
        }

    private data class Preview(val item: ItemStack, val available: Boolean)
}
