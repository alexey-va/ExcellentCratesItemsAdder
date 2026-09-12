package ru.ruscrafting.ecia.integration;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.plugin.java.JavaPlugin;
import su.nightexpress.excellentcrates.CratesAPI;
import su.nightexpress.excellentcrates.api.addon.CratesAddon;
import su.nightexpress.excellentcrates.api.event.CrateOpenEvent;
import su.nightexpress.excellentcrates.crate.impl.Crate;
import su.nightexpress.excellentcrates.crate.listener.CrateListener;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * EC 6.6.1 checks inventory capacity before its cancellable opening event and
 * ignores cancellation on block interaction. Replace only that exact native
 * handler, forwarding previews, link tools and unmanaged cases unchanged.
 */
public final class NativeCrateInteractionRouter implements Listener, CratesAddon, AutoCloseable {
    private final JavaPlugin plugin;
    private final Predicate<String> managed;
    private final BiConsumer<Player, Crate> open;
    private final BiConsumer<Player, Crate> preview;
    private final Consumer<Player> nativeOpenDenied;
    private final Predicate<Player> editor;
    private final Runnable reloaded;
    private final ThreadLocal<Set<CrateOpenEvent>> selfPermits = ThreadLocal.withInitial(HashSet::new);
    private RegisteredListener nativeHandler;
    private RegisteredListener wrapper;
    private Plugin boundCratesPlugin;
    private boolean attached;
    private boolean closed;

    public NativeCrateInteractionRouter(JavaPlugin plugin, Predicate<String> managed,
            BiConsumer<Player, Crate> open, BiConsumer<Player, Crate> preview, Consumer<Player> nativeOpenDenied,
            Predicate<Player> editor, Runnable reloaded) {
        this.plugin = plugin;
        this.managed = managed;
        this.open = open;
        this.preview = preview;
        this.nativeOpenDenied = nativeOpenDenied;
        this.editor = editor;
        this.reloaded = reloaded;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        attachIfAvailable();
    }

    private boolean attachIfAvailable() {
        if (closed) return false;
        if (!CratesAPI.isLoaded()) {
            detach(false);
            return false;
        }
        Plugin current = CratesAPI.plugin();
        if (current == null || !current.isEnabled()) {
            detach(false);
            return false;
        }
        if (attached && boundCratesPlugin == current && wrapper != null) return true;
        var handlers = PlayerInteractEvent.getHandlerList();
        var matches = Arrays.stream(handlers.getRegisteredListeners())
                .filter(handler -> handler.getPlugin() == current
                        && handler.getListener() instanceof CrateListener).toList();
        if (matches.size() != 1 || matches.getFirst().getPriority() != EventPriority.HIGH) {
            detach(false);
            plugin.getLogger().warning("ExcellentCrates interaction listener is unavailable; managed openings stay blocked");
            return false;
        }
        if (wrapper != null) handlers.unregister(wrapper);
        nativeHandler = matches.getFirst();
        RegisteredListener original = nativeHandler;
        wrapper = new RegisteredListener(this, (listener, event) -> {
            if (!(event instanceof PlayerInteractEvent interaction) || !route(interaction)) original.callEvent(event);
        }, original.getPriority(), plugin, original.isIgnoringCancelled());
        handlers.unregister(original);
        handlers.register(wrapper);
        boundCratesPlugin = current;
        attached = true;
        if (!CratesAPI.plugin().getAddons().contains(this)) CratesAPI.registerAddon(this);
        return true;
    }

    /**
     * Runs EC's cancellable pre-open contract before the durable key debit.
     * The router's own listener is bypassed only for this exact event instance;
     * other listeners can still cancel it.
     */
    public boolean allowManagedOpen(Player player, Crate crate) {
        if (closed || !attached || !managed.test(crate.getId())) return false;
        CrateOpenEvent event = new CrateOpenEvent(crate, player);
        Set<CrateOpenEvent> permits = selfPermits.get();
        permits.add(event);
        try {
            plugin.getServer().getPluginManager().callEvent(event);
            return !event.isCancelled();
        } finally {
            permits.remove(event);
            if (permits.isEmpty()) selfPermits.remove();
        }
    }

    private boolean route(PlayerInteractEvent event) {
        boolean rightClick = event.getAction() == Action.RIGHT_CLICK_BLOCK || event.getAction() == Action.RIGHT_CLICK_AIR;
        boolean leftClick = event.getAction() == Action.LEFT_CLICK_BLOCK || event.getAction() == Action.LEFT_CLICK_AIR;
        if ((!rightClick && !leftClick) || event.getHand() != EquipmentSlot.HAND) return false;
        var manager = CratesAPI.getCrateManager();
        var item = event.getItem();
        if (item != null && event.getClickedBlock() != null
                && manager.handleLinkToolInteraction(event.getPlayer(), event.getClickedBlock(), item, event)) return true;
        Crate crate = item == null ? null : manager.getCrateByItem(item);
        boolean portable = crate != null;
        if (crate == null && event.getClickedBlock() != null) crate = manager.getCrateByBlock(event.getClickedBlock());
        if (crate == null || !managed.test(crate.getId())) return false;
        boolean previouslyCancelled = event.isCancelled();
        event.setUseItemInHand(Event.Result.DENY);
        event.setUseInteractedBlock(Event.Result.DENY);
        if (previouslyCancelled) return true;
        if (editor.test(event.getPlayer())) return true;
        if (leftClick) {
            preview.accept(event.getPlayer(), crate);
        } else if (portable) {
            // Portable native crate items add a second consumed source that is
            // absent from this addon's key-only journal contract.
            nativeOpenDenied.accept(event.getPlayer());
        } else {
            open.accept(event.getPlayer(), crate);
        }
        return true;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onNativeOpen(CrateOpenEvent event) {
        if (selfPermits.get().contains(event)) return;
        if (!managed.test(event.getCrate().getId())) return;
        event.setCancelled(true);
        // Native commands can mean free, forced or multi-open. Never reinterpret
        // an event that omits those options as permission to debit a physical key.
        nativeOpenDenied.accept(event.getPlayer());
    }

    @Override public void onInit() { }

    @Override public void onLoad() {
        if (closed) return;
        if (attachIfAvailable()) reloaded.run();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onExcellentCratesEnable(PluginEnableEvent event) {
        if (closed || !isExcellentCrates(event.getPlugin())) return;
        if (attachIfAvailable()) reloaded.run();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onExcellentCratesDisable(PluginDisableEvent event) {
        if (!isExcellentCrates(event.getPlugin())) return;
        detach(false);
    }

    private static boolean isExcellentCrates(Plugin plugin) {
        return plugin != null && "ExcellentCrates".equals(plugin.getName());
    }

    private void detach(boolean restoreNative) {
        var handlers = PlayerInteractEvent.getHandlerList();
        if (wrapper != null) handlers.unregister(wrapper);
        if (restoreNative && nativeHandler != null && nativeHandler.getPlugin().isEnabled()
                && Arrays.stream(handlers.getRegisteredListeners()).noneMatch(listener -> listener == nativeHandler)) {
            handlers.register(nativeHandler);
        }
        wrapper = null;
        nativeHandler = null;
        boundCratesPlugin = null;
        attached = false;
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        Plugin bound = boundCratesPlugin;
        detach(true);
        HandlerList.unregisterAll(this);
        if (CratesAPI.isLoaded() && bound == CratesAPI.plugin()) {
            CratesAPI.plugin().getAddons().remove(this);
        }
    }
}
