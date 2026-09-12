package ru.ruscrafting.ecia.admin;

import ru.ruscrafting.ecia.CratePosition;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Narrow runtime boundary for admin inspection and repair.
 *
 * <p>The production implementation is responsible for using
 * {@code CustomFurniture.byAlreadySpawned} / {@code CustomFurniture.spawn}
 * from the exact ItemsAdder runtime. It must never implement this contract by
 * creating fake display entities. It also owns the core chunk ticket and
 * placement-grounding probe; this service refuses an unloaded or unverified
 * anchor and therefore never loads a chunk or guesses a vertical offset.</p>
 */
public interface AdminCrateGateway {
    Optional<AdminCrateDescriptor> findCrate(String crateId);

    AnchorObservation observe(CratePosition position, String expectedModel);

    SpawnReceipt spawnExpectedFurniture(CratePosition position, String expectedModel);

    /** Re-indexes native EC positions and recreates its configured hologram. */
    void rebuildNativeBinding(String crateId);

    /**
     * Snapshot of one exact configured anchor. A null actual model means that
     * no single model was identified; the carrier count still remains useful
     * for an operator report.
     */
    record AnchorObservation(CratePosition position, WorldStatus worldStatus,
            ChunkStatus chunkStatus, TechnicalBlock technicalBlock,
            FurnitureIdentity actualModel, int nearbyCarrierCount,
            GroundingEvidence grounding) {
        public AnchorObservation {
            Objects.requireNonNull(position);
            Objects.requireNonNull(worldStatus);
            Objects.requireNonNull(chunkStatus);
            Objects.requireNonNull(technicalBlock);
            Objects.requireNonNull(grounding);
            if (nearbyCarrierCount < 0) throw new IllegalArgumentException("Carrier count must not be negative");
        }

        /**
         * True when the provider saw multiple carriers but could not establish
         * that they belong to the same IA furniture instance. The compact
         * seven-argument form remains the normal unambiguous contract.
         */
        public boolean carrierIdentityAmbiguous() {
            return nearbyCarrierCount > 1 && actualModel == null;
        }
    }

    /** Result of the native IA spawn call; the service verifies it by observing again. */
    record SpawnReceipt(Outcome outcome, String detail) {
        public SpawnReceipt {
            Objects.requireNonNull(outcome);
            detail = detail == null ? "" : detail;
        }

        public enum Outcome { SPAWNED, REJECTED, FAILED, UNKNOWN }
    }

    record TechnicalBlock(String material, String blockData, Kind kind, String ownerModel) {
        public TechnicalBlock {
            Objects.requireNonNull(material);
            Objects.requireNonNull(blockData);
            Objects.requireNonNull(kind);
            ownerModel = ownerModel == null ? "" : ownerModel;
        }

        public boolean isEmpty() {
            return kind == Kind.AIR
                    && ownerModel.isBlank()
                    && switch (material.toLowerCase(java.util.Locale.ROOT)) {
                        case "air", "cave_air", "void_air", "minecraft:air", "minecraft:cave_air", "minecraft:void_air" -> true;
                        default -> false;
                    };
        }

        public enum Kind { AIR, EXPECTED_HITBOX, FOREIGN, UNKNOWN }
    }

    /** Identity returned by ItemsAdder, including optional model provenance. */
    record FurnitureIdentity(String namespacedId, String modelPath, String entityType) {
        public FurnitureIdentity {
            Objects.requireNonNull(namespacedId);
            modelPath = modelPath == null ? "" : modelPath;
            entityType = entityType == null ? "" : entityType;
        }

        public boolean sameId(String other) {
            return other != null && namespacedId.equalsIgnoreCase(other.trim());
        }
    }

    /**
     * Grounding evidence produced by the core placement/model probe. The
     * expected model id and SHA are carried through the report so a later
     * repair cannot silently reuse stale geometry.
     */
    record GroundingEvidence(GeometryStatus status, String modelId, String modelSha256,
            double supportResidual) {
        public GroundingEvidence {
            Objects.requireNonNull(status);
            modelId = modelId == null ? "" : modelId;
            modelSha256 = modelSha256 == null ? "" : modelSha256;
            if (!Double.isFinite(supportResidual) || supportResidual < 0) {
                throw new IllegalArgumentException("Invalid grounding residual");
            }
        }

        public boolean verifiedFor(String expectedModel) {
            return (status == GeometryStatus.GROUNDED || status == GeometryStatus.CONTACTED)
                    && modelId.equalsIgnoreCase(expectedModel)
                    && modelSha256.matches("(?i)[0-9a-f]{64}")
                    && supportResidual <= 1.0e-4;
        }
    }

    enum WorldStatus { PRESENT, MISSING, UNKNOWN }

    enum ChunkStatus { LOADED, UNLOADED, UNKNOWN }

    enum GeometryStatus { GROUNDED, CONTACTED, FLOATING, INTERSECTING, TERRAIN_MISMATCH, AMBIGUOUS, UNVERIFIED }
}
