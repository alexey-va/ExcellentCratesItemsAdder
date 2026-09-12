package ru.ruscrafting.ecia.analytics;

import org.junit.jupiter.api.Test;
import ru.ruscrafting.ecia.journal.OpeningLedger;
import ru.ruscrafting.ecia.journal.OpeningRecord;
import ru.ruscrafting.ecia.roll.PoolSnapshot;
import ru.ruscrafting.ecia.roll.RewardDefinition;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OpeningAnalyticsTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000728");
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(1234), ZoneOffset.UTC);
    private static final RewardDefinition COMMON = new RewardDefinition(
            "common", 3.0, false, "deliver:common", "preview:common");
    private static final RewardDefinition RARE = new RewardDefinition(
            "rare", 1.0, true, "deliver:rare", "preview:rare");
    private static final PoolSnapshot POOL = new PoolSnapshot(
            "crate", "summer", List.of(COMMON, RARE), 3, 3, 1);

    @Test
    void groupsModesSeparatelyAndKeepsOfferedAndSelectedCountsDistinct() {
        OpeningLedger ledger = new OpeningLedger(new MemoryStore(), CLOCK);
        OpeningRecord ordinary = ready(ledger, List.of(COMMON, RARE), false);
        ordinary = ledger.select(ordinary.id(), PLAYER, ordinary.revision(), COMMON.id());
        OpeningRecord guaranteed = ready(ledger, List.of(COMMON, RARE), true);
        guaranteed = ledger.select(guaranteed.id(), PLAYER, guaranteed.revision(), RARE.id());
        OpeningRecord rerolled = ready(ledger, List.of(COMMON, RARE), false);
        rerolled = ledger.reroll(rerolled.id(), PLAYER, rerolled.revision(), List.of(RARE), false);
        rerolled = ledger.select(rerolled.id(), PLAYER, rerolled.revision(), RARE.id());

        List<OpeningDistribution> distributions = new OpeningAnalytics()
                .summarize(List.of(ordinary, guaranteed, rerolled));

        assertEquals(3, distributions.size());
        OpeningDistribution ordinaryReport = distributions.stream()
                .filter(report -> report.mode() == OpeningDistribution.Mode.ORDINARY)
                .findFirst().orElseThrow();
        assertEquals(1, ordinaryReport.openingCount());
        assertEquals(1L, ordinaryReport.offeredCounts().get("common"));
        assertEquals(1L, ordinaryReport.selectedCounts().get("common"));
        assertEquals(0.75, ordinaryReport.baseWeightShares().get("common"));
        assertEquals(0.25, ordinaryReport.baseWeightShares().get("rare"));
        OpeningDistribution rerolledReport = distributions.stream()
                .filter(report -> report.mode() == OpeningDistribution.Mode.REROLLED_ORDINARY)
                .findFirst().orElseThrow();
        assertEquals(1, rerolledReport.openingCount());
        assertEquals(1L, rerolledReport.offeredCounts().get("rare"));
        assertFalse(rerolledReport.offeredCounts().containsKey("common"));
        assertEquals(1L, rerolledReport.selectedCounts().get("rare"));
    }

    @Test
    void excludesAttemptsUntilDebitIsProvenButKeepsCommittedStates() {
        List<OpeningRecord> records = List.of(
                record(OpeningRecord.Stage.RESERVED, ""),
                record(OpeningRecord.Stage.ABORTED, ""),
                record(OpeningRecord.Stage.REVIEW, ""),
                record(OpeningRecord.Stage.CHOOSING, ""),
                record(OpeningRecord.Stage.MAIL, COMMON.id()),
                record(OpeningRecord.Stage.DELIVERING, COMMON.id()),
                record(OpeningRecord.Stage.DELIVERED, RARE.id()),
                record(OpeningRecord.Stage.REVIEW, RARE.id())
        );

        List<OpeningDistribution> distributions = OpeningAnalytics.summarize(records);

        assertEquals(1, distributions.size());
        OpeningDistribution report = distributions.getFirst();
        assertEquals(5, report.openingCount());
        assertEquals(5L, report.offeredCounts().get("common"));
        assertEquals(5L, report.offeredCounts().get("rare"));
        assertEquals(2L, report.selectedCounts().get("common"));
        assertEquals(2L, report.selectedCounts().get("rare"));
    }

    @Test
    void rejectsConflictingDefinitionsWithinAnImmutableSeason() {
        OpeningLedger ledger = new OpeningLedger(new MemoryStore(), CLOCK);
        OpeningRecord first = ready(ledger, List.of(COMMON, RARE), false);
        OpeningRecord firstCompleted = ledger.select(first.id(), PLAYER, first.revision(), COMMON.id());
        PoolSnapshot changed = new PoolSnapshot("crate", "summer", List.of(
                new RewardDefinition("common", 2.0, false, "deliver:common", "preview:common"), RARE), 3, 3, 1);
        OpeningRecord conflicting = new OpeningRecord(
                UUID.randomUUID(), PLAYER, changed, 10, 10, 0, OpeningRecord.Stage.CHOOSING,
                List.of(changed.rewards().getFirst(), RARE), false, 0, "", "key", "", "", "");

        assertThrows(IllegalArgumentException.class,
                () -> new OpeningAnalytics().summarize(List.of(firstCompleted, conflicting)));
    }

    private static OpeningRecord ready(OpeningLedger ledger, List<RewardDefinition> offers, boolean guaranteed) {
        OpeningRecord record = ledger.reserve(UUID.randomUUID(), PLAYER, POOL, offers, guaranteed, "key-witness");
        return ledger.debitConfirmed(record.id(), PLAYER, record.revision());
    }

    private static OpeningRecord record(OpeningRecord.Stage stage, String selectedRewardId) {
        String prepared = stage == OpeningRecord.Stage.DELIVERING || stage == OpeningRecord.Stage.DELIVERED
                ? "prepared-payload" : "";
        String witness = stage == OpeningRecord.Stage.DELIVERING || stage == OpeningRecord.Stage.DELIVERED
                ? "delivery-witness" : "";
        return new OpeningRecord(
                UUID.randomUUID(), PLAYER, POOL, 1, 1, 0, stage,
                List.of(COMMON, RARE), false, 0, selectedRewardId,
                "key-witness", prepared, witness, ""
        );
    }

    private static final class MemoryStore implements ru.ruscrafting.ecia.journal.OpeningStore {
        private final java.util.Map<UUID, OpeningRecord> records = new java.util.HashMap<>();

        @Override
        public List<OpeningRecord> load() {
            return List.copyOf(records.values());
        }

        @Override
        public OpeningRecord commit(OpeningRecord record) {
            records.put(record.id(), record);
            return record;
        }
    }
}
