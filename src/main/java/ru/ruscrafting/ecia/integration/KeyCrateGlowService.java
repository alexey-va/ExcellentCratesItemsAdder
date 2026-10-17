package ru.ruscrafting.ecia.integration;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import ru.ruscrafting.ecia.ArcExcellentCratesPlugin;
import ru.ruscrafting.ecia.CrateAmbientEffectService;
import su.nightexpress.excellentcrates.util.pos.WorldPos;
import su.nightexpress.excellentcrates.CratesAPI;
import su.nightexpress.excellentcrates.crate.impl.Crate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Iterator;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.time.ZoneId;

/** Viewer-scoped outline for nearby crates matching the key in the main hand. */
final class KeyCrateGlowService implements AutoCloseable {
    private static final long DEFAULT_REFRESH_TICKS = 5L;
    private static final double DEFAULT_RANGE = 48.0D;
    private static final float GLOW_SHELL_SCALE = 1.01F;
    private static final Display.Brightness GLOW_BRIGHTNESS = new Display.Brightness(15, 15);
    private static final Color OUTLINE_COLOR = Color.fromRGB(255, 213, 103);

    private final ArcExcellentCratesPlugin plugin;
    private final NativeSeasonKeys keys;
    private final ItemsAdderFurnitureAccess furniture;
    private final NamespacedKey marker;
    private Map<MarkerKey, UUID> markers = new ConcurrentHashMap<>();
    private final BukkitTask task;
    private Map<String, ManagedCratesSettings.CaseSettings> cases = Map.of();
    private ZoneId freeOpeningZone = ZoneId.of("Europe/Moscow");
    private boolean closed;
    private boolean initialized;
    private boolean pending;
    private long generation;

