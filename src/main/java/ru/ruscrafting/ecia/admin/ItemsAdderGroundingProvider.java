package ru.ruscrafting.ecia.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import ru.ruscrafting.ecia.CratePosition;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Validates retained ItemsAdder placement evidence.
 *
 * <p>The plugin does not derive placement geometry from a Blockbench model at
 * runtime. That approach cannot account for parent models, display transforms,
 * rotations or the transform actually written to a live ItemDisplay. A
 * bounded analyzer report is therefore the authority. Every lookup re-hashes
 * the one report's referenced IA config and model files, checks the exact
 * native spawn transform, and compares every recorded collision-surface block
 * with the currently loaded world. Missing, stale or incomplete evidence is
 * {@code UNVERIFIED} and cannot authorize repair.</p>
 *
 * <p>Reports are JSON files in {@code reportDirectory}. The preferred layout is
 * {@code reportDirectory/&lt;namespace&gt;/&lt;item&gt;.json}; a single
 * {@code grounding-report.json} containing a {@code models} object is also
 * accepted. The report format is intentionally explicit and is documented by
 * the public records below. This class never scans the ItemsAdder contents
 * tree and never loads a chunk.</p>
 */
public final class ItemsAdderGroundingProvider implements NativeAdminCrateGateway.GroundingProvider {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final double EPSILON = 1.0e-4D;
    private static final long MAX_REPORT_BYTES = 2L * 1024L * 1024L;
    private static final long MAX_ARTIFACT_BYTES = 8L * 1024L * 1024L;
    private static final int MAX_SURFACE_BLOCKS = 256;
    private static final String BLOCK_SPAWN_API = "CUSTOM_FURNITURE_SPAWN_BLOCK";

    private final Path itemsAdderDataFolder;
    private final Path reportDirectory;
    private final SupportProbe supportProbe;
    private final TransformProbe transformProbe;
    private final Map<String, Optional<ModelDefinition>> definitions = new ConcurrentHashMap<>();

    /** Uses {@code ItemsAdder/grounding} as the retained-report directory. */
    public ItemsAdderGroundingProvider(Path itemsAdderDataFolder) {
        this(itemsAdderDataFolder,
                itemsAdderDataFolder == null ? null : itemsAdderDataFolder.resolve("grounding"));
    }

    /** Production constructor. The report directory is supplied by the parent integration. */
    public ItemsAdderGroundingProvider(Path itemsAdderDataFolder, Path reportDirectory) {
        this(itemsAdderDataFolder, reportDirectory, SupportProbe.bukkit(), TransformProbe.bukkit());
    }

    /** Deterministic support-probe injection for tests and core-owned world probes. */
    public ItemsAdderGroundingProvider(Path itemsAdderDataFolder, Path reportDirectory,
            SupportProbe supportProbe) {
        this(itemsAdderDataFolder, reportDirectory, supportProbe, TransformProbe.bukkit());
    }

    /** Full injection form; useful for tests that provide a native transform snapshot. */
    public ItemsAdderGroundingProvider(Path itemsAdderDataFolder, Path reportDirectory,
            SupportProbe supportProbe, TransformProbe transformProbe) {
        this.itemsAdderDataFolder = normalizeRoot(itemsAdderDataFolder);
        this.reportDirectory = reportDirectory == null ? null : reportDirectory.toAbsolutePath().normalize();
        this.supportProbe = Objects.requireNonNull(supportProbe, "supportProbe");
        this.transformProbe = Objects.requireNonNull(transformProbe, "transformProbe");
    }

    /** Compatibility form: no retained report means every result remains fail-closed. */
    public ItemsAdderGroundingProvider(Path itemsAdderDataFolder, SupportProbe supportProbe) {
        this(itemsAdderDataFolder,
                itemsAdderDataFolder == null ? null : itemsAdderDataFolder.resolve("grounding"),
                supportProbe, TransformProbe.bukkit());
    }

    /** Factory with argument order convenient for callers holding the report first. */
    public static ItemsAdderGroundingProvider fromReport(Path reportDirectory, Path itemsAdderDataFolder) {
        return new ItemsAdderGroundingProvider(itemsAdderDataFolder, reportDirectory);
    }

