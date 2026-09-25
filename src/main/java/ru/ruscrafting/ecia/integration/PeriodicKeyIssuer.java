package ru.ruscrafting.ecia.integration;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import ru.ruscrafting.ecia.inventory.InventoryMutationWitness;
import ru.ruscrafting.ecia.inventory.NativeItemPayload;
import ru.ruscrafting.ecia.inventory.OpeningInventoryTransactions;
import ru.ruscrafting.ecia.journal.OpeningLedger;
import ru.ruscrafting.ecia.journal.PeriodicKeyGrant;
import ru.ruscrafting.ecia.journal.PeriodicKeyLedger;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiPredicate;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Persist a capacity-checked grant before applying it, then settle against a separate native receipt. */
public final class PeriodicKeyIssuer {
    public enum Result { DELIVERED, ALREADY_DELIVERED, SKIPPED, FULL, RETRY, REVIEW }
    private final PeriodicKeyLedger grants;
    private final OpeningLedger openings;
    private final NativeItemPayload payload;
    private final OpeningInventoryTransactions inventory;
    private final Clock clock;
    private final Executor storage;
    private final Executor paper;
    private final BiPredicate<Player, Long> current;

    public PeriodicKeyIssuer(PeriodicKeyLedger grants, OpeningLedger openings, NativeItemPayload payload,
            OpeningInventoryTransactions inventory, Clock clock, Executor storage, Executor paper,
            BiPredicate<Player, Long> current) {
        this.grants = grants; this.openings = openings; this.payload = payload; this.inventory = inventory;
        this.clock = clock; this.storage = storage; this.paper = paper; this.current = current;
    }

    public void playerDataLoaded(Player player) { inventory.playerDataLoaded(player); }
    public void playerLeft(UUID playerId) { inventory.playerLeft(playerId); }

    public CompletableFuture<Result> grant(Player player, long session, String crateId,
            PeriodicVirtualOpening.Window window, Supplier<ItemStack> item, BooleanSupplier allowed) {
        UUID playerId = player.getUniqueId();
        UUID id = PeriodicVirtualOpening.openingId(playerId, crateId, window.period(), window);
        return async(storage, () -> {
            grants.expireUnresolved(playerId, clock.millis());
            var old = grants.get(id).orElse(null);
            // Existing virtual records remain the authority for periods spent before this upgrade.
            return new Start(old, old == null && openings.contains(id), openings.pending(playerId).isPresent());
        }).thenCompose(start -> {
            if (start.spent()) return CompletableFuture.completedFuture(Result.SKIPPED);
            PeriodicKeyGrant old = start.grant();
            if (old != null && old.state() == PeriodicKeyGrant.State.DELIVERED) {
                return CompletableFuture.completedFuture(Result.ALREADY_DELIVERED);
            }
            if (old != null && old.state() == PeriodicKeyGrant.State.EXPIRED) {
                return CompletableFuture.completedFuture(Result.SKIPPED);
            }
            // Preserve unresolved opening preimages/receipts until that delivery has reconciled.
            if (start.openingPending()) return CompletableFuture.completedFuture(Result.RETRY);
            if (old != null && old.state() != PeriodicKeyGrant.State.RETRY) {
                return apply(player, session, old, allowed, false);
            }
            return async(paper, () -> {
                requireCurrent(player, session);
                if (!within(window)) return new Plan(null, Result.SKIPPED);
                if (!allowed.getAsBoolean()) return new Plan(null, Result.RETRY);
                // Only after unresolved openings have been ruled out: their inventory preimages stay intact.
                removeExpiredKeys(player);
                var witness = inventory.periodicKeyDelivery(player, id, item.get());
                return new Plan(witness.map(payload::write).orElse(null), Result.FULL);
            }).thenCompose(plan -> {
                if (plan.witness() == null) return CompletableFuture.completedFuture(plan.noPlan());
                var prepared = new PeriodicKeyGrant(id, playerId, crateId, window.period(),
                        window.start().toEpochMilli(), window.nextReset().toEpochMilli(),
                        PeriodicKeyGrant.State.PREPARED, plan.witness());
                return async(storage, () -> grants.save(prepared))
                        .thenCompose(committed -> apply(player, session, committed, allowed, true));
            });
        });
    }

    private CompletableFuture<Result> apply(Player player, long session, PeriodicKeyGrant grant,
            BooleanSupplier allowed, boolean freshPlan) {
        return async(paper, () -> {
            requireCurrent(player, session);
            if (player.isDead()) return freshPlan ? PeriodicKeyGrant.State.RETRY : grant.state();
            var witness = payload.read(grant.witness(), InventoryMutationWitness.class);
            if (!witness.playerId().equals(player.getUniqueId()) || !witness.openingId().equals(grant.id())
                    || witness.kind() != InventoryMutationWitness.Kind.PERIODIC_KEY_DELIVERY) {
                throw new IllegalStateException("Periodic grant witness identity mismatch");
            }
            var observed = inventory.inspect(player, witness);
            if (observed == OpeningInventoryTransactions.Outcome.APPLIED) return PeriodicKeyGrant.State.DELIVERED;
            if (clock.millis() >= grant.expires()) return PeriodicKeyGrant.State.EXPIRED;
            if (clock.millis() < grant.start() || !allowed.getAsBoolean()) {
                return freshPlan ? PeriodicKeyGrant.State.RETRY : PeriodicKeyGrant.State.REVIEW;
            }
            if (observed == OpeningInventoryTransactions.Outcome.UNKNOWN) {
                // Before the first apply we know this plan never ran: ordinary slot changes can safely retry.
                return freshPlan ? PeriodicKeyGrant.State.RETRY : PeriodicKeyGrant.State.REVIEW;
            }
            return switch (inventory.apply(player, witness)) {
                case APPLIED -> PeriodicKeyGrant.State.DELIVERED;
                case NOT_APPLIED -> PeriodicKeyGrant.State.RETRY;
                case UNKNOWN -> PeriodicKeyGrant.State.REVIEW;
            };
        }).thenCompose(state -> async(storage, () -> {
            if (state != grant.state()) grants.save(grant.withState(state));
            return switch (state) {
                case DELIVERED -> Result.DELIVERED;
                case RETRY, PREPARED -> Result.RETRY;
                case EXPIRED -> Result.SKIPPED;
                default -> Result.REVIEW;
            };
        }));
    }

    private boolean within(PeriodicVirtualOpening.Window window) {
        return !clock.instant().isBefore(window.start()) && clock.instant().isBefore(window.nextReset());
    }
    private void removeExpiredKeys(Player player) {
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            var key = PeriodicPhysicalKey.identify(contents[slot]).orElse(null);
            if (key != null && !clock.instant().isBefore(key.expiry())) player.getInventory().setItem(slot, null);
        }
    }
    private void requireCurrent(Player player, long session) {
        if (!current.test(player, session)) throw new IllegalStateException("Player session changed during periodic key delivery");
    }
    private static <T> CompletableFuture<T> async(Executor executor, Supplier<T> action) {
        try { return CompletableFuture.supplyAsync(action, executor); }
        catch (RuntimeException failure) { return CompletableFuture.failedFuture(failure); }
    }
    private record Start(PeriodicKeyGrant grant, boolean spent, boolean openingPending) { }
    private record Plan(String witness, Result noPlan) { }
}
