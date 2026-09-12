package ru.ruscrafting.ecia.admin;

import ru.ruscrafting.ecia.CratePosition;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Admin read/repair use case. It deliberately contains no edit-mode or
 * permission logic: those remain owned by the parent command composition.
 */
public final class CrateAdminService {
    private final AdminCrateGateway gateway;
    private final Function<String, PendingMailCounters> pendingMail;

    public CrateAdminService(AdminCrateGateway gateway,
            Function<String, PendingMailCounters> pendingMail) {
        this.gateway = Objects.requireNonNull(gateway);
        this.pendingMail = Objects.requireNonNull(pendingMail);
    }

    /** Convenience form for a caller holding one already computed snapshot. */
    public CrateAdminService(AdminCrateGateway gateway, PendingMailCounters pendingMail) {
        this(gateway, ignored -> Objects.requireNonNull(pendingMail));
    }

    /** Compact API used by the parent admin command/menu composition. */
    public CrateInspection inspect(String crateId, String expectedModel) {
        String normalizedCrate = normalize(crateId);
        String normalizedModel = normalizeModel(expectedModel);
        PendingMailCounters counters = counters(normalizedCrate);
        if (normalizedCrate == null || normalizedModel == null) {
            return new CrateInspection(crateId == null ? "" : crateId, expectedModel == null ? "" : expectedModel,
                    List.of(), null, counters, CrateInspection.Status.INVALID_INPUT,
                    Set.of(CrateInspection.Reason.INVALID_INPUT));
        }

        Optional<AdminCrateDescriptor> descriptor;
        try {
            descriptor = gateway.findCrate(normalizedCrate);
        } catch (RuntimeException failure) {
            return new CrateInspection(normalizedCrate, normalizedModel, List.of(), null, counters,
                    CrateInspection.Status.BLOCKED,
                    Set.of(CrateInspection.Reason.RUNTIME_UNAVAILABLE));
        }
        if (descriptor == null || descriptor.isEmpty()) {
            return new CrateInspection(normalizedCrate, normalizedModel, List.of(), null, counters,
                    CrateInspection.Status.NOT_FOUND, Set.of(CrateInspection.Reason.CRATE_NOT_FOUND));
        }
        if (!descriptor.get().crateId().equalsIgnoreCase(normalizedCrate)) {
            return new CrateInspection(normalizedCrate, normalizedModel, List.of(), null, counters,
                    CrateInspection.Status.AMBIGUOUS,
                    Set.of(CrateInspection.Reason.CRATE_NOT_FOUND));
        }
        return inspectDescriptor(descriptor.get(), normalizedModel, counters);
    }

