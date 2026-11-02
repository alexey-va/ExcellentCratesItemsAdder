package ru.ruscrafting.ecia.journal;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static ru.ruscrafting.ecia.journal.OpeningLedgerTest.*;

class OpeningMailExpiryLedgerTest {
    private static final long NOW = CLOCK.millis() + OpeningLedger.MAIL_INACTIVITY_MILLIS;
    private static final Clock EXPIRY_CLOCK = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC);

    @Test void sevenDayBoundaryExpiresMailWithoutPretendingToDeliverOrRefundIt() {
        MemoryStore store = new MemoryStore();
        OpeningRecord mail = mail(new OpeningLedger(store, CLOCK), false);
        OpeningLedger ledger = new OpeningLedger(store, EXPIRY_CLOCK);
        assertFalse(ledger.expireMail(mail.id(), PLAYER, mail.revision(), CLOCK.millis(), NOW - 1));
        assertTrue(ledger.expireMail(mail.id(), PLAYER, mail.revision(), CLOCK.millis(), NOW));
        OpeningRecord expired = ledger.get(mail.id(), PLAYER);
        assertEquals(OpeningRecord.Stage.EXPIRED, expired.stage());
        assertFalse(expired.pending());
        assertEquals(mail.pool(), expired.pool());
        assertEquals(mail.offers(), expired.offers());
        assertEquals(mail.selectedRewardId(), expired.selectedRewardId());
        assertEquals(mail.keyWitness(), expired.keyWitness());
        assertTrue(expired.preparedReward().isEmpty());
        assertTrue(expired.deliveryWitness().isEmpty());
        assertEquals(0, ledger.pendingCount());
        assertFalse(ledger.canSpendPeriodicKey(mail.id()));
        assertThrows(IllegalStateException.class, () -> ledger.reservePeriodicKey(mail.id(), PLAYER, POOL,
                () -> { throw new AssertionError("Expired key cannot roll again"); }, "another-key"));
        OpeningLedger restarted = new OpeningLedger(store, EXPIRY_CLOCK);
        assertEquals(expired, restarted.history(PLAYER, 0, 1).getFirst());
        assertFalse(restarted.canSpendPeriodicKey(mail.id()));
        assertTrue(restarted.contains(mail.id()));
    }

    @Test void expiredVirtualReceiptStaysSpentAfterRestart() {
        MemoryStore store = new MemoryStore();
        OpeningRecord mail = mail(new OpeningLedger(store, CLOCK), true);
        OpeningLedger ledger = new OpeningLedger(store, EXPIRY_CLOCK);
        assertTrue(ledger.expireMail(mail.id(), PLAYER, mail.revision(), CLOCK.millis(), NOW));
        OpeningLedger restarted = new OpeningLedger(store, EXPIRY_CLOCK);
        var previous = restarted.reserveVirtual(mail.id(), PLAYER, POOL,
                () -> { throw new AssertionError("Expired quota cannot roll again"); }, mail.keyWitness());
        assertEquals(OpeningLedger.VirtualReservationStatus.EXISTING_PERIOD, previous.status());
        assertEquals(OpeningRecord.Stage.EXPIRED, previous.record().stage());
        assertEquals(java.util.Set.of(mail.id()), restarted.virtualOpeningIds());
        assertThrows(IllegalStateException.class, () -> restarted.select(mail.id(), PLAYER,
                previous.record().revision(), COMMON.id()));
    }

    @Test void missingFutureRecentActivityAndWrongRevisionOrOwnerKeepMail() {
        MemoryStore store = new MemoryStore();
        OpeningRecord mail = mail(new OpeningLedger(store, CLOCK), false);
        OpeningLedger ledger = new OpeningLedger(store, EXPIRY_CLOCK);
        for (long lastSeen : new long[]{0, -1, NOW + 1, CLOCK.millis() + 1}) {
            assertFalse(ledger.expireMail(mail.id(), PLAYER, mail.revision(), lastSeen, NOW));
        }
        assertFalse(ledger.expireMail(mail.id(), UUID.randomUUID(), mail.revision(), CLOCK.millis(), NOW));
        assertFalse(ledger.expireMail(mail.id(), PLAYER, mail.revision() + 1, CLOCK.millis(), NOW));
        OpeningRecord prepared = ledger.prepared(mail.id(), PLAYER, mail.revision(), "exact-native-prize");
        assertFalse(ledger.expireMail(mail.id(), PLAYER, mail.revision(), CLOCK.millis(), NOW));
        assertFalse(ledger.expireMail(mail.id(), PLAYER, prepared.revision(), CLOCK.millis(), NOW));
        assertEquals(prepared, ledger.get(mail.id(), PLAYER));
    }

    @Test void deliveringAndReviewAreNeverExpired() {
        MemoryStore store = new MemoryStore();
        OpeningLedger original = new OpeningLedger(store, CLOCK);
        OpeningRecord mail = mail(original, false);
        OpeningRecord prepared = original.prepared(mail.id(), PLAYER, mail.revision(), "exact-native-prize");
        OpeningRecord delivering = original.deliveryStarted(prepared.id(), PLAYER, prepared.revision(), "uncertain-native-witness");
        OpeningLedger ledger = new OpeningLedger(store, EXPIRY_CLOCK);
        assertFalse(ledger.expireMail(delivering.id(), PLAYER, delivering.revision(), CLOCK.millis(), NOW));
        OpeningRecord review = ledger.review(delivering.id(), PLAYER, delivering.revision(), "unknown-save-outcome");
        assertFalse(ledger.expireMail(review.id(), PLAYER, review.revision(), CLOCK.millis(), NOW));
        assertEquals(review, ledger.get(review.id(), PLAYER));
    }

    @Test void failedExpiryCommitKeepsTheFullMailUntilStorageIsReopened() {
        MemoryStore store = new MemoryStore();
        OpeningRecord mail = mail(new OpeningLedger(store, CLOCK), false);
        OpeningLedger ledger = new OpeningLedger(store, EXPIRY_CLOCK);
        store.fail = true;
        assertThrows(IllegalStateException.class,
                () -> ledger.expireMail(mail.id(), PLAYER, mail.revision(), CLOCK.millis(), NOW));
        assertEquals(mail, ledger.get(mail.id(), PLAYER));
        assertFalse(ledger.available());
        store.fail = false;
        assertThrows(IllegalStateException.class,
                () -> ledger.expireMail(mail.id(), PLAYER, mail.revision(), CLOCK.millis(), NOW));
        OpeningLedger reopened = new OpeningLedger(store, EXPIRY_CLOCK);
        assertTrue(reopened.expireMail(mail.id(), PLAYER, mail.revision(), CLOCK.millis(), NOW));
    }

    @Test void writtenExpiryWithFailedReadbackStaysUncertainUntilRestartReadsItsRealStage() {
        MemoryStore durable = new MemoryStore();
        OpeningRecord mail = mail(new OpeningLedger(durable, CLOCK), false);
        OpeningStore uncertain = new OpeningStore() {
            @Override public List<OpeningRecord> load() { return durable.load(); }
            @Override public OpeningRecord commit(OpeningRecord record) { durable.commit(record); return mail; }
        };
        OpeningLedger ledger = new OpeningLedger(uncertain, EXPIRY_CLOCK);
        assertThrows(IllegalStateException.class,
                () -> ledger.expireMail(mail.id(), PLAYER, mail.revision(), CLOCK.millis(), NOW));
        assertEquals(mail, ledger.get(mail.id(), PLAYER));
        assertFalse(ledger.available());
        OpeningLedger reopened = new OpeningLedger(durable, EXPIRY_CLOCK);
        assertEquals(OpeningRecord.Stage.EXPIRED, reopened.get(mail.id(), PLAYER).stage());
        assertFalse(reopened.canSpendPeriodicKey(mail.id()));
    }

    private static OpeningRecord mail(OpeningLedger ledger, boolean virtual) {
        UUID id = UUID.randomUUID();
        OpeningRecord choosing;
        if (virtual) choosing = ledger.reserveVirtual(id, PLAYER, POOL,
                () -> List.of(COMMON), "virtual:v1:daily-window").record();
        else {
            OpeningRecord reserved = ledger.reservePeriodicKey(id, PLAYER, POOL, () -> List.of(COMMON), "native-key-witness");
            choosing = ledger.debitConfirmed(id, PLAYER, reserved.revision());
        }
        return ledger.select(id, PLAYER, choosing.revision(), COMMON.id());
    }
}
