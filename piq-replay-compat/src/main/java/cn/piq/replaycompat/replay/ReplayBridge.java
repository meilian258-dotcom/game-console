package cn.piq.replaycompat.replay;

import cn.piq.replaycompat.api.ScreenFrame;
import cn.piq.replaycompat.audio.AudioBridge;
import com.replaymod.replay.ReplayHandler;
import com.replaymod.replay.events.*;
import com.replaymod.replaystudio.replay.ReplayFile;
import de.johni0702.minecraft.gui.utils.EventRegistration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Pinned ReForgedPlay 0.3 integration; callers do not depend on Replay internals. */
public final class ReplayBridge {
    private static final Logger LOGGER = LogManager.getLogger("PIQ-ReplayScreen");
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean();
    private static final Map<Object, RecordingSession> RECORDERS = new IdentityHashMap<>();
    private static final Set<Object> FINISHING = Collections.newSetFromMap(new IdentityHashMap<>());
    private record ActiveRecorder(Object owner, RecordingClock clock, RecordingSession session) {}
    private static volatile ActiveRecorder active;
    private static volatile PlaybackSession playback;
    private static volatile ReplayHandler handler;
    private static volatile String status = "屏幕轨待机";
    private static final java.util.concurrent.atomic.AtomicInteger EXPORTS = new java.util.concurrent.atomic.AtomicInteger();
    private ReplayBridge() {}

    public static void init() { initialize(); }

