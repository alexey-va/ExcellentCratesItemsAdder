package ru.ruscrafting.ecia.journal;

import ru.ruscrafting.ecia.integration.PeriodicVirtualOpening;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** One physical replacement for an existing personal calendar entitlement. */
public record PeriodicKeyGrant(UUID id, UUID playerId, String crateId,
        PeriodicVirtualOpening.Period period, long start, long expires,
        State state, String witness) {
    public enum State { PREPARED, DELIVERED, RETRY, REVIEW, EXPIRED }

    public PeriodicKeyGrant {
        Objects.requireNonNull(id);
        Objects.requireNonNull(playerId);
        Objects.requireNonNull(crateId);
        Objects.requireNonNull(period);
        Objects.requireNonNull(state);
        Objects.requireNonNull(witness);
        if (!crateId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}") || start < 0 || expires <= start
                || witness.isBlank() || witness.length() > 2_000_000) throw new IllegalArgumentException("Invalid periodic key grant");
        var window = new PeriodicVirtualOpening.Window(period, Instant.ofEpochMilli(start), Instant.ofEpochMilli(expires));
        if (!id.equals(PeriodicVirtualOpening.openingId(playerId, crateId, period, window))) {
            throw new IllegalArgumentException("Periodic key grant identity mismatch");
        }
    }

    public PeriodicKeyGrant withState(State next) {
        return new PeriodicKeyGrant(id, playerId, crateId, period, start, expires, next, witness);
    }
}
