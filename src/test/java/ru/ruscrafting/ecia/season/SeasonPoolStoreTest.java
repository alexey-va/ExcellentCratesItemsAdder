package ru.ruscrafting.ecia.season;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import kotlin.Unit;
import ru.arc.persistence.DurableRecordJournal;
import ru.ruscrafting.ecia.roll.PoolSnapshot;
import ru.ruscrafting.ecia.roll.RewardDefinition;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SeasonPoolStoreTest {
    private static final RewardDefinition COMMON = new RewardDefinition(
            "common", 9.0, "deliver:common", "preview:common");
    private static final RewardDefinition RARE = new RewardDefinition(
            "rare", 1.0, "deliver:rare", "preview:rare");

    @TempDir
    Path directory;

    @Test
    void identicalPoolReusesAndChangedDefinitionIsRejected() {
        PoolSnapshot first = pool("summer", COMMON);
        SeasonPoolStore store = new SeasonPoolStore(directory);

        assertEquals(first, store.register(first));
        assertEquals(first, store.register(first));
        assertThrows(IllegalStateException.class, () -> store.register(pool("summer", RARE)));
        assertEquals(List.of(first), store.snapshots());
    }

    @Test
    void restartLoadsOldSeasonWithoutChangingItsSnapshot() {
        PoolSnapshot old = pool("summer", COMMON);
        SeasonPoolStore first = new SeasonPoolStore(directory);
        first.register(old);

        SeasonPoolStore restarted = new SeasonPoolStore(directory);

        assertEquals(old, restarted.find("crate", "summer").orElseThrow());
        assertEquals(List.of(old), restarted.snapshots());
    }

    @Test
    void corruptRecordFailsClosed() throws IOException {
        SeasonPoolStore store = new SeasonPoolStore(directory);
        store.register(pool("summer", COMMON));
        Path record;
        try (var files = Files.list(store.directory())) {
            record = files.findFirst().orElseThrow();
        }
        Files.writeString(record, "{broken");

        assertThrows(RuntimeException.class, () -> new SeasonPoolStore(directory));
    }

    @Test
    void failedCommitDoesNotPublishAnUnconfirmedPool() {
        DurableRecordJournal<PoolSnapshot> journal = new DurableRecordJournal<>(
                directory,
                Path.of("season-pools"),
                1L,
                snapshot -> new byte[]{1, 2},
                bytes -> pool("summer", COMMON),
                snapshot -> Unit.INSTANCE
        );
        SeasonPoolStore store = new SeasonPoolStore(journal);

        assertThrows(IllegalArgumentException.class, () -> store.register(pool("summer", COMMON)));
        assertEquals(java.util.Optional.empty(), store.find("crate", "summer"));
    }

    @Test
    void readbackFailurePoisonsStoreBeforeAnAlternativeCanOverwriteTheKey() {
        PoolSnapshot changed = pool("summer", RARE);
        DurableRecordJournal<PoolSnapshot> journal = new DurableRecordJournal<>(
                directory,
                Path.of("season-pools"),
                128L,
                snapshot -> new byte[]{1},
                bytes -> changed,
                snapshot -> Unit.INSTANCE
        );
        SeasonPoolStore store = new SeasonPoolStore(journal);

        assertThrows(IllegalStateException.class, () -> store.register(pool("summer", COMMON)));
        assertThrows(IllegalStateException.class, () -> store.register(changed));
        assertEquals(java.util.Optional.empty(), store.find("crate", "summer"));
    }

    private static PoolSnapshot pool(String season, RewardDefinition reward) {
        return new PoolSnapshot("crate", season, List.of(reward), 3, 1);
    }
}
