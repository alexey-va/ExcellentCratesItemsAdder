package ru.ruscrafting.ecia.integration;

import org.junit.jupiter.api.Test;
import org.bukkit.entity.Display;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

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
    void matchesTheNativeKeyIdRegardlessOfLegacySeasonMetadata() {
        assertEquals(List.of(DAILY), KeyCrateGlowPlan.select(
                new KeyCrateGlowPlan.HeldKey("daily_key", "launch"),
                "rc_origin_spawn", -50.0, 71.0, 28.0, 48.0, List.of(DAILY)));
    }

    @Test
    void rejectsBoundaryOutsideRadius() {
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

    @Test
    void furnitureGlowInflatesEveryScaleAxisAroundTheExistingCenter() {
        var source = new Transformation(
                new Vector3f(.25F, -.5F, .75F),
                new Quaternionf().rotateY(.4F),
                new Vector3f(.8F, 1.2F, 1.6F),
                new Quaternionf().rotateX(-.2F));

        Transformation glow = KeyCrateGlowService.inflateCentered(source);

        assertEquals(source.getTranslation(), glow.getTranslation());
        assertEquals(.808F, glow.getScale().x, 1.0E-6F);
        assertEquals(1.212F, glow.getScale().y, 1.0E-6F);
        assertEquals(1.616F, glow.getScale().z, 1.0E-6F);
        assertEquals(source.getLeftRotation(), glow.getLeftRotation());
        assertEquals(source.getRightRotation(), glow.getRightRotation());
    }

    @Test
    void blockGlowExtendsEquallyPastAllSixFaces() {
        Transformation glow = KeyCrateGlowService.blockShellTransformation();

        assertEquals(-.005F, glow.getTranslation().x, 1.0E-6F);
        assertEquals(-.005F, glow.getTranslation().y, 1.0E-6F);
        assertEquals(-.005F, glow.getTranslation().z, 1.0E-6F);
        assertEquals(1.01F, glow.getScale().x, 1.0E-6F);
        assertEquals(1.01F, glow.getScale().y, 1.0E-6F);
        assertEquals(1.01F, glow.getScale().z, 1.0E-6F);
        assertEquals(1.005F, glow.getTranslation().x + glow.getScale().x, 1.0E-6F);
        assertEquals(1.005F, glow.getTranslation().y + glow.getScale().y, 1.0E-6F);
        assertEquals(1.005F, glow.getTranslation().z + glow.getScale().z, 1.0E-6F);
    }

    @Test
    void glowShellUsesFullBrightnessInsteadOfAmbientBlockLight() {
        Display.Brightness brightness = KeyCrateGlowService.glowBrightness();

        assertEquals(15, brightness.getBlockLight());
        assertEquals(15, brightness.getSkyLight());
    }
}
