package ru.ruscrafting.ecia.integration

import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryView
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import su.nightexpress.excellentcrates.CratesPlugin
import su.nightexpress.excellentcrates.api.crate.Reward
import su.nightexpress.excellentcrates.crate.CrateManager
import su.nightexpress.excellentcrates.crate.impl.Crate
import su.nightexpress.excellentcrates.crate.impl.CrateSource
import su.nightexpress.excellentcrates.crate.menu.PreviewMenu
import su.nightexpress.nightcore.ui.menu.MenuRegistry
import su.nightexpress.nightcore.ui.menu.MenuViewer
import su.nightexpress.nightcore.ui.menu.data.MenuFiller
import su.nightexpress.nightcore.ui.menu.item.MenuItem
import su.nightexpress.nightcore.util.bukkit.NightItem
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.UUID
import java.util.function.Function

/** Admin-only reward issuance from the native ExcellentCrates reward preview. */
internal class NativePreviewRewardGrantService(private val plugin: Plugin) : Listener, AutoCloseable {
    private val fillerFields = MenuFillerFields.discover()
    private val snapshots = mutableMapOf<UUID, CapturedPreview>()
    private val pendingCapture = mutableMapOf<UUID, PendingCapture>()
    private var registered = false

    init {
        when {
            fillerFields == null -> plugin.logger.warning(
                "Native preview reward grants disabled: MenuFiller 2.16.4 fields do not match the verified schema",
            )
            currentNativeContext() == null -> plugin.logger.warning(
                "Native preview reward grants disabled: ExcellentCrates 6.6.1 / NightCore 2.16.4 runtime provenance is unavailable",
            )
            else -> {
                plugin.server.pluginManager.registerEvents(this, plugin)
                registered = true
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onInventoryOpen(event: InventoryOpenEvent) {
        val player = event.player as? Player ?: return
        if (!player.hasPermission(ADMIN_PERMISSION)) {
            clear(player.uniqueId)
            return
        }
        snapshots.remove(player.uniqueId)
        scheduleCapture(player, event.inventory, 1L)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onInventoryClose(event: InventoryCloseEvent) {
        val player = event.player as? Player ?: return
        val id = player.uniqueId
        if (snapshots[id]?.snapshot?.topInventory === event.inventory) snapshots.remove(id)
        if (pendingCapture[id]?.topInventory === event.inventory) pendingCapture.remove(id)?.task?.cancel()
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlayerQuit(event: PlayerQuitEvent) = clear(event.player.uniqueId)

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        if (!player.hasPermission(ADMIN_PERMISSION)) {
            clear(player.uniqueId)
            return
        }
        val viewer = MenuRegistry.getViewer(player) ?: return
        val preview = viewer.menu as? PreviewMenu ?: return
        val captured = snapshots[player.uniqueId]
        val snapshot = captured?.snapshot

        if (captured == null || snapshot == null || !snapshot.matches(viewer, preview, event.view.topInventory)) {
            scheduleCapture(player, event.view.topInventory, 2L)
            return
        }

        val reward = NativePreviewGrantPolicy.resolve(
            permitted = player.hasPermission(ADMIN_PERMISSION),
            click = event.click,
            shiftClick = event.isShiftClick,
            rawSlot = event.rawSlot,
            topSize = snapshot.topInventory.size,
            clickedInventory = event.clickedInventory,
            eventTopInventory = event.view.topInventory,
            currentViewer = viewer,
            currentMenu = preview,
            currentPage = viewer.page,
            clickedItem = event.currentItem,
            snapshot = snapshot,
        )
        if (reward == null) {
            if (event.clickedInventory === snapshot.topInventory) {
                scheduleCapture(player, snapshot.topInventory, 2L)
            }
            return
        }

        val native = currentNativeContext() ?: run {
            snapshots.remove(player.uniqueId)
            return
        }
        if (!captured.matches(native, reward)) {
            snapshots.remove(player.uniqueId)
            return
        }

        // Native PreviewMenu cancels menu clicks. Set it before issuance too,
        // so the displayed preview item cannot be moved by the same event.
        event.isCancelled = true
        plugin.logger.info(
            "admin_preview_reward_grant_attempt player_uuid=${player.uniqueId} crate_id=${captured.crate.id} reward_id=${reward.id}",
        )
        try {
            native.manager.giveReward(player, reward)
        } catch (failure: RuntimeException) {
            plugin.logger.log(
                java.util.logging.Level.SEVERE,
                "Native preview reward grant failed for ${player.name} (${reward.id}); no retry was attempted",
                failure,
            )
        }
    }

    private fun scheduleCapture(player: Player, topInventory: Inventory, delay: Long) {
        val id = player.uniqueId
        pendingCapture.remove(id)?.task?.cancel()
        snapshots.remove(id)
        val task = plugin.server.scheduler.runTaskLater(plugin, Runnable {
            pendingCapture.remove(id)
            capture(player, topInventory)
        }, delay)
        pendingCapture[id] = PendingCapture(topInventory, task)
    }

    private fun capture(player: Player, expectedTopInventory: Inventory) {
        if (!player.hasPermission(ADMIN_PERMISSION)) {
            clear(player.uniqueId)
            return
        }
        val native = currentNativeContext() ?: return
        val viewer = MenuRegistry.getViewer(player) ?: return
        val preview = viewer.menu as? PreviewMenu ?: return
        val view = viewer.view ?: return
        if (viewer.player !== player || view.topInventory !== expectedTopInventory) return
        if (player.openInventory.topInventory !== expectedTopInventory) return

        val source = preview.getLink(viewer) as? CrateSource ?: return
        val crate = source.crate
        if (!isCurrent(native, preview, crate)) return
        val filler = try {
            preview.createFiller(viewer)
        } catch (failure: RuntimeException) {
            plugin.logger.warning("Native preview reward snapshot unavailable: ${failure.message}")
            return
        }
        val contents = fillerFields?.read(filler) ?: return
        val page = viewer.page
        if (viewer.pages != pageCount(contents.items.size, contents.slots.size)) return
        val pageItems = NativePreviewGrantPolicy.projectPage(
            contents.items,
            contents.slots,
            page,
            expectedTopInventory.size,
        ) ?: return

        val currentMenuItems = viewer.items.toList()
        val pageRewards = mutableMapOf<Int, NativePreviewDisplayedReward>()
        val crateStacks = try {
            crate.rewards.map { reward -> reward to render(contents.itemCreator.apply(reward), preview, player) }
        } catch (failure: RuntimeException) {
            plugin.logger.warning("Native preview reward snapshot failed closed: ${failure.message}")
            return
        }

        for ((slot, reward) in pageItems) {
            if (crate.getReward(reward.id) !== reward) return
            val itemsAtSlot = currentMenuItems.filter { slot in it.slots }
            val rewardMenuItems = itemsAtSlot.filter { it.priority == FILLER_PRIORITY }
            if (rewardMenuItems.size != 1 || itemsAtSlot.any { it.priority > FILLER_PRIORITY }) return
            val menuItem = rewardMenuItems.single()
            if (!menuItem.slots.contentEquals(intArrayOf(slot)) || menuItem.handler?.options != null) return
            if (preview.getItem(viewer, slot) !== menuItem) return

            val menuStack = render(menuItem.item, preview, player)
            val currentStack = expectedTopInventory.getItem(slot) ?: return
            val rewardStack = try {
                render(contents.itemCreator.apply(reward), preview, player)
            } catch (failure: RuntimeException) {
                plugin.logger.warning("Native preview reward snapshot failed closed: ${failure.message}")
                return
            }
            if (!sameNativePreviewStack(menuStack, currentStack) || !sameNativePreviewStack(rewardStack, menuStack)) return

            val visuallyAmbiguous = crateStacks.any { (other, stack) ->
                other !== reward && sameNativePreviewStack(stack, rewardStack)
            }
            if (!visuallyAmbiguous) {
                pageRewards[slot] = NativePreviewDisplayedReward(reward, currentStack.clone(), menuItem)
            }
        }
        if (pageRewards.isEmpty()) return

        val snapshot = NativePreviewRewardSnapshot(
            viewer,
            preview,
            source,
            view,
            expectedTopInventory,
            page,
            pageRewards.toMap(),
        )
        snapshots[player.uniqueId] = CapturedPreview(native.plugin, native.manager, source, crate, snapshot)
    }

    private fun render(item: NightItem, menu: PreviewMenu, player: Player): ItemStack {
        val displayed = item.copy()
        if (menu.isApplyPlaceholderAPI) {
            displayed.replacement { replacer -> replacer.replacePlaceholderAPI(player) }
        }
        return displayed.itemStack.clone()
    }

    private fun currentNativeContext(): NativeContext? {
        val pluginManager = plugin.server.pluginManager
        val nativeOwner = pluginManager.getPlugin(EXCELLENT_CRATES_PLUGIN) ?: return null
        val nightCore = pluginManager.getPlugin(NIGHTCORE_PLUGIN) ?: return null
        if (!nativeOwner.isEnabled || nativeOwner.description.version != EXCELLENT_CRATES_VERSION) return null
        if (!nightCore.isEnabled || nightCore.description.version != NIGHTCORE_VERSION) return null
        val nativePlugin = nativeOwner as? CratesPlugin ?: return null
        return NativeContext(nativePlugin, nativePlugin.crateManager)
    }

    private fun isCurrent(native: NativeContext, preview: PreviewMenu, crate: Crate): Boolean =
        native.manager.getCrateById(crate.id) === crate &&
            native.manager.getPreviewById(crate.previewId) === preview

    private fun clear(id: UUID) {
        snapshots.remove(id)
        pendingCapture.remove(id)?.task?.cancel()
    }

    override fun close() {
        if (registered) {
            org.bukkit.event.HandlerList.unregisterAll(this)
            registered = false
        }
        pendingCapture.values.forEach { it.task.cancel() }
        pendingCapture.clear()
        snapshots.clear()
    }

    private data class NativeContext(val plugin: CratesPlugin, val manager: CrateManager)

    private data class CapturedPreview(
        val nativePlugin: CratesPlugin,
        val manager: CrateManager,
        val source: CrateSource,
        val crate: Crate,
        val snapshot: NativePreviewRewardSnapshot,
    ) {
        fun matches(native: NativeContext, reward: Reward): Boolean =
            native.plugin === nativePlugin && native.manager === manager &&
                manager.getCrateById(crate.id) === crate &&
                manager.getPreviewById(crate.previewId) === snapshot.preview &&
                source.crate === crate && crate.getReward(reward.id) === reward
    }

    private data class PendingCapture(val topInventory: Inventory, val task: BukkitTask)

    private data class FillerContents(
        val slots: IntArray,
        val items: List<Reward>,
        val itemCreator: Function<Reward, NightItem>,
    )

    private data class MenuFillerFields(
        private val slots: Field,
        private val items: Field,
        private val itemCreator: Field,
    ) {
        fun read(filler: MenuFiller<Reward>): FillerContents? {
            if (filler.javaClass !== MenuFiller::class.java) return null
            val rawSlots = slots.get(filler) as? IntArray ?: return null
            val rawItems = items.get(filler) as? List<*> ?: return null
            val rawCreator = itemCreator.get(filler) as? Function<*, *> ?: return null
            if (rawItems.any { it !is Reward }) return null
            @Suppress("UNCHECKED_CAST")
            val rewardItems = rawItems as List<Reward>
            @Suppress("UNCHECKED_CAST")
            val creator = rawCreator as Function<Reward, NightItem>
            return FillerContents(rawSlots.clone(), rewardItems.toList(), creator)
        }

        companion object {
            fun discover(): MenuFillerFields? = runCatching {
                fun verified(name: String, type: Class<*>): Field {
                    val field = MenuFiller::class.java.getDeclaredField(name)
                    check(field.type === type)
                    check(Modifier.isPrivate(field.modifiers) && Modifier.isFinal(field.modifiers))
                    check(field.trySetAccessible())
                    return field
                }
                MenuFillerFields(
                    verified("slots", IntArray::class.java),
                    verified("items", java.util.Collection::class.java),
                    verified("itemCreator", Function::class.java),
                )
            }.getOrNull()
        }
    }

    private companion object {
        const val ADMIN_PERMISSION = "ecia.admin"
        const val EXCELLENT_CRATES_PLUGIN = "ExcellentCrates"
        const val NIGHTCORE_PLUGIN = "nightcore"
        const val EXCELLENT_CRATES_VERSION = "6.6.1"
        const val NIGHTCORE_VERSION = "2.16.4"
        const val FILLER_PRIORITY = 100

        fun pageCount(items: Int, slots: Int): Int = if (slots <= 0) 0 else (items + slots - 1) / slots
    }
}

internal data class NativePreviewDisplayedReward(
    val reward: Reward,
    val stack: ItemStack,
    val menuItem: MenuItem,
)

internal data class NativePreviewRewardSnapshot(
    val viewer: MenuViewer,
    val preview: PreviewMenu,
    val source: CrateSource,
    val view: InventoryView,
    val topInventory: Inventory,
    val page: Int,
    val rewardsBySlot: Map<Int, NativePreviewDisplayedReward>,
) {
    fun matches(viewer: MenuViewer, preview: PreviewMenu, topInventory: Inventory): Boolean =
        this.viewer === viewer && this.preview === preview && this.topInventory === topInventory &&
            viewer.menu === preview && viewer.view === view && viewer.page == page &&
            preview.getLink(viewer) === source && rewardsBySlot.all { (slot, entry) ->
            viewer.items.any { it === entry.menuItem } &&
                    preview.getItem(viewer, slot) === entry.menuItem &&
                    entry.menuItem.priority == FILLER_PRIORITY &&
                    entry.menuItem.slots.contentEquals(intArrayOf(slot))
            }

    companion object {
        private const val FILLER_PRIORITY = 100
    }
}

internal object NativePreviewGrantPolicy {
    fun <R> projectPage(items: List<R>, slots: IntArray, page: Int, topSize: Int): Map<Int, R>? {
        if (slots.isEmpty() || page < 1 || topSize <= 0) return null
        if (slots.any { it !in 0 until topSize } || slots.distinct().size != slots.size) return null
        val pageCount = (items.size + slots.size - 1) / slots.size
        if (page > pageCount) return null
        val start = (page - 1) * slots.size
        if (start !in 0..items.size) return null
        val visible = items.drop(start).take(slots.size)
        return slots.take(visible.size).zip(visible).toMap()
    }

    fun resolve(
        permitted: Boolean,
        click: ClickType,
        shiftClick: Boolean,
        rawSlot: Int,
        topSize: Int,
        clickedInventory: Inventory?,
        eventTopInventory: Inventory,
        currentViewer: MenuViewer,
        currentMenu: PreviewMenu,
        currentPage: Int,
        clickedItem: ItemStack?,
        snapshot: NativePreviewRewardSnapshot,
    ): Reward? {
        if (!permitted || click != ClickType.LEFT || shiftClick) return null
        if (rawSlot !in 0 until topSize || clickedInventory !== snapshot.topInventory) return null
        if (eventTopInventory !== snapshot.topInventory) return null
        if (!snapshot.matches(currentViewer, currentMenu, eventTopInventory) || currentPage != snapshot.page) return null
        val entry = snapshot.rewardsBySlot[rawSlot] ?: return null
        val clicked = clickedItem ?: return null
        val live = eventTopInventory.getItem(rawSlot) ?: return null
        if (!sameNativePreviewStack(entry.stack, clicked) || !sameNativePreviewStack(entry.stack, live)) return null
        return entry.reward
    }
}

internal fun sameNativePreviewStack(first: ItemStack, second: ItemStack): Boolean =
    first.type != Material.AIR && second.type != Material.AIR &&
        first.amount == second.amount && first.isSimilar(second)
