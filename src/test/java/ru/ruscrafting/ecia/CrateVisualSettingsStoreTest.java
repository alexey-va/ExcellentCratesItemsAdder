package ru.ruscrafting.ecia;

import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.Location;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

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
                new CrateVisualSettingsStore.Roulette(.2, 4.1, -.1, 1.1, 1.4f, 2f, 1.3, 2.2f, 32f)
        );

        store.save(edited, visuals);
        CrateVisualSettingsStore reloaded = new CrateVisualSettingsStore(directory, config);

        assertEquals(visuals, reloaded.get(edited));
        assertEquals(reloaded.defaults(), reloaded.get(untouched));
        assertEquals("%crate_name%", reloaded.defaults().hologram().textTemplate());
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