    public static void initialize() {
        if (!INITIALIZED.compareAndSet(false, true)) return;
        AudioBridge.initialize();
        EventRegistration.register(ReplayOpenedCallback.EVENT, opened -> {
            handler = opened; playback = new PlaybackSession(opened.getReplayFile());
            AudioBridge.open(opened.getReplayFile());
            status = "正在读取录像屏幕轨"; playback.at(0);
            LOGGER.info("Screen playback opened; track decode requested");
        });
        EventRegistration.register(ReplayClosingCallback.EVENT, closing -> {
            if (handler == closing) { AudioBridge.close(); var old = playback; playback = null; if (old != null) old.close(); }
        });
        EventRegistration.register(ReplayClosedCallback.EVENT, closed -> {
            if (handler == closed) { handler = null; cn.piq.retro.replay.ReplayGuard.exitReplay(); status = "屏幕轨待机"; }
        });
        LOGGER.info("Screen replay lifecycle callbacks registered");
    }
    public static synchronized void beginRecording(Object owner, RecordingClock clock, ReplayFile file) {
        initialize();
        if (RECORDERS.containsKey(owner)) return;
        if (RECORDERS.size() >= 2) { warn("已有屏幕录像正在收尾，本次不启动额外屏幕录制。", null); return; }
        var session = new RecordingSession(file); RECORDERS.put(owner, session); active = new ActiveRecorder(owner, clock, session);
        AudioBridge.start(owner, file, clock::piqReplay$recordingTime);
        status = "屏幕轨录制中（有界队列，最多72000帧/9000块/2GiB）";
        LOGGER.info("Screen recording started; pending recordings={}", RECORDERS.size());
    }
    public static synchronized void stopRecording(Object owner) {
        AudioBridge.stop(owner);
        var session = RECORDERS.get(owner); if (session != null) session.stopAccepting();
        if (active != null && active.owner == owner) active = null;
    }
    public static void finishRecording(Object owner) {
        RecordingSession session;
        synchronized (ReplayBridge.class) {
            stopRecording(owner); session = RECORDERS.get(owner);
            if (session == null || !FINISHING.add(owner)) return;
        }
        try { try { session.finish(); } finally { AudioBridge.finish(owner); } }
        finally { synchronized (ReplayBridge.class) { RECORDERS.remove(owner); FINISHING.remove(owner); } }
    }
    public static boolean recording() { var value = active; return value != null && value.session.accepting() && !playing(); }
    public static boolean playing() { return handler != null; }
    public static long recordingTime() { var value = active; return value == null ? 0 : value.clock.piqReplay$recordingTime(); }
    public static void submit(ScreenFrame frame) { var value = active; if (value != null && !playing()) value.session.submit(frame); }
    public static void close(String source, long time) { var value = active; if (value != null && !playing()) value.session.close(source, time); }
    public static List<ScreenFrame> at(long time) { var value = playback; return value == null ? List.of() : value.at(Math.max(0, time)); }
    /** One immutable read for rendering: an unresolved frame is not an empty track. */
    public static PlaybackView viewAt(long time) { var value = playback; return value == null ? PlaybackView.closed(time) : value.viewAt(Math.max(0, time)); }
    public static List<ScreenFrame> frames() { return at(playbackTime()); }
    /** Non-blocking exact-selection readiness; false means retain textures but do not draw stale ones. */
    public static boolean readyAt(long time) { var value = playback; return value != null && value.readyAt(Math.max(0, time)); }
    public static long playbackTime() { var current = handler; return current == null ? 0 : Math.max(0, current.getReplaySender().currentTimeStamp()); }
    public static long readyTime() { var current = playback; return current == null ? -1 : current.readyTime(); }
    /** Export-only preparation barrier: actual disk/decoding work remains on the decoder worker. */
    public static boolean prepareAt(long time) { var value = playback; return value == null || value.prepareAt(time, 10_000); }
    public static String status() { return status; }
    /** Audio work stays on its worker; frame/tick callers only publish playback controls. */
    public static void tickAudio() {
        var current = handler;
        if (current == null) return;
        var sender = current.getReplaySender();
        boolean seeking = sender instanceof com.replaymod.replay.FullReplaySender full && full.isHurrying();
        AudioBridge.tick(Math.max(0, sender.currentTimeStamp()), sender.paused() || seeking, sender.getReplaySpeed(), EXPORTS.get() > 0);
    }
    public static void timelineJump() { AudioBridge.seek(); }
    public static void beginExport() { EXPORTS.incrementAndGet(); AudioBridge.seek(); tickAudio(); }
    public static void endExport() { EXPORTS.updateAndGet(value -> Math.max(0, value - 1)); AudioBridge.seek(); }
    /** Also covers Replay's IOException recovery path, which does not emit ReplayClosedCallback. */
    public static void forcefullyStopPlayback() {
        EXPORTS.set(0);
        PlaybackSession old = playback;
        playback = null; handler = null;
        cn.piq.retro.replay.ReplayGuard.exitReplay();
        try {
            try { AudioBridge.close(); }
            finally { if (old != null) old.close(); }
            status = "屏幕轨待机";
        } catch (RuntimeException | LinkageError failure) {
            // Clearing the playback guard is unconditional, even if optional track cleanup failed.
            warn("屏幕轨退出清理异常；已解除回放状态，不阻止重新进入正常游戏。", failure);
        }
    }
    public static void warn(String message, Throwable failure) {
        status = message;
        if (failure == null) LOGGER.warn(message); else LOGGER.warn(message, failure);
    }
    static void recordingAccepted(RecordingSession session) {
        var current = active;
        if (current != null && current.session == session) status = "已采集屏幕画面，正在录制";
        LOGGER.info("First screen image accepted for recording; archive write pending");
    }
    static void recordingFinished(TrackFiles.Summary summary, long submitted, long dropped, String failureCode) {
        if (active == null) status = summary.images() == 0 ? TrackFiles.EMPTY_WARNING
                : "屏幕轨已提交：" + summary.images() + "帧 / " + summary.sources() + "个来源 / " + summary.chunks() + "块；等待 Replay 保存录像"
                + (!"none".equals(failureCode) || dropped > 0 ? "（画面可能不完整，详见日志）" : "");
        LOGGER.info("Screen track finalized in ReplayFile (outer replay save pending): frames={}, images={}, sources={}, chunks={}, bytes={}, submitted={}, dropped={}, result={}",
                summary.frames(), summary.images(), summary.sources(), summary.chunks(), summary.bytes(), submitted, dropped,
                !"none".equals(failureCode) ? failureCode : summary.images() == 0 ? "empty_capture" : dropped > 0 ? "partial_capture" : "complete");
    }
}
