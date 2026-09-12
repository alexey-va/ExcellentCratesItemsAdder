package ru.ruscrafting.ecia;

import net.kyori.adventure.text.Component;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class CrateProtectionListener implements Listener {
    private static final NamespacedKey ITEMSADDER_BEHAVIOUR = NamespacedKey.fromString("itemsadder:placeable_behaviour_type");
    private static final long FEEDBACK_INTERVAL_NANOS = Duration.ofSeconds(1).toNanos();

    private final CrateRegistry registry;
    private final JavaPlugin plugin;
    private final Component protectedMessage;
    private final String previewCommand;
    private final Set<UUID> editors = new HashSet<>();
    private final Map<UUID, Long> lastFeedback = new HashMap<>();

    CrateProtectionListener(CrateRegistry registry, JavaPlugin plugin, Component protectedMessage, String previewCommand) {
        this.registry = registry;
        this.plugin = plugin;
        this.protectedMessage = protectedMessage;
        this.previewCommand = previewCommand;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onFurnitureDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) {
            return;
        }
        Entity target = event.getEntity();
        boolean furniture = isFurnitureCarrier(target);
        boolean registered = target.getWorld() != null && registry.contains(CratePosition.from(target.getLocation()));
        if (!ProtectionPolicy.shouldProtect(player.getGameMode(), editors.contains(player.getUniqueId()), furniture, registered)) {
            return;
        }
        event.setCancelled(true);
        feedbackAndPreview(player, CratePosition.from(target.getLocation()));
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
        editors.clear();
        lastFeedback.clear();
    }

    private boolean isFurnitureCarrier(Entity target) {
        String behaviour = target.getPersistentDataContainer().get(
                ITEMSADDER_BEHAVIOUR,
                PersistentDataType.STRING
        );
        return "furniture".equals(behaviour) || FurnitureCarrierPolicy.isKnownCarrier(target.getType());
    }

    private void feedbackAndPreview(Player player, CratePosition position) {
        long now = System.nanoTime();
        Long previous = lastFeedback.put(player.getUniqueId(), now);
        if (previous == null || now - previous >= FEEDBACK_INTERVAL_NANOS) {
            player.sendActionBar(protectedMessage);
            registry.crateId(position).ifPresent(crateId -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                String command = previewCommand
                        .replace("<crate>", crateId)
                        .replace("<player>", player.getName());
                if (!plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), command)) {
                    plugin.getLogger().warning("Preview command was not handled for crate " + crateId);
                }
            }));
        }
    }
}
