package cn.piq.fcarcade.client.performance;

import java.util.Arrays;
import java.util.function.LongSupplier;

/** Bounded, opt-in wall-time sampling. Never calls a core or consumes a mailbox. */
public final class FramePerformance {
    private static final long BIN = 100_000_000L;
    private final LongSupplier clock;
    private final long[] tags = new long[16], counts = new long[16], totals = new long[16], maxima = new long[16];
    private volatile boolean enabled;
    private long enabledAt;
    public record Sample(boolean enabled, boolean ready, double fps, double averageMs, double maximumMs) {}
    public FramePerformance() { this(System::nanoTime); }
    public FramePerformance(LongSupplier clock) { this.clock = clock; clear(); }
    public synchronized void enabled(boolean value) {
        if (enabled == value) return;
        enabled = value; clear(); enabledAt = clock.getAsLong();
    }
    public synchronized void reset() { clear(); enabledAt = clock.getAsLong(); }
    private void clear() {
        Arrays.fill(tags, Long.MIN_VALUE); Arrays.fill(counts, 0);
        Arrays.fill(totals, 0); Arrays.fill(maxima, 0);
    }
    /** Disabled path does not read the clock. Long.MIN_VALUE is the disabled token. */
    public long begin() { return enabled ? clock.getAsLong() : Long.MIN_VALUE; }
    public void completed(long started) {
        if (!enabled || started == Long.MIN_VALUE) return;
        synchronized (this) {
            long now = clock.getAsLong();
            if (!enabled || started < enabledAt || now < started) return;
            long bin = Math.floorDiv(now, BIN); int i = (int)Math.floorMod(bin, 16);
            if (tags[i] != bin) { tags[i] = bin; counts[i] = totals[i] = maxima[i] = 0; }
            long cost = now - started;
            counts[i]++; totals[i] += cost; maxima[i] = Math.max(maxima[i], cost);
        }
    }
    /** Ten completed 100ms buckets; idle time contributes zero, not a stale FPS. */
    public synchronized Sample sample() {
        if (!enabled) return new Sample(false, false, 0, 0, 0);
        long now = clock.getAsLong(), end = Math.floorDiv(now, BIN);
        if (now - enabledAt < 11 * BIN) return new Sample(true, false, 0, 0, 0);
        long count = 0, total = 0, max = 0;
        for (int i = 0; i < tags.length; i++) if (tags[i] >= end - 10 && tags[i] < end) {
            count += counts[i]; total += totals[i]; max = Math.max(max, maxima[i]);
        }
        return new Sample(true, true, count, count == 0 ? 0 : total / (double)count / 1_000_000, max / 1_000_000D);
    }
}
