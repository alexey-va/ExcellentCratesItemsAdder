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

/** Animates real reward previews beside every idle placed crate. */
public final class CrateAmbientEffectService implements AutoCloseable {
    private static final long RECONCILE_TICKS = 40L;
    private static final long ANIMATION_TICKS = 1L;
    private static final int INTERPOLATION_TICKS = 2;
    private static final int BASE_PERIOD_FRAMES = 50;
    /** One logical frame used to take two ticks; retain that phase speed at one-tick sampling. */
    private static final double FRAME_STEP = .5;

    private final ArcExcellentCratesPlugin plugin;
    private final CrateVisualSettingsStore settings;
    private final NamespacedKey marker;
    private final Map<Anchor, Orbit> orbits = new HashMap<>();
    private final Set<Anchor> paused = new HashSet<>();
    private final BukkitTask reconcileTask;
    private final BukkitTask animationTask;
    private Map<String, List<ItemStack>> rewardsByCrate = Map.of();
    private double frame;
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
                entity.setTeleportDuration(INTERPOLATION_TICKS);
                entity.setInterpolationDuration(INTERPOLATION_TICKS);
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
        frame += FRAME_STEP;
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
        return frame((double) globalFrame, slot, visual, rewardCount);
    }

    static Frame frame(double globalFrame, int slot, CrateVisualSettingsStore.Ambient visual, int rewardCount) {
        int count = Math.max(1, visual.itemCount());
        int period = Math.max(12, (int) Math.round(BASE_PERIOD_FRAMES / visual.speed()));
        double shifted = globalFrame + (long) slot * period / count;
        double cycle = Math.floor(shifted / period);
        double progress = (shifted - cycle * period) / period;
        double angle = Math.PI * 2.0 * slot / count + globalFrame * .035 * visual.speed();
        double envelope = Math.sin(Math.PI * progress);
        double radius;
        double height;
        double x = Double.NaN;
        double z = Double.NaN;
        double rotation = Double.NaN;
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
            case "WHEEL" -> {
                angle = Math.PI * 2.0 * slot / count + globalFrame * .055 * visual.speed();
                envelope = 1.0;
                radius = visual.radius();
                height = visual.height() * .95 + Math.sin(angle) * visual.height() * .85;
                x = Math.cos(angle) * radius;
                z = radius * .95;
            }
            case "SWING" -> {
                double swing = Math.sin(globalFrame * .045 * visual.speed()) * 1.15;
                angle = Math.PI * 2.0 * slot / count + swing;
                envelope = .9 + .1 * Math.cos(angle);
                radius = visual.radius();
                height = visual.height() * .95 + Math.sin(angle) * visual.height() * .8;
                x = Math.cos(angle) * radius;
                z = -radius * .95;
            }
            case "INFINITY" -> {
                angle = Math.PI * 2.0 * slot / count + globalFrame * .028 * visual.speed();
                envelope = .85 + .15 * (.5 + .5 * Math.cos(angle * 2.0));
                radius = visual.radius();
                height = visual.height() * 1.05 + Math.cos(angle * 2.0) * .15;
                x = Math.sin(angle) * radius;
                z = Math.sin(angle * 2.0) * radius * .55;
            }
            case "SATURN" -> {
                envelope = 1.0;
                radius = visual.radius();
                height = visual.height() * .72 + Math.sin(angle) * visual.height() * .42;
            }
            case "CAROUSEL" -> {
                angle = Math.PI * 2.0 * slot / count + globalFrame * .025 * visual.speed();
                envelope = .9 + .1 * Math.sin(angle * 2.0);
                radius = visual.radius() * (.9 + .1 * Math.sin(angle * 3.0));
                height = visual.height() * (.58 + .2 * Math.sin(angle * 2.0));
            }
            case "COMET" -> {
                double trail = count == 1 ? 0.0 : slot / (double) (count - 1);
                angle = globalFrame * .06 * visual.speed() - slot * .24;
                envelope = 1.0 - trail * .65;
                radius = visual.radius() * (.82 + trail * .18);
                height = visual.height() * .58 + Math.sin(angle * 2.0) * .1;
            }
            case "BLOOM" -> {
                angle = Math.PI * 2.0 * slot / count + globalFrame * .026 * visual.speed();
                envelope = .85 + .15 * (.5 + .5 * Math.sin(angle * 2.0));
                radius = visual.radius() * (.91 + .09 * Math.cos(angle * 4.0));
                height = visual.height() * .58 + Math.sin(angle * 3.0) * .18;
            }
            case "HELIX" -> {
                int strand = slot & 1;
                int strandSize = Math.max(1, (count + 1) / 2);
                int strandSlot = slot / 2;
                angle = Math.PI * 2.0 * strandSlot / strandSize
                        + globalFrame * .035 * visual.speed() * (strand == 0 ? 1.0 : -1.0)
                        + strand * Math.PI;
                envelope = .9 + .1 * Math.sin(angle * 2.0);
                radius = visual.radius() * .92;
                height = visual.height() * (.58 + .3 * Math.sin(angle * 2.0 + strand * Math.PI));
            }
            case "TIDE" -> {
                double lane = count == 1 ? 0.0 : slot * 2.0 / (count - 1) - 1.0;
                double wave = globalFrame * .045 * visual.speed() + slot * .75;
                angle = wave;
                envelope = .85 + .15 * (.5 + .5 * Math.cos(wave));
                radius = visual.radius();
                height = visual.height() * (.62 + .28 * Math.sin(wave));
                x = lane * radius * 1.4;
                z = radius * (.9 + .12 * Math.sin(wave));
            }
            case "CLOCKWORK" -> {
                boolean inner = (slot & 1) == 0;
                angle = Math.PI * 2.0 * slot / count + globalFrame * .035 * visual.speed() * (inner ? 1.35 : -.9);
                envelope = .94 + .06 * Math.sin(angle * 2.0);
                radius = visual.radius() * (inner ? .82 : 1.08);
                height = visual.height() * (inner ? .48 : .76);
            }
            case "SHOWCASE" -> {
                int hold = Math.max(8, period / 2);
                int active = Math.floorMod((int) Math.floor(globalFrame / hold), count);
                double local = (globalFrame - Math.floor(globalFrame / hold) * hold) / hold;
                envelope = slot == active ? .82 + .18 * Math.sin(Math.PI * local) : 0.0;
                radius = 0.0;
                height = .95 + visual.height() * .55 + Math.sin(local * Math.PI * 2.0) * .08;
                x = 0.0;
                z = 0.0;
                rotation = globalFrame * .025 * visual.speed();
            }
            case "REELS" -> {
                int columns = Math.min(3, count);
                int column = slot % columns;
                int row = slot / columns;
                double roll = (globalFrame * .035 * visual.speed() + row * .36) % 1.0;
                envelope = smooth(Math.min(1.0, Math.min(roll, 1.0 - roll) * 7.0));
                radius = visual.radius();
                height = .55 + roll * visual.height() * 1.65;
                x = (column - (columns - 1) / 2.0) * radius * .55;
                z = Math.max(1.05, radius * .9);
                rotation = 0.0;
            }
            case "WALL" -> {
                int columns = Math.min(4, count);
                int column = slot % columns;
                int row = slot / columns;
                double pulse = globalFrame * .045 * visual.speed() + slot * .8;
                envelope = .78 + .22 * (.5 + .5 * Math.sin(pulse));
                radius = visual.radius();
                height = .65 + row * visual.height() * .8;
                x = (column - (columns - 1) / 2.0) * radius * .55;
                z = Math.max(1.05, radius * .9);
                rotation = Math.sin(pulse) * .08;
            }
            case "CONVEYOR" -> {
                double lane = count == 1 ? 0.0 : slot * 2.0 / (count - 1) - 1.0;
                double travel = Math.sin(globalFrame * .03 * visual.speed()) * .35;
                envelope = 1.0;
                radius = visual.radius();
                height = .65 + Math.abs(lane) * .08;
                x = (lane * 1.3 + travel) * radius;
                z = Math.max(1.05, radius * .9);
                rotation = 0.0;
            }
            case "RAIN" -> {
                double fall = (globalFrame * .025 * visual.speed() + slot * .17) % 1.0;
                int lanes = Math.min(4, count);
                double lane = slot % lanes - (lanes - 1) / 2.0;
                envelope = smooth(Math.min(1.0, Math.min(fall, 1.0 - fall) * 9.0));
                radius = visual.radius();
                height = .55 + (1.0 - fall) * visual.height() * 2.0;
                x = lane * radius * .45;
                z = Math.max(1.05, radius * .9);
                rotation = fall * 1.3;
            }
            case "TOWER" -> {
                double level = count == 1 ? 0.0 : slot / (double) (count - 1);
                double sway = Math.sin(globalFrame * .035 * visual.speed() + level * Math.PI) * .12;
                envelope = .92 + .08 * Math.sin(globalFrame * .05 * visual.speed() + slot);
                radius = 0.0;
                height = .95 + level * visual.height() * 2.0;
                x = sway;
                z = 0.0;
                rotation = sway * 1.5;
            }
            case "FAN" -> {
                double lane = count == 1 ? 0.0 : slot * 2.0 / (count - 1) - 1.0;
                double spread = .65 + .35 * (.5 + .5 * Math.sin(globalFrame * .035 * visual.speed()));
                envelope = .9 + .1 * Math.cos(globalFrame * .04 * visual.speed() + slot * .7);
                radius = visual.radius();
                height = .6 + (1.0 - lane * lane) * visual.height() * .55;
                x = lane * radius * 1.3 * spread;
                z = Math.max(1.05, radius * .9);
                rotation = lane * .65 * spread;
            }
            case "SCALES" -> {
                int leftSize = (count + 1) / 2;
                boolean left = slot < leftSize;
                int groupSlot = left ? slot : slot - leftSize;
                int groupSize = left ? leftSize : count - leftSize;
                double local = groupSize <= 1 ? 0.0 : groupSlot / (double) (groupSize - 1) - .5;
                double balance = Math.sin(globalFrame * .04 * visual.speed());
                envelope = 1.0;
                radius = visual.radius();
                height = .72 + (left ? balance : -balance) * visual.height() * .3 + Math.abs(local) * .12;
                x = count == 1 ? 0.0 : (left ? -.82 : .82) * radius + local * .65;
                z = Math.max(1.05, radius * .86);
                rotation = (left ? -.16 : .16) * balance;
            }
            default -> {
                angle += progress * 1.1;
                radius = visual.radius() * (.90 + .10 * envelope);
                height = visual.height() * 4.0 * progress * (1.0 - progress);
            }
        }
        float scale = (float) (.01 + visual.itemScale() * Math.pow(Math.max(0.0, envelope), .72));
        int rewardIndex = Math.floorMod((int) Math.floor(cycle * count + slot), Math.max(1, rewardCount));
        if (!Double.isFinite(x)) x = Math.cos(angle) * radius;
        if (!Double.isFinite(z)) z = Math.sin(angle) * radius;
        if (!Double.isFinite(rotation)) rotation = angle;
        return new Frame(x, height, z,
                scale, (float) rotation, rewardIndex);
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
