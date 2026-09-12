package ru.ruscrafting.ecia.roll;

import java.util.Objects;

/** A weighted reward whose payloads are interpreted by the integration layer. */
public record RewardDefinition(
        String id,
        double weight,
        boolean guaranteeEligible,
        String deliveryPayload,
        String previewPayload
) {
    private static final String IDENTIFIER_PATTERN = "[A-Za-z0-9][A-Za-z0-9._:-]*";

    public RewardDefinition {
        requireIdentifier(id, "id");
        if (!Double.isFinite(weight) || weight <= 0.0d) {
            throw new IllegalArgumentException("weight must be finite and positive");
        }
        Objects.requireNonNull(deliveryPayload, "deliveryPayload");
        Objects.requireNonNull(previewPayload, "previewPayload");
    }

    static void requireIdentifier(String value, String fieldName) {
        if (value == null || !value.matches(IDENTIFIER_PATTERN)) {
            throw new IllegalArgumentException(fieldName + " must be a non-empty safe identifier");
        }
    }
}
