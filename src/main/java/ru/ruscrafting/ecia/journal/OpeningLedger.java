package ru.ruscrafting.ecia.journal;

import ru.ruscrafting.ecia.roll.PoolSnapshot;
import ru.ruscrafting.ecia.roll.RewardDefinition;

import java.time.Clock;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static ru.ruscrafting.ecia.journal.OpeningRecord.Stage;

/**
 * Feature-specific opening transitions. Call from a single owned storage executor
 * or behind a frozen player interaction. Persisted revisions reject repeated or
 * stale GUI clicks; an interrupted side effect remains explicit for reconciliation.
 */
public final class OpeningLedger {
    public enum VirtualReservationStatus { CREATED, EXISTING_PERIOD, BLOCKED_BY_ACTIVE }
    public record VirtualReservation(VirtualReservationStatus status, OpeningRecord record) {
        public VirtualReservation {
            Objects.requireNonNull(status);
            Objects.requireNonNull(record);
        }
    }

    private final OpeningStore store;
    private final Clock clock;
    private final Map<UUID, OpeningRecord> records = new HashMap<>();
    private boolean storageUncertain;

    public OpeningLedger(OpeningStore store, Clock clock) {
        this.store = Objects.requireNonNull(store);
        this.clock = Objects.requireNonNull(clock);
        for (OpeningRecord record : store.load()) {
            if (records.putIfAbsent(record.id(), record) != null) {
                throw new IllegalStateException("Duplicate opening record " + record.id());
            }
        }
        Map<UUID, UUID> pending = new HashMap<>();
        for (OpeningRecord record : records.values()) {
            if (blocksOpening(record) && pending.putIfAbsent(record.playerId(), record.id()) != null) {
                throw new IllegalStateException("Multiple pending openings for " + record.playerId());
            }
        }
    }

    public synchronized OpeningRecord reserve(UUID id, UUID player, PoolSnapshot pool,
            List<RewardDefinition> offers, String keyWitness) {
        if (records.containsKey(id)) throw new IllegalStateException("Opening id already exists");
        if (active(player).isPresent()) throw new IllegalStateException("Player has an unresolved opening");
        if (keyWitness.isBlank()) throw new IllegalArgumentException("Key debit witness required");
        long previous = records.values().stream().filter(r -> r.playerId().equals(player))
                .mapToLong(OpeningRecord::createdAt).max().orElse(-1);
        long now = Math.max(clock.millis(), Math.addExact(previous, 1));
        return save(new OpeningRecord(id, player, pool, now, now, 0, Stage.RESERVED,
                offers, 0, "", keyWitness, "", "", ""));
    }

    /**
     * Atomically consumes one local calendar entitlement and freezes its offers.
     * The stable period id is retained forever in this journal; no native virtual
     * balance is incremented or decremented. Invoke only on the storage executor.
     */
    public synchronized VirtualReservation reserveVirtual(UUID id, UUID player, PoolSnapshot pool,
            Supplier<List<RewardDefinition>> offers, String witness) {
        Objects.requireNonNull(offers);
        if (witness == null || !witness.startsWith("virtual:v1:")) {
            throw new IllegalArgumentException("A versioned virtual entitlement witness is required");
        }
        OpeningRecord existing = records.get(id);
        if (existing != null) {
            if (!existing.playerId().equals(player) || !existing.pool().crateId().equals(pool.crateId())
                    || !existing.keyWitness().equals(witness)) {
                throw new IllegalStateException("Virtual entitlement identity conflicts with its durable record");
            }
            return new VirtualReservation(VirtualReservationStatus.EXISTING_PERIOD, existing);
        }
        Optional<OpeningRecord> active = active(player);
        if (active.isPresent()) {
            return new VirtualReservation(VirtualReservationStatus.BLOCKED_BY_ACTIVE, active.get());
        }
        long previous = records.values().stream().filter(r -> r.playerId().equals(player))
                .mapToLong(OpeningRecord::createdAt).max().orElse(-1);
        long now = Math.max(clock.millis(), Math.addExact(previous, 1));
        OpeningRecord created = save(new OpeningRecord(id, player, pool, now, now, 0, Stage.CHOOSING,
                offers.get(), 0, "", witness, "", "", ""));
        return new VirtualReservation(VirtualReservationStatus.CREATED, created);
    }

