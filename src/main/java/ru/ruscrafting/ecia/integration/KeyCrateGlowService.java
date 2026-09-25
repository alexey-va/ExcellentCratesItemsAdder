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
import su.nightexpress.excellentcrates.CratesAPI;
import su.nightexpress.excellentcrates.crate.impl.Crate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
    private final Map<MarkerKey, UUID> markers = new HashMap<>();
    private final BukkitTask task;
    private List<KeyCrateGlowPlan.Target> targets = List.of();
    private Map<String, ManagedCratesSettings.CaseSettings> cases = Map.of();
    private ZoneId freeOpeningZone = ZoneId.of("Europe/Moscow");
    private boolean closed;

    KeyCrateGlowService(ArcExcellentCratesPlugin plugin, NativeSeasonKeys keys,
            ItemsAdderFurnitureAccess furniture) {
        this.plugin = plugin;
        this.keys = keys;
        this.furniture = furniture;
        marker = new NamespacedKey(plugin, "key_crate_glow");
        removeOrphans();
        long refreshTicks = Math.max(2L,
                plugin.getConfig().getLong("case-key-glow.refresh-ticks", DEFAULT_REFRESH_TICKS));
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::reconcile,
                refreshTicks, refreshTicks);
    }

    void configure(Map<String, ManagedCratesSettings.CaseSettings> cases) {
        configure(cases, ZoneId.of("Europe/Moscow"));
    }

    void configure(Map<String, ManagedCratesSettings.CaseSettings> cases, ZoneId zone) {
        removeAll();
        targets = List.of();
        this.cases = Map.copyOf(cases);
        this.freeOpeningZone = zone;
        if (closed || !CratesAPI.isLoaded()) return;
        targets = buildTargets();
        reconcile();
    }

    private List<KeyCrateGlowPlan.Target> buildTargets() {
        List<KeyCrateGlowPlan.Target> next = new ArrayList<>();
        for (ManagedCratesSettings.CaseSettings configured : cases.values()) {
            Crate crate = CratesAPI.getCrateManager().getCrateById(configured.crateId());
            if (crate == null) continue;
            NativeSeasonKeys.KeyCost cost = keys.cost(crate);
            crate.getBlockPositions().forEach(position -> next.add(new KeyCrateGlowPlan.Target(
                    crate.getId(), cost.keyId(), NativeSeasonKeys.LEGACY_SEASON, position.getWorldName(),
                    position.getX(), position.getY(), position.getZ(), configured.freeOpenPeriod())));
        }
        return List.copyOf(next);
    }

    private void reconcile() {
        if (closed || !plugin.getConfig().getBoolean("case-key-glow.enabled", true) || !CratesAPI.isLoaded()) {
            removeAll();
            return;
        }
        // Native editor/reload changes positions without notifying this configure-only addon.
        targets = buildTargets();
        if (targets.isEmpty()) {
            removeAll();
            return;
        }
        markers.entrySet().removeIf(entry -> {
            Entity entity = plugin.getServer().getEntity(entry.getValue());
            return entity == null || !entity.isValid();
        });

        double range = Math.max(1.0D, plugin.getConfig().getDouble("case-key-glow.range", DEFAULT_RANGE));
        Set<MarkerKey> desired = new HashSet<>();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            Location location = player.getLocation();
            NativeSeasonKeys.KeyIdentity key = keys.identifyForPlayer(player.getInventory().getItemInMainHand(),
                    player.getUniqueId(), freeOpeningZone, cases).orElse(null);
            if (key != null) {
                KeyCrateGlowPlan.HeldKey held = new KeyCrateGlowPlan.HeldKey(key.keyId(), key.season());
                List<KeyCrateGlowPlan.Target> candidates = key.crateId() == null ? targets
                        : targets.stream().filter(target -> target.crateId().equals(key.crateId())).toList();
                for (KeyCrateGlowPlan.Target target : KeyCrateGlowPlan.select(held, player.getWorld().getName(),
                        location.getX(), location.getY(), location.getZ(), range, candidates)) {
                    desired.add(new MarkerKey(player.getUniqueId(), target));
                }
            }
        }

        Set<MarkerKey> obsolete = new HashSet<>(markers.keySet());
        obsolete.removeAll(desired);
        obsolete.forEach(this::remove);
        desired.stream().filter(key -> !markers.containsKey(key)).forEach(key -> spawn(key, range));
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

    private void removeAll() {
        Set.copyOf(markers.keySet()).forEach(this::remove);
    }

    private void removeOrphans() {
        for (World world : plugin.getServer().getWorlds()) {
            for (ItemDisplay display : world.getEntitiesByClass(ItemDisplay.class)) {
                if (display.getPersistentDataContainer().has(marker, PersistentDataType.BYTE)) display.remove();
            }
            for (BlockDisplay display : world.getEntitiesByClass(BlockDisplay.class)) {
                if (display.getPersistentDataContainer().has(marker, PersistentDataType.BYTE)) display.remove();
            }
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        task.cancel();
        targets = List.of();
        cases = Map.of();
        removeAll();
    }

    private record MarkerKey(UUID viewer, KeyCrateGlowPlan.Target target) {
    }
}