    /**
     * Restores only missing furniture at a safe empty anchor. Every anchor is
     * preflighted before the first native IA mutation, so a foreign block,
     * entity, unloaded chunk or unverified model geometry blocks the attempt.
     */
    public CrateRepairResult repair(String crateId, String expectedModel) {
        CrateInspection before = inspect(crateId, expectedModel);
        if (before.status() == CrateInspection.Status.INVALID_INPUT
                || before.status() == CrateInspection.Status.NOT_FOUND) {
            return blocked(before, before.status() == CrateInspection.Status.INVALID_INPUT
                    ? CrateInspection.Reason.INVALID_INPUT : CrateInspection.Reason.CRATE_NOT_FOUND);
        }
        if (before.status() == CrateInspection.Status.AMBIGUOUS
                || before.status() == CrateInspection.Status.BLOCKED) {
            return new CrateRepairResult(before.status() == CrateInspection.Status.AMBIGUOUS
                    ? CrateRepairResult.Status.AMBIGUOUS : CrateRepairResult.Status.BLOCKED,
                    before, null, 0, before.reasons(), "Preflight refused the repair");
        }

        List<CrateInspection.PositionInspection> missing = before.positions().stream()
                .filter(position -> position.status() == CrateInspection.PositionStatus.MISSING_MODEL)
                .toList();
        if (missing.isEmpty()) {
            return new CrateRepairResult(CrateRepairResult.Status.NOOP, before, before, 0,
                    before.reasons(), "All configured anchors already contain the expected model");
        }

        Set<CrateInspection.Reason> preflightReasons = EnumSet.noneOf(CrateInspection.Reason.class);
        for (CrateInspection.PositionInspection position : missing) {
            // MODEL_MISSING is the requested repair condition. Every other
            // reason is a safety veto for the native IA mutation.
            position.reasons().stream()
                    .filter(reason -> reason != CrateInspection.Reason.MODEL_MISSING)
                    .forEach(preflightReasons::add);
            if (!position.canRepair()) preflightReasons.add(CrateInspection.Reason.MODEL_UNRESOLVED);
        }
        if (!preflightReasons.isEmpty()) {
            return new CrateRepairResult(CrateRepairResult.Status.BLOCKED, before, null, 0,
                    Set.copyOf(preflightReasons), "A missing anchor is not an empty grounded target");
        }

        int repaired = 0;
        for (CrateInspection.PositionInspection position : missing) {
            AdminCrateGateway.SpawnReceipt receipt;
            try {
                receipt = gateway.spawnExpectedFurniture(position.position(), before.expectedModel());
            } catch (RuntimeException failure) {
                return failed(before, repaired, Set.of(CrateInspection.Reason.SPAWN_FAILED), failure.getMessage());
            }
            if (receipt == null || receipt.outcome() != AdminCrateGateway.SpawnReceipt.Outcome.SPAWNED) {
                CrateInspection.Reason reason = receipt != null && receipt.outcome() == AdminCrateGateway.SpawnReceipt.Outcome.REJECTED
                        ? CrateInspection.Reason.IA_UNAVAILABLE : CrateInspection.Reason.SPAWN_UNCONFIRMED;
                return failed(before, repaired, Set.of(reason), receipt == null ? "No spawn receipt" : receipt.detail());
            }
            CrateInspection.PositionInspection afterSpawn = inspectPosition(position.position(), before.expectedModel());
            if (afterSpawn.status() != CrateInspection.PositionStatus.HEALTHY) {
                return failed(before, repaired, afterSpawn.reasons().isEmpty()
                        ? Set.of(CrateInspection.Reason.SPAWN_UNCONFIRMED) : afterSpawn.reasons(),
                        "Native spawn did not produce a verified expected anchor");
            }
            repaired++;
        }

        try {
            gateway.rebuildNativeBinding(before.crateId());
        } catch (RuntimeException failure) {
            return failed(before, repaired, Set.of(CrateInspection.Reason.BINDING_REBUILD_FAILED), failure.getMessage());
        }
        CrateInspection after = inspect(before.crateId(), before.expectedModel());
        if (after.status() == CrateInspection.Status.BLOCKED || after.status() == CrateInspection.Status.AMBIGUOUS) {
            return failed(before, repaired, after.reasons(), "Post-repair readback is not healthy");
        }
        return new CrateRepairResult(CrateRepairResult.Status.REPAIRED, before, after, repaired,
                after.reasons(), "Native IA furniture and EC binding were rebuilt");
    }

