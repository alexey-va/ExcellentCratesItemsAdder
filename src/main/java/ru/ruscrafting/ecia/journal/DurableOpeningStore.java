package ru.ruscrafting.ecia.journal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import kotlin.Unit;
import ru.arc.persistence.DurableRecordJournal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;

/** Opening records use Core's fsync/readback boundary; no native item serialization is reinvented here. */
public final class DurableOpeningStore implements OpeningStore {
    private final DurableRecordJournal<OpeningRecord> journal;
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);

    public DurableOpeningStore(Path dataDirectory) {
        journal = new DurableRecordJournal<>(dataDirectory, Path.of("openings"), 8L * 1024 * 1024,
                this::encode, this::decode, record -> Unit.INSTANCE);
    }

    @Override
    public List<OpeningRecord> load() {
        return journal.loadAll().stream().map(record -> {
            if (!record.getRecordId().equals(record.getValue().id().toString())) {
                throw new IllegalStateException("Opening journal identity mismatch");
            }
            return record.getValue();
        }).toList();
    }

    @Override
    public OpeningRecord commit(OpeningRecord record) {
        return journal.commit(record.id().toString(), record);
    }

    private byte[] encode(OpeningRecord record) {
        try {
            return mapper.writeValueAsBytes(record);
        } catch (JsonProcessingException error) {
            throw new UncheckedIOException("Could not encode opening " + record.id(), error);
        }
    }

    private OpeningRecord decode(byte[] bytes) {
        try {
            return mapper.readValue(bytes, OpeningRecord.class);
        } catch (IOException error) {
            throw new UncheckedIOException("Could not decode durable opening", error);
        }
    }
}
