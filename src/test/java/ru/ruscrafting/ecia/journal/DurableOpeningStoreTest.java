package ru.ruscrafting.ecia.journal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static ru.ruscrafting.ecia.journal.OpeningLedgerTest.*;

class DurableOpeningStoreTest {
    @TempDir Path directory;

    @Test void exactNativePayloadAndPoolSurviveFreshStoreAndLedger() {
        OpeningLedger ledger = new OpeningLedger(new DurableOpeningStore(directory), CLOCK);
        OpeningRecord record = ready(ledger);
        record = ledger.select(record.id(), PLAYER, record.revision(), RARE.id());
        OpeningRecord prepared = ledger.prepared(record.id(), PLAYER, record.revision(), "base64-native-item-payload");
        OpeningLedger restarted = new OpeningLedger(new DurableOpeningStore(directory), CLOCK);
        assertEquals(prepared, restarted.get(record.id(), PLAYER));
        assertEquals(POOL, restarted.get(record.id(), PLAYER).pool());
    }

    @Test void corruptJournalFailsClosedInsteadOfForgettingEntitlement() throws Exception {
        OpeningLedger ledger = new OpeningLedger(new DurableOpeningStore(directory), CLOCK);
        OpeningRecord record = ready(ledger);
        Files.writeString(directory.resolve("openings").resolve(record.id() + ".json"), "{broken");
        assertThrows(RuntimeException.class,
                () -> new OpeningLedger(new DurableOpeningStore(directory), CLOCK));
    }

    @Test void nullMissingFieldsCannotTurnCorruptRecordIntoFreshState() throws Exception {
        new DurableOpeningStore(directory);
        Files.writeString(directory.resolve("openings").resolve(UUID.randomUUID() + ".json"), "{}");
        assertThrows(RuntimeException.class,
                () -> new OpeningLedger(new DurableOpeningStore(directory), CLOCK));
    }
}
