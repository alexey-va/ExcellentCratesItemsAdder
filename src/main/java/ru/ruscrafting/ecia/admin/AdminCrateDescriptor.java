package ru.ruscrafting.ecia.admin;

import ru.ruscrafting.ecia.CratePosition;

import java.util.List;
import java.util.Objects;

/** Native EC data needed by the addon admin boundary. */
public record AdminCrateDescriptor(String crateId, List<CratePosition> positions,
        NativeKeyCost nativeKeyCost) {
    public AdminCrateDescriptor {
        Objects.requireNonNull(crateId);
        positions = List.copyOf(positions);
    }

    /** The one physical key cost exposed by the native EC definition. */
    public record NativeKeyCost(String costId, String keyId, int amount, boolean enabled) {
        public NativeKeyCost {
            Objects.requireNonNull(costId);
            Objects.requireNonNull(keyId);
            if (amount < 1) throw new IllegalArgumentException("Native key cost amount must be positive");
        }
    }
}
