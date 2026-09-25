package ru.ruscrafting.ecia.integration;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import ru.ruscrafting.ecia.inventory.InventoryMutationWitness;
import ru.ruscrafting.ecia.inventory.NativeItemPayload;
import ru.ruscrafting.ecia.inventory.OpeningInventoryTransactions;
import ru.ruscrafting.ecia.journal.OpeningLedger;
import ru.ruscrafting.ecia.journal.OpeningRecord;
import ru.ruscrafting.ecia.roll.OfferSet;
import ru.ruscrafting.ecia.roll.PoolSnapshot;
import ru.ruscrafting.ecia.roll.RewardDefinition;
import ru.ruscrafting.ecia.roll.WeightedOfferGenerator;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Coordinates durable opening transitions away from Paper's owning thread.
 * Inventory planning/mutation and reward-provider access are marshalled back to
 * Paper; each durable journal transition must complete before the next effect.
 */
public final class ManagedOpeningEngine {
    public enum VirtualResult { OPENED, RESUMABLE, PERIOD_ALREADY_USED }

    public record VirtualOpening(VirtualResult result, OpeningRecord record) {
        public VirtualOpening {
            Objects.requireNonNull(result);
            Objects.requireNonNull(record);
        }
    }

    private final OpeningLedger ledger;
    private final WeightedOfferGenerator random;
    private final OpeningInventoryTransactions inventory;
    private final NativeItemPayload payload;
    private final java.util.function.Function<RewardDefinition, ItemStack[]> materialize;
    private final Executor storageExecutor;
    private final Executor paperExecutor;
    private final BiPredicate<Player, Long> currentPlayer;

    public ManagedOpeningEngine(OpeningLedger ledger, WeightedOfferGenerator random,
            OpeningInventoryTransactions inventory, NativeItemPayload payload,
            java.util.function.Function<RewardDefinition, ItemStack[]> materialize,
            Executor storageExecutor, Executor paperExecutor, BiPredicate<Player, Long> currentPlayer) {
        this.ledger = Objects.requireNonNull(ledger);
        this.random = Objects.requireNonNull(random);
        this.inventory = Objects.requireNonNull(inventory);
        this.payload = Objects.requireNonNull(payload);
        this.materialize = Objects.requireNonNull(materialize);
        this.storageExecutor = Objects.requireNonNull(storageExecutor);
        this.paperExecutor = Objects.requireNonNull(paperExecutor);
        this.currentPlayer = Objects.requireNonNull(currentPlayer);
    }

    /**
     * Returns an unresolved opening if one exists; otherwise plans a physical
     * key debit on Paper, persists it, applies it on Paper, then persists the
     * outcome. An empty value means the player has no matching physical key.
     */
    public CompletableFuture<Optional<OpeningRecord>> openPhysical(Player player, long session, PoolSnapshot pool,
            Predicate<ItemStack> matchingKey, int keyAmount) {
        return openPhysical(player, session, pool, matchingKey, keyAmount, null);
    }

    public CompletableFuture<Optional<OpeningRecord>> openPeriodicKey(Player player, long session, PoolSnapshot pool,
            Predicate<ItemStack> matchingKey, UUID periodId) {
        return openPhysical(player, session, pool, matchingKey, 1, Objects.requireNonNull(periodId));
    }

