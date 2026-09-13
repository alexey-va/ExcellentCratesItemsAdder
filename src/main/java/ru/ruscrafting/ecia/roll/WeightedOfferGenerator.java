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

    public OfferSet generate(PoolSnapshot pool) {
        Objects.requireNonNull(pool, "pool");
        int targetSize = Math.min(pool.choiceCount(), pool.rewards().size());
        List<RewardDefinition> candidates = new ArrayList<>(pool.rewards());
        List<RewardDefinition> selected = new ArrayList<>(targetSize);
        while (selected.size() < targetSize) {
            selected.add(candidates.remove(weightedIndex(candidates)));
        }
        return new OfferSet(selected);
    }

    public OfferSet reroll(PoolSnapshot pool, OfferSet currentOffer, int rerollsUsed) {
        Objects.requireNonNull(pool, "pool");
        Objects.requireNonNull(currentOffer, "currentOffer");
        if (rerollsUsed < 0 || rerollsUsed >= pool.maxRerolls()) {
            throw new IllegalArgumentException("rerollsUsed must be within the configured reroll bound");
        }

        List<RewardDefinition> selected = new ArrayList<>(currentOffer.rewards());
        int replaced = 0;
        for (int index = 1; index < selected.size(); index++) {
            if (selected.get(index).weight() > selected.get(replaced).weight()) replaced = index;
        }
        List<RewardDefinition> candidates = new ArrayList<>(pool.rewards());
        candidates.removeAll(currentOffer.rewards());
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("reward pool has no unseen reward for reroll");
        }
        selected.set(replaced, candidates.remove(weightedIndex(candidates)));
        return new OfferSet(selected);
    }

    /** Builds a distinct weighted bundle with the player's selected headline reward first. */
    public List<RewardDefinition> bundle(PoolSnapshot pool, RewardDefinition selectedReward) {
        Objects.requireNonNull(pool, "pool");
        Objects.requireNonNull(selectedReward, "selectedReward");
        if (!pool.rewards().contains(selectedReward)) {
            throw new IllegalArgumentException("selected reward is outside the pool");
        }
        List<RewardDefinition> candidates = new ArrayList<>(pool.rewards());
        candidates.remove(selectedReward);
        List<RewardDefinition> bundle = new ArrayList<>(pool.bundleSize());
        bundle.add(selectedReward);
        while (bundle.size() < pool.bundleSize()) {
            bundle.add(candidates.remove(weightedIndex(candidates)));
        }
        return List.copyOf(bundle);
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

}
