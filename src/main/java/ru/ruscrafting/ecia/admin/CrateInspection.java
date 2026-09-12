package ru.ruscrafting.ecia.admin;

import ru.ruscrafting.ecia.CratePosition;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Typed, localized-message-friendly readback of one managed native crate. */
public record CrateInspection(String crateId, String expectedModel,
        List<PositionInspection> positions, AdminCrateDescriptor.NativeKeyCost nativeKeyCost,
        PendingMailCounters pendingMail, Status status, Set<Reason> reasons) {
    public CrateInspection {
        Objects.requireNonNull(crateId);
        Objects.requireNonNull(expectedModel);
        positions = List.copyOf(positions);
        Objects.requireNonNull(pendingMail);
        Objects.requireNonNull(status);
        reasons = Set.copyOf(reasons);
    }

    public boolean healthy() { return status == Status.HEALTHY; }

    public enum Status { HEALTHY, REPAIRABLE, BLOCKED, AMBIGUOUS, NOT_FOUND, INVALID_INPUT }

    public enum PositionStatus { HEALTHY, MISSING_MODEL, BLOCKED, AMBIGUOUS }

    public enum Reason {
        INVALID_INPUT,
        CRATE_NOT_FOUND,
        NO_CONFIGURED_POSITIONS,
        DUPLICATE_CONFIGURED_POSITION,
        NATIVE_KEY_COST_UNAVAILABLE,
        NATIVE_KEY_COST_DISABLED,
        WORLD_MISSING,
        CHUNK_NOT_LOADED,
        FOREIGN_BLOCK,
        UNKNOWN_BLOCK,
        NON_EMPTY_ANCHOR,
        HITBOX_MISSING,
        MODEL_MISSING,
        MODEL_MISMATCH,
        MODEL_UNRESOLVED,
        FOREIGN_CARRIER,
        AMBIGUOUS_CARRIERS,
        GEOMETRY_UNVERIFIED,
        GEOMETRY_NOT_GROUNDED,
        IA_UNAVAILABLE,
        RUNTIME_UNAVAILABLE,
        SPAWN_FAILED,
        SPAWN_UNCONFIRMED,
        BINDING_REBUILD_FAILED
    }

    public record PositionInspection(CratePosition position,
            AdminCrateGateway.WorldStatus worldStatus,
            AdminCrateGateway.ChunkStatus chunkStatus,
            AdminCrateGateway.TechnicalBlock technicalBlock,
            AdminCrateGateway.FurnitureIdentity actualModel,
            AdminCrateGateway.FurnitureIdentity expectedModel,
            int nearbyCarrierCount,
            AdminCrateGateway.GroundingEvidence grounding,
            PositionStatus status, Set<Reason> reasons) {
        public PositionInspection {
            Objects.requireNonNull(position);
            Objects.requireNonNull(worldStatus);
            Objects.requireNonNull(chunkStatus);
            Objects.requireNonNull(technicalBlock);
            Objects.requireNonNull(expectedModel);
            Objects.requireNonNull(grounding);
            if (nearbyCarrierCount < 0) throw new IllegalArgumentException("Carrier count must not be negative");
            Objects.requireNonNull(status);
            reasons = Set.copyOf(reasons);
        }

        /** True only for an empty, loaded, grounded anchor with no other warning. */
        public boolean canRepair() {
            return status == PositionStatus.MISSING_MODEL
                    && reasons.size() == 1
                    && reasons.contains(Reason.MODEL_MISSING);
        }
    }
}