    public synchronized OpeningRecord debitConfirmed(UUID id, UUID player, long revision) {
        OpeningRecord record = expect(id, player, revision, Stage.RESERVED);
        return change(record, Stage.CHOOSING, record.offers(), record.rerollsUsed(),
                "", "", "", "");
    }

    public synchronized OpeningRecord debitRejected(UUID id, UUID player, long revision, String reason) {
        OpeningRecord record = expect(id, player, revision, Stage.RESERVED);
        return change(record, Stage.ABORTED, record.offers(), record.rerollsUsed(),
                "", "", "", reason);
    }

    public synchronized OpeningRecord reroll(UUID id, UUID player, long revision,
            List<RewardDefinition> offers) {
        OpeningRecord record = expect(id, player, revision, Stage.CHOOSING);
        if (record.rerollsUsed() >= record.pool().maxRerolls()) throw new IllegalStateException("Rerolls exhausted");
        return change(record, Stage.CHOOSING, offers, record.rerollsUsed() + 1,
                "", "", "", "");
    }

    public synchronized OpeningRecord select(UUID id, UUID player, long revision, String rewardId) {
        OpeningRecord record = expect(id, player, revision, Stage.CHOOSING);
        if (record.offers().stream().noneMatch(reward -> reward.id().equals(rewardId))) {
            throw new IllegalArgumentException("Reward is not offered");
        }
        return change(record, Stage.MAIL, record.offers(), record.rerollsUsed(),
                rewardId, "", "", "");
    }

    public synchronized OpeningRecord prepared(UUID id, UUID player, long revision, String payload) {
        OpeningRecord record = expect(id, player, revision, Stage.MAIL);
        if (payload.isBlank()) throw new IllegalArgumentException("Reward payload required");
        if (!record.preparedReward().isEmpty() && !record.preparedReward().equals(payload)) {
            throw new IllegalStateException("Prepared reward is immutable");
        }
        return change(record, Stage.MAIL, record.offers(), record.rerollsUsed(),
                record.selectedRewardId(), payload, "", "");
    }

    public synchronized OpeningRecord deliveryStarted(UUID id, UUID player, long revision, String witness) {
        OpeningRecord record = expect(id, player, revision, Stage.MAIL);
        if (active(player).isPresent()) throw new IllegalStateException("Finish the active opening before claiming mail");
        if (record.preparedReward().isEmpty() || witness.isBlank()) {
            throw new IllegalStateException("Delivery must have durable payload and witness");
        }
        return change(record, Stage.DELIVERING, record.offers(), record.rerollsUsed(),
                record.selectedRewardId(), record.preparedReward(), witness, "");
    }

    public synchronized OpeningRecord deliveryConfirmed(UUID id, UUID player, long revision) {
        OpeningRecord record = expect(id, player, revision, Stage.DELIVERING);
        return change(record, Stage.DELIVERED, record.offers(), record.rerollsUsed(),
                record.selectedRewardId(), record.preparedReward(), record.deliveryWitness(), "");
    }

    /** Only a proven no-mutation outcome may return to the claimable mailbox. */
    public synchronized OpeningRecord deliveryNotApplied(UUID id, UUID player, long revision, String reason) {
        OpeningRecord record = expect(id, player, revision, Stage.DELIVERING);
        return change(record, Stage.MAIL, record.offers(), record.rerollsUsed(),
                record.selectedRewardId(), record.preparedReward(), "", reason);
    }

    public synchronized OpeningRecord review(UUID id, UUID player, long revision, String reason) {
        OpeningRecord record = get(id, player);
        if (record.revision() != revision || (record.stage() != Stage.RESERVED && record.stage() != Stage.DELIVERING)) {
            throw new IllegalStateException("Only an interrupted side effect requires review");
        }
        if (reason.isBlank()) throw new IllegalArgumentException("Review reason required");
        return change(record, Stage.REVIEW, record.offers(), record.rerollsUsed(),
                record.selectedRewardId(), record.preparedReward(), record.deliveryWitness(), reason);
    }

