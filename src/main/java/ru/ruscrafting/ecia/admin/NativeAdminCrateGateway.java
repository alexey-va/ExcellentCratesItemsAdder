package ru.ruscrafting.ecia.admin;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.persistence.PersistentDataType;
import ru.ruscrafting.ecia.CratePosition;
import su.nightexpress.excellentcrates.crate.CrateManager;
import su.nightexpress.excellentcrates.crate.impl.Crate;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Paper-thread gateway backed by the native ExcellentCrates and ItemsAdder
 * APIs. The adapter is deliberately kept in this package so the rest of the
 * addon does not acquire an ItemsAdder compile-time dependency.
 *
 * <p>The default constructor resolves the exact public
 * {@code dev.lone.itemsadder.api.CustomFurniture} methods once. It does not
 * synthesize display entities and it does not scan or mutate unloaded chunks.
 * A missing ItemsAdder class/plugin leaves the adapter available for
 * inspection of marker data, while every spawn is rejected.</p>
 *
 * <p>The gateway must only be called on Paper's primary thread. The parent
 * command owns edit-mode and permission checks. The supplied grounding
 * provider must be built from the active IA configuration/model artifacts;
 * returning {@link AdminCrateGateway.GeometryStatus#UNVERIFIED} is the safe
 * default and makes repair fail closed.</p>
 */
public final class NativeAdminCrateGateway implements AdminCrateGateway {
    private static final String ITEMSADDER_PLUGIN = "ItemsAdder";
    private static final double CARRIER_RADIUS = 2.5D;
    private static final Set<EntityType> KNOWN_CARRIERS = Set.of(
            EntityType.ITEM_DISPLAY,
            EntityType.BLOCK_DISPLAY,
            EntityType.INTERACTION,
            EntityType.ARMOR_STAND
    );

    private static final NamespacedKey SIMPLE_ITEM_KEY =
            new NamespacedKey("itemsadder", "placeable_entity_item");
    private static final NamespacedKey SIMPLE_BEHAVIOUR_KEY =
            new NamespacedKey("itemsadder", "placeable_behaviour_type");
    private static final NamespacedKey COMPLEX_FURNITURE_KEY =
            new NamespacedKey("itemsadder", "complex_furniture");

    private final CrateManager crateManager;
    private final FurnitureBridge furniture;
    private final GroundingProvider grounding;

    /**
     * Production constructor. It uses a cached reflection binding because the
     * addon deliberately compiles without the optional ItemsAdder API jar.
     */
    public NativeAdminCrateGateway(CrateManager crateManager, GroundingProvider grounding) {
        this(crateManager, ReflectiveFurnitureBridge.create(), grounding);
    }

    /**
     * Injection constructor for the parent integration and unit tests. The
     * bridge is expected to call native IA API methods and to return the root
     * entity of the furniture instance.
     */
    public NativeAdminCrateGateway(CrateManager crateManager, FurnitureBridge furniture,
            GroundingProvider grounding) {
        this.crateManager = Objects.requireNonNull(crateManager, "crateManager");
        this.furniture = Objects.requireNonNull(furniture, "furniture");
        this.grounding = Objects.requireNonNull(grounding, "grounding");
    }

    @Override
    public Optional<AdminCrateDescriptor> findCrate(String crateId) {
        Crate crate = crateManager.getCrateById(crateId);
        return crate == null ? Optional.empty() : Optional.of(ExcellentCratesDescriptorFactory.from(crate));
    }

    @Override
    public AnchorObservation observe(CratePosition position, String expectedModel) {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(expectedModel, "expectedModel");

        org.bukkit.World world = Bukkit.getWorld(position.world());
        if (world == null) {
            return unavailable(position, AdminCrateGateway.WorldStatus.MISSING,
                    AdminCrateGateway.ChunkStatus.UNKNOWN, expectedModel);
        }

        int chunkX = position.x() >> 4;
        int chunkZ = position.z() >> 4;
        if (!world.isChunkLoaded(chunkX, chunkZ)) {
            return unavailable(position, AdminCrateGateway.WorldStatus.PRESENT,
                    AdminCrateGateway.ChunkStatus.UNLOADED, expectedModel);
        }

        Block anchor = world.getBlockAt(position.x(), position.y(), position.z());
        CarrierSnapshot carriers = inspectCarriers(world, anchor, position);
        FurnitureObservation atAnchorObservation = singleObservation(carriers.atAnchor());
        AdminCrateGateway.FurnitureIdentity actual = atAnchorObservation == null
                ? null : atAnchorObservation.identity();
        AdminCrateGateway.TechnicalBlock technical = technicalBlock(anchor, actual);
        AdminCrateGateway.GroundingEvidence geometry = safeGrounding(position, expectedModel,
                atAnchorObservation);
        return new AnchorObservation(position, AdminCrateGateway.WorldStatus.PRESENT,
                AdminCrateGateway.ChunkStatus.LOADED, technical, actual,
                carriers.count(), geometry);
    }

    @Override
    public SpawnReceipt spawnExpectedFurniture(CratePosition position, String expectedModel) {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(expectedModel, "expectedModel");
        org.bukkit.World world = Bukkit.getWorld(position.world());
        if (world == null) {
            return new SpawnReceipt(SpawnReceipt.Outcome.REJECTED, "World is not present: " + position.world());
        }
        if (!world.isChunkLoaded(position.x() >> 4, position.z() >> 4)) {
            return new SpawnReceipt(SpawnReceipt.Outcome.REJECTED, "Chunk is not loaded");
        }

        Block anchor = world.getBlockAt(position.x(), position.y(), position.z());
        // The service performs the complete preflight. Repeat the exact empty
        // block/carrier guard here so direct gateway callers cannot overwrite a
        // foreign block or entity by accident.
        CarrierSnapshot before = inspectCarriers(world, anchor, position);
        if (!anchor.isEmpty() || before.count() != 0) {
            return new SpawnReceipt(SpawnReceipt.Outcome.REJECTED,
                    "Configured anchor is occupied by a block or carrier");
        }
        return furniture.spawn(expectedModel, anchor);
    }

    @Override
    public void rebuildNativeBinding(String crateId) {
        Crate crate = crateManager.getCrateById(crateId);
        if (crate == null) {
            throw new IllegalArgumentException("Unknown crate: " + crateId);
        }
        // These are the native EC 6.6.1 operations used when a crate position
        // is edited. Hologram recreation is intentionally last, after the
        // position index has been rebuilt.
        crateManager.removeCratePositions(crate);
        crateManager.addCratePositions(crate);
        crate.recreateHologram();
    }

    private CarrierSnapshot inspectCarriers(org.bukkit.World world, Block anchor, CratePosition position) {
        Location center = anchor.getLocation().add(0.5D, 0.5D, 0.5D);
        List<Entity> nearby = new ArrayList<>(world.getNearbyEntities(
                center, CARRIER_RADIUS, CARRIER_RADIUS, CARRIER_RADIUS));
        Map<java.util.UUID, FurnitureObservation> recognized = new HashMap<>();
        Map<java.util.UUID, Boolean> carriers = new HashMap<>();

        for (Entity entity : nearby) {
            Optional<FurnitureObservation> resolved = inspectEntity(entity);
            boolean carrier = resolved.isPresent() || KNOWN_CARRIERS.contains(entity.getType())
                    || hasFurnitureMarker(entity);
            if (!carrier) continue;
            carriers.put(entity.getUniqueId(), Boolean.TRUE);
            resolved.ifPresent(value -> recognized.put(entity.getUniqueId(), value));
        }

        // CustomFurniture.byAlreadySpawned(Block) is the authoritative native
        // anchor lookup. Include its root even if a provider implementation
        // uses an unusual entity bounding box that excludes the radius query.
        Optional<FurnitureObservation> byBlock = inspectBlock(anchor);
        byBlock.filter(value -> atExactAnchor(value.entity(), position)).ifPresent(value -> {
            recognized.put(value.entity().getUniqueId(), value);
            carriers.put(value.entity().getUniqueId(), Boolean.TRUE);
        });

        List<FurnitureObservation> atAnchor = new ArrayList<>();
        for (FurnitureObservation value : recognized.values()) {
            if (atExactAnchor(value.entity(), position)) atAnchor.add(value);
        }
        // Marker-only observations have no reliable root offset but still
        // count as foreign carriers. They are intentionally excluded from the
        // actual-model set so an unresolved marker cannot authorize repair.
        return new CarrierSnapshot(carriers.size(), atAnchor);
    }

    private Optional<FurnitureObservation> inspectEntity(Entity entity) {
        Optional<FurnitureObservation> nativeValue = furniture.byAlreadySpawned(entity);
        if (nativeValue.isPresent()) return nativeValue;
        return markerObservation(entity);
    }

    private Optional<FurnitureObservation> inspectBlock(Block block) {
        Optional<FurnitureObservation> nativeValue = furniture.byAlreadySpawned(block);
        if (nativeValue.isPresent()) return nativeValue;
        // IA marker data belongs to entities, not blocks. Do not infer an
        // anchor model from an arbitrary non-air block.
        return Optional.empty();
    }

    private Optional<FurnitureObservation> markerObservation(Entity entity) {
        var pdc = entity.getPersistentDataContainer();
        String behaviour = pdc.get(SIMPLE_BEHAVIOUR_KEY, PersistentDataType.STRING);
        String simpleId = pdc.get(SIMPLE_ITEM_KEY, PersistentDataType.STRING);
        String complexId = pdc.get(COMPLEX_FURNITURE_KEY, PersistentDataType.STRING);
        String id = "furniture".equals(behaviour) ? simpleId : null;
        if (id == null || id.isBlank()) id = complexId;
        if (id == null || id.isBlank()) return Optional.empty();
        return Optional.of(new FurnitureObservation(
                new AdminCrateGateway.FurnitureIdentity(id.trim(), "", entity.getType().name()), entity));
    }

    private static boolean hasFurnitureMarker(Entity entity) {
        var pdc = entity.getPersistentDataContainer();
        String behaviour = pdc.get(SIMPLE_BEHAVIOUR_KEY, PersistentDataType.STRING);
        String simpleId = pdc.get(SIMPLE_ITEM_KEY, PersistentDataType.STRING);
        String complexId = pdc.get(COMPLEX_FURNITURE_KEY, PersistentDataType.STRING);
        return "furniture".equals(behaviour) || (simpleId != null && !simpleId.isBlank())
                || (complexId != null && !complexId.isBlank());
    }

    private static FurnitureObservation singleObservation(List<FurnitureObservation> observations) {
        return observations.size() == 1 ? observations.getFirst() : null;
    }

    private static AdminCrateGateway.TechnicalBlock technicalBlock(Block block,
            AdminCrateGateway.FurnitureIdentity actual) {
        String material = block.getType().name();
        String data = block.getBlockData().getAsString();
        if (block.isEmpty()) {
            return new AdminCrateGateway.TechnicalBlock(material, data,
                    AdminCrateGateway.TechnicalBlock.Kind.AIR, "");
        }
        if (block.getType() == Material.BARRIER) {
            return new AdminCrateGateway.TechnicalBlock(material, data,
                    actual == null
                            ? AdminCrateGateway.TechnicalBlock.Kind.UNKNOWN
                            : AdminCrateGateway.TechnicalBlock.Kind.EXPECTED_HITBOX,
                    actual == null ? "" : actual.namespacedId());
        }
        return new AdminCrateGateway.TechnicalBlock(material, data,
                AdminCrateGateway.TechnicalBlock.Kind.FOREIGN, "");
    }

    private AdminCrateGateway.GroundingEvidence safeGrounding(CratePosition position, String expectedModel,
            FurnitureObservation actual) {
        try {
            AdminCrateGateway.GroundingEvidence value = grounding.resolve(position, expectedModel, actual);
            return value == null ? unverified(expectedModel) : value;
        } catch (RuntimeException ignored) {
            return unverified(expectedModel);
        }
    }

    private static AdminCrateGateway.GroundingEvidence unverified(String expectedModel) {
        return new AdminCrateGateway.GroundingEvidence(
                AdminCrateGateway.GeometryStatus.UNVERIFIED, expectedModel, "", 1.0D);
    }

    private static AnchorObservation unavailable(CratePosition position,
            AdminCrateGateway.WorldStatus worldStatus, AdminCrateGateway.ChunkStatus chunkStatus,
            String expectedModel) {
        AdminCrateGateway.TechnicalBlock unknown = new AdminCrateGateway.TechnicalBlock(
                "UNKNOWN", "", AdminCrateGateway.TechnicalBlock.Kind.UNKNOWN, "");
        return new AnchorObservation(position, worldStatus, chunkStatus, unknown, null, 0,
                unverified(expectedModel));
    }

    private static boolean atExactAnchor(Entity entity, CratePosition position) {
        Location location = entity.getLocation();
        return location.getWorld() != null
                && position.world().equals(location.getWorld().getName())
                && location.getBlockX() == position.x()
                && location.getBlockY() == position.y()
                && location.getBlockZ() == position.z();
    }

    private record CarrierSnapshot(int count, List<FurnitureObservation> atAnchor) {
        private CarrierSnapshot {
            atAnchor = List.copyOf(atAnchor);
        }
    }

    /** Root entity and native model identity returned by ItemsAdder. */
    public record FurnitureObservation(AdminCrateGateway.FurnitureIdentity identity, Entity entity) {
        public FurnitureObservation {
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(entity, "entity");
        }
    }

    /**
     * Isolated native IA boundary. Implementations must use
     * {@code CustomFurniture.byAlreadySpawned(...)} and
     * {@code CustomFurniture.spawn(...)}; creating replacement Bukkit display
     * entities is outside this contract.
     */
    public interface FurnitureBridge {
        boolean isAvailable();

        Optional<FurnitureObservation> byAlreadySpawned(Entity entity);

        Optional<FurnitureObservation> byAlreadySpawned(Block block);

        SpawnReceipt spawn(String namespacedId, Block block);
    }

    /** Supplies geometry verified against the active IA model/config artifact. */
    @FunctionalInterface
    public interface GroundingProvider {
        AdminCrateGateway.GroundingEvidence resolve(CratePosition position, String expectedModel);

        /**
         * Optional native readback context. Implementations that validate a
         * retained ItemDisplay transform override this method; simple probes
         * retain the two-argument contract and are still safe for empty targets.
         */
        default AdminCrateGateway.GroundingEvidence resolve(CratePosition position, String expectedModel,
                FurnitureObservation actual) {
            return resolve(position, expectedModel);
        }
    }

    /**
     * Cached reflection implementation of the stable public IA 4.x simple
     * furniture API. Resolution happens once at construction, never per
     * inspection request. If the optional API is absent, calls fail closed.
     */
    public static final class ReflectiveFurnitureBridge implements FurnitureBridge {
        private final Method byEntity;
        private final Method byBlock;
        private final Method spawn;
        private final Method getEntity;
        private final Method getNamespacedId;
        private final Method getModelPath;
        private final String bindingFailure;

        private ReflectiveFurnitureBridge(Method byEntity, Method byBlock, Method spawn,
                Method getEntity, Method getNamespacedId, Method getModelPath, String bindingFailure) {
            this.byEntity = byEntity;
            this.byBlock = byBlock;
            this.spawn = spawn;
            this.getEntity = getEntity;
            this.getNamespacedId = getNamespacedId;
            this.getModelPath = getModelPath;
            this.bindingFailure = bindingFailure;
        }

        public static ReflectiveFurnitureBridge create() {
            try {
                Class<?> api = Class.forName("dev.lone.itemsadder.api.CustomFurniture");
                return new ReflectiveFurnitureBridge(
                        api.getMethod("byAlreadySpawned", Entity.class),
                        api.getMethod("byAlreadySpawned", Block.class),
                        api.getMethod("spawn", String.class, Block.class),
                        api.getMethod("getEntity"),
                        api.getMethod("getNamespacedID"),
                        findMethod(api, "getModelPath"),
                        "");
            } catch (ReflectiveOperationException | LinkageError failure) {
                return new ReflectiveFurnitureBridge(null, null, null, null, null, null,
                        failure.getClass().getSimpleName() + ": " + String.valueOf(failure.getMessage()));
            }
        }

        @Override
        public boolean isAvailable() {
            if (bindingFailure != null && !bindingFailure.isEmpty()) return false;
            try {
                return Bukkit.getPluginManager().isPluginEnabled(ITEMSADDER_PLUGIN);
            } catch (RuntimeException ignored) {
                return false;
            }
        }

        @Override
        public Optional<FurnitureObservation> byAlreadySpawned(Entity entity) {
            return invokeLookup(byEntity, entity);
        }

        @Override
        public Optional<FurnitureObservation> byAlreadySpawned(Block block) {
            return invokeLookup(byBlock, block);
        }

        @Override
        public SpawnReceipt spawn(String namespacedId, Block block) {
            if (!isAvailable()) {
                return new SpawnReceipt(SpawnReceipt.Outcome.REJECTED,
                        bindingFailure == null || bindingFailure.isBlank()
                                ? "ItemsAdder is not enabled" : "ItemsAdder API unavailable: " + bindingFailure);
            }
            try {
                Object furniture = spawn.invoke(null, namespacedId, block);
                if (furniture == null) {
                    return new SpawnReceipt(SpawnReceipt.Outcome.FAILED,
                            "ItemsAdder rejected unknown/non-furniture model " + namespacedId);
                }
                Optional<FurnitureObservation> observation = observation(furniture);
                return observation.isPresent()
                        ? new SpawnReceipt(SpawnReceipt.Outcome.SPAWNED, "Native ItemsAdder furniture spawned")
                        : new SpawnReceipt(SpawnReceipt.Outcome.UNKNOWN,
                                "ItemsAdder returned furniture without a readable root identity");
            } catch (InvocationTargetException failure) {
                Throwable cause = failure.getCause() == null ? failure : failure.getCause();
                return new SpawnReceipt(SpawnReceipt.Outcome.FAILED,
                        "ItemsAdder spawn failed: " + String.valueOf(cause.getMessage()));
            } catch (ReflectiveOperationException | RuntimeException failure) {
                return new SpawnReceipt(SpawnReceipt.Outcome.FAILED,
                        "ItemsAdder spawn failed: " + String.valueOf(failure.getMessage()));
            }
        }

        private Optional<FurnitureObservation> invokeLookup(Method lookup, Object argument) {
            if (!isAvailable() || lookup == null) return Optional.empty();
            try {
                Object furniture = lookup.invoke(null, argument);
                return furniture == null ? Optional.empty() : observation(furniture);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                return Optional.empty();
            }
        }

        private Optional<FurnitureObservation> observation(Object furniture) {
            try {
                Object entityValue = getEntity.invoke(furniture);
                if (!(entityValue instanceof Entity entity) || !entity.isValid()) return Optional.empty();
                Object idValue = getNamespacedId.invoke(furniture);
                if (!(idValue instanceof String id) || id.isBlank()) return Optional.empty();
                Object modelValue = getModelPath == null ? null : getModelPath.invoke(furniture);
                String modelPath = modelValue instanceof String path ? path : "";
                return Optional.of(new FurnitureObservation(
                        new AdminCrateGateway.FurnitureIdentity(id.trim(), modelPath, entity.getType().name()),
                        entity));
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                return Optional.empty();
            }
        }

        private static Method findMethod(Class<?> type, String name) {
            try {
                return type.getMethod(name);
            } catch (NoSuchMethodException ignored) {
                return null;
            }
        }
    }
}
