package cn.piq.replaycompat.replay;

/** Mirrors Replay 0.3's recording clock without mutating its pause bookkeeping. */
public final class ReplayClock {
    private ReplayClock() {}
    public static long recordingTime(long now, long start, long paused, long lastPacket, boolean serverPaused) {
        if (serverPaused) return Math.max(0, lastPacket);
        return Math.max(0, Math.min(Integer.MAX_VALUE, now - start - paused));
    }
}
