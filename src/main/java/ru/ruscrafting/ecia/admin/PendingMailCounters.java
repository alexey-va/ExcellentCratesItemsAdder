package ru.ruscrafting.ecia.admin;

/**
 * Caller supplied durable opening counters for one crate. The admin layer does
 * not inspect the opening journal itself: its owner may keep that journal
 * behind a different storage or lifecycle boundary.
 */
public record PendingMailCounters(int activeOpenings, int choosing, int mail,
        int delivering, int review) {
    public PendingMailCounters {
        if (activeOpenings < 0 || choosing < 0 || mail < 0 || delivering < 0 || review < 0) {
            throw new IllegalArgumentException("Pending mail counters must not be negative");
        }
    }

    public int total() {
        return Math.addExact(Math.addExact(Math.addExact(Math.addExact(activeOpenings, choosing), mail), delivering), review);
    }

    public static PendingMailCounters empty() {
        return new PendingMailCounters(0, 0, 0, 0, 0);
    }
}
