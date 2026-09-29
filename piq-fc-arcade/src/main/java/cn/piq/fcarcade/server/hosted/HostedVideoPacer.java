package cn.piq.fcarcade.server.hosted;

/** Video delivery cap only. Never advances an emulator or schedules audio. */
public final class HostedVideoPacer {
    private long next;
    private int rate;
    private boolean started;

    public static boolean supported(int fps) { return fps == 20 || fps == 30 || fps == 60; }

    public boolean take(long now, int fps) {
        if (!supported(fps)) throw new IllegalArgumentException("Hosted video cap");
        long interval = 1_000_000_000L / fps;
        if (!started) { started = true; rate = fps; next = now; }
        else if (rate != fps) { rate = fps; next = now + interval; }
        if (now < next) return false;
        // Keep phase across a 5 ms encoder poll, but never replay missed frames in a burst.
        long elapsed = now - next;
        next = elapsed >= 1_000_000_000L ? now + interval
                : next + (elapsed / interval + 1) * interval;
        return true;
    }
}
