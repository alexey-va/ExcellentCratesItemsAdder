package ru.ruscrafting.ecia;

import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FurnitureCarrierPolicyTest {
    @Test
    void recognizesModernAndLegacyFurnitureCarrierTypes() {
        assertTrue(FurnitureCarrierPolicy.isKnownCarrier(EntityType.ITEM_DISPLAY));
        assertTrue(FurnitureCarrierPolicy.isKnownCarrier(EntityType.BLOCK_DISPLAY));
        assertTrue(FurnitureCarrierPolicy.isKnownCarrier(EntityType.INTERACTION));
        assertTrue(FurnitureCarrierPolicy.isKnownCarrier(EntityType.ARMOR_STAND));
    }

    @Test
    void doesNotTreatPlayersOrDroppedItemsAsFurniture() {
        assertFalse(FurnitureCarrierPolicy.isKnownCarrier(EntityType.PLAYER));
        assertFalse(FurnitureCarrierPolicy.isKnownCarrier(EntityType.ITEM));
    }
}
