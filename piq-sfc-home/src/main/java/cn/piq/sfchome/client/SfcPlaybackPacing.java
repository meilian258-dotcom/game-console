package cn.piq.sfchome.client;

/** Worker-local policy: catch up by executing every frame, never by skipping input. */
final class SfcPlaybackPacing {
    static final int ENTER_BACKLOG = 9, TARGET_BACKLOG = 3, MAX_BURST_FRAMES = 12;
    static final long MAX_BURST_NANOS = 8_000_000L, YIELD_NANOS = 1_000_000L;
    static final long MAX_CATCH_UP_NANOS = 10_000_000_000L;
    record Step(boolean catchingUp, boolean entered, boolean recovered, boolean expired, long yieldNanos) {}
    private boolean catchingUp;
    private long began, sliceBegan;
    private int sliceFrames;

    Step frame(int outstandingFrames, long now, boolean repairing) {
        if (outstandingFrames < 1) throw new IllegalArgumentException("Missing current frame");
        if (repairing) { reset(); return new Step(false, false, false, false, 0); }
        if (catchingUp && outstandingFrames <= TARGET_BACKLOG) {
            reset(); return new Step(false, false, true, false, 0);
        }
        boolean entered = !catchingUp && outstandingFrames > ENTER_BACKLOG;
        if (entered) { catchingUp = true; began = sliceBegan = now; sliceFrames = 0; }
        if (!catchingUp) return new Step(false, false, false, false, 0);
        if (now - began >= MAX_CATCH_UP_NANOS) return new Step(true, entered, false, true, 0);
        long yield = 0;
        if (sliceFrames >= MAX_BURST_FRAMES || sliceFrames > 0 && now - sliceBegan >= MAX_BURST_NANOS) {
            yield = YIELD_NANOS; sliceFrames = 0; sliceBegan = now;
        }
        sliceFrames++;
        return new Step(true, entered, false, false, yield);
    }
    void reset() { catchingUp = false; began = sliceBegan = 0; sliceFrames = 0; }
}
