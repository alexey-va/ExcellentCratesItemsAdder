package ru.ruscrafting.ecia.integration;

import org.bukkit.configuration.ConfigurationSection;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.time.DateTimeException;
import java.time.ZoneId;

/** Validated feature configuration, separate from native EC crate definitions. */
public record ManagedCratesSettings(boolean enabled, Map<String, CaseSettings> cases, ZoneId freeOpeningZone) {
    public ManagedCratesSettings {
        cases = Map.copyOf(cases);
        Objects.requireNonNull(freeOpeningZone);
    }

    public ManagedCratesSettings(boolean enabled, Map<String, CaseSettings> cases) {
        this(enabled, cases, ZoneId.of("Europe/Moscow"));
    }

    public record CaseSettings(String crateId,
            int choiceCount, int maxRerolls, int bundleSize, String furnitureId,
            PeriodicVirtualOpening.Period freeOpenPeriod) {
        public CaseSettings {
            requireId(crateId);
            Objects.requireNonNull(furnitureId);
            Objects.requireNonNull(freeOpenPeriod);
            if (choiceCount < 1 || choiceCount > 3 || maxRerolls < 0 || maxRerolls > 5
                    || bundleSize < 1 || bundleSize > 5) {
                throw new IllegalArgumentException("Invalid managed case rules: " + crateId);
            }
        }

        public CaseSettings(String crateId, int choiceCount, int maxRerolls, int bundleSize, String furnitureId) {
            this(crateId, choiceCount, maxRerolls, bundleSize, furnitureId, PeriodicVirtualOpening.Period.NONE);
        }
    }

    public static ManagedCratesSettings read(ConfigurationSection config) {
        return read(config, ignored -> { });
    }

    public static ManagedCratesSettings read(ConfigurationSection config, Consumer<String> warning) {
        Objects.requireNonNull(warning);
        var section = config.getConfigurationSection("cases");
        Map<String, CaseSettings> cases = new LinkedHashMap<>();
        if (section != null) {
            for (String id : section.getKeys(false)) {
                var item = section.getConfigurationSection(id);
                if (item == null) throw new IllegalArgumentException("Invalid case configuration: " + id);
                PeriodicVirtualOpening.Period period;
                try {
                    period = PeriodicVirtualOpening.Period.parse(item.getString("free-open-period"), id);
                } catch (IllegalArgumentException failure) {
                    warning.accept("Invalid free-open-period for " + id + "; free opening is disabled for this case");
                    period = PeriodicVirtualOpening.Period.NONE;
                }
                cases.put(id, new CaseSettings(id,
                        integer(item, "choices", 3), integer(item, "rerolls", 1), integer(item, "bundle-size", 1),
                        item.getString("furniture", ""), period));
            }
        }
        if (config.contains("enabled") && !(config.get("enabled") instanceof Boolean)) {
            throw new IllegalArgumentException("enabled must be a boolean");
        }
        boolean enabled = config.getBoolean("enabled", false);
        if (enabled && cases.isEmpty()) throw new IllegalArgumentException("Managed openings enabled without cases");
        ZoneId zone;
        try {
            zone = ZoneId.of(config.getString("free-open-time-zone", "Europe/Moscow"));
        } catch (DateTimeException failure) {
            warning.accept("Invalid free-open-time-zone; using Europe/Moscow");
            zone = ZoneId.of("Europe/Moscow");
        }
        return new ManagedCratesSettings(enabled, cases, zone);
    }

    private static void requireId(String value) {
        if (value == null || value.length() > 128 || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]*")) {
            throw new IllegalArgumentException("Invalid case or season identifier");
        }
    }

    private static int integer(ConfigurationSection section, String key, int fallback) {
        Object value = section.get(key);
        if (value == null) return fallback;
        if (!(value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte)) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
        return Math.toIntExact(((Number) value).longValue());
    }
}
