package ru.ruscrafting.ecia

import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabExecutor
import org.bukkit.inventory.ItemStack
import ru.arc.network.BackendServerId
import ru.arc.network.NetworkPlayerName
import java.util.Base64
import java.util.UUID

internal const val ARC_CRATE_ADMIN_PERMISSION = "ecia.admin"

/** Receives an exact physical key ItemStack, even on backends without ExcellentCrates. */
internal class NetworkKeyReceiver(private val plugin: ArcExcellentCratesPlugin) : TabExecutor, AutoCloseable {
    private val receivedGrantIds = linkedSetOf<UUID>()
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
        if (args.firstOrNull()?.equals(RECEIVE_SUBCOMMAND, ignoreCase = true) == true) {
            return receive(sender, args.drop(1))
        }
        if (!sender.hasPermission(ARC_CRATE_ADMIN_PERMISSION)) {
            message(sender, "no-permission")
        } else {
            message(sender, "command.source-only")
        }
        return true
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>,
    ): List<String> = emptyList()

    fun receive(sender: CommandSender, args: List<String>): Boolean {
        if (sender !== plugin.server.consoleSender) {
            message(sender, "no-permission")
            return true
        }
        val delivery = SerializedKeyDelivery.parse(args)
        if (delivery == null) {
            plugin.runtime().warn("Rejected malformed internal key delivery")
            return true
        }
        if (delivery.requestId in receivedGrantIds) {
            plugin.runtime().info("Skipped duplicate key delivery id={}", delivery.requestId)
            return true
        }
        val player = plugin.server.getPlayerExact(delivery.player.value)
        if (player == null || !player.isOnline) {
            plugin.runtime().warn(
                "Key delivery reached the server without its player id={} player={}",
                delivery.requestId,
                delivery.player.value,
            )
            return true
        }
        val item = runCatching(delivery::item).onFailure {
            plugin.runtime().warn("Could not decode key delivery id={}: {}", delivery.requestId, it.toString())
        }.getOrNull() ?: return true
        val leftovers = player.inventory.addItem(item)
        leftovers.values.forEach { player.world.dropItemNaturally(player.location, it) }
        remember(delivery.requestId)
        message(player, "key.received", mapOf(
            "key" to delivery.keyId,
            "amount" to delivery.amount.toString(),
        ))
        plugin.runtime().info(
            "Applied network key delivery id={} player={} key={} amount={}",
            delivery.requestId,
            delivery.player.value,
            delivery.keyId,
            delivery.amount,
        )
        return true
    }

    private fun remember(requestId: UUID) {
        receivedGrantIds += requestId
        while (receivedGrantIds.size > MAX_RECEIVED_GRANTS) {
            receivedGrantIds.remove(receivedGrantIds.first())
        }
    }

    private fun message(sender: CommandSender, key: String, values: Map<String, String> = emptyMap()) {
        sender.sendMessage(plugin.runtime().locale().renderPadded(key, sender, values))
    }

    override fun close() {
        if (command.executor === this) command.setExecutor(null)
        if (command.tabCompleter === this) command.tabCompleter = null
        receivedGrantIds.clear()
    }

    private companion object {
        const val COMMAND = "arc-crate"
        const val RECEIVE_SUBCOMMAND = "receive-key"
        const val MAX_RECEIVED_GRANTS = 2048
    }
}

/** Command-safe envelope containing the exact key item minted on the source backend. */
internal data class SerializedKeyDelivery(
    val requestId: UUID,
    val player: NetworkPlayerName,
    val keyId: String,
    val amount: Int,
    val server: BackendServerId,
    val payload: String,
) {
    fun xCommand(timeoutTicks: Int): String {
        require(timeoutTicks in 20..1200) { "Delivery timeout outside 20..1200 ticks" }
        return "x -servers:${server.value} -player:${player.value} -timeout:$timeoutTicks " +
            "arc-crate receive-key $requestId ${player.value} $keyId $amount $payload"
    }

    fun item(): ItemStack {
        val bytes = Base64.getUrlDecoder().decode(payload.removePrefix(PAYLOAD_PREFIX))
        require(bytes.size <= MAX_ITEM_BYTES) { "Serialized key is too large" }
        return ItemStack.deserializeBytes(bytes).also {
            require(!it.isEmpty && it.amount == amount) { "Serialized key amount mismatch" }
        }
    }

    companion object {
        private const val PAYLOAD_PREFIX = "b64_"
        private val PAYLOAD = Regex("$PAYLOAD_PREFIX[A-Za-z0-9_-]{1,$MAX_PAYLOAD_LENGTH}")
        private const val MAX_PAYLOAD_LENGTH = 16_384
        private const val MAX_ITEM_BYTES = 12_288

        fun create(request: KeyGrantRequest, item: ItemStack): SerializedKeyDelivery? {
            if (item.isEmpty || item.amount != request.amount) return null
            val bytes = item.serializeAsBytes()
            if (bytes.size > MAX_ITEM_BYTES) return null
            val payload = PAYLOAD_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
            if (!PAYLOAD.matches(payload)) return null
            return SerializedKeyDelivery(
                request.requestId,
                request.player,
                request.keyId,
                request.amount,
                request.server,
                payload,
            )
        }

        fun parse(args: List<String>): SerializedKeyDelivery? {
            if (args.size != 5) return null
            val requestId = runCatching { UUID.fromString(args[0]) }.getOrNull() ?: return null
            val player = NetworkPlayerName.parseOrNull(args[1]) ?: return null
            val keyId = args[2].takeIf(KeyGrantRequest::safeKeyId) ?: return null
            val amount = args[3].toIntOrNull()?.takeIf { it in 1..64 } ?: return null
            val payload = args[4].takeIf(PAYLOAD::matches) ?: return null
            return SerializedKeyDelivery(requestId, player, keyId, amount, BackendServerId.of("local"), payload)
        }
    }
}
