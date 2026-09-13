package ru.ruscrafting.ecia.inventory;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;
import ru.arc.paper.playerstate.NativePaperItemStackBinaryCodec;
import ru.arc.paper.playerstate.PaperItemStackBinaryCodec;
import ru.arc.paper.testing.MockBukkitTestRuntime;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class OpeningInventoryTransactionsTest {
    @Test void semanticallyEqualInventoryAcceptsNonCanonicalBinaryEncoding() {
        try (var runtime = MockBukkitTestRuntime.Companion.open()) {
            var player = runtime.addPlayer("CrateQA");
            var codec = new NativeItemPayload(new NonCanonicalItemCodec());
            var transactions = new OpeningInventoryTransactions(codec, p -> { }, () -> true);
            player.getInventory().setItem(10, new ItemStack(Material.TRIPWIRE_HOOK, 56));
            var witness = transactions.debit(player, UUID.randomUUID(), item -> true, 1).orElseThrow();

            assertEquals(OpeningInventoryTransactions.Outcome.APPLIED, transactions.apply(player, witness));
            assertEquals(55, player.getInventory().getItem(10).getAmount());
        }
    }

    @Test void keyDebitUsesExactSeasonAndSurvivesRepeatedApply() {
        try (var runtime = MockBukkitTestRuntime.Companion.open()) {
            var player = runtime.addPlayer("CrateQA");
            var codec = new NativeItemPayload();
            var saves = new AtomicInteger();
            var transactions = new OpeningInventoryTransactions(codec, p -> saves.incrementAndGet(), () -> true);
            var season = NamespacedKey.fromString("ecia:season");
            var old = new ItemStack(Material.TRIPWIRE_HOOK, 2);
            old.editMeta(meta -> meta.getPersistentDataContainer().set(season, PersistentDataType.STRING, "launch"));
            var current = old.clone();
            current.editMeta(meta -> meta.getPersistentDataContainer().set(season, PersistentDataType.STRING, "autumn"));
            player.getInventory().setItem(0, old);
            player.getInventory().setItem(1, current);
            var witness = transactions.debit(player, UUID.randomUUID(), item ->
                    "autumn".equals(item.getItemMeta().getPersistentDataContainer().get(season, PersistentDataType.STRING)), 1).orElseThrow();
            assertEquals(2, player.getInventory().getItem(1).getAmount());
            assertEquals(OpeningInventoryTransactions.Outcome.APPLIED, transactions.apply(player, witness));
            assertEquals(2, player.getInventory().getItem(0).getAmount());
            assertEquals(1, player.getInventory().getItem(1).getAmount());
            assertEquals(OpeningInventoryTransactions.Outcome.APPLIED, transactions.apply(player, witness));
            assertEquals(1, saves.get());
            assertEquals(witness, codec.read(codec.write(witness), InventoryMutationWitness.class));
        }
    }

    @Test void fullInventoryDoesNotPartiallyDeliverOrDropItems() {
        try (var runtime = MockBukkitTestRuntime.Companion.open()) {
            var player = runtime.addPlayer("CrateQA");
            var transactions = new OpeningInventoryTransactions(new NativeItemPayload(), p -> fail("No save expected"), () -> true);
            for (int slot = 0; slot < 36; slot++) player.getInventory().setItem(slot, new ItemStack(Material.STONE, 64));
            player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 63));
            assertTrue(transactions.delivery(player, UUID.randomUUID(), new ItemStack[]{new ItemStack(Material.DIAMOND, 2)}).isEmpty());
            assertEquals(63, player.getInventory().getItem(0).getAmount());
        }
    }

    @Test void changedInventoryRejectsStalePlanAndSaveFailureStaysUncertain() {
        try (var runtime = MockBukkitTestRuntime.Companion.open()) {
            var player = runtime.addPlayer("CrateQA");
            var transactions = new OpeningInventoryTransactions(new NativeItemPayload(), p -> { throw new IllegalStateException("Disk failure"); }, () -> true);
            var first = transactions.delivery(player, UUID.randomUUID(), new ItemStack[]{new ItemStack(Material.DIAMOND)}).orElseThrow();
            player.getInventory().setItem(0, new ItemStack(Material.STONE));
            assertEquals(OpeningInventoryTransactions.Outcome.UNKNOWN, transactions.apply(player, first));
            player.getInventory().clear();
            assertEquals(OpeningInventoryTransactions.Outcome.UNKNOWN, transactions.apply(player, first));
            assertEquals(OpeningInventoryTransactions.Outcome.UNKNOWN, transactions.inspect(player, first));
            assertEquals(1, player.getInventory().getItem(0).getAmount());
            var hotReloaded = new OpeningInventoryTransactions(new NativeItemPayload(), p -> {}, () -> true);
            assertEquals(OpeningInventoryTransactions.Outcome.UNKNOWN, hotReloaded.inspect(player, first),
                    "A hot reload must not trust an in-memory receipt from a failed native save");
        }
    }

    private static final class NonCanonicalItemCodec implements PaperItemStackBinaryCodec {
        private final AtomicInteger sequence = new AtomicInteger();
        @Override public byte[] encodeItems(List<? extends ItemStack> items) {
            return NativePaperItemStackBinaryCodec.INSTANCE.encodeItems(items);
        }
        @Override public List<ItemStack> decodeItems(byte[] payload) {
            return NativePaperItemStackBinaryCodec.INSTANCE.decodeItems(payload);
        }
        @Override public byte[] encodeItem(ItemStack item) {
            byte[] encoded = NativePaperItemStackBinaryCodec.INSTANCE.encodeItem(item);
            return ByteBuffer.allocate(encoded.length + Integer.BYTES)
                    .putInt(sequence.incrementAndGet()).put(encoded).array();
        }
        @Override public ItemStack decodeItem(byte[] payload) {
            return NativePaperItemStackBinaryCodec.INSTANCE.decodeItem(Arrays.copyOfRange(payload, Integer.BYTES, payload.length));
        }
    }
}
