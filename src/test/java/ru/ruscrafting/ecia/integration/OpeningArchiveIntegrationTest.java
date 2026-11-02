package ru.ruscrafting.ecia.integration;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.arc.paper.testing.MockBukkitTestRuntime;
import ru.ruscrafting.ecia.inventory.NativeItemPayload;
import ru.ruscrafting.ecia.inventory.OpeningInventoryTransactions;
import ru.ruscrafting.ecia.journal.DurableOpeningStore;
import ru.ruscrafting.ecia.journal.OpeningLedger;
import ru.ruscrafting.ecia.journal.OpeningRecord;
import ru.ruscrafting.ecia.journal.OpeningStore;
import ru.ruscrafting.ecia.roll.PoolSnapshot;
import ru.ruscrafting.ecia.roll.RewardDefinition;
import ru.ruscrafting.ecia.roll.WeightedOfferGenerator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class OpeningArchiveIntegrationTest {
    private static final RewardDefinition REWARD = new RewardDefinition("prize", 1, "frozen", "preview");
    private static final PoolSnapshot POOL = new PoolSnapshot("daily", "launch", List.of(REWARD), 1, 0, 1);
    @TempDir Path directory;

    @Test void nativeDeliveryReleasesWitnessesAndReadsExactHistoryOnlyOnTheStorageExecutor() throws Exception {
        try (var runtime = MockBukkitTestRuntime.Companion.open()) {
            var player = runtime.addPlayer("CrateArchiveQA");
            var storage = new QueuedExecutor();
            var store = new CheckedStore(directory, storage);
            var ledger = new OpeningLedger(store, Clock.systemUTC());
            var codec = new NativeItemPayload();
            var inventory = new OpeningInventoryTransactions(codec, ignored -> { }, () -> true);
            var engine = engine(ledger, inventory, codec, storage, new AtomicInteger());
            player.getInventory().setItem(0, new ItemStack(Material.TRIPWIRE_HOOK, 2));
            var opened = storage.finish(engine.openPhysical(player, 1L, POOL,
                    item -> item.getType() == Material.TRIPWIRE_HOOK, 1)).orElseThrow();
            var mail = storage.finish(engine.select(player, opened.id(), opened.revision(), REWARD.id()));
            var done = storage.finish(engine.claim(player, 1L, mail.id(), mail.revision()));
            Path file = directory.resolve("openings").resolve(done.id() + ".json");
            byte[] durableBytes = Files.readAllBytes(file);
            assertEquals(OpeningRecord.Stage.DELIVERED, done.stage());
            assertTrue(done.keyWitness().length() > 100);
            assertTrue(done.deliveryWitness().length() > 100);
            assertNoRetainedPayload(ledger, done);
            assertEquals(0, storage.finish(engine.pendingCount()));
            assertEquals(Set.of(), storage.finish(engine.virtualOpeningIds()));
            assertEquals(0, store.lookups);

            var lookup = CompletableFuture.supplyAsync(() -> ledger.history(player.getUniqueId(), 0, 1).getFirst(), storage);
            assertFalse(lookup.isDone());
            assertEquals(0, store.lookups);
            OpeningRecord historical = storage.finish(lookup);
            assertEquals(done, historical);
            assertEquals(done.keyWitness(), historical.keyWitness());
            assertEquals(done.deliveryWitness(), historical.deliveryWitness());
            assertEquals(done.preparedReward(), historical.preparedReward());
            assertArrayEquals(durableBytes, Files.readAllBytes(file));
            assertNoRetainedPayload(ledger, done);

            OpeningLedger restarted = new OpeningLedger(new CheckedStore(directory, storage), Clock.systemUTC());
            assertNoRetainedPayload(restarted, done);
            assertEquals(done, storage.finish(CompletableFuture.supplyAsync(
                    () -> restarted.get(done.id(), player.getUniqueId()), storage)));
            assertNoRetainedPayload(restarted, done);
            var before = codec.capture(player.getInventory().getContents());
            assertThrows(CompletionException.class,
                    () -> storage.finish(engine.claim(player, 1L, done.id(), done.revision())));
            assertEquals(before, codec.capture(player.getInventory().getContents()));

            Files.delete(file);
            int reads = store.lookups;
            assertTrue(storage.finish(engine.openPeriodicKey(player, 1L, POOL, item -> true, done.id())).isEmpty());
            assertEquals(reads, store.lookups, "a spent period is rejected from compact metadata without disk I/O");
            assertThrows(CompletionException.class,
                    () -> storage.finish(engine.claim(player, 1L, done.id(), done.revision())));
            Files.writeString(file, "{broken");
            assertThrows(CompletionException.class,
                    () -> storage.finish(engine.claim(player, 1L, done.id(), done.revision())));
            assertTrue(ledger.contains(done.id()));
            assertFalse(ledger.canSpendPeriodicKey(done.id()));
            assertEquals(before, codec.capture(player.getInventory().getContents()));
        }
    }

    @Test void completedVirtualPeriodRemainsSpentAfterNativeDeliveryAndRestart() {
        try (var runtime = MockBukkitTestRuntime.Companion.open()) {
            var player = runtime.addPlayer("CrateArchiveQA");
            var storage = new QueuedExecutor();
            var store = new CheckedStore(directory, storage);
            var ledger = new OpeningLedger(store, Clock.systemUTC());
            var codec = new NativeItemPayload();
            var inventory = new OpeningInventoryTransactions(codec, ignored -> { }, () -> true);
            var materializations = new AtomicInteger();
            var engine = engine(ledger, inventory, codec, storage, materializations);
            var window = PeriodicVirtualOpening.window(PeriodicVirtualOpening.Period.DAILY,
                    Instant.parse("2026-11-03T00:00:00Z"), ZoneId.of("Europe/Moscow"));
            var opened = storage.finish(engine.openVirtual(player.getUniqueId(), POOL,
                    PeriodicVirtualOpening.Period.DAILY, window));
            var mail = storage.finish(engine.select(player, opened.record().id(), opened.record().revision(), REWARD.id()));
            var done = storage.finish(engine.claim(player, 1L, mail.id(), mail.revision()));
            var reopenedStore = new CheckedStore(directory, storage);
            var reopened = new OpeningLedger(reopenedStore, Clock.systemUTC());
            var restarted = engine(reopened, inventory, codec, storage, materializations);
            assertEquals(Set.of(done.id()), storage.finish(restarted.virtualOpeningIds()));
            assertEquals(0, storage.finish(restarted.pendingCount()));
            assertEquals(0, reopenedStore.lookups);
            var spent = storage.finish(restarted.openVirtual(player.getUniqueId(), POOL,
                    PeriodicVirtualOpening.Period.DAILY, window));
            assertEquals(ManagedOpeningEngine.VirtualResult.PERIOD_ALREADY_USED, spent.result());
            assertEquals(done, spent.record());
            assertEquals(1, reopenedStore.lookups);
            assertEquals(1, materializations.get());
            assertEquals(1, player.getInventory().getItem(0).getAmount());
        }
    }

    @Test void compensationLooksUpOnlyItsUnresolvedRecordEvenWhenAnotherArchiveIsCorrupt() throws Exception {
        try (var runtime = MockBukkitTestRuntime.Companion.open()) {
            var player = runtime.addPlayer("CrateArchiveQA");
            var storage = new QueuedExecutor();
            var store = new CheckedStore(directory, storage);
            var ledger = new OpeningLedger(store, Clock.systemUTC());
            var codec = new NativeItemPayload();
            var inventory = new OpeningInventoryTransactions(codec, ignored -> { }, () -> true);
            var engine = engine(ledger, inventory, codec, storage, new AtomicInteger());
            var window = PeriodicVirtualOpening.window(PeriodicVirtualOpening.Period.DAILY,
                    Instant.parse("2026-11-03T00:00:00Z"), ZoneId.of("Europe/Moscow"));
            var opened = storage.finish(engine.openVirtual(player.getUniqueId(), POOL,
                    PeriodicVirtualOpening.Period.DAILY, window)).record();
            var mail = storage.finish(engine.select(player, opened.id(), opened.revision(), REWARD.id()));
            var done = storage.finish(engine.claim(player, 1L, mail.id(), mail.revision()));
            Files.writeString(directory.resolve("openings").resolve(done.id() + ".json"), "{broken");
            var reserved = ledger.reserve(UUID.randomUUID(), player.getUniqueId(), POOL, List.of(REWARD), "key-witness");
            var choosing = ledger.debitConfirmed(reserved.id(), player.getUniqueId(), reserved.revision());
            var selected = ledger.select(choosing.id(), player.getUniqueId(), choosing.revision(), REWARD.id());
            var prepared = ledger.prepared(selected.id(), player.getUniqueId(), selected.revision(),
                    codec.items(new ItemStack[]{new ItemStack(Material.DIAMOND)}));
            var delivering = ledger.deliveryStarted(prepared.id(), player.getUniqueId(), prepared.revision(), "operator-audit-witness");
            var review = ledger.review(delivering.id(), player.getUniqueId(), delivering.revision(), "uncertain-reward");
            var before = codec.capture(player.getInventory().getContents());
            var resolved = storage.finish(engine.resolveCompensatedOpening(review.id()));
            assertEquals(OpeningRecord.Stage.DELIVERED, resolved.stage());
            assertEquals(0, store.lookups);
            assertEquals(0, storage.finish(engine.pendingCount()));
            assertEquals(before, codec.capture(player.getInventory().getContents()));
        }
    }

    private static ManagedOpeningEngine engine(OpeningLedger ledger, OpeningInventoryTransactions inventory,
            NativeItemPayload codec, Executor storage, AtomicInteger materializations) {
        return new ManagedOpeningEngine(ledger, new WeightedOfferGenerator(new Random(1)), inventory, codec,
                reward -> { materializations.incrementAndGet(); return new ItemStack[]{new ItemStack(Material.DIAMOND)}; },
                storage, Runnable::run, (player, session) -> player.isOnline());
    }

    private static void assertNoRetainedPayload(OpeningLedger ledger, OpeningRecord done) throws Exception {
        var full = OpeningLedger.class.getDeclaredField("records");
        full.setAccessible(true);
        assertFalse(((Map<?, ?>) full.get(ledger)).containsKey(done.id()));
        var archive = OpeningLedger.class.getDeclaredField("delivered");
        archive.setAccessible(true);
        Object compact = ((Map<?, ?>) archive.get(ledger)).get(done.id());
        assertNotNull(compact);
        for (var field : compact.getClass().getDeclaredFields()) {
            field.setAccessible(true);
            Object value = field.get(compact);
            assertNotSame(done, value);
            assertNotSame(done.pool(), value);
            assertNotSame(done.keyWitness(), value);
            assertNotSame(done.deliveryWitness(), value);
            assertNotSame(done.preparedReward(), value);
        }
    }

    private static final class QueuedExecutor implements Executor {
        private final Deque<Runnable> tasks = new ArrayDeque<>();
        private boolean running;
        @Override public void execute(Runnable command) { tasks.addLast(command); }
        private <T> T finish(CompletableFuture<T> future) {
            for (int step = 0; !future.isDone() && step < 100; step++) {
                running = true;
                try { tasks.removeFirst().run(); } finally { running = false; }
            }
            assertTrue(future.isDone(), "storage flow did not complete");
            return future.join();
        }
    }

    private static final class CheckedStore implements OpeningStore {
        private final DurableOpeningStore delegate;
        private final QueuedExecutor executor;
        private int lookups;
        private CheckedStore(Path directory, QueuedExecutor executor) {
            delegate = new DurableOpeningStore(directory);
            this.executor = executor;
        }
        @Override public List<OpeningRecord> load() { return delegate.load(); }
        @Override public OpeningRecord commit(OpeningRecord record) { return delegate.commit(record); }
        @Override public Optional<OpeningRecord> find(UUID id) {
            assertTrue(executor.running, "archive reads must run on the owned storage executor");
            lookups++;
            return delegate.find(id);
        }
    }
}
