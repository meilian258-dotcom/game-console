package cn.piq.replaycompat.capture;

/** A replay guard must retire an existing runtime before suppressing its normal cleanup tick. */
public final class ReplayRetirement {
    private boolean retired;

    public boolean block(boolean replay, Runnable cleanup) {
        if (!replay) {
            retired = false;
            return false;
        }
        if (!retired) {
            cleanup.run();
            retired = true;
        }
        return true;
    }
}