    /** Resolves one requested ID lazily; it does not index the IA contents tree. */
    public Optional<ModelDefinition> definition(String modelId) {
        String normalized = normalizeModel(modelId);
        if (normalized == null) return Optional.empty();
        return definitions.computeIfAbsent(normalized, this::readDefinition);
    }

    /** Returns IDs already requested by this provider. It intentionally performs no broad scan. */
    public Set<String> resolvedModelIds() {
        return definitions.entrySet().stream()
                .filter(entry -> entry.getValue().isPresent() && entry.getValue().get().usable())
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    @Override
    public AdminCrateGateway.GroundingEvidence resolve(CratePosition position, String expectedModel) {
        return resolve(position, expectedModel, null);
    }

    /**
     * Native gateway overload. A missing observation is allowed for an empty
     * repair target: the retained transform is validated before spawn and the
     * native readback validates the actual ItemDisplay afterwards.
     */
    @Override
    public AdminCrateGateway.GroundingEvidence resolve(CratePosition position, String expectedModel,
            NativeAdminCrateGateway.FurnitureObservation actual) {
        Objects.requireNonNull(position, "position");
        String normalized = normalizeModel(expectedModel);
        if (normalized == null) return unverified(expectedModel, null);
        Optional<ModelDefinition> loaded = definition(normalized);
        if (loaded.isEmpty()) return unverified(normalized, null);
        ModelDefinition model = loaded.get();
        String artifactSha = verifiedArtifactSha(model);
        if (!model.usable() || artifactSha.isBlank() || !transformMatches(position, normalized, model, actual)) {
            return unverified(normalized, model);
        }

        SupportObservation support;
        try {
            support = supportProbe.probe(position, model);
        } catch (RuntimeException ignored) {
            return unverified(normalized, model);
        }
        if (support == null || !support.worldPresent() || !support.chunkLoaded()
                || !Double.isFinite(support.supportResidual()) || support.supportResidual() < 0.0D) {
            return unverified(normalized, model);
        }
        return new AdminCrateGateway.GroundingEvidence(support.status(), normalized,
                artifactSha, support.supportResidual());
    }

    private Optional<ModelDefinition> readDefinition(String normalizedModel) {
        if (reportDirectory == null) return Optional.empty();
        for (Path candidate : reportCandidates(normalizedModel)) {
            Optional<ModelDefinition> value = readReport(candidate, normalizedModel);
            if (value.isPresent()) return value;
        }
        return Optional.empty();
    }

    private List<Path> reportCandidates(String normalizedModel) {
        String[] parts = normalizedModel.split(":", 2);
        String namespace = parts[0];
        String item = parts[1];
        List<Path> candidates = new ArrayList<>(5);
        if (Files.isDirectory(reportDirectory)) {
            candidates.add(reportDirectory.resolve(namespace).resolve(item + ".json"));
            candidates.add(reportDirectory.resolve(normalizedModel.replace(':', '_') + ".json"));
            candidates.add(reportDirectory.resolve(normalizedModel.replace(':', '-') + ".json"));
            candidates.add(reportDirectory.resolve("grounding-report.json"));
            candidates.add(reportDirectory.resolve("reports.json"));
        } else {
            candidates.add(reportDirectory);
        }
        return candidates;
    }

    private Optional<ModelDefinition> readReport(Path reportFile, String requestedModel) {
        try {
            if (!Files.isRegularFile(reportFile) || Files.size(reportFile) <= 0L
                    || Files.size(reportFile) > MAX_REPORT_BYTES) return Optional.empty();
            JsonNode root = JSON.readTree(Files.readAllBytes(reportFile));
            JsonNode entry = findEntry(root, requestedModel);
            return entry == null ? Optional.empty() : parseEntry(entry, requestedModel);
        } catch (IOException | RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private static JsonNode findEntry(JsonNode root, String requestedModel) {
        if (root == null || root.isMissingNode() || root.isNull()) return null;
        JsonNode models = root.path("models");
        if (models.isObject()) {
            JsonNode direct = models.get(requestedModel);
            if (direct != null) return direct;
            var fields = models.fields();
            int seen = 0;
            while (fields.hasNext() && seen++ < 4096) {
                var field = fields.next();
                if (requestedModel.equalsIgnoreCase(field.getKey())) return field.getValue();
                if (requestedModel.equalsIgnoreCase(field.getValue().path("modelId").asText(""))) {
                    return field.getValue();
                }
            }
            return null;
        }
        if (root.isArray()) {
            int seen = 0;
            for (JsonNode value : root) {
                if (seen++ >= 4096) break;
                if (requestedModel.equalsIgnoreCase(value.path("modelId").asText(""))) return value;
            }
            return null;
        }
        String modelId = root.path("modelId").asText(root.path("id").asText(""));
        if (modelId.isBlank()) modelId = root.path("identity").path("itemsadder_id").asText(
                root.path("identity").path("model_reference").asText(""));
        return requestedModel.equalsIgnoreCase(modelId) ? root : null;
    }

    private Optional<ModelDefinition> parseEntry(JsonNode entry, String requestedModel) {
        String modelId = normalizeModel(text(entry, "modelId", "id"));
        if (modelId == null) modelId = normalizeModel(text(entry.path("identity"), "itemsadder_id", "model_reference"));
        if (!requestedModel.equals(modelId)) return Optional.empty();
        Path configFile = resolveArtifact(text(entry, "configPath", "configFile"));
        if (configFile.toString().isBlank()) configFile = resolveArtifact(text(entry.path("identity"), "config_path"));
        Path modelFile = resolveArtifact(text(entry, "modelPath", "modelFile"));
        if (modelFile.toString().isBlank()) modelFile = resolveArtifact(provenancePath(entry));
        String configSha = hashText(entry, "configSha256", "config_sha256", "configHash", "config_hash");
        String modelSha = hashText(entry, "modelSha256", "model_sha256", "modelHash", "model_hash");
        if (modelSha.isBlank()) modelSha = provenanceSha(entry);
        String artifactSha = hashText(entry, "artifactSha256", "artifact_sha256", "artifactHash", "artifact_hash");
        JsonNode boundsNode = first(entry, "visibleBounds", "modelBounds", "bounds");
        ModelBounds bounds = parseBounds(boundsNode);
        JsonNode transformNode = first(entry, "spawnTransform", "transform");
        TransformExpectation transform = parseTransform(transformNode, entry);
        List<SurfaceContact> surface = parseSurface(first(entry, "collisionSurface", "supportSurface", "supports"));
        AdminCrateGateway.GeometryStatus status = status(text(entry, "geometryStatus", "status"));
        if (status == AdminCrateGateway.GeometryStatus.UNVERIFIED
                && entry.path("automatic_placement_suitable").asBoolean(false)) {
            status = AdminCrateGateway.GeometryStatus.GROUNDED;
        }
        double residual = number(entry, "supportResidual", "support_residual");
        double currentResidual = number(entry.path("contact"), "current_min_residual");
        // Never substitute a post-adjustment residual for the transform that
        // the native block spawn will actually produce. A negative current
        // residual means the current spawn floats/intersects even when the
        // analyzer also reports a zero recommended-position residual.
        if (Double.isFinite(currentResidual)) residual = Math.abs(currentResidual);
        String nativeModelPath = text(entry, "nativeModelPath", "itemModelPath", "iaModelPath");
        if (nativeModelPath.isBlank()) nativeModelPath = text(entry.path("identity"), "model_path");
        boolean furniture = bool(entry, true, "furniture");
        boolean solid = bool(entry, true, "solid");
        boolean floorPlacement = bool(entry, true, "floorPlacement", "floor_placement");
        boolean boundsVerified = bool(entry, false, "boundsVerified", "bounds_verified");
        ModelDefinition model = new ModelDefinition(modelId, configFile, modelFile,
                artifactSha, configSha, modelSha, furniture, solid, floorPlacement,
                number(entry, "width"), number(entry, "length"), number(entry, "height"),
                number(entry, "widthOffset", "width_offset"), number(entry, "lengthOffset", "length_offset"),
                number(entry, "heightOffset", "height_offset"), bounds, false,
                text(entry, "analyzerVersion", "analyzer_version"), boundsVerified, transform,
                surface, status, residual, nativeModelPath);
        return Optional.of(model);
    }

    private static String provenancePath(JsonNode entry) {
        JsonNode provenance = entry.path("model_provenance");
        if (!provenance.isArray() || provenance.isEmpty()) return "";
        return text(provenance.get(0), "path");
    }

    private static String provenanceSha(JsonNode entry) {
        JsonNode provenance = entry.path("model_provenance");
        if (!provenance.isArray() || provenance.isEmpty()) return "";
        return hashText(provenance.get(0), "sha256");
    }

    private String verifiedArtifactSha(ModelDefinition model) {
        if (!validSha(model.configSha256()) || !validSha(model.modelSha256())
                || model.configFile().toString().isBlank()
                || model.modelFile().toString().isBlank()) return "";
        try {
            if (!Files.isRegularFile(model.configFile()) || !Files.isRegularFile(model.modelFile())
                    || Files.size(model.configFile()) > MAX_ARTIFACT_BYTES
                    || Files.size(model.modelFile()) > MAX_ARTIFACT_BYTES) return "";
            if (itemsAdderDataFolder == null || !Files.isDirectory(itemsAdderDataFolder)) return "";
            Path root = itemsAdderDataFolder.toRealPath();
            if (!model.configFile().toRealPath().startsWith(root)
                    || !model.modelFile().toRealPath().startsWith(root)) return "";
            byte[] config = Files.readAllBytes(model.configFile());
            byte[] modelBytes = Files.readAllBytes(model.modelFile());
            String configSha = sha256(config);
            String modelSha = sha256(modelBytes);
            if (!configSha.equalsIgnoreCase(model.configSha256())
                    || !modelSha.equalsIgnoreCase(model.modelSha256())) return "";
            // A leaf hash does not bind the parent model that ItemsAdder will
            // merge into the rendered geometry. Until the report carries and
            // verifies the complete parent provenance chain, only a leaf with
            // no custom parent (or a vanilla Minecraft parent) is safe.
            if (!safeParentChain(modelBytes)) return "";
            String actualArtifactSha = sha256(concat(config, modelBytes));
            return (model.artifactSha256().isBlank()
                    || actualArtifactSha.equalsIgnoreCase(model.artifactSha256())) ? actualArtifactSha : "";
        } catch (IOException | RuntimeException ignored) {
            return "";
        }
    }

    private static boolean safeParentChain(byte[] modelBytes) {
        try {
            JsonNode root = JSON.readTree(modelBytes);
            if (root == null || !root.isObject()) return false;
            JsonNode parent = root.get("parent");
            if (parent == null || parent.isNull() || !parent.isTextual()) return parent == null || parent.isNull();
            String value = parent.asText().trim().toLowerCase(Locale.ROOT);
            return value.isBlank() || value.startsWith("minecraft:");
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }

    private boolean transformMatches(CratePosition position, String expectedModel,
            ModelDefinition model, NativeAdminCrateGateway.FurnitureObservation actual) {
        if (actual == null) return true;
        if (!actual.identity().sameId(expectedModel)) return false;
        if (!model.nativeModelPath().isBlank() && !actual.identity().modelPath().isBlank()
                && !samePath(model.nativeModelPath(), actual.identity().modelPath())) {
            return false;
        }
        TransformExpectation expected = model.spawnTransform();
        if (!expected.usable() || !expected.entityType().equalsIgnoreCase(actual.identity().entityType())) return false;
        Optional<TransformSnapshot> snapshot;
        try {
            snapshot = transformProbe.capture(actual.entity());
        } catch (RuntimeException ignored) {
            return false;
        }
        return snapshot.isPresent() && expected.matches(position, snapshot.get());
    }

    private static boolean samePath(String expected, String actual) {
        return normalizePath(expected).equals(normalizePath(actual));
    }

    private static String normalizePath(String value) {
        return value == null ? "" : value.trim().replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    private static AdminCrateGateway.GroundingEvidence unverified(String expectedModel,
            ModelDefinition model) {
        return new AdminCrateGateway.GroundingEvidence(AdminCrateGateway.GeometryStatus.UNVERIFIED,
                expectedModel == null ? "" : expectedModel,
                model == null ? "" : model.artifactSha256(), 1.0D);
    }

    private static Path normalizeRoot(Path root) {
        return root == null ? null : root.toAbsolutePath().normalize();
    }

    private static String normalizeModel(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.matches("[a-z0-9_.-]+:[a-z0-9_.-]+") ? normalized : null;
    }

    private Path resolveArtifact(String value) {
        if (value == null || value.isBlank() || itemsAdderDataFolder == null) return Path.of("");
        Path path = Path.of(value);
        if (!path.isAbsolute()) path = itemsAdderDataFolder.resolve(path);
        path = path.toAbsolutePath().normalize();
        return path.startsWith(itemsAdderDataFolder) ? path : Path.of("");
    }

    private static String text(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.path(name);
            if (value.isTextual() && !value.asText().isBlank()) return value.asText().trim();
        }
        return "";
    }

    private static JsonNode first(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && !value.isMissingNode() && !value.isNull()) return value;
        }
        return null;
    }

    private static String hashText(JsonNode node, String... names) {
        String value = text(node, names).toLowerCase(Locale.ROOT);
        return validSha(value) ? value : "";
    }

    private static boolean validSha(String value) {
        return value != null && value.matches("[0-9a-fA-F]{64}");
    }

    private static boolean bool(JsonNode node, boolean fallback, String... names) {
        for (String name : names) if (node.has(name)) return node.path(name).asBoolean(fallback);
        return fallback;
    }

    private static double number(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && value.isNumber()) return value.asDouble();
        }
        return Double.NaN;
    }

