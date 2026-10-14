package cn.piq.replaycompat.replay;

public final class ReplayClockTest {
    public static void main(String[] args) {
        check(ReplayClock.recordingTime(1300, 1000, 50, 200, false) == 250, "record clock offset");
        check(ReplayClock.recordingTime(9000, 1000, 50, 200, true) == 200, "paused packet clock freeze");
        check(ReplayClock.recordingTime(900, 1000, 0, 0, false) == 0, "clock rollback clamped");
        check(ReplayClock.recordingTime(Long.MAX_VALUE, 0, 0, 0, false) == Integer.MAX_VALUE, "wire time clamp");
        System.out.println("ReplayClockTest: 4 checks passed");
    }
    private static void check(boolean condition, String name) { if (!condition) throw new AssertionError(name); }
}