    KeyCrateGlowService(ArcExcellentCratesPlugin plugin, NativeSeasonKeys keys,
            ItemsAdderFurnitureAccess furniture) {
        this.plugin = plugin;
        this.keys = keys;
        this.furniture = furniture;
        marker = new NamespacedKey(plugin, "key_crate_glow");
        CrateAmbientEffectService.cleanLoadedDisplays(plugin, () -> !closed, entity -> {
            if ((entity instanceof ItemDisplay || entity instanceof BlockDisplay)
                    && entity.getPersistentDataContainer().has(marker, PersistentDataType.BYTE)) entity.remove();
        }).whenComplete((ignored, failure) -> {
            if (failure != null) CrateAmbientEffectService.reportWorkFailure(plugin, "key glow orphan cleanup", failure);
            else if (!closed) { initialized = true; reconcile(); }
        });
        long refreshTicks = Math.max(2L,
                plugin.getConfig().getLong("case-key-glow.refresh-ticks", DEFAULT_REFRESH_TICKS));
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::reconcile,
                refreshTicks, refreshTicks);
    }

    void configure(Map<String, ManagedCratesSettings.CaseSettings> cases) {
        configure(cases, ZoneId.of("Europe/Moscow"));
    }

    void configure(Map<String, ManagedCratesSettings.CaseSettings> cases, ZoneId zone) {
        if (closed) return;
        this.cases = cases; // Published immutable settings; no native item or entity is retained here.
        this.freeOpeningZone = zone;
        generation++;
        pending = false;
        reconcile();
    }

    private void reconcile() {
        if (closed || !initialized || pending) return;
        pending = true;
        long expected = generation;
        var work = new Supplier<Boolean>() {
            final boolean active = plugin.getConfig().getBoolean("case-key-glow.enabled", true) && CratesAPI.isLoaded();
            final Iterator<ManagedCratesSettings.CaseSettings> configured = active ? cases.values().iterator() : Collections.emptyIterator();
            final List<KeyCrateGlowPlan.Target> targets = new ArrayList<>();
            final Set<MarkerKey> desired = new HashSet<>();
            final double range = Math.max(1.0D, plugin.getConfig().getDouble("case-key-glow.range", DEFAULT_RANGE));
            Iterator<WorldPos> positions = Collections.emptyIterator();
            Iterator<? extends Player> players;
            Iterator<MarkerKey> stale;
            Crate crate;
            ManagedCratesSettings.CaseSettings currentCase;
            NativeSeasonKeys.KeyCost cost;
            Player player;
            int targetIndex;

            @Override public Boolean get() {
                if (stale != null) {
                    if (!stale.hasNext()) return true;
                    MarkerKey key = stale.next();
                    if (!desired.contains(key)) remove(key);
                } else if (players != null) {
                    if (player != null && targetIndex < targets.size()) {
                        KeyCrateGlowPlan.Target target = targets.get(targetIndex++);
                        if (!matches(player, target, range)) return false;
                        MarkerKey key = new MarkerKey(player.getUniqueId(), target);
                        desired.add(key);
                        UUID existing = markers.get(key);
                        Entity entity = existing == null ? null : plugin.getServer().getEntity(existing);
                        if (entity == null || !entity.isValid()) { remove(key); spawn(key, range); }
                    } else if (players.hasNext()) { player = players.next(); targetIndex = 0; }
                    else stale = markers.keySet().iterator();
                } else if (positions.hasNext()) {
                    WorldPos position = positions.next();
                    targets.add(new KeyCrateGlowPlan.Target(crate.getId(), cost.keyId(), NativeSeasonKeys.LEGACY_SEASON,
                            position.getWorldName(), position.getX(), position.getY(), position.getZ(), currentCase.freeOpenPeriod()));
                } else if (configured.hasNext()) {
                    currentCase = configured.next();
                    crate = CratesAPI.getCrateManager().getCrateById(currentCase.crateId());
                    if (crate != null) { cost = keys.cost(crate); positions = crate.getBlockPositions().iterator(); }
                } else players = active && !targets.isEmpty()
                        ? plugin.getServer().getOnlinePlayers().iterator() : Collections.emptyIterator();
                return false;
            }
        };
        plugin.runtime().nativeWork().submit(() -> !closed && generation == expected, work::get)
                .whenComplete((ignored, failure) -> {
                    if (generation == expected) pending = false;
                    if (failure != null) CrateAmbientEffectService.reportWorkFailure(plugin, "key glow reconciliation", failure);
                });
    }

    private boolean matches(Player player, KeyCrateGlowPlan.Target target, double range) {
        if (!player.isOnline()) return false;
        NativeSeasonKeys.KeyIdentity key = keys.identifyForPlayer(player.getInventory().getItemInMainHand(),
                player.getUniqueId(), freeOpeningZone, cases).orElse(null);
        if (key == null || key.crateId() != null && !key.crateId().equals(target.crateId())) return false;
        Location location = player.getLocation();
        return !KeyCrateGlowPlan.select(new KeyCrateGlowPlan.HeldKey(key.keyId(), key.season()),
                player.getWorld().getName(), location.getX(), location.getY(), location.getZ(), range, List.of(target)).isEmpty();
    }

    private void spawn(MarkerKey key, double range) {
        Player viewer = plugin.getServer().getPlayer(key.viewer());
        World world = plugin.getServer().getWorld(key.target().world());
        if (viewer == null || !viewer.isOnline() || world == null || viewer.getWorld() != world
                || !world.isChunkLoaded(key.target().x() >> 4, key.target().z() >> 4)) return;
        Block block = world.getBlockAt(key.target().x(), key.target().y(), key.target().z());
        ItemsAdderFurnitureAccess.Instance furnitureInstance = furniture.at(block).orElse(null);
        Display display;
        if (furnitureInstance == null) {
            display = cloneBlock(block, (float) range);
        } else if (furnitureInstance.entity() instanceof ItemDisplay source) {
            display = cloneFurniture(source, (float) range);
        } else {
            return;
        }
        if (display == null) return;
        markers.put(key, display.getUniqueId());
        viewer.showEntity(plugin, display);
    }

    private ItemDisplay cloneFurniture(ItemDisplay source, float range) {
        return source.getWorld().spawn(source.getLocation(), ItemDisplay.class, display -> {
            configure(display, range);
            display.setItemStack(source.getItemStack().clone());
            display.setItemDisplayTransform(source.getItemDisplayTransform());
            display.setBillboard(source.getBillboard());
            display.setTransformation(inflateCentered(source.getTransformation()));
            display.setDisplayWidth(source.getDisplayWidth());
            display.setDisplayHeight(source.getDisplayHeight());
        });
    }

    private BlockDisplay cloneBlock(Block block, float range) {
        if (block.getType() == Material.AIR) return null;
        return block.getWorld().spawn(block.getLocation(), BlockDisplay.class, display -> {
            configure(display, range);
            display.setBlock(block.getBlockData().clone());
            display.setTransformation(blockShellTransformation());
            display.setDisplayWidth(1.0F);
            display.setDisplayHeight(1.0F);
        });
    }

    private void configure(Display display, float range) {
        display.getPersistentDataContainer().set(marker, PersistentDataType.BYTE, (byte) 1);
        display.setPersistent(false);
        display.setVisibleByDefault(false);
        display.setInvulnerable(true);
        display.setSilent(true);
        display.setGravity(false);
        display.setGlowing(true);
        display.setGlowColorOverride(OUTLINE_COLOR);
        display.setBrightness(glowBrightness());
        display.setViewRange(range);
        display.setInterpolationDuration(0);
        display.setTeleportDuration(0);
        display.setShadowRadius(0.0F);
        display.setShadowStrength(0.0F);
    }

    static Transformation inflateCentered(Transformation source) {
        Vector3f scale = new Vector3f(source.getScale()).mul(GLOW_SHELL_SCALE);
        return new Transformation(
                new Vector3f(source.getTranslation()),
                new Quaternionf(source.getLeftRotation()),
                scale,
                new Quaternionf(source.getRightRotation()));
    }

    static Transformation blockShellTransformation() {
        float outset = (GLOW_SHELL_SCALE - 1.0F) * 0.5F;
        return new Transformation(
                new Vector3f(-outset, -outset, -outset),
                new Quaternionf(),
                new Vector3f(GLOW_SHELL_SCALE, GLOW_SHELL_SCALE, GLOW_SHELL_SCALE),
                new Quaternionf());
    }

    static Display.Brightness glowBrightness() {
        return GLOW_BRIGHTNESS;
    }

    private void remove(MarkerKey key) {
        UUID id = markers.remove(key);
        if (id == null) return;
        Entity entity = plugin.getServer().getEntity(id);
        if (entity != null) entity.remove();
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        generation++;
        task.cancel();
        cases = Map.of();
        var server = plugin.getServer();
        Iterator<UUID> detached = markers.values().iterator();
        markers = new ConcurrentHashMap<>();
        plugin.runtime().submitNativeCleanup("key-glow", () -> {
            if (!detached.hasNext()) return true;
            Entity entity = server.getEntity(detached.next());
            if (entity != null) entity.remove();
            detached.remove();
            return false;
        });
    }

    private record MarkerKey(UUID viewer, KeyCrateGlowPlan.Target target) {
    }
}
