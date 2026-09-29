package cn.piq.fcarcade.client.cabinet;

/** Bounded, focus-safe reserved diagnostic combo. No claim to detect a game's menu. */
final class PgmStartSequence {
    static final long HOLD = 4_000_000_000L, ARM_TIMEOUT = 10_000_000_000L;
    private boolean armed, started;
    private long expires, release;
    boolean arm(long now) {
        if (armed) return false;
        armed = true; started = false; expires = now + ARM_TIMEOUT; return true;
    }
    /** -1: no override; diagnostic combo: active; 0: one explicit release. */
    int poll(boolean active, long now) {
        if (!armed) return -1;
        if (!active) { if (started || now - expires >= 0) cancel(); return -1; }
        if (!started) {
            if (now - expires >= 0) { cancel(); return -1; }
            started = true; release = now + HOLD;
        }
        if (now - release >= 0) { cancel(); return 0; }
        return cn.piq.fcarcade.cabinet.PgmServicePolicy.DIAGNOSTIC_MASK;
    }
    void cancel() { armed = started = false; }
}
