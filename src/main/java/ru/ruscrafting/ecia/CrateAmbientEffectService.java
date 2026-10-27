package ru.ruscrafting.ecia;

import org.bukkit.Location;
import org.bukkit.Chunk;
import kotlin.Unit;
import ru.arc.core.BudgetedTaskQueue;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import ru.arc.paper.display.PacketItemDisplay;
import ru.arc.paper.display.PaperPacketDisplays;
import ru.arc.paper.api.ArcSceneBounds;
import ru.arc.paper.api.ArcSceneDisplay;
import ru.arc.paper.api.ArcSceneDisplayContent;
import ru.arc.paper.api.ArcSceneDisplayKeyframe;
import ru.arc.paper.api.ArcSceneDisplayItemSwitch;
import ru.arc.paper.api.ArcSceneDisplayTimeline;
import ru.arc.paper.api.ArcSceneDisplayPose;
import ru.arc.paper.api.ArcSceneVector3;
import ru.arc.paper.api.ArcSceneQuaternion;
import ru.arc.paper.api.ArcSceneEntity;
import ru.arc.paper.api.ArcSceneEntitySource;
import ru.arc.paper.api.ArcSceneItem;
import ru.arc.paper.scene.PaperSceneSnapshots;
import ru.ruscrafting.ecia.integration.ItemsAdderFurnitureAccess;
import su.nightexpress.excellentcrates.CratesAPI;
import su.nightexpress.excellentcrates.api.crate.Reward;
import su.nightexpress.excellentcrates.crate.impl.Crate;
import su.nightexpress.excellentcrates.util.pos.WorldPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Iterator;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Animates packet-only reward previews beside every idle placed crate. */
public final class CrateAmbientEffectService implements AutoCloseable, ArcSceneEntitySource {
    private static final long RECONCILE_TICKS = 40L;
    private static final long ANIMATION_TICKS = 1L;
    private static final int INTERPOLATION_TICKS = 2;
    private static final int BASE_PERIOD_FRAMES = 50;
    /** One logical frame used to take two ticks; retain that phase speed at one-tick sampling. */
    private static final double FRAME_STEP = .5;

    private final ArcExcellentCratesPlugin plugin;
    private final CrateVisualSettingsStore settings;
    private final PaperPacketDisplays packetDisplays;
    private final NamespacedKey marker;
    private final LegacyAmbientCleanup legacyCleanup;
    private final ItemsAdderFurnitureAccess furniture = ItemsAdderFurnitureAccess.create();
    private Map<Anchor, Orbit> orbits = new ConcurrentHashMap<>();
    private Set<Anchor> paused = ConcurrentHashMap.newKeySet();
    private final BukkitTask reconcileTask;
    private final BukkitTask animationTask;
    private Map<String, RewardPool> rewardsByCrate = Map.of();
    private double frame;
    private boolean closed;
    private boolean initialized;
    private boolean reconcilePending;
    private boolean resetPending;
    private long generation;
    private long rewardsGeneration;