    private CompletableFuture<Optional<OpeningRecord>> openPhysical(Player player, long session, PoolSnapshot pool,
            Predicate<ItemStack> matchingKey, int keyAmount, UUID periodId) {
        UUID playerId = player.getUniqueId();
        return storage(() -> new PhysicalStart(ledger.pending(playerId),
                periodId == null || ledger.canSpendPeriodicKey(periodId))).thenCompose(start -> {
            var pending = start.pending();
            // Resume any durable opening (including claimable MAIL) before a new
            // physical debit. MAIL is intentionally not considered active by the
            // ledger, but it must still win over another key spend.
            if (pending.isPresent()) return CompletableFuture.completedFuture(pending);
            if (!start.available()) return CompletableFuture.completedFuture(Optional.empty());
            return paper(() -> {
                requireCurrent(player, session);
                return inventory.debit(player, periodId == null ? UUID.randomUUID() : periodId, matchingKey, keyAmount)
                        .map(witness -> new DebitPlan(witness.openingId(), payload.write(witness)));
            })
                    .thenCompose(plan -> plan.isEmpty()
                            ? CompletableFuture.completedFuture(Optional.empty())
                            : storage(() -> periodId == null
                                    ? ledger.reserve(plan.get().openingId(), playerId, pool,
                                            generateOne(pool).rewards(), plan.get().witness())
                                    : ledger.reservePeriodicKey(periodId, playerId, pool,
                                            () -> generateOne(pool).rewards(), plan.get().witness()))
                                    .thenCompose(record -> paper(() -> {
                                                requireCurrent(player, session);
                                                if (periodId != null && java.util.Arrays.stream(player.getInventory().getContents())
                                                        .filter(Objects::nonNull).filter(item -> !item.isEmpty())
                                                        .noneMatch(matchingKey)) return OpeningInventoryTransactions.Outcome.NOT_APPLIED;
                                                return inventory.apply(player,
                                                        payload.read(record.keyWitness(), InventoryMutationWitness.class));
                                            })
                                            .thenCompose(outcome -> storage(() -> Optional.of(settleDebit(record, playerId, outcome))))));
        });
    }

    private record PhysicalStart(Optional<OpeningRecord> pending, boolean available) { }

    /**
     * The stable opening id is the local calendar entitlement and frozen-roll
     * receipt. A prior CHOOSING/MAIL record can be resumed, but a terminal prior
     * record cannot be reopened or rerolled.
     */
    public CompletableFuture<VirtualOpening> openVirtual(UUID playerId, PoolSnapshot pool,
            PeriodicVirtualOpening.Period period, PeriodicVirtualOpening.Window window) {
        UUID openingId = PeriodicVirtualOpening.openingId(playerId, pool.crateId(), period, window);
        String witness = PeriodicVirtualOpening.witness(pool.crateId(), window);
        return storage(() -> ledger.reserveVirtual(openingId, playerId, pool,
                () -> generateOne(pool).rewards(), witness)).thenApply(reservation -> {
            OpeningRecord record = reservation.record();
            if (reservation.status() == OpeningLedger.VirtualReservationStatus.CREATED) {
                return new VirtualOpening(VirtualResult.OPENED, record);
            }
            if (reservation.status() == OpeningLedger.VirtualReservationStatus.BLOCKED_BY_ACTIVE) {
                return new VirtualOpening(VirtualResult.RESUMABLE, record);
            }
            return new VirtualOpening(record.pending() ? VirtualResult.RESUMABLE : VirtualResult.PERIOD_ALREADY_USED, record);
        });
    }

    public CompletableFuture<OpeningRecord> reroll(Player player, UUID id, long revision) {
        UUID playerId = player.getUniqueId();
        return storage(() -> {
            OpeningRecord current = ledger.get(id, playerId);
            if (current.revision() != revision || current.stage() != OpeningRecord.Stage.CHOOSING) {
                throw new IllegalStateException("Opening changed; refresh the menu");
            }
            OfferSet rolled = rerollOffers(current);
            return ledger.reroll(id, playerId, revision, rolled.rewards());
        });
    }

    public CompletableFuture<OpeningRecord> select(Player player, UUID id, long revision, String rewardId) {
        UUID playerId = player.getUniqueId();
        return storage(() -> ledger.select(id, playerId, revision, rewardId));
    }

