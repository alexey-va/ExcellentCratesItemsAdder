package ru.ruscrafting.ecia;

import org.bukkit.GameMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtectionPolicyTest {
    @Test
    void protectsOnlyRegisteredItemsAdderFurnitureFromCreativePlayers() {
        assertTrue(ProtectionPolicy.shouldProtect(GameMode.CREATIVE, false, true, true));
        assertFalse(ProtectionPolicy.shouldProtect(GameMode.SURVIVAL, false, true, true));
        assertFalse(ProtectionPolicy.shouldProtect(GameMode.CREATIVE, true, true, true));
        assertFalse(ProtectionPolicy.shouldProtect(GameMode.CREATIVE, false, false, true));
        assertFalse(ProtectionPolicy.shouldProtect(GameMode.CREATIVE, false, true, false));
    }
}
