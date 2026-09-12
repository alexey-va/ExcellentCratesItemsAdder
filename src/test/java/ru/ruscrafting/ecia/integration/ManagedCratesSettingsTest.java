package ru.ruscrafting.ecia.integration;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ManagedCratesSettingsTest {
    @Test void economyCountersCannotBeSilentlyRoundedOrReplacedWithDefaults() {
        var config = new YamlConfiguration();
        config.set("enabled", true);
        config.set("cases.case_daily.choices", 2.9);
        assertThrows(IllegalArgumentException.class, () -> ManagedCratesSettings.read(config));
        config.set("cases.case_daily.choices", 3);
        config.set("cases.case_daily.rerolls", "unlimited");
        assertThrows(IllegalArgumentException.class, () -> ManagedCratesSettings.read(config));
        config.set("cases.case_daily.rerolls", 1);
        assertEquals(3, ManagedCratesSettings.read(config).cases().get("case_daily").choiceCount());
    }

    @Test void enabledCratesUseBoundedChoiceAndRerollDefaults() {
        var config = new YamlConfiguration();
        config.set("enabled", true);
        config.createSection("cases.case_daily");
        var settings = ManagedCratesSettings.read(config).cases().get("case_daily");
        assertEquals(3, settings.choiceCount());
        assertEquals(1, settings.maxRerolls());
    }
}
