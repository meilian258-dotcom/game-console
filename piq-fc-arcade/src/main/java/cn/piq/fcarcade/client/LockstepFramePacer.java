package cn.piq.fcarcade.client;

final class LockstepFramePacer {
    private static final int MAX_FRAMES_PER_UPDATE = 12;
    private static final int TARGET_BUFFER_FRAMES = 3;

    private final long frameNanos;
    private long nextFrameNanos = Long.MIN_VALUE;

    LockstepFramePacer(long frameNanos) {
        if (frameNanos <= 0) {
            throw new IllegalArgumentException("Frame duration must be positive");
        }
        this.frameNanos = frameNanos;
    }

    int framesDue(long nowNanos, long availableFrames) {
        if (availableFrames <= 0) return 0;
        if (nextFrameNanos == Long.MIN_VALUE) {
            nextFrameNanos = nowNanos;
        }

        int limit = (int) Math.min(availableFrames, MAX_FRAMES_PER_UPDATE);
        int frames = 0;
        while (frames < limit && nowNanos >= nextFrameNanos) {
            frames++;
            nextFrameNanos += frameNanos;
        }

        int backlogFrames = (int) Math.min(
                limit,
                Math.max(0, availableFrames - TARGET_BUFFER_FRAMES));
        if (backlogFrames > frames) {
            frames = backlogFrames;
            nextFrameNanos = nowNanos + frameNanos;
        } else if (frames == MAX_FRAMES_PER_UPDATE
                && nowNanos >= nextFrameNanos) {
            nextFrameNanos = nowNanos + frameNanos;
        }
        return frames;
    }

    void reset() {
        nextFrameNanos = Long.MIN_VALUE;
    }
}
