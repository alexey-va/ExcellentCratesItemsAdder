package ru.ruscrafting.ecia.integration;

import org.junit.jupiter.api.Test;
import ru.ruscrafting.ecia.roll.PoolSnapshot;
import ru.ruscrafting.ecia.roll.RewardDefinition;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NativeRewardPoolsFilteringTest {
    @Test
    void currentPoolUsesTheNativeRewardListInsteadOfAnArchivedSeason() {
        List<RewardDefinition> current = List.of(
                reward("keep", "current"),
                reward("new", "current")
        );

        PoolSnapshot pool = NativeRewardPools.currentPool("case", current, 1, 0, 1);

        assertEquals("current", pool.seasonId());
        assertEquals(current, pool.rewards());
    }

    @Test
    void currentPoolKeepsTheConfiguredBundleBound() {
        PoolSnapshot pool = NativeRewardPools.currentPool(
                "case", List.of(reward("one", "current"), reward("two", "current")), 3, 1, 1);

        assertEquals(3, pool.choiceCount());
        assertEquals(1, pool.maxRerolls());
        assertEquals(1, pool.bundleSize());
    }

    private static RewardDefinition reward(String id, String fingerprint) {
        return new RewardDefinition(id, 1.0, fingerprint, "preview:" + fingerprint);
    }
}
