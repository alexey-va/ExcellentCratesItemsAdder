package ru.ruscrafting.ecia;

import org.bukkit.block.Block;
import ru.ruscrafting.ecia.integration.ItemsAdderFurnitureAccess;
import su.nightexpress.excellentcrates.util.pos.WorldPos;

/** Read-only Paper-thread check: a saved anchor without its shell must not render ghost effects. */
final class CrateShellPresence {
    private CrateShellPresence() { }

    static boolean isPresent(WorldPos position, ItemsAdderFurnitureAccess furniture) {
        if (position.getWorld() == null || !position.isChunkLoaded()) return false;
        Block block = position.toBlock();
        // Some furniture has an entity-only carrier; AIR does not always mean a deleted shell.
        return !block.getType().isAir() || furniture.at(block).isPresent();
    }
}
