package ru.ruscrafting.ecia.api;

import java.util.List;
import java.util.Objects;

/** Immutable loaded-pool evidence; display names retain the native formatting. */
public record CrateAcquisitionSnapshot(
        String crateId,
        String displayName,
        boolean available,
        List<CrateAcquisitionReward> rewards
) {
    public CrateAcquisitionSnapshot {
        Objects.requireNonNull(crateId, "crateId");
        Objects.requireNonNull(displayName, "displayName");
        rewards = List.copyOf(rewards);
        if (!available && !rewards.isEmpty()) {
            throw new IllegalArgumentException("An unavailable pool cannot contain rewards");
        }
    }

    public static CrateAcquisitionSnapshot unavailable(String crateId) {
        return new CrateAcquisitionSnapshot(crateId, crateId, false, List.of());
    }
}
