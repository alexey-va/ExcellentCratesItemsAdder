package ru.ruscrafting.ecia.journal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Commit must return only after durable readback; failure never authorizes a side effect. */
public interface OpeningStore {
    List<OpeningRecord> load();
    /** Blocking lookup; durable stores override this compatibility scan with an exact read. */
    default Optional<OpeningRecord> find(UUID id) {
        return load().stream().filter(record -> record.id().equals(id)).findFirst();
    }
    OpeningRecord commit(OpeningRecord record);
}