    private static AdminCrateGateway.GeometryStatus status(String value) {
        if (value == null || value.isBlank()) return AdminCrateGateway.GeometryStatus.UNVERIFIED;
        try {
            return AdminCrateGateway.GeometryStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return AdminCrateGateway.GeometryStatus.UNVERIFIED;
        }
    }

    private static ModelBounds parseBounds(JsonNode node) {
        if (node == null || !node.isObject()) return ModelBounds.invalid();
        return new ModelBounds(number(node, "minX"), number(node, "minY"), number(node, "minZ"),
                number(node, "maxX"), number(node, "maxY"), number(node, "maxZ"));
    }

    private static TransformExpectation parseTransform(JsonNode node, JsonNode entry) {
        if (node == null || !node.isObject()) return TransformExpectation.invalid();
        String api = text(node, "api", "spawnApi", "spawn_api");
        if (api.isBlank()) api = text(entry, "spawnApi", "spawn_api");
        String entityType = text(node, "entityType", "entity_type");
        if (entityType.isBlank()) entityType = text(entry, "entityType", "entity_type");
        Vector3 anchor = vector(first(node, "anchorOffset", "anchor_offset"));
        Vector3 translation = vector(first(node, "translation"));
        Vector3 scale = vector(first(node, "scale"));
        Quaternion left = quaternion(first(node, "leftRotation", "left_rotation"));
        Quaternion right = quaternion(first(node, "rightRotation", "right_rotation"));
        double yaw = number(node, "yaw");
        double pitch = number(node, "pitch");
        JsonNode cmd = first(node, "customModelData", "custom_model_data");
        Integer customModelData = cmd != null && cmd.isIntegralNumber() ? cmd.intValue() : null;
        return new TransformExpectation(api, entityType, anchor, translation, scale, left, right,
                yaw, pitch, customModelData);
    }

