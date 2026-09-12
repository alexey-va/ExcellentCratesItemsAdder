package ru.ruscrafting.ecia.roll;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.random.RandomGenerator;

/** Generates weighted, without-replacement offers without any platform dependency. */
public final class WeightedOfferGenerator {
    private final RandomGenerator random;

    public WeightedOfferGenerator(RandomGenerator random) {
        this.random = Objects.requireNonNull(random, "random");
    }

    public OfferSet generate(PoolSnapshot pool, int consecutiveUnsuccessfulOpenings) {
        Objects.requireNonNull(pool, "pool");
        boolean guaranteed = pool.guaranteeDue(consecutiveUnsuccessfulOpenings);
        int targetSize = Math.min(pool.choiceCount(), pool.rewards().size());
        List<RewardDefinition> candidates = new ArrayList<>(pool.rewards());
        List<RewardDefinition> selected = new ArrayList<>(targetSize);
        if (guaranteed) {
            List<RewardDefinition> qualifying = candidates.stream()
                    .filter(RewardDefinition::guaranteeEligible)
                    .toList();
            RewardDefinition forced = qualifying.get(weightedIndex(qualifying));
            candidates.removeIf(reward -> reward.id().equals(forced.id()));
            selected.add(forced);
        }
        while (selected.size() < targetSize) {
            selected.add(candidates.remove(weightedIndex(candidates)));
        }
        return new OfferSet(selected, guaranteed);
    }

    public OfferSet reroll(PoolSnapshot pool, OfferSet currentOffer, int rerollsUsed) {
        Objects.requireNonNull(pool, "pool");
        Objects.requireNonNull(currentOffer, "currentOffer");
        if (rerollsUsed < 0 || rerollsUsed >= pool.maxRerolls()) {
            throw new IllegalArgumentException("rerollsUsed must be within the configured reroll bound");
        }

        int targetSize = Math.min(pool.choiceCount(), pool.rewards().size());
        List<RewardDefinition> candidates = new ArrayList<>(pool.rewards());
        List<RewardDefinition> selected = new ArrayList<>(targetSize);
        if (currentOffer.guaranteed()) {
            RewardDefinition preserved = currentOffer.rewards().stream()
                    .filter(RewardDefinition::guaranteeEligible)
                    .map(reward -> findById(pool.rewards(), reward.id()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "guaranteed offer has no qualifying reward in the pool"));
            selected.add(preserved);
            candidates.removeIf(reward -> reward.id().equals(preserved.id()));
        }
        while (selected.size() < targetSize) {
            selected.add(candidates.remove(weightedIndex(candidates)));
        }
        return new OfferSet(selected, currentOffer.guaranteed());
    }

    /**
     * Advances persisted pity only when the player actually selected a
     * qualifying reward. A due guarantee followed by an ordinary selection
     * therefore remains due rather than being reset.
     */
    public int nextUnsuccessfulOpenings(
            PoolSnapshot pool,
            int previousUnsuccessfulOpenings,
            RewardDefinition selectedReward
    ) {
        Objects.requireNonNull(pool, "pool");
        Objects.requireNonNull(selectedReward, "selectedReward");
        if (previousUnsuccessfulOpenings < 0) {
            throw new IllegalArgumentException("previousUnsuccessfulOpenings must be non-negative");
        }
        RewardDefinition canonical = findById(pool.rewards(), selectedReward.id());
        if (canonical.guaranteeEligible()) {
            return 0;
        }
        return previousUnsuccessfulOpenings == Integer.MAX_VALUE
                ? Integer.MAX_VALUE
                : previousUnsuccessfulOpenings + 1;
    }

    private int weightedIndex(List<RewardDefinition> candidates) {
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("cannot draw from an empty candidate set");
        }
        double maximum = candidates.stream().mapToDouble(RewardDefinition::weight).max().orElseThrow();
        double total = 0.0d;
        for (RewardDefinition candidate : candidates) {
            total += candidate.weight() / maximum;
        }
        double unit = random.nextDouble();
        if (!Double.isFinite(unit) || unit < 0.0d || unit >= 1.0d) {
            throw new IllegalStateException("random generator returned a value outside [0, 1)");
        }
        double target = unit * total;
        double cumulative = 0.0d;
        for (int index = 0; index < candidates.size(); index++) {
            cumulative += candidates.get(index).weight() / maximum;
            if (target < cumulative || index == candidates.size() - 1) {
                return index;
            }
        }
        throw new IllegalStateException("weighted draw did not select a candidate");
    }

    private static RewardDefinition findById(List<RewardDefinition> rewards, String id) {
        return rewards.stream()
                .filter(reward -> reward.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("reward is not part of the pool: " + id));
    }
}
