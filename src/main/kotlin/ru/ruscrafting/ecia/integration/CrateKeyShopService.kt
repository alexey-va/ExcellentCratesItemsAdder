package ru.ruscrafting.ecia.integration

import dev.unnm3d.rediseconomy.api.RedisEconomyAPI
import dev.unnm3d.rediseconomy.currency.Currency
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.william278.husksync.api.BukkitHuskSyncAPI
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabExecutor
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import ru.arc.core.whenCompleteSync
import ru.arc.paper.menu.PaperDialogActionId
import ru.arc.paper.menu.PaperDialogBody
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.menu.PaperDialogClickContext
import ru.arc.paper.menu.PaperDialogRuntime
import ru.arc.paper.menu.PaperDialogScreen
import ru.ruscrafting.ecia.ArcExcellentCratesPlugin
import ru.ruscrafting.ecia.journal.KeyShopJournal
import ru.ruscrafting.ecia.journal.KeyShopJournal.Purchase
import ru.ruscrafting.ecia.journal.KeyShopJournal.State
import su.nightexpress.excellentcrates.CratesAPI
import su.nightexpress.nightcore.util.text.NightMessage
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/** Player key sales with a durable payment/delivery barrier around each external effect. */
class CrateKeyShopService(
    private val plugin: ArcExcellentCratesPlugin,
) : TabExecutor, AutoCloseable {
    private val runtime = plugin.runtime()
    private val locale = runtime.locale()
    private val tasks = runtime.tasks()
    private val taskToken = tasks.token()
    private var dialogs = PaperDialogRuntime(plugin)
    private val keys = NativeSeasonKeys()
    private val journal = KeyShopJournal(plugin.dataFolder.toPath())
    private val journalLock = Any()
    private val storageExecutor = Executor { command ->
        if (tasks.runAsync(taskToken) { command.run() } == null) {
            throw RejectedExecutionException("Key shop storage lifecycle is closed")
        }
    }
    private val command = checkNotNull(plugin.getCommand(COMMAND)) { "Missing /$COMMAND in plugin.yml" }
    private val inFlight = mutableSetOf<UUID>()
    private val blockedPlayers = mutableSetOf<UUID>()

    @Volatile
    private var settings: KeyShopSettings? = null
    @Volatile
    private var configurationFailed = true
    private var journalLoading = false
    private var journalReady = false
    private var reloadGeneration = 0L
    private var closed = false

    init {
        command.setExecutor(this)
        command.tabCompleter = this
        reload()
        loadJournal()
    }

    /** Reloads the complete seller surface; invalid settings fail closed. */
    @JvmOverloads
    fun reload(onComplete: ((Boolean) -> Unit)? = null) {
        if (!plugin.server.isPrimaryThread) {
            if (tasks.runSync(taskToken) { reload(onComplete) } == null) onComplete?.invoke(false)
            return
        }
        dialogs.close()
        dialogs = PaperDialogRuntime(plugin)
        settings = null
        configurationFailed = true
        val generation = ++reloadGeneration
        asyncStorage {
            val defaults = plugin.getResource(CONFIG_RESOURCE)?.bufferedReader()?.use { it.readText() }
                ?: throw IllegalStateException("Bundled $CONFIG_RESOURCE is missing")
            KeyShopSettings.load(File(plugin.dataFolder, CONFIG_RESOURCE), defaults).also {
                locale.reload()
            }
        }.whenCompleteSync(tasks, taskToken) { loaded, failure ->
            if (closed || generation != reloadGeneration) return@whenCompleteSync
            if (failure != null || loaded == null) {
                settings = null
                configurationFailed = true
                runtime.error("Key shop configuration rejected: {}",
                    failure?.toString() ?: "empty configuration result")
                onComplete?.invoke(false)
                return@whenCompleteSync
            }
            settings = loaded
            configurationFailed = false
            runtime.info("Key shop settings loaded: enabled={}", loaded.enabled)
            onComplete?.invoke(true)
        }
        if (!journalReady && !journalLoading && !closed) loadJournal()
    }

    override fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>,
    ): Boolean {
        if (args.firstOrNull()?.lowercase(Locale.ROOT) == "reload") {
            if (!sender.hasPermission(ADMIN_PERMISSION)) {
                message(sender, "no-permission")
                return true
            }
            reload { success ->
                if (sender is Player && !sender.isOnline) return@reload
                message(sender, if (success) "key-shop.reloaded" else "key-shop.reload-invalid")
            }
            return true
        }
        if (args.isNotEmpty()) return false
        val player = sender as? Player ?: run {
            message(sender, "key-shop.unavailable")
            return true
        }
        if (!player.hasPermission(USE_PERMISSION)) {
            message(player, "no-permission")
            return true
        }
        if (!journalReady || configurationFailed) {
            message(player, "key-shop.unavailable")
            return true
        }
        if (player.uniqueId in blockedPlayers) {
            message(player, "key-shop.review")
            return true
        }
        val activeSettings = settings ?: run {
            message(player, "key-shop.unavailable")
            return true
        }
        if (!activeSettings.enabled) {
            message(player, "key-shop.disabled")
            return true
        }
        if (!atMerchant(player, activeSettings)) {
            message(player, "key-shop.outside")
            return true
        }
        if (tokenCurrency() == null || !CratesAPI.isLoaded()) {
            message(player, "key-shop.unavailable")
            return true
        }
        dialogs.beginFlow(player)
        openCatalog(player)
        return true
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>,
    ): List<String> = if (args.size == 1 && sender.hasPermission(ADMIN_PERMISSION)) {
        listOf("reload").filter { it.startsWith(args[0], ignoreCase = true) }
    } else emptyList()

    private fun loadJournal() {
        if (journalLoading || journalReady || closed) return
        journalLoading = true
        asyncStorage {
            synchronized(journalLock) { journal.load() }
        }.whenCompleteSync(tasks, taskToken) { records, failure ->
            journalLoading = false
            if (closed) return@whenCompleteSync
            if (failure != null || records == null) {
                runtime.error("Key sale recovery journal is unavailable: {}", failure?.toString() ?: "empty read")
                return@whenCompleteSync
            }
            records.filter(Purchase::blocksFurtherPurchases).forEach { record ->
                blockedPlayers += record.playerId()
                ArcActivityTelemetryBridge.keyPurchase(
                    "recovery_required", record, "${record.state().name.lowercase(Locale.ROOT)}_recovery",
                )
                runtime.error(
                    "Key sale requires manual review: id={}, player={}, state={}",
                    record.id(), record.playerId(), record.state(),
                )
            }
            journalReady = true
            runtime.info("Key sale recovery journal loaded: records={}, blocked={}",
                records.size, blockedPlayers.size)
        }
    }

    private fun openCatalog(player: Player) {
        val activeSettings = settings ?: return message(player, "key-shop.unavailable")
        if (configurationFailed || !activeSettings.enabled || !atMerchant(player, activeSettings)) {
            message(player, if (!activeSettings.enabled) "key-shop.disabled" else "key-shop.outside")
            return
        }
        if (player.uniqueId in blockedPlayers) {
            message(player, "key-shop.review")
            return
        }
        val offers = KeyShopSettings.SUPPORTED_CASES.mapNotNull { id ->
            activeSettings.prices[id]?.let { resolveOffer(player, id, it) }
        }
        if (offers.isEmpty()) {
            message(player, "key-shop.unavailable")
            return
        }
        val buttons = offers.map { offer ->
            dialogButton(
                "offer_" + offer.crateId.replace('-', '_'),
                locale.render("key-shop.offer", player, mapOf(
                    "crate" to offer.crateName,
                    "price" to offer.price.toString(),
                )),
            ) { openConfirmation(it.player, offer.crateId, offer.price) }
        } + dialogButton("shop_close", locale.render("key-shop.close", player, emptyMap()), true) { }
        dialogs.open(player, PaperDialogScreen(
            id = "arc-excellent-crates.keyshop.catalog",
            title = locale.render("key-shop.title", player, emptyMap()),
            body = listOf(PaperDialogBody(locale.render("key-shop.body", player, emptyMap()), 468)),
            buttons = buttons,
            columns = 2,
        ))
    }

    private fun openConfirmation(player: Player, crateId: String, displayedPrice: Long) {
        if (!preflight(player, crateId, displayedPrice)) return
        val offer = resolveOffer(player, crateId, displayedPrice) ?: run {
            message(player, "key-shop.unavailable")
            return
        }
        var confirmed = false
        dialogs.open(player, PaperDialogScreen(
            id = "arc-excellent-crates.keyshop.confirm",
            title = locale.render("key-shop.confirm-title", player, emptyMap()),
            body = listOf(PaperDialogBody(locale.render("key-shop.confirm-body", player, mapOf(
                "crate" to offer.crateName,
                "price" to offer.price.toString(),
            )), 468)),
            buttons = listOf(
                dialogButton(
                    "purchase_confirm",
                    locale.render("key-shop.buy", player, mapOf("price" to offer.price.toString())),
                    true,
                ) {
                    if (confirmed) return@dialogButton
                    confirmed = true
                    startPurchase(it.player, crateId, displayedPrice)
                },
                dialogButton("purchase_back", locale.render("key-shop.back", player, emptyMap())) {
                    openCatalog(it.player)
                },
            ),
            columns = 2,
        ))
    }

    private fun startPurchase(player: Player, crateId: String, displayedPrice: Long) {
        if (!preflight(player, crateId, displayedPrice)) return
        if (!inFlight.add(player.uniqueId)) {
            message(player, "key-shop.processing")
            return
        }
        if (player.uniqueId in blockedPlayers) {
            inFlight.remove(player.uniqueId)
            message(player, "key-shop.review")
            return
        }
        val offer = resolveOffer(player, crateId, displayedPrice)
        if (offer == null) {
            inFlight.remove(player.uniqueId)
            message(player, "key-shop.price-changed")
            openCatalog(player)
            return
        }
        if (!hasStorageSpace(player)) {
            inFlight.remove(player.uniqueId)
            message(player, "key-shop.no-space")
            return
        }
        val purchase = Purchase.prepared(
            UUID.randomUUID(), player.uniqueId, offer.crateId, offer.keyId, offer.price, System.currentTimeMillis(),
        )
        val context = SaleContext(player, purchase, offer.crateName)
        // Block immediately in memory while the durable reservation is being written.
        blockedPlayers += player.uniqueId
        asyncStorage {
            synchronized(journalLock) { journal.prepare(purchase) }
        }.whenCompleteSync(tasks, taskToken) { prepared, failure ->
            if (closed) return@whenCompleteSync
            if (failure != null || prepared == null) {
                runtime.error("Could not reserve key sale {}: {}", purchase.id(), failure?.toString() ?: "empty write")
                message(player, "key-shop.review")
                inFlight.remove(player.uniqueId)
                return@whenCompleteSync
            }
            context.purchase = prepared
            ArcActivityTelemetryBridge.keyPurchase("started", prepared, "prepared")
            afterPrepared(context)
        }
    }

    private fun afterPrepared(context: SaleContext) {
        val purchase = context.purchase
        val offer = resolveOffer(context.player, purchase.crateId(), purchase.priceTokens(), purchase.keyId())
        if (offer == null) {
            cancel(context, "price_changed", "key-shop.price-changed")
            return
        }
        if (!hasStorageSpace(context.player)) {
            cancel(context, "inventory_full", "key-shop.no-space")
            return
        }
        transition(context, State.CHARGE_ATTEMPTED, "charge_attempted") {
            afterChargeAttempted(context)
        }
    }

    private fun afterChargeAttempted(context: SaleContext) {
        val purchase = context.purchase
        val offer = resolveOffer(context.player, purchase.crateId(), purchase.priceTokens(), purchase.keyId())
        if (offer == null || !hasStorageSpace(context.player)) {
            cancel(context, if (offer == null) "price_changed" else "inventory_full",
                if (offer == null) "key-shop.price-changed" else "key-shop.no-space")
            return
        }
        val currency = tokenCurrency()
        if (currency == null) {
            cancel(context, "provider_unavailable", "key-shop.unavailable")
            return
        }
        val response = try {
            checkNotNull(currency.withdrawPlayer(
                context.player.uniqueId,
                context.player.name,
                purchase.priceTokens().toDouble(),
                "ecia-keyshop:" + purchase.id(),
            )) { "RedisEconomy returned no transaction response" }
        } catch (failure: RuntimeException) {
            runtime.error("Token withdrawal became ambiguous for key sale {}: {}",
                purchase.id(), failure.toString())
            review(context, "payment_ambiguous")
            return
        } catch (failure: LinkageError) {
            runtime.error("Token provider linkage became ambiguous for key sale {}: {}",
                purchase.id(), failure.toString())
            review(context, "payment_ambiguous")
            return
        }
        if (!response.transactionSuccess()) {
            cancel(context, "payment_rejected", "key-shop.payment-failed")
            return
        }
        if (!response.amount.isFinite() || response.amount != purchase.priceTokens().toDouble()) {
            runtime.error("Token response amount mismatch for key sale {}: expected={}, reported={}",
                purchase.id(), purchase.priceTokens(), response.amount)
            review(context, "payment_amount_mismatch")
            return
        }
        transition(context, State.CHARGED, "charged") { afterCharged(context) }
    }

    private fun afterCharged(context: SaleContext) {
        val purchase = context.purchase
        val offer = resolveOffer(context.player, purchase.crateId(), purchase.priceTokens(), purchase.keyId())
        if (offer == null || !hasStorageSpace(context.player)) {
            review(context, if (offer == null) "post_charge_changed" else "post_charge_no_space")
            return
        }
        transition(context, State.DELIVERY_ATTEMPTED, "delivery_attempted") {
            deliver(context)
        }
    }

    private fun deliver(context: SaleContext) {
        val purchase = context.purchase
        val offer = resolveOffer(context.player, purchase.crateId(), purchase.priceTokens(), purchase.keyId())
        if (offer == null || !hasStorageSpace(context.player)) {
            review(context, if (offer == null) "delivery_changed" else "delivery_no_space")
            return
        }
        try {
            val leftovers = context.player.inventory.addItem(offer.item)
            if (leftovers.isNotEmpty()) {
                review(context, "delivery_capacity_changed")
                return
            }
        } catch (failure: RuntimeException) {
            runtime.error("Key insertion became ambiguous for sale {}: {}", purchase.id(), failure.toString())
            review(context, "delivery_ambiguous")
            return
        } catch (failure: LinkageError) {
            runtime.error("Key insertion linkage became ambiguous for sale {}: {}", purchase.id(), failure.toString())
            review(context, "delivery_ambiguous")
            return
        }
        transition(context, State.DELIVERED, "delivered") {
            message(context.player, "key-shop.delivered", mapOf(
                "crate" to context.crateName,
                "price" to purchase.priceTokens().toString(),
            ))
            finish(context, clearBlock = true)
        }
    }

    private fun cancel(context: SaleContext, reason: String, messageKey: String) {
        transition(context, State.CANCELLED, reason) {
            message(context.player, messageKey)
            finish(context, clearBlock = true)
        }
    }

    private fun review(context: SaleContext, reason: String) {
        message(context.player, "key-shop.review")
        val record = context.purchase
        if (record.state() == State.REVIEW || record.state() == State.DELIVERED || record.state() == State.CANCELLED) {
            finish(context, clearBlock = false)
            return
        }
        transition(context, State.REVIEW, reason) {
            runtime.error("Key sale requires manual review: id={}, player={}, state={}",
                context.purchase.id(), context.purchase.playerId(), context.purchase.state())
            finish(context, clearBlock = false)
        }
    }

    private fun transition(context: SaleContext, next: State, reason: String, onSuccess: () -> Unit) {
        val current = context.purchase
        asyncStorage {
            synchronized(journalLock) {
                journal.transition(current.id(), current.revision(), next, reason, System.currentTimeMillis())
            }
        }.whenCompleteSync(tasks, taskToken) { saved, failure ->
            if (closed) return@whenCompleteSync
            if (failure != null || saved == null) {
                runtime.error("Key sale journal barrier failed: id={}, from={}, to={}, error={}",
                    current.id(), current.state(), next, failure?.toString() ?: "empty write")
                ArcActivityTelemetryBridge.keyPurchase(
                    "unknown", current, "${next.name.lowercase(Locale.ROOT)}_barrier", "journal_barrier_failed",
                )
                message(context.player, "key-shop.review")
                // A failed barrier leaves the previous durable state unresolved; never retry its side effect.
                finish(context, clearBlock = false)
                return@whenCompleteSync
            }
            context.purchase = saved
            when (saved.state()) {
                State.DELIVERED -> ArcActivityTelemetryBridge.keyPurchase("delivered", saved, "delivered")
                State.CANCELLED -> ArcActivityTelemetryBridge.keyPurchase("cancelled", saved, "cancelled")
                State.REVIEW -> ArcActivityTelemetryBridge.keyPurchase("review", saved, "review")
                else -> Unit
            }
            onSuccess()
        }
    }

    private fun resolveOffer(
        player: Player,
        crateId: String,
        expectedPrice: Long,
        expectedKeyId: String? = null,
    ): Offer? {
        val activeSettings = settings ?: return null
        if (configurationFailed || !activeSettings.enabled || !isCurrentPlayer(player)
            || !player.hasPermission(USE_PERMISSION) || !huskSyncReady(player)
            || !atMerchant(player, activeSettings)) return null
        if (crateId !in KeyShopSettings.SUPPORTED_CASES || activeSettings.prices[crateId] != expectedPrice) return null
        if (!CratesAPI.isLoaded()) return null
        val crate = CratesAPI.getCrateManager().getCrateById(crateId) ?: return null
        if (!crate.hasPermission(player)) return null
        val keyCost = runCatching { keys.cost(crate) }.getOrNull() ?: return null
        if (keyCost.amount() != 1) return null
        if (expectedKeyId != null && expectedKeyId != keyCost.keyId()) return null
        val item = runCatching { keys.createFromCurrentTemplate(keyCost.keyId(), 1) }.getOrNull() ?: return null
        val identity = keys.identify(item).orElse(null) ?: return null
        if (identity.keyId() != keyCost.keyId()) return null
        return Offer(crateId, keyCost.keyId(), expectedPrice, NightMessage.stripAll(crate.name), item)
    }

    private fun preflight(player: Player, crateId: String, displayedPrice: Long): Boolean {
        val activeSettings = settings
        if (!journalReady || configurationFailed || activeSettings == null) {
            message(player, "key-shop.unavailable")
            return false
        }
        if (!activeSettings.enabled) {
            message(player, "key-shop.disabled")
            return false
        }
        if (player.uniqueId in blockedPlayers) {
            message(player, "key-shop.review")
            return false
        }
        if (!atMerchant(player, activeSettings)) {
            message(player, "key-shop.outside")
            return false
        }
        if (activeSettings.prices[crateId] != displayedPrice) {
            message(player, "key-shop.price-changed")
            return false
        }
        if (tokenCurrency() == null || !CratesAPI.isLoaded()) {
            message(player, "key-shop.unavailable")
            return false
        }
        return true
    }

    private fun atMerchant(player: Player, activeSettings: KeyShopSettings): Boolean {
        if (!isCurrentPlayer(player)) return false
        val location = player.location
        if (location.world?.name != activeSettings.world) return false
        val dx = location.x - activeSettings.x
        val dy = location.y - activeSettings.y
        val dz = location.z - activeSettings.z
        return dx * dx + dy * dy + dz * dz <= activeSettings.maxDistance * activeSettings.maxDistance
    }

    private fun isCurrentPlayer(player: Player): Boolean =
        player.isOnline && plugin.server.getPlayer(player.uniqueId) === player

    private fun huskSyncReady(player: Player): Boolean {
        if (!plugin.server.pluginManager.isPluginEnabled(HUSK_SYNC_PLUGIN)) return true
        return runCatching { !BukkitHuskSyncAPI.getInstance().getUser(player).isLocked }.getOrDefault(false)
    }

    private fun hasStorageSpace(player: Player): Boolean =
        player.inventory.storageContents.any { it == null || it.type.isAir || it.amount <= 0 }

    private fun tokenCurrency(): Currency? = try {
        if (!plugin.server.pluginManager.isPluginEnabled(REDIS_ECONOMY_PLUGIN)) return null
        val currency = RedisEconomyAPI.getAPI()?.getCurrencyByName(TOKENS_CURRENCY) ?: return null
        if (!currency.isEnabled || !currency.currencyName.equals(TOKENS_CURRENCY, ignoreCase = true)
            || currency.transactionTax != 0.0) return null
        currency
    } catch (_: RuntimeException) {
        null
    } catch (_: LinkageError) {
        null
    }

    private fun <T> asyncStorage(action: () -> T): CompletableFuture<T> =
        CompletableFuture.supplyAsync(action, storageExecutor)

    private fun dialogButton(
        id: String,
        label: Component,
        closeBeforeAction: Boolean = false,
        action: (PaperDialogClickContext) -> Unit,
    ) = PaperDialogButton(
        id = PaperDialogActionId.of(id),
        label = label.decoration(TextDecoration.ITALIC, false),
        width = 260,
        closeDialogBeforeAction = closeBeforeAction,
        onClick = action,
    )

    private fun message(sender: CommandSender, key: String, values: Map<String, String> = emptyMap()) {
        sender.sendMessage(locale.renderPadded(key, sender, values))
    }

    private fun finish(context: SaleContext, clearBlock: Boolean) {
        inFlight.remove(context.player.uniqueId)
        if (clearBlock) blockedPlayers.remove(context.player.uniqueId)
    }

    override fun close() {
        if (closed) return
        closed = true
        if (command.executor === this) command.setExecutor(null)
        if (command.tabCompleter === this) command.tabCompleter = null
        dialogs.close()
        keys.close()
        inFlight.clear()
    }

    private data class Offer(
        val crateId: String,
        val keyId: String,
        val price: Long,
        val crateName: String,
        val item: ItemStack,
    )

    private data class SaleContext(
        val player: Player,
        var purchase: Purchase,
        val crateName: String,
    )

    private companion object {
        const val COMMAND = "cratekeys"
        const val CONFIG_RESOURCE = "key-shop.yml"
        const val USE_PERMISSION = "ecia.use"
        const val ADMIN_PERMISSION = "ecia.admin"
        const val TOKENS_CURRENCY = "tokens"
        const val REDIS_ECONOMY_PLUGIN = "RedisEconomy"
        const val HUSK_SYNC_PLUGIN = "HuskSync"
    }
}