    private CrateInspection inspectDescriptor(AdminCrateDescriptor descriptor, String expectedModel,
            PendingMailCounters counters) {
        Set<CrateInspection.Reason> reasons = EnumSet.noneOf(CrateInspection.Reason.class);
        List<CratePosition> positions = descriptor.positions();
        Set<CratePosition> distinct = new HashSet<>(positions);
        if (positions.isEmpty()) reasons.add(CrateInspection.Reason.NO_CONFIGURED_POSITIONS);
        if (distinct.size() != positions.size()) reasons.add(CrateInspection.Reason.DUPLICATE_CONFIGURED_POSITION);
        if (descriptor.nativeKeyCost() == null) reasons.add(CrateInspection.Reason.NATIVE_KEY_COST_UNAVAILABLE);
        else if (!descriptor.nativeKeyCost().enabled()) reasons.add(CrateInspection.Reason.NATIVE_KEY_COST_DISABLED);
        List<CrateInspection.PositionInspection> reports = new ArrayList<>();
        for (CratePosition position : positions) {
            reports.add(inspectPosition(position, expectedModel));
            reasons.addAll(reports.getLast().reasons());
        }
        CrateInspection.Status status;
        if (reasons.contains(CrateInspection.Reason.DUPLICATE_CONFIGURED_POSITION)
                || reasons.contains(CrateInspection.Reason.AMBIGUOUS_CARRIERS)) {
            status = CrateInspection.Status.AMBIGUOUS;
        } else if (reasons.isEmpty()) {
            status = CrateInspection.Status.HEALTHY;
        } else if (reasons.stream().allMatch(reason -> reason == CrateInspection.Reason.MODEL_MISSING)
                && reports.stream().anyMatch(CrateInspection.PositionInspection::canRepair)) {
            status = CrateInspection.Status.REPAIRABLE;
        } else {
            status = CrateInspection.Status.BLOCKED;
        }
        return new CrateInspection(descriptor.crateId(), expectedModel, reports,
                descriptor.nativeKeyCost(), counters, status, reasons);
    }

