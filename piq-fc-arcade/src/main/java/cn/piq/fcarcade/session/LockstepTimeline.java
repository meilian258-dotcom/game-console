package cn.piq.fcarcade.session;

import java.util.ArrayList;
import java.util.List;

public final class LockstepTimeline {
    public static final int MAX_RETAINED_FRAMES = 3600;
    private final List<LockstepInputRun> runs = new ArrayList<>();
    private int epoch;
    private long targetFrame;
    private long baseFrame;

    public void reset(int nextEpoch) {
        epoch = nextEpoch;
        targetFrame = 0;
        baseFrame = 0;
        runs.clear();
    }

    public void record(LockstepState.FrameStep step) {
        if (step.epoch() != epoch
                || step.targetFrame() != targetFrame + 1 || !canRecordFrames(1)) {
            throw new IllegalArgumentException("锁步历史必须按当前代次逐模拟帧记录，且不能超过安全容量");
        }
        targetFrame = step.targetFrame();
        if (!runs.isEmpty()) {
            int lastIndex = runs.size() - 1;
            LockstepInputRun last = runs.get(lastIndex);
            if (last.playerOneMask() == step.playerOneMask()
                    && last.playerTwoMask() == step.playerTwoMask() && last.zapperState()==step.zapperState()) {
                runs.set(lastIndex, new LockstepInputRun(
                        last.frames() + 1,
                        last.playerOneMask(),
                        last.playerTwoMask(),last.zapperState()));
                return;
            }
        }
        runs.add(new LockstepInputRun(
                1,
                step.playerOneMask(),
                step.playerTwoMask(),step.zapperState()));
    }

    public int epoch() {
        return epoch;
    }

    public long targetFrame() {
        return targetFrame;
    }

    public List<LockstepInputRun> snapshot() {
        return List.copyOf(runs);
    }

    public long baseFrame() {
        return baseFrame;
    }

    public boolean canRecordFrames(int count) {
        return count >= 0 && count <= MAX_RETAINED_FRAMES
                && targetFrame - baseFrame <= MAX_RETAINED_FRAMES - count;
    }

    /** Discard only input already covered by an accepted, absolute-frame snapshot. */
    public void discardThrough(long frame) {
        List<LockstepInputRun> retained = snapshotAfter(frame);
        runs.clear();
        runs.addAll(retained);
        baseFrame = frame;
    }

    public List<LockstepInputRun> snapshotAfter(long frame) {
        if (frame < baseFrame
                || frame > targetFrame
                || frame % LockstepState.FRAMES_PER_SERVER_TICK != 0) {
            throw new IllegalArgumentException("历史起始帧不在当前锁步时间线上");
        }
        long framesToSkip = frame - baseFrame;
        List<LockstepInputRun> suffix = new ArrayList<>();
        for (LockstepInputRun run : runs) {
            if (framesToSkip >= run.frames()) {
                framesToSkip -= run.frames();
                continue;
            }
            int remainingFrames = run.frames() - (int) framesToSkip;
            suffix.add(new LockstepInputRun(
                    remainingFrames,
                    run.playerOneMask(),
                    run.playerTwoMask(),run.zapperState()));
            framesToSkip = 0;
        }
        return List.copyOf(suffix);
    }
}
