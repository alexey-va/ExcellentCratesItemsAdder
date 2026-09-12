package ru.ruscrafting.ecia.roll;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeightedOfferGeneratorTest {
    private static final RewardDefinition COMMON = reward("common", 1.0, false);
    private static final RewardDefinition RARE = reward("rare", 1.0, true);

    @Test
    void capsOfferAtPoolSizeInsteadOfLoopingWhenPoolHasFewerThanThreeRewards() {
        PoolSnapshot pool = new PoolSnapshot("crate", "season", List.of(COMMON), 0, 3, 2);

        OfferSet offer = new WeightedOfferGenerator(new SequenceRandom(0.0)).generate(pool, 0);

        assertEquals(List.of(COMMON), offer.rewards());
        assertFalse(offer.guaranteed());
    }

    @Test
    void drawsAnOfferWithoutDuplicateRewardsAndCopiesInputs() {
        List<RewardDefinition> configured = new ArrayList<>(List.of(
                COMMON, RARE, reward("other", 1.0, false), reward("another", 1.0, false)
        ));
        PoolSnapshot pool = new PoolSnapshot("crate", "season", configured, 0, 3, 0);
        configured.clear();

        OfferSet offer = new WeightedOfferGenerator(new SequenceRandom(0.0)).generate(pool, 0);

        assertEquals(4, pool.rewards().size());
        assertEquals(3, offer.rewards().stream().map(RewardDefinition::id).distinct().count());
        assertThrows(UnsupportedOperationException.class, () -> offer.rewards().clear());
    }

    @Test
    void usesWeightedHalfOpenBoundaries() {
        RewardDefinition heavy = reward("heavy", 3.0, false);
        RewardDefinition light = reward("light", 1.0, false);
        PoolSnapshot pool = new PoolSnapshot("crate", "season", List.of(heavy, light), 0, 1, 0);

        assertEquals("heavy", new WeightedOfferGenerator(new SequenceRandom(0.0))
                .generate(pool, 0).rewards().getFirst().id());
        assertEquals("heavy", new WeightedOfferGenerator(new SequenceRandom(Math.nextDown(0.75)))
                .generate(pool, 0).rewards().getFirst().id());
        assertEquals("light", new WeightedOfferGenerator(new SequenceRandom(0.75))
                .generate(pool, 0).rewards().getFirst().id());
    }

    @Test
    void includesQualifyingRewardWhenPityIsDueAndKeepsItThroughReroll() {
        RewardDefinition other = reward("other", 1.0, false);
        RewardDefinition another = reward("another", 1.0, false);
        PoolSnapshot pool = new PoolSnapshot("crate", "season", List.of(COMMON, RARE, other, another), 3, 3, 1);
        WeightedOfferGenerator generator = new WeightedOfferGenerator(new SequenceRandom(0.0, 0.0, 0.0, 0.0));

        OfferSet initial = generator.generate(pool, 2);
        OfferSet rerolled = generator.reroll(pool, initial, 0);

        assertTrue(initial.guaranteed());
        assertTrue(initial.rewards().stream().anyMatch(RewardDefinition::guaranteeEligible));
        assertTrue(rerolled.guaranteed());
        assertTrue(rerolled.rewards().stream().anyMatch(RewardDefinition::guaranteeEligible));
    }

    @Test
    void pityChangesOnlyForTheRewardActuallyChosen() {
        PoolSnapshot pool = new PoolSnapshot("crate", "season", List.of(COMMON, RARE), 3, 3, 0);
        WeightedOfferGenerator generator = new WeightedOfferGenerator(new SequenceRandom(0.0));

        assertEquals(3, generator.nextUnsuccessfulOpenings(pool, 2, COMMON));
        assertEquals(0, generator.nextUnsuccessfulOpenings(pool, 2, RARE));
    }

    @Test
    void rejectsInvalidDefinitionsAndPoolConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> reward("", 1.0, false));
        assertThrows(IllegalArgumentException.class, () -> reward("bad", Double.NaN, false));
        assertThrows(IllegalArgumentException.class, () -> new PoolSnapshot(
                "crate", "season", List.of(COMMON, reward("common", 2.0, false)), 0, 3, 0));
        assertThrows(IllegalArgumentException.class, () -> new PoolSnapshot(
                "crate", "season", List.of(COMMON), 1, 3, 0));
        assertThrows(IllegalArgumentException.class, () -> new PoolSnapshot(
                "crate", "season", List.of(COMMON), 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new PoolSnapshot(
                "crate", "season", List.of(COMMON), 0, 3, -1));
        assertThrows(NullPointerException.class,
                () -> new RewardDefinition("reward", 1.0, false, null, "preview"));
    }

    private static RewardDefinition reward(String id, double weight, boolean eligible) {
        return new RewardDefinition(id, weight, eligible, "deliver:" + id, "preview:" + id);
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
