package ru.ruscrafting.ecia;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrateAmbientEffectServiceTest {
    private static final Set<String> PRESETS = Set.of(
            "FOUNTAIN", "HALO", "CROWN", "SPIRAL", "PULSE",
            "WHEEL", "SWING", "INFINITY", "SATURN", "CAROUSEL",
            "COMET", "BLOOM", "HELIX", "TIDE", "CLOCKWORK",
            "SHOWCASE", "REELS", "WALL", "CONVEYOR", "RAIN", "TOWER", "FAN", "SCALES"
    );

    @Test
    void everyPresetKeepsActualPreviewOutsideTheChest() {
        for (String preset : PRESETS) {
            var visual = new CrateVisualSettingsStore.Ambient(preset, 5, 1.35, 1.05, .7f, 1, 20f);
            var frames = IntStream.range(0, 50)
                    .mapToObj(frame -> CrateAmbientEffectService.frame(frame, 0, visual, 7))
                    .toList();

            assertTrue(frames.stream().mapToDouble(CrateAmbientEffectService.Frame::scale).max().orElseThrow() > .5,
                    preset + " should visibly grow");
            assertTrue(frames.stream().noneMatch(frame ->
                            Math.hypot(frame.x(), frame.z()) < 1.0 && frame.y() < .9),
                    preset + " should never enter the chest volume");
        }
    }

    @Test
    void fortuneWheelIsAVerticalRingThatKeepsSpinning() {
        var visual = new CrateVisualSettingsStore.Ambient("WHEEL", 6, 1.35, 1.05, .7f, 1, 20f);
        var start = CrateAmbientEffectService.frame(0, 0, visual, 7);
        var later = CrateAmbientEffectService.frame(20, 0, visual, 7);

        assertTrue(Math.abs(start.z()) >= visual.radius() * .8, "wheel should stand outside the chest");
        assertTrue(Math.abs(start.x() - later.x()) > .2, "wheel should keep rotating while idle");
        assertTrue(Math.abs(start.y() - later.y()) > .2, "wheel should rotate in a vertical plane");
    }

    @Test
    void swingingFortuneWheelReversesInsteadOfLooping() {
        var visual = new CrateVisualSettingsStore.Ambient("SWING", 6, 1.35, 1.05, .7f, 1, 20f);
        var start = CrateAmbientEffectService.frame(0, 0, visual, 7);
        var edge = CrateAmbientEffectService.frame(35, 0, visual, 7);
        var returned = CrateAmbientEffectService.frame(70, 0, visual, 7);

        assertTrue(Math.abs(start.x() - edge.x()) > .5, "swing should travel toward an edge");
        assertTrue(Math.abs(start.x() - returned.x()) < .05, "swing should return after reversing");
    }

    @Test
    void shippedPresetsHaveDistinctTrajectories() {
        Set<String> signatures = PRESETS.stream().map(preset -> {
            var visual = new CrateVisualSettingsStore.Ambient(preset, 6, 1.35, 1.05, .7f, 1, 20f);
            return IntStream.of(0, 11, 23)
                    .mapToObj(frame -> CrateAmbientEffectService.frame(frame, frame % 2, visual, 7))
                    .map(point -> "%.3f,%.3f,%.3f".formatted(point.x(), point.y(), point.z()))
                    .collect(Collectors.joining(";"));
        }).collect(Collectors.toSet());

        assertEquals(PRESETS.size(), signatures.size(), "every menu choice should have its own motion");
    }

    @Test
    void previewSelectionCyclesDeterministicallyAcrossThePool() {
        var visual = new CrateVisualSettingsStore.Ambient("FOUNTAIN", 5, .9, 1.05, .7f, 1, 20f);
        Set<Integer> shown = IntStream.range(0, 350)
                .map(frame -> CrateAmbientEffectService.frame(frame, 0, visual, 7).rewardIndex())
                .boxed().collect(java.util.stream.Collectors.toSet());

        assertEquals(Set.of(0, 1, 2, 3, 4, 5, 6), shown);
    }

    @Test
    void showcasePresentsOneLargeRewardAtATime() {
        var visual = new CrateVisualSettingsStore.Ambient("SHOWCASE", 8, 1.35, 1.05, .7f, 1, 20f);
        long visible = IntStream.range(0, visual.itemCount())
                .mapToObj(slot -> CrateAmbientEffectService.frame(12, slot, visual, 8))
                .filter(point -> point.scale() > .2f)
                .count();

        assertEquals(1, visible);
    }

    @Test
    void reelsFormThreeVerticalColumnsInsteadOfAnOrbit() {
        var visual = new CrateVisualSettingsStore.Ambient("REELS", 8, 1.35, 1.05, .7f, 1, 20f);
        var points = IntStream.range(0, visual.itemCount())
                .mapToObj(slot -> CrateAmbientEffectService.frame(17, slot, visual, 8))
                .toList();

        assertEquals(3, points.stream().map(point -> Math.round(point.x() * 100)).collect(Collectors.toSet()).size());
        assertEquals(1, points.stream().map(point -> Math.round(point.z() * 100)).collect(Collectors.toSet()).size());
        double yRange = points.stream().mapToDouble(CrateAmbientEffectService.Frame::y).max().orElseThrow()
                - points.stream().mapToDouble(CrateAmbientEffectService.Frame::y).min().orElseThrow();
        assertTrue(yRange > .5, "reels should visibly travel vertically");
    }

    @Test
    void towerIsAStackAboveTheCrateInsteadOfAHorizontalLoop() {
        var visual = new CrateVisualSettingsStore.Ambient("TOWER", 8, 1.35, 1.05, .7f, 1, 20f);
        var points = IntStream.range(0, visual.itemCount())
                .mapToObj(slot -> CrateAmbientEffectService.frame(17, slot, visual, 8))
                .toList();

        double xRange = points.stream().mapToDouble(CrateAmbientEffectService.Frame::x).max().orElseThrow()
                - points.stream().mapToDouble(CrateAmbientEffectService.Frame::x).min().orElseThrow();
        double yRange = points.stream().mapToDouble(CrateAmbientEffectService.Frame::y).max().orElseThrow()
                - points.stream().mapToDouble(CrateAmbientEffectService.Frame::y).min().orElseThrow();
        assertTrue(xRange < .4, "tower should remain narrow");
        assertTrue(yRange > 1.0, "tower should read as a vertical stack");
    }

    @Test
    void idleMotionSupportsHalfFrameSamplesForSmoothRotationAndHeight() {
        var visual = new CrateVisualSettingsStore.Ambient("SATURN", 8, 1.6, .5, .5f, 2, 20f);
        var start = CrateAmbientEffectService.frame(0.0, 0, visual, 8);
        var middle = CrateAmbientEffectService.frame(0.5, 0, visual, 8);
        var next = CrateAmbientEffectService.frame(1.0, 0, visual, 8);

        assertTrue(Math.abs(middle.rotation() - start.rotation()) > 1.0E-4,
                "rotation should advance between animation updates");
        assertTrue(Math.abs(middle.rotation() - start.rotation())
                        < Math.abs(next.rotation() - start.rotation()),
                "half-frame rotation should stay between full-frame samples");
        assertTrue(Math.abs(middle.y() - start.y()) > 1.0E-4,
                "vertical motion should advance between animation updates");
        assertTrue(Math.abs(middle.y() - start.y()) < Math.abs(next.y() - start.y()),
                "half-frame height should stay between full-frame samples");
    }

    @Test
    void saturnRotationRemainsContinuousAtPreviewCycleBoundary() {
        var visual = new CrateVisualSettingsStore.Ambient("SATURN", 8, 1.6, .5, .5f, 2, 20f);
        double cycle = Math.round(50.0 / visual.speed());
        var before = CrateAmbientEffectService.frame(cycle - .5, 0, visual, 8);
        var boundary = CrateAmbientEffectService.frame(cycle, 0, visual, 8);
        var after = CrateAmbientEffectService.frame(cycle + .5, 0, visual, 8);

        assertTrue(Math.abs(boundary.rotation() - before.rotation()) < .2,
                "rotation must not jump when the preview item cycle restarts");
        assertTrue(Math.abs(after.rotation() - boundary.rotation()) < .2,
                "rotation must continue smoothly after the preview item cycle");
    }
}