    private static Vector3 vector(JsonNode node) {
        if (node == null) return Vector3.invalid();
        if (node.isArray() && node.size() == 3) {
            return new Vector3(node.get(0).asDouble(Double.NaN), node.get(1).asDouble(Double.NaN),
                    node.get(2).asDouble(Double.NaN));
        }
        return new Vector3(number(node, "x"), number(node, "y"), number(node, "z"));
    }

    private static Quaternion quaternion(JsonNode node) {
        if (node == null) return Quaternion.invalid();
        if (node.isArray() && node.size() == 4) {
            return new Quaternion(node.get(0).asDouble(Double.NaN), node.get(1).asDouble(Double.NaN),
                    node.get(2).asDouble(Double.NaN), node.get(3).asDouble(Double.NaN));
        }
        return new Quaternion(number(node, "x"), number(node, "y"), number(node, "z"), number(node, "w"));
    }

    private static List<SurfaceContact> parseSurface(JsonNode node) {
        if (node == null || !node.isArray() || node.size() == 0 || node.size() > MAX_SURFACE_BLOCKS) {
            return List.of();
        }
        List<SurfaceContact> result = new ArrayList<>(node.size());
        for (JsonNode value : node) {
            result.add(new SurfaceContact(value.path("dx").asInt(Integer.MIN_VALUE),
                    value.path("dy").asInt(Integer.MIN_VALUE), value.path("dz").asInt(Integer.MIN_VALUE),
                    text(value, "material", "type"), text(value, "blockData", "block_data"),
                    number(value, "topY", "top_y")));
        }
        return List.copyOf(result);
    }

