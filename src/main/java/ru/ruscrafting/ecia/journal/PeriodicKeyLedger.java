package ru.ruscrafting.ecia.journal;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import kotlin.Unit;
import ru.arc.persistence.DurableRecordJournal;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;

/** Domain grant/notice receipts. All access is on the owned storage executor, never a Paper callback. */
public final class PeriodicKeyLedger {
    public record Notice(UUID playerId, String day) {
        public Notice { Objects.requireNonNull(playerId); LocalDate.parse(day); }
    }
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
    private final DurableRecordJournal<PeriodicKeyGrant> grants;
    private final DurableRecordJournal<Notice> notices;
    private final Map<UUID, PeriodicKeyGrant> records = new HashMap<>();
    private boolean uncertain;

    public PeriodicKeyLedger(Path root) {
        grants = new DurableRecordJournal<>(root, Path.of("periodic-keys"), 4L * 1024 * 1024,
                this::encode, bytes -> decode(bytes, PeriodicKeyGrant.class), value -> Unit.INSTANCE);
        notices = new DurableRecordJournal<>(root, Path.of("periodic-key-notices"), 1024,
                this::encode, bytes -> decode(bytes, Notice.class), value -> Unit.INSTANCE);
        for (var row : grants.loadAll()) {
            if (!row.getRecordId().equals(row.getValue().id().toString())) throw new IllegalStateException("Grant identity mismatch");
            records.put(row.getValue().id(), row.getValue());
        }
    }

    public synchronized Optional<PeriodicKeyGrant> get(UUID id) { requireHealthy(); return Optional.ofNullable(records.get(id)); }
    public synchronized Set<UUID> deliveredIds() {
        requireHealthy();
        var result = new HashSet<UUID>();
        records.forEach((id, value) -> { if (value.state() == PeriodicKeyGrant.State.DELIVERED) result.add(id); });
        return Set.copyOf(result);
    }

    /** Expired unknown grants have no redeemable value; retire their witnesses before inventory cleanup. */
    public synchronized void expireUnresolved(UUID playerId, long now) {
        requireHealthy();
        var expired = records.values().stream().filter(record -> record.playerId().equals(playerId)
                && record.expires() <= now && record.state() != PeriodicKeyGrant.State.DELIVERED
                && record.state() != PeriodicKeyGrant.State.EXPIRED).toList();
        for (var record : expired) save(record.withState(PeriodicKeyGrant.State.EXPIRED));
    }

    public synchronized PeriodicKeyGrant save(PeriodicKeyGrant value) {
        requireHealthy();
        PeriodicKeyGrant previous = records.get(value.id());
        if (previous != null && (previous.state() == PeriodicKeyGrant.State.DELIVERED
                || previous.state() == PeriodicKeyGrant.State.EXPIRED) && !previous.equals(value)) {
            throw new IllegalStateException("Terminal calendar grant cannot be reissued");
        }
        try {
            var readback = grants.commit(value.id().toString(), value);
            if (!value.equals(readback)) throw new IllegalStateException("Grant readback mismatch");
            records.put(value.id(), readback);
            return readback;
        } catch (RuntimeException failure) { uncertain = true; throw failure; }
    }

    /** Claim before sending: a crash may omit one notice, but never repeats it that day. */
    public synchronized boolean claimNotice(UUID playerId, LocalDate day) {
        requireHealthy();
        try {
            var old = notices.loadOrNull(playerId.toString());
            if (old != null && !old.playerId().equals(playerId)) throw new IllegalStateException("Notice owner mismatch");
            if (old != null && !LocalDate.parse(old.day()).isBefore(day)) return false;
            var next = new Notice(playerId, day.toString());
            if (!next.equals(notices.commit(playerId.toString(), next))) throw new IllegalStateException("Notice readback mismatch");
            return true;
        } catch (RuntimeException failure) { uncertain = true; throw failure; }
    }

    private void requireHealthy() {
        if (uncertain) throw new IllegalStateException("Periodic key storage requires restart and reconciliation");
    }
    private byte[] encode(Object value) {
        try { return mapper.writeValueAsBytes(value); } catch (IOException e) { throw new UncheckedIOException(e); }
    }
    private <T> T decode(byte[] bytes, Class<T> type) {
        try { return mapper.readValue(bytes, type); } catch (IOException e) { throw new UncheckedIOException(e); }
    }
}