    public synchronized Optional<OpeningRecord> pending(UUID player) {
        return records.values().stream().filter(r -> r.playerId().equals(player) && r.pending())
                .sorted(Comparator.comparingLong(OpeningRecord::createdAt)).findFirst();
    }

    /** Resolve only from a freshly loaded native player-data receipt or an exact preimage. */
    public synchronized OpeningRecord reconciled(UUID id, UUID player, long revision,
            boolean applied, String evidence) {
        OpeningRecord record = expect(id, player, revision, Stage.REVIEW);
        if (evidence.isBlank()) throw new IllegalArgumentException("Reconciliation evidence required");
        boolean debit = record.selectedRewardId().isEmpty();
        Stage target = debit ? (applied ? Stage.CHOOSING : Stage.ABORTED)
                : (applied ? Stage.DELIVERED : Stage.MAIL);
        return change(record, target, record.offers(), record.rerollsUsed(),
                record.selectedRewardId(), record.preparedReward(),
                target == Stage.MAIL ? "" : record.deliveryWitness(), evidence);
    }

    public synchronized Optional<OpeningRecord> active(UUID player) {
        return records.values().stream().filter(r -> r.playerId().equals(player) && blocksOpening(r)).findFirst();
    }

    private static boolean blocksOpening(OpeningRecord record) {
        return record.pending() && record.stage() != Stage.MAIL;
    }

    public synchronized OpeningRecord get(UUID id, UUID player) {
        OpeningRecord record = records.get(id);
        if (record == null || !record.playerId().equals(player)) throw new IllegalArgumentException("Unknown opening");
        return record;
    }

    public synchronized List<OpeningRecord> history(UUID player, int page, int pageSize) {
        if (page < 0 || pageSize < 1 || pageSize > 100) throw new IllegalArgumentException("Invalid history page");
        return records.values().stream().filter(r -> r.playerId().equals(player))
                .sorted(Comparator.comparingLong(OpeningRecord::createdAt).reversed().thenComparing(OpeningRecord::id))
                .skip((long) page * pageSize).limit(pageSize).toList();
    }

    public synchronized List<OpeningRecord> snapshot() { return List.copyOf(records.values()); }

    /** Stable ids are published to the Paper thread as a read-only glow cache. */
    public synchronized java.util.Set<UUID> virtualOpeningIds() {
        return records.values().stream()
                .filter(record -> record.keyWitness().startsWith("virtual:v1:"))
                .map(OpeningRecord::id)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private OpeningRecord expect(UUID id, UUID player, long revision, Stage stage) {
        OpeningRecord record = get(id, player);
        if (record.revision() != revision || record.stage() != stage) {
            throw new IllegalStateException("Opening changed; refresh the menu");
        }
        return record;
    }

    private OpeningRecord change(OpeningRecord record, Stage stage, List<RewardDefinition> offers,
            int rerolls, String selected, String payload, String witness, String reason) {
        return save(new OpeningRecord(record.id(), record.playerId(), record.pool(), record.createdAt(),
                Math.max(clock.millis(), record.updatedAt()), Math.addExact(record.revision(), 1), stage,
                offers, rerolls, selected, record.keyWitness(), payload, witness, reason));
    }

    private OpeningRecord save(OpeningRecord record) {
        if (storageUncertain) throw new IllegalStateException("Opening storage requires plugin restart and reconciliation");
        try {
            OpeningRecord readback = store.commit(record);
            if (!record.equals(readback)) throw new IllegalStateException("Durable opening readback mismatch");
            records.put(record.id(), readback);
            return readback;
        } catch (RuntimeException failure) {
            // A failed readback can follow a successful disk write. Retrying from
            // the old cache would permit another debit or overwrite a selection.
            storageUncertain = true;
            throw failure;
        }
    }

    public synchronized boolean available() { return !storageUncertain; }
}
