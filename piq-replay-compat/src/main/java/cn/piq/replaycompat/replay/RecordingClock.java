package cn.piq.replaycompat.replay;

/** Implemented only on the pinned Replay recorder by the companion Mixin. */
public interface RecordingClock {
    long piqReplay$recordingTime();
}
