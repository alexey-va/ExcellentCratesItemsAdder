package ru.ruscrafting.ecia.admin;

import org.bukkit.entity.Entity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.ruscrafting.ecia.CratePosition;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemsAdderGroundingProviderTest {
    private static final String MODEL = "case:wooden_chest";
    private static final CratePosition ANCHOR = new CratePosition("world", 10, 71, 20);

    @Test
    void validatesReportArtifactsSurfaceAndNativeTransform(@TempDir Path temp) throws Exception {
        Path plugin = temp.resolve("ItemsAdder");
        byte[] config = "namespace: case\nitem: wooden_chest\n".getBytes(StandardCharsets.UTF_8);
        byte[] model = "{\"elements\":[]}".getBytes(StandardCharsets.UTF_8);
        Path configFile = plugin.resolve("contents/case/configs/cases.yml");
        Path modelFile = plugin.resolve("contents/case/resourcepack/assets/case/models/wooden_chest.json");
        Files.createDirectories(configFile.getParent());
        Files.createDirectories(modelFile.getParent());
        Files.write(configFile, config);
        Files.write(modelFile, model);
        Path report = plugin.resolve("grounding/case/wooden_chest.json");
        Files.createDirectories(report.getParent());
        Files.writeString(report, reportJson(config, model));

        ItemsAdderGroundingProvider provider = new ItemsAdderGroundingProvider(plugin,
                plugin.resolve("grounding"),
                (position, definition) -> new ItemsAdderGroundingProvider.SupportObservation(
                        true, true, definition.reportStatus(), definition.reportResidual()),
                ignored -> Optional.of(nativeTransform()));

        ItemsAdderGroundingProvider.ModelDefinition definition = provider.definition(MODEL).orElseThrow();
        assertTrue(definition.usable());
        assertTrue(definition.modelBounds().usable());
        AdminCrateGateway.GroundingEvidence evidence = provider.resolve(ANCHOR, MODEL,
                new NativeAdminCrateGateway.FurnitureObservation(
                        new AdminCrateGateway.FurnitureIdentity(MODEL, "case/wooden_chest", "ITEM_DISPLAY"),
                        proxyEntity()));
        assertEquals(AdminCrateGateway.GeometryStatus.GROUNDED, evidence.status());
        assertEquals(MODEL, evidence.modelId());
        assertTrue(evidence.verifiedFor(MODEL));
    }

    @Test
    void floatingNativeTransformIsRefused(@TempDir Path temp) throws Exception {
        Path plugin = writeFixture(temp);
        ItemsAdderGroundingProvider provider = new ItemsAdderGroundingProvider(plugin,
                plugin.resolve("grounding"),
                (position, definition) -> new ItemsAdderGroundingProvider.SupportObservation(
                        true, true, definition.reportStatus(), definition.reportResidual()),
                ignored -> Optional.of(new ItemsAdderGroundingProvider.TransformSnapshot(
                        "ITEM_DISPLAY", 10.5, 71.001, 20.5, 0, 0,
                        new ItemsAdderGroundingProvider.Vector3(0, 2.2, 0),
                        new ItemsAdderGroundingProvider.Vector3(.65, .65, .65),
                        new ItemsAdderGroundingProvider.Quaternion(0, 0, 0, 1),
                        new ItemsAdderGroundingProvider.Quaternion(0, 0, 0, 1), 11782)));
        AdminCrateGateway.GroundingEvidence evidence = provider.resolve(ANCHOR, MODEL,
                new NativeAdminCrateGateway.FurnitureObservation(
                        new AdminCrateGateway.FurnitureIdentity(MODEL, "case/wooden_chest", "ITEM_DISPLAY"),
                        proxyEntity()));
        assertEquals(AdminCrateGateway.GeometryStatus.UNVERIFIED, evidence.status());
    }

    @Test
    void changedArtifactShaAndSurfaceRefuse(@TempDir Path temp) throws Exception {
        Path plugin = writeFixture(temp);
        Path model = plugin.resolve("contents/case/resourcepack/assets/case/models/wooden_chest.json");
        Files.writeString(model, "changed");
        ItemsAdderGroundingProvider provider = new ItemsAdderGroundingProvider(plugin,
                plugin.resolve("grounding"),
                (position, definition) -> new ItemsAdderGroundingProvider.SupportObservation(
                        true, true, AdminCrateGateway.GeometryStatus.TERRAIN_MISMATCH, 1.0),
                ignored -> Optional.of(nativeTransform()));
        AdminCrateGateway.GroundingEvidence stale = provider.resolve(ANCHOR, MODEL);
        assertEquals(AdminCrateGateway.GeometryStatus.UNVERIFIED, stale.status());

        Path plugin2 = writeFixture(temp.resolve("surface"));
        ItemsAdderGroundingProvider floorChanged = new ItemsAdderGroundingProvider(plugin2,
                plugin2.resolve("grounding"),
                (position, definition) -> new ItemsAdderGroundingProvider.SupportObservation(
                        true, true, AdminCrateGateway.GeometryStatus.TERRAIN_MISMATCH, 1.0),
                ignored -> Optional.of(nativeTransform()));
        AdminCrateGateway.GroundingEvidence terrain = floorChanged.resolve(ANCHOR, MODEL);
        assertEquals(AdminCrateGateway.GeometryStatus.TERRAIN_MISMATCH, terrain.status());
        assertTrue(!terrain.verifiedFor(MODEL));
    }

    @Test
    void nonMinecraftParentIsRejectedUntilItsProvenanceIsVerified(@TempDir Path temp) throws Exception {
        Path plugin = writeFixture(temp, "{\"parent\":\"case/base\",\"elements\":[]}");
        ItemsAdderGroundingProvider provider = new ItemsAdderGroundingProvider(plugin,
                plugin.resolve("grounding"),
                (position, definition) -> new ItemsAdderGroundingProvider.SupportObservation(
                        true, true, definition.reportStatus(), definition.reportResidual()),
                ignored -> Optional.of(nativeTransform()));

        AdminCrateGateway.GroundingEvidence evidence = provider.resolve(ANCHOR, MODEL);
        assertEquals(AdminCrateGateway.GeometryStatus.UNVERIFIED, evidence.status());
    }

    @Test
    void postAdjustmentResidualCannotAuthorizeCurrentSpawn(@TempDir Path temp) throws Exception {
        Path plugin = writeFixture(temp);
        Path report = plugin.resolve("grounding/case/wooden_chest.json");
        String staleAdjustment = Files.readString(report).replace(
                "\"supportResidual\":0,",
                "\"supportResidual\":0,\"contact\":{\"current_min_residual\":-0.06364925},");
        Files.writeString(report, staleAdjustment);
        ItemsAdderGroundingProvider provider = new ItemsAdderGroundingProvider(plugin,
                plugin.resolve("grounding"),
                (position, definition) -> new ItemsAdderGroundingProvider.SupportObservation(
                        true, true, definition.reportStatus(), definition.reportResidual()));
        AdminCrateGateway.GroundingEvidence evidence = provider.resolve(ANCHOR, MODEL);
        assertEquals(AdminCrateGateway.GeometryStatus.UNVERIFIED, evidence.status());
    }

    @Test
    void missingReportIsUnverified(@TempDir Path temp) {
        ItemsAdderGroundingProvider provider = new ItemsAdderGroundingProvider(
                temp.resolve("ItemsAdder"), temp.resolve("grounding"));
        assertEquals(AdminCrateGateway.GeometryStatus.UNVERIFIED,
                provider.resolve(ANCHOR, MODEL).status());
    }

    private static Path writeFixture(Path temp) throws Exception {
        return writeFixture(temp, "{\"elements\":[]}");
    }

    private static Path writeFixture(Path temp, String modelJson) throws Exception {
        Path plugin = temp.resolve("ItemsAdder");
        byte[] config = "namespace: case\nitem: wooden_chest\n".getBytes(StandardCharsets.UTF_8);
        byte[] model = modelJson.getBytes(StandardCharsets.UTF_8);
        Path configFile = plugin.resolve("contents/case/configs/cases.yml");
        Path modelFile = plugin.resolve("contents/case/resourcepack/assets/case/models/wooden_chest.json");
        Files.createDirectories(configFile.getParent());
        Files.createDirectories(modelFile.getParent());
        Files.write(configFile, config);
        Files.write(modelFile, model);
        Path report = plugin.resolve("grounding/case/wooden_chest.json");
        Files.createDirectories(report.getParent());
        Files.writeString(report, reportJson(config, model));
        return plugin;
    }

    private static String reportJson(byte[] config, byte[] model) {
        return """
                {
                  "modelId":"case:wooden_chest",
                  "configPath":"contents/case/configs/cases.yml",
                  "modelPath":"contents/case/resourcepack/assets/case/models/wooden_chest.json",
                  "configSha256":"%s",
                  "modelSha256":"%s",
                  "artifactSha256":"%s",
                  "analyzerVersion":"itemsadder-grounding-v1",
                  "boundsVerified":true,
                  "visibleBounds":{"minX":0,"minY":0,"minZ":0,"maxX":1,"maxY":1,"maxZ":1},
                  "geometryStatus":"GROUNDED",
                  "supportResidual":0,
                  "collisionSurface":[{"dx":0,"dy":-1,"dz":0,"material":"stone","blockData":"minecraft:stone","topY":70}],
                  "spawnTransform":{
                    "api":"CUSTOM_FURNITURE_SPAWN_BLOCK",
                    "entityType":"ITEM_DISPLAY",
                    "anchorOffset":[0.5,0.001,0.5],
                    "translation":[0,1.7,0],
                    "scale":[0.65,0.65,0.65],
                    "leftRotation":[0,0,0,1],
                    "rightRotation":[0,0,0,1],
                    "yaw":0,"pitch":0,"customModelData":11782
                  },
                  "nativeModelPath":"case/wooden_chest"
                }
                """.formatted(sha256(config), sha256(model), sha256(concat(config, model)));
    }

    private static ItemsAdderGroundingProvider.TransformSnapshot nativeTransform() {
        return new ItemsAdderGroundingProvider.TransformSnapshot("ITEM_DISPLAY", 10.5, 71.001, 20.5, 0, 0,
                new ItemsAdderGroundingProvider.Vector3(0, 1.7, 0),
                new ItemsAdderGroundingProvider.Vector3(.65, .65, .65),
                new ItemsAdderGroundingProvider.Quaternion(0, 0, 0, 1),
                new ItemsAdderGroundingProvider.Quaternion(0, 0, 0, 1), 11782);
    }

    private static Entity proxyEntity() {
        return (Entity) Proxy.newProxyInstance(Entity.class.getClassLoader(), new Class<?>[]{Entity.class},
                (proxy, method, args) -> null);
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = new byte[first.length + 1 + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        result[first.length] = 0;
        System.arraycopy(second, 0, result, first.length + 1, second.length);
        return result;
    }

    private static String sha256(byte[] value) throws RuntimeException {
        try {
            StringBuilder result = new StringBuilder();
            for (byte item : MessageDigest.getInstance("SHA-256").digest(value)) {
                result.append("%02x".formatted(item));
            }
            return result.toString();
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }
}
