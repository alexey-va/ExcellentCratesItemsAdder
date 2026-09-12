package ru.ruscrafting.ecia.season;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import kotlin.Unit;
import ru.arc.persistence.DurableRecord;
import ru.arc.persistence.DurableRecordJournal;
import ru.ruscrafting.ecia.roll.PoolSnapshot;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Durable registry of immutable crate and season pool definitions. */
public final class SeasonPoolStore {
    private static final Path JOURNAL_DIRECTORY = Path.of("season-pools");
    private static final long MAX_RECORD_BYTES = 8L * 1024L * 1024L;
    private static final int MAX_IDENTIFIER_LENGTH = 128;
    private static final int MAX_PAYLOAD_LENGTH = 2_000_000;
    private static final String IDENTIFIER_PATTERN = "[A-Za-z0-9][A-Za-z0-9._:-]*";

    private final DurableRecordJournal<PoolSnapshot> journal;
    private final Map<String, PoolSnapshot> snapshots = new LinkedHashMap<>();
    private boolean poisoned;
    private RuntimeException poisonCause;

    public SeasonPoolStore(Path dataDirectory) {
        this(createJournal(dataDirectory));
    }

    /** Package-private seam for deterministic durability-failure tests. */
    SeasonPoolStore(DurableRecordJournal<PoolSnapshot> journal) {
        this.journal = Objects.requireNonNull(journal, "journal");
        for (DurableRecord<PoolSnapshot> record : journal.loadAll()) {
            validateSnapshot(record.getValue());
            String expectedId = recordId(record.getValue());
            if (!expectedId.equals(record.getRecordId())) {
                throw new IllegalStateException("Season pool journal id does not match its snapshot");
            }
            String key = poolKey(record.getValue());
            if (snapshots.putIfAbsent(key, record.getValue()) != null) {
                throw new IllegalStateException("Duplicate season pool snapshot: " + key);
            }
        }
    }

    /**
     * Registers a pool once. An identical snapshot is returned from memory;
     * changing a definition under the same crate and season is rejected.
     * A journal exception is propagated before the in-memory registry changes.
     */
    public synchronized PoolSnapshot register(PoolSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        validateSnapshot(snapshot);
        if (poisoned) {
            throw new IllegalStateException("Season pool store is unavailable; create a new store to reload it", poisonCause);
        }
        String key = poolKey(snapshot);
        PoolSnapshot existing = snapshots.get(key);
        if (existing != null) {
            if (!existing.equals(snapshot)) {
                throw new IllegalStateException("Season pool is immutable: "
                        + snapshot.crateId() + "/" + snapshot.seasonId());
            }
            return existing;
        }

        try {
            PoolSnapshot readback = journal.commit(recordId(snapshot), snapshot);
            if (!snapshot.equals(readback)) {
                throw new IllegalStateException("Season pool durable readback mismatch");
            }
            snapshots.put(key, readback);
            return readback;
        } catch (RuntimeException failure) {
            poisoned = true;
            poisonCause = failure;
            throw failure;
        }
    }

    public synchronized Optional<PoolSnapshot> find(String crateId, String seasonId) {
        return Optional.ofNullable(snapshots.get(poolKey(crateId, seasonId)));
    }

    public synchronized List<PoolSnapshot> snapshots() {
        return snapshots.values().stream()
                .sorted(Comparator.comparing(PoolSnapshot::crateId).thenComparing(PoolSnapshot::seasonId))
                .toList();
    }

    /** Exposes the trusted journal directory for operator diagnostics and tests. */
    public Path directory() {
        return journal.getDirectory();
    }

    private static DurableRecordJournal<PoolSnapshot> createJournal(Path dataDirectory) {
        ObjectMapper mapper = new ObjectMapper()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
        return new DurableRecordJournal<>(
                Objects.requireNonNull(dataDirectory, "dataDirectory"),
                JOURNAL_DIRECTORY,
                MAX_RECORD_BYTES,
                snapshot -> encode(mapper, snapshot),
                bytes -> decode(mapper, bytes),
                snapshot -> Unit.INSTANCE
        );
    }

    private static byte[] encode(ObjectMapper mapper, PoolSnapshot snapshot) {
        try {
            return mapper.writeValueAsBytes(snapshot);
        } catch (JsonProcessingException error) {
            throw new UncheckedIOException("Could not encode season pool " + poolKey(snapshot), error);
        }
    }

    private static PoolSnapshot decode(ObjectMapper mapper, byte[] bytes) {
        try {
            return mapper.readValue(bytes, PoolSnapshot.class);
        } catch (IOException error) {
            throw new UncheckedIOException("Could not decode season pool", error);
        }
    }

    private static String poolKey(PoolSnapshot snapshot) {
        return poolKey(snapshot.crateId(), snapshot.seasonId());
    }

    private static String poolKey(String crateId, String seasonId) {
        requireIdentifier(crateId, "crateId");
        requireIdentifier(seasonId, "seasonId");
        return crateId + "\u0000" + seasonId;
    }

    private static String recordId(PoolSnapshot snapshot) {
        byte[] key = poolKey(snapshot).getBytes(StandardCharsets.UTF_8);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static void requireIdentifier(String value, String fieldName) {
        if (value == null || value.length() > MAX_IDENTIFIER_LENGTH || !value.matches(IDENTIFIER_PATTERN)) {
            throw new IllegalArgumentException(fieldName + " must be a non-empty safe identifier");
        }
    }

    private static void validateSnapshot(PoolSnapshot snapshot) {
        requireIdentifier(snapshot.crateId(), "crateId");
        requireIdentifier(snapshot.seasonId(), "seasonId");
        for (var reward : snapshot.rewards()) {
            requireIdentifier(reward.id(), "reward id");
            if (reward.deliveryPayload().length() > MAX_PAYLOAD_LENGTH
                    || reward.previewPayload().length() > MAX_PAYLOAD_LENGTH) {
                throw new IllegalArgumentException("Reward payload exceeds " + MAX_PAYLOAD_LENGTH + " characters");
            }
        }
    }
}
