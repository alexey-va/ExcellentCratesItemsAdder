package ru.ruscrafting.ecia;

import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.ServicesManager;
import org.bukkit.plugin.java.JavaPlugin;
import ru.ruscrafting.ecia.api.CrateLocationService;

import java.util.Objects;

/** Owns one optional public service registration for this plugin lifecycle. */
final class CrateLocationServiceRegistration implements AutoCloseable {
    private final ServicesManager servicesManager;
    private final CrateLocationService provider;
    private boolean closed;

    CrateLocationServiceRegistration(JavaPlugin owner, CrateLocationService provider) {
        Objects.requireNonNull(owner, "owner");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.servicesManager = owner.getServer().getServicesManager();
        servicesManager.register(CrateLocationService.class, provider, owner, ServicePriority.Normal);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        servicesManager.unregister(CrateLocationService.class, provider);
    }
}
