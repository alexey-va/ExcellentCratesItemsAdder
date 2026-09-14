package ru.ruscrafting.ecia;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
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
    private static final long VISIBILITY_TICKS = 2L;

    private final ArcExcellentCratesPlugin plugin;
    private final CrateVisualSettingsStore settings;
    private final NamespacedKey marker;
    private final Map<Anchor, Faces> displays = new HashMap<>();
    private final Map<UUID, Map<Anchor, Boolean>> visibleFaces = new HashMap<>();
    private final BukkitTask reconcileTask;
    private final BukkitTask visibilityTask;

    private boolean enabled;

    CrateHologramService(ArcExcellentCratesPlugin plugin, CrateVisualSettingsStore settings) {
        this.plugin = plugin;
        this.settings = settings;
        this.marker = new NamespacedKey(plugin, "case_hologram");
        reloadSettings();
        removeOrphans();
        reconcile();
        this.reconcileTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::reconcile,
                RECONCILE_TICKS, RECONCILE_TICKS);
        this.visibilityTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::updateVisibility,
                VISIBILITY_TICKS, VISIBILITY_TICKS);
    }

    void reload() {
        reloadSettings();
        reconcile();
    }

    void refresh(CrateVisualSettingsStore.Anchor ignored) {
        reconcile();
    }

    private void reloadSettings() {
        enabled = plugin.getConfig().getBoolean("case-holograms.enabled", true);
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
        updateVisibility();
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

        Faces faces = entities(anchor);
        if (faces == null) {
            remove(anchor);
            TextDisplay front = spawn(world, source.position(), anchor, "front");
            TextDisplay back = spawn(world, source.position(), anchor, "back");
            faces = new Faces(front.getUniqueId(), back.getUniqueId());
            displays.put(anchor, faces);
        }
        CrateVisualSettingsStore.Hologram visual = settings.get(anchor.settingsAnchor()).hologram();
        style(entity(faces.front()), source.crate(), source.position(), visual, visual.yaw());
        style(entity(faces.back()), source.crate(), source.position(), visual, visual.yaw());
    }

    private TextDisplay spawn(World world, WorldPos position, Anchor anchor, String face) {
        var visual = settings.get(anchor.settingsAnchor()).hologram();
        return world.spawn(displayLocation(position, visual), TextDisplay.class, display -> {
            display.getPersistentDataContainer().set(marker, PersistentDataType.STRING, anchor.key() + "|" + face);
            display.setPersistent(false);
            display.setVisibleByDefault(false);
            display.setInvulnerable(true);
            display.setSilent(true);
            display.setGravity(false);
        });
    }

    private void style(TextDisplay display, Crate crate, WorldPos position,
                       CrateVisualSettingsStore.Hologram visual, float faceYaw) {
        Location location = displayLocation(position, visual);
        if (!display.getLocation().toVector().equals(location.toVector())) {
            display.teleport(location);
        }
        display.text(MINI_MESSAGE.deserialize(renderText(visual.textTemplate(), crate.getName())));
        // Client-side billboard rotation keeps the label readable while each
        // viewer approaches the crate from a different direction.
        display.setBillboard(Display.Billboard.CENTER);
        display.setRotation(faceYaw, visual.pitch());
        display.setAlignment(TextDisplay.TextAlignment.CENTER);
        display.setLineWidth(512);
        display.setDefaultBackground(false);
        display.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
        display.setTextOpacity((byte) -1);
        display.setShadowed(true);
        display.setSeeThrough(false);
        display.setBrightness(new Display.Brightness(15, 15));
        display.setViewRange(visual.viewRange());
        display.setInterpolationDuration(0);
        display.setTeleportDuration(0);

        Transformation current = display.getTransformation();
        display.setTransformation(new Transformation(
                current.getTranslation(),
                current.getLeftRotation(),
                new Vector3f(visual.scale(), visual.scale(), visual.scale()),
                current.getRightRotation()
        ));
    }

    private Location displayLocation(WorldPos position, CrateVisualSettingsStore.Hologram visual) {
        Block block = position.toBlock();
        double top = block == null ? position.getY() + 1.0 : block.getBoundingBox().getMaxY();
        return new Location(position.getWorld(), position.getX() + 0.5 + visual.offsetX(), top + visual.offsetY(),
                position.getZ() + 0.5 + visual.offsetZ(), visual.yaw(), visual.pitch());
    }

    private TextDisplay entity(UUID id) {
        return plugin.getServer().getEntity(id) instanceof TextDisplay display && display.isValid()
                ? display
                : null;
    }

    private Faces entities(Anchor anchor) {
        Faces faces = displays.get(anchor);
        return faces != null && entity(faces.front()) != null && entity(faces.back()) != null ? faces : null;
    }

    private void updateVisibility() {
        Set<UUID> online = new HashSet<>();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            online.add(player.getUniqueId());
            Map<Anchor, Boolean> state = visibleFaces.computeIfAbsent(player.getUniqueId(), ignored -> new HashMap<>());
            for (Map.Entry<Anchor, Faces> entry : displays.entrySet()) {
                TextDisplay front = entity(entry.getValue().front());
                TextDisplay back = entity(entry.getValue().back());
                if (front == null || back == null || front.getWorld() != player.getWorld()) {
                    state.remove(entry.getKey());
                    continue;
                }
                Location location = front.getLocation();
                boolean showFront = frontFaces(location, player.getEyeLocation());
                Boolean previous = state.put(entry.getKey(), showFront);
                if (previous != null && previous == showFront) continue;
                if (showFront) {
                    player.showEntity(plugin, front);
                    player.hideEntity(plugin, back);
                } else {
                    player.hideEntity(plugin, front);
                    player.showEntity(plugin, back);
                }
            }
        }
        visibleFaces.keySet().removeIf(id -> !online.contains(id));
    }

    private void remove(Anchor anchor) {
        Faces faces = displays.remove(anchor);
        if (faces == null) return;
        var front = plugin.getServer().getEntity(faces.front());
        var back = plugin.getServer().getEntity(faces.back());
        if (front != null) front.remove();
        if (back != null) back.remove();
        visibleFaces.values().forEach(state -> state.remove(anchor));
    }

    static boolean frontFaces(Location display, Location viewer) {
        return display.getDirection().dot(viewer.toVector().subtract(display.toVector())) >= 0.0;
    }

    static String renderText(String template, String crateName) {
        return template.replace("%crate_name%", crateName);
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
        reconcileTask.cancel();
        visibilityTask.cancel();
        removeAll();
        restoreNative();
    }

    private record Source(Crate crate, WorldPos position) {
    }

    private record Faces(UUID front, UUID back) { }

    private record Anchor(String crateId, String world, int x, int y, int z) {
        static Anchor of(Crate crate, WorldPos position) {
            return new Anchor(crate.getId(), position.getWorldName(), position.getX(), position.getY(), position.getZ());
        }

        String key() {
            return crateId + "|" + world + "|" + x + "|" + y + "|" + z;
        }

        CrateVisualSettingsStore.Anchor settingsAnchor() {
            return new CrateVisualSettingsStore.Anchor(world, x, y, z);
        }
    }
}
