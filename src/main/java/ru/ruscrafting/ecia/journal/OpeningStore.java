package ru.ruscrafting.ecia.journal;

import java.util.List;

/** Commit must return only after durable readback; failure never authorizes a side effect. */
public interface OpeningStore {
    List<OpeningRecord> load();
    OpeningRecord commit(OpeningRecord record);
}
