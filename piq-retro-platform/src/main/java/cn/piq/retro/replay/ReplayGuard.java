// PIQ-authored code: additional permission in piq-fc-arcade/MD-ARCADE-LINKING-EXCEPTION.txt.
// PIQ-authored code: additional permission in piq-fc-arcade/SNES9X-LINKING-EXCEPTION.txt.
package cn.piq.retro.replay;

import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Optional replay lifecycle barrier. No game, device, file or network dependencies. */
public final class ReplayGuard {
    public static final int API_VERSION = 1;
    private static final int MAX_ENTRY_CHECKS = 16;
    private static final LinkedHashMap<String, Runnable> ENTRY_CHECKS = new LinkedHashMap<>();
    private static volatile BooleanSupplier supplier;
    private static boolean explicit;
    private static boolean observed;
    private static long generation;

    private ReplayGuard() { }

    /** Bootstrap only; replacement by another integration is rejected. */
    public static synchronized void install(BooleanSupplier playing) {
        Objects.requireNonNull(playing, "playing");
        if (supplier != null && supplier != playing) throw new IllegalStateException("Replay guard already installed");
        supplier = playing;
    }

    /** Call before replay construction dispatches any recorded packets. */
    public static synchronized void enterReplay() {
        if (!explicit) { explicit = true; generation++; }
    }

    /** Call only after replay teardown (also on failed construction). Never revives old work. */
    public static synchronized void exitReplay() { explicit = false; }

    /** Supplier failure denies live work. Suppliers must be prompt and side-effect free. */
    public static boolean playing() {
        boolean supplied = false;
        BooleanSupplier current = supplier;
        try { supplied = current != null && current.getAsBoolean(); }
        catch (Throwable failure) { supplied = true; }
        synchronized (ReplayGuard.class) {
            if (supplied && !observed && !explicit) generation++;
            observed = supplied;
            return explicit || supplied;
        }
    }

    /** Capture at admission, including before scheduling asynchronous work. */
    public static long epoch() { playing(); synchronized (ReplayGuard.class) { return generation; } }

    /** A whole replay interval invalidates work even if it never polled while replay was open. */
    public static boolean valid(long epoch) {
        if (playing()) return false;
        synchronized (ReplayGuard.class) { return generation == epoch && !explicit; }
    }

    /** At most 16 host-owned, nonblocking, read-only admission checks. No cleanup or I/O here. */
    public static synchronized void registerEntryCheck(String owner, Runnable check) {
        Objects.requireNonNull(owner, "owner"); Objects.requireNonNull(check, "check");
        if (owner.isBlank() || ENTRY_CHECKS.containsKey(owner)) throw new IllegalArgumentException("Duplicate/empty replay owner");
        if (ENTRY_CHECKS.size() >= MAX_ENTRY_CHECKS) throw new IllegalStateException("Replay entry check capacity exceeded");
        ENTRY_CHECKS.put(owner, check);
    }

    /** Reject an unsupported active owner before starting playback; failure keeps protection active. */
    public static void checkEntry() {
        Runnable[] checks;
        synchronized (ReplayGuard.class) { checks = ENTRY_CHECKS.values().toArray(Runnable[]::new); }
        for (Runnable check : checks) check.run();
    }

    /** Owner-local retirement; cleanup must release without saving or sending gameplay packets. */
    public static final class Retirement {
        private long retiredEpoch = Long.MIN_VALUE;
        public synchronized boolean block(Runnable cleanup) {
            if (!playing()) return false;
            long current = epoch();
            if (retiredEpoch != current) {
                Objects.requireNonNull(cleanup, "cleanup").run();
                retiredEpoch = current;
            }
            return true;
        }
    }
}
