package ru.ruscrafting.ecia.inventory;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import ru.arc.paper.playerstate.NativePaperPlayerDataPersistence;
import ru.arc.paper.playerstate.PaperPlayerDataPersistence;

import java.util.Arrays;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * One journaled debit or delivery. Planning never mutates the player. The caller
 * persists the witness first, then calls apply on the owning Paper thread.
 */
public final class OpeningInventoryTransactions {
    public enum Outcome { APPLIED, NOT_APPLIED, UNKNOWN }
    private final NativeItemPayload payload;
    private final PaperPlayerDataPersistence persistence;
    private final BooleanSupplier primaryThread;
    private final Set<UUID> uncertainPlayers = new HashSet<>();
    private final Set<UUID> loadedPlayers = new HashSet<>();
    private final Map<UUID, UUID> livePlans = new HashMap<>();
    private final NamespacedKey debitReceipt = Objects.requireNonNull(NamespacedKey.fromString("ecia:last_key_debit"));
    private final NamespacedKey deliveryReceipt = Objects.requireNonNull(NamespacedKey.fromString("ecia:last_reward_delivery"));
    private final NamespacedKey periodicReceipt = Objects.requireNonNull(NamespacedKey.fromString("ecia:last_periodic_key_delivery"));

    public OpeningInventoryTransactions(NativeItemPayload payload) {
        this(payload, NativePaperPlayerDataPersistence.INSTANCE, Bukkit::isPrimaryThread);
    }

    public OpeningInventoryTransactions(NativeItemPayload payload, PaperPlayerDataPersistence persistence,
            BooleanSupplier primaryThread) {
        this.payload = Objects.requireNonNull(payload);
        this.persistence = Objects.requireNonNull(persistence);
        this.primaryThread = Objects.requireNonNull(primaryThread);
    }

    public Optional<InventoryMutationWitness> debit(Player player, UUID openingId,
            Predicate<ItemStack> matchingSeasonKey, int amount) {
        requirePlayer(player);
        if (amount < 1) throw new IllegalArgumentException("Positive key amount required");
        ItemStack[] before = player.getInventory().getContents();
        ItemStack[] after = clones(before);
        int remaining = amount;
        for (int slot = 0; slot < after.length && remaining > 0; slot++) {
            if (slot >= 36 && slot != 40) continue;
            ItemStack item = after[slot];
            if (item == null || item.isEmpty() || !matchingSeasonKey.test(item)) continue;
            int removed = Math.min(remaining, item.getAmount());
            remaining -= removed;
            if (removed == item.getAmount()) after[slot] = null;
            else item.setAmount(item.getAmount() - removed);
        }
        return remaining == 0 ? Optional.of(witness(player, openingId,
                InventoryMutationWitness.Kind.KEY_DEBIT, before, after)) : Optional.empty();
    }

    /** Awards use storage slots only. Any overflow keeps the whole award in mail. */
    public Optional<InventoryMutationWitness> delivery(Player player, UUID openingId, ItemStack[] rewards) {
        return delivery(player, openingId, rewards, InventoryMutationWitness.Kind.REWARD_DELIVERY);
    }

    public Optional<InventoryMutationWitness> periodicKeyDelivery(Player player, UUID grantId, ItemStack key) {
        return periodicKeyDelivery(player, grantId, key, ignored -> false);
    }

    /**
     * Plan a one-key delivery while atomically reclaiming only caller-approved
     * expired keys. Reclaimed slots and the new key share one before/after
     * witness, so an uncertain native save can be reconciled as a single write.
     */
    public Optional<InventoryMutationWitness> periodicKeyDelivery(Player player, UUID grantId, ItemStack key,
            Predicate<ItemStack> replaceExpired) {
        if (key.getAmount() != 1) throw new IllegalArgumentException("One periodic key per window required");
        return delivery(player, grantId, new ItemStack[]{key}, InventoryMutationWitness.Kind.PERIODIC_KEY_DELIVERY,
                Objects.requireNonNull(replaceExpired), true);
    }

    private Optional<InventoryMutationWitness> delivery(Player player, UUID openingId, ItemStack[] rewards,
            InventoryMutationWitness.Kind kind) {
        return delivery(player, openingId, rewards, kind, ignored -> false, false);
    }

