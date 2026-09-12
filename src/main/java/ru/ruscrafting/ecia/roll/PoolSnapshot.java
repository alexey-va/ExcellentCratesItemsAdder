package ru.ruscrafting.ecia.roll;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable season-specific reward configuration captured by an opening. */
public record PoolSnapshot(
        String crateId,
        String seasonId,
        List<RewardDefinition> rewards,
        int choiceCount,
        int maxRerolls
) {
    public PoolSnapshot {
        RewardDefinition.requireIdentifier(crateId, "crateId");
        RewardDefinition.requireIdentifier(seasonId, "seasonId");
        List<RewardDefinition> immutableRewards = List.copyOf(Objects.requireNonNull(rewards, "rewards"));
        if (immutableRewards.isEmpty()) {
            throw new IllegalArgumentException("rewards must not be empty");
        }
        Set<String> ids = new HashSet<>();
        for (RewardDefinition reward : immutableRewards) {
            if (!ids.add(reward.id())) {
                throw new IllegalArgumentException("duplicate reward id: " + reward.id());
            }
        }
        if (choiceCount <= 0) {
            throw new IllegalArgumentException("choiceCount must be positive");
        }
        if (maxRerolls < 0) {
            throw new IllegalArgumentException("maxRerolls must be zero or positive");
        }
        rewards = immutableRewards;
    }

}
