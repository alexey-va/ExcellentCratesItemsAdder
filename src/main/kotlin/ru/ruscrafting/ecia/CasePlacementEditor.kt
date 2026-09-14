package ru.ruscrafting.ecia

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.FluidCollisionMode
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.data.Directional
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import ru.arc.paper.menu.PaperDialogActionId
import ru.arc.paper.menu.PaperDialogBody
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.menu.PaperDialogRuntime
import ru.arc.paper.menu.PaperDialogScreen
import ru.ruscrafting.ecia.integration.CrateModelPairing
import ru.ruscrafting.ecia.integration.ItemsAdderFurnitureAccess
import su.nightexpress.excellentcrates.CratesAPI
import su.nightexpress.excellentcrates.crate.impl.Crate
import su.nightexpress.excellentcrates.util.pos.WorldPos

/** Replaces the bare ExcellentCrates /case entry point with a two-step placement flow. */
internal class CasePlacementEditor(
    private val plugin: ArcExcellentCratesPlugin,
    private val registry: CrateRegistry,
    private val furniture: ItemsAdderFurnitureAccess,
    private val refreshed: () -> Unit,
) : Listener, AutoCloseable {
    private val dialogs = PaperDialogRuntime(plugin)

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onCommand(event: PlayerCommandPreprocessEvent) {
        val label = event.message.drop(1).trim().substringBefore(' ').substringBefore(':')
        if (!label.equals("case", ignoreCase = true)) return
        event.isCancelled = true
        val player = event.player
        if (!player.hasPermission(ADMIN_PERMISSION)) {
            message(player, "no-permission")
            return
        }
        val hit = player.getTargetBlockExact(MAX_DISTANCE, FluidCollisionMode.NEVER)
        val face = player.getTargetBlockFace(MAX_DISTANCE, FluidCollisionMode.NEVER)
        val target = if (face == null) null else hit?.getRelative(face)
        if (target == null) {
            message(player, "placement.no-target")
            return
        }
        if (!free(target)) {
            message(player, "placement.occupied")
            return
        }
        if (!CratesAPI.isLoaded()) {
            message(player, "placement.unavailable")
            return
        }
        dialogs.beginFlow(player)
        openPools(player, target)
    }

    private fun openPools(player: Player, target: Block) {
        val crates = CratesAPI.getCrateManager().crates.sortedBy { it.id }
        if (crates.isEmpty()) {
            message(player, "placement.no-pools")
            return
        }
        dialogs.open(player, PaperDialogScreen(
            id = "arc-excellent-crates.case.pool",
            title = text(if (english(player)) "Place a crate" else "Установить кейс", NamedTextColor.GOLD, true),
            body = listOf(PaperDialogBody(text(
                if (english(player)) "Target: ${target.world.name} · ${target.x}, ${target.y}, ${target.z}\nChoose a reward pool."
                else "Точка: ${target.world.name} · ${target.x}, ${target.y}, ${target.z}\nВыберите пул наград.",
                NamedTextColor.WHITE,
            ), 420)),
            buttons = crates.mapIndexed { index, crate ->
                button("pool_$index", crate.id) { openModels(it.player, target, crate, 0) }
            },
            exitButton = closeButton(player),
            columns = 2,
        ))
    }

    private fun openModels(player: Player, target: Block, crate: Crate, requestedPage: Int) {
        val registered = furniture.models()
            .filterNot { CrateModelPairing.isOpenVariant(it.namespacedId()) }
        val models = buildList {
            add(Model.Vanilla(Material.CHEST))
            add(Model.Vanilla(Material.TRAPPED_CHEST))
            add(Model.Vanilla(Material.BARREL))
            addAll(registered.map { Model.ItemsAdder(it.namespacedId()) })
        }
        val pages = maxOf(1, (models.size + PAGE_SIZE - 1) / PAGE_SIZE)
        val page = requestedPage.coerceIn(0, pages - 1)
        val registeredIds = registered.mapTo(linkedSetOf()) { it.namespacedId() }
        val choices = models.drop(page * PAGE_SIZE).take(PAGE_SIZE)
        val buttons = choices.mapIndexed { index, model ->
            val companion = (model as? Model.ItemsAdder)?.let {
                CrateModelPairing.openingModel(it.id, registeredIds)
            }
            val label = when (model) {
                is Model.Vanilla -> vanillaName(player, model.material)
                is Model.ItemsAdder -> model.id + if (companion != null) "  ↗" else ""
            }
            button("model_${page}_$index", label, closeBefore = true) { place(it.player, target, crate, model) }
        }.toMutableList()
        if (page > 0) buttons += button("models_previous", if (english(player)) "‹ Previous" else "‹ Назад") {
            openModels(it.player, target, crate, page - 1)
        }
        if (page + 1 < pages) buttons += button("models_next", if (english(player)) "Next ›" else "Дальше ›") {
            openModels(it.player, target, crate, page + 1)
        }
        dialogs.open(player, PaperDialogScreen(
            id = "arc-excellent-crates.case.model",
            title = text(crate.id, NamedTextColor.LIGHT_PURPLE, true),
            body = listOf(PaperDialogBody(text(
                if (english(player)) "Choose a shell · page ${page + 1}/$pages\n↗ means an opening twin was found."
                else "Выберите корпус · страница ${page + 1}/$pages\n↗ — найдена парная открытая модель.",
                NamedTextColor.WHITE,
            ), 420)),
            buttons = buttons,
            exitButton = PaperDialogButton(
                PaperDialogActionId.of("models_back"),
                text(if (english(player)) "‹ Reward pools" else "‹ Пулы наград", NamedTextColor.WHITE),
                width = 200,
                closeDialogBeforeAction = false,
            ) { openPools(it.player, target) },
            columns = 2,
        ))
    }

    private fun place(player: Player, target: Block, crate: Crate, model: Model) {
        if (!free(target)) {
            message(player, "placement.occupied")
            return
        }
        val previous = crate.blockPositions.mapNotNull { it.toLocation() }
        val shellPlaced = when (model) {
            is Model.Vanilla -> placeVanilla(player, target, model.material)
            is Model.ItemsAdder -> furniture.spawn(model.id, target).isPresent
        }
        if (!shellPlaced) {
            message(player, "placement.failed")
            return
        }
        val manager = CratesAPI.getCrateManager()
        runCatching {
            manager.removeCratePositions(crate)
            crate.addBlockPosition(target.location)
            crate.saveForce()
            manager.addCratePositions(crate)
            crate.recreateHologram()
        }.onSuccess {
            val count = registry.reload()
            plugin.runtime().updateRegistrySize(count)
            refreshed()
            player.closeInventory()
            message(player, "placement.placed", mapOf(
                "crate" to crate.id,
                "model" to model.id,
                "position" to "${target.world.name} ${target.x}, ${target.y}, ${target.z}",
            ))
        }.onFailure { failure ->
            plugin.runtime().warn("Crate placement failed for {}: {}", crate.id, failure.toString())
            rollbackShell(target, model)
            runCatching {
                manager.removeCratePositions(crate)
                crate.clearBlockPositions()
                previous.forEach(crate::addBlockPosition)
                crate.saveForce()
                manager.addCratePositions(crate)
                crate.recreateHologram()
            }
            message(player, "placement.failed")
        }
    }

    private fun placeVanilla(player: Player, target: Block, material: Material): Boolean = runCatching {
        target.type = material
        val directional = target.blockData as? Directional
        if (directional != null) {
            directional.facing = player.facing.oppositeFace
            target.blockData = directional
        }
        true
    }.getOrDefault(false)

    private fun rollbackShell(target: Block, model: Model) {
        when (model) {
            is Model.Vanilla -> target.type = Material.AIR
            is Model.ItemsAdder -> furniture.remove(target)
        }
    }

    private fun free(block: Block): Boolean = block.isEmpty
        && CratesAPI.getCrateManager().getCrateByLocation(block.location) == null
        && furniture.at(block).isEmpty

    private fun message(player: Player, key: String, values: Map<String, String> = emptyMap()) {
        player.sendMessage(plugin.runtime().locale().renderPadded(key, player, values))
    }

    private fun button(
        id: String,
        label: String,
        closeBefore: Boolean = false,
        action: (ru.arc.paper.menu.PaperDialogClickContext) -> Unit,
    ) = PaperDialogButton(
        id = PaperDialogActionId.of(id),
        label = text(label, NamedTextColor.LIGHT_PURPLE),
        width = 260,
        closeDialogBeforeAction = closeBefore,
        onClick = action,
    )

    private fun closeButton(player: Player) = PaperDialogButton(
        PaperDialogActionId.of("case_close"),
        text(if (english(player)) "Close" else "Закрыть", NamedTextColor.WHITE),
        width = 200,
        closeDialogBeforeAction = true,
    ) {}

    private fun text(value: String, color: NamedTextColor, bold: Boolean = false): Component =
        Component.text(value, color)
            .decoration(TextDecoration.ITALIC, false)
            .decoration(TextDecoration.BOLD, bold)

    private fun english(player: Player) = player.locale().language.equals("en", ignoreCase = true)

    private fun vanillaName(player: Player, material: Material): String = when (material) {
        Material.CHEST -> if (english(player)) "Vanilla chest" else "Ванильный сундук"
        Material.TRAPPED_CHEST -> if (english(player)) "Trapped chest" else "Сундук-ловушка"
        Material.BARREL -> if (english(player)) "Vanilla barrel" else "Ванильная бочка"
        else -> material.name
    }

    override fun close() {
        HandlerList.unregisterAll(this)
        dialogs.close()
    }

    private sealed interface Model {
        val id: String

        data class Vanilla(val material: Material) : Model {
            override val id = "minecraft:${material.name.lowercase()}"
        }

        data class ItemsAdder(override val id: String) : Model
    }

    private companion object {
        const val ADMIN_PERMISSION = "ecia.admin"
        const val MAX_DISTANCE = 12
        const val PAGE_SIZE = 10
    }
}
