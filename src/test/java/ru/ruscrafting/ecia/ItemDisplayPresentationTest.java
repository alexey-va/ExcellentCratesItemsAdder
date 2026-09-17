package ru.ruscrafting.ecia;

import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.entity.ItemDisplay;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ItemDisplayPresentationTest {
    @Test
    void rouletteFlatPresentationIsTheDefaultAndCompressesModelDepth() {
        var config = new MemoryConfiguration();

        var presentation = ItemDisplayPresentation.from(config, "case-roulette.flat-item-displays", true);

        assertEquals(ItemDisplay.ItemDisplayTransform.FIXED, presentation.transform());
        assertEquals(1.0f, presentation.scale(1.0f).x());
        assertEquals(1.0f, presentation.scale(1.0f).y());
        assertEquals(.06f, presentation.scale(1.0f).z());
    }

    @Test
    void ambientPresentationDefaultsToThePreviousThreeDimensionalModel() {
        var config = new MemoryConfiguration();

        var presentation = ItemDisplayPresentation.from(config, "case-ambient.flat-item-displays", false);

        assertEquals(ItemDisplay.ItemDisplayTransform.GUI, presentation.transform());
        assertEquals(1.0f, presentation.scale(1.0f).z());
    }

    @Test
    void disabledFlatPresentationRestoresThePreviousGuiModel() {
        var config = new MemoryConfiguration();
        config.set("case-roulette.flat-item-displays", false);

        var presentation = ItemDisplayPresentation.from(config, "case-roulette.flat-item-displays");

        assertEquals(ItemDisplay.ItemDisplayTransform.GUI, presentation.transform());
        assertEquals(1.4f, presentation.scale(1.4f).x());
        assertEquals(1.4f, presentation.scale(1.4f).y());
        assertEquals(1.4f, presentation.scale(1.4f).z());
    }
}