    /** Source files and retained analyzer status for one exact native ID. */
    public record ModelDefinition(String modelId, Path configFile, Path modelFile,
            String artifactSha256, String configSha256, String modelSha256,
            boolean furniture, boolean solid, boolean floorPlacement,
            double width, double length, double height, double widthOffset, double lengthOffset,
            double heightOffset, ModelBounds modelBounds, boolean ambiguous,
            String analyzerVersion, boolean boundsVerified, TransformExpectation spawnTransform,
            List<SurfaceContact> supportSurface, AdminCrateGateway.GeometryStatus reportStatus,
            double reportResidual, String nativeModelPath) {
        public ModelDefinition {
            modelId = modelId == null ? "" : modelId;
            configFile = configFile == null ? Path.of("") : configFile;
            modelFile = modelFile == null ? Path.of("") : modelFile;
            artifactSha256 = artifactSha256 == null ? "" : artifactSha256;
            configSha256 = configSha256 == null ? "" : configSha256;
            modelSha256 = modelSha256 == null ? "" : modelSha256;
            analyzerVersion = analyzerVersion == null ? "" : analyzerVersion;
            spawnTransform = spawnTransform == null ? TransformExpectation.invalid() : spawnTransform;
            supportSurface = supportSurface == null ? List.of() : List.copyOf(supportSurface);
            reportStatus = reportStatus == null ? AdminCrateGateway.GeometryStatus.UNVERIFIED : reportStatus;
            nativeModelPath = nativeModelPath == null ? "" : nativeModelPath;
        }

        public boolean usable() {
            return !ambiguous && furniture && solid && floorPlacement && boundsVerified
                    && analyzerVersion != null && !analyzerVersion.isBlank() && modelBounds.usable()
                    && validSha(configSha256) && validSha(modelSha256)
                    && spawnTransform.usable() && !supportSurface.isEmpty()
                    && supportSurface.stream().allMatch(SurfaceContact::usable)
                    && (reportStatus == AdminCrateGateway.GeometryStatus.GROUNDED
                    || reportStatus == AdminCrateGateway.GeometryStatus.CONTACTED)
                    && Double.isFinite(reportResidual) && reportResidual >= 0.0D
                    && reportResidual <= EPSILON;
        }
    }

