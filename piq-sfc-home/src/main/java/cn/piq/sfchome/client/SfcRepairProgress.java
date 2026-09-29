package cn.piq.sfchome.client;

/** Completion is about the live input backlog, not just an old transfer target. */
final class SfcRepairProgress {
    static final int MAX_QUEUED_FRAMES = 6;
    private SfcRepairProgress() {}
    static boolean ready(int frame, int goal, int queuedFrames) {
        return goal >= 0 && frame >= goal && queuedFrames >= 0 && queuedFrames <= MAX_QUEUED_FRAMES;
    }
}
