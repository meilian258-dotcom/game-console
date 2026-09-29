package cn.piq.fcarcade.home;

/** Client presentation in fixed 50 ms units, independent of world/TPS clocks. Never controls the emulator. */
public final class TelevisionPowerTransition {
    public static double presentationTime() { return System.nanoTime() / 50_000_000.0; }
    public static final double ON_TICKS = 14;
    public static final double OFF_TICKS = 7;
    private boolean loaded;
    private boolean target;
    private double from;
    private double started;
    private double duration;

    /** Initial chunks, reconnects and reloads snap to the persisted state, never replay history. */
    public void loaded(boolean powered) {
        loaded = true;
        snap(powered);
    }

    public void unloaded() { loaded = false; }

    public void observe(boolean powered, boolean enabled, double now) {
        if (!loaded || !enabled || !Double.isFinite(now)) { snap(powered); return; }
        if (target == powered) return; // Volume, signal and repeated packets are not power events.
        from = amount(now);
        target = powered;
        started = now;
        duration = powered ? ON_TICKS : OFF_TICKS;
    }

    public double amount(double now) {
        if (duration <= 0 || !Double.isFinite(now)) return target ? 1 : 0;
        double t = Math.max(0, Math.min(1, (now - started) / duration));
        double eased = t * t * (3 - 2 * t);
        return from + ((target ? 1 : 0) - from) * eased;
    }

    public static double verticalScale(boolean crt, double amount) {
        return crt ? Math.max(.012, bounded(amount)) : 1;
    }

    public static int brightness(double amount) { return (int) Math.round(255 * Math.sqrt(bounded(amount))); }

    private static double bounded(double amount) {
        return Double.isFinite(amount) ? Math.max(0, Math.min(1, amount)) : 0;
    }

    private void snap(boolean powered) { target = powered; from = powered ? 1 : 0; duration = 0; }
}
