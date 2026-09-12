package ru.ruscrafting.ecia.integration

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NativeRewardPoolsTest {
    @Test
    fun acceptsExcellentCratesNormalizedPlayerPlaceholder() {
        assertTrue(NativeRewardPools.isArcRewardIssue("arc-reward-issue %player_name% case_mounts blaze".split(" ").toTypedArray()))
        assertTrue(NativeRewardPools.isArcRewardIssue("arc-reward-issue %player% case_mounts blaze".split(" ").toTypedArray()))
        assertFalse(NativeRewardPools.isArcRewardIssue("arc-reward-issue player case_mounts blaze".split(" ").toTypedArray()))
    }
}