    /** Bounds produced by the retained analyzer after model-parent processing and native transform. */
    public record ModelBounds(double minX, double minY, double minZ,
            double maxX, double maxY, double maxZ) {
        static ModelBounds invalid() {
            return new ModelBounds(Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        }

        public boolean usable() {
            return List.of(minX, minY, minZ, maxX, maxY, maxZ).stream().allMatch(Double::isFinite)
                    && maxX > minX && maxY > minY && maxZ > minZ;
        }
    }

    /** One exact collision-surface block relative to the configured crate anchor. */
    public record SurfaceContact(int dx, int dy, int dz, String material, String blockData, double topY) {
        public SurfaceContact {
            material = material == null ? "" : material;
            blockData = blockData == null ? "" : blockData;
        }

        boolean usable() {
            return dx != Integer.MIN_VALUE && dy != Integer.MIN_VALUE && dz != Integer.MIN_VALUE
                    && !material.isBlank() && !blockData.isBlank() && Double.isFinite(topY);
        }
    }

    /** Exact transform written by native {@code CustomFurniture.spawn(id, block)}. */
    public record TransformExpectation(String api, String entityType, Vector3 anchorOffset,
            Vector3 translation, Vector3 scale, Quaternion leftRotation, Quaternion rightRotation,
            double yaw, double pitch, Integer customModelData) {
        static TransformExpectation invalid() {
            return new TransformExpectation("", "", Vector3.invalid(), Vector3.invalid(), Vector3.invalid(),
                    Quaternion.invalid(), Quaternion.invalid(), Double.NaN, Double.NaN, null);
        }

        boolean usable() {
            return BLOCK_SPAWN_API.equals(api) && !entityType.isBlank() && anchorOffset.usable()
                    && translation.usable() && scale.usable() && scale.x() > 0.0D
                    && scale.y() > 0.0D && scale.z() > 0.0D && leftRotation.usable()
                    && rightRotation.usable() && Double.isFinite(yaw) && Double.isFinite(pitch);
        }

        boolean matches(CratePosition anchor, TransformSnapshot actual) {
            return actual != null && entityType.equalsIgnoreCase(actual.entityType())
                    && close(actual.x(), anchor.x() + anchorOffset.x())
                    && close(actual.y(), anchor.y() + anchorOffset.y())
                    && close(actual.z(), anchor.z() + anchorOffset.z())
                    && close(actual.yaw(), yaw) && close(actual.pitch(), pitch)
                    && translation.close(actual.translation()) && scale.close(actual.scale())
                    && leftRotation.close(actual.leftRotation()) && rightRotation.close(actual.rightRotation())
                    && (customModelData == null || customModelData.equals(actual.customModelData()));
        }

        private static boolean close(double left, double right) {
            return Double.isFinite(left) && Double.isFinite(right) && Math.abs(left - right) <= EPSILON;
        }
    }

    public record Vector3(double x, double y, double z) {
        static Vector3 invalid() { return new Vector3(Double.NaN, Double.NaN, Double.NaN); }
        boolean usable() { return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z); }
        boolean close(Vector3 other) {
            return other != null && Math.abs(x - other.x()) <= EPSILON && Math.abs(y - other.y()) <= EPSILON
                    && Math.abs(z - other.z()) <= EPSILON;
        }
    }

