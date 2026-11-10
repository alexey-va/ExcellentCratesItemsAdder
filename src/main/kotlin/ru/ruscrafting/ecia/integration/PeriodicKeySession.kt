package ru.ruscrafting.ecia.integration

import org.bukkit.entity.Player
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.random.Random

/** One wait per recovered backend connection. Main-thread polls pause it while the player is inactive. */
internal class PeriodicKeySession(
    val player: Player,
    startedAt: Long,
    private var wasPlaying: Boolean,
    waitSeconds: Long = Random.nextLong(300L, 331L),
) {
    private var remaining = SECONDS.toNanos(waitSeconds)
    private var lastPoll = startedAt
    private var nextPoll = startedAt + SECONDS.toNanos(2)
    val waitComplete: Boolean get() = remaining == 0L

    fun poll(now: Long, isPlaying: () -> Boolean): Boolean {
        if (now - nextPoll < 0L) return false
        val playing = isPlaying()
        if (wasPlaying && playing && !waitComplete) {
            // A stalled scheduler cannot establish activity throughout the whole gap.
            val elapsed = (now - lastPoll).coerceAtMost(SECONDS.toNanos(10))
            remaining = (remaining - elapsed).coerceAtLeast(0L)
        }
        lastPoll = now
        wasPlaying = playing
        nextPoll = now + SECONDS.toNanos(5)
        return playing && waitComplete
    }
}

// Normal ranks are independent LuckPerms children of default. Exact effective group nodes
// exclude OP defaults and wildcard permissions and do not require a plugin API dependency.
internal fun hasPeriodicKeyRank(player: Player): Boolean =
    player.effectivePermissions.any { it.value && it.permission in PERIODIC_KEY_RANK_GROUPS }

private val PERIODIC_KEY_RANK_GROUPS = setOf(
    "group.rank_peasant", "group.rank_citizen", "group.rank_artisan", "group.rank_knight",
    "group.rank_baron", "group.rank_count", "group.rank_prince", "group.rank_caesar",
)
