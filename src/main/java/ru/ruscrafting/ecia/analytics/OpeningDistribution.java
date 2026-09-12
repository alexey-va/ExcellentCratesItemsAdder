package ru.ruscrafting.ecia.analytics;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Observed offers and selections for one crate, season and opening mode.
 * Offered counts describe only each record's final visible offer set; the
 * durable opening record does not retain superseded reroll sets.
 */
public record OpeningDistribution(
        String crateId,
        String seasonId,
        boolean guaranteed,
        boolean rerolled,
        long openingCount,
        Map<String, Long> offeredCounts,
        Map<String, Long> selectedCounts,
        Map<String, Double> baseWeightShares
) {
    public OpeningDistribution {
        Objects.requireNonNull(crateId, "crateId");
        Objects.requireNonNull(seasonId, "seasonId");
        if (openingCount < 0) {
            throw new IllegalArgumentException("openingCount must be non-negative");
        }
        offeredCounts = immutableLongMap(offeredCounts, "offeredCounts");
        selectedCounts = immutableLongMap(selectedCounts, "selectedCounts");
        baseWeightShares = immutableDoubleMap(baseWeightShares, "baseWeightShares");
    }

    public Mode mode() {
        if (rerolled) {
            return guaranteed ? Mode.REROLLED_GUARANTEED : Mode.REROLLED_ORDINARY;
        }
        return guaranteed ? Mode.GUARANTEED : Mode.ORDINARY;
    }

    public enum Mode {
        ORDINARY,
        GUARANTEED,
        REROLLED_ORDINARY,
        REROLLED_GUARANTEED
    }

    private static Map<String, Long> immutableLongMap(Map<String, Long> values, String fieldName) {
        Objects.requireNonNull(values, fieldName);
        Map<String, Long> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Long> entry : values.entrySet()) {
            Objects.requireNonNull(entry.getKey(), fieldName + " key");
            Long value = Objects.requireNonNull(entry.getValue(), fieldName + " value");
            if (value < 0) {
                throw new IllegalArgumentException(fieldName + " values must be non-negative");
            }
            copy.put(entry.getKey(), value);
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Map<String, Double> immutableDoubleMap(Map<String, Double> values, String fieldName) {
        Objects.requireNonNull(values, fieldName);
        Map<String, Double> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Double> entry : values.entrySet()) {
            Objects.requireNonNull(entry.getKey(), fieldName + " key");
            Double value = Objects.requireNonNull(entry.getValue(), fieldName + " value");
            if (!Double.isFinite(value) || value < 0.0d) {
                throw new IllegalArgumentException(fieldName + " values must be finite and non-negative");
            }
            copy.put(entry.getKey(), value);
        }
        return Collections.unmodifiableMap(copy);
    }
}
