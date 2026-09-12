package ru.ruscrafting.ecia;

import org.bukkit.entity.EntityType;

final class FurnitureCarrierPolicy {
    private FurnitureCarrierPolicy() {
    }

    static boolean isKnownCarrier(EntityType type) {
        return switch (type) {
            case ITEM_DISPLAY, BLOCK_DISPLAY, INTERACTION, ARMOR_STAND -> true;
            default -> false;
        };
    }
}
