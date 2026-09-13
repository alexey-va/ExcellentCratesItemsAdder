package ru.ruscrafting.ecia.roll;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WeightedOfferGeneratorTest {
    private static final RewardDefinition COMMON = reward("common", 1.0);
    private static final RewardDefinition RARE = reward("rare", 1.0);

    @Test
    void capsOfferAtPoolSizeInsteadOfLoopingWhenPoolHasFewerThanThreeRewards() {
        PoolSnapshot pool = new PoolSnapshot("crate", "season", List.of(COMMON), 3, 2);

        OfferSet offer = new WeightedOfferGenerator(new SequenceRandom(0.0)).generate(pool);

        assertEquals(List.of(COMMON), offer.rewards());
    }

    @Test
    void drawsAnOfferWithoutDuplicateRewardsAndCopiesInputs() {
        List<RewardDefinition> configured = new ArrayList<>(List.of(
                COMMON, RARE, reward("other", 1.0), reward("another", 1.0)
        ));
        PoolSnapshot pool = new PoolSnapshot("crate", "season", configured, 3, 0);
        configured.clear();

        OfferSet offer = new WeightedOfferGenerator(new SequenceRandom(0.0)).generate(pool);

        assertEquals(4, pool.rewards().size());
        assertEquals(3, offer.rewards().stream().map(RewardDefinition::id).distinct().count());
        assertThrows(UnsupportedOperationException.class, () -> offer.rewards().clear());
    }

    @Test
    void usesWeightedHalfOpenBoundaries() {
        RewardDefinition heavy = reward("heavy", 3.0);
        RewardDefinition light = reward("light", 1.0);
        PoolSnapshot pool = new PoolSnapshot("crate", "season", List.of(heavy, light), 1, 0);

        assertEquals("heavy", new WeightedOfferGenerator(new SequenceRandom(0.0))
                .generate(pool).rewards().getFirst().id());
        assertEquals("heavy", new WeightedOfferGenerator(new SequenceRandom(Math.nextDown(0.75)))
                .generate(pool).rewards().getFirst().id());
        assertEquals("light", new WeightedOfferGenerator(new SequenceRandom(0.75))
                .generate(pool).rewards().getFirst().id());
    }

    @Test
    void rerollReplacesOnlyTheMostCommonOptionWithAnUnseenReward() {
        RewardDefinition commonest = reward("commonest", 10.0);
        RewardDefinition other = reward("other", 2.0);
        RewardDefinition newReward = reward("new", 1.0);
        PoolSnapshot pool = new PoolSnapshot("crate", "season", List.of(commonest, RARE, other, newReward), 3, 1);
        WeightedOfferGenerator generator = new WeightedOfferGenerator(new SequenceRandom(0.0));
        OfferSet initial = new OfferSet(List.of(commonest, RARE, other));
        OfferSet rerolled = generator.reroll(pool, initial, 0);

        assertEquals(List.of(newReward, RARE, other), rerolled.rewards());
        assertFalse(rerolled.rewards().contains(commonest));
    }

    @Test
    void bundleKeepsSelectedRewardFirstAndAddsDistinctWeightedExtras() {
        RewardDefinition other = reward("other", 1.0);
        RewardDefinition another = reward("another", 1.0);
        PoolSnapshot pool = new PoolSnapshot("crate", "season", List.of(COMMON, RARE, other, another), 3, 1, 3);

        List<RewardDefinition> bundle = new WeightedOfferGenerator(new SequenceRandom(0.0, 0.0))
                .bundle(pool, RARE);

        assertEquals(RARE, bundle.getFirst());
        assertEquals(3, bundle.size());
        assertEquals(3, bundle.stream().map(RewardDefinition::id).distinct().count());
        assertTrue(pool.rewards().containsAll(bundle));
    }

    @Test
    void rejectsInvalidDefinitionsAndPoolConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> reward("", 1.0));
        assertThrows(IllegalArgumentException.class, () -> reward("bad", Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new PoolSnapshot(
                "crate", "season", List.of(COMMON, reward("common", 2.0)), 3, 0));
        assertThrows(IllegalArgumentException.class, () -> new PoolSnapshot(
                "crate", "season", List.of(COMMON), 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new PoolSnapshot(
                "crate", "season", List.of(COMMON), 3, -1));
        assertThrows(IllegalArgumentException.class, () -> new PoolSnapshot(
                "crate", "season", List.of(COMMON), 3, 1, 2));
        assertThrows(NullPointerException.class,
                () -> new RewardDefinition("reward", 1.0, null, "preview"));
    }

    private static RewardDefinition reward(String id, double weight) {
        return new RewardDefinition(id, weight, "deliver:" + id, "preview:" + id);
    }

    private static final class SequenceRandom implements RandomGenerator {
        private final double[] values;
        private int index;

        private SequenceRandom(double... values) {
            this.values = values;
        }

        @Override
        public double nextDouble() {
            return values[Math.min(index++, values.length - 1)];
        }

        @Override
        public long nextLong() {
            return 0L;
        }
    }
}
