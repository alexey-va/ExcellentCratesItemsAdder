package ru.ruscrafting.ecia.admin;

import org.junit.jupiter.api.Test;
import ru.ruscrafting.ecia.CratePosition;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrateAdminServiceTest {
    private static final String MODEL = "arc:case_crate";
    private static final String SHA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final CratePosition ANCHOR = new CratePosition("world", 10, 64, 20);
    private static final AdminCrateDescriptor CRATE = new AdminCrateDescriptor(
            "emerald", List.of(ANCHOR), new AdminCrateDescriptor.NativeKeyCost("key_emerald", "emerald_key", 1, true));

    @Test
    void inspectionContainsNativeAndCallerSuppliedState() {
        FakeGateway gateway = new FakeGateway(CRATE, healthy());
        PendingMailCounters pending = new PendingMailCounters(2, 1, 3, 1, 1);
        CrateAdminService service = new CrateAdminService(gateway, ignored -> pending);

        CrateInspection result = service.inspect("emerald", MODEL);

        assertEquals(CrateInspection.Status.HEALTHY, result.status());
        assertEquals(CRATE.nativeKeyCost(), result.nativeKeyCost());
        assertEquals(pending, result.pendingMail());
        assertEquals(1, result.positions().size());
        CrateInspection.PositionInspection position = result.positions().getFirst();
        assertEquals(AdminCrateGateway.ChunkStatus.LOADED, position.chunkStatus());
        assertEquals("BARRIER", position.technicalBlock().material());
        assertEquals(MODEL, position.actualModel().namespacedId());
        assertEquals(MODEL, position.expectedModel().namespacedId());
        assertEquals(2, position.nearbyCarrierCount());
        assertTrue(position.grounding().verifiedFor(MODEL));
    }

    @Test
    void missingModelAtEmptyGroundedAnchorIsRepairable() {
        FakeGateway gateway = new FakeGateway(CRATE, missing());
        CrateAdminService service = new CrateAdminService(gateway, ignored -> PendingMailCounters.empty());

        CrateInspection result = service.inspect("emerald", MODEL);

        assertEquals(CrateInspection.Status.REPAIRABLE, result.status());
        assertEquals(CrateInspection.PositionStatus.MISSING_MODEL, result.positions().getFirst().status());
        assertTrue(result.positions().getFirst().canRepair());
        assertTrue(result.reasons().contains(CrateInspection.Reason.MODEL_MISSING));
    }

    @Test
    void repairUsesNativeGatewayAndRebuildsBindingAfterReadback() {
        FakeGateway gateway = new FakeGateway(CRATE, missing());
        CrateAdminService service = new CrateAdminService(gateway, ignored -> PendingMailCounters.empty());

        CrateRepairResult result = service.repair("emerald", MODEL);

        assertEquals(CrateRepairResult.Status.REPAIRED, result.status());
        assertEquals(1, result.repairedPositions());
        assertEquals(1, gateway.spawnCalls);
        assertEquals(1, gateway.rebuildCalls);
        assertEquals(CrateInspection.Status.HEALTHY, result.after().status());
    }

    @Test
    void foreignBlockOrCarrierBlocksWithoutMutation() {
        FakeGateway foreignBlock = new FakeGateway(CRATE, new AdminCrateGateway.AnchorObservation(
                ANCHOR, AdminCrateGateway.WorldStatus.PRESENT, AdminCrateGateway.ChunkStatus.LOADED,
                new AdminCrateGateway.TechnicalBlock("CHEST", "minecraft:chest", AdminCrateGateway.TechnicalBlock.Kind.FOREIGN, ""),
                null, 0, grounded()));
        CrateAdminService blockService = new CrateAdminService(foreignBlock, ignored -> PendingMailCounters.empty());
        CrateRepairResult blockResult = blockService.repair("emerald", MODEL);

        assertEquals(CrateRepairResult.Status.BLOCKED, blockResult.status());
        assertTrue(blockResult.reasons().contains(CrateInspection.Reason.FOREIGN_BLOCK));
        assertEquals(0, foreignBlock.spawnCalls);
        assertEquals(0, foreignBlock.rebuildCalls);

        FakeGateway foreignCarrier = new FakeGateway(CRATE, new AdminCrateGateway.AnchorObservation(
                ANCHOR, AdminCrateGateway.WorldStatus.PRESENT, AdminCrateGateway.ChunkStatus.LOADED,
                new AdminCrateGateway.TechnicalBlock("AIR", "minecraft:air", AdminCrateGateway.TechnicalBlock.Kind.AIR, ""),
                null, 1, grounded()));
        CrateRepairResult carrierResult = new CrateAdminService(foreignCarrier, ignored -> PendingMailCounters.empty())
                .repair("emerald", MODEL);

        assertEquals(CrateRepairResult.Status.BLOCKED, carrierResult.status());
        assertTrue(carrierResult.reasons().contains(CrateInspection.Reason.FOREIGN_CARRIER));
        assertEquals(0, foreignCarrier.spawnCalls);
        assertEquals(0, foreignCarrier.rebuildCalls);
    }

    @Test
    void duplicateOrUnverifiedAnchorFailsClosed() {
        AdminCrateDescriptor duplicate = new AdminCrateDescriptor(
                "emerald", List.of(ANCHOR, ANCHOR), CRATE.nativeKeyCost());
        FakeGateway duplicateGateway = new FakeGateway(duplicate, missing());
        CrateInspection duplicateResult = new CrateAdminService(duplicateGateway, ignored -> PendingMailCounters.empty())
                .inspect("emerald", MODEL);
        assertEquals(CrateInspection.Status.AMBIGUOUS, duplicateResult.status());
        assertTrue(duplicateResult.reasons().contains(CrateInspection.Reason.DUPLICATE_CONFIGURED_POSITION));

        FakeGateway unverifiedGateway = new FakeGateway(CRATE, new AdminCrateGateway.AnchorObservation(
                ANCHOR, AdminCrateGateway.WorldStatus.PRESENT, AdminCrateGateway.ChunkStatus.LOADED,
                new AdminCrateGateway.TechnicalBlock("AIR", "minecraft:air", AdminCrateGateway.TechnicalBlock.Kind.AIR, ""),
                null, 0, new AdminCrateGateway.GroundingEvidence(
                AdminCrateGateway.GeometryStatus.FLOATING, MODEL, SHA, 0.0)));
        CrateRepairResult unverifiedResult = new CrateAdminService(unverifiedGateway, ignored -> PendingMailCounters.empty())
                .repair("emerald", MODEL);
        assertEquals(CrateRepairResult.Status.BLOCKED, unverifiedResult.status());
        assertTrue(unverifiedResult.reasons().contains(CrateInspection.Reason.GEOMETRY_NOT_GROUNDED));
        assertEquals(0, unverifiedGateway.spawnCalls);
    }

    @Test
    void existingHitboxWithoutFurnitureIsNotAnEmptyRepairTarget() {
        FakeGateway gateway = new FakeGateway(CRATE, new AdminCrateGateway.AnchorObservation(
                ANCHOR, AdminCrateGateway.WorldStatus.PRESENT, AdminCrateGateway.ChunkStatus.LOADED,
                new AdminCrateGateway.TechnicalBlock("BARRIER", "minecraft:barrier",
                        AdminCrateGateway.TechnicalBlock.Kind.EXPECTED_HITBOX, MODEL),
                null, 0, grounded()));

        CrateRepairResult result = new CrateAdminService(gateway, PendingMailCounters.empty())
                .repair("emerald", MODEL);

        assertEquals(CrateRepairResult.Status.BLOCKED, result.status());
        assertTrue(result.reasons().contains(CrateInspection.Reason.NON_EMPTY_ANCHOR));
        assertEquals(0, gateway.spawnCalls);
        assertEquals(0, gateway.rebuildCalls);
    }

    @Test
    void missingNativeKeyCostBlocksOtherwiseSafeRepair() {
        AdminCrateDescriptor noKeyCost = new AdminCrateDescriptor("emerald", List.of(ANCHOR), null);
        FakeGateway gateway = new FakeGateway(noKeyCost, missing());

        CrateInspection inspection = new CrateAdminService(gateway, PendingMailCounters.empty())
                .inspect("emerald", MODEL);
        CrateRepairResult result = new CrateAdminService(gateway, PendingMailCounters.empty())
                .repair("emerald", MODEL);

        assertEquals(CrateInspection.Status.BLOCKED, inspection.status());
        assertTrue(inspection.reasons().contains(CrateInspection.Reason.NATIVE_KEY_COST_UNAVAILABLE));
        assertEquals(CrateRepairResult.Status.BLOCKED, result.status());
        assertEquals(0, gateway.spawnCalls);
    }

    @Test
    void failedOrUnknownSpawnDoesNotRebuildBinding() {
        FakeGateway gateway = new FakeGateway(CRATE, missing());
        gateway.spawnOutcome = AdminCrateGateway.SpawnReceipt.Outcome.UNKNOWN;
        CrateRepairResult result = new CrateAdminService(gateway, ignored -> PendingMailCounters.empty())
                .repair("emerald", MODEL);

        assertEquals(CrateRepairResult.Status.FAILED, result.status());
        assertTrue(result.reasons().contains(CrateInspection.Reason.SPAWN_UNCONFIRMED));
        assertEquals(0, gateway.rebuildCalls);
    }

    private static AdminCrateGateway.AnchorObservation healthy() {
        return new AdminCrateGateway.AnchorObservation(ANCHOR,
                AdminCrateGateway.WorldStatus.PRESENT, AdminCrateGateway.ChunkStatus.LOADED,
                new AdminCrateGateway.TechnicalBlock("BARRIER", "minecraft:barrier", AdminCrateGateway.TechnicalBlock.Kind.EXPECTED_HITBOX, MODEL),
                new AdminCrateGateway.FurnitureIdentity(MODEL, "arc/block/case_crate", "ITEM_DISPLAY"), 2, grounded());
    }

    private static AdminCrateGateway.AnchorObservation missing() {
        return new AdminCrateGateway.AnchorObservation(ANCHOR,
                AdminCrateGateway.WorldStatus.PRESENT, AdminCrateGateway.ChunkStatus.LOADED,
                new AdminCrateGateway.TechnicalBlock("AIR", "minecraft:air", AdminCrateGateway.TechnicalBlock.Kind.AIR, ""),
                null, 0, grounded());
    }

    private static AdminCrateGateway.GroundingEvidence grounded() {
        return new AdminCrateGateway.GroundingEvidence(
                AdminCrateGateway.GeometryStatus.GROUNDED, MODEL, SHA, 0.0);
    }

    private static final class FakeGateway implements AdminCrateGateway {
        private final AdminCrateDescriptor descriptor;
        private final Map<CratePosition, AnchorObservation> observations = new HashMap<>();
        private SpawnReceipt.Outcome spawnOutcome = SpawnReceipt.Outcome.SPAWNED;
        private int spawnCalls;
        private int rebuildCalls;

        private FakeGateway(AdminCrateDescriptor descriptor, AnchorObservation observation) {
            this.descriptor = descriptor;
            descriptor.positions().forEach(position -> observations.put(position, observation));
        }

        @Override
        public Optional<AdminCrateDescriptor> findCrate(String crateId) {
            return descriptor.crateId().equalsIgnoreCase(crateId) ? Optional.of(descriptor) : Optional.empty();
        }

        @Override
        public AnchorObservation observe(CratePosition position, String expectedModel) {
            return observations.get(position);
        }

        @Override
        public SpawnReceipt spawnExpectedFurniture(CratePosition position, String expectedModel) {
            spawnCalls++;
            if (spawnOutcome == SpawnReceipt.Outcome.SPAWNED) observations.put(position, healthy());
            return new SpawnReceipt(spawnOutcome, spawnOutcome.name());
        }

        @Override
        public void rebuildNativeBinding(String crateId) {
            rebuildCalls++;
        }
    }
}
