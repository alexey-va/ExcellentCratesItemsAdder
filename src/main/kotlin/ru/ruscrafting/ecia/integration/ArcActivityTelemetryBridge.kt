package ru.ruscrafting.ecia.integration

import org.bukkit.Bukkit
import ru.arc.paper.api.ArcTelemetryProvider
import ru.ruscrafting.ecia.journal.KeyShopJournal.Purchase
import ru.ruscrafting.ecia.journal.OpeningRecord
import java.util.UUID

/** Optional queue-only activity sink. Provider resolution happens once during enable. */
internal object ArcActivityTelemetryBridge {
    @Volatile private var telemetry: ArcTelemetryProvider? = null

    fun install() {
        telemetry = runCatching { Bukkit.getServicesManager().load(ArcTelemetryProvider::class.java) }.getOrNull()
    }

    /** Called only after a durable opening-ledger transition. */
    fun openingTransition(event: String, record: OpeningRecord) {
        publishOpeningTransition(event, record, telemetry)
    }

    internal fun publishOpeningTransition(event: String, record: OpeningRecord, provider: ArcTelemetryProvider?) {
        val base = mapOf(
            "crate_id" to record.pool().crateId(),
            "season_id" to record.pool().seasonId(),
            "stage" to record.stage().name.lowercase(),
            "rerolls_used" to record.rerollsUsed().toString(),
            "offer_count" to record.offers().size.toString(),
            "bundle_size" to record.pool().bundleSize().toString(),
        )
        val operationId = "${record.id()}:$event:${record.revision()}"
        val attributes = when (event) {
            "opened" -> base + mapOf("outcome" to "started", "quantity" to "1")
            "selected" -> base + mapOf(
                "outcome" to "selected",
                "selected_reward_id" to record.selectedRewardId(),
            )
            "rerolled" -> base + mapOf("outcome" to "rerolled")
            "claimed" -> base + mapOf(
                "outcome" to "delivered",
                "selected_reward_id" to record.selectedRewardId(),
                "duration_ms" to (record.updatedAt() - record.createdAt()).coerceAtLeast(0L).toString(),
            )
            "mail_pending" -> base + mapOf(
                "outcome" to "mail_pending",
                "selected_reward_id" to record.selectedRewardId(),
            )
            "open_failed" -> base + mapOf("outcome" to "not_applied", "reason" to "key_not_consumed")
            "recovery_required" -> base + mapOf("outcome" to "recovery_required", "reason" to "unresolved_side_effect")
            "recovered" -> base + mapOf("outcome" to "reconciled")
            else -> return
        }
        val eventId = when (event) {
            "opened" -> "crate_open_started"
            "selected" -> "crate_reward_selected"
            "rerolled" -> "crate_offers_rerolled"
            "claimed" -> "crate_reward_claimed"
            "mail_pending" -> "crate_reward_mail_pending"
            "open_failed" -> "crate_open_failed"
            "recovery_required" -> if (record.selectedRewardId().isEmpty()) "crate_open_recovery_required" else "crate_reward_recovery_required"
            "recovered" -> "crate_opening_recovered"
            else -> return
        }
        record(provider, record.playerId(), eventId, record.id().toString(), operationId, attributes)
    }

    fun keyPurchase(event: String, purchase: Purchase, stage: String = event, reasonOverride: String? = null) {
        publishKeyPurchase(event, purchase, stage, reasonOverride, telemetry)
    }

    internal fun publishKeyPurchase(
        event: String,
        purchase: Purchase,
        stage: String,
        reasonOverride: String?,
        provider: ArcTelemetryProvider?,
    ) {
        val eventId = when (event) {
            "started" -> "crate_key_purchase_started"
            "delivered" -> "crate_key_purchase_delivered"
            "cancelled" -> "crate_key_purchase_cancelled"
            "review" -> "crate_key_purchase_review"
            "unknown" -> "crate_key_purchase_outcome_unknown"
            "recovery_required" -> "crate_key_purchase_recovery_required"
            else -> return
        }
        val attributes = buildMap {
            put("crate_id", purchase.crateId())
            put("key_id", purchase.keyId())
            put("quantity", "1")
            put("price_amount", purchase.priceTokens().toString())
            put("price_currency", "tokens")
            put("outcome", event)
            put("stage", stage)
            (reasonOverride ?: purchase.reason()).takeIf(String::isNotBlank)?.let { put("reason", it.take(64)) }
            if (event != "started") {
                put("duration_ms", (purchase.updatedAt() - purchase.createdAt()).coerceAtLeast(0L).toString())
            }
        }
        val operationId = "${purchase.id()}:$stage"
        record(provider, purchase.playerId(), eventId, purchase.crateId(), operationId, attributes)
    }

    private fun record(provider: ArcTelemetryProvider?, playerId: UUID, event: String, subject: String?, operationId: String,
        attributes: Map<String, String>) {
        runCatching {
            provider?.recordActivity(playerId, "arcexcellentcrates", event, subject, operationId, attributes.toMap())
        }
    }
}
