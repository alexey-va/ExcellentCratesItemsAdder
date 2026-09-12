package ru.ruscrafting.ecia.roll;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable choices presented for one opening. */
public record OfferSet(List<RewardDefinition> rewards, boolean guaranteed) {
    public OfferSet {
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
        if (guaranteed && immutableRewards.stream().noneMatch(RewardDefinition::guaranteeEligible)) {
            throw new IllegalArgumentException("a guaranteed offer must include a qualifying reward");
        }
        rewards = immutableRewards;
    }
}