    private CrateInspection.PositionInspection inspectPosition(CratePosition position, String expectedModel) {
        AdminCrateGateway.AnchorObservation observation;
        boolean observationFailed = false;
        try {
            observation = gateway.observe(position, expectedModel);
        } catch (RuntimeException failure) {
            observationFailed = true;
            observation = new AdminCrateGateway.AnchorObservation(position,
                    AdminCrateGateway.WorldStatus.UNKNOWN, AdminCrateGateway.ChunkStatus.UNKNOWN,
                    new AdminCrateGateway.TechnicalBlock("UNKNOWN", "", AdminCrateGateway.TechnicalBlock.Kind.UNKNOWN, ""),
                    null, 0, new AdminCrateGateway.GroundingEvidence(
                    AdminCrateGateway.GeometryStatus.UNVERIFIED, expectedModel, "", 1.0));
        }
        EnumSet<CrateInspection.Reason> reasons = EnumSet.noneOf(CrateInspection.Reason.class);
        if (observationFailed) reasons.add(CrateInspection.Reason.RUNTIME_UNAVAILABLE);
        if (observation == null) {
            reasons.add(CrateInspection.Reason.RUNTIME_UNAVAILABLE);
            observation = new AdminCrateGateway.AnchorObservation(position,
                    AdminCrateGateway.WorldStatus.UNKNOWN, AdminCrateGateway.ChunkStatus.UNKNOWN,
                    new AdminCrateGateway.TechnicalBlock("UNKNOWN", "", AdminCrateGateway.TechnicalBlock.Kind.UNKNOWN, ""),
                    null, 0, new AdminCrateGateway.GroundingEvidence(
                    AdminCrateGateway.GeometryStatus.UNVERIFIED, expectedModel, "", 1.0));
        }
        if (observation.worldStatus() != AdminCrateGateway.WorldStatus.PRESENT) reasons.add(CrateInspection.Reason.WORLD_MISSING);
        if (observation.chunkStatus() != AdminCrateGateway.ChunkStatus.LOADED) reasons.add(CrateInspection.Reason.CHUNK_NOT_LOADED);
        switch (observation.technicalBlock().kind()) {
            case FOREIGN -> reasons.add(CrateInspection.Reason.FOREIGN_BLOCK);
            case UNKNOWN -> reasons.add(CrateInspection.Reason.UNKNOWN_BLOCK);
            case EXPECTED_HITBOX -> {
                if (observation.actualModel() == null) reasons.add(CrateInspection.Reason.NON_EMPTY_ANCHOR);
            }
            case AIR -> {
                if (!observation.technicalBlock().isEmpty()) reasons.add(CrateInspection.Reason.NON_EMPTY_ANCHOR);
                else if (observation.actualModel() != null) reasons.add(CrateInspection.Reason.HITBOX_MISSING);
            }
            default -> { }
        }
        if (observation.nearbyCarrierCount() > 1 && observation.actualModel() == null) {
            reasons.add(CrateInspection.Reason.AMBIGUOUS_CARRIERS);
        }
        if (observation.actualModel() == null) {
            reasons.add(CrateInspection.Reason.MODEL_MISSING);
        } else if (!observation.actualModel().sameId(expectedModel)) {
            reasons.add(CrateInspection.Reason.MODEL_MISMATCH);
        }
        if (!observation.grounding().verifiedFor(expectedModel)) {
            reasons.add(CrateInspection.Reason.GEOMETRY_UNVERIFIED);
            if (observation.grounding().status() != AdminCrateGateway.GeometryStatus.GROUNDED
                    && observation.grounding().status() != AdminCrateGateway.GeometryStatus.CONTACTED) {
                reasons.add(CrateInspection.Reason.GEOMETRY_NOT_GROUNDED);
            }
        }
        // A carrier on an empty anchor is a foreign occupant even if its model
        // adapter failed to resolve an identity. Never overwrite it.
        if (observation.actualModel() == null && observation.nearbyCarrierCount() > 0
                && !reasons.contains(CrateInspection.Reason.AMBIGUOUS_CARRIERS)) {
            reasons.add(CrateInspection.Reason.FOREIGN_CARRIER);
        }
        CrateInspection.PositionStatus status;
        boolean expected = observation.actualModel() != null && observation.actualModel().sameId(expectedModel)
                && observation.technicalBlock().kind() != AdminCrateGateway.TechnicalBlock.Kind.FOREIGN
                && observation.technicalBlock().kind() != AdminCrateGateway.TechnicalBlock.Kind.UNKNOWN;
        if (expected && reasons.isEmpty()) {
            status = CrateInspection.PositionStatus.HEALTHY;
        } else if (observation.actualModel() == null && reasons.size() == 1
                && reasons.contains(CrateInspection.Reason.MODEL_MISSING)) {
            status = CrateInspection.PositionStatus.MISSING_MODEL;
        } else if (reasons.contains(CrateInspection.Reason.AMBIGUOUS_CARRIERS)) {
            status = CrateInspection.PositionStatus.AMBIGUOUS;
        } else {
            status = CrateInspection.PositionStatus.BLOCKED;
        }
        return new CrateInspection.PositionInspection(position, observation.worldStatus(), observation.chunkStatus(),
                observation.technicalBlock(), observation.actualModel(),
                new AdminCrateGateway.FurnitureIdentity(expectedModel, "", ""),
                observation.nearbyCarrierCount(), observation.grounding(), status, reasons);
    }

    private PendingMailCounters counters(String crateId) {
        if (crateId == null) return PendingMailCounters.empty();
        PendingMailCounters value = pendingMail.apply(crateId);
        return value == null ? PendingMailCounters.empty() : value;
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank() || !value.trim().matches("[A-Za-z0-9_-]+")) return null;
        return value.trim();
    }

    private static String normalizeModel(String value) {
        if (value == null || value.isBlank() || value.trim().length() > 256
                || !value.trim().matches("[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+")) return null;
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static CrateRepairResult blocked(CrateInspection before, CrateInspection.Reason reason) {
        return new CrateRepairResult(CrateRepairResult.Status.BLOCKED, before, null, 0,
                Set.of(reason), "Repair preflight refused the request");
    }

    private static CrateRepairResult failed(CrateInspection before, int repaired,
            Set<CrateInspection.Reason> reasons, String detail) {
        return new CrateRepairResult(CrateRepairResult.Status.FAILED, before, null, repaired,
                reasons.isEmpty() ? Set.of(CrateInspection.Reason.SPAWN_UNCONFIRMED) : reasons, detail);
    }
}