    public record Quaternion(double x, double y, double z, double w) {
        static Quaternion invalid() { return new Quaternion(Double.NaN, Double.NaN, Double.NaN, Double.NaN); }
        boolean usable() { return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z) && Double.isFinite(w); }
        boolean close(Quaternion other) {
            return other != null && Math.abs(x - other.x()) <= EPSILON && Math.abs(y - other.y()) <= EPSILON
                    && Math.abs(z - other.z()) <= EPSILON && Math.abs(w - other.w()) <= EPSILON;
        }
    }

    /** Runtime support result. A custom probe must compare the retained surface exactly. */
    public record SupportObservation(boolean worldPresent, boolean chunkLoaded,
            AdminCrateGateway.GeometryStatus status, double supportResidual) {
        public SupportObservation { Objects.requireNonNull(status, "status"); }
    }

    @FunctionalInterface
    public interface SupportProbe {
        SupportObservation probe(CratePosition position, ModelDefinition definition);

        static SupportProbe bukkit() { return BukkitSupportProbe.INSTANCE; }
    }

    private enum BukkitSupportProbe implements SupportProbe {
        INSTANCE;

        @Override
        public SupportObservation probe(CratePosition position, ModelDefinition definition) {
            org.bukkit.World world = Bukkit.getWorld(position.world());
            if (world == null) return new SupportObservation(false, false,
                    AdminCrateGateway.GeometryStatus.UNVERIFIED, 1.0D);
            double residual = definition.reportResidual();
            boolean mismatch = false;
            for (SurfaceContact expected : definition.supportSurface()) {
                int x = position.x() + expected.dx();
                int y = position.y() + expected.dy();
                int z = position.z() + expected.dz();
                if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                    return new SupportObservation(true, false,
                            AdminCrateGateway.GeometryStatus.UNVERIFIED, 1.0D);
                }
                Block actual = world.getBlockAt(x, y, z);
                if (actual.isEmpty() || actual.isPassable()) {
                    mismatch = true;
                    residual = Math.max(residual, 1.0D);
                    continue;
                }
                double topError = Math.abs(actual.getBoundingBox().getMaxY() - expected.topY());
                residual = Math.max(residual, topError);
                if (topError > EPSILON || !sameMaterial(actual, expected.material())
                        || !actual.getBlockData().getAsString().equalsIgnoreCase(expected.blockData())) {
                    mismatch = true;
                    residual = Math.max(residual, 1.0D);
                }
            }
            return new SupportObservation(true, true,
                    mismatch ? AdminCrateGateway.GeometryStatus.TERRAIN_MISMATCH : definition.reportStatus(), residual);
        }

        private static boolean sameMaterial(Block block, String expected) {
            String normalized = expected.trim().toLowerCase(Locale.ROOT);
            if (normalized.startsWith("minecraft:")) normalized = normalized.substring("minecraft:".length());
            String actual = block.getType().name().toLowerCase(Locale.ROOT);
            return actual.equals(normalized);
        }
    }

    /** Captures the live root transform without adding a compile-time IA dependency. */
    @FunctionalInterface
    public interface TransformProbe {
        Optional<TransformSnapshot> capture(Entity entity);

        static TransformProbe bukkit() { return BukkitTransformProbe.INSTANCE; }
    }

    public record TransformSnapshot(String entityType, double x, double y, double z,
            double yaw, double pitch, Vector3 translation, Vector3 scale,
            Quaternion leftRotation, Quaternion rightRotation, Integer customModelData) { }

    private enum BukkitTransformProbe implements TransformProbe {
        INSTANCE;

        @Override
        public Optional<TransformSnapshot> capture(Entity entity) {
            if (entity == null || !entity.isValid()) return Optional.empty();
            Location location = entity.getLocation();
            try {
                Method getTransformation = entity.getClass().getMethod("getTransformation");
                Object transformation = getTransformation.invoke(entity);
                Vector3 translation = vectorObject(method(transformation, "getTranslation"));
                Vector3 scale = vectorObject(method(transformation, "getScale"));
                Quaternion left = quaternionObject(method(transformation, "getLeftRotation"));
                Quaternion right = quaternionObject(method(transformation, "getRightRotation"));
                if (!translation.usable() || !scale.usable() || !left.usable() || !right.usable()) return Optional.empty();
                Integer cmd = customModelData(entity);
                return Optional.of(new TransformSnapshot(entity.getType().name(), location.getX(), location.getY(),
                        location.getZ(), location.getYaw(), location.getPitch(), translation, scale, left, right, cmd));
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                return Optional.empty();
            }
        }

        private static Object method(Object value, String name) throws ReflectiveOperationException {
            if (value == null) throw new ReflectiveOperationException("missing transformation");
            return value.getClass().getMethod(name).invoke(value);
        }

        private static Vector3 vectorObject(Object value) throws ReflectiveOperationException {
            return new Vector3(component(value, "x"), component(value, "y"), component(value, "z"));
        }

        private static Quaternion quaternionObject(Object value) throws ReflectiveOperationException {
            return new Quaternion(component(value, "x"), component(value, "y"), component(value, "z"),
                    component(value, "w"));
        }

        private static double component(Object value, String name) throws ReflectiveOperationException {
            if (value == null) throw new ReflectiveOperationException("missing transform component");
            try {
                Object result = value.getClass().getMethod(name).invoke(value);
                return ((Number) result).doubleValue();
            } catch (NoSuchMethodException missingMethod) {
                return ((Number) value.getClass().getField(name).get(value)).doubleValue();
            } catch (InvocationTargetException failure) {
                throw new ReflectiveOperationException(failure);
            }
        }

        private static Integer customModelData(Entity entity) {
            try {
                Method getItemStack = entity.getClass().getMethod("getItemStack");
                Object stack = getItemStack.invoke(entity);
                if (stack == null) return null;
                Object meta = stack.getClass().getMethod("getItemMeta").invoke(stack);
                if (meta == null) return null;
                Method has = find(meta.getClass(), "hasCustomModelData");
                Method get = find(meta.getClass(), "getCustomModelData");
                if (has != null && !(Boolean) has.invoke(meta)) return null;
                Object value = get == null ? null : get.invoke(meta);
                return value instanceof Number number ? number.intValue() : null;
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                return null;
            }
        }

        private static Method find(Class<?> type, String name) {
            try { return type.getMethod(name); } catch (NoSuchMethodException ignored) { return null; }
        }
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = new byte[first.length + 1 + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        result[first.length] = 0;
        System.arraycopy(second, 0, result, first.length + 1, second.length);
        return result;
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest.digest(bytes)) result.append(String.format("%02x", value));
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JVM has no SHA-256", impossible);
        }
    }
}
