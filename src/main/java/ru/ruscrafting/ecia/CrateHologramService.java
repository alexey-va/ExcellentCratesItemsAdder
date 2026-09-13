package ru.ruscrafting.ecia;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Vector3f;
import su.nightexpress.excellentcrates.CratesAPI;
import su.nightexpress.excellentcrates.crate.impl.Crate;
import su.nightexpress.excellentcrates.hologram.HologramManager;
import su.nightexpress.excellentcrates.util.pos.WorldPos;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Replaces ExcellentCrates' line-per-entity labels with one compact TextDisplay per block. */
final class CrateHologramService implements AutoCloseable {
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final long RECONCILE_TICKS = 20L;

    private final ArcExcellentCratesPlugin plugin;
    private final NamespacedKey marker;
    private final Map<Anchor, UUID> displays = new HashMap<>();
    private final BukkitTask task;

    private boolean enabled;
    private double heightAboveBlock;
    private float scale;
    private float yaw;
    private float viewRange;

    CrateHologramService(ArcExcellentCratesPlugin plugin) {
        this.plugin = plugin;
        this.marker = new NamespacedKey(plugin, "case_hologram");
        reloadSettings();
        removeOrphans();
        reconcile();
        this.task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::reconcile,
                RECONCILE_TICKS, RECONCILE_TICKS);
    }

    void reload() {
        reloadSettings();
        reconcile();
    }

    private void reloadSettings() {
        enabled = plugin.getConfig().getBoolean("case-holograms.enabled", true);
        heightAboveBlock = bounded(plugin.getConfig().getDouble("case-holograms.height-above-block", 0.18),
                0.0, 2.0, 0.18);
        scale = (float) bounded(plugin.getConfig().getDouble("case-holograms.scale", 2.0),
                0.25, 8.0, 2.0);
        yaw = (float) bounded(plugin.getConfig().getDouble("case-holograms.yaw", 0.0),
                -360.0, 360.0, 0.0);
        viewRange = (float) bounded(plugin.getConfig().getDouble("case-holograms.view-range", 1.0),
                0.1, 16.0, 1.0);
    }

    private void reconcile() {
        if (!CratesAPI.isLoaded()) {
            removeAll();
            return;
        }

        Map<Anchor, Source> desired = new HashMap<>();
        for (Crate crate : CratesAPI.getCrateManager().getCrates()) {
            if (!crate.isHologramEnabled()) continue;
            for (WorldPos position : crate.getBlockPositions()) {
                desired.put(Anchor.of(crate, position), new Source(crate, position));
            }
        }

        if (!enabled) {
            removeAll();
            restoreNative();
            return;
        }

        suppressNative(desired.values());
        Set<Anchor> obsolete = new HashSet<>(displays.keySet());
        obsolete.removeAll(desired.keySet());
        obsolete.forEach(this::remove);
        desired.forEach(this::reconcileDisplay);
    }

    private void suppressNative(Iterable<Source> sources) {
        HologramManager manager = CratesAPI.getPlugin().getHologramManager().orElse(null);
        if (manager == null) return;
        for (Source source : sources) {
            manager.disableBlockHologram(source.crate(), source.position());
        }
    }

    private void reconcileDisplay(Anchor anchor, Source source) {
        World world = source.position().getWorld();
        if (world == null || !source.position().isChunkLoaded()) {
            remove(anchor);
            return;
        }

        TextDisplay display = entity(anchor);
        if (display == null) {
            display = spawn(world, source.position(), anchor);
            displays.put(anchor, display.getUniqueId());
        }
        style(display, source.crate(), source.position());
    }

    private TextDisplay spawn(World world, WorldPos position, Anchor anchor) {
        return world.spawn(displayLocation(position), TextDisplay.class, display -> {
            display.getPersistentDataContainer().set(marker, PersistentDataType.STRING, anchor.key());
            display.setPersistent(false);
            display.setInvulnerable(true);
            display.setSilent(true);
            display.setGravity(false);
        });
    }

    private void style(TextDisplay display, Crate crate, WorldPos position) {
        Location location = displayLocation(position);
        if (!display.getLocation().toVector().equals(location.toVector())) {
            display.teleport(location);
        }
        display.text(MINI_MESSAGE.deserialize(crate.getName()));
        display.setBillboard(Display.Billboard.FIXED);
        display.setRotation(yaw, 0.0F);
        display.setAlignment(TextDisplay.TextAlignment.CENTER);
        display.setLineWidth(512);
        display.setDefaultBackground(false);
        display.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
        display.setTextOpacity((byte) -1);
        display.setShadowed(true);
        display.setSeeThrough(false);
        display.setBrightness(new Display.Brightness(15, 15));
        display.setViewRange(viewRange);
        display.setInterpolationDuration(0);
        display.setTeleportDuration(0);

        Transformation current = display.getTransformation();
        display.setTransformation(new Transformation(
                current.getTranslation(),
                current.getLeftRotation(),
                new Vector3f(scale, scale, scale),
                current.getRightRotation()
        ));
    }

    private Location displayLocation(WorldPos position) {
        Block block = position.toBlock();
        double top = block == null ? position.getY() + 1.0 : block.getBoundingBox().getMaxY();
        return new Location(position.getWorld(), position.getX() + 0.5, top + heightAboveBlock,
                position.getZ() + 0.5, yaw, 0.0F);
    }

    private TextDisplay entity(Anchor anchor) {
        UUID id = displays.get(anchor);
        if (id == null) return null;
        return plugin.getServer().getEntity(id) instanceof TextDisplay display && display.isValid()
                ? display
                : null;
    }

    private void remove(Anchor anchor) {
        UUID id = displays.remove(anchor);
        if (id == null) return;
        var entity = plugin.getServer().getEntity(id);
        if (entity != null) entity.remove();
    }

    private void removeAll() {
        Set.copyOf(displays.keySet()).forEach(this::remove);
    }

    private void removeOrphans() {
        for (World world : plugin.getServer().getWorlds()) {
            for (TextDisplay display : world.getEntitiesByClass(TextDisplay.class)) {
                if (display.getPersistentDataContainer().has(marker, PersistentDataType.STRING)) {
                    display.remove();
                }
            }
        }
    }

    private void restoreNative() {
        if (!CratesAPI.isLoaded()) return;
        HologramManager manager = CratesAPI.getPlugin().getHologramManager().orElse(null);
        if (manager == null) return;
        for (Crate crate : CratesAPI.getCrateManager().getCrates()) {
            if (!crate.isHologramEnabled()) continue;
            for (WorldPos position : crate.getBlockPositions()) {
                manager.enableBlockHologram(crate, position);
            }
        }
    }

    @Override
    public void close() {
        task.cancel();
        removeAll();
        restoreNative();
    }

    private static double bounded(double value, double minimum, double maximum, double fallback) {
        return Double.isFinite(value) && value >= minimum && value <= maximum ? value : fallback;
    }

    private record Source(Crate crate, WorldPos position) {
    }

    private record Anchor(String crateId, String world, int x, int y, int z) {
        static Anchor of(Crate crate, WorldPos position) {
            return new Anchor(crate.getId(), position.getWorldName(), position.getX(), position.getY(), position.getZ());
        }

        String key() {
            return crateId + "|" + world + "|" + x + "|" + y + "|" + z;
        }
    }
}
