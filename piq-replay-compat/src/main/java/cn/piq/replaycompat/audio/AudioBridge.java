package cn.piq.replaycompat.audio;

import cn.piq.replaycompat.api.*;
import cn.piq.replaycompat.replay.TrackCutPlan;
import com.replaymod.replaystudio.replay.ReplayFile;
import java.util.*;
import java.util.function.LongSupplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** ReForgedPlay lifecycle adapter. PCM never invokes a core, a network sender or a save-game path. */
public final class AudioBridge {
    private static final Logger LOG = LogManager.getLogger("PIQ-ReplayAudio");
    private static final Map<Object, AudioRecording> RECORDERS = new IdentityHashMap<>();
    private static final Set<Object> FINISHING = Collections.newSetFromMap(new IdentityHashMap<>());
    private static volatile AudioRecording active;
    private static volatile AudioPlayback playback;
    private static volatile String status = "音轨待机", mode = "";
    private static final AudioReplay.Service SERVICE = new AudioReplay.Service() {
        public Object captureToken() { AudioRecording recorder = active; return recorder != null && recorder.accepting() && playback == null ? recorder : null; }
        public Object captureToken(Object source) {
            AudioRecording recorder = active;
            return recorder == null || playback != null ? null : recorder.token(source);
        }
        public boolean playing() { return playback != null; }
        public void capture(Object token, Object source, byte[] pcm, int offset, int length, int rate, int channels) {
            AudioRecording recorder = active;
            if (recorder != null && playback == null) recorder.capture(token, source, pcm, offset, length, rate, channels);
        }
        public void reset(Object source) { AudioRecording recorder = active; if (recorder != null) recorder.reset(source, false); }
        public void close(Object source) { AudioRecording recorder = active; if (recorder != null) recorder.reset(source, true); }
    };
    private AudioBridge() {}
    public static void initialize() { AudioReplay.install(SERVICE); }
    public static synchronized void start(Object owner, ReplayFile file, LongSupplier recordingMillis) {
        initialize();
        if (owner == null || file == null || RECORDERS.containsKey(owner) || playback != null) return;
        if (RECORDERS.size() >= 2) { warning("上一音轨仍在收尾，本次不启动额外音轨。", null); return; }
        try {
            AudioRecording recorder = new AudioRecording(file, recordingMillis);
            RECORDERS.put(owner, recorder); active = recorder; status = "正在录制设备最终PCM（录制视点音量）";
            LOG.info("Audio recording started; bounded PCM capture, save pending");
        } catch (RuntimeException | LinkageError failure) { warning("无法启动可选音轨，主录像仍继续。", failure); }
    }
    public static synchronized void stop(Object owner) {
        AudioRecording recorder = RECORDERS.get(owner);
        if (recorder != null) { recorder.stopAccepting(); if (active == recorder) active = null; }
    }
    /** Only on Replay's archive-save worker, before the outer ReplayFile save/close. */
    public static void finish(Object owner) {
        AudioRecording recorder;
        synchronized (AudioBridge.class) {
            stop(owner); recorder = RECORDERS.get(owner);
            if (recorder == null || !FINISHING.add(owner)) return;
        }
        try { recorder.finish(); }
        finally { synchronized (AudioBridge.class) { RECORDERS.remove(owner); FINISHING.remove(owner); } }
    }
    public static synchronized void open(ReplayFile file) {
        close(); initialize();
        if (file == null) return;
        AudioRecording recording = active; active = null;
        if (recording != null) recording.stopAccepting();
        try { playback = new AudioPlayback(file, new JavaSoundSink()); status = "正在读取设备音轨"; mode = ""; }
        catch (RuntimeException | LinkageError failure) { warning("无法打开可选音轨，画面仍继续。", failure); }
    }
    public static void tick(long replayMillis, boolean paused, double speed, boolean exportMute) {
        AudioPlayback player = playback;
        if (player == null) return;
        mode = exportMute ? "视频导出期间设备音轨静音；本版不导出声音"
                : paused ? "设备音轨已暂停" : !Double.isFinite(speed) || Math.abs(speed - 1.0) >= .000001
                ? "设备音轨仅支持正向1×；当前速度静音" : "";
        player.tick(replayMillis, paused, speed, exportMute);
    }
    public static void seek() { AudioPlayback player = playback; if (player != null) player.seek(); }
    /** Invalidates queues immediately; device flush/close and any reads finish on their own daemon. */
    public static synchronized void close() {
        AudioPlayback old = playback; playback = null;
        if (old != null) old.close();
        mode = ""; status = "音轨待机";
    }
    public static String status() { return mode.isEmpty() ? status : mode; }
    static void warning(String message, Throwable failure) {
        status = message;
        if (failure == null) LOG.warn(message); else LOG.warn(message, failure);
    }
    static void recorded(AudioTrackFiles.Summary summary, String result) {
        if (active == null && playback == null) status = summary.frames() == 0 ? "此次没有采集设备音频；请检查支持的输出路线"
                : "设备音轨已提交ReplayFile，等待主录像保存";
        LOG.info("Audio track finalized in ReplayFile (outer save pending): chunks={}, frames={}, sources={}, bytes={}, result={}",
                summary.chunks(), summary.frames(), summary.sources(), summary.bytes(), result);
    }
    static synchronized void playbackMissing(AudioPlayback owner) { if (playback == owner) { status = "此录像没有可回放的设备音轨；不能凭空恢复声音"; LOG.info(status); } }
    static synchronized void playbackReady(AudioPlayback owner) { if (playback == owner) { status = "设备音轨就绪：正向1×，录制视点音量"; LOG.info(status); } }
    static synchronized void playbackWarning(AudioPlayback owner, String message, Throwable failure) { if (playback == owner) warning(message, failure); }
    static synchronized void deviceWarning(PcmSink owner, String message, Throwable failure) { if (playback != null && playback.owns(owner)) warning(message, failure); }
    /** Called on the marker processing/save worker. Whole native samples are sliced, not video frames. */
    public static boolean copySegments(ReplayFile source, ReplayFile destination, TrackCutPlan.Output output) {
        if (source == destination) return true;
        try {
            AudioTrackFiles.Index index = AudioTrackFiles.readIndex(source);
            if (index == null) return true;
            var writer = new AudioTrackFiles.Writer(destination);
            List<AudioFrame> batch = new ArrayList<>(); int raw = 0; long copied = 0;
            // A tiny LRU bounds memory even when multiple retained segments share one chunk.
            Map<Integer, List<AudioFrame>> cache = new LinkedHashMap<>(4, .75f, true);
            long durationMicros = Math.min(AudioFrame.MAX_TIME_MICROS, (Math.max(0L, destination.getMetaData().getDuration()) + 1) * 1000);
            for (var segment : output.segments()) {
                long from = segment.sourceFrom() * 1000, to = segment.sourceTo() * 1000, target = segment.outputFrom() * 1000;
                to = Math.min(to, from + Math.max(0, durationMicros - target));
                if (from >= to) continue;
                // A retained segment can be hours long; do not apply the playback overlap budget to it.
                for (var ref : index.refs()) {
                    if (ref.fromMicros() >= to || ref.toMicros() <= from) continue;
                    List<AudioFrame> frames = cache.get(ref.number());
                    if (frames == null) {
                        frames = AudioTrackFiles.readChunk(source, ref); cache.put(ref.number(), frames);
                        while (cache.size() > 4) cache.remove(cache.keySet().iterator().next());
                    }
                    for (AudioFrame frame : frames) {
                        AudioFrame clipped = frame.slice(from, to, target);
                        if (clipped == null) continue;
                        if (!batch.isEmpty() && (batch.size() >= AudioTrackFiles.MAX_CHUNK_FRAMES || raw + clipped.byteCount() > AudioTrackFiles.MAX_CHUNK_PCM)) {
                            writer.append(batch); batch.clear(); raw = 0;
                        }
                        batch.add(clipped); raw += clipped.byteCount(); copied++;
                    }
                }
            }
            if (!batch.isEmpty()) writer.append(batch);
            writer.finish(copied, 0, "cut_mapped"); return true;
        } catch (Exception | LinkageError failure) {
            warning("剪切/分段音轨映射失败；请保留原录像，不把该输出当完整有声录像。", failure);
            return false;
        }
    }
}
