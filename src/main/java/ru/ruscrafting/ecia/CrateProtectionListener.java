package ru.ruscrafting.ecia;

import net.kyori.adventure.text.Component;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;

import ru.ruscrafting.ecia.runtime.EciaRuntime;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;

final class CrateProtectionListener implements Listener {
    private static final NamespacedKey ITEMSADDER_BEHAVIOUR = NamespacedKey.fromString("itemsadder:placeable_behaviour_type");
    private static final long FEEDBACK_INTERVAL_NANOS = Duration.ofSeconds(1).toNanos();
    private static final long ENTITY_DUPLICATE_WINDOW_NANOS = Duration.ofMillis(50).toNanos();

    private final CrateRegistry registry;
    private final EciaRuntime runtime;
    private volatile Component protectedMessage;
    private final String previewCommand;
    private final Set<UUID> editors = new HashSet<>();
    private final Map<UUID, Long> lastFeedback = new HashMap<>();
    private final Map<UUID, EntityInteraction> lastEntityInteractions = new HashMap<>();
    private volatile BiFunction<Player, String, Boolean> managedPreviewHandler;
    private volatile BiFunction<Player, String, Boolean> managedOpenHandler;
    private volatile BiFunction<Player, CrateVisualTarget, Boolean> visualEditorHandler;

    CrateProtectionListener(CrateRegistry registry, EciaRuntime runtime, Component protectedMessage, String previewCommand) {
        this.registry = registry;
        this.runtime = runtime;
        this.protectedMessage = protectedMessage;
        this.previewCommand = previewCommand;
    }

    /**
     * Installs the managed preview route used by the legacy protection feedback
     * event. A handler returning {@code true} owns the preview; {@code false}
     * keeps the existing preview command.
     * The callback runs on Paper's primary thread before any command dispatch.
     */
    void setManagedPreviewHandler(BiFunction<Player, String, Boolean> handler) {
        this.managedPreviewHandler = handler;
    }

    void clearManagedPreviewHandler() {
        this.managedPreviewHandler = null;
    }

    /**
     * Installs the managed opening route for an exact registered furniture
     * anchor. A handler returning {@code true} owns and cancels the entity
     * event; {@code false} preserves native entity interaction behavior.
     */
    void setManagedOpenHandler(BiFunction<Player, String, Boolean> handler) {
        this.managedOpenHandler = handler;
    }

    void clearManagedOpenHandler() {
        this.managedOpenHandler = null;
    }

    void setVisualEditorHandler(BiFunction<Player, CrateVisualTarget, Boolean> handler) {
        this.visualEditorHandler = handler;
    }

    void clearVisualEditorHandler() {
        this.visualEditorHandler = null;
    }

