package ru.ruscrafting.ecia;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrateAmbientEffectServiceTest {
    @Test
    void everyPresetGrowsActualPreviewAwayFromTheChest() {
        for (String preset : Set.of("FOUNTAIN", "HALO", "CROWN", "SPIRAL", "PULSE")) {
            var visual = new CrateVisualSettingsStore.Ambient(preset, 5, .9, 1.05, .7f, 1, 20f);
            var frames = IntStream.range(0, 50)
                    .mapToObj(frame -> CrateAmbientEffectService.frame(frame, 0, visual, 7))
                    .toList();

            assertTrue(frames.stream().mapToDouble(CrateAmbientEffectService.Frame::scale).max().orElseThrow() > .5,
                    preset + " should visibly grow");
            assertTrue(frames.stream().mapToDouble(frame -> Math.hypot(frame.x(), frame.z())).max().orElseThrow() > .5,
                    preset + " should leave the chest center");
        }
    }

    @Test
    void previewSelectionCyclesDeterministicallyAcrossThePool() {
        var visual = new CrateVisualSettingsStore.Ambient("FOUNTAIN", 5, .9, 1.05, .7f, 1, 20f);
        Set<Integer> shown = IntStream.range(0, 350)
                .map(frame -> CrateAmbientEffectService.frame(frame, 0, visual, 7).rewardIndex())
                .boxed().collect(java.util.stream.Collectors.toSet());

        assertEquals(Set.of(0, 1, 2, 3, 4, 5, 6), shown);
    }
}
