package ru.ruscrafting.ecia;

import org.junit.jupiter.api.Test;
import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;
import ru.arc.paper.api.ArcSceneItem;

import java.util.Collections;
import java.util.List;
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

    @Test
    void publicTimelineUsesTheNativePoseForEveryPresetAndNeverLoopsItsForwardWindow() {
        for (String preset : PRESETS) {
            var orbit = sceneOrbit(preset, 2);
            var timeline = CrateAmbientEffectService.sceneTimeline(orbit, 0, 24.5, 1_000);
            assertEquals(21, timeline.getSamples().size());
            assertEquals(100, timeline.getSamplePeriodMillis());
            assertEquals(24.5, timeline.getCapturedFrame());
            for (int index = 0; index <= 20; index++) {
                var nativeFrame = CrateAmbientEffectService.frame(24.5 + index, 0, orbit.visual(), orbit.rewards().size());
                var pose = timeline.getSamples().get(index).getPose();
                assertEquals(8.5 + nativeFrame.x(), pose.getX(), 1.0E-9, preset);
                assertEquals(64.45 + nativeFrame.y(), pose.getY(), 1.0E-9, preset);
                assertEquals(8.5 + nativeFrame.z(), pose.getZ(), 1.0E-9, preset);
                assertEquals(nativeFrame.scale(), pose.getScale().getX(), 1.0E-6, preset);
                assertEquals(nativeFrame.scale(), pose.getScale().getZ(), 1.0E-6, preset);
                assertEquals(Math.sin(nativeFrame.rotation() / 2.0), pose.getLeftRotation().getZ(), 1.0E-6, preset);
            }
        }
        var orbit = sceneOrbit("SATURN", 2);
        var timeline = CrateAmbientEffectService.sceneTimeline(orbit, 0, 10.0, 1_000);
        var first = timeline.getSamples().getFirst().getPose();
        var last = timeline.getSamples().getLast().getPose();
        assertTrue(Math.abs(first.getX() - last.getX()) > .2, "prediction endpoints must not be forced into a fake loop");
        var midpoint = CrateAmbientEffectService.frame(10.5, 0, orbit.visual(), orbit.rewards().size());
        var second = timeline.getSamples().get(1).getPose();
        assertEquals(8.5 + midpoint.x(), (first.getX() + second.getX()) / 2.0, .002);
        assertEquals(64.45 + midpoint.y(), (first.getY() + second.getY()) / 2.0, .002);
        assertEquals(8.5 + midpoint.z(), (first.getZ() + second.getZ()) / 2.0, .002);
    }

    @Test
    void publicTimelinePreservesNativeHalfFrameRewardSwitchesBetweenPoseSamples() {
        var orbit = sceneOrbit("SATURN", 2);
        var timeline = CrateAmbientEffectService.sceneTimeline(orbit, 0, 24.5, 1_000);
        assertEquals(0, timeline.getItemSwitches().getFirst().getOffsetMillis());
        assertEquals(50, timeline.getItemSwitches().get(1).getOffsetMillis());
        for (int tick = 0; tick <= 40; tick++) {
            int offset = tick * 50;
            var latest = timeline.getItemSwitches().stream().filter(change -> change.getOffsetMillis() <= offset)
                    .reduce((before, after) -> after).orElseThrow();
            int shown = timeline.getItems().get(latest.getItemIndex()).getCustomModelData();
            assertEquals(CrateAmbientEffectService.frame(24.5 + tick * .5, 0, orbit.visual(), orbit.rewards().size()).rewardIndex(), shown);
        }
        assertTrue(timeline.getItemSwitches().size() <= 8);
        var flat = sceneOrbit("SATURN", 2);
        flat = new CrateAmbientEffectService.Orbit(flat.anchor(), flat.displays(), flat.rewards(), flat.appearances(),
                flat.rewardIndices(), flat.visual(), ItemDisplayPresentation.FLAT);
        var pose = CrateAmbientEffectService.sceneTimeline(flat, 0, 10.0, 1_000).getSamples().getFirst().getPose();
        assertEquals(pose.getScale().getX() * .06f, pose.getScale().getZ(), 1.0E-6);
    }

    private static CrateAmbientEffectService.Orbit sceneOrbit(String preset, double speed) {
        List<ArcSceneItem> items = IntStream.range(0, 7)
                .mapToObj(index -> new ArcSceneItem("minecraft:paper", 1, index, null, "ruscrafting:crate_reward_" + index)).toList();
        return new CrateAmbientEffectService.Orbit(new Location(null, 8, 64, 8), List.of(),
                Collections.nCopies(7, (ItemStack) null), items, new int[8],
                new CrateVisualSettingsStore.Ambient(preset, 8, 1.6, .5, .5f, speed, 20f), ItemDisplayPresentation.THREE_DIMENSIONAL);
    }
}
