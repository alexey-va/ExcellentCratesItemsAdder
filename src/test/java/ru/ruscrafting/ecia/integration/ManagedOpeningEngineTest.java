package ru.ruscrafting.ecia.integration;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import ru.arc.paper.testing.MockBukkitTestRuntime;
import ru.ruscrafting.ecia.inventory.InventoryMutationWitness;
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
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ManagedOpeningEngineTest {
    @Test void ordinaryRetryHealsInterruptedKeyDebitFromItsReceiptOrPreimage() {
        for (boolean applied : new boolean[]{false, true}) {
            try (var runtime = MockBukkitTestRuntime.Companion.open()) {
                var player = runtime.addPlayer("CrateQA");
                player.getInventory().setItem(0, new ItemStack(Material.TRIPWIRE_HOOK, 2));
                var ledger = new OpeningLedger(new MemoryStore(), Clock.systemUTC());
                var codec = new NativeItemPayload();
                var saves = new AtomicInteger();
                var inventory = new OpeningInventoryTransactions(codec, ignored -> saves.incrementAndGet(), () -> true);
                var reward = new RewardDefinition("prize", 1, "frozen", "preview");
                var pool = new PoolSnapshot("daily", "launch", List.of(reward), 1, 0, 1);
                var planned = inventory.debit(player, UUID.randomUUID(),
                        item -> item.getType() == Material.TRIPWIRE_HOOK, 1).orElseThrow();
                var interrupted = ledger.reserve(planned.openingId(), player.getUniqueId(), pool,
                        List.of(reward), codec.write(planned));
                if (applied) assertEquals(OpeningInventoryTransactions.Outcome.APPLIED, inventory.apply(player, planned));
                var engine = engine(ledger, inventory, codec, ignored -> new ItemStack[]{new ItemStack(Material.DIAMOND)});

                var resumed = engine.openPhysical(player, 1L, pool,
                        item -> item.getType() == Material.TRIPWIRE_HOOK, 1).join().orElseThrow();
                assertEquals(OpeningRecord.Stage.CHOOSING, resumed.stage());
                assertEquals(1, player.getInventory().getItem(0).getAmount());
                assertEquals(1, saves.get(), "The retry must charge exactly one key across both attempts");
                assertEquals(applied ? OpeningRecord.Stage.CHOOSING : OpeningRecord.Stage.ABORTED,
                        ledger.get(interrupted.id(), interrupted.playerId()).stage());
                assertEquals(applied, interrupted.id().equals(resumed.id()));
                assertEquals(resumed, engine.openPhysical(player, 1L, pool,
                        item -> item.getType() == Material.TRIPWIRE_HOOK, 1).join().orElseThrow());
                assertEquals(1, saves.get());
            }
        }
    }

    @Test void ordinaryRetryHealsInterruptedRewardDeliveryWithoutRepeatingTheGrant() {
        for (boolean applied : new boolean[]{false, true}) {
            try (var runtime = MockBukkitTestRuntime.Companion.open()) {
                var player = runtime.addPlayer("CrateQA");
                var ledger = new OpeningLedger(new MemoryStore(), Clock.systemUTC());
                var codec = new NativeItemPayload();
                var saves = new AtomicInteger();
                var inventory = new OpeningInventoryTransactions(codec, ignored -> saves.incrementAndGet(), () -> true);
                var mail = preparedMail(ledger, player.getUniqueId(), codec, new ItemStack(Material.DIAMOND, 2));
                var planned = inventory.delivery(player, mail.id(), codec.items(mail.preparedReward())).orElseThrow();
                var interrupted = ledger.deliveryStarted(mail.id(), mail.playerId(), mail.revision(), codec.write(planned));
                if (applied) assertEquals(OpeningInventoryTransactions.Outcome.APPLIED, inventory.apply(player, planned));
                var engine = engine(ledger, inventory, codec, ignored -> { throw new AssertionError("Already prepared"); });

                var retry = engine.openPhysical(player, 1L, mail.pool(),
                        item -> item.getType() == Material.TRIPWIRE_HOOK, 1).join();
                if (applied) {
                    assertTrue(retry.isEmpty(), "A completed delivery must not demand another key");
                } else {
                    var resumed = retry.orElseThrow();
                    assertEquals(OpeningRecord.Stage.MAIL, resumed.stage());
                    assertEquals(mail.id(), resumed.id());
                    assertEquals(OpeningRecord.Stage.DELIVERED,
                            engine.claim(player, 1L, resumed.id(), resumed.revision()).join().stage());
                }
                assertEquals(OpeningRecord.Stage.DELIVERED, ledger.get(interrupted.id(), interrupted.playerId()).stage());
                assertEquals(2, player.getInventory().getItem(0).getAmount());
                assertEquals(1, saves.get());
                assertTrue(engine.recover(player, 1L).join().isEmpty());
                assertEquals(1, saves.get());
            }
        }
    }

    @Test void inventoryMoveDuringKeyJournalWriteRejectsWithoutReviewAndNextOpenSucceeds() {
        try (var runtime = MockBukkitTestRuntime.Companion.open()) {
            var player = runtime.addPlayer("CrateQA");
            player.getInventory().setItem(0, new ItemStack(Material.TRIPWIRE_HOOK, 2));
            player.getInventory().setItem(1, new ItemStack(Material.STONE, 5));
            var ledger = new OpeningLedger(new MemoryStore(), Clock.systemUTC());
            var codec = new NativeItemPayload();
            var saves = new AtomicInteger();
            var inventory = new OpeningInventoryTransactions(codec, p -> saves.incrementAndGet(), () -> true);
            var reward = new RewardDefinition("prize", 1, "frozen", "preview");
            var pool = new PoolSnapshot("daily", "launch", List.of(reward), 1, 0, 1);
            var storage = new QueuedExecutor();
            var engine = new ManagedOpeningEngine(ledger, new WeightedOfferGenerator(new Random(1)), inventory, codec,
                    ignored -> new ItemStack[]{new ItemStack(Material.DIAMOND)}, storage, Runnable::run,
                    (candidate, session) -> candidate.isOnline());
            var attempt = engine.openPhysical(player, 1L, pool,
                    item -> item.getType() == Material.TRIPWIRE_HOOK, 1);
            storage.runNext(); // Check for an interrupted prior operation.
            storage.runNext(); // Plan the debit, leaving its journal write queued.
            assertFalse(attempt.isDone());
            player.getInventory().setItem(7, player.getInventory().getItem(1));
            player.getInventory().setItem(1, null);
            storage.runNext();
            storage.runNext();

            var rejected = attempt.join().orElseThrow();
            assertEquals(OpeningRecord.Stage.ABORTED, rejected.stage());
            assertTrue(ledger.active(player.getUniqueId()).isEmpty());
            assertEquals(2, player.getInventory().getItem(0).getAmount());
            assertNull(player.getInventory().getItem(1));
            assertEquals(Material.STONE, player.getInventory().getItem(7).getType());
            assertEquals(5, player.getInventory().getItem(7).getAmount());
            assertEquals(0, saves.get(), "A stale first plan must not mutate or save player data");

            var retry = engine.openPhysical(player, 1L, pool,
                    item -> item.getType() == Material.TRIPWIRE_HOOK, 1);
            storage.runNext();
            storage.runNext();
            storage.runNext();
            storage.runNext();
            assertEquals(OpeningRecord.Stage.CHOOSING, retry.join().orElseThrow().stage());
            assertEquals(1, player.getInventory().getItem(0).getAmount());
            assertEquals(5, player.getInventory().getItem(7).getAmount());
            assertEquals(1, saves.get());
        }
    }

    @Test void inventoryMoveDuringDeliveryJournalWritePreservesItemsAndGrantsOnce() {
        try (var runtime = MockBukkitTestRuntime.Companion.open()) {
            var player = runtime.addPlayer("CrateQA");
            player.getInventory().setItem(0, new ItemStack(Material.STONE, 5));
            var ledger = new OpeningLedger(new MemoryStore(), Clock.systemUTC());
            var codec = new NativeItemPayload();
            var saves = new AtomicInteger();
            var inventory = new OpeningInventoryTransactions(codec, p -> saves.incrementAndGet(), () -> true);
            var mail = preparedMail(ledger, player.getUniqueId(), codec, new ItemStack(Material.DIAMOND, 2));
            var storage = new QueuedExecutor();
            var engine = new ManagedOpeningEngine(ledger, new WeightedOfferGenerator(new Random(1)), inventory, codec,
                    ignored -> { throw new AssertionError("The frozen reward must be reused"); }, storage, Runnable::run,
                    (candidate, session) -> candidate.isOnline());
            var claim = engine.claim(player, 1L, mail.id(), mail.revision());
            storage.runNext(); // Capture the plan, leaving its journal write queued.
            assertFalse(claim.isDone());
            player.getInventory().setItem(7, player.getInventory().getItem(0));
            player.getInventory().setItem(0, null);
            storage.runNext(); // Commit and replan/apply together on Paper.
            storage.runNext();
            var delivered = claim.join();
            assertEquals(OpeningRecord.Stage.DELIVERED, delivered.stage());
            assertEquals(Material.STONE, player.getInventory().getItem(7).getType());
            assertEquals(5, player.getInventory().getItem(7).getAmount());
            assertEquals(Material.DIAMOND, player.getInventory().getItem(0).getType());
            assertEquals(2, player.getInventory().getItem(0).getAmount());
            var witness = codec.read(delivered.deliveryWitness(), InventoryMutationWitness.class);
            assertEquals(OpeningInventoryTransactions.Outcome.APPLIED,
                    inventory.applyFreshDelivery(player, witness, codec.items(delivered.preparedReward())));
            assertEquals(1, saves.get());
            var duplicate = engine.claim(player, 1L, delivered.id(), delivered.revision());
            storage.runNext();
            assertThrows(java.util.concurrent.CompletionException.class, duplicate::join);
            assertEquals(2, player.getInventory().getItem(0).getAmount());
        }
    }

    @Test void inventoryFillingDuringDeliveryJournalWriteReturnsWholeRewardToMail() {
        try (var runtime = MockBukkitTestRuntime.Companion.open()) {
            var player = runtime.addPlayer("CrateQA");
            for (int slot = 1; slot < 35; slot++) player.getInventory().setItem(slot, new ItemStack(Material.STONE, 64));
            player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 63));
            var ledger = new OpeningLedger(new MemoryStore(), Clock.systemUTC());
            var codec = new NativeItemPayload();
            var saves = new AtomicInteger();
            var inventory = new OpeningInventoryTransactions(codec, p -> saves.incrementAndGet(), () -> true);
            var mail = preparedMail(ledger, player.getUniqueId(), codec, new ItemStack(Material.DIAMOND, 2));
            var storage = new QueuedExecutor();
            var engine = new ManagedOpeningEngine(ledger, new WeightedOfferGenerator(new Random(1)), inventory, codec,
                    ignored -> { throw new AssertionError("The frozen reward must be reused"); }, storage, Runnable::run,
                    (candidate, session) -> candidate.isOnline());
            var claim = engine.claim(player, 1L, mail.id(), mail.revision());
            storage.runNext();
            player.getInventory().setItem(35, new ItemStack(Material.STONE, 64));
            var before = codec.capture(player.getInventory().getContents());
            storage.runNext();
            storage.runNext();
            var waiting = claim.join();
            assertEquals(OpeningRecord.Stage.MAIL, waiting.stage());
            assertEquals(mail.preparedReward(), waiting.preparedReward());
            assertEquals(before, codec.capture(player.getInventory().getContents()));
            assertEquals(0, saves.get());
            player.getInventory().setItem(35, null);
            var retry = engine.claim(player, 1L, waiting.id(), waiting.revision());
            storage.runNext();
            storage.runNext();
            storage.runNext();
            assertEquals(OpeningRecord.Stage.DELIVERED, retry.join().stage());
            assertEquals(64, player.getInventory().getItem(0).getAmount());
            assertEquals(1, player.getInventory().getItem(35).getAmount());
            assertEquals(1, saves.get());
        }
    }

    @Test void nativeRewardSaveFailureRequiresReviewAndNeverRepeatsTheGrant() {
        try (var runtime = MockBukkitTestRuntime.Companion.open()) {
            var player = runtime.addPlayer("CrateQA");
            var ledger = new OpeningLedger(new MemoryStore(), Clock.systemUTC());
            var codec = new NativeItemPayload();
            var saves = new AtomicInteger();
            var inventory = new OpeningInventoryTransactions(codec, p -> {
                saves.incrementAndGet();
                throw new IllegalStateException("Native player-data persistence failed");
            }, () -> true);
            var mail = preparedMail(ledger, player.getUniqueId(), codec, new ItemStack(Material.DIAMOND, 2));
            var engine = engine(ledger, inventory, codec, ignored -> { throw new AssertionError("Already prepared"); });
            var reviewed = engine.claim(player, 1L, mail.id(), mail.revision()).join();
            assertEquals(OpeningRecord.Stage.REVIEW, reviewed.stage());
            assertEquals(2, player.getInventory().getItem(0).getAmount());
            var witness = codec.read(reviewed.deliveryWitness(), InventoryMutationWitness.class);
            assertEquals(OpeningInventoryTransactions.Outcome.UNKNOWN,
                    inventory.applyFreshDelivery(player, witness, codec.items(reviewed.preparedReward())));
            assertThrows(java.util.concurrent.CompletionException.class,
                    () -> engine.claim(player, 1L, reviewed.id(), reviewed.revision()).join());
            assertTrue(engine.pending(player.getUniqueId()).join().isEmpty(), "A retained review must not block another opening");
            assertEquals(reviewed, ledger.get(reviewed.id(), reviewed.playerId()));
            assertEquals(reviewed, engine.recover(player, 1L).join().orElseThrow(),
                    "Ordinary recovery must not clear native-save uncertainty");
            assertEquals(1, saves.get());
            assertEquals(2, player.getInventory().getItem(0).getAmount());

            var recovered = engine.playerDataLoaded(player, 2L).join().orElseThrow();
            assertEquals(OpeningRecord.Stage.DELIVERED, recovered.stage());
            assertEquals("native-receipt-confirmed", recovered.reason());
            assertEquals(1, saves.get(), "Receipt recovery must not repeat the native grant");
            assertEquals(2, player.getInventory().getItem(0).getAmount());
        }
    }

    @Test void retainedUnknownRewardDoesNotBlockNewOpeningOrReplayAfterReceiptChanges() {
        try (var runtime = MockBukkitTestRuntime.Companion.open()) {
            var player = runtime.addPlayer("CrateQA");
            player.getInventory().setItem(0, new ItemStack(Material.TRIPWIRE_HOOK, 2));
            var ledger = new OpeningLedger(new MemoryStore(), Clock.systemUTC());
            var codec = new NativeItemPayload();
            var saves = new AtomicInteger();
            var inventory = new OpeningInventoryTransactions(codec, ignored -> saves.incrementAndGet(), () -> true);
            var mail = preparedMail(ledger, player.getUniqueId(), codec, new ItemStack(Material.SPECTRAL_ARROW, 32));
            var witness = inventory.delivery(player, mail.id(), codec.items(mail.preparedReward())).orElseThrow();
            var delivering = ledger.deliveryStarted(mail.id(), mail.playerId(), mail.revision(), codec.write(witness));
            var reviewed = ledger.review(delivering.id(), delivering.playerId(), delivering.revision(),
                    "native-reward-delivery-unconfirmed");
            player.getInventory().setItem(10, new ItemStack(Material.STONE, 5));
            var engine = engine(ledger, inventory, codec, ignored -> new ItemStack[]{new ItemStack(Material.DIAMOND)});

            assertEquals(reviewed, engine.playerDataLoaded(player, 1L).join().orElseThrow());
            assertTrue(engine.pending(player.getUniqueId()).join().isEmpty());
            var opened = engine.openPhysical(player, 1L, mail.pool(),
                    item -> item.getType() == Material.TRIPWIRE_HOOK, 1).join().orElseThrow();
            assertEquals(OpeningRecord.Stage.CHOOSING, opened.stage());
            assertEquals(opened, engine.playerDataLoaded(player, 1L).join().orElseThrow(),
                    "The current opening must take priority over a retained review");
            var selected = engine.select(player, opened.id(), opened.revision(), "prize").join();
            var delivered = engine.claim(player, 1L, selected.id(), selected.revision()).join();
            assertEquals(OpeningRecord.Stage.DELIVERED, delivered.stage());
            assertEquals(1, player.getInventory().getItem(0).getAmount());
            assertEquals(Material.DIAMOND, player.getInventory().getItem(1).getType());
            assertEquals(5, player.getInventory().getItem(10).getAmount());

            var beforeRecovery = codec.capture(player.getInventory().getContents());
            assertEquals(reviewed, engine.playerDataLoaded(player, 2L).join().orElseThrow());
            assertEquals(reviewed, ledger.get(reviewed.id(), reviewed.playerId()));
            assertTrue(reviewed.pending(), "The unknown reward must remain in the durable recovery backlog");
            assertEquals(beforeRecovery, codec.capture(player.getInventory().getContents()));
            assertEquals(2, saves.get(), "Only the new key debit and new reward may save native data");
        }
    }

    @Test void operatorResolutionOnlyClosesReviewedPreparedRewardsWithoutNativeEffects() {
        try (var runtime = MockBukkitTestRuntime.Companion.open()) {
            var player = runtime.addPlayer("CrateQA");
            player.getInventory().setItem(5, new ItemStack(Material.SPECTRAL_ARROW, 32));
            var ledger = new OpeningLedger(new MemoryStore(), Clock.systemUTC());
            var codec = new NativeItemPayload();
            var inventory = new OpeningInventoryTransactions(codec, p -> fail("Resolution must not save player data"), () -> true);
            var mail = preparedMail(ledger, player.getUniqueId(), codec, new ItemStack(Material.SPECTRAL_ARROW, 32));
            var delivering = ledger.deliveryStarted(mail.id(), mail.playerId(), mail.revision(), "native-witness");
            var reviewed = ledger.review(delivering.id(), delivering.playerId(), delivering.revision(), "unconfirmed-save");
            var otherMail = preparedMail(ledger, UUID.randomUUID(), codec, new ItemStack(Material.DIAMOND));
            var debit = ledger.reserve(UUID.randomUUID(), UUID.randomUUID(), mail.pool(), mail.offers(), "key-witness");
            var debitReview = ledger.review(debit.id(), debit.playerId(), debit.revision(), "unconfirmed-debit");
            var choosingDebit = ledger.reserve(UUID.randomUUID(), UUID.randomUUID(), mail.pool(), mail.offers(), "key-witness");
            var choosing = ledger.debitConfirmed(choosingDebit.id(), choosingDebit.playerId(), choosingDebit.revision());
            var storage = new QueuedExecutor();
            var engine = new ManagedOpeningEngine(ledger, new WeightedOfferGenerator(new Random(1)), inventory, codec,
                    ignored -> { throw new AssertionError("Resolution must not materialize rewards"); }, storage,
                    ignored -> fail("Resolution must not schedule native work"), (candidate, session) -> true);
            var before = codec.capture(player.getInventory().getContents());
            for (UUID denied : List.of(otherMail.id(), debitReview.id(), choosing.id(), UUID.randomUUID())) {
                var rejected = engine.resolveCompensatedOpening(denied);
                storage.runNext();
                assertThrows(java.util.concurrent.CompletionException.class, rejected::join);
            }
            var resolution = engine.resolveCompensatedOpening(reviewed.id());
            assertFalse(resolution.isDone(), "Resolution must use the storage executor");
            storage.runNext();
            var resolved = resolution.join();
            assertEquals(OpeningRecord.Stage.DELIVERED, resolved.stage());
            assertEquals("operator-compensated-native-grant", resolved.reason());
            assertTrue(ledger.pending(player.getUniqueId()).isEmpty());
            assertTrue(ledger.active(player.getUniqueId()).isEmpty());
            assertEquals(before, codec.capture(player.getInventory().getContents()));
            var duplicate = engine.resolveCompensatedOpening(reviewed.id());
            storage.runNext();
            assertThrows(java.util.concurrent.CompletionException.class, duplicate::join);
            assertEquals(resolved, ledger.get(resolved.id(), resolved.playerId()));
            assertEquals(before, codec.capture(player.getInventory().getContents()));
        }
    }

    @Test void copiedPeriodicKeyCannotBuyAnotherRollButOrdinaryKeysStillWork() {
        try (var runtime = MockBukkitTestRuntime.Companion.open()) {
            var player = runtime.addPlayer("CrateQA");
            var ledger = new OpeningLedger(new MemoryStore(), Clock.systemUTC());
            var codec = new NativeItemPayload();
            var inventory = new OpeningInventoryTransactions(codec, p -> {}, () -> true);
            var prize = new RewardDefinition("prize", 1, "frozen", "preview");
            var pool = new PoolSnapshot("daily", "launch", List.of(prize), 1, 0, 1);
            var engine = engine(ledger, inventory, codec, definition -> new ItemStack[]{new ItemStack(Material.DIAMOND)});
            var periodId = UUID.randomUUID();
            player.getInventory().setItem(0, new ItemStack(Material.TRIPWIRE_HOOK, 2));
            var opened = engine.openPeriodicKey(player, 1L, pool,
                    item -> item != null && item.getType() == Material.TRIPWIRE_HOOK, periodId).join().orElseThrow();
            var selected = engine.select(player, opened.id(), opened.revision(), "prize").join();
            assertEquals(OpeningRecord.Stage.DELIVERED, engine.claim(player, 1L, selected.id(), selected.revision()).join().stage());
            assertTrue(engine.openPeriodicKey(player, 1L, pool, item -> true, periodId).join().isEmpty());
            assertEquals(1, player.getInventory().getItem(0).getAmount());
            assertTrue(engine.openPhysical(player, 1L, pool,
                    item -> item != null && item.getType() == Material.TRIPWIRE_HOOK, 1).join().isPresent());
            assertEquals(2, ledger.snapshot().size());
        }
    }

    @Test void periodicKeyExpiringDuringReservationIsNotDebitedAndRetryKeepsFrozenReward() {
        try (var runtime = MockBukkitTestRuntime.Companion.open()) {
            var player = runtime.addPlayer("CrateQA");
            var ledger = new OpeningLedger(new MemoryStore(), Clock.systemUTC());
            var codec = new NativeItemPayload();
            var inventory = new OpeningInventoryTransactions(codec, p -> {}, () -> true);
            var prize = new RewardDefinition("prize", 1, "frozen", "preview");
            var pool = new PoolSnapshot("daily", "launch", List.of(prize), 1, 0, 1);
            var engine = engine(ledger, inventory, codec, definition -> new ItemStack[]{new ItemStack(Material.DIAMOND)});
            var periodId = UUID.randomUUID();
            var checks = new AtomicInteger();
            player.getInventory().setItem(0, new ItemStack(Material.TRIPWIRE_HOOK));
            var aborted = engine.openPeriodicKey(player, 1L, pool, item -> item != null && !item.isEmpty()
                    && checks.getAndIncrement() == 0, periodId).join().orElseThrow();
            assertEquals(OpeningRecord.Stage.ABORTED, aborted.stage());
            assertEquals(1, player.getInventory().getItem(0).getAmount());
            var retry = engine.openPeriodicKey(player, 1L, pool,
                    item -> item != null && item.getType() == Material.TRIPWIRE_HOOK, periodId).join().orElseThrow();
            assertEquals(OpeningRecord.Stage.CHOOSING, retry.stage());
            assertEquals(aborted.offers(), retry.offers());
            assertEquals(1, ledger.snapshot().size());
        }
    }

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

    private static OpeningRecord preparedMail(OpeningLedger ledger, UUID playerId, NativeItemPayload codec, ItemStack... rewards) {
        var reward = new RewardDefinition("prize", 1, "frozen", "preview");
        var pool = new PoolSnapshot("daily", "launch", List.of(reward), 1, 0, 1);
        var reserved = ledger.reserve(UUID.randomUUID(), playerId, pool, List.of(reward), "key-witness");
        var choosing = ledger.debitConfirmed(reserved.id(), playerId, reserved.revision());
        var selected = ledger.select(choosing.id(), playerId, choosing.revision(), reward.id());
        return ledger.prepared(selected.id(), playerId, selected.revision(), codec.items(rewards));
    }

    private static final class QueuedExecutor implements Executor {
        private final Deque<Runnable> queued = new ArrayDeque<>();
        @Override public void execute(Runnable action) { queued.addLast(action); }
        private void runNext() { queued.removeFirst().run(); }
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