    private Optional<InventoryMutationWitness> delivery(Player player, UUID openingId, ItemStack[] rewards,
            InventoryMutationWitness.Kind kind, Predicate<ItemStack> replaceExpired, boolean allowReclaimedOffhand) {
        requirePlayer(player);
        if (rewards.length == 0) throw new IllegalArgumentException("Empty reward");
        ItemStack[] before = player.getInventory().getContents();
        ItemStack[] after = clones(before);
        boolean reclaimedOffhand = false;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack existing = after[slot];
            if (existing != null && !existing.isEmpty() && replaceExpired.test(existing)) after[slot] = null;
        }
        if (after.length > 40 && after[40] != null && !after[40].isEmpty()
                && replaceExpired.test(after[40])) {
            after[40] = null;
            reclaimedOffhand = true;
        }
        for (ItemStack reward : rewards) {
            if (reward == null || reward.isEmpty() || reward.getAmount() <= 0) {
                throw new IllegalArgumentException("Empty reward stack");
            }
            int remaining = reward.getAmount();
            int maximum = Math.min(player.getInventory().getMaxStackSize(), reward.getMaxStackSize());
            for (int slot = 0; slot < 36 && remaining > 0; slot++) {
                ItemStack current = after[slot];
                if (current == null || current.isEmpty() || !current.isSimilar(reward)) continue;
                int inserted = Math.min(remaining, Math.max(0, maximum - current.getAmount()));
                current.setAmount(current.getAmount() + inserted);
                remaining -= inserted;
            }
            for (int slot = 0; slot < 36 && remaining > 0; slot++) {
                if (after[slot] != null && !after[slot].isEmpty()) continue;
                int inserted = Math.min(remaining, maximum);
                after[slot] = reward.clone();
                after[slot].setAmount(inserted);
                remaining -= inserted;
            }
            if (remaining > 0 && allowReclaimedOffhand && reclaimedOffhand) {
                int inserted = Math.min(remaining, maximum);
                after[40] = reward.clone();
                after[40].setAmount(inserted);
                remaining -= inserted;
            }
            if (remaining > 0) return Optional.empty();
        }
        return Optional.of(witness(player, openingId, kind, before, after));
    }

    public Outcome inspect(Player player, InventoryMutationWitness witness) {
        requirePlayer(player);
        if (!player.getUniqueId().equals(witness.playerId())) throw new IllegalArgumentException("Wrong witness owner");
        if (uncertainPlayers.contains(player.getUniqueId())) return Outcome.UNKNOWN;
        if (!loadedPlayers.contains(player.getUniqueId())
                && !witness.openingId().equals(livePlans.get(player.getUniqueId()))) return Outcome.UNKNOWN;
        String receipt = receipt(player, witness.kind());
        if (receipt.equals(witness.receipt())) return Outcome.APPLIED;
        return receipt.equals(witness.previousReceipt())
                && sameInventory(player.getInventory().getContents(), payload.restore(witness.before()))
                ? Outcome.NOT_APPLIED : Outcome.UNKNOWN;
    }

    public Outcome apply(Player player, InventoryMutationWitness witness) {
        Outcome observed = inspect(player, witness);
        if (observed != Outcome.NOT_APPLIED) return observed;
        try {
            ItemStack[] after = payload.restore(witness.after());
            player.getInventory().setContents(after);
            if (!sameInventory(player.getInventory().getContents(), after)) {
                uncertainPlayers.add(player.getUniqueId());
                return Outcome.UNKNOWN;
            }
            player.getPersistentDataContainer().set(receiptKey(witness.kind()), PersistentDataType.STRING, witness.receipt());
            persistence.persist(player);
            loadedPlayers.add(player.getUniqueId());
            livePlans.remove(player.getUniqueId());
            return Outcome.APPLIED;
        } catch (RuntimeException failure) {
            // The mutation or native save may have succeeded. The journal stays
            // unresolved until a fresh player-data load can establish its receipt.
            uncertainPlayers.add(player.getUniqueId());
            return Outcome.UNKNOWN;
        }
    }

    /** Call only after native player data was loaded anew, never on a menu refresh. */
    public void playerDataLoaded(Player player) {
        requirePlayer(player);
        uncertainPlayers.remove(player.getUniqueId());
        livePlans.remove(player.getUniqueId());
        loadedPlayers.add(player.getUniqueId());
    }

    public void playerLeft(UUID playerId) {
        uncertainPlayers.remove(playerId);
        loadedPlayers.remove(playerId);
        livePlans.remove(playerId);
    }

    private InventoryMutationWitness witness(Player player, UUID openingId, InventoryMutationWitness.Kind kind,
            ItemStack[] before, ItemStack[] after) {
        InventoryMutationWitness witness = new InventoryMutationWitness(openingId, player.getUniqueId(), kind,
                payload.capture(before), payload.capture(after), receipt(player, kind));
        livePlans.put(player.getUniqueId(), openingId);
        return witness;
    }

    private String receipt(Player player, InventoryMutationWitness.Kind kind) {
        return player.getPersistentDataContainer().getOrDefault(receiptKey(kind), PersistentDataType.STRING, "");
    }

    private NamespacedKey receiptKey(InventoryMutationWitness.Kind kind) {
        return switch (kind) {
            case KEY_DEBIT -> debitReceipt;
            case REWARD_DELIVERY -> deliveryReceipt;
            case PERIODIC_KEY_DELIVERY -> periodicReceipt;
        };
    }

    private void requirePlayer(Player player) {
        if (!primaryThread.getAsBoolean()) throw new IllegalStateException("Player mutation requires the owning Paper thread");
        if (!player.isOnline() || player.isDead()) throw new IllegalStateException("Player is unavailable");
    }

    private static ItemStack[] clones(ItemStack[] values) {
        return Arrays.stream(values).map(item -> item == null ? null : item.clone()).toArray(ItemStack[]::new);
    }

    private static boolean sameInventory(ItemStack[] actual, ItemStack[] expected) {
        if (actual.length != expected.length) return false;
        for (int slot = 0; slot < actual.length; slot++) {
            ItemStack left = actual[slot];
            ItemStack right = expected[slot];
            if ((left == null || left.isEmpty()) && (right == null || right.isEmpty())) continue;
            if (left == null || right == null || left.getAmount() != right.getAmount() || !left.isSimilar(right)) return false;
        }
        return true;
    }
}
