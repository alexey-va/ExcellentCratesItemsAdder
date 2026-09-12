package ru.ruscrafting.ecia.integration;

import org.bukkit.configuration.ConfigurationSection;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Validated feature configuration, separate from native EC crate definitions. */
public record ManagedCratesSettings(boolean enabled, Map<String, CaseSettings> cases) {
    public ManagedCratesSettings { cases = Map.copyOf(cases); }

    public record CaseSettings(String crateId, String seasonId, int pityThreshold,
            int choiceCount, int maxRerolls, List<String> qualifyingRewards, String furnitureId) {
        public CaseSettings {
            requireId(crateId);
            requireId(seasonId);
            qualifyingRewards = List.copyOf(qualifyingRewards);
            qualifyingRewards.forEach(ManagedCratesSettings::requireId);
            Objects.requireNonNull(furnitureId);
            if (pityThreshold < 0 || pityThreshold > 10_000 || choiceCount < 1 || choiceCount > 3
                    || maxRerolls < 0 || maxRerolls > 5
                    || (pityThreshold > 0 && qualifyingRewards.isEmpty())
                    || qualifyingRewards.stream().distinct().count() != qualifyingRewards.size()) {
                throw new IllegalArgumentException("Invalid managed case rules: " + crateId);
            }
        }
    }

    public static ManagedCratesSettings read(ConfigurationSection config) {
        var section = config.getConfigurationSection("cases");
        Map<String, CaseSettings> cases = new LinkedHashMap<>();
        if (section != null) {
            for (String id : section.getKeys(false)) {
                var item = section.getConfigurationSection(id);
                if (item == null) throw new IllegalArgumentException("Invalid case configuration: " + id);
                Object qualifying = item.get("qualifying-rewards");
                if (qualifying != null && (!(qualifying instanceof List<?> values)
                        || values.stream().anyMatch(value -> !(value instanceof String)))) {
                    throw new IllegalArgumentException("qualifying-rewards must be a list of IDs: " + id);
                }
                cases.put(id, new CaseSettings(id, item.getString("season", "launch"),
                        integer(item, "pity-threshold", 20), integer(item, "choices", 3),
                        integer(item, "rerolls", 1), item.getStringList("qualifying-rewards"),
                        item.getString("furniture", "")));
            }
        }
        if (config.contains("enabled") && !(config.get("enabled") instanceof Boolean)) {
            throw new IllegalArgumentException("enabled must be a boolean");
        }
        boolean enabled = config.getBoolean("enabled", false);
        if (enabled && cases.isEmpty()) throw new IllegalArgumentException("Managed openings enabled without cases");
        return new ManagedCratesSettings(enabled, cases);
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
