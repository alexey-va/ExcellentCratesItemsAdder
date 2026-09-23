package ru.ruscrafting.ecia.integration;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import ru.arc.paper.testing.MockBukkitTestRuntime;
import ru.ruscrafting.ecia.inventory.NativeItemPayload;
import ru.ruscrafting.ecia.inventory.OpeningInventoryTransactions;
import ru.ruscrafting.ecia.journal.OpeningLedger;
import ru.ruscrafting.ecia.journal.OpeningRecord;
import ru.ruscrafting.ecia.journal.OpeningStore;
import ru.ruscrafting.ecia.roll.PoolSnapshot;
import ru.ruscrafting.ecia.roll.RewardDefinition;
import ru.ruscrafting.ecia.roll.WeightedOfferGenerator;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ManagedOpeningEngineTest {
    @Test void manualResumeContinuesOneUnconfirmedKeyDebitWithoutDebitingAgain() {
        try (var runtime = MockBukkitTestRuntime.Companion.open()) {
            var player = runtime.addPlayer("CrateQA");
            var store = new MemoryStore();
            var ledger = new OpeningLedger(store, Clock.systemUTC());
            var codec = new NativeItemPayload();
            var inventory = new OpeningInventoryTransactions(codec, p -> { throw new IllegalStateException("save failed"); }, () -> true);
            var reward = new RewardDefinition("prize", 1, "frozen", "preview");
            var pool = new PoolSnapshot("daily", "launch", List.of(reward), 1, 0, 1);
            var engine = engine(ledger, inventory, codec, definition -> new ItemStack[]{new ItemStack(Material.DIAMOND)});
            player.getInventory().setItem(0, new ItemStack(Material.TRIPWIRE_HOOK, 2));

            var review = engine.openPhysical(player, 1L, pool,
                    stack -> stack.getType() == Material.TRIPWIRE_HOOK, 1).join().orElseThrow();
            assertEquals(OpeningRecord.Stage.REVIEW, review.stage());
            assertEquals(1, player.getInventory().getItem(0).getAmount());

            var choosing = engine.playerDataLoaded(player, 1L).join().orElseThrow();
            assertEquals(OpeningRecord.Stage.CHOOSING, choosing.stage());
            assertEquals(1, player.getInventory().getItem(0).getAmount());
            assertEquals(choosing, engine.playerDataLoaded(player, 1L).join().orElseThrow());
        }
    }

    @Test void oneWeightedRollAndRepeatedClicksKeepOneKeyOnePreparedBundle() {
        try (var runtime = MockBukkitTestRuntime.Companion.open()) {
            var player = runtime.addPlayer("CrateQA");
            var store = new MemoryStore();
            var ledger = new OpeningLedger(store, Clock.systemUTC());
            var codec = new NativeItemPayload();
            var inventory = new OpeningInventoryTransactions(codec, p -> { }, () -> true);
            var preparations = new AtomicInteger();
            var reward = new RewardDefinition("prize", 1, "frozen", "preview");
            var bonusOne = new RewardDefinition("bonus-one", 1, "frozen-one", "preview-one");
            var bonusTwo = new RewardDefinition("bonus-two", 1, "frozen-two", "preview-two");
            var pool = new PoolSnapshot("daily", "launch", List.of(reward, bonusOne, bonusTwo), 3, 1, 3);
            var engine = engine(ledger, inventory, codec, definition -> {
                preparations.incrementAndGet();
                return new ItemStack[]{new ItemStack(Material.DIAMOND)};
            });
            player.getInventory().setItem(0, new ItemStack(Material.TRIPWIRE_HOOK, 2));
            var opening = engine.openPhysical(player, 1L, pool,
                    stack -> stack.getType() == Material.TRIPWIRE_HOOK, 1).join().orElseThrow();
            assertEquals(OpeningRecord.Stage.CHOOSING, opening.stage());
            assertEquals(1, opening.offers().size());
            assertEquals(opening, engine.openPhysical(player, 1L, pool, stack -> true, 1).join().orElseThrow());
            assertEquals(1, player.getInventory().getItem(0).getAmount());
            for (int slot = 0; slot < 36; slot++) player.getInventory().setItem(slot, new ItemStack(Material.STONE, 64));
            var mail = engine.select(player, opening.id(), opening.revision(), opening.offers().getFirst().id()).join();
            var prepared = engine.claim(player, 1L, mail.id(), mail.revision()).join();
            assertEquals(OpeningRecord.Stage.MAIL, prepared.stage());
            assertEquals(3, preparations.get());
            assertEquals(prepared, engine.claim(player, 1L, prepared.id(), prepared.revision()).join());
            assertEquals(3, preparations.get());
            var restartedLedger = new OpeningLedger(store, Clock.systemUTC());
            var restarted = engine(restartedLedger, inventory, codec,
                    definition -> { fail("Persisted native prize must not be minted again"); return null; });
            player.getInventory().setItem(0, null);
            var delivered = restarted.claim(player, 1L, prepared.id(), prepared.revision()).join();
            assertEquals(OpeningRecord.Stage.DELIVERED, delivered.stage());
            assertEquals(Material.DIAMOND, player.getInventory().getItem(0).getType());
            assertEquals(3, player.getInventory().getItem(0).getAmount());
            assertThrows(java.util.concurrent.CompletionException.class,
                    () -> restarted.claim(player, 1L, delivered.id(), delivered.revision()).join());
        }
    }

    @Test void virtualOpeningDoesNotCompleteUntilItsFrozenRollIsDurable() throws Exception {
        try (var runtime = MockBukkitTestRuntime.Companion.open()) {
            var player = runtime.addPlayer("CrateQA");
            var reward = new RewardDefinition("prize", 1, "frozen", "preview");
            var pool = new PoolSnapshot("daily", "launch", List.of(reward), 1, 0, 1);
            var store = new BlockingStore();
            var ledger = new OpeningLedger(store, Clock.systemUTC());
            var codec = new NativeItemPayload();
            var inventory = new OpeningInventoryTransactions(codec, ignored -> { }, () -> true);
            ExecutorService storage = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "ecia-opening-store-test");
                thread.setDaemon(true);
                return thread;
            });
            try {
                var engine = new ManagedOpeningEngine(ledger, new WeightedOfferGenerator(new Random(1)),
                        inventory, codec, ignored -> new ItemStack[]{new ItemStack(Material.DIAMOND)},
                        storage, Runnable::run, (candidate, session) -> candidate.isOnline());
                var window = PeriodicVirtualOpening.window(PeriodicVirtualOpening.Period.DAILY,
                        Instant.parse("2026-09-23T12:00:00Z"), ZoneId.of("Europe/Moscow"));
                var future = engine.openVirtual(player.getUniqueId(), pool, PeriodicVirtualOpening.Period.DAILY, window);

                assertTrue(store.commitStarted.await(5, TimeUnit.SECONDS));
                assertFalse(future.isDone(), "opening must not become visible before the durable commit returns");
                store.allowCommit.countDown();

                var opened = future.join();
                assertEquals(ManagedOpeningEngine.VirtualResult.OPENED, opened.result());
                assertEquals(OpeningRecord.Stage.CHOOSING, opened.record().stage());
                assertTrue(store.committed);
            } finally {
                store.allowCommit.countDown();
                storage.shutdownNow();
            }
        }
    }

    @Test void availableVirtualOpeningLeavesPhysicalKeysForAdditionalOpens() {
        try (var runtime = MockBukkitTestRuntime.Companion.open()) {
            var player = runtime.addPlayer("CrateQA");
            var store = new MemoryStore();
            var ledger = new OpeningLedger(store, Clock.systemUTC());
            var codec = new NativeItemPayload();
            var inventory = new OpeningInventoryTransactions(codec, ignored -> { }, () -> true);
            var reward = new RewardDefinition("prize", 1, "frozen", "preview");
            var pool = new PoolSnapshot("daily", "launch", List.of(reward), 1, 0, 1);
            var engine = engine(ledger, inventory, codec, ignored -> new ItemStack[]{new ItemStack(Material.DIAMOND)});
            player.getInventory().setItem(0, new ItemStack(Material.TRIPWIRE_HOOK, 2));
            var window = PeriodicVirtualOpening.window(PeriodicVirtualOpening.Period.DAILY,
                    Instant.parse("2026-09-23T12:00:00Z"), ZoneId.of("Europe/Moscow"));

            var free = engine.openVirtual(player.getUniqueId(), pool,
                    PeriodicVirtualOpening.Period.DAILY, window).join();
            assertEquals(ManagedOpeningEngine.VirtualResult.OPENED, free.result());
            assertEquals(2, player.getInventory().getItem(0).getAmount(), "virtual opening must not inspect/debit physical keys");
            var selected = engine.select(player, free.record().id(), free.record().revision(), "prize").join();
            assertEquals(OpeningRecord.Stage.DELIVERED,
                    engine.claim(player, 1L, selected.id(), selected.revision()).join().stage());

            var spent = engine.openVirtual(player.getUniqueId(), pool,
                    PeriodicVirtualOpening.Period.DAILY, window).join();
            assertEquals(ManagedOpeningEngine.VirtualResult.PERIOD_ALREADY_USED, spent.result());
            var extra = engine.openPhysical(player, 1L, pool,
                    stack -> stack.getType() == Material.TRIPWIRE_HOOK, 1).join().orElseThrow();
            assertFalse(extra.keyWitness().startsWith("virtual:v1:"));
            assertEquals(1, player.getInventory().getItem(0).getAmount(), "after quota use, a physical key remains an extra opening");
        }
    }

    @Test void fullInventoryMailResumesAfterReconnectWithoutAnotherPhysicalDebit() {
        try (var runtime = MockBukkitTestRuntime.Companion.open()) {
            var player = runtime.addPlayer("CrateQA");
            var store = new MemoryStore();
            var ledger = new OpeningLedger(store, Clock.systemUTC());
            var codec = new NativeItemPayload();
            var inventory = new OpeningInventoryTransactions(codec, ignored -> { }, () -> true);
            var reward = new RewardDefinition("prize", 1, "frozen", "preview");
            var pool = new PoolSnapshot("daily", "launch", List.of(reward), 1, 0, 1);
            var engine = engine(ledger, inventory, codec, ignored -> new ItemStack[]{new ItemStack(Material.DIAMOND)});
            player.getInventory().setItem(0, new ItemStack(Material.TRIPWIRE_HOOK, 2));

            var opened = engine.openPhysical(player, 1L, pool,
                    stack -> stack.getType() == Material.TRIPWIRE_HOOK, 1).join().orElseThrow();
            for (int slot = 1; slot < 36; slot++) player.getInventory().setItem(slot, new ItemStack(Material.STONE, 64));
            var selected = engine.select(player, opened.id(), opened.revision(), "prize").join();
            var mail = engine.claim(player, 1L, selected.id(), selected.revision()).join();
            assertEquals(OpeningRecord.Stage.MAIL, mail.stage());

            var restarted = engine(new OpeningLedger(store, Clock.systemUTC()), inventory, codec,
                    ignored -> new ItemStack[]{new ItemStack(Material.DIAMOND)});
            assertTrue(restarted.playerDataLoaded(player, 2L).join().isEmpty());
            var resumed = restarted.openPhysical(player, 2L, pool,
                    stack -> stack.getType() == Material.TRIPWIRE_HOOK, 1).join().orElseThrow();
            assertEquals(mail.id(), resumed.id());
            assertEquals(OpeningRecord.Stage.MAIL, resumed.stage());
            assertEquals(1, player.getInventory().getItem(0).getAmount(), "resuming mail must not debit the second key");

            var stillWaiting = restarted.claim(player, 2L, resumed.id(), resumed.revision()).join();
            assertEquals(OpeningRecord.Stage.MAIL, stillWaiting.stage(), "full inventory keeps the durable claim in mail");
            player.getInventory().setItem(35, null);
            var retry = restarted.openPhysical(player, 2L, pool,
                    stack -> stack.getType() == Material.TRIPWIRE_HOOK, 1).join().orElseThrow();
            var delivered = restarted.claim(player, 2L, retry.id(), retry.revision()).join();
            assertEquals(OpeningRecord.Stage.DELIVERED, delivered.stage());
            assertEquals(1, player.getInventory().getItem(0).getAmount());
            assertEquals(Material.DIAMOND, player.getInventory().getItem(35).getType());
        }
    }

    @Test void replacedPlayerSessionCannotReceiveThePhysicalDebit() {
        try (var runtime = MockBukkitTestRuntime.Companion.open()) {
            var player = runtime.addPlayer("CrateQA");
            var current = new AtomicBoolean(true);
            var store = new MemoryStore(() -> current.set(false));
            var ledger = new OpeningLedger(store, Clock.systemUTC());
            var codec = new NativeItemPayload();
            var inventory = new OpeningInventoryTransactions(codec, ignored -> { }, () -> true);
            var reward = new RewardDefinition("prize", 1, "frozen", "preview");
            var pool = new PoolSnapshot("daily", "launch", List.of(reward), 1, 0, 1);
            var engine = new ManagedOpeningEngine(ledger, new WeightedOfferGenerator(new Random(1)), inventory, codec,
                    ignored -> new ItemStack[]{new ItemStack(Material.DIAMOND)}, Runnable::run, Runnable::run,
                    (candidate, session) -> current.get());
            player.getInventory().setItem(0, new ItemStack(Material.TRIPWIRE_HOOK, 2));

            assertThrows(java.util.concurrent.CompletionException.class,
                    () -> engine.openPhysical(player, 4L, pool,
                            stack -> stack.getType() == Material.TRIPWIRE_HOOK, 1).join());
            assertEquals(2, player.getInventory().getItem(0).getAmount());
            assertEquals(OpeningRecord.Stage.RESERVED, ledger.active(player.getUniqueId()).orElseThrow().stage());
        }
    }

    private static ManagedOpeningEngine engine(OpeningLedger ledger, OpeningInventoryTransactions inventory,
            NativeItemPayload codec, java.util.function.Function<RewardDefinition, ItemStack[]> materialize) {
        return new ManagedOpeningEngine(ledger, new WeightedOfferGenerator(new Random(1)), inventory, codec,
                materialize, Runnable::run, Runnable::run, (player, session) -> player.isOnline());
    }

    private static final class MemoryStore implements OpeningStore {
        private final Map<UUID, OpeningRecord> values = new HashMap<>();
        private final Runnable afterCommit;
        private MemoryStore() { this(() -> { }); }
        private MemoryStore(Runnable afterCommit) { this.afterCommit = afterCommit; }
        @Override public List<OpeningRecord> load() { return List.copyOf(values.values()); }
        @Override public OpeningRecord commit(OpeningRecord value) {
            values.put(value.id(), value);
            afterCommit.run();
            return value;
        }
    }

    private static final class BlockingStore implements OpeningStore {
        private final CountDownLatch commitStarted = new CountDownLatch(1);
        private final CountDownLatch allowCommit = new CountDownLatch(1);
        private final Map<UUID, OpeningRecord> records = new HashMap<>();
        private volatile boolean committed;

        @Override public List<OpeningRecord> load() { return List.copyOf(records.values()); }

        @Override public OpeningRecord commit(OpeningRecord value) {
            commitStarted.countDown();
            try {
                if (!allowCommit.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Commit was not released");
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Commit interrupted", failure);
            }
            records.put(value.id(), value);
            committed = true;
            return value;
        }
    }
}
