package ru.ruscrafting.ecia;

import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.Location;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrateVisualSettingsStoreTest {
    @TempDir Path directory;

    @Test
    void onePlacedCrateRoundTripsWithoutChangingOtherAnchors() {
        MemoryConfiguration config = new MemoryConfiguration();
        CrateVisualSettingsStore store = new CrateVisualSettingsStore(directory, config);
        var edited = new CrateVisualSettingsStore.Anchor("survival", 10, 64, -3);
        var untouched = new CrateVisualSettingsStore.Anchor("survival", 11, 64, -3);
        var visuals = new CrateVisualSettingsStore.Visuals(
                new CrateVisualSettingsStore.Hologram("<gold>%crate_name%</gold>", .4, .7, -.2, 35f, -5f, 3.5f, 4f),
                new CrateVisualSettingsStore.Roulette(.2, 4.1, -.1, 1.1, 1.4f, 2f, 1.3, 2.2f, 32f, false),
                new CrateVisualSettingsStore.Ambient("SPIRAL", 6, 1.2, 1.7, .8f, 1.4, 28f)
        );

        store.save(edited, visuals);
        CrateVisualSettingsStore reloaded = new CrateVisualSettingsStore(directory, config);

        assertEquals(visuals, reloaded.get(edited));
        assertEquals(reloaded.defaults(), reloaded.get(untouched));
        assertEquals("%crate_name%", reloaded.defaults().hologram().textTemplate());
    }

    @Test
    void legacyVisualsFileReceivesNewAnimationAndAudienceDefaults() throws Exception {
        Files.writeString(directory.resolve("visuals.yml"), """
                anchors:
                  a0:
                    world: survival
                    x: 10
                    y: 64
                    z: -3
                    hologram:
                      text: legacy
                    roulette:
                      item-scale: 1.4
                """);
        MemoryConfiguration config = new MemoryConfiguration();
        config.set("case-roulette.visible-to-nearby", false);
        config.set("case-ambient.preset", "CROWN");

        var loaded = new CrateVisualSettingsStore(directory, config)
                .get(new CrateVisualSettingsStore.Anchor("survival", 10, 64, -3));

        assertEquals("legacy", loaded.hologram().textTemplate());
        assertEquals(1.4f, loaded.roulette().itemScale());
        assertFalse(loaded.roulette().visibleToNearby());
        assertEquals("CROWN", loaded.ambient().preset());
        assertEquals(8, loaded.ambient().itemCount());
    }

    @Test
    void defaultsMatchTheOriginDailyCrateVisuals() {
        var defaults = new CrateVisualSettingsStore(directory, new MemoryConfiguration()).defaults();

        assertEquals("%crate_name%", defaults.hologram().textTemplate());
        assertEquals(0.0, defaults.hologram().offsetX());
        assertEquals(.58, defaults.hologram().offsetY());
        assertEquals(0.0, defaults.hologram().offsetZ());
        assertEquals(0.0f, defaults.hologram().yaw());
        assertEquals(0.0f, defaults.hologram().pitch());
        assertEquals(2.0f, defaults.hologram().scale());
        assertEquals(1.0f, defaults.hologram().viewRange());
        assertEquals(0.0, defaults.roulette().offsetX());
        assertEquals(2.65, defaults.roulette().offsetY());
        assertEquals(0.0, defaults.roulette().offsetZ());
        assertEquals(.82, defaults.roulette().itemSpacing());
        assertEquals(.95f, defaults.roulette().itemScale());
        assertEquals(1.32f, defaults.roulette().winnerScale());
        assertEquals(1.08, defaults.roulette().pointerHeight());
        assertEquals(1.55f, defaults.roulette().pointerScale());
        assertEquals(24.0f, defaults.roulette().viewRange());
        assertTrue(defaults.roulette().visibleToNearby());
        assertEquals("SATURN", defaults.ambient().preset());
        assertEquals(8, defaults.ambient().itemCount());
        assertEquals(1.6, defaults.ambient().radius());
        assertEquals(.5, defaults.ambient().height());
        assertEquals(.5f, defaults.ambient().itemScale());
        assertEquals(2.0, defaults.ambient().speed());
        assertEquals(20.0f, defaults.ambient().viewRange());
    }

    @Test
    void everyShippedAmbientPresetSurvivesPersistence() {
        for (String preset : java.util.Set.of(
                "FOUNTAIN", "HALO", "CROWN", "SPIRAL", "PULSE",
                "WHEEL", "SWING", "INFINITY", "SATURN", "CAROUSEL",
                "COMET", "BLOOM", "HELIX", "TIDE", "CLOCKWORK",
                "SHOWCASE", "REELS", "WALL", "CONVEYOR", "RAIN", "TOWER", "FAN", "SCALES")) {
            MemoryConfiguration config = new MemoryConfiguration();
            config.set("case-ambient.preset", preset);

            assertEquals(preset, new CrateVisualSettingsStore(directory.resolve(preset), config)
                    .defaults().ambient().preset());
        }
    }

    @Test
    void frozenLegacyDefaultsMigrateWithoutChangingCustomGeometry() throws Exception {
        Files.writeString(directory.resolve("visuals.yml"), """
                anchors:
                  a0:
                    world: survival
                    x: 10
                    y: 64
                    z: -3
                    roulette:
                      offset-y: 3.65
                    ambient:
                      radius: 0.9
                  a1:
                    world: survival
                    x: 11
                    y: 64
                    z: -3
                    roulette:
                      offset-y: 2.0
                    ambient:
                      radius: 2.0
                """);

        var store = new CrateVisualSettingsStore(directory, new MemoryConfiguration());
        var migrated = store.get(new CrateVisualSettingsStore.Anchor("survival", 10, 64, -3));
        var custom = store.get(new CrateVisualSettingsStore.Anchor("survival", 11, 64, -3));

        assertEquals(2.65, migrated.roulette().offsetY());
        assertEquals(1.6, migrated.ambient().radius());
        assertEquals(2.0, custom.roulette().offsetY());
        assertEquals(2.0, custom.ambient().radius());
    }

    @Test
    void fixedHologramSelectsOneReadableFaceForEachSide() {
        Location display = new Location(null, 0, 0, 0, 0f, 0f);

        assertTrue(CrateHologramService.frontFaces(display, new Location(null, 0, 0, 4)));
        assertFalse(CrateHologramService.frontFaces(display, new Location(null, 0, 0, -4)));
    }

    @Test
    void hologramTemplateInsertsTheConfiguredCrateName() {
        assertEquals("<gold>Легендарный кейс</gold>",
                CrateHologramService.renderText("<gold>%crate_name%</gold>", "Легендарный кейс"));
    }
}
