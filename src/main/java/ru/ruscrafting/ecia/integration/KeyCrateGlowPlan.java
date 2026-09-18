package ru.ruscrafting.ecia.integration;

import java.util.List;
import java.util.Objects;

/** Pure matching and distance policy for the held-key crate locator. */
final class KeyCrateGlowPlan {
    private KeyCrateGlowPlan() {
    }

    record HeldKey(String keyId, String season) {
        HeldKey {
            Objects.requireNonNull(keyId, "keyId");
            Objects.requireNonNull(season, "season");
        }
    }

    record Target(String crateId, String keyId, String season, String world, int x, int y, int z) {
        Target {
            Objects.requireNonNull(crateId, "crateId");
            Objects.requireNonNull(keyId, "keyId");
            Objects.requireNonNull(season, "season");
            Objects.requireNonNull(world, "world");
        }
    }

    static List<Target> select(HeldKey held, String world, double x, double y, double z,
            double range, List<Target> targets) {
        if (held == null || world == null || !Double.isFinite(range) || range <= 0.0D) return List.of();
        double rangeSquared = range * range;
        return targets.stream()
                .filter(target -> target.keyId().equals(held.keyId()))
                .filter(target -> target.world().equals(world))
                .filter(target -> distanceSquared(target, x, y, z) <= rangeSquared)
                .toList();
    }

    private static double distanceSquared(Target target, double x, double y, double z) {
        double dx = target.x() + .5D - x;
        double dy = target.y() + .5D - y;
        double dz = target.z() + .5D - z;
        return dx * dx + dy * dy + dz * dz;
    }
}
