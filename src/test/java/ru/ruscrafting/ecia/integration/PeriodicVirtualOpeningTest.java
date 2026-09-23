package ru.ruscrafting.ecia.integration;

import org.junit.jupiter.api.Test;
import ru.ruscrafting.ecia.journal.OpeningLedger;
import ru.ruscrafting.ecia.journal.OpeningRecord;
import ru.ruscrafting.ecia.journal.OpeningStore;
import ru.ruscrafting.ecia.roll.PoolSnapshot;
import ru.ruscrafting.ecia.roll.RewardDefinition;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class PeriodicVirtualOpeningTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000728");
    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");

    @Test
    void dailyWindowUsesLocalCalendarMidnightAndDoesNotAccumulate() {
        var beforeMidnight = PeriodicVirtualOpening.window(
                PeriodicVirtualOpening.Period.DAILY, Instant.parse("2026-09-22T20:59:59Z"), MOSCOW);
        var afterMidnight = PeriodicVirtualOpening.window(
                PeriodicVirtualOpening.Period.DAILY, Instant.parse("2026-09-22T21:00:00Z"), MOSCOW);

        assertEquals(Instant.parse("2026-09-22T21:00:00Z"), beforeMidnight.nextReset());
        assertEquals(Instant.parse("2026-09-22T21:00:00Z"), afterMidnight.start());
        assertNotEquals(
                PeriodicVirtualOpening.openingId(PLAYER, "case_daily", PeriodicVirtualOpening.Period.DAILY, beforeMidnight),
                PeriodicVirtualOpening.openingId(PLAYER, "case_daily", PeriodicVirtualOpening.Period.DAILY, afterMidnight));
        assertEquals(
                PeriodicVirtualOpening.openingId(PLAYER, "case_daily", PeriodicVirtualOpening.Period.DAILY, afterMidnight),
                PeriodicVirtualOpening.openingId(PLAYER, "case_daily", PeriodicVirtualOpening.Period.DAILY, afterMidnight));
    }

    @Test
    void weeklyWindowResetsOnMondayInConfiguredZone() {
        var sunday = PeriodicVirtualOpening.window(
                PeriodicVirtualOpening.Period.WEEKLY, Instant.parse("2026-09-27T20:59:59Z"), MOSCOW);
        var monday = PeriodicVirtualOpening.window(
                PeriodicVirtualOpening.Period.WEEKLY, Instant.parse("2026-09-27T21:00:00Z"), MOSCOW);

        assertEquals(Instant.parse("2026-09-27T21:00:00Z"), sunday.nextReset());
        assertEquals(Instant.parse("2026-09-27T21:00:00Z"), monday.start());
        assertNotEquals(
                PeriodicVirtualOpening.openingId(PLAYER, "case_weekly", PeriodicVirtualOpening.Period.WEEKLY, sunday),
                PeriodicVirtualOpening.openingId(PLAYER, "case_weekly", PeriodicVirtualOpening.Period.WEEKLY, monday));
    }

    @Test
    void quotasAreScopedByPlayerCaseAndCadence() {
        var window = PeriodicVirtualOpening.window(
                PeriodicVirtualOpening.Period.DAILY, Instant.parse("2026-09-23T12:00:00Z"), MOSCOW);
        UUID daily = PeriodicVirtualOpening.openingId(PLAYER, "case_daily", PeriodicVirtualOpening.Period.DAILY, window);

        assertNotEquals(daily, PeriodicVirtualOpening.openingId(
                UUID.randomUUID(), "case_daily", PeriodicVirtualOpening.Period.DAILY, window));
        assertNotEquals(daily, PeriodicVirtualOpening.openingId(
                PLAYER, "case_weekly", PeriodicVirtualOpening.Period.DAILY, window));
        var weeklyWindow = PeriodicVirtualOpening.window(
                PeriodicVirtualOpening.Period.WEEKLY, Instant.parse("2026-09-23T12:00:00Z"), MOSCOW);
        assertNotEquals(daily, PeriodicVirtualOpening.openingId(
                PLAYER, "case_daily", PeriodicVirtualOpening.Period.WEEKLY, weeklyWindow));
    }

    @Test
    void durablePeriodIdFreezesOneRollAndTerminalClaimCannotBeReopened() {
        var reward = new RewardDefinition("prize", 1, "delivery", "preview");
        var pool = new PoolSnapshot("case_daily", "launch", List.of(reward), 1, 0, 1);
        var ledger = new OpeningLedger(new MemoryStore(), Clock.fixed(Instant.parse("2026-09-23T12:00:00Z"), ZoneOffset.UTC));
        var window = PeriodicVirtualOpening.window(PeriodicVirtualOpening.Period.DAILY,
                Instant.parse("2026-09-23T12:00:00Z"), MOSCOW);
        UUID id = PeriodicVirtualOpening.openingId(PLAYER, pool.crateId(), PeriodicVirtualOpening.Period.DAILY, window);
        String witness = PeriodicVirtualOpening.witness(pool.crateId(), window);
        AtomicInteger rolls = new AtomicInteger();

        var first = ledger.reserveVirtual(id, PLAYER, pool, () -> {
            rolls.incrementAndGet();
            return List.of(reward);
        }, witness);
        var resumed = ledger.reserveVirtual(id, PLAYER, pool, () -> {
            rolls.incrementAndGet();
            return List.of(reward);
        }, witness);
        assertEquals(OpeningLedger.VirtualReservationStatus.CREATED, first.status());
        assertEquals(OpeningLedger.VirtualReservationStatus.EXISTING_PERIOD, resumed.status());
        assertEquals(first.record(), resumed.record());
        assertEquals(1, rolls.get());

        OpeningRecord mail = ledger.select(id, PLAYER, first.record().revision(), reward.id());
        OpeningRecord prepared = ledger.prepared(id, PLAYER, mail.revision(), "persisted-prize");
        OpeningRecord started = ledger.deliveryStarted(id, PLAYER, prepared.revision(), "delivery-witness");
        OpeningRecord delivered = ledger.deliveryConfirmed(id, PLAYER, started.revision());
        var spent = ledger.reserveVirtual(id, PLAYER, pool, () -> {
            rolls.incrementAndGet();
            return List.of(reward);
        }, witness);

        assertEquals(OpeningRecord.Stage.DELIVERED, delivered.stage());
        assertEquals(OpeningRecord.Stage.DELIVERED, spent.record().stage());
        assertEquals(OpeningLedger.VirtualReservationStatus.EXISTING_PERIOD, spent.status());
        assertEquals(1, rolls.get());
        assertEquals(java.util.Set.of(id), ledger.virtualOpeningIds());
    }

    private static final class MemoryStore implements OpeningStore {
        private final Map<UUID, OpeningRecord> records = new HashMap<>();
        @Override public List<OpeningRecord> load() { return List.copyOf(records.values()); }
        @Override public OpeningRecord commit(OpeningRecord record) {
            records.put(record.id(), record);
            return record;
        }
    }
}
