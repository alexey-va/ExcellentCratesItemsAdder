package ru.ruscrafting.ecia.analytics;

import ru.ruscrafting.ecia.journal.OpeningRecord;
import ru.ruscrafting.ecia.roll.PoolSnapshot;
import ru.ruscrafting.ecia.roll.RewardDefinition;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Pure aggregation over persisted opening records.
 *
 * An opening is counted only after the key debit is proven. The record's
 * {@code offers} list is the final set shown to the player after any rerolls;
 * it is intentionally not a history of every intermediate offer set.
 */
public final class OpeningAnalytics {
    public static List<OpeningDistribution> summarize(List<OpeningRecord> records) {
        Objects.requireNonNull(records, "records");
        Map<GroupKey, Aggregate> groups = new LinkedHashMap<>();
        for (OpeningRecord record : records) {
            Objects.requireNonNull(record, "records entry");
            if (!isCommitted(record)) {
                continue;
            }
            GroupKey key = new GroupKey(record.pool().crateId(), record.pool().seasonId(),
                    record.guaranteed(), record.rerollsUsed() > 0);
            groups.computeIfAbsent(key, ignored -> new Aggregate(record.pool())).add(record);
        }
        return groups.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator
                        .comparing(GroupKey::crateId)
                        .thenComparing(GroupKey::seasonId)
                        .thenComparing(GroupKey::guaranteed)
                        .thenComparing(GroupKey::rerolled)))
                .map(entry -> entry.getValue().toDistribution(entry.getKey()))
                .toList();
    }

    private static boolean isCommitted(OpeningRecord record) {
        return record.stage() != OpeningRecord.Stage.RESERVED
                && record.stage() != OpeningRecord.Stage.ABORTED
                && (record.stage() != OpeningRecord.Stage.REVIEW
                || !record.selectedRewardId().isEmpty());
    }

    private record GroupKey(String crateId, String seasonId, boolean guaranteed, boolean rerolled) {
    }

    private static final class Aggregate {
        private final PoolSnapshot pool;
        private final Map<String, Long> offeredCounts = new LinkedHashMap<>();
        private final Map<String, Long> selectedCounts = new LinkedHashMap<>();
        private long openingCount;

        private Aggregate(PoolSnapshot pool) {
            this.pool = pool;
        }

        private void add(OpeningRecord record) {
            if (!pool.equals(record.pool())) {
                throw new IllegalArgumentException("Conflicting pool definitions for "
                        + pool.crateId() + "/" + pool.seasonId());
            }
            openingCount = Math.addExact(openingCount, 1);
            // `offers` is the final visible set, not the complete reroll history.
            for (RewardDefinition reward : record.offers()) {
                increment(offeredCounts, reward.id());
            }
            if (!record.selectedRewardId().isEmpty()) {
                increment(selectedCounts, record.selectedRewardId());
            }
        }

        private OpeningDistribution toDistribution(GroupKey key) {
            return new OpeningDistribution(
                    key.crateId(),
                    key.seasonId(),
                    key.guaranteed(),
                    key.rerolled(),
                    openingCount,
                    offeredCounts,
                    selectedCounts,
                    baseWeightShares(pool)
            );
        }

        private static void increment(Map<String, Long> counts, String id) {
            counts.merge(id, 1L, Math::addExact);
        }
    }

    private static Map<String, Double> baseWeightShares(PoolSnapshot pool) {
        double maximum = pool.rewards().stream().mapToDouble(RewardDefinition::weight).max().orElseThrow();
        double total = 0.0d;
        for (RewardDefinition reward : pool.rewards()) {
            total += reward.weight() / maximum;
        }
        Map<String, Double> shares = new LinkedHashMap<>();
        for (RewardDefinition reward : pool.rewards()) {
            shares.put(reward.id(), (reward.weight() / maximum) / total);
        }
        return shares;
    }
}
