package ru.ruscrafting.ecia.journal

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.ruscrafting.ecia.journal.KeyShopJournal.Purchase
import ru.ruscrafting.ecia.journal.KeyShopJournal.State
import java.nio.file.Files
import java.util.UUID

class KeyShopJournalTest : FunSpec({
    test("ambiguous charge survives restart, blocks duplicates, and cannot be retried") {
        val directory = Files.createTempDirectory("ecia-key-sale-ambiguous-")
        try {
            val player = UUID.randomUUID()
            val id = UUID.randomUUID()
            val journal = KeyShopJournal(directory)
            journal.prepare(Purchase.prepared(id, player, "case_daily", "daily", 3, 1_000))
            journal.transition(id, 0, State.CHARGE_ATTEMPTED, "charge_attempted", 1_001)

            val recoveredJournal = KeyShopJournal(directory)
            val recovered = recoveredJournal.load().single()
            recovered.state() shouldBe State.CHARGE_ATTEMPTED
            recovered.blocksFurtherPurchases() shouldBe true
            shouldThrow<IllegalStateException> {
                recoveredJournal.prepare(
                    Purchase.prepared(UUID.randomUUID(), player, "case_weekly", "weekly", 9, 1_002),
                )
            }

            val review = recoveredJournal.transition(id, recovered.revision(), State.REVIEW, "payment_ambiguous", 1_003)
            review.blocksFurtherPurchases() shouldBe true
            shouldThrow<IllegalStateException> {
                recoveredJournal.transition(id, review.revision(), State.CANCELLED, "payment_rejected", 1_004)
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    test("a definitive provider rejection is terminal and permits a later purchase") {
        val directory = Files.createTempDirectory("ecia-key-sale-rejected-")
        try {
            val player = UUID.randomUUID()
            val journal = KeyShopJournal(directory)
            val firstId = UUID.randomUUID()
            journal.prepare(Purchase.prepared(firstId, player, "case_daily", "daily", 3, 2_000))
            val attempted = journal.transition(firstId, 0, State.CHARGE_ATTEMPTED, "charge_attempted", 2_001)
            val cancelled = journal.transition(
                firstId, attempted.revision(), State.CANCELLED, "payment_rejected", 2_002,
            )

            cancelled.blocksFurtherPurchases() shouldBe false
            val second = journal.prepare(
                Purchase.prepared(UUID.randomUUID(), player, "case_weekly", "weekly", 9, 2_003),
            )
            second.state() shouldBe State.PREPARED
            second.playerId() shouldBe player
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
})
