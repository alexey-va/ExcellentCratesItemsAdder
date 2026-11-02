package ru.ruscrafting.ecia.journal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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

    @Test void exactLookupDoesNotScanOtherFilesAndChecksTheFilenameIdentity() throws Exception {
        DurableOpeningStore store = new DurableOpeningStore(directory);
        OpeningLedger ledger = new OpeningLedger(store, CLOCK);
        OpeningRecord first = ready(ledger);
        first = ledger.select(first.id(), PLAYER, first.revision(), COMMON.id());
        OpeningRecord second = ready(ledger);
        Path firstFile = directory.resolve("openings").resolve(first.id() + ".json");
        Path secondFile = directory.resolve("openings").resolve(second.id() + ".json");
        Files.writeString(secondFile, "{broken");

        assertEquals(first, store.find(first.id()).orElseThrow());
        assertTrue(store.find(UUID.randomUUID()).isEmpty());
        Files.copy(firstFile, secondFile, StandardCopyOption.REPLACE_EXISTING);
        assertThrows(IllegalStateException.class, () -> store.find(second.id()));
    }
}
