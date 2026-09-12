package ru.ruscrafting.ecia.admin;

import ru.ruscrafting.ecia.CratePosition;
import su.nightexpress.excellentcrates.crate.cost.Cost;
import su.nightexpress.excellentcrates.crate.cost.entry.impl.KeyCostEntry;
import su.nightexpress.excellentcrates.crate.impl.Crate;
import su.nightexpress.excellentcrates.util.pos.WorldPos;

import java.util.Comparator;
import java.util.List;

/**
 * Converts the exact EC 6.6.1 in-memory crate to the admin boundary. A cost
 * is reported only when the native definition has exactly one cost containing
 * exactly one key entry; ambiguous/free/currency-only definitions stay null
 * and therefore fail closed in the admin service.
 */
public final class ExcellentCratesDescriptorFactory {
    private ExcellentCratesDescriptorFactory() {
    }

    public static AdminCrateDescriptor from(Crate crate) {
        if (crate == null) throw new IllegalArgumentException("Crate is required");
        List<CratePosition> positions = crate.getBlockPositions().stream()
                .map(ExcellentCratesDescriptorFactory::position)
                .sorted(Comparator.comparing(CratePosition::world)
                        .thenComparingInt(CratePosition::x)
                        .thenComparingInt(CratePosition::y)
                        .thenComparingInt(CratePosition::z))
                .toList();
        return new AdminCrateDescriptor(crate.getId(), positions, keyCost(crate));
    }

    private static AdminCrateDescriptor.NativeKeyCost keyCost(Crate crate) {
        List<Cost> costs = crate.getCosts();
        if (costs.size() != 1) return null;
        Cost cost = costs.getFirst();
        if (cost.getEntries().size() != 1 || !(cost.getEntries().getFirst() instanceof KeyCostEntry key)) return null;
        return new AdminCrateDescriptor.NativeKeyCost(cost.getId(), key.getKeyId(), key.getAmount(), cost.isEnabled());
    }

    private static CratePosition position(WorldPos worldPos) {
        return new CratePosition(worldPos.getWorldName(), worldPos.getX(), worldPos.getY(), worldPos.getZ());
    }
}
