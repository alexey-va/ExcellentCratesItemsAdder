package ru.ruscrafting.ecia.journal;

import org.junit.jupiter.api.Test;
import ru.ruscrafting.ecia.roll.PoolSnapshot;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static ru.ruscrafting.ecia.journal.OpeningLedgerTest.*;

class OpeningLedgerArchiveTest {
    @Test void deliveredRecordsKeepOnlyCompactMetadataEvenAfterMaterialization() throws Exception {
        TrackingStore store = new TrackingStore();
        OpeningLedger ledger = new OpeningLedger(store, CLOCK);
        OpeningRecord done = complete(ledger, UUID.randomUUID(), "key-witness".repeat(1000));
        assertCompact(ledger, done);
        assertEquals(done, ledger.get(done.id(), PLAYER));
        assertEquals(List.of(done), ledger.history(PLAYER, 0, 10));
        assertEquals(List.of(done), ledger.snapshot());
        assertCompact(ledger, done);

        OpeningLedger restarted = new OpeningLedger(store, CLOCK);
        assertCompact(restarted, done);
        assertTrue(reserve(restarted).createdAt() > done.createdAt());
    }

    @Test void historyLoadsOnlyTheRequestedPageAndKeepsPendingRecordsFull() {
        TrackingStore store = new TrackingStore();
        OpeningLedger ledger = new OpeningLedger(store, CLOCK);
        List<OpeningRecord> completed = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            completed.add(complete(ledger, UUID.randomUUID(), "virtual:v1:period-" + index));
        }
        OpeningRecord current = ready(ledger);
        OpeningRecord mail = ledger.select(current.id(), PLAYER, current.revision(), COMMON.id());
        OpeningRecord reserved = reserve(ledger);
        OpeningRecord review = ledger.review(reserved.id(), PLAYER, reserved.revision(), "uncertain-debit");
        OpeningRecord aborted = reserve(ledger);
        aborted = ledger.debitRejected(aborted.id(), PLAYER, aborted.revision(), "unchanged");

        OpeningLedger restarted = new OpeningLedger(store, CLOCK);
        assertEquals(2, restarted.pendingCount());
        assertEquals(mail, restarted.pending(PLAYER).orElseThrow());
        assertEquals(review, restarted.latestReview(PLAYER).orElseThrow());
        assertTrue(restarted.active(PLAYER).isEmpty());
        assertEquals(mail, restarted.get(mail.id(), PLAYER));
        assertEquals(review, restarted.get(review.id(), PLAYER));
        assertEquals(aborted, restarted.get(aborted.id(), PLAYER));
        assertEquals(Set.copyOf(completed.stream().map(OpeningRecord::id).toList()), restarted.virtualOpeningIds());
        assertTrue(restarted.contains(completed.getFirst().id()));
        assertFalse(restarted.canSpendPeriodicKey(completed.getFirst().id()));
        assertTrue(store.lookups.isEmpty(), "routine state and quota checks must not read the archive");

