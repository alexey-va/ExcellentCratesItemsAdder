package ru.ruscrafting.ecia.integration;

import org.junit.jupiter.api.Test;
import ru.ruscrafting.ecia.roll.PoolSnapshot;
import ru.ruscrafting.ecia.roll.RewardDefinition;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeRewardPoolsFilteringTest {
    @Test
    void filtersAddedRemovedAndChangedRewardsAndReportsEachDrift() {
        PoolSnapshot frozen = new PoolSnapshot("case", "default", List.of(
                reward("keep", "same"),
                reward("removed", "old"),
                reward("changed", "old")
        ), 3, 1, 1);
        List<NativeRewardPools.NativeRewardFingerprint> nativeRewards = List.of(
                new NativeRewardPools.NativeRewardFingerprint("keep", "same"),
                new NativeRewardPools.NativeRewardFingerprint("added", "new"),
                new NativeRewardPools.NativeRewardFingerprint("changed", "new")
        );
        List<String> issues = new ArrayList<>();

        PoolSnapshot filtered = NativeRewardPools.filterFrozenPool(
                frozen, nativeRewards, RewardDefinition::deliveryPayload, issues::add).orElseThrow();

        assertEquals(List.of("keep"), filtered.rewards().stream().map(RewardDefinition::id).toList());
        assertTrue(issues.stream().anyMatch(issue -> issue.contains("added") && issue.contains("missing frozen reward")));
        assertTrue(issues.stream().anyMatch(issue -> issue.contains("removed") && issue.contains("missing native reward")));
        assertTrue(issues.stream().anyMatch(issue -> issue.contains("changed") && issue.contains("fingerprint changed")));
    }

    @Test
    void disablesOnlyTheCaseWhenNoFrozenRewardRemainsUsable() {
        PoolSnapshot frozen = new PoolSnapshot("case", "default", List.of(
                reward("removed", "old")
        ), 1, 0, 1);
        List<String> issues = new ArrayList<>();

        var filtered = NativeRewardPools.filterFrozenPool(
                frozen,
                List.of(new NativeRewardPools.NativeRewardFingerprint("added", "new")),
                RewardDefinition::deliveryPayload,
                issues::add);

        assertFalse(filtered.isPresent());
        assertTrue(issues.stream().anyMatch(issue -> issue.contains("no valid rewards remain")));
    }

    private static RewardDefinition reward(String id, String fingerprint) {
        return new RewardDefinition(id, 1.0, fingerprint, "preview:" + fingerprint);
    }
}
