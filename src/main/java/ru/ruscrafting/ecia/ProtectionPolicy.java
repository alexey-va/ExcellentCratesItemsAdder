package ru.ruscrafting.ecia;

import org.bukkit.GameMode;

final class ProtectionPolicy {
    private ProtectionPolicy() {
    }

    static boolean shouldProtect(GameMode gameMode, boolean editMode, boolean itemsAdderFurniture, boolean registeredCrate) {
        return gameMode == GameMode.CREATIVE && !editMode && itemsAdderFurniture && registeredCrate;
    }
}
