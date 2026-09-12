package ru.ruscrafting.ecia.journal;

import ru.ruscrafting.ecia.roll.PoolSnapshot;
import ru.ruscrafting.ecia.roll.RewardDefinition;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** An opening is the durable owner of one key debit and exactly one selected prize. */
public record OpeningRecord(
        UUID id, UUID playerId, PoolSnapshot pool, long createdAt, long updatedAt,
        long revision, Stage stage, List<RewardDefinition> offers,
        int rerollsUsed, String selectedRewardId, String keyWitness,
        String preparedReward, String deliveryWitness, String reason
) {
    public enum Stage { RESERVED, CHOOSING, MAIL, DELIVERING, DELIVERED, REVIEW, ABORTED }

    public OpeningRecord {
        Objects.requireNonNull(id);
        Objects.requireNonNull(playerId);
        Objects.requireNonNull(pool);
        Objects.requireNonNull(stage);
        offers = List.copyOf(offers);
        Objects.requireNonNull(selectedRewardId);
        keyWitness = bounded(keyWitness, 2_000_000, "key witness");
        preparedReward = bounded(preparedReward, 2_000_000, "prepared reward");
        deliveryWitness = bounded(deliveryWitness, 2_000_000, "delivery witness");
        reason = bounded(reason, 1024, "reason");
        if (createdAt < 0 || updatedAt < createdAt || revision < 0 || rerollsUsed < 0
                || rerollsUsed > pool.maxRerolls()) throw new IllegalArgumentException("Invalid opening counters");
        if (offers.isEmpty() || offers.size() > pool.choiceCount()
                || offers.stream().map(RewardDefinition::id).distinct().count() != offers.size()) {
            throw new IllegalArgumentException("Invalid opening offers");
        }
        for (RewardDefinition offer : offers) {
            if (!pool.rewards().contains(offer)) throw new IllegalArgumentException("Offer is outside frozen pool");
        }
        if (keyWitness.isBlank()) throw new IllegalArgumentException("Key debit witness required");
        if (!selectedRewardId.isEmpty() && offers.stream().noneMatch(r -> r.id().equals(selectedRewardId))) {
            throw new IllegalArgumentException("Selected reward is outside offers");
        }
        if ((stage == Stage.MAIL || stage == Stage.DELIVERING || stage == Stage.DELIVERED)
                && selectedRewardId.isEmpty()) throw new IllegalArgumentException("Selected reward required");
        if ((stage == Stage.DELIVERING || stage == Stage.DELIVERED)
                && (preparedReward.isEmpty() || deliveryWitness.isEmpty())) {
            throw new IllegalArgumentException("Delivery requires a persisted payload and witness");
        }
    }

    public boolean pending() { return stage != Stage.DELIVERED && stage != Stage.ABORTED; }

    public RewardDefinition selectedReward() {
        return offers.stream().filter(r -> r.id().equals(selectedRewardId)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Opening has no selected reward"));
    }

    private static String bounded(String value, int max, String field) {
        Objects.requireNonNull(value, field);
        if (value.length() > max) throw new IllegalArgumentException("Oversized " + field);
        return value;
    }
}
