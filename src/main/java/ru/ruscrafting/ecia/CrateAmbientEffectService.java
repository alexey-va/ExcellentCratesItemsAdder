package ru.ruscrafting.ecia;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import su.nightexpress.excellentcrates.CratesAPI;
import su.nightexpress.excellentcrates.api.crate.Reward;
import su.nightexpress.excellentcrates.crate.impl.Crate;
import su.nightexpress.excellentcrates.util.pos.WorldPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Cycles real reward previews around every idle placed crate. */
public final class CrateAmbientEffectService implements AutoCloseable {
    private static final long RECONCILE_TICKS = 40L;
    private static final long ANIMATION_TICKS = 2L;
    private static final int BASE_PERIOD_FRAMES = 50;

    private final ArcExcellentCratesPlugin plugin;
    private final CrateVisualSettingsStore settings;
    private final NamespacedKey marker;
    private final Map<Anchor, Orbit> orbits = new HashMap<>();
    private final Set<Anchor> paused = new HashSet<>();
    private final BukkitTask reconcileTask;
    private final BukkitTask animationTask;
    private Map<String, List<ItemStack>> rewardsByCrate = Map.of();
    private long frame;
    private boolean closed;

    CrateAmbientEffectService(ArcExcellentCratesPlugin plugin, CrateVisualSettingsStore settings) {
        this.plugin = plugin;
        this.settings = settings;
        this.marker = new NamespacedKey(plugin, "case_ambient");
        removeOrphans();
        reconcile();
        reconcileTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::reconcile,
                RECONCILE_TICKS, RECONCILE_TICKS);
        animationTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::animate,
                ANIMATION_TICKS, ANIMATION_TICKS);
    }

    public void setRewards(Map<String, List<ItemStack>> rewards) {
        if (closed) return;
        Map<String, List<ItemStack>> copied = new HashMap<>();
        rewards.forEach((crateId, items) -> {
            List<ItemStack> usable = items.stream()
                    .filter(item -> item != null && !item.isEmpty())
                    .map(ItemStack::clone)
                    .toList();
            if (!usable.isEmpty()) copied.put(crateId, usable);
        });
        rewardsByCrate = Map.copyOf(copied);
        removeAll();
        reconcile();
    }

    public void setOpening(Location location, boolean opening) {
        if (closed || location.getWorld() == null) return;
        Anchor anchor = Anchor.of(location);
        if (opening) paused.add(anchor); else paused.remove(anchor);
    }

    void refresh() {
        if (closed) return;
        removeAll();
        reconcile();
    }

    private void reconcile() {
        if (closed || !plugin.getConfig().getBoolean("case-ambient.enabled", true) || !CratesAPI.isLoaded()) {
            removeAll();
            return;
        }
        Set<Anchor> desired = new HashSet<>();
        for (Crate crate : CratesAPI.getCrateManager().getCrates()) {
            List<ItemStack> rewards = previews(crate);
            if (rewards.isEmpty()) continue;
            for (WorldPos position : crate.getBlockPositions()) {
                Anchor anchor = Anchor.of(position);
                desired.add(anchor);
                if (position.getWorld() == null || !position.isChunkLoaded()) {
                    remove(anchor);
                } else if (!valid(orbits.get(anchor))) {
                    remove(anchor);
                    orbits.put(anchor, spawn(position, rewards));
                }
            }
        }
        Set<Anchor> obsolete = new HashSet<>(orbits.keySet());
        obsolete.removeAll(desired);
        obsolete.forEach(this::remove);
        paused.retainAll(desired);
    }

    private List<ItemStack> previews(Crate crate) {
        List<ItemStack> managed = rewardsByCrate.get(crate.getId());
        if (managed != null && !managed.isEmpty()) return managed;
        return crate.getRewards().stream()
                .map(Reward::getPreviewItem)
                .filter(item -> item != null && !item.isEmpty())
                .map(ItemStack::clone)
                .toList();
    }

    private Orbit spawn(WorldPos source, List<ItemStack> rewards) {
        CrateVisualSettingsStore.Anchor settingsAnchor = new CrateVisualSettingsStore.Anchor(
                source.getWorldName(), source.getX(), source.getY(), source.getZ());
        CrateVisualSettingsStore.Ambient visual = settings.get(settingsAnchor).ambient();
        World world = source.getWorld();
        List<UUID> ids = new ArrayList<>(visual.itemCount());
        Location origin = source.toLocation().add(.5, .45, .5);
        for (int index = 0; index < visual.itemCount(); index++) {
            int slot = index;
            ItemDisplay display = world.spawn(origin, ItemDisplay.class, entity -> {
                entity.getPersistentDataContainer().set(marker, PersistentDataType.BYTE, (byte) 1);
                entity.setItemStack(rewards.get(slot % rewards.size()).clone());
                entity.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.GUI);
                entity.setPersistent(false);
                entity.setInvulnerable(true);
                entity.setSilent(true);
                entity.setGravity(false);
                entity.setBillboard(Display.Billboard.CENTER);
                entity.setBrightness(new Display.Brightness(15, 15));
                entity.setViewRange(visual.viewRange());
                entity.setTeleportDuration((int) ANIMATION_TICKS);
                entity.setInterpolationDuration((int) ANIMATION_TICKS);
                entity.setShadowRadius(.12F);
                entity.setShadowStrength(.55F);
                entity.setTransformation(transformation(.01F, 0.0F));
            });
            ids.add(display.getUniqueId());
        }
        return new Orbit(source.toLocation(), List.copyOf(ids), rewards.stream().map(ItemStack::clone).toList(),
                new int[ids.size()], visual);
    }

    private void animate() {
        if (closed) return;
        frame++;
        for (Map.Entry<Anchor, Orbit> entry : orbits.entrySet()) {
            Orbit orbit = entry.getValue();
            World world = orbit.anchor().getWorld();
            if (world == null || !world.isChunkLoaded(orbit.anchor().getBlockX() >> 4,
                    orbit.anchor().getBlockZ() >> 4)) continue;
            boolean hidden = paused.contains(entry.getKey());
            for (int index = 0; index < orbit.displays().size(); index++) {
                ItemDisplay display = entity(orbit.displays().get(index));
                if (display == null) continue;
                if (hidden) {
                    display.teleport(orbit.anchor().clone().add(.5, .45, .5));
                    display.setTransformation(transformation(.01F, 0.0F));
                    continue;
                }
                Frame next = frame(frame, index, orbit.visual(), orbit.rewards().size());
                if (orbit.rewardIndices()[index] != next.rewardIndex() + 1) {
                    display.setItemStack(orbit.rewards().get(next.rewardIndex()).clone());
                    orbit.rewardIndices()[index] = next.rewardIndex() + 1;
                }
                Location location = orbit.anchor().clone().add(.5 + next.x(), .45 + next.y(), .5 + next.z());
                try {
                    display.teleport(location);
                    display.setTransformation(transformation(next.scale(), next.rotation()));
                } catch (RuntimeException ignored) {
                    // The next reconciliation recreates entities invalidated by a chunk lifecycle edge.
                }
            }
        }
    }

    static Frame frame(long globalFrame, int slot, CrateVisualSettingsStore.Ambient visual, int rewardCount) {
        int count = Math.max(1, visual.itemCount());
        int period = Math.max(12, (int) Math.round(BASE_PERIOD_FRAMES / visual.speed()));
        long shifted = globalFrame + (long) slot * period / count;
        long cycle = Math.floorDiv(shifted, period);
        double progress = Math.floorMod(shifted, period) / (double) period;
        double angle = Math.PI * 2.0 * slot / count + globalFrame * .035 * visual.speed();
        double envelope = Math.sin(Math.PI * progress);
        double radius;
        double height;
        switch (visual.preset()) {
            case "HALO" -> {
                double reveal = Math.min(1.0, Math.min(progress, 1.0 - progress) * 7.0);
                envelope = smooth(reveal);
                radius = visual.radius() * (.92 + .08 * Math.sin(progress * Math.PI));
                height = visual.height() * .62 + Math.sin(angle * 1.5) * .12;
            }
            case "CROWN" -> {
                envelope = Math.pow(envelope, .55);
                radius = visual.radius() * (.88 + .12 * envelope);
                height = visual.height() * envelope + Math.sin(angle * 2.0) * .08 * envelope;
            }
            case "SPIRAL" -> {
                angle += progress * Math.PI * 3.0;
                radius = visual.radius() * (.82 + .18 * progress);
                height = visual.height() * progress;
            }
            case "PULSE" -> {
                angle = Math.PI * 2.0 * slot / count + cycle * .45;
                radius = visual.radius() * (.86 + .14 * envelope);
                height = visual.height() * .55 * envelope;
            }
            default -> {
                angle += progress * 1.1;
                radius = visual.radius() * (.90 + .10 * envelope);
                height = visual.height() * 4.0 * progress * (1.0 - progress);
            }
        }
        float scale = (float) (.01 + visual.itemScale() * Math.pow(Math.max(0.0, envelope), .72));
        int rewardIndex = Math.floorMod((int) (cycle * count + slot), Math.max(1, rewardCount));
        return new Frame(Math.cos(angle) * radius, height, Math.sin(angle) * radius,
                scale, (float) (angle + progress * Math.PI), rewardIndex);
    }

    private static double smooth(double value) {
        double clamped = Math.max(0.0, Math.min(1.0, value));
        return clamped * clamped * (3.0 - 2.0 * clamped);
    }

    private static Transformation transformation(float scale, float rotation) {
        return new Transformation(new Vector3f(), new Quaternionf().rotateZ(rotation),
                new Vector3f(scale, scale, scale), new Quaternionf());
    }

    private boolean valid(Orbit orbit) {
        return orbit != null && orbit.displays().stream().allMatch(id -> entity(id) != null);
    }

    private ItemDisplay entity(UUID id) {
        return plugin.getServer().getEntity(id) instanceof ItemDisplay display && display.isValid()
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
        reconcileTask.cancel();
        animationTask.cancel();
        removeAll();
        paused.clear();
        rewardsByCrate = Map.of();
    }

    record Orbit(Location anchor, List<UUID> displays, List<ItemStack> rewards, int[] rewardIndices,
                 CrateVisualSettingsStore.Ambient visual) { }

    record Frame(double x, double y, double z, float scale, float rotation, int rewardIndex) { }

    private record Anchor(String world, int x, int y, int z) {
        static Anchor of(WorldPos value) {
            return new Anchor(value.getWorldName(), value.getX(), value.getY(), value.getZ());
        }

        static Anchor of(Location value) {
            return new Anchor(value.getWorld().getName(), value.getBlockX(), value.getBlockY(), value.getBlockZ());
        }
    }
}
