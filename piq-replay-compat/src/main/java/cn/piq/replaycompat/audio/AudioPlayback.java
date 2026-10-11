package cn.piq.replaycompat.audio;

import cn.piq.replaycompat.api.AudioFrame;
import com.replaymod.replaystudio.replay.ReplayFile;
import java.util.*;
import java.util.concurrent.*;

/** Asynchronous bounded decoder and a separately-owned non-blocking device sink. */
final class AudioPlayback {
    private final ReplayFile file; private final PcmSink sink;
    private final ExecutorService worker;
    private final Object lock = new Object();
    private final LinkedHashMap<Integer, List<AudioFrame>> cache = new LinkedHashMap<>(16, .75f, true);
    private volatile boolean closed, failed, missing, outputFailed;
    private AudioTrackFiles.Index index; private boolean loaded, active, scheduled;
    private long generation, currentSample, submittedUntil = -1, previousMillis = -1, previousNanos;
    private boolean recovering, recoveryNoticePending, waitingForDevice;
    private long backpressure, staleOffers, deviceWaits, recoveries, resyncs, lastRecoveryNotice;
    record Diagnostics(boolean trackFailed, boolean outputFailed, long backpressure, long staleOffers,
                       long deviceWaits, long recoveries, long resyncs) {}
    AudioPlayback(ReplayFile file, PcmSink sink) {
        this.file = Objects.requireNonNull(file); this.sink = Objects.requireNonNull(sink);
        worker = Executors.newSingleThreadExecutor(task -> { Thread thread = new Thread(task, "PIQ-Replay-Audio-Decode"); thread.setDaemon(true); return thread; });
        sink.reset(0);
    }
    void tick(long millis, boolean paused, double speed, boolean exportMute) {
        if (closed || failed || outputFailed) return;
        boolean nextActive = !paused && !exportMute && Double.isFinite(speed) && Math.abs(speed - 1.0) < .000001;
        long now = System.nanoTime();
        synchronized (lock) {
            if (closed || failed || outputFailed) return;
            boolean jump = previousMillis >= 0 && (millis < previousMillis
                    || Math.abs((millis - previousMillis) - (now - previousNanos) / 1_000_000) > 150);
            if (nextActive != active || jump) resetLocked();
            active = nextActive; currentSample = Math.max(0, millis) * 48;
            previousMillis = millis; previousNanos = now;
            if (active && !missing && !scheduled) { scheduled = true; worker.execute(this::decode); }
        }
    }
    void seek() { synchronized (lock) { if (!closed) { active = false; resetLocked(); previousMillis = -1; } } }
    private void resetLocked() { generation++; resyncs++; submittedUntil = -1; sink.reset(generation); }
    private void decode() {
        try {
            if (!loaded) {
                if (closed) return;
                index = AudioTrackFiles.readIndex(file, () -> closed); loaded = true;
                if (closed) return;
                if (index == null || index.refs().isEmpty()) {
                    missing = true; AudioBridge.playbackMissing(this); return;
                }
                AudioBridge.playbackReady(this);
            }
            while (!closed) {
                long token, from, to;
                synchronized (lock) {
                    if (!active || closed || missing) return;
                    if (submittedUntil >= 0 && currentSample - submittedUntil > 960) resetLocked();
                    token = generation; from = Math.max(currentSample, submittedUntil); to = currentSample + PcmMixer.MAX_OUTPUT_FRAMES;
                    if (from >= to) return;
                }
                List<AudioFrame> frames = frames(from * 1_000_000 / 48_000, (to * 1_000_000 + 47_999) / 48_000);
                byte[] bytes = PcmMixer.render(frames, from, (int) (to - from));
                String notice = null; boolean resumed = false; Throwable outputFailure = null;
                PcmSink.OfferResult result;
                synchronized (lock) {
                    if (closed || !active || generation != token) continue;
                    if (to <= currentSample) continue;
                    // Decoding may have overlapped a tick. Never enqueue already elapsed samples.
                    int skip = (int) Math.max(0, currentSample - from) * 4;
                    if (skip > 0) bytes = Arrays.copyOfRange(bytes, skip, bytes.length);
                    try { result = sink.offerResult(token, bytes); }
                    catch (RuntimeException | LinkageError failure) { result = PcmSink.OfferResult.DEVICE_FAILED; outputFailure = failure; }
                    if (result == PcmSink.OfferResult.ACCEPTED) {
                        submittedUntil = to;
                        if (recovering) {
                            recovering = false; recoveries++; resumed = recoveryNoticePending || waitingForDevice;
                            recoveryNoticePending = false; waitingForDevice = false;
                        }
                    } else if (result == PcmSink.OfferResult.DEVICE_FAILED) {
                        outputFailed = true; resetLocked();
                        notice = "回放音频设备不可用，设备音轨已停止；不是录像缺失或损坏，请重新打开录像重试。";
                    } else if (result == PcmSink.OfferResult.INVALID) {
                        outputFailed = true; resetLocked();
                        notice = "回放PCM输出格式无效，设备音轨已停止；画面继续。";
                    } else {
                        boolean first = !recovering;
                        recovering = true;
                        if (result == PcmSink.OfferResult.BACKPRESSURE) backpressure++;
                        if (result == PcmSink.OfferResult.STALE) staleOffers++;
                        if (result == PcmSink.OfferResult.DEVICE_RECOVERING) { deviceWaits++; waitingForDevice = true; }
                        // Drop expired output, not the track. No retry loop, sleeping, IO, or larger queue:
                        // the next active tick starts from its current sample clock.
                        if (result != PcmSink.OfferResult.DEVICE_RECOVERING || first) resetLocked();
                        long now = System.nanoTime();
                        if (lastRecoveryNotice == 0 || now - lastRecoveryNotice >= TimeUnit.SECONDS.toNanos(5)) {
                            lastRecoveryNotice = now;
                            recoveryNoticePending = true;
                            String reason = result == PcmSink.OfferResult.BACKPRESSURE ? "队列暂满"
                                    : result == PcmSink.OfferResult.STALE ? "旧代次已清除" : "声卡正在重试";
                            notice = "设备音轨正在恢复输出（" + reason + ", 背压=" + backpressure
                                    + ", 旧代次=" + staleOffers + "）；录像未判损坏。";
                        }
                    }
                }
                // Bridge callbacks take its lifecycle lock: never call them while holding our lock.
                if (notice != null) AudioBridge.playbackWarning(this, notice, outputFailure);
                if (resumed) AudioBridge.playbackReady(this);
                return;
            }
        } catch (Exception | LinkageError failure) {
            synchronized (lock) { if (!closed) { failed = true; resetLocked(); } }
            if (!closed) AudioBridge.playbackWarning(this, "录像音轨读取或解码失败，已静音；不会启动真实模拟器。", failure);
        } finally {
            synchronized (lock) { scheduled = false; }
            if (closed) cache.clear();
        }
    }
    private List<AudioFrame> frames(long from, long to) throws Exception {
        List<AudioFrame> frames = new ArrayList<>();
        for (var ref : index.between(from, to)) {
            if (closed) return List.of();
            List<AudioFrame> chunk = cache.get(ref.number());
            if (chunk == null) {
                chunk = AudioTrackFiles.readChunk(file, ref, () -> closed); if (closed) return List.of();
                cache.put(ref.number(), chunk);
                while (cache.size() > 16) cache.remove(cache.keySet().iterator().next());
            }
            for (AudioFrame frame : chunk) if (frame.startMicros() < to && frame.endMicros() > from) frames.add(frame);
        }
        return frames;
    }
    void close() {
        synchronized (lock) { if (closed) return; closed = true; active = false; resetLocked(); }
        sink.close(); worker.shutdownNow();
        // Never join the decoder/device or wait for archive IO on the client thread.
    }
    boolean owns(PcmSink output) { return sink == output; }
    Diagnostics diagnostics() {
        synchronized (lock) { return new Diagnostics(failed, outputFailed, backpressure, staleOffers,
                deviceWaits, recoveries, resyncs); }
    }
}
