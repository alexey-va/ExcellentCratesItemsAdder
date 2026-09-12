package ru.ruscrafting.ecia.integration;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ManagedCratesSettingsTest {
    @Test void economyCountersCannotBeSilentlyRoundedOrReplacedWithDefaults() {
        var config = new YamlConfiguration();
        config.set("enabled", true);
        config.set("cases.case_daily.qualifying-rewards", List.of("rare"));
        config.set("cases.case_daily.choices", 2.9);
        assertThrows(IllegalArgumentException.class, () -> ManagedCratesSettings.read(config));
        config.set("cases.case_daily.choices", 3);
        config.set("cases.case_daily.rerolls", "unlimited");
        assertThrows(IllegalArgumentException.class, () -> ManagedCratesSettings.read(config));
        config.set("cases.case_daily.rerolls", 1);
        assertEquals(3, ManagedCratesSettings.read(config).cases().get("case_daily").choiceCount());
    }

    @Test void enabledCratesRequireDefinedGuaranteeAndDoNotIgnoreNonStringIds() {
        var config = new YamlConfiguration();
        config.set("enabled", true);
        config.createSection("cases.case_daily");
        assertThrows(IllegalArgumentException.class, () -> ManagedCratesSettings.read(config));
        config.set("cases.case_daily.qualifying-rewards", List.of("rare", 3));
        assertThrows(IllegalArgumentException.class, () -> ManagedCratesSettings.read(config));
    }
}
