package ru.ruscrafting.ecia;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;
import su.nightexpress.excellentcrates.crate.CrateManager;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Vector3f;
import ru.arc.paper.display.PacketTextDisplay;
import ru.arc.paper.display.PaperPacketDisplays;
import ru.ruscrafting.ecia.integration.ItemsAdderFurnitureAccess;
import su.nightexpress.excellentcrates.CratesAPI;
import su.nightexpress.excellentcrates.crate.impl.Crate;
import su.nightexpress.excellentcrates.hologram.HologramManager;
import su.nightexpress.excellentcrates.util.pos.WorldPos;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Replaces ExcellentCrates' line-per-entity labels with private packet-rendered faces. */
final class CrateHologramService implements AutoCloseable, Listener {
    private final ItemsAdderFurnitureAccess furniture = ItemsAdderFurnitureAccess.create();
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final long RECONCILE_TICKS = 20L;
    private static final long VISIBILITY_TICKS = 2L;

    private final ArcExcellentCratesPlugin plugin;
    private final CrateVisualSettingsStore settings;
    private final PaperPacketDisplays packetDisplays;
    private final NamespacedKey marker;
    private Map<Anchor, Faces> displays = new ConcurrentHashMap<>();
    private Map<UUID, Map<Anchor, FaceVisibility>> visibleFaces = new HashMap<>();
    private Map<Anchor, Restoration> suppressed = new HashMap<>();
    private final BukkitTask reconcileTask;
    private final BukkitTask visibilityTask;

    private boolean enabled;
    private boolean initialized;
    private boolean closed;
    private boolean reconcilePending;
    private boolean visibilityPending;

