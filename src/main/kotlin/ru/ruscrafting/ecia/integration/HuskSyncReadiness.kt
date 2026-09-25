package ru.ruscrafting.ecia.integration

import net.william278.husksync.api.BukkitHuskSyncAPI
import net.william278.husksync.event.BukkitSyncCompleteEvent
import net.william278.husksync.user.BukkitUser
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.plugin.Plugin
import ru.arc.core.LifecycleTaskScope

/** Optional native boundary: inventory receipts are inspected only after network data is applied. */
internal class HuskSyncReadiness(
    plugin: Plugin,
    private val tasks: LifecycleTaskScope,
    private val synchronized: (Player) -> Unit,
) : Listener, AutoCloseable {
    private val token = tasks.token()
    private var closed = false

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    fun isReady(player: Player): Boolean = !BukkitHuskSyncAPI.getInstance().getUser(player).isLocked

    @EventHandler(priority = EventPriority.MONITOR)
    fun onSyncComplete(event: BukkitSyncCompleteEvent) {
        val player = (event.user as BukkitUser).player
        // HuskSync fires completion before releasing its inventory lock.
        tasks.runSync(token) { awaitUnlock(player) }
    }

    private fun awaitUnlock(player: Player) {
        if (closed || !player.isOnline) return
        if (isReady(player)) {
            synchronized(player)
        } else {
            // The provider releases the lock asynchronously after dispatching the event.
            tasks.runLater(token, 20L) { awaitUnlock(player) }
        }
    }

    override fun close() {
        closed = true
        HandlerList.unregisterAll(this)
    }
}