        // The three newest records are ABORTED, REVIEW and MAIL; this page is all completed records.
        assertEquals(List.of(completed.get(3), completed.get(2), completed.get(1)), restarted.history(PLAYER, 1, 3));
        assertEquals(List.of(completed.get(3).id(), completed.get(2).id(), completed.get(1).id()), store.lookups);
        store.lookups.clear();
        assertEquals(List.of(aborted, review, mail), restarted.history(PLAYER, 0, 3));
        assertTrue(store.lookups.isEmpty());
    }

    @Test void completedVirtualPeriodStaysSpentAfterRestartWithoutRollingAgain() {
        TrackingStore store = new TrackingStore();
        OpeningLedger ledger = new OpeningLedger(store, CLOCK);
        UUID id = UUID.randomUUID();
        String witness = "virtual:v1:daily-window";
        OpeningRecord done = complete(ledger, id, witness);
        OpeningLedger restarted = new OpeningLedger(store, CLOCK);
        AtomicInteger rolls = new AtomicInteger();

        var existing = restarted.reserveVirtual(id, PLAYER, POOL, () -> {
            rolls.incrementAndGet();
            return List.of(RARE);
        }, witness);
        assertEquals(OpeningLedger.VirtualReservationStatus.EXISTING_PERIOD, existing.status());
        assertEquals(done, existing.record());
        assertEquals(0, rolls.get());
        assertEquals(List.of(id), store.lookups);
        assertFalse(restarted.canSpendPeriodicKey(id));
        assertThrows(IllegalStateException.class,
                () -> restarted.reserve(id, PLAYER, POOL, List.of(COMMON), "another-key"));
    }

    @Test void missingUnreadableOrMismatchedArchiveNeverForgetsTheSpentReceipt() {
        TrackingStore store = new TrackingStore();
        OpeningLedger ledger = new OpeningLedger(store, CLOCK);
        OpeningRecord done = complete(ledger, UUID.randomUUID(), "virtual:v1:daily-window");
        assertThrows(IllegalArgumentException.class, () -> ledger.get(done.id(), UUID.randomUUID()));
        assertTrue(store.lookups.isEmpty(), "wrong owner must be rejected before disk lookup");

        store.values.remove(done.id());
        assertUnavailableButSpent(ledger, done);
        store.values.put(done.id(), done);
        store.failRead = true;
        assertUnavailableButSpent(ledger, done);
        store.failRead = false;

        PoolSnapshot otherCrate = new PoolSnapshot("other", POOL.seasonId(), POOL.rewards(),
                POOL.choiceCount(), POOL.maxRerolls(), POOL.bundleSize());
        List<OpeningRecord> mismatches = List.of(
                modified(done, UUID.randomUUID(), PLAYER, POOL, done.createdAt(), done.updatedAt(), done.revision(), done.stage(), done.keyWitness()),
                modified(done, done.id(), UUID.randomUUID(), POOL, done.createdAt(), done.updatedAt(), done.revision(), done.stage(), done.keyWitness()),
                modified(done, done.id(), PLAYER, otherCrate, done.createdAt(), done.updatedAt(), done.revision(), done.stage(), done.keyWitness()),
                modified(done, done.id(), PLAYER, POOL, done.createdAt() + 1, done.updatedAt() + 1, done.revision(), done.stage(), done.keyWitness()),
                modified(done, done.id(), PLAYER, POOL, done.createdAt(), done.updatedAt() + 1, done.revision(), done.stage(), done.keyWitness()),
                modified(done, done.id(), PLAYER, POOL, done.createdAt(), done.updatedAt(), done.revision() + 1, done.stage(), done.keyWitness()),
                modified(done, done.id(), PLAYER, POOL, done.createdAt(), done.updatedAt(), done.revision(), OpeningRecord.Stage.MAIL, done.keyWitness()),
                modified(done, done.id(), PLAYER, POOL, done.createdAt(), done.updatedAt(), done.revision(), done.stage(), "physical-key")
        );
        for (OpeningRecord mismatch : mismatches) {
            store.values.put(done.id(), mismatch);
            assertUnavailableButSpent(ledger, done);
        }
        store.values.put(done.id(), done);
        assertEquals(done, ledger.get(done.id(), PLAYER));
    }

    @Test void failedTerminalReadbackKeepsTheLastFullRecordAndRequiresReconciliation() throws Exception {
        TrackingStore store = new TrackingStore();
        OpeningLedger ledger = new OpeningLedger(store, CLOCK);
        OpeningRecord started = delivering(ledger, UUID.randomUUID(), "key-witness");
        store.mismatchReadback = true;
        assertThrows(IllegalStateException.class,
                () -> ledger.deliveryConfirmed(started.id(), PLAYER, started.revision()));
        assertFalse(ledger.available());
        assertEquals(started, ledger.get(started.id(), PLAYER));
        assertEquals(started, cachedMap(ledger, "records").get(started.id()));
        assertTrue(cachedMap(ledger, "delivered").isEmpty());
        assertEquals(OpeningRecord.Stage.DELIVERED, store.values.get(started.id()).stage());
    }

    @Test void abortedPhysicalRetryRetainsItsFrozenOffersAndNeverLoadsAnArchive() {
        TrackingStore store = new TrackingStore();
        OpeningLedger ledger = new OpeningLedger(store, CLOCK);
        UUID id = UUID.randomUUID();
        OpeningRecord reserved = ledger.reservePeriodicKey(id, PLAYER, POOL, () -> List.of(RARE), "old-key-witness");
        OpeningRecord aborted = ledger.debitRejected(id, PLAYER, reserved.revision(), "unchanged");
        OpeningLedger restarted = new OpeningLedger(store, CLOCK);
        PoolSnapshot changed = new PoolSnapshot(POOL.crateId(), "new-season", List.of(COMMON), 1, 0);
        OpeningRecord retried = restarted.reservePeriodicKey(id, PLAYER, changed,
                () -> { throw new AssertionError("Frozen offers must be reused"); }, "new-key-witness");
        assertEquals(aborted.pool(), retried.pool());
        assertEquals(aborted.offers(), retried.offers());
        assertEquals(aborted.createdAt(), retried.createdAt());
        assertTrue(store.lookups.isEmpty());
    }

    @Test void duplicateIdsAcrossFullAndCompactRecordsFailStartupInEitherOrder() {
        TrackingStore store = new TrackingStore();
        OpeningRecord done = complete(new OpeningLedger(store, CLOCK), UUID.randomUUID(), "key-witness");
        OpeningRecord mail = modified(done, done.id(), PLAYER, POOL, done.createdAt(), done.updatedAt(),
                done.revision(), OpeningRecord.Stage.MAIL, done.keyWitness());
        for (List<OpeningRecord> entries : List.of(List.of(done, mail), List.of(mail, done))) {
            OpeningStore duplicates = new OpeningStore() {
                @Override public List<OpeningRecord> load() { return entries; }
                @Override public OpeningRecord commit(OpeningRecord record) { throw new AssertionError(); }
            };
            assertThrows(IllegalStateException.class, () -> new OpeningLedger(duplicates, CLOCK));
        }
    }

    private static void assertUnavailableButSpent(OpeningLedger ledger, OpeningRecord done) {
        assertThrows(RuntimeException.class, () -> ledger.get(done.id(), PLAYER));
        assertTrue(ledger.contains(done.id()));
        assertFalse(ledger.canSpendPeriodicKey(done.id()));
        assertEquals(Set.of(done.id()), ledger.virtualOpeningIds());
        assertThrows(RuntimeException.class, () -> ledger.reserveVirtual(done.id(), PLAYER, POOL,
                () -> { throw new AssertionError("Archive failure must not create a fresh roll"); }, done.keyWitness()));
        assertThrows(IllegalStateException.class,
                () -> ledger.reserve(done.id(), PLAYER, POOL, List.of(COMMON), "another-key"));
    }

    private static void assertCompact(OpeningLedger ledger, OpeningRecord done) throws Exception {
        assertFalse(cachedMap(ledger, "records").containsKey(done.id()));
        Object receipt = cachedMap(ledger, "delivered").get(done.id());
        assertNotNull(receipt);
        assertEquals(List.of(UUID.class, UUID.class, String.class, long.class, long.class, long.class, boolean.class),
                Arrays.stream(receipt.getClass().getRecordComponents()).map(RecordComponent::getType).toList());
    }

    private static Map<?, ?> cachedMap(OpeningLedger ledger, String name) throws Exception {
        var field = OpeningLedger.class.getDeclaredField(name);
        field.setAccessible(true);
        return (Map<?, ?>) field.get(ledger);
    }

    private static OpeningRecord complete(OpeningLedger ledger, UUID id, String witness) {
        OpeningRecord started = delivering(ledger, id, witness);
        return ledger.deliveryConfirmed(id, PLAYER, started.revision());
    }

    private static OpeningRecord delivering(OpeningLedger ledger, UUID id, String witness) {
        OpeningRecord record = witness.startsWith("virtual:v1:")
                ? ledger.reserveVirtual(id, PLAYER, POOL, () -> List.of(COMMON, RARE), witness).record()
                : ledger.reserve(id, PLAYER, POOL, List.of(COMMON, RARE), witness);
        if (record.stage() == OpeningRecord.Stage.RESERVED) record = ledger.debitConfirmed(id, PLAYER, record.revision());
        record = ledger.select(id, PLAYER, record.revision(), RARE.id());
        record = ledger.prepared(id, PLAYER, record.revision(), "native-prize".repeat(1000));
        return ledger.deliveryStarted(id, PLAYER, record.revision(), "native-inventory-witness".repeat(1000));
    }

    private static OpeningRecord modified(OpeningRecord record, UUID id, UUID player, PoolSnapshot pool,
            long created, long updated, long revision, OpeningRecord.Stage stage, String keyWitness) {
        return new OpeningRecord(id, player, pool, created, updated, revision, stage, record.offers(),
                record.rerollsUsed(), record.selectedRewardId(), keyWitness, record.preparedReward(),
                record.deliveryWitness(), record.reason());
    }

    private static final class TrackingStore implements OpeningStore {
        private final Map<UUID, OpeningRecord> values = new HashMap<>();
        private final List<UUID> lookups = new ArrayList<>();
        private boolean failRead;
        private boolean mismatchReadback;
        @Override public List<OpeningRecord> load() { return List.copyOf(values.values()); }
        @Override public Optional<OpeningRecord> find(UUID id) {
            lookups.add(id);
            if (failRead) throw new IllegalStateException("Archive unavailable");
            return Optional.ofNullable(values.get(id));
        }
        @Override public OpeningRecord commit(OpeningRecord record) {
            values.put(record.id(), record);
            if (!mismatchReadback) return record;
            return modified(record, record.id(), record.playerId(), record.pool(), record.createdAt(),
                    record.updatedAt(), record.revision(), record.stage(), record.keyWitness() + "-mismatch");
        }
    }
}