    CrateAmbientEffectService(ArcExcellentCratesPlugin plugin, CrateVisualSettingsStore settings,
                              PaperPacketDisplays packetDisplays) {
        this.plugin = plugin;
        this.settings = settings;
        this.packetDisplays = packetDisplays;
        this.marker = new NamespacedKey(plugin, "case_ambient");
        this.legacyCleanup = new LegacyAmbientCleanup(marker, plugin.runtime().nativeWork(), plugin);
        cleanLoadedDisplays(plugin, () -> !closed, legacyCleanup::remove).whenComplete((ignored, failure) -> {
            if (failure != null) reportWorkFailure(plugin, "ambient orphan cleanup", failure);
            else if (!closed) { initialized = true; reconcile(); }
        });
        reconcileTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::reconcile,
                RECONCILE_TICKS, RECONCILE_TICKS);
        animationTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::animate,
                ANIMATION_TICKS, ANIMATION_TICKS);
        plugin.getServer().getPluginManager().registerEvents(legacyCleanup, plugin);
    }

    public CompletableFuture<Unit> setRewards(Map<String, List<ItemStack>> rewards) {
        long expected = ++rewardsGeneration;
        Map<String, RewardPool> copied = new HashMap<>();
        var work = new Supplier<Boolean>() {
            final Iterator<Map.Entry<String, List<ItemStack>>> crates = rewards.entrySet().iterator();
            Iterator<ItemStack> items = Collections.emptyIterator();
            List<ItemStack> usable = new ArrayList<>();
            List<ArcSceneItem> appearances = new ArrayList<>();
            String crateId;
            @Override public Boolean get() {
                if (items.hasNext()) {
                    ItemStack item = items.next();
                    if (item != null && !item.isEmpty()) {
                        usable.add(item.clone());
                        appearances.add(PaperSceneSnapshots.item(item));
                    }
                } else {
                    if (crateId != null && !usable.isEmpty()) copied.put(crateId, new RewardPool(List.copyOf(usable), List.copyOf(appearances)));
                    if (!crates.hasNext()) {
                        rewardsByCrate = Collections.unmodifiableMap(copied);
                        refresh();
                        return true;
                    }
                    var crate = crates.next();
                    crateId = crate.getKey();
                    items = crate.getValue().iterator();
                    usable = new ArrayList<>();
                    appearances = new ArrayList<>();
                }
                return false;
            }
        };
        CompletableFuture<Unit> result = plugin.runtime().nativeWork().submit(() -> !closed && rewardsGeneration == expected, work::get);
        result.whenComplete((ignored, failure) -> {
            if (failure != null) reportWorkFailure(plugin, "ambient reward preparation", failure);
        });
        return result;
    }

    public void setOpening(Location location, boolean opening) {
        if (closed || location.getWorld() == null) return;
        Anchor anchor = Anchor.of(location);
        if (opening) paused.add(anchor); else paused.remove(anchor);
    }

    void refresh() {
        if (closed) return;
        long expected = ++generation;
        reconcilePending = false;
        resetPending = true;
        Iterator<Anchor> stale = orbits.keySet().iterator();
        plugin.runtime().nativeWork().submit(() -> !closed && generation == expected, () -> {
            if (stale.hasNext()) { remove(stale.next()); return false; }
            return true;
        }).whenComplete((ignored, failure) -> {
            if (generation != expected) return;
            resetPending = false;
            if (failure != null) reportWorkFailure(plugin, "ambient reset", failure);
            else reconcile();
        });
    }

    private void reconcile() {
        if (closed || !initialized || resetPending || reconcilePending) return;
        reconcilePending = true;
        long expected = generation;
        var work = new Supplier<Boolean>() {
            final boolean active = plugin.getConfig().getBoolean("case-ambient.enabled", true) && CratesAPI.isLoaded();
            final Iterator<Crate> crates = active ? CratesAPI.getCrateManager().getCrates().iterator() : Collections.emptyIterator();
            final Set<Anchor> desired = new HashSet<>();
            Iterator<WorldPos> positions = Collections.emptyIterator();
            Iterator<Reward> previewRewards = Collections.emptyIterator();
            Iterator<Anchor> stale;
            Iterator<Anchor> pausedStale;
            Crate crate;
            RewardPool rewards;
            List<ItemStack> captured = new ArrayList<>();
            List<ArcSceneItem> appearances = new ArrayList<>();
            boolean capturing;

            @Override public Boolean get() {
                if (pausedStale != null) {
                    if (!pausedStale.hasNext()) return true;
                    if (!desired.contains(pausedStale.next())) pausedStale.remove();
                } else if (stale != null) {
                    if (stale.hasNext()) { Anchor anchor = stale.next(); if (!desired.contains(anchor)) remove(anchor); }
                    else pausedStale = paused.iterator();
                } else if (capturing) {
                    if (previewRewards.hasNext()) {
                        ItemStack item = previewRewards.next().getPreviewItem();
                        if (item != null && !item.isEmpty()) {
                            captured.add(item.clone());
                            appearances.add(PaperSceneSnapshots.item(item));
                        }
                    } else {
                        rewards = new RewardPool(List.copyOf(captured), List.copyOf(appearances));
                        if (!rewards.items().isEmpty()) positions = crate.getBlockPositions().iterator();
                        capturing = false;
                    }
                } else if (positions.hasNext()) {
                    WorldPos position = positions.next();
                    Anchor anchor = Anchor.of(position);
                    desired.add(anchor);
                    if (!CrateShellPresence.isPresent(position, furniture)) remove(anchor);
                    else if (!valid(orbits.get(anchor))) { remove(anchor); orbits.put(anchor, spawn(position, rewards)); }
                } else if (crates.hasNext()) {
                    crate = crates.next();
                    rewards = rewardsByCrate.get(crate.getId());
                    if (rewards != null && !rewards.items().isEmpty()) positions = crate.getBlockPositions().iterator();
                    else { captured = new ArrayList<>(); appearances = new ArrayList<>(); previewRewards = crate.getRewards().iterator(); capturing = true; }
                } else stale = orbits.keySet().iterator();
                return false;
            }
        };
        plugin.runtime().nativeWork().submit(() -> !closed && generation == expected, work::get).whenComplete((ignored, failure) -> {
            if (generation == expected) reconcilePending = false;
            if (failure != null) reportWorkFailure(plugin, "ambient reconciliation", failure);
        });
    }

    private Orbit spawn(WorldPos source, RewardPool rewards) {
        CrateVisualSettingsStore.Anchor settingsAnchor = new CrateVisualSettingsStore.Anchor(
                source.getWorldName(), source.getX(), source.getY(), source.getZ());
        CrateVisualSettingsStore.Ambient visual = settings.get(settingsAnchor).ambient();
        ItemDisplayPresentation presentation = ItemDisplayPresentation.from(
                plugin.getConfig(), "case-ambient.flat-item-displays", false);
        List<PacketItemDisplay> displays = new ArrayList<>(visual.itemCount());
        Location origin = source.toLocation().add(.5, .45, .5);
        try {
            for (int index = 0; index < visual.itemCount(); index++) {
                int slot = index;
                PacketItemDisplay display = packetDisplays.spawnItem(
                        origin.clone(), rewards.items().get(slot % rewards.items().size()).clone());
                displays.add(display);
                display.setVisibleByDefault(true);
                display.setItemDisplayTransform(presentation.transform());
                display.setBillboard(Display.Billboard.CENTER);
                display.setBrightness(new Display.Brightness(15, 15));
                display.setViewRange(settings.ambientDisplayViewRange(visual));
                display.setTeleportDuration(INTERPOLATION_TICKS);
                display.setInterpolationDuration(INTERPOLATION_TICKS);
                display.setShadowRadius(.12F);
                display.setShadowStrength(.55F);
                display.setTransformation(transformation(.01F, 0.0F, presentation));
            }
        } catch (RuntimeException failure) {
            displays.forEach(PacketItemDisplay::remove);
            throw failure;
        }
        return new Orbit(source.toLocation(), List.copyOf(displays), rewards.items(), rewards.appearances(),
                new int[displays.size()], visual, presentation);
    }

    @Override
    public List<ArcSceneEntity> snapshot(ArcSceneBounds bounds, int limit) {
        return List.of();
    }

    /** Only this owner's idle public placed-crate rewards opt in; no packet-owner registry scan. */
    @Override
    public List<ArcSceneDisplay> displays(ArcSceneBounds bounds, int limit) {
        if (closed) return List.of();
        if (!org.bukkit.Bukkit.isPrimaryThread()) throw new IllegalStateException("Crate scene capture requires Paper's primary thread");
        if (limit < 1 || limit > 128) throw new IllegalArgumentException("Crate scene display limit must be 1..128");
        List<ArcSceneDisplay> result = new ArrayList<>(limit);
        int anchorsExamined = 0;
        int displaysExamined = 0;
        double capturedFrame = frame;
        long capturedAt = System.currentTimeMillis();
        for (Map.Entry<Anchor, Orbit> entry : orbits.entrySet()) {
            if (++anchorsExamined > 128 || displaysExamined >= 128) break;
            Orbit orbit = entry.getValue();
            World world = orbit.anchor().getWorld();
            if (world == null || !world.getUID().equals(bounds.getWorldId()) || paused.contains(entry.getKey())
                    || !world.isChunkLoaded(orbit.anchor().getBlockX() >> 4, orbit.anchor().getBlockZ() >> 4)) continue;
            for (int slot = 0; slot < orbit.displays().size() && displaysExamined < 128; slot++) {
                displaysExamined++;
                PacketItemDisplay display = orbit.displays().get(slot);
                if (!display.isValid() || !display.isVisibleByDefault() || display.getAudienceGroup() != null) continue;
                Frame current = frame(capturedFrame, slot, orbit.visual(), orbit.rewards().size());
                double x = orbit.anchor().getX() + .5 + current.x();
                double y = orbit.anchor().getY() + .45 + current.y();
                double z = orbit.anchor().getZ() + .5 + current.z();
                if (!bounds.contains(world.getUID(), x, y, z)) continue;
                ArcSceneDisplayTimeline timeline = sceneTimeline(orbit, slot, capturedFrame, capturedAt);
                Anchor anchor = entry.getKey();
                result.add(new ArcSceneDisplay(world.getUID(), "ecia:ambient:" + world.getUID() + ":" + anchor.x() + ":" + anchor.y() + ":" + anchor.z() + ":" + slot,
                        timeline.getSamples().getFirst().getPose(), display.getBillboard().name(), 15, 15,
                        display.getViewRange(), display.getShadowRadius(), display.getShadowStrength(),
                        new ArcSceneDisplayContent.Item(timeline.getItems().getFirst(), orbit.presentation().transform().name()), timeline));
                if (result.size() == limit) return List.copyOf(result);
            }
        }
        return List.copyOf(result);
    }

    /** Same authored native frame function, detached into a finite non-looping prediction window. */
    static ArcSceneDisplayTimeline sceneTimeline(Orbit orbit, int slot, double capturedFrame, long capturedAt) {
        List<ArcSceneDisplayKeyframe> samples = new ArrayList<>(21);
        List<ArcSceneItem> palette = new ArrayList<>();
        Map<Integer, Integer> paletteIndices = new LinkedHashMap<>();
        java.util.function.IntUnaryOperator paletteIndex = reward -> paletteIndices.computeIfAbsent(reward, index -> {
            palette.add(orbit.appearances().get(index));
            return palette.size() - 1;
        });
        for (int index = 0; index <= 20; index++) {
            Frame sample = frame(capturedFrame + index, slot, orbit.visual(), orbit.rewards().size());
            Transformation transform = transformation(sample.scale(), sample.rotation(), orbit.presentation());
            Vector3f scale = transform.getScale();
            Quaternionf rotation = transform.getLeftRotation();
            ArcSceneDisplayPose pose = new ArcSceneDisplayPose(
                    orbit.anchor().getX() + .5 + sample.x(), orbit.anchor().getY() + .45 + sample.y(),
                    orbit.anchor().getZ() + .5 + sample.z(), orbit.anchor().getYaw(), orbit.anchor().getPitch(),
                    new ArcSceneVector3(0f, 0f, 0f), new ArcSceneVector3(scale.x, scale.y, scale.z),
                    new ArcSceneQuaternion(rotation.x, rotation.y, rotation.z, rotation.w), new ArcSceneQuaternion(0f, 0f, 0f, 1f));
            samples.add(new ArcSceneDisplayKeyframe(pose, paletteIndex.applyAsInt(sample.rewardIndex())));
        }
        List<ArcSceneDisplayItemSwitch> switches = new ArrayList<>();
        int previousReward = -1;
        for (int index = 0; index <= 40; index++) {
            Frame sample = frame(capturedFrame + index * FRAME_STEP, slot, orbit.visual(), orbit.rewards().size());
            if (sample.rewardIndex() == previousReward) continue;
            switches.add(new ArcSceneDisplayItemSwitch(index * 50, paletteIndex.applyAsInt(sample.rewardIndex())));
            previousReward = sample.rewardIndex();
        }
        return new ArcSceneDisplayTimeline(capturedAt, capturedFrame, 100, samples, palette, switches);
    }

    private void animate() {
        if (closed) return;
        double frameTime = (frame += FRAME_STEP);
        // Pose authoring is one coherent frame; the renderer budgets native capture and packet delivery.
        for (Map.Entry<Anchor, Orbit> entry : orbits.entrySet()) {
            Orbit orbit = entry.getValue();
            World world = orbit.anchor().getWorld();
            if (world == null || !world.isChunkLoaded(orbit.anchor().getBlockX() >> 4,
                    orbit.anchor().getBlockZ() >> 4)) continue;
            boolean hidden = paused.contains(entry.getKey());
            for (int slot = 0; slot < orbit.displays().size(); slot++) {
                PacketItemDisplay display = orbit.displays().get(slot);
                if (!display.isValid()) continue;
                if (hidden) {
                    display.teleport(orbit.anchor().clone().add(.5, .45, .5));
                    display.setTransformation(transformation(.01F, 0.0F, orbit.presentation()));
                } else {
                    Frame next = frame(frameTime, slot, orbit.visual(), orbit.rewards().size());
                    if (orbit.rewardIndices()[slot] != next.rewardIndex() + 1) {
                        display.setItemStack(orbit.rewards().get(next.rewardIndex()).clone());
                        orbit.rewardIndices()[slot] = next.rewardIndex() + 1;
                    }
                    display.teleport(orbit.anchor().clone().add(.5 + next.x(), .45 + next.y(), .5 + next.z()));
                    display.setTransformation(transformation(next.scale(), next.rotation(), orbit.presentation()));
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

    private static Transformation transformation(float scale, float rotation,
                                                 ItemDisplayPresentation presentation) {
        return new Transformation(new Vector3f(), new Quaternionf().rotateZ(rotation),
                presentation.scale(scale), new Quaternionf());
    }

    private boolean valid(Orbit orbit) {
        return orbit != null && orbit.displays().stream().allMatch(PacketItemDisplay::isValid);
    }

    private void remove(Anchor anchor) {
        Orbit orbit = orbits.remove(anchor);
        if (orbit == null) return;
        orbit.displays().forEach(PacketItemDisplay::remove);
    }

    /** Chunk/entity snapshots are indivisible native reads; every inspected entity gets its own step. */
    public static CompletableFuture<Unit> cleanLoadedDisplays(ArcExcellentCratesPlugin plugin,
            BooleanSupplier current, Consumer<Entity> inspect) {
        var work = new Supplier<Boolean>() {
            final Iterator<World> worlds = plugin.getServer().getWorlds().iterator();
            Iterator<Chunk> chunks = Collections.emptyIterator();
            Iterator<Entity> entities = Collections.emptyIterator();
            @Override public Boolean get() {
                if (entities.hasNext()) inspect.accept(entities.next());
                else if (chunks.hasNext()) {
                    Chunk chunk = chunks.next();
                    if (chunk.isLoaded() && chunk.isEntitiesLoaded()) entities = Arrays.asList(chunk.getEntities()).iterator();
                } else if (worlds.hasNext()) chunks = Arrays.asList(worlds.next().getLoadedChunks()).iterator();
                else return true;
                return false;
            }
        };
        return plugin.runtime().nativeWork().submit(current::getAsBoolean, work::get);
    }

    public static void reportWorkFailure(ArcExcellentCratesPlugin plugin, String operation, Throwable failure) {
        if (!(failure instanceof CancellationException)) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Crate " + operation + " failed", failure);
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        generation++;
        rewardsGeneration++;
        legacyCleanup.closed = true;
        HandlerList.unregisterAll(legacyCleanup);
        reconcileTask.cancel();
        animationTask.cancel();
        Iterator<Orbit> detached = orbits.values().iterator();
        orbits = new ConcurrentHashMap<>();
        paused = ConcurrentHashMap.newKeySet();
        rewardsByCrate = Map.of();
        var cleanup = new Supplier<Boolean>() {
            Iterator<PacketItemDisplay> displays = Collections.emptyIterator();
            @Override public Boolean get() {
                if (displays.hasNext()) displays.next().remove();
                else if (detached.hasNext()) { displays = detached.next().displays().iterator(); detached.remove(); }
                else return true;
                return false;
            }
        };
        plugin.runtime().submitNativeCleanup("ambient", cleanup::get);
    }

    record RewardPool(List<ItemStack> items, List<ArcSceneItem> appearances) { }

    record Orbit(Location anchor, List<PacketItemDisplay> displays, List<ItemStack> rewards, List<ArcSceneItem> appearances, int[] rewardIndices,
                 CrateVisualSettingsStore.Ambient visual, ItemDisplayPresentation presentation) { }

    record Frame(double x, double y, double z, float scale, float rotation, int rewardIndex) { }

    /** Removes only the old server-entity ambient markers when a chunk arrives later. */
    public static final class LegacyAmbientCleanup implements Listener {
        private final NamespacedKey marker;
        private final BudgetedTaskQueue queue;
        private final ArcExcellentCratesPlugin plugin;
        private boolean closed;

        LegacyAmbientCleanup(NamespacedKey marker, BudgetedTaskQueue queue, ArcExcellentCratesPlugin plugin) {
            this.marker = marker;
            this.queue = queue;
            this.plugin = plugin;
        }

        @EventHandler
        public void onEntitiesLoad(EntitiesLoadEvent event) {
            Iterator<Entity> entities = event.getEntities().iterator();
            queue.submit(() -> !closed, () -> {
                if (!entities.hasNext()) return true;
                remove(entities.next());
                return false;
            }).whenComplete((ignored, failure) -> {
                if (failure != null) reportWorkFailure(plugin, "legacy ambient cleanup", failure);
            });
        }

        private void remove(Entity entity) {
            if ((entity instanceof ItemDisplay || entity instanceof BlockDisplay)
                    && entity.getPersistentDataContainer().has(marker, PersistentDataType.BYTE)) entity.remove();
        }
    }

    private record Anchor(String world, int x, int y, int z) {
        static Anchor of(WorldPos value) {
            return new Anchor(value.getWorldName(), value.getX(), value.getY(), value.getZ());
        }

        static Anchor of(Location value) {
            return new Anchor(value.getWorld().getName(), value.getBlockX(), value.getBlockY(), value.getBlockZ());
        }
    }
}
