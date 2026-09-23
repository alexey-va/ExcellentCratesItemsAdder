package ru.ruscrafting.ecia.integration;

import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.Objects;
import java.util.UUID;

/**
 * Calendar windows for the local, single-authority virtual case entitlements.
 * The stable opening id is also the durable claim: no separate key balance or
 * inventory item is issued. This is not replicated across independent plugin
 * data directories; only one ExcellentCrates-authoritative server may enable
 * these windows for a player/case pair.
 */
public final class PeriodicVirtualOpening {
    public enum Period {
        NONE,
        DAILY,
        WEEKLY;

        public static Period parse(String value, String caseId) {
            if (value == null || value.isBlank()) return NONE;
            try {
                return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException failure) {
                throw new IllegalArgumentException("free-open-period must be none, daily, or weekly for " + caseId);
            }
        }
    }

    public record Window(Period period, Instant start, Instant nextReset) {
        public Window {
            Objects.requireNonNull(period);
            Objects.requireNonNull(start);
            Objects.requireNonNull(nextReset);
            if (period == Period.NONE || !start.isBefore(nextReset)) {
                throw new IllegalArgumentException("A quota window needs a positive daily or weekly period");
            }
        }
    }

    private PeriodicVirtualOpening() {
    }

    public static Window window(Period period, Instant now, ZoneId zone) {
        Objects.requireNonNull(period);
        Objects.requireNonNull(now);
        Objects.requireNonNull(zone);
        if (period == Period.NONE) throw new IllegalArgumentException("No quota window for NONE");
        LocalDate localDate = now.atZone(zone).toLocalDate();
        LocalDate firstDate = period == Period.DAILY
                ? localDate
                : localDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        ZonedDateTime start = firstDate.atStartOfDay(zone);
        ZonedDateTime end = period == Period.DAILY
                ? firstDate.plusDays(1).atStartOfDay(zone)
                : firstDate.plusWeeks(1).atStartOfDay(zone);
        return new Window(period, start.toInstant(), end.toInstant());
    }

    public static UUID openingId(UUID playerId, String caseId, Period period, Window window) {
        Objects.requireNonNull(playerId);
        Objects.requireNonNull(caseId);
        Objects.requireNonNull(period);
        Objects.requireNonNull(window);
        if (period == Period.NONE || window.period() != period) {
            throw new IllegalArgumentException("Quota window does not match its cadence");
        }
        String identity = "ecia:virtual-opening:v1:" + playerId + ':' + caseId + ':' + period + ':'
                + window.start().toEpochMilli();
        return UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
    }

    public static String witness(String caseId, Window window) {
        Objects.requireNonNull(caseId);
        Objects.requireNonNull(window);
        return "virtual:v1:" + window.period().name().toLowerCase(java.util.Locale.ROOT) + ':'
                + window.start().toEpochMilli() + ':' + caseId;
    }
}
