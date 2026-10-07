package ru.ruscrafting.ecia;

import org.bukkit.Location;
import su.nightexpress.excellentcrates.CratesAPI;

/** Isolates the optional native ExcellentCrates linkage from the plugin entrypoint. */
final class NativeExcellentCratesLocationLookup {
    private NativeExcellentCratesLocationLookup() { }

    static boolean isCrateAt(Location location) {
        if (!CratesAPI.isLoaded()) {
            throw new IllegalStateException("ExcellentCrates API is not loaded");
        }
        var crateManager = CratesAPI.getCrateManager();
        if (crateManager == null) {
            throw new IllegalStateException("ExcellentCrates crate manager is unavailable");
        }
        return crateManager.getCrateByBlock(location.getBlock()) != null;
    }
}
