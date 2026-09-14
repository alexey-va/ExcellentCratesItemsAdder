package ru.ruscrafting.ecia;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import su.nightexpress.excellentcrates.CratesAPI;
import su.nightexpress.excellentcrates.crate.impl.Crate;
import su.nightexpress.excellentcrates.util.pos.WorldPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Four small BlockDisplay runes orbit every placed crate while it is idle. */
final class CrateAmbientEffectService implements AutoCloseable {
    private static final long RECONCILE_TICKS = 40L;
    private static final long ANIMATION_TICKS = 4L;
    private static final int SHARDS = 4;

    private final ArcExcellentCratesPlugin plugin;
    private final NamespacedKey marker;
    private final Map<Anchor, Orbit> orbits = new HashMap<>();
    private final BukkitTask reconcileTask;
    private final BukkitTask animationTask;
    private long frame;

    CrateAmbientEffectService(ArcExcellentCratesPlugin plugin) {
        this.plugin = plugin;
        this.marker = new NamespacedKey(plugin, "case_ambient");
        removeOrphans();
        reconcile();
        reconcileTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::reconcile,
                RECONCILE_TICKS, RECONCILE_TICKS);
        animationTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::animate,
                ANIMATION_TICKS, ANIMATION_TICKS);
    }

    void refresh() {
        reconcile();
    }

    private void reconcile() {
        if (!plugin.getConfig().getBoolean("case-ambient.enabled", true) || !CratesAPI.isLoaded()) {
            removeAll();
            return;
        }
        Set<Anchor> desired = new HashSet<>();
        for (Crate crate : CratesAPI.getCrateManager().getCrates()) {
            for (WorldPos position : crate.getBlockPositions()) {
                Anchor anchor = Anchor.of(position);
                desired.add(anchor);
                if (position.getWorld() == null || !position.isChunkLoaded()) {
                    remove(anchor);
                } else if (!valid(orbits.get(anchor))) {
                    remove(anchor);
                    orbits.put(anchor, spawn(position));
                }
            }
        }
        Set<Anchor> obsolete = new HashSet<>(orbits.keySet());
        obsolete.removeAll(desired);
        obsolete.forEach(this::remove);
    }

    private Orbit spawn(WorldPos source) {
        World world = source.getWorld();
        List<UUID> ids = new ArrayList<>(SHARDS);
        for (int index = 0; index < SHARDS; index++) {
            int shard = index;
            Location origin = source.toLocation().add(.5, .55, .5);
            BlockDisplay display = world.spawn(origin, BlockDisplay.class, entity -> {
                entity.getPersistentDataContainer().set(marker, PersistentDataType.BYTE, (byte) 1);
                entity.setBlock((shard & 1) == 0
                        ? Material.AMETHYST_BLOCK.createBlockData()
                        : Material.GOLD_BLOCK.createBlockData());
                entity.setPersistent(false);
                entity.setInvulnerable(true);
                entity.setSilent(true);
                entity.setGravity(false);
                entity.setBillboard(Display.Billboard.FIXED);
                entity.setBrightness(new Display.Brightness(15, 15));
                entity.setViewRange(20.0F);
                entity.setTeleportDuration((int) ANIMATION_TICKS);
                entity.setInterpolationDuration((int) ANIMATION_TICKS);
                entity.setShadowRadius(0.0F);
                entity.setTransformation(transformation(shard, 0.0F));
            });
            ids.add(display.getUniqueId());
        }
        return new Orbit(source.toLocation(), List.copyOf(ids));
    }

    private void animate() {
        frame++;
        double time = frame * 0.15D;
        for (Orbit orbit : orbits.values()) {
            World world = orbit.anchor().getWorld();
            if (world == null || !world.isChunkLoaded(orbit.anchor().getBlockX() >> 4,
                    orbit.anchor().getBlockZ() >> 4)) continue;
            for (int index = 0; index < orbit.displays().size(); index++) {
                BlockDisplay display = entity(orbit.displays().get(index));
                if (display == null) continue;
                double angle = time + Math.PI * 2.0D * index / SHARDS;
                double radius = .78D + Math.sin(time * .7D + index) * .06D;
                Location next = orbit.anchor().clone().add(
                        .5D + Math.cos(angle) * radius,
                        .62D + Math.sin(time * 1.25D + index) * .18D,
                        .5D + Math.sin(angle) * radius);
                next.setYaw((float) Math.toDegrees(-angle));
                try {
                    display.teleport(next);
                    display.setTransformation(transformation(index, (float) angle));
                } catch (RuntimeException ignored) {
                    // Reconciliation recreates an entity invalidated during a
                    // chunk/world lifecycle edge without cancelling animation.
                }
            }
        }
    }

    private static Transformation transformation(int index, float angle) {
        float scale = (index & 1) == 0 ? .13F : .09F;
        Quaternionf rotation = new Quaternionf(new AxisAngle4f(angle + index * .35F, 0.0F, 1.0F, 0.0F))
                .rotateZ(.78F);
        return new Transformation(new Vector3f(-scale / 2.0F, -.28F, -scale / 2.0F), rotation,
                new Vector3f(scale, .56F, scale), new Quaternionf());
    }

    private boolean valid(Orbit orbit) {
        return orbit != null && orbit.displays().stream().allMatch(id -> entity(id) != null);
    }

    private BlockDisplay entity(UUID id) {
        return plugin.getServer().getEntity(id) instanceof BlockDisplay display && display.isValid()
                ? display : null;
    }

    private void remove(Anchor anchor) {
        Orbit orbit = orbits.remove(anchor);
        if (orbit == null) return;
        orbit.displays().forEach(id -> {
            var entity = plugin.getServer().getEntity(id);
            if (entity != null) entity.remove();
        });
    }

    private void removeAll() {
        Set.copyOf(orbits.keySet()).forEach(this::remove);
    }

    private void removeOrphans() {
        for (World world : plugin.getServer().getWorlds()) {
            for (BlockDisplay display : world.getEntitiesByClass(BlockDisplay.class)) {
                if (display.getPersistentDataContainer().has(marker, PersistentDataType.BYTE)) display.remove();
            }
        }
    }

    @Override
    public void close() {
        reconcileTask.cancel();
        animationTask.cancel();
        removeAll();
    }

    private record Orbit(Location anchor, List<UUID> displays) { }

    private record Anchor(String world, int x, int y, int z) {
        static Anchor of(WorldPos value) {
            return new Anchor(value.getWorldName(), value.getX(), value.getY(), value.getZ());
        }
    }
}
