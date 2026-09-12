package ru.ruscrafting.ecia.screens

import net.kyori.adventure.text.Component
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
import java.util.UUID

/**
 * Typed actions emitted by ECIA screens. Page values are signed deltas:
 * `-1` means previous page and `+1` means next page. The open helpers apply
 * the delta to the core session before forwarding it to the owner.
 */
data class EciaMenuActions(
    val choiceSelect: ChoiceSelect = ChoiceSelect { _, _, _ -> },
    val reroll: Reroll = Reroll { _, _ -> },
    val claim: Claim = Claim { _, _ -> },
    val page: Page = Page { _, _ -> },
    val back: Back = Back {},
) {
    fun interface ChoiceSelect {
        fun invoke(opening: UUID, revision: Long, reward: String)
    }

    fun interface Reroll {
        fun invoke(opening: UUID, revision: Long)
    }

    fun interface Claim {
        fun invoke(opening: UUID, revision: Long)
    }

    fun interface Page {
        fun invoke(menu: MenuId, delta: Int)
    }

    fun interface Back {
        fun invoke()
    }
}

/**
 * Paper inventory views for opening choices, pending mail, history and a
 * frozen pool preview. It only renders content and invokes typed callbacks;
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

    fun renderChoices(
        opening: OpeningRecord,
        misses: Int,
        actions: EciaMenuActions = EciaMenuActions(),
    ): PaperMenuContent {
        require(misses >= 0) { "Pity misses must not be negative" }
        val progress = progress(opening.pool.pityThreshold(), misses)
        val values = mapOf(
            "crate" to caseName(opening.pool.crateId()),
            "season" to opening.pool.seasonId(),
            "progress" to progress,
            "threshold" to thresholdLabel(opening.pool.pityThreshold()),
            "rerolls" to "${opening.rerollsUsed()}/${opening.pool.maxRerolls()}",
            "guaranteed" to label(if (opening.guaranteed()) "guaranteed" else "ordinary"),
        )
        val offers = opening.offers().take(MAX_VISIBLE_CHOICES)
        val entries = offers.map { reward ->
            val share = weightShare(reward, opening.pool())
            val rendered = preview(
                reward,
                "choice",
                values = mapOf("weight" to share, "action" to label("choice-action")),
            )
            PaperMenuEntry(
                item = rendered.item,
                enabled = rendered.available,
                onClick = PaperMenuClickHandler {
                    if (rendered.available) actions.choiceSelect.invoke(opening.id(), opening.revision(), reward.id())
                },
            )
        }
        val remaining = (opening.pool.maxRerolls() - opening.rerollsUsed()).coerceAtLeast(0)
        val rerollAvailable = opening.stage() == OpeningRecord.Stage.CHOOSING && remaining > 0
        val rerollReason = when {
            opening.stage() != OpeningRecord.Stage.CHOOSING -> label("reroll-stage")
            remaining == 0 -> label("reroll-exhausted")
            opening.guaranteed() -> label("reroll-guarantee")
            else -> label("reroll-action")
        }
        val elements = linkedMapOf(
            element("info") to PaperMenuEntry(configured("choices-info", values), enabled = false),
            element("reroll") to PaperMenuEntry(
                configured(
                    "reroll",
                    mapOf("remaining" to remaining.toString(), "reason" to rerollReason),
                    buildSet {
                        if (rerollAvailable) add("available")
                        if (opening.guaranteed()) add("guaranteed")
                    },
                ),
                enabled = rerollAvailable,
                onClick = PaperMenuClickHandler {
                    actions.reroll.invoke(opening.id(), opening.revision())
                },
            ),
            element("back") to actionEntry("back") { actions.back.invoke() },
        )
        return PaperMenuContent(
            title = Component.text(label("title-choices")),
            background = background(),
            elements = elements,
            regions = mapOf(EciaMenuConfiguration.OFFERS to entries),
        )
    }

    fun renderChoices(
        opening: OpeningRecord,
        actions: EciaMenuActions = EciaMenuActions(),
    ): PaperMenuContent = renderChoices(opening, 0, actions)

    fun renderMail(
        openings: List<OpeningRecord>,
        actions: EciaMenuActions = EciaMenuActions(),
    ): PaperMenuContent {
        val pending = openings.count(OpeningRecord::pending)
        val entries = openings.map { opening ->
            val reward = selectedRewardOrNull(opening)
            val claimable = opening.stage() == OpeningRecord.Stage.MAIL && reward != null
            val state = stateLabel(opening.stage())
            val rendered = preview(
                reward,
                "mail-entry",
                values = mapOf(
                    "state" to state,
                    "action" to if (claimable) label("mail-claim") else label("mail-pending"),
                    "revision" to opening.revision().toString(),
                ),
                flags = buildSet {
                    if (claimable) add("claimable")
                    if (opening.stage() == OpeningRecord.Stage.REVIEW) add("review")
                },
            )
            PaperMenuEntry(
                item = rendered.item,
                enabled = claimable && rendered.available,
                onClick = PaperMenuClickHandler {
                    if (claimable && rendered.available) actions.claim.invoke(opening.id(), opening.revision())
                },
            )
        }
        return pagedContent(
            menu = EciaMenuConfiguration.MAIL,
            title = Component.text(label("title-mail")),
            info = configured("mail-info", mapOf("pending" to pending.toString())),
            entries = entries,
            region = EciaMenuConfiguration.ENTRIES,
            actions = actions,
        )
    }

    fun renderHistory(
        openings: List<OpeningRecord>,
        actions: EciaMenuActions = EciaMenuActions(),
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
            title = Component.text(label("title-history")),
            info = configured("history-info", mapOf("count" to openings.size.toString())),
            entries = entries,
            region = EciaMenuConfiguration.ENTRIES,
            actions = actions,
        )
    }

    fun renderPoolPreview(
        pool: PoolSnapshot,
        misses: Int,
        actions: EciaMenuActions = EciaMenuActions(),
    ): PaperMenuContent {
        require(misses >= 0) { "Pity misses must not be negative" }
        val entries = pool.rewards().map { reward ->
            val guaranteed = label(if (reward.guaranteeEligible()) "guarantee-yes" else "guarantee-no")
            val rendered = preview(
                reward,
                "pool-reward",
                values = mapOf(
                    "weight" to weightShare(reward, pool),
                    "guarantee" to guaranteed,
                ),
            )
            PaperMenuEntry(
                item = rendered.item,
                enabled = false,
            )
        }
        return pagedContent(
            menu = EciaMenuConfiguration.POOL_PREVIEW,
            title = Component.text(label("title-pool", "crate" to caseName(pool.crateId()))),
            info = configured(
                "pool-info",
                mapOf(
                    "crate" to caseName(pool.crateId()),
                    "season" to pool.seasonId(),
                    "progress" to progress(pool.pityThreshold(), misses),
                    "threshold" to thresholdLabel(pool.pityThreshold()),
                    "choices" to pool.choiceCount().toString(),
                    "rerolls" to pool.maxRerolls().toString(),
                ),
            ),
            entries = entries,
            region = EciaMenuConfiguration.REWARDS,
            actions = actions,
        )
    }

    fun openChoices(
        runtime: PaperMenuRuntime,
        player: Player,
        opening: OpeningRecord,
        misses: Int,
        actions: EciaMenuActions = EciaMenuActions(),
    ): PaperMenuSession = runtime.open(player, EciaMenuConfiguration.CHOICES) {
        renderChoices(opening, misses, actions)
    }

    fun openMail(
        runtime: PaperMenuRuntime,
        player: Player,
        openings: List<OpeningRecord>,
        actions: EciaMenuActions = EciaMenuActions(),
    ): PaperMenuSession = openPaged(runtime, player, EciaMenuConfiguration.MAIL, actions) { effective ->
        renderMail(openings, effective)
    }

    fun openHistory(
        runtime: PaperMenuRuntime,
        player: Player,
        openings: List<OpeningRecord>,
        actions: EciaMenuActions = EciaMenuActions(),
    ): PaperMenuSession = openPaged(runtime, player, EciaMenuConfiguration.HISTORY, actions) { effective ->
        renderHistory(openings, effective)
    }

    fun openPoolPreview(
        runtime: PaperMenuRuntime,
        player: Player,
        pool: PoolSnapshot,
        misses: Int,
        actions: EciaMenuActions = EciaMenuActions(),
    ): PaperMenuSession = openPaged(runtime, player, EciaMenuConfiguration.POOL_PREVIEW, actions) { effective ->
        renderPoolPreview(pool, misses, effective)
    }

    private fun openPaged(
        runtime: PaperMenuRuntime,
        player: Player,
        menu: MenuId,
        actions: EciaMenuActions,
        content: (EciaMenuActions) -> PaperMenuContent,
    ): PaperMenuSession {
        lateinit var session: PaperMenuSession
        val effective = actions.copy(
            page = EciaMenuActions.Page { id, delta ->
                val result = if (delta < 0) session.previousPage() else session.nextPage()
                if (result != PaperMenuSessionResult.UNCHANGED && result != PaperMenuSessionResult.NO_PAGINATION) {
                    actions.page.invoke(id, delta)
                }
            },
        )
        session = runtime.open(player, menu) { content(effective) }
        return session
    }

    private fun pagedContent(
        menu: MenuId,
        title: Component,
        info: ItemStack,
        entries: List<PaperMenuEntry>,
        region: ru.arc.menu.MenuRegionId,
        actions: EciaMenuActions,
    ): PaperMenuContent = PaperMenuContent(
        title = title,
        background = background(),
        elements = mapOf(
            element("info") to PaperMenuEntry(info, enabled = false),
            element("previous") to actionEntry("previous") { actions.page.invoke(menu, -1) },
            element("back") to actionEntry("back") { actions.back.invoke() },
            element("next") to actionEntry("next") { actions.page.invoke(menu, 1) },
        ),
        regions = mapOf(region to entries),
    )

    private fun actionEntry(template: String, action: () -> Unit): PaperMenuEntry = PaperMenuEntry(
        item = configured(template),
        onClick = PaperMenuClickHandler { action() },
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

    private fun progress(threshold: Int, misses: Int): String =
        if (threshold <= 0) label("pity-disabled") else "${misses.coerceAtMost(threshold - 1)}/${threshold - 1}"

    private fun thresholdLabel(threshold: Int): String =
        if (threshold <= 0) label("pity-disabled") else label("pity-threshold", "count" to (threshold - 1).toString())

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

    private companion object {
        const val MAX_VISIBLE_CHOICES = 3
    }
}
