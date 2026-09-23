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
        config.set("cases.case_daily.bundle-size", 3);
        assertEquals(3, ManagedCratesSettings.read(config).cases().get("case_daily").choiceCount());
        assertEquals(3, ManagedCratesSettings.read(config).cases().get("case_daily").bundleSize());
    }

    @Test void enabledCratesUseBoundedChoiceAndRerollDefaults() {
        var config = new YamlConfiguration();
        config.set("enabled", true);
        config.createSection("cases.case_daily");
        var settings = ManagedCratesSettings.read(config).cases().get("case_daily");
        assertEquals(3, settings.choiceCount());
        assertEquals(1, settings.maxRerolls());
        assertEquals(1, settings.bundleSize());
    }

    @Test void freeOpeningCadenceAndTimeZoneAreExplicitAndInvalidCadenceFailsClosed() {
        var config = new YamlConfiguration();
        config.set("enabled", true);
        config.set("free-open-time-zone", "Europe/Moscow");
        config.set("cases.case_daily.free-open-period", "daily");
        config.set("cases.case_weekly.free-open-period", "weekly");
        var settings = ManagedCratesSettings.read(config);
        assertEquals("Europe/Moscow", settings.freeOpeningZone().getId());
        assertEquals(PeriodicVirtualOpening.Period.DAILY,
                settings.cases().get("case_daily").freeOpenPeriod());
        assertEquals(PeriodicVirtualOpening.Period.WEEKLY,
                settings.cases().get("case_weekly").freeOpenPeriod());

        config.set("cases.case_daily.free-open-period", "monthly");
        var warnings = new java.util.ArrayList<String>();
        settings = ManagedCratesSettings.read(config, warnings::add);
        assertEquals(PeriodicVirtualOpening.Period.NONE,
                settings.cases().get("case_daily").freeOpenPeriod());
        assertEquals(1, warnings.size());
    }
}
