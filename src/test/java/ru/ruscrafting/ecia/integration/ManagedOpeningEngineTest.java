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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ManagedOpeningEngineTest {
    @Test void restartAndRepeatedClicksKeepOneKeyOnePreparedBundle() {
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
            var engine = new ManagedOpeningEngine(ledger, new WeightedOfferGenerator(new Random(1)), inventory, codec, definition -> {
                preparations.incrementAndGet();
                return new ItemStack[]{new ItemStack(Material.DIAMOND)};
            });
            player.getInventory().setItem(0, new ItemStack(Material.TRIPWIRE_HOOK, 2));
            var opening = engine.open(player, pool, stack -> stack.getType() == Material.TRIPWIRE_HOOK, 1).orElseThrow();
            assertEquals(OpeningRecord.Stage.CHOOSING, opening.stage());
            assertEquals(opening, engine.open(player, pool, stack -> true, 1).orElseThrow());
            assertEquals(1, player.getInventory().getItem(0).getAmount());
            for (int slot = 0; slot < 36; slot++) player.getInventory().setItem(slot, new ItemStack(Material.STONE, 64));
            var mail = engine.select(player, opening.id(), opening.revision(), "prize");
            var prepared = engine.claim(player, mail.id(), mail.revision());
            assertEquals(OpeningRecord.Stage.MAIL, prepared.stage());
            assertEquals(3, preparations.get());
            assertEquals(prepared, engine.claim(player, prepared.id(), prepared.revision()));
            assertEquals(3, preparations.get());
            var restartedLedger = new OpeningLedger(store, Clock.systemUTC());
            var restarted = new ManagedOpeningEngine(restartedLedger, new WeightedOfferGenerator(new Random(2)), inventory, codec,
                    definition -> { fail("Persisted native prize must not be minted again"); return null; });
            player.getInventory().setItem(0, null);
            var delivered = restarted.claim(player, prepared.id(), prepared.revision());
            assertEquals(OpeningRecord.Stage.DELIVERED, delivered.stage());
            assertEquals(Material.DIAMOND, player.getInventory().getItem(0).getType());
            assertEquals(3, player.getInventory().getItem(0).getAmount());
            assertThrows(IllegalStateException.class, () -> restarted.claim(player, delivered.id(), delivered.revision()));
        }
    }

    private static final class MemoryStore implements OpeningStore {
        private final Map<UUID, OpeningRecord> values = new HashMap<>();
        @Override public List<OpeningRecord> load() { return List.copyOf(values.values()); }
        @Override public OpeningRecord commit(OpeningRecord value) { values.put(value.id(), value); return value; }
    }
}
