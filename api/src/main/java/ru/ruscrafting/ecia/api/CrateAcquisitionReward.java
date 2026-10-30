package ru.ruscrafting.ecia.api;

import java.util.Objects;

/**
 * One top-level reward in a managed pool. Probability is in [0, 1] for the
 * single weighted headline draw; the reward's own contents may be a bundle.
 * Catalog IDs are both empty for native rewards such as physical crate keys.
 * Display names retain the loaded native formatting.
 */
public record CrateAcquisitionReward(
        String rewardId,
        String displayName,
        double weight,
        double probability,
        String catalogCategoryId,
        String catalogEntryId
) {
    public CrateAcquisitionReward {
        Objects.requireNonNull(rewardId, "rewardId");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(catalogCategoryId, "catalogCategoryId");
        Objects.requireNonNull(catalogEntryId, "catalogEntryId");
        if (!Double.isFinite(weight) || weight <= 0.0d) {
            throw new IllegalArgumentException("weight must be finite and positive");
        }
        if (!Double.isFinite(probability) || probability < 0.0d || probability > 1.0d) {
            throw new IllegalArgumentException("probability must be in [0, 1]");
        }
    }
}
