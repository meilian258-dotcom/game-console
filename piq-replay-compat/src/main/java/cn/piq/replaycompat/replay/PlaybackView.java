package cn.piq.replaycompat.replay;

import cn.piq.replaycompat.api.ScreenFrame;
import java.util.List;
import java.util.Set;

/** Atomic render selection. Frames are exact for time; active sources include pending decodes. */
public record PlaybackView(Status status, long time, List<ScreenFrame> frames, Set<String> activeSources) {
    public enum Status { PENDING, PARTIAL, READY_EMPTY, READY_FRAMES, FAILED, CLOSED }
    public PlaybackView { frames = List.copyOf(frames); activeSources = Set.copyOf(activeSources); }
    public boolean ready() { return status == Status.READY_EMPTY || status == Status.READY_FRAMES; }
    public static PlaybackView closed(long time) { return new PlaybackView(Status.CLOSED, time, List.of(), Set.of()); }
}
