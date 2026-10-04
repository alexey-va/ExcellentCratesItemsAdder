package ru.ruscrafting.ecia.integration

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.arc.paper.api.ArcTelemetryProvider
import ru.ruscrafting.ecia.journal.KeyShopJournal.Purchase
import ru.ruscrafting.ecia.journal.KeyShopJournal.State
import ru.ruscrafting.ecia.journal.OpeningRecord
import ru.ruscrafting.ecia.roll.PoolSnapshot
import ru.ruscrafting.ecia.roll.RewardDefinition
import java.util.UUID

class ArcActivityTelemetryBridgeTest : FunSpec({
    test("durable opening and key purchase transitions publish bounded domain snapshots") {
        val playerId = UUID.randomUUID()
        val openingId = UUID.randomUUID()
        val reward = RewardDefinition("reward_rare", 1.0, "native:item", "preview")
        val opening = OpeningRecord(
            openingId,
            playerId,
            PoolSnapshot("case_daily", "season_7", listOf(reward), 1, 0, 1),
            1_000L,
            2_500L,
            3L,
            OpeningRecord.Stage.CHOOSING,
            listOf(reward),
            0,
            "",
            "debit-witness",
            "",
            "",
            "",
        )
        val openingCollector = ActivityCollector()

        ArcActivityTelemetryBridge.publishOpeningTransition("opened", opening, openingCollector)

        openingCollector.activity shouldBe Activity(
            playerId = playerId,
            source = "arcexcellentcrates",
            event = "crate_open_started",
            subject = openingId.toString(),
            operationId = "$openingId:opened:3",
            attributes = mapOf(
                "crate_id" to "case_daily",
                "season_id" to "season_7",
                "stage" to "choosing",
                "rerolls_used" to "0",
                "offer_count" to "1",
                "bundle_size" to "1",
                "outcome" to "started",
                "quantity" to "1",
            ),
        )

        val saleId = UUID.randomUUID()
        val purchase = Purchase(
            saleId,
            playerId,
            "case_daily",
            "key_daily",
            3L,
            1_000L,
            4_000L,
            4,
            State.DELIVERED,
            "delivered",
        )
        val purchaseCollector = ActivityCollector(accept = false)

        ArcActivityTelemetryBridge.publishKeyPurchase("delivered", purchase, "delivered", null, purchaseCollector)

        purchaseCollector.activity shouldBe Activity(
            playerId = playerId,
            source = "arcexcellentcrates",
            event = "crate_key_purchase_delivered",
            subject = "case_daily",
            operationId = "$saleId:delivered",
            attributes = mapOf(
                "crate_id" to "case_daily",
                "key_id" to "key_daily",
                "quantity" to "1",
                "price_amount" to "3",
                "price_currency" to "tokens",
                "outcome" to "delivered",
                "stage" to "delivered",
                "reason" to "delivered",
                "duration_ms" to "3000",
            ),
        )
        purchaseCollector.accepted shouldBe false
    }

    test("a failing optional telemetry provider cannot escape the adapter") {
        val playerId = UUID.randomUUID()
        val purchase = Purchase.prepared(UUID.randomUUID(), playerId, "case_daily", "key_daily", 3L, 1_000L)
        val collector = ActivityCollector(failure = IllegalStateException("optional sink unavailable"))

        ArcActivityTelemetryBridge.publishKeyPurchase("started", purchase, "prepared", null, collector)

        collector.calls shouldBe 1
    }
})

private data class Activity(
    val playerId: UUID,
    val source: String,
    val event: String,
    val subject: String?,
    val operationId: String?,
    val attributes: Map<String, String>,
)

private class ActivityCollector(
    private val accept: Boolean = true,
    private val failure: Throwable? = null,
) : ArcTelemetryProvider {
    var calls = 0
        private set
    var accepted: Boolean? = null
        private set
    var activity: Activity? = null
        private set

    override fun recordActivity(
        playerId: UUID,
        source: String,
        event: String,
        subject: String?,
        operationId: String?,
        attributes: Map<String, String>,
    ): Boolean {
        calls += 1
        failure?.let { throw it }
        activity = Activity(playerId, source, event, subject, operationId, attributes.toMap())
        accepted = accept
        return accept
    }
}
