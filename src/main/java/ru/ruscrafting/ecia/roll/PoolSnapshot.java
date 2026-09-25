package ru.ruscrafting.ecia.roll;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Reward configuration recorded for one opening; new openings read the current native pool. */
public record PoolSnapshot(
        String crateId,
        String seasonId,
        List<RewardDefinition> rewards,
        int choiceCount,
        int maxRerolls,
        int bundleSize
) {
    public PoolSnapshot(String crateId, String seasonId, List<RewardDefinition> rewards,
            int choiceCount, int maxRerolls) {
        this(crateId, seasonId, rewards, choiceCount, maxRerolls, 1);
    }

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
        if (bundleSize <= 0 || bundleSize > immutableRewards.size()) {
            throw new IllegalArgumentException("bundleSize must be positive and not exceed the reward pool");
        }
        rewards = immutableRewards;
    }

}