    /** Materialization never grants; native provider bytes are durable before inventory mutation. */
    public CompletableFuture<OpeningRecord> claim(Player player, long session, UUID id, long revision) {
        UUID playerId = player.getUniqueId();
        return storage(() -> {
            OpeningRecord record = ledger.get(id, playerId);
            if (record.revision() != revision || record.stage() != OpeningRecord.Stage.MAIL) {
                throw new IllegalStateException("Mail changed; refresh the menu");
            }
            if (ledger.active(playerId).isPresent()) throw new IllegalStateException("An opening is still active");
            List<RewardDefinition> bundle = record.preparedReward().isEmpty() ? bundle(record) : List.of();
            return new ClaimStart(record, bundle);
        }).thenCompose(start -> {
            OpeningRecord record = start.record();
            CompletableFuture<OpeningRecord> prepared;
            if (!record.preparedReward().isEmpty()) {
                prepared = CompletableFuture.completedFuture(record);
            } else {
                prepared = paper(() -> {
                    requireCurrent(player, session);
                    return prepareReward(start.bundle());
                })
                        .thenCompose(result -> result.payload().isEmpty()
                                ? CompletableFuture.completedFuture(record)
                                : storage(() -> ledger.prepared(id, playerId, revision, result.payload())));
            }
            return prepared.thenCompose(value -> {
                if (value.preparedReward().isEmpty()) return CompletableFuture.completedFuture(value);
                return paper(() -> {
                            requireCurrent(player, session);
                            return inventory.delivery(player, id, payload.items(value.preparedReward()))
                                    .map(witness -> new DeliveryPlan(payload.write(witness)));
                        })
                        .thenCompose(plan -> plan.isEmpty()
                                ? CompletableFuture.completedFuture(value)
                                : storage(() -> ledger.deliveryStarted(id, playerId, value.revision(), plan.get().witness()))
                                        .thenCompose(started -> paper(() -> {
                                                    requireCurrent(player, session);
                                                    return inventory.apply(player,
                                                            payload.read(started.deliveryWitness(), InventoryMutationWitness.class));
                                                })
                                                .thenCompose(outcome -> storage(() -> settleDelivery(started, playerId, outcome)))));
            });
        });
    }

    /** Call on reconnect after native player data has loaded; all journal work remains asynchronous. */
    public CompletableFuture<Optional<OpeningRecord>> playerDataLoaded(Player player, long session) {
        UUID playerId = player.getUniqueId();
        return paper(() -> {
            requireCurrent(player, session);
            inventory.playerDataLoaded(player);
            return null;
        }).thenCompose(ignored -> reconcile(playerId, player, session));
    }

    public CompletableFuture<Optional<OpeningRecord>> active(UUID playerId) {
        return storage(() -> ledger.active(playerId));
    }

    public CompletableFuture<Optional<OpeningRecord>> pending(UUID playerId) {
        return storage(() -> ledger.pending(playerId));
    }

    public CompletableFuture<Set<UUID>> virtualOpeningIds() {
        return storage(ledger::virtualOpeningIds);
    }

    public CompletableFuture<Integer> pendingCount() {
        return storage(() -> (int) ledger.snapshot().stream().filter(OpeningRecord::pending).count());
    }

    public CompletableFuture<Boolean> available() {
        return storage(ledger::available);
    }

    private CompletableFuture<Optional<OpeningRecord>> reconcile(UUID playerId, Player player, long session) {
        return storage(() -> ledger.active(playerId)).thenCompose(active -> {
            if (active.isEmpty() || active.get().stage() == OpeningRecord.Stage.CHOOSING) {
                return CompletableFuture.completedFuture(active);
            }
            CompletableFuture<OpeningRecord> marked = active.get().stage() == OpeningRecord.Stage.REVIEW
                    ? CompletableFuture.completedFuture(active.get())
                    : storage(() -> ledger.review(active.get().id(), playerId, active.get().revision(),
                            "interrupted-native-side-effect"));
            return marked.thenCompose(record -> paper(() -> {
                requireCurrent(player, session);
                return inspect(player, record);
            }).thenCompose(outcome -> {
                if (outcome == OpeningInventoryTransactions.Outcome.UNKNOWN) {
                    return CompletableFuture.completedFuture(Optional.of(record));
                }
                return storage(() -> Optional.of(ledger.reconciled(record.id(), playerId, record.revision(),
                        outcome == OpeningInventoryTransactions.Outcome.APPLIED,
                        outcome == OpeningInventoryTransactions.Outcome.APPLIED
                                ? "native-receipt-confirmed" : "native-preimage-confirmed")));
            }));
        });
    }

