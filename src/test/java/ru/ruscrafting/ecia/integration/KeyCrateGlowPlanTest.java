package ru.ruscrafting.ecia.integration;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KeyCrateGlowPlanTest {
    private static final KeyCrateGlowPlan.Target DAILY =
            new KeyCrateGlowPlan.Target("daily", "daily_key", "autumn", "rc_origin_spawn", -50, 71, 28);
    private static final KeyCrateGlowPlan.Target WEEKLY =
            new KeyCrateGlowPlan.Target("weekly", "weekly_key", "autumn", "rc_origin_spawn", -20, 71, 28);

    @Test
    void selectsOnlyMatchingCurrentSeasonInSameWorldAndRange() {
        var selected = KeyCrateGlowPlan.select(
                new KeyCrateGlowPlan.HeldKey("daily_key", "autumn"),
                "rc_origin_spawn", -48.5, 72.0, 28.5, 48.0,
                List.of(DAILY, WEEKLY,
                        new KeyCrateGlowPlan.Target("far", "daily_key", "autumn", "rc_origin_spawn", 50, 71, 28),
                        new KeyCrateGlowPlan.Target("other-world", "daily_key", "autumn", "survival", -50, 71, 28)));

        assertEquals(List.of(DAILY), selected);
    }

    @Test
    void rejectsOldSeasonAndBoundaryOutsideRadius() {
        assertEquals(List.of(), KeyCrateGlowPlan.select(
                new KeyCrateGlowPlan.HeldKey("daily_key", "launch"),
                "rc_origin_spawn", -50.0, 71.0, 28.0, 48.0, List.of(DAILY)));
        assertEquals(List.of(), KeyCrateGlowPlan.select(
                new KeyCrateGlowPlan.HeldKey("daily_key", "autumn"),
                "rc_origin_spawn", -1.4, 71.5, 28.5, 48.0, List.of(DAILY)));
    }

    @Test
    void keepsStableTargetOrder() {
        var near = new KeyCrateGlowPlan.Target("near", "daily_key", "autumn", "rc_origin_spawn", -49, 71, 28);
        assertEquals(List.of(DAILY, near), KeyCrateGlowPlan.select(
                new KeyCrateGlowPlan.HeldKey("daily_key", "autumn"),
                "rc_origin_spawn", -49.0, 71.0, 28.0, 48.0, List.of(DAILY, WEEKLY, near)));
    }
}
