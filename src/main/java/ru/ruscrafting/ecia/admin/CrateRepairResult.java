package ru.ruscrafting.ecia.admin;

import java.util.Objects;
import java.util.Set;

/** Typed outcome of a fail-closed repair attempt. */
public record CrateRepairResult(Status status, CrateInspection before,
        CrateInspection after, int repairedPositions, Set<CrateInspection.Reason> reasons,
        String detail) {
    public CrateRepairResult {
        Objects.requireNonNull(status);
        Objects.requireNonNull(before);
        Objects.requireNonNull(reasons);
        detail = detail == null ? "" : detail;
        if (repairedPositions < 0) throw new IllegalArgumentException("Repaired position count must not be negative");
        if (status == Status.REPAIRED && after == null) throw new IllegalArgumentException("Repaired result needs readback");
    }

    public enum Status { REPAIRED, NOOP, BLOCKED, AMBIGUOUS, FAILED }
}
