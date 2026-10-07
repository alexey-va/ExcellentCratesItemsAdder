package ru.ruscrafting.ecia.api;

import org.bukkit.Location;

/**
 * Read-only classifier for locations managed by a crate plugin.
 *
 * <p>Consumers should resolve this contract through Bukkit's ServicesManager
 * at the point of use because its provider is optional. Call on the owning
 * Paper thread for an already loaded location. An unavailable service or a
 * provider exception is unknown access, not evidence that the location is safe
 * to inspect. ArcExcellentCrates owns this type at runtime; consumers must use
 * compileOnly and must not shade another copy.</p>
 */
@FunctionalInterface
public interface CrateLocationService {
    /**
     * Returns whether the supplied block location is owned by a managed crate.
     *
     * @param location location to classify
     * @return true when a crate is managed at this location
     */
    boolean isCrateLocation(Location location);
}