    private InventoryMutationWitness witness(String encoded, InventoryMutationWitness.Kind kind,
            UUID openingId, UUID playerId) {
        InventoryMutationWitness witness = payload.read(encoded, InventoryMutationWitness.class);
        if (!witness.openingId().equals(openingId) || !witness.playerId().equals(playerId) || witness.kind() != kind) {
            throw new IllegalStateException("Opening witness identity mismatch");
        }
        return witness;
    }

    private OpeningInventoryTransactions.Outcome inspect(Player player, OpeningRecord record) {
        boolean debit = record.selectedRewardId().isEmpty();
        InventoryMutationWitness witness = witness(
                debit ? record.keyWitness() : record.deliveryWitness(),
                debit ? InventoryMutationWitness.Kind.KEY_DEBIT : InventoryMutationWitness.Kind.REWARD_DELIVERY,
                record.id(), record.playerId());
        return inventory.inspect(player, witness);
    }

    private void requireCurrent(Player player, long session) {
        if (!currentPlayer.test(player, session)) {
            throw new IllegalStateException("Player session is no longer current");
        }
    }

    private PreparedReward prepareReward(List<RewardDefinition> bundle) {
        List<ItemStack> items = new ArrayList<>();
        for (RewardDefinition reward : bundle) {
            ItemStack[] materialized = materialize.apply(reward);
            if (materialized == null || materialized.length == 0) return new PreparedReward("");
            for (ItemStack item : materialized) {
                if (item == null || item.isEmpty()) throw new IllegalStateException("Provider returned an empty item");
                items.add(item);
            }
        }
        return new PreparedReward(payload.items(items.toArray(ItemStack[]::new)));
    }

    private OfferSet generateOne(PoolSnapshot pool) {
        synchronized (random) {
            return random.generateOne(pool);
        }
    }

    private OfferSet rerollOffers(OpeningRecord current) {
        synchronized (random) {
            return random.reroll(current.pool(), new OfferSet(current.offers()), current.rerollsUsed());
        }
    }

    private List<RewardDefinition> bundle(OpeningRecord record) {
        synchronized (random) {
            return random.bundle(record.pool(), record.selectedReward());
        }
    }

    private OpeningRecord settleDebit(OpeningRecord record, UUID playerId, OpeningInventoryTransactions.Outcome outcome) {
        return switch (outcome) {
            case APPLIED -> ledger.debitConfirmed(record.id(), playerId, record.revision());
            case NOT_APPLIED -> ledger.debitRejected(record.id(), playerId, record.revision(), "native-inventory-unchanged");
            case UNKNOWN -> ledger.review(record.id(), playerId, record.revision(), "native-key-debit-unconfirmed");
        };
    }

    private OpeningRecord settleDelivery(OpeningRecord record, UUID playerId,
            OpeningInventoryTransactions.Outcome outcome) {
        return switch (outcome) {
            case APPLIED -> ledger.deliveryConfirmed(record.id(), playerId, record.revision());
            case NOT_APPLIED -> ledger.deliveryNotApplied(record.id(), playerId, record.revision(), "native-inventory-unchanged");
            case UNKNOWN -> ledger.review(record.id(), playerId, record.revision(), "native-reward-delivery-unconfirmed");
        };
    }

    private <T> CompletableFuture<T> storage(Supplier<T> action) {
        return submit(storageExecutor, action);
    }

    private <T> CompletableFuture<T> paper(Supplier<T> action) {
        return submit(paperExecutor, action);
    }

    private static <T> CompletableFuture<T> submit(Executor executor, Supplier<T> action) {
        CompletableFuture<T> result = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try {
                    result.complete(action.get());
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                }
            });
        } catch (Throwable failure) {
            result.completeExceptionally(failure);
        }
        return result;
    }

    private record DebitPlan(UUID openingId, String witness) { }
    private record DeliveryPlan(String witness) { }
    private record ClaimStart(OpeningRecord record, List<RewardDefinition> bundle) { }
    private record PreparedReward(String payload) { }
}
