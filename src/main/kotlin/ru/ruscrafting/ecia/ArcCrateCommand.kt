package ru.ruscrafting.ecia

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.FluidCollisionMode
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.data.Directional
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabExecutor
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import ru.arc.network.BackendServerId
import ru.arc.network.NetworkPlayerName
import ru.arc.paper.menu.PaperDialogActionId
import ru.arc.paper.menu.PaperDialogBody
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.menu.PaperDialogClickContext
import ru.arc.paper.menu.PaperDialogInputId
import ru.arc.paper.menu.PaperDialogNumberRangeInput
import ru.arc.paper.menu.PaperDialogRuntime
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.paper.menu.PaperDialogTextInput
import ru.ruscrafting.ecia.integration.CrateModelPairing
import ru.ruscrafting.ecia.integration.ItemsAdderFurnitureAccess
import ru.ruscrafting.ecia.integration.ManagedCratesSettings
import ru.ruscrafting.ecia.integration.NativeSeasonKeys
import su.nightexpress.excellentcrates.CratesAPI
import su.nightexpress.excellentcrates.crate.cost.entry.impl.KeyCostEntry
import su.nightexpress.excellentcrates.crate.impl.Crate
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

/** Standalone administrator entry point for placement, editing guidance, and network key grants. */
internal class ArcCrateCommand(
    private val plugin: ArcExcellentCratesPlugin,
    private val registry: CrateRegistry,
    private val furniture: ItemsAdderFurnitureAccess,
    private val receiver: NetworkKeyReceiver,
    private val refreshed: () -> Unit,
) : TabExecutor, AutoCloseable {
    private val dialogs = PaperDialogRuntime(plugin)
    private val nativeKeys = NativeSeasonKeys()
    private val command = checkNotNull(plugin.getCommand(COMMAND)) { "Missing /$COMMAND in plugin.yml" }

    init {
        command.setExecutor(this)
        command.tabCompleter = this
    }

    override fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>,
    ): Boolean {
        if (!sender.hasPermission(ARC_CRATE_ADMIN_PERMISSION)) {
            message(sender, "no-permission")
            return true
        }
        return when (args.firstOrNull()?.lowercase(Locale.ROOT)) {
            null, "menu" -> openMenu(sender)
            "place" -> beginPlacement(sender)
            "key", "give" -> grantFromCommand(sender, args.drop(1))
            "receive-key" -> receiver.receive(sender, args.drop(1))
            else -> {
                message(sender, "command.usage")
                true
            }
        }
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>,
    ): List<String> {
        if (!sender.hasPermission(ARC_CRATE_ADMIN_PERMISSION)) return emptyList()
        val choices = when (args.size) {
            1 -> listOf("menu", "place", "key")
            2 -> if (args[0].equals("key", true) || args[0].equals("give", true)) {
                plugin.server.onlinePlayers.map(Player::getName)
            } else emptyList()
            3 -> if (args[0].equals("key", true) || args[0].equals("give", true)) {
                runCatching(::keyChoices).getOrDefault(emptyList()).map(KeyChoice::id)
            } else emptyList()
            4 -> if (args[0].equals("key", true) || args[0].equals("give", true)) {
                listOf("1", "2", "5", "10", "16", "32", "64") + backends().map(BackendServerId::value)
            } else emptyList()
            5 -> if (args[0].equals("key", true) || args[0].equals("give", true)) backends().map(BackendServerId::value) else emptyList()
            else -> emptyList()
        }
        val prefix = args.lastOrNull().orEmpty()
        return choices.filter { it.startsWith(prefix, ignoreCase = true) }.sorted()
    }

    private fun openMenu(sender: CommandSender): Boolean {
        val player = sender as? Player ?: run {
            message(sender, "command.usage")
            return true
        }
        if (!ready(player)) return true
        dialogs.beginFlow(player)
        openRoot(player)
        return true
    }

    private fun openRoot(player: Player) {
        dialogs.open(player, PaperDialogScreen(
            id = "arc-excellent-crates.admin",
            title = text(if (english(player)) "Crate control" else "Управление кейсами", NamedTextColor.GOLD, true),
            body = listOf(PaperDialogBody(text(
                if (english(player)) {
                    "Place a new crate where you are looking, or grant a physical key on a selected server.\nShift + left-click an existing crate for detailed visual settings."
                } else {
                    "Установите новый кейс в точку прицела или выдайте физический ключ на выбранном сервере.\nShift + ЛКМ по установленному кейсу — тонкая настройка визуала."
                },
                NamedTextColor.WHITE,
            ), 468)),
            buttons = listOf(
                button("admin_place", if (english(player)) "Place a crate ›" else "Установить кейс ›", NamedTextColor.GREEN) {
                    beginPlacement(it.player)
                },
                button("admin_keys", if (english(player)) "Grant a key ›" else "Выдать ключ ›", NamedTextColor.GOLD) {
                    openKeys(it.player, 0)
                },
            ),
            exitButton = closeButton(player),
            columns = 2,
        ))
    }

    private fun beginPlacement(sender: CommandSender): Boolean {
        val player = sender as? Player ?: run {
            message(sender, "command.player-only")
            return true
        }
        if (!ready(player)) return true
        val hit = player.getTargetBlockExact(MAX_DISTANCE, FluidCollisionMode.NEVER)
        val face = player.getTargetBlockFace(MAX_DISTANCE, FluidCollisionMode.NEVER)
        val target = if (face == null) null else hit?.getRelative(face)
        if (target == null) {
            message(player, "placement.no-target")
            return true
        }
        if (!free(target)) {
            message(player, "placement.occupied")
            return true
        }
        dialogs.beginFlow(player)
        openPools(player, target)
        return true
    }

    private fun openPools(player: Player, target: Block) {
        val crates = CratesAPI.getCrateManager().crates.sortedBy { it.id }
        if (crates.isEmpty()) {
            message(player, "placement.no-pools")
            return
        }
        dialogs.open(player, PaperDialogScreen(
            id = "arc-excellent-crates.admin.place.pool",
            title = text(if (english(player)) "Place a crate" else "Установить кейс", NamedTextColor.GOLD, true),
            body = listOf(PaperDialogBody(text(
                if (english(player)) "Target: ${target.world.name} · ${target.x}, ${target.y}, ${target.z}\nChoose a reward pool."
                else "Точка: ${target.world.name} · ${target.x}, ${target.y}, ${target.z}\nВыберите пул наград.",
                NamedTextColor.WHITE,
            ), 420)),
            buttons = crates.mapIndexed { index, crate ->
                button("pool_$index", crate.id, NamedTextColor.LIGHT_PURPLE) { openModels(it.player, target, crate, 0) }
            },
            exitButton = backButton(player, "admin_pool_back") { openRoot(it) },
            columns = 2,
        ))
    }

    private fun openModels(player: Player, target: Block, crate: Crate, requestedPage: Int) {
        val registered = furniture.models().filter { CrateModelPairing.isShellCandidate(it.namespacedId()) }
        val models = buildList {
            add(Model.Vanilla(Material.CHEST))
            add(Model.Vanilla(Material.TRAPPED_CHEST))
            add(Model.Vanilla(Material.BARREL))
            add(Model.Vanilla(Material.ENDER_CHEST))
            addAll(registered.map { Model.ItemsAdder(it.namespacedId()) })
        }
        val pages = maxOf(1, (models.size + PAGE_SIZE - 1) / PAGE_SIZE)
        val page = requestedPage.coerceIn(0, pages - 1)
        val registeredIds = registered.mapTo(linkedSetOf()) { it.namespacedId() }
        val choices = models.drop(page * PAGE_SIZE).take(PAGE_SIZE)
        val buttons = choices.mapIndexed { index, model ->
            val companion = (model as? Model.ItemsAdder)?.let { CrateModelPairing.openingModel(it.id, registeredIds) }
            val label = when (model) {
                is Model.Vanilla -> vanillaName(player, model.material)
                is Model.ItemsAdder -> model.id + if (companion != null) "  ↗" else ""
            }
            button("model_${page}_$index", label, NamedTextColor.LIGHT_PURPLE, closeBefore = true) {
                place(it.player, target, crate, model)
            }
        }.toMutableList()
        if (page > 0) buttons += button("models_previous", if (english(player)) "‹ Previous" else "‹ Назад", NamedTextColor.BLUE) {
            openModels(it.player, target, crate, page - 1)
        }
        if (page + 1 < pages) buttons += button("models_next", if (english(player)) "Next ›" else "Дальше ›", NamedTextColor.BLUE) {
            openModels(it.player, target, crate, page + 1)
        }
        dialogs.open(player, PaperDialogScreen(
            id = "arc-excellent-crates.admin.place.model",
            title = text(crate.id, NamedTextColor.LIGHT_PURPLE, true),
            body = listOf(PaperDialogBody(text(
                if (english(player)) "Choose a shell · page ${page + 1}/$pages\n↗ means an opening twin was found."
                else "Выберите корпус · страница ${page + 1}/$pages\n↗ — найдена парная открытая модель.",
                NamedTextColor.WHITE,
            ), 420)),
            buttons = buttons,
            exitButton = backButton(player, "admin_models_back") { openPools(it, target) },
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

    private fun openKeys(player: Player, requestedPage: Int) {
        val keys = runCatching(::keyChoices).onFailure {
            plugin.runtime().warn("Could not prepare key catalog: {}", it.toString())
        }.getOrElse {
            message(player, "key.unavailable")
            return
        }
        if (keys.isEmpty()) {
            message(player, "key.no-keys")
            return
        }
        val pages = maxOf(1, (keys.size + PAGE_SIZE - 1) / PAGE_SIZE)
        val page = requestedPage.coerceIn(0, pages - 1)
        val listed = keys.drop(page * PAGE_SIZE).take(PAGE_SIZE)
        val buttons = listed.mapIndexed { index, key ->
            button("key_${page}_$index", key.label(), NamedTextColor.LIGHT_PURPLE) {
                openGrant(it.player, key.id, key.season)
            }
        }.toMutableList()
        if (page > 0) buttons += button("keys_previous", if (english(player)) "‹ Previous" else "‹ Назад", NamedTextColor.BLUE) {
            openKeys(it.player, page - 1)
        }
        if (page + 1 < pages) buttons += button("keys_next", if (english(player)) "Next ›" else "Дальше ›", NamedTextColor.BLUE) {
            openKeys(it.player, page + 1)
        }
        dialogs.open(player, PaperDialogScreen(
            id = "arc-excellent-crates.admin.keys",
            title = text(if (english(player)) "Grant a key" else "Выдать ключ", NamedTextColor.GOLD, true),
            body = listOf(PaperDialogBody(text(
                if (english(player)) "Choose a physical key · page ${page + 1}/$pages. The next screen asks for player, amount, and destination server."
                else "Выберите физический ключ · страница ${page + 1}/$pages. Далее укажите игрока, количество и сервер назначения.",
                NamedTextColor.WHITE,
            ), 468)),
            buttons = buttons,
            exitButton = backButton(player, "admin_keys_back") { openRoot(it) },
            columns = 2,
        ))
    }

    private fun openGrant(
        player: Player,
        keyId: String,
        season: String?,
        initialPlayer: String = "",
        initialAmount: Int = 1,
        error: String? = null,
    ) {
        if (!knownPhysicalKey(keyId)) {
            message(player, "key.unknown", mapOf("key" to keyId))
            return
        }
        val servers = backends()
        if (servers.isEmpty()) {
            message(player, "key.no-backends")
            return
        }
        val body = buildList {
            add(PaperDialogBody(text(
                if (english(player)) "Key: $keyId\nThe request is sent to exactly one server and waits briefly for the player there."
                else "Ключ: $keyId\nЗаявка отправляется ровно на один сервер и недолго ждёт игрока именно там.",
                NamedTextColor.WHITE,
            ), 468))
            error?.let { add(PaperDialogBody(text(it, NamedTextColor.RED), 468)) }
        }
        dialogs.open(player, PaperDialogScreen(
            id = "arc-excellent-crates.admin.key.grant",
            title = text(if (english(player)) "Key delivery" else "Доставка ключа", NamedTextColor.LIGHT_PURPLE, true),
            body = body,
            inputs = listOf(PaperDialogTextInput(
                PLAYER_INPUT,
                text(if (english(player)) "Player name" else "Ник игрока", NamedTextColor.WHITE),
                initial = initialPlayer.take(20),
                width = 420,
                maxLength = 20,
            )),
            numberInputs = listOf(PaperDialogNumberRangeInput(
                AMOUNT_INPUT,
                text(if (english(player)) "Amount" else "Количество", NamedTextColor.WHITE),
                1f,
                64f,
                initialAmount.coerceIn(1, 64).toFloat(),
                1f,
                420,
                "%s: %s",
            )),
            buttons = servers.mapIndexed { index, server ->
                button("grant_$index", backendLabel(player, server), NamedTextColor.GREEN) { context ->
                    val rawPlayer = context.text(PLAYER_INPUT).orEmpty().trim()
                    val amount = context.number(AMOUNT_INPUT)?.roundToInt() ?: initialAmount
                    val request = KeyGrantRequest.parse(rawPlayer, keyId, amount, server.value, season)
                    if (request == null) {
                        val failure = if (NetworkPlayerName.parseOrNull(rawPlayer) == null) {
                            if (english(player)) "Enter a valid Minecraft player name." else "Введите корректный ник Minecraft."
                        } else {
                            if (english(player)) "Amount must be between 1 and 64." else "Количество должно быть от 1 до 64."
                        }
                        openGrant(context.player, keyId, season, rawPlayer, amount, failure)
                    } else if (dispatch(request) == KeyDispatchResult.SENT) {
                        openGrantComplete(context.player, request)
                    } else {
                        openGrant(context.player, keyId, season, rawPlayer, amount,
                            if (english(player)) "ARC could not send the request. Nothing was issued."
                            else "ARC не смог отправить заявку. Ничего не выдано.")
                    }
                }
            },
            exitButton = backButton(player, "admin_grant_back") { openKeys(it, 0) },
            columns = minOf(servers.size, 2).coerceAtLeast(1),
        ))
    }

    private fun openGrantComplete(player: Player, request: KeyGrantRequest) {
        val server = checkNotNull(request.server) { "Dialog grants always select an explicit backend" }
        dialogs.open(player, PaperDialogScreen(
            id = "arc-excellent-crates.admin.key.sent",
            title = text(if (english(player)) "Request sent" else "Заявка отправлена", NamedTextColor.GREEN, true),
            body = listOf(PaperDialogBody(text(
                if (english(player)) {
                    "${request.amount} × ${request.keyId} → ${request.player.value} · ${server.value}\nThe target server will issue the key when it sees the player. The request is not broadcast or retried automatically."
                } else {
                    "${request.amount} × ${request.keyId} → ${request.player.value} · ${server.value}\nЦелевой сервер выдаст ключ, когда увидит игрока. Заявка не рассылается по всей сети и автоматически не повторяется."
                },
                NamedTextColor.WHITE,
            ), 468)),
            buttons = listOf(
                button("grant_again", if (english(player)) "Grant this key again" else "Выдать такой ещё", NamedTextColor.LIGHT_PURPLE) {
                    openGrant(it.player, request.keyId, request.season, request.player.value, request.amount)
                },
                button("grant_other", if (english(player)) "Choose another key" else "Выбрать другой ключ", NamedTextColor.GOLD) {
                    openKeys(it.player, 0)
                },
            ),
            exitButton = backButton(player, "admin_sent_back") { openRoot(it) },
            columns = 2,
        ))
        message(player, "key.sent", mapOf(
            "player" to request.player.value,
            "key" to request.keyId,
            "amount" to request.amount.toString(),
            "server" to server.value,
        ))
    }

    private fun grantFromCommand(sender: CommandSender, args: List<String>): Boolean {
        if (!ready(sender)) return true
        val parsed = KeyGrantArguments.parse(args)
        if (parsed == null) {
            if (args.size == 4 && args.getOrNull(2)?.toIntOrNull() == null) {
                message(sender, "key.invalid-amount")
                return true
            }
            message(sender, "command.usage")
            return true
        }
        val server = parsed.server?.let(BackendServerId::parseOrNull)
        if (parsed.server != null && (server == null || server !in backends())) {
            message(sender, "key.invalid-server", mapOf("server" to parsed.server))
            return true
        }
        if (!knownPhysicalKey(parsed.keyId)) {
            message(sender, "key.unknown", mapOf("key" to parsed.keyId))
            return true
        }
        val season = runCatching { seasonForKey(parsed.keyId) }.onFailure {
            plugin.runtime().warn("Could not resolve current season for key {}: {}", parsed.keyId, it.toString())
        }.getOrElse {
            message(sender, "key.unavailable")
            return true
        }
        val request = KeyGrantRequest.parse(parsed.player, parsed.keyId, parsed.amount, server?.value, season)
        if (request == null) {
            val key = if (NetworkPlayerName.parseOrNull(parsed.player) == null) "key.invalid-player" else "key.invalid-amount"
            message(sender, key)
            return true
        }
        when (dispatch(request)) {
            KeyDispatchResult.SENT -> message(sender, "key.sent", mapOf(
                    "player" to request.player.value,
                    "key" to request.keyId,
                    "amount" to request.amount.toString(),
                    "server" to (request.server?.value ?: if (sender is Player && english(sender)) "this server" else "этом сервере"),
                ))
            KeyDispatchResult.PLAYER_NOT_HERE -> message(sender, "key.player-not-here", mapOf("player" to request.player.value))
            KeyDispatchResult.FAILED -> message(sender, "key.dispatch-failed")
        }
        return true
    }

    private fun dispatch(request: KeyGrantRequest): KeyDispatchResult {
        val item = runCatching { physicalKey(request) }.onFailure {
            plugin.runtime().warn("Could not create key delivery id={}: {}", request.requestId, it.toString())
        }.getOrNull() ?: return KeyDispatchResult.FAILED
        if (request.server == null) {
            return when (receiver.deliverLocal(request.requestId, request.player, request.keyId, request.amount, item)) {
                LocalKeyDeliveryResult.DELIVERED -> KeyDispatchResult.SENT
                LocalKeyDeliveryResult.PLAYER_NOT_HERE -> KeyDispatchResult.PLAYER_NOT_HERE
                LocalKeyDeliveryResult.FAILED -> KeyDispatchResult.FAILED
            }
        }
        if (!plugin.server.pluginManager.isPluginEnabled("ARC") || plugin.server.getPluginCommand("x") == null) {
            return KeyDispatchResult.FAILED
        }
        val delivery = SerializedKeyDelivery.create(request, item) ?: return KeyDispatchResult.FAILED
        return if (plugin.server.dispatchCommand(plugin.server.consoleSender, delivery.xCommand(deliveryTimeoutTicks()))) {
            KeyDispatchResult.SENT
        } else KeyDispatchResult.FAILED
    }

    private fun physicalKey(request: KeyGrantRequest): ItemStack = request.season?.let {
        nativeKeys.create(request.keyId, it, request.amount)
    } ?: checkNotNull(CratesAPI.getKeyManager().getKeyById(request.keyId)).itemStack.clone().also {
        it.amount = request.amount
    }

    private fun keyChoices(): List<KeyChoice> {
        if (!CratesAPI.isLoaded()) return emptyList()
        val cratesByKey = linkedMapOf<String, MutableSet<String>>()
        val seasonsByKey = currentSeasonsByKey()
        CratesAPI.getCrateManager().crates.forEach { crate ->
            crate.costs.asSequence()
                .filter { it.isEnabled }
                .flatMap { it.entries.asSequence() }
                .filterIsInstance<KeyCostEntry>()
                .forEach { entry -> cratesByKey.getOrPut(entry.keyId) { linkedSetOf() }.add(crate.id) }
        }
        return CratesAPI.getKeyManager().keys.asSequence()
            .filterNot { it.isVirtual }
            .filter { KeyGrantRequest.safeKeyId(it.id) }
            .map { KeyChoice(it.id, cratesByKey[it.id].orEmpty().sorted(), seasonsByKey[it.id]) }
            .sortedBy(KeyChoice::id)
            .toList()
    }

    private fun knownPhysicalKey(keyId: String): Boolean {
        if (!CratesAPI.isLoaded() || !KeyGrantRequest.safeKeyId(keyId)) return false
        return CratesAPI.getKeyManager().getKeyById(keyId)?.isVirtual == false
    }

    private fun seasonForKey(keyId: String): String? = currentSeasonsByKey()[keyId]

    private fun currentSeasonsByKey(): Map<String, String> {
        val file = plugin.dataFolder.toPath().resolve("features.yml").toFile()
        val settings = ManagedCratesSettings.read(YamlConfiguration.loadConfiguration(file))
        val result = linkedMapOf<String, String>()
        settings.cases.values.forEach { configured ->
            val crate = checkNotNull(CratesAPI.getCrateManager().getCrateById(configured.crateId)) {
                "Missing configured crate ${configured.crateId}"
            }
            val keyId = nativeKeys.cost(crate).keyId()
            val previous = result.putIfAbsent(keyId, configured.seasonId)
            check(previous == null || previous == configured.seasonId) { "Shared key has conflicting current seasons: $keyId" }
        }
        return result
    }

    private fun backends(): List<BackendServerId> = plugin.config.getStringList("key-delivery.backends")
        .ifEmpty { DEFAULT_BACKENDS }
        .mapNotNull { BackendServerId.parseOrNull(it.trim().lowercase(Locale.ROOT)) }
        .distinct()

    private fun deliveryTimeoutTicks(): Int = plugin.config.getInt("key-delivery.timeout-ticks", 200).coerceIn(20, 1200)

    private fun ready(sender: CommandSender): Boolean {
        if (CratesAPI.isLoaded()) return true
        message(sender, "placement.unavailable")
        return false
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

    private fun message(sender: CommandSender, key: String, values: Map<String, String> = emptyMap()) {
        sender.sendMessage(plugin.runtime().locale().renderPadded(key, sender, values))
    }

    private fun button(
        id: String,
        label: String,
        color: NamedTextColor,
        closeBefore: Boolean = false,
        action: (PaperDialogClickContext) -> Unit,
    ) = PaperDialogButton(
        id = PaperDialogActionId.of(id),
        label = text(label, color),
        width = 260,
        closeDialogBeforeAction = closeBefore,
        onClick = action,
    )

    private fun backButton(player: Player, id: String, action: (Player) -> Unit) = PaperDialogButton(
        PaperDialogActionId.of(id),
        text(if (english(player)) "‹ Back" else "‹ Назад", NamedTextColor.WHITE),
        width = 200,
        closeDialogBeforeAction = false,
    ) { action(it.player) }

    private fun closeButton(player: Player) = PaperDialogButton(
        PaperDialogActionId.of("admin_close"),
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
        Material.ENDER_CHEST -> if (english(player)) "Ender chest" else "Эндер-сундук"
        else -> material.name
    }

    private fun backendLabel(player: Player, server: BackendServerId): String = when (server.value) {
        "spawn" -> if (english(player)) "Grant on Spawn" else "Выдать на спавне"
        "survival" -> if (english(player)) "Grant on Survival" else "Выдать на выживании"
        else -> if (english(player)) "Grant on ${server.value}" else "Выдать на ${server.value}"
    }

    override fun close() {
        if (command.executor === this) command.setExecutor(null)
        if (command.tabCompleter === this) command.tabCompleter = null
        nativeKeys.close()
        dialogs.close()
    }

    private data class KeyChoice(val id: String, val crates: List<String>, val season: String?) {
        fun label(): String {
            val suffix = if (crates.isEmpty()) "" else crates.take(2).joinToString(", ", " · ") + if (crates.size > 2) "…" else ""
            return id + suffix
        }
    }

    private enum class KeyDispatchResult { SENT, PLAYER_NOT_HERE, FAILED }

    private sealed interface Model {
        val id: String

        data class Vanilla(val material: Material) : Model {
            override val id = "minecraft:${material.name.lowercase()}"
        }

        data class ItemsAdder(override val id: String) : Model
    }

    private companion object {
        const val COMMAND = "arc-crate"
        const val MAX_DISTANCE = 12
        const val PAGE_SIZE = 10
        val DEFAULT_BACKENDS = listOf("spawn", "survival")
        val PLAYER_INPUT = PaperDialogInputId.of("player")
        val AMOUNT_INPUT = PaperDialogInputId.of("amount")
    }
}

/** Validated cross-server mint request. Every interpolated token is command-safe. */
internal data class KeyGrantRequest(
    val requestId: UUID,
    val player: NetworkPlayerName,
    val keyId: String,
    val amount: Int,
    val server: BackendServerId?,
    val season: String?,
) {
    companion object {
        private val KEY_ID = Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}")
        private val SEASON_ID = Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")

        fun safeKeyId(value: String): Boolean = KEY_ID.matches(value)

        fun parse(
            player: String?,
            keyId: String?,
            amount: Int,
            server: String?,
            season: String?,
            requestId: UUID = UUID.randomUUID(),
        ): KeyGrantRequest? {
            val safePlayer = NetworkPlayerName.parseOrNull(player) ?: return null
            val safeServer = server?.let(BackendServerId::parseOrNull)
            if (server != null && safeServer == null) return null
            val safeKey = keyId?.takeIf(::safeKeyId) ?: return null
            val safeSeason = season?.takeIf(SEASON_ID::matches)
            if (season != null && safeSeason == null) return null
            if (amount !in 1..64) return null
            return KeyGrantRequest(requestId, safePlayer, safeKey, amount, safeServer, safeSeason)
        }
    }
}

internal data class KeyGrantArguments(
    val player: String,
    val keyId: String,
    val amount: Int,
    val server: String?,
) {
    companion object {
        fun parse(args: List<String>): KeyGrantArguments? = when (args.size) {
            2 -> KeyGrantArguments(args[0], args[1], 1, null)
            3 -> args[2].toIntOrNull()?.let { KeyGrantArguments(args[0], args[1], it, null) }
                ?: KeyGrantArguments(args[0], args[1], 1, args[2])
            4 -> args[2].toIntOrNull()?.let { KeyGrantArguments(args[0], args[1], it, args[3]) }
            else -> null
        }
    }
}