    CrateHologramService(ArcExcellentCratesPlugin plugin, CrateVisualSettingsStore settings,
                        PaperPacketDisplays packetDisplays) {
        this.plugin = plugin;
        this.settings = settings;
        this.packetDisplays = packetDisplays;
        this.marker = new NamespacedKey(plugin, "case_hologram");
        reloadSettings();
        cleanLegacyDisplays();
        this.reconcileTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::reconcile,
                RECONCILE_TICKS, RECONCILE_TICKS);
        this.visibilityTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::updateVisibility,
                VISIBILITY_TICKS, VISIBILITY_TICKS);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    private void cleanLegacyDisplays() {
        CrateAmbientEffectService.cleanLoadedDisplays(plugin, () -> !closed, this::removeLegacyDisplay)
                .whenComplete((ignored, failure) -> {
            if (failure != null) CrateAmbientEffectService.reportWorkFailure(plugin, "hologram orphan cleanup", failure);
            else if (!closed) { initialized = true; reconcile(); }
        });
    }

    void reload() {
        reloadSettings();
        cleanLegacyDisplays();
    }

    void refresh(CrateVisualSettingsStore.Anchor ignored) {
        reconcile();
    }

    private void reloadSettings() {
        enabled = plugin.getConfig().getBoolean("case-holograms.enabled", true);
    }

    private void reconcile() {
        if (closed || !initialized || reconcilePending) return;
        reconcilePending = true;
        var work = new Supplier<Boolean>() {
            final boolean active = CratesAPI.isLoaded();
            final boolean replace = active && enabled;
            final HologramManager manager = active ? CratesAPI.getPlugin().getHologramManager().orElse(null) : null;
            final Iterator<Crate> crates = active ? CratesAPI.getCrateManager().getCrates().iterator() : Collections.emptyIterator();
            final Set<Anchor> desired = new HashSet<>();
            Iterator<WorldPos> positions = Collections.emptyIterator();
            Iterator<Anchor> stale;
            Iterator<Anchor> oldSuppressions;
            Crate crate;

            @Override public Boolean get() {
                if (oldSuppressions != null) {
                    if (!oldSuppressions.hasNext()) return true;
                    if (!desired.contains(oldSuppressions.next())) oldSuppressions.remove();
                } else if (stale != null) {
                    if (!stale.hasNext()) { oldSuppressions = suppressed.keySet().iterator(); return false; }
                    Anchor anchor = stale.next();
                    if (!desired.contains(anchor)) remove(anchor);
                } else if (positions.hasNext()) {
                    WorldPos position = positions.next();
                    if (manager != null) {
                        if (replace) {
                            manager.disableBlockHologram(crate, position);
                            suppressed.put(Anchor.of(crate, position), new Restoration(manager, CratesAPI.getPlugin(),
                                    CratesAPI.getCrateManager(), new Source(crate, position)));
                        } else {
                            manager.enableBlockHologram(crate, position);
                            suppressed.remove(Anchor.of(crate, position));
                        }
                    }
                    if (replace) {
                        Anchor anchor = Anchor.of(crate, position);
                        desired.add(anchor);
                        reconcileDisplay(anchor, new Source(crate, position));
                    }
                } else if (crates.hasNext()) {
                    crate = crates.next();
                    if (crate.isHologramEnabled()) positions = crate.getBlockPositions().iterator();
                } else stale = displays.keySet().iterator();
                return false;
            }
        };
        plugin.runtime().nativeWork().submit(() -> !closed, work::get).whenComplete((ignored, failure) -> {
            reconcilePending = false;
            if (failure != null) CrateAmbientEffectService.reportWorkFailure(plugin, "hologram reconciliation", failure);
        });
    }

    private void reconcileDisplay(Anchor anchor, Source source) {
        if (!CrateShellPresence.isPresent(source.position(), furniture)) {
            remove(anchor);
            return;
        }

        Faces faces = entities(anchor);
        if (faces == null) {
            remove(anchor);
            PacketTextDisplay front = spawn(source.position(), anchor);
            PacketTextDisplay back;
            try { back = spawn(source.position(), anchor); }
            catch (RuntimeException failure) { front.remove(); throw failure; }
            faces = new Faces(front, back);
            displays.put(anchor, faces);
        }
        CrateVisualSettingsStore.Hologram visual = settings.get(anchor.settingsAnchor()).hologram();
        style(faces.front(), source.crate(), source.position(), visual);
        style(faces.back(), source.crate(), source.position(), visual);
    }

    private PacketTextDisplay spawn(WorldPos position, Anchor anchor) {
        var visual = settings.get(anchor.settingsAnchor()).hologram();
        PacketTextDisplay display = packetDisplays.spawnText(displayLocation(position, visual), Component.empty());
        display.setVisibleByDefault(false);
        return display;
    }

    private void style(PacketTextDisplay display, Crate crate, WorldPos position,
                       CrateVisualSettingsStore.Hologram visual) {
        Location location = displayLocation(position, visual);
        if (!display.getLocation().equals(location)) display.teleport(location);
        display.text(MINI_MESSAGE.deserialize(renderText(visual.textTemplate(), crate.getName())));
        // Client-side billboard rotation keeps the label readable while each
        // viewer approaches the crate from a different direction.
        display.setBillboard(Display.Billboard.CENTER);
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

    private Faces entities(Anchor anchor) {
        Faces faces = displays.get(anchor);
        return faces != null && faces.front().isValid() && faces.back().isValid() ? faces : null;
    }

    private void updateVisibility() {
        if (closed || !initialized || visibilityPending) return;
        visibilityPending = true;
        var work = new Supplier<Boolean>() {
            final Iterator<? extends Player> players = plugin.getServer().getOnlinePlayers().iterator();
            Iterator<Map.Entry<Anchor, Faces>> anchors = Collections.emptyIterator();
            Iterator<Anchor> stale;
            Iterator<UUID> offline;
            Player player;
            Map<Anchor, FaceVisibility> state;

            @Override public Boolean get() {
                if (offline != null) {
                    if (!offline.hasNext()) return true;
                    Player current = plugin.getServer().getPlayer(offline.next());
                    if (current == null || !current.isOnline()) offline.remove();
                } else if (stale != null) {
                    if (stale.hasNext()) {
                        if (!displays.containsKey(stale.next())) stale.remove();
                    } else { stale = null; player = null; }
                } else if (player != null && anchors.hasNext()) {
                    var entry = anchors.next();
                    PacketTextDisplay front = entry.getValue().front();
                    PacketTextDisplay back = entry.getValue().back();
                    Location location = front.getLocation();
                    if (!player.isOnline() || !front.isValid() || !back.isValid() || location.getWorld() != player.getWorld()) {
                        state.remove(entry.getKey());
                        return false;
                    }
                    boolean showFront = frontFaces(location, player.getEyeLocation());
                    FaceVisibility next = new FaceVisibility(entry.getValue(), showFront);
                    if (next.equals(state.put(entry.getKey(), next))) return false;
                    if (showFront) { front.showTo(player); back.hideFrom(player); }
                    else { front.hideFrom(player); back.showTo(player); }
                } else if (player != null) {
                    stale = state.keySet().iterator();
                } else if (players.hasNext()) {
                    player = players.next();
                    state = visibleFaces.computeIfAbsent(player.getUniqueId(), ignored -> new HashMap<>());
                    anchors = displays.entrySet().iterator();
                } else offline = visibleFaces.keySet().iterator();
                return false;
            }
        };
        plugin.runtime().nativeWork().submit(() -> !closed, work::get).whenComplete((ignored, failure) -> {
            visibilityPending = false;
            if (failure != null) CrateAmbientEffectService.reportWorkFailure(plugin, "hologram visibility", failure);
        });
    }

    private void remove(Anchor anchor) {
        Faces faces = displays.remove(anchor);
        if (faces == null) return;
        faces.front().remove();
        faces.back().remove();
        // Visibility remembers entity identities; the next bounded sweep forgets removed anchors.
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        if (closed) return;
        Iterator<Entity> entities = event.getEntities().iterator();
        plugin.runtime().nativeWork().submit(() -> !closed, () -> {
            if (!entities.hasNext()) return true;
            removeLegacyDisplay(entities.next());
            return false;
        }).whenComplete((ignored, failure) -> {
            if (failure != null) CrateAmbientEffectService.reportWorkFailure(plugin, "legacy hologram cleanup", failure);
        });
    }

    private void removeLegacyDisplay(Entity entity) {
        if (entity instanceof TextDisplay && entity.getPersistentDataContainer().has(marker, PersistentDataType.STRING)) entity.remove();
    }

    static boolean frontFaces(Location display, Location viewer) {
        return display.getDirection().dot(viewer.toVector().subtract(display.toVector())) >= 0.0;
    }

    static String renderText(String template, String crateName) {
        return template.replace("%crate_name%", crateName);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        HandlerList.unregisterAll(this);
        reconcileTask.cancel();
        visibilityTask.cancel();
        // Retire this service's desired handles before the shared owner's tracked closeAsync drain.
        displays.values().forEach(faces -> { faces.front().remove(); faces.back().remove(); });
        Iterator<Restoration> restorations = suppressed.values().iterator();
        displays = new ConcurrentHashMap<>();
        suppressed = new HashMap<>();
        visibleFaces = new HashMap<>();
        var cleanup = new Supplier<Boolean>() {
            @Override public Boolean get() {
                if (restorations.hasNext()) {
                    Restoration restore = restorations.next();
                    Source source = restore.source();
                    if (restore.provider().isEnabled() && restore.crates().getCrateById(source.crate().getId()) == source.crate()
                            && source.position().isChunkLoaded()) {
                        restore.manager().enableBlockHologram(source.crate(), source.position());
                    }
                    restorations.remove();
                } else return true;
                return false;
            }
        };
        plugin.runtime().submitNativeCleanup("holograms", cleanup::get);
    }

    private record Restoration(HologramManager manager, Plugin provider, CrateManager crates, Source source) { }

    private record FaceVisibility(Faces entities, boolean front) { }

    private record Source(Crate crate, WorldPos position) {
    }

    private record Faces(PacketTextDisplay front, PacketTextDisplay back) { }

    private record Anchor(String crateId, String world, int x, int y, int z) {
        static Anchor of(Crate crate, WorldPos position) {
            return new Anchor(crate.getId(), position.getWorldName(), position.getX(), position.getY(), position.getZ());
        }

        CrateVisualSettingsStore.Anchor settingsAnchor() {
            return new CrateVisualSettingsStore.Anchor(world, x, y, z);
        }
    }
}
