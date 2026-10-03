package ru.ruscrafting.ecia.journal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import kotlin.Unit;
import ru.arc.persistence.DurableRecordJournal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Durable payment/delivery states; every unresolved state blocks another sale for that player. */
public final class KeyShopJournal {
    private static final Set<String> CASES = Set.of(
            "case_daily", "case_weekly", "case_rank_artisan", "case_rank_knight",
            "case_furniture", "case_collections", "case_mounts");
    private final DurableRecordJournal<Purchase> journal;
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);

    public KeyShopJournal(Path dataDirectory) {
        journal = new DurableRecordJournal<>(dataDirectory, Path.of("key-shop-sales"), 16 * 1024,
                this::encode, this::decode, ignored -> Unit.INSTANCE);
    }

    public synchronized List<Purchase> load() {
        return journal.loadAll().stream().map(entry -> {
            Purchase purchase = entry.getValue();
            if (!entry.getRecordId().equals(purchase.id().toString())) {
                throw new IllegalStateException("Key sale journal identity mismatch");
            }
            return purchase;
        }).toList();
    }

    /** The core journal returns only after its durable readback succeeds. */
    public synchronized Purchase commit(Purchase purchase) {
        return journal.commit(purchase.id().toString(), purchase);
    }

    /** Reserves one unresolved sale per player before any external payment call. */
    public synchronized Purchase prepare(Purchase purchase) {
        List<Purchase> current = load();
        if (current.stream().anyMatch(record -> record.id().equals(purchase.id()))) {
            throw new IllegalStateException("Duplicate key sale id " + purchase.id());
        }
        if (current.stream().anyMatch(record -> record.playerId().equals(purchase.playerId())
                && record.blocksFurtherPurchases())) {
            throw new IllegalStateException("Player already has an unresolved key sale");
        }
        if (purchase.state() != State.PREPARED || purchase.revision() != 0) {
            throw new IllegalArgumentException("A new key sale must start in PREPARED");
        }
        return commit(purchase);
    }

    /** Reloads the durable head and rejects stale or duplicate state transitions. */
    public synchronized Purchase transition(UUID id, int expectedRevision, State next,
            String reason, long now) {
        Purchase current = load().stream().filter(record -> record.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Unknown key sale " + id));
        if (current.revision() != expectedRevision) {
            throw new IllegalStateException("Stale key sale revision " + expectedRevision);
        }
        return commit(current.advance(next, reason, now));
    }

    private byte[] encode(Purchase purchase) {
        try {
            return mapper.writeValueAsBytes(purchase);
        } catch (JsonProcessingException error) {
            throw new UncheckedIOException("Could not encode key sale " + purchase.id(), error);
        }
    }

    private Purchase decode(byte[] bytes) {
        try {
            return mapper.readValue(bytes, Purchase.class);
        } catch (IOException error) {
            throw new UncheckedIOException("Could not decode key sale", error);
        }
    }

    public enum State { PREPARED, CHARGE_ATTEMPTED, CHARGED, DELIVERY_ATTEMPTED, DELIVERED, CANCELLED, REVIEW }

    public record Purchase(
            UUID id,
            UUID playerId,
            String crateId,
            String keyId,
            long priceTokens,
            long createdAt,
            long updatedAt,
            int revision,
            State state,
            String reason) {
        public Purchase {
            Objects.requireNonNull(id);
            Objects.requireNonNull(playerId);
            Objects.requireNonNull(crateId);
            Objects.requireNonNull(keyId);
            Objects.requireNonNull(state);
            Objects.requireNonNull(reason);
            if (!CASES.contains(crateId) || !keyId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
                throw new IllegalArgumentException("Invalid key sale target");
            }
            if (priceTokens < 1 || priceTokens > 1_000_000 || createdAt < 0 || updatedAt < createdAt || revision < 0) {
                throw new IllegalArgumentException("Invalid key sale counters");
            }
            if (reason.length() > 64 || !reason.matches("[a-z0-9_:-]{1,64}")) {
                throw new IllegalArgumentException("Invalid key sale reason");
            }
        }

        public static Purchase prepared(UUID id, UUID playerId, String crateId, String keyId,
                long priceTokens, long now) {
            return new Purchase(id, playerId, crateId, keyId, priceTokens, now, now, 0, State.PREPARED, "prepared");
        }

        public boolean blocksFurtherPurchases() {
            return state != State.DELIVERED && state != State.CANCELLED;
        }

        public Purchase advance(State next, String nextReason, long now) {
            if (!canAdvance(state, next)) {
                throw new IllegalStateException("Illegal key sale transition " + state + " -> " + next);
            }
            long nextTime = Math.max(now, Math.addExact(updatedAt, 1));
            return new Purchase(id, playerId, crateId, keyId, priceTokens, createdAt, nextTime,
                    Math.incrementExact(revision), next, nextReason);
        }

        private static boolean canAdvance(State current, State next) {
            if (next == State.REVIEW) return current != State.DELIVERED && current != State.CANCELLED && current != State.REVIEW;
            return switch (current) {
                case PREPARED -> next == State.CHARGE_ATTEMPTED || next == State.CANCELLED;
                case CHARGE_ATTEMPTED -> next == State.CHARGED || next == State.CANCELLED;
                case CHARGED -> next == State.DELIVERY_ATTEMPTED;
                case DELIVERY_ATTEMPTED -> next == State.DELIVERED;
                case DELIVERED, CANCELLED, REVIEW -> false;
            };
        }
    }
}
