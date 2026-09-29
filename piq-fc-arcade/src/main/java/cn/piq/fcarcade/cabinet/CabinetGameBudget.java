package cn.piq.fcarcade.cabinet;

/** Monotonic byte token bucket; callers charge only after transport admission. */
public final class CabinetGameBudget {
    private final long rate, burst;
    private long last;
    private double tokens;
    public CabinetGameBudget(long rate, long burst, long now) {
        if (rate <= 0 || burst <= 0) throw new IllegalArgumentException("Positive game budget required");
        this.rate = rate; this.burst = burst; this.last = now; this.tokens = burst;
    }
    public boolean permits(int bytes, long now) {
        refill(now);
        return bytes > 0 && bytes <= burst && tokens >= bytes;
    }
    public void charge(int bytes, long now) {
        if (!permits(bytes, now)) throw new IllegalStateException("Game byte budget exhausted");
        tokens -= bytes;
    }
    private void refill(long now) {
        // Backwards clocks cannot manufacture credit. Cap elapsed time before multiplying.
        long elapsed = now - last;
        if (elapsed > 0) {
            tokens = Math.min(burst, tokens + Math.min(elapsed / 1_000_000_000.0, (double) burst / rate) * rate);
            last = now;
        }
    }
}
