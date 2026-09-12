package ru.ruscrafting.ecia.inventory;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Exact native inventory images and the preceding player-data receipt. */
public record InventoryMutationWitness(UUID openingId, UUID playerId, Kind kind,
        List<String> before, List<String> after, String previousReceipt) {
    public enum Kind { KEY_DEBIT, REWARD_DELIVERY }

    public InventoryMutationWitness {
        Objects.requireNonNull(openingId);
        Objects.requireNonNull(playerId);
        Objects.requireNonNull(kind);
        before = List.copyOf(before);
        after = List.copyOf(after);
        Objects.requireNonNull(previousReceipt);
        if (before.size() < 41 || before.size() > 54 || before.size() != after.size() || before.equals(after)) {
            throw new IllegalArgumentException("Invalid player inventory witness: before=" + before.size()
                    + ", after=" + after.size() + ", unchanged=" + before.equals(after));
        }
        if (previousReceipt.length() > 128) throw new IllegalArgumentException("Invalid prior receipt");
    }

    public String receipt() { return kind.name() + ":" + openingId; }
}
