package ru.ruscrafting.ecia;

import org.bukkit.Location;

import java.util.Optional;

public record CratePosition(String world, int x, int y, int z) {
    public static Optional<CratePosition> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String[] parts = raw.split(",", 4);
        if (parts.length != 4) {
            return Optional.empty();
        }
        try {
            String world = parts[3].trim();
            if (world.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new CratePosition(
                    world,
                    Integer.parseInt(parts[0].trim()),
                    Integer.parseInt(parts[1].trim()),
                    Integer.parseInt(parts[2].trim())
            ));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    public static CratePosition from(Location location) {
        return new CratePosition(
                location.getWorld().getName(),
                location.getBlockX(),
                location.getBlockY(),
                location.getBlockZ()
        );
    }
}
