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

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

/** Feature coordinator. All calls run on the owning Paper thread. */
public final class ManagedOpeningEngine {
    private final OpeningLedger ledger;
    private final WeightedOfferGenerator random;
    private final OpeningInventoryTransactions inventory;
    private final NativeItemPayload payload;
    private final Function<RewardDefinition, ItemStack[]> materialize;

    public ManagedOpeningEngine(OpeningLedger ledger, WeightedOfferGenerator random,
            OpeningInventoryTransactions inventory, NativeItemPayload payload,
            Function<RewardDefinition, ItemStack[]> materialize) {
        this.ledger = Objects.requireNonNull(ledger);
        this.random = Objects.requireNonNull(random);
        this.inventory = Objects.requireNonNull(inventory);
        this.payload = Objects.requireNonNull(payload);
        this.materialize = Objects.requireNonNull(materialize);
    }

    /** An empty result means no sufficient matching-season key; nothing was persisted or debited. */
    public Optional<OpeningRecord> open(Player player, PoolSnapshot pool,
            Predicate<ItemStack> matchingKey, int keyAmount) {
        UUID playerId = player.getUniqueId();
        var active = ledger.active(playerId);
        if (active.isPresent()) return active;
        UUID id = UUID.randomUUID();
        var planned = inventory.debit(player, id, matchingKey, keyAmount);
        if (planned.isEmpty()) return Optional.empty();
        OfferSet offers = random.generate(pool, ledger.misses(playerId, pool.crateId(), pool.seasonId()));
        OpeningRecord record = ledger.reserve(id, playerId, pool, offers.rewards(), offers.guaranteed(), payload.write(planned.get()));
        OpeningRecord settled = switch (inventory.apply(player, planned.get())) {
            case APPLIED -> ledger.debitConfirmed(id, playerId, record.revision());
            case NOT_APPLIED -> ledger.debitRejected(id, playerId, record.revision(), "native-inventory-unchanged");
            case UNKNOWN -> ledger.review(id, playerId, record.revision(), "native-key-debit-unconfirmed");
        };
        return Optional.of(settled);
    }

    public OpeningRecord reroll(Player player, UUID id, long revision) {
        OpeningRecord current = ledger.get(id, player.getUniqueId());
        if (current.revision() != revision || current.stage() != OpeningRecord.Stage.CHOOSING) {
            throw new IllegalStateException("Opening changed; refresh the menu");
        }
        OfferSet rolled = random.reroll(current.pool(), new OfferSet(current.offers(), current.guaranteed()), current.rerollsUsed());
        return ledger.reroll(id, player.getUniqueId(), revision, rolled.rewards(), rolled.guaranteed());
    }

    public OpeningRecord select(Player player, UUID id, long revision, String rewardId) {
        return ledger.select(id, player.getUniqueId(), revision, rewardId);
    }

    /** Materialization never grants; actual provider items are persisted before inventory mutation. */
    public OpeningRecord claim(Player player, UUID id, long revision) {
        UUID playerId = player.getUniqueId();
        OpeningRecord record = ledger.get(id, playerId);
        if (record.revision() != revision || record.stage() != OpeningRecord.Stage.MAIL) {
            throw new IllegalStateException("Mail changed; refresh the menu");
        }
        if (ledger.active(playerId).isPresent()) throw new IllegalStateException("An opening is still active");
        if (record.preparedReward().isEmpty()) {
            ItemStack[] items = materialize.apply(record.selectedReward());
            if (items == null || items.length == 0) return record;
            for (ItemStack item : items) {
                if (item == null || item.isEmpty()) throw new IllegalStateException("Provider returned an empty item");
            }
            record = ledger.prepared(id, playerId, revision, payload.items(items));
        }
        var planned = inventory.delivery(player, id, payload.items(record.preparedReward()));
        if (planned.isEmpty()) return record;
        record = ledger.deliveryStarted(id, playerId, record.revision(), payload.write(planned.get()));
        return switch (inventory.apply(player, planned.get())) {
            case APPLIED -> ledger.deliveryConfirmed(id, playerId, record.revision());
            case NOT_APPLIED -> ledger.deliveryNotApplied(id, playerId, record.revision(), "native-inventory-unchanged");
            case UNKNOWN -> ledger.review(id, playerId, record.revision(), "native-reward-delivery-unconfirmed");
        };
    }

    /** Only native reconnect/load clears an uncertain in-memory persistence attempt. */
    public Optional<OpeningRecord> playerDataLoaded(Player player) {
        inventory.playerDataLoaded(player);
        return reconcile(player);
    }

    /** Operator inspection can reconcile known receipts but cannot force ambiguous grants. */
    public Optional<OpeningRecord> reconcile(Player player) {
        UUID playerId = player.getUniqueId();
        var active = ledger.active(playerId);
        if (active.isEmpty()) return active;
        OpeningRecord record = active.get();
        if (record.stage() == OpeningRecord.Stage.CHOOSING) return active;
        boolean debit = record.selectedRewardId().isEmpty();
        var witness = payload.read(debit ? record.keyWitness() : record.deliveryWitness(), InventoryMutationWitness.class);
        if (!witness.openingId().equals(record.id()) || !witness.playerId().equals(record.playerId())
                || witness.kind() != (debit ? InventoryMutationWitness.Kind.KEY_DEBIT : InventoryMutationWitness.Kind.REWARD_DELIVERY)) {
            throw new IllegalStateException("Opening witness identity mismatch");
        }
        var outcome = inventory.inspect(player, witness);
        if (record.stage() != OpeningRecord.Stage.REVIEW) {
            record = ledger.review(record.id(), playerId, record.revision(), "interrupted-native-side-effect");
        }
        if (outcome == OpeningInventoryTransactions.Outcome.UNKNOWN) return Optional.of(record);
        return Optional.of(ledger.reconciled(record.id(), playerId, record.revision(),
                outcome == OpeningInventoryTransactions.Outcome.APPLIED,
                outcome == OpeningInventoryTransactions.Outcome.APPLIED ? "native-receipt-confirmed" : "native-preimage-confirmed"));
    }
}
