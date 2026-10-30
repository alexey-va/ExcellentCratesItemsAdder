package ru.ruscrafting.ecia.api;

import org.bukkit.Location;

import java.util.List;
import java.util.concurrent.CompletableFuture;

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

    /**
     * Returns the configured managed crate IDs while this provider is ready.
     * Call on the owning Paper thread; this reads memory only. The immutable
     * list may include a crate whose current reward capture is unavailable.
     */
    default List<String> acquisitionCrateIds() {
        return List.of();
    }

    /**
     * Captures the same currently loaded, filtered reward pool used for a new
     * managed opening. Call on the owning Paper thread and never wait for the
     * future there. Completion has no thread affinity. No roll, item delivery,
     * key consumption or player-data access is performed.
     *
     * <p>Availability describes the managed provider and its pool, not whether
     * a particular player has permission or the required opening key. Missing,
     * disabled, reloading or closed providers return an unavailable snapshot.
     * Consumers must discard stale completions after their own UI/lifecycle
     * changes and resolve this service at the point of use.</p>
     */
    default CompletableFuture<CrateAcquisitionSnapshot> acquisitionSnapshotAsync(String crateId) {
        return CompletableFuture.completedFuture(CrateAcquisitionSnapshot.unavailable(crateId));
    }
}
