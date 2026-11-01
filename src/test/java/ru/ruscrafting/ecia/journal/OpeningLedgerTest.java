package ru.ruscrafting.ecia.journal;

import org.junit.jupiter.api.Test;
import ru.ruscrafting.ecia.roll.PoolSnapshot;
import ru.ruscrafting.ecia.roll.RewardDefinition;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class OpeningLedgerTest {
    static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000728");
    static final RewardDefinition COMMON = new RewardDefinition("coin", 90, "coin-delivery", "coin-preview");
    static final RewardDefinition RARE = new RewardDefinition("mount", 10, "mount-delivery", "mount-preview");
    static final PoolSnapshot POOL = new PoolSnapshot("daily", "launch", List.of(COMMON, RARE), 3, 1);
    static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(1234), ZoneOffset.UTC);

    @Test void restartRetainsChoicesAndRejectsSecondKeyDebit() {
        MemoryStore store = new MemoryStore();
        OpeningLedger first = new OpeningLedger(store, CLOCK);
        OpeningRecord reserved = reserve(first);
        OpeningRecord ready = first.debitConfirmed(reserved.id(), PLAYER, reserved.revision());
        OpeningLedger restarted = new OpeningLedger(store, CLOCK);
        assertEquals(ready, restarted.active(PLAYER).orElseThrow());
        assertThrows(IllegalStateException.class, () -> reserve(restarted));
        assertThrows(IllegalStateException.class, () -> restarted.debitConfirmed(ready.id(), PLAYER, ready.revision()));
    }

    @Test void staleClicksCannotRerollTwiceOrChooseOldOffer() {
        OpeningLedger ledger = new OpeningLedger(new MemoryStore(), CLOCK);
        OpeningRecord record = ready(ledger);
        OpeningRecord rolled = ledger.reroll(record.id(), PLAYER, record.revision(), List.of(RARE));
        assertThrows(IllegalStateException.class,
                () -> ledger.select(record.id(), PLAYER, record.revision(), COMMON.id()));
        assertThrows(IllegalStateException.class,
                () -> ledger.reroll(rolled.id(), PLAYER, rolled.revision(), List.of(COMMON)));
        assertThrows(IllegalArgumentException.class,
                () -> ledger.select(rolled.id(), PLAYER, rolled.revision(), COMMON.id()));
        assertEquals(OpeningRecord.Stage.MAIL,
                ledger.select(rolled.id(), PLAYER, rolled.revision(), RARE.id()).stage());
    }

    @Test void fullMailboxPreservesPrizeAndAllowsAnotherOpening() {
        OpeningLedger ledger = new OpeningLedger(new MemoryStore(), CLOCK);
        OpeningRecord first = ready(ledger);
        OpeningRecord mail = ledger.select(first.id(), PLAYER, first.revision(), COMMON.id());
        assertEquals(OpeningRecord.Stage.MAIL, mail.stage());
        assertTrue(ledger.active(PLAYER).isEmpty());
        assertEquals(mail, ledger.pending(PLAYER).orElseThrow());
        assertEquals(OpeningRecord.Stage.RESERVED, reserve(ledger).stage());
    }

    @Test void deliveryCannotStartWithoutDurablePayloadAndDoesNotBlindlyReplay() {
        MemoryStore store = new MemoryStore();
        OpeningLedger ledger = new OpeningLedger(store, CLOCK);
        OpeningRecord record = ready(ledger);
        OpeningRecord mail = ledger.select(record.id(), PLAYER, record.revision(), COMMON.id());
        assertThrows(IllegalStateException.class,
                () -> ledger.deliveryStarted(mail.id(), PLAYER, mail.revision(), "inventory-before"));
        OpeningRecord prepared = ledger.prepared(mail.id(), PLAYER, mail.revision(), "native-item-bytes");
        OpeningRecord started = ledger.deliveryStarted(prepared.id(), PLAYER, prepared.revision(), "before-and-after");
        OpeningLedger restarted = new OpeningLedger(store, CLOCK);
        assertEquals(OpeningRecord.Stage.DELIVERING, restarted.get(started.id(), PLAYER).stage());
        assertThrows(IllegalStateException.class,
                () -> restarted.deliveryStarted(started.id(), PLAYER, started.revision(), "new-attempt"));
        OpeningRecord review = restarted.review(started.id(), PLAYER, started.revision(), "provider-outcome-unknown");
        assertEquals(OpeningRecord.Stage.REVIEW, review.stage());
        assertThrows(IllegalStateException.class,
                () -> restarted.deliveryNotApplied(review.id(), PLAYER, review.revision(), "guess"));
    }

    @Test void completedDeliveryCannotBeClaimedAgain() {
        OpeningLedger ledger = new OpeningLedger(new MemoryStore(), CLOCK);
        OpeningRecord record = ready(ledger);
        record = ledger.select(record.id(), PLAYER, record.revision(), RARE.id());
        record = ledger.prepared(record.id(), PLAYER, record.revision(), "native-item-bytes");
        record = ledger.deliveryStarted(record.id(), PLAYER, record.revision(), "inventory-witness");
        OpeningRecord done = ledger.deliveryConfirmed(record.id(), PLAYER, record.revision());
        assertFalse(done.pending());
        assertThrows(IllegalStateException.class,
                () -> ledger.deliveryConfirmed(done.id(), PLAYER, done.revision()));
    }

    @Test void reviewedOpeningsRemainRecordedWithoutBlockingNewOpeningsAfterRestart() {
        MemoryStore store = new MemoryStore();
        OpeningLedger ledger = new OpeningLedger(store, CLOCK);
        OpeningRecord first = ready(ledger);
        first = ledger.select(first.id(), PLAYER, first.revision(), RARE.id());
        first = ledger.prepared(first.id(), PLAYER, first.revision(), "native-prize");
        first = ledger.deliveryStarted(first.id(), PLAYER, first.revision(), "delivery-witness");
        OpeningRecord rewardReview = ledger.review(first.id(), PLAYER, first.revision(), "unconfirmed-delivery");
        assertTrue(rewardReview.pending(), "Review still counts as an unresolved entitlement");
        assertTrue(ledger.pending(PLAYER).isEmpty());
        assertTrue(ledger.active(PLAYER).isEmpty());

        OpeningRecord second = reserve(ledger);
        OpeningRecord debitReview = ledger.review(second.id(), PLAYER, second.revision(), "unconfirmed-debit");
        OpeningRecord current = reserve(ledger);
        OpeningLedger restarted = new OpeningLedger(store, CLOCK);
        assertEquals(current, restarted.pending(PLAYER).orElseThrow());
        assertEquals(current, restarted.active(PLAYER).orElseThrow());
        assertEquals(debitReview, restarted.latestReview(PLAYER).orElseThrow());
        assertTrue(restarted.latestReview(UUID.randomUUID()).isEmpty());
        assertEquals(rewardReview, restarted.get(rewardReview.id(), PLAYER));
        assertFalse(restarted.canSpendPeriodicKey(rewardReview.id()));
        assertEquals(3, restarted.history(PLAYER, 0, 10).size());

        OpeningRecord resolved = restarted.reconciled(rewardReview.id(), PLAYER, rewardReview.revision(),
                true, "native-receipt-confirmed");
        assertEquals(OpeningRecord.Stage.DELIVERED, resolved.stage());
        assertEquals(current, restarted.active(PLAYER).orElseThrow());
    }

    @Test void reviewedDebitCannotBecomeChoosingWhileAnotherOpeningIsActive() {
        OpeningLedger ledger = new OpeningLedger(new MemoryStore(), CLOCK);
        OpeningRecord first = reserve(ledger);
        OpeningRecord review = ledger.review(first.id(), PLAYER, first.revision(), "unconfirmed-debit");
        OpeningRecord current = ready(ledger);

        assertThrows(IllegalStateException.class,
                () -> ledger.reconciled(review.id(), PLAYER, review.revision(), true, "native-receipt-confirmed"));
        assertEquals(review, ledger.get(review.id(), PLAYER));
        assertEquals(current, ledger.active(PLAYER).orElseThrow());

        ledger.select(current.id(), PLAYER, current.revision(), COMMON.id());
        OpeningRecord recovered = ledger.reconciled(review.id(), PLAYER, review.revision(),
                true, "native-receipt-confirmed");
        assertEquals(OpeningRecord.Stage.CHOOSING, recovered.stage());
        assertEquals(recovered, ledger.active(PLAYER).orElseThrow());
    }

    @Test void failedStoreCommitLeavesLastConfirmedStateInMemory() {
        MemoryStore store = new MemoryStore();
        OpeningLedger ledger = new OpeningLedger(store, CLOCK);
        OpeningRecord record = ready(ledger);
        store.fail = true;
        assertThrows(IllegalStateException.class,
                () -> ledger.select(record.id(), PLAYER, record.revision(), RARE.id()));
        assertEquals(record, ledger.get(record.id(), PLAYER));
        assertFalse(ledger.available());
        store.fail = false;
        assertThrows(IllegalStateException.class,
                () -> ledger.select(record.id(), PLAYER, record.revision(), COMMON.id()));
    }

    @Test void committedWriteWithFailedReadbackCannotBeRetriedFromStaleCache() {
        MemoryStore store = new MemoryStore();
        OpeningStore uncertainStore = new OpeningStore() {
            @Override public List<OpeningRecord> load() { return store.load(); }
            @Override public OpeningRecord commit(OpeningRecord record) {
                store.commit(record);
                throw new IllegalStateException("Readback unavailable after write");
            }
        };
        OpeningLedger ledger = new OpeningLedger(uncertainStore, CLOCK);
        assertThrows(IllegalStateException.class, () -> reserve(ledger));
        assertThrows(IllegalStateException.class, () -> reserve(ledger));
        assertEquals(1, store.records.size());
        assertTrue(new OpeningLedger(store, CLOCK).active(PLAYER).isPresent());
    }

    @Test void createdAtRemainsMonotonicWhenClockDoesNotAdvance() {
        MemoryStore store = new MemoryStore();
        OpeningLedger ledger = new OpeningLedger(store, CLOCK);
        OpeningRecord first = ready(ledger);
        ledger.select(first.id(), PLAYER, first.revision(), COMMON.id());
        OpeningRecord second = ready(ledger);
        ledger.select(second.id(), PLAYER, second.revision(), COMMON.id());
        assertTrue(second.createdAt() > first.createdAt());
        OpeningRecord third = ready(new OpeningLedger(store, CLOCK));
        assertTrue(third.createdAt() > second.createdAt());
    }

    @Test void wrongPlayerCannotObserveOrClaimAnotherPlayersOpening() {
        OpeningLedger ledger = new OpeningLedger(new MemoryStore(), CLOCK);
        OpeningRecord record = ready(ledger);
        UUID intruder = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> ledger.get(record.id(), intruder));
        assertThrows(IllegalArgumentException.class,
                () -> ledger.select(record.id(), intruder, record.revision(), COMMON.id()));
    }

    @Test void mailboxClaimCannotCreateTwoUnresolvedSideEffectsForOnePlayer() {
        OpeningLedger ledger = new OpeningLedger(new MemoryStore(), CLOCK);
        OpeningRecord first = ready(ledger);
        first = ledger.select(first.id(), PLAYER, first.revision(), COMMON.id());
        OpeningRecord mail = ledger.prepared(first.id(), PLAYER, first.revision(), "native-prize");
        ready(ledger);
        assertThrows(IllegalStateException.class,
                () -> ledger.deliveryStarted(mail.id(), PLAYER, mail.revision(), "inventory-before-after"));
    }

    static OpeningRecord reserve(OpeningLedger ledger) {
        return ledger.reserve(UUID.randomUUID(), PLAYER, POOL, List.of(COMMON, RARE), "key-before-and-after");
    }

    static OpeningRecord ready(OpeningLedger ledger) {
        OpeningRecord record = reserve(ledger);
        return ledger.debitConfirmed(record.id(), PLAYER, record.revision());
    }

    static final class MemoryStore implements OpeningStore {
        final Map<UUID, OpeningRecord> records = new HashMap<>();
        boolean fail;
        @Override public List<OpeningRecord> load() { return List.copyOf(records.values()); }
        @Override public OpeningRecord commit(OpeningRecord record) {
            if (fail) throw new IllegalStateException("Injected storage failure");
            records.put(record.id(), record);
            return record;
        }
    }
}