    void setProtectedMessage(Component message) {
        this.protectedMessage = message;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onFurnitureDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) {
            return;
        }
        handleFurnitureDamage(player, event.getEntity(), event);
    }

    void handleFurnitureDamage(Player player, Entity target, Cancellable event) {
        boolean furniture = isFurnitureCarrier(target);
        CratePosition position = CratePosition.from(target.getLocation());
        boolean registered = target.getWorld() != null && registry.contains(position);
        if (!editors.contains(player.getUniqueId()) && furniture && registered && player.isSneaking()
                && openVisualEditor(player, position, target.getLocation())) {
            event.setCancelled(true);
            return;
        }
        if (!ProtectionPolicy.shouldProtect(player.getGameMode(), editors.contains(player.getUniqueId()), furniture, registered)) {
            return;
        }
        event.setCancelled(true);
        feedbackAndPreview(player, position);
    }

    /** Handles the ordinary right-click entity event path. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onFurnitureInteract(PlayerInteractEntityEvent event) {
        handleManagedOpen(event.getPlayer(), event.getRightClicked(), event.getHand(), event);
    }

    /**
     * Paper may deliver the hit-vector subtype through its own HandlerList.
     * Keep this adapter alongside the base event and collapse duplicate
     * delivery in [handleManagedOpen].
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onFurnitureInteractAtEntity(PlayerInteractAtEntityEvent event) {
        handleManagedOpen(event.getPlayer(), event.getRightClicked(), event.getHand(), event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onCrateBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() != GameMode.CREATIVE || editors.contains(player.getUniqueId())) {
            return;
        }
        if (registry.contains(CratePosition.from(event.getBlock().getLocation()))) {
            event.setCancelled(true);
            feedbackAndPreview(player, CratePosition.from(event.getBlock().getLocation()));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        editors.remove(playerId);
        lastFeedback.remove(playerId);
        lastEntityInteractions.remove(playerId);
    }

    boolean setEditMode(Player player, boolean enabled) {
        if (enabled) {
            return editors.add(player.getUniqueId());
        }
        return editors.remove(player.getUniqueId());
    }

    boolean isEditMode(Player player) {
        return editors.contains(player.getUniqueId());
    }

    void clear() {
        visualEditorHandler = null;
        editors.clear();
        lastFeedback.clear();
        lastEntityInteractions.clear();
    }

    private void handleManagedOpen(Player player, Entity target, EquipmentSlot hand, Cancellable event) {
        if (hand != EquipmentSlot.HAND) {
            return;
        }
        boolean furniture = isFurnitureCarrier(target);
        boolean registered = target.getWorld() != null
                && registry.contains(CratePosition.from(target.getLocation()));
        if (editors.contains(player.getUniqueId()) || !furniture || !registered) {
            return;
        }
        CratePosition position = CratePosition.from(target.getLocation());
        String crateId = registry.crateId(position).orElse(null);
        if (crateId == null) {
            return;
        }
        BiFunction<Player, String, Boolean> handler = managedOpenHandler;
        if (handler == null) {
            return;
        }
        UUID playerId = player.getUniqueId();
        UUID entityId = target.getUniqueId();
        long now = System.nanoTime();
        EntityInteraction previous = lastEntityInteractions.get(playerId);
        if (previous != null && previous.entityId().equals(entityId) && previous.hand() == hand
                && now >= previous.atNanos() && now - previous.atNanos() < ENTITY_DUPLICATE_WINDOW_NANOS) {
            if (previous.owned()) {
                event.setCancelled(true);
            }
            return;
        }
        if (event.isCancelled()) return;
        // Mark before invoking owner code so a re-entrant duplicate cannot
        // invoke the durable opening route twice. Update ownership below.
        lastEntityInteractions.put(playerId, new EntityInteraction(entityId, hand, now, false));
        try {
            boolean owned = Boolean.TRUE.equals(handler.apply(player, crateId));
            if (owned) {
                lastEntityInteractions.put(playerId, new EntityInteraction(entityId, hand, now, true));
                event.setCancelled(true);
            }
        } catch (RuntimeException exception) {
            runtime.error("Managed crate opening failed for crate {}", crateId, exception);
        }
    }

    private boolean isFurnitureCarrier(Entity target) {
        String behaviour = target.getPersistentDataContainer().get(
                ITEMSADDER_BEHAVIOUR,
                PersistentDataType.STRING
        );
        return "furniture".equals(behaviour) || FurnitureCarrierPolicy.isKnownCarrier(target.getType());
    }

    private boolean openVisualEditor(Player player, CratePosition position, org.bukkit.Location location) {
        String crateId = registry.crateId(position).orElse(null);
        BiFunction<Player, CrateVisualTarget, Boolean> handler = visualEditorHandler;
        if (crateId == null || handler == null) return false;
        try {
            return Boolean.TRUE.equals(handler.apply(player, new CrateVisualTarget(crateId, location)));
        } catch (RuntimeException exception) {
            runtime.error("Crate visual editor failed for crate {}", crateId, exception);
            return false;
        }
    }

    private void feedbackAndPreview(Player player, CratePosition position) {
        long now = System.nanoTime();
        Long previous = lastFeedback.put(player.getUniqueId(), now);
        if (previous == null || now - previous >= FEEDBACK_INTERVAL_NANOS) {
            player.sendActionBar(protectedMessage);
            registry.crateId(position).ifPresent(crateId -> {
                BiFunction<Player, String, Boolean> handler = managedPreviewHandler;
                if (handler != null) {
                    try {
                        if (Boolean.TRUE.equals(handler.apply(player, crateId))) {
                            return;
                        }
                    } catch (RuntimeException exception) {
                        runtime.error("Managed crate preview failed for crate {}", crateId, exception);
                    }
                }
                runtime.runSync(() -> {
                    String command = previewCommand
                            .replace("<crate>", crateId)
                            .replace("<player>", player.getName());
                    if (!runtime.dispatchConsole(command)) {
                        runtime.warn("Preview command was not handled for crate {}", crateId);
                    }
                });
            });
        }
    }

    private record EntityInteraction(UUID entityId, EquipmentSlot hand, long atNanos, boolean owned) {
    }
}
