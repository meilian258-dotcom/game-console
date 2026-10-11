package cn.piq.replaycompat.audio;

import cn.piq.replaycompat.api.AudioFrame;
import com.replaymod.replaystudio.replay.ReplayFile;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;

/** Device threads only copy bounded PCM; all archive work belongs to this recorder's worker. */
final class AudioRecording {
    static final int MAX_ACTIVE_SOURCES = 8, MAX_PENDING_BYTES = 2 * 1024 * 1024;
    private static final class Source {
        final String id; long epoch, lastAt = Long.MIN_VALUE, anchor, samples; int rate, channels; boolean anchored, closed;
        Source(int number) { id = "terminal-" + number; }
    }
    private record Token(AudioRecording recorder, Object owner, Source source, long epoch) {}
    private final IdentityHashMap<Object, Source> sources = new IdentityHashMap<>();
    private final ArrayBlockingQueue<AudioFrame> queue = new ArrayBlockingQueue<>(256);
    private final AudioTrackFiles.Writer writer;
    private final LongSupplier clock;
    private final ExecutorService worker;
    private volatile boolean accepting = true, stopping;
    private int pendingBytes; private long submitted, dropped;
    private volatile String failure = "none";
    AudioRecording(ReplayFile file, LongSupplier clock) {
        this.clock = Objects.requireNonNull(clock); writer = new AudioTrackFiles.Writer(file);
        worker = Executors.newSingleThreadExecutor(task -> { Thread thread = new Thread(task, "PIQ-Replay-Audio-Record"); thread.setDaemon(true); return thread; });
        worker.execute(this::run);
    }
    boolean accepting() { return accepting; }
    synchronized Object token(Object owner) {
        if (!accepting || owner == null) return null;
        Source source = sources.get(owner);
        if (source == null) {
            if (sources.size() >= AudioTrackFiles.MAX_SOURCES) { dropped++; return null; }
            source = new Source(sources.size()); sources.put(owner, source);
        }
        if (source.closed) return null;
        return new Token(this, owner, source, source.epoch);
    }
    synchronized void capture(Object candidate, Object owner, byte[] bytes, int offset, int length, int rate, int channels) {
        if (!accepting || !(candidate instanceof Token token) || token.recorder != this || token.owner != owner
                || token.source.epoch != token.epoch || token.source.closed) return;
        if (bytes == null || offset < 0 || length <= 0 || length > AudioFrame.MAX_PCM_BYTES || offset > bytes.length - length
                || rate < 8_000 || rate > 192_000 || channels < 1 || channels > 2 || length % (channels * 2) != 0
                || (long) length * 1_000_000 / (rate * channels * 2L) > 500_000) { dropped++; return; }
        long now = Math.max(0, clock.getAsLong()) * 1000;
        if (now < 0 || now > AudioFrame.MAX_TIME_MICROS) { stop("duration_quota"); return; }
        Source source = token.source;
        long activeSources = sources.values().stream().filter(s -> !s.closed && s.lastAt != Long.MIN_VALUE && now - s.lastAt <= 500_000).count();
        if ((source.lastAt == Long.MIN_VALUE || now - source.lastAt > 500_000) && activeSources >= MAX_ACTIVE_SOURCES) { dropped++; return; }
        source.lastAt = now;
        if (!source.anchored || source.rate != rate || source.channels != channels
                || now < source.anchor || now - (source.anchor + source.samples * 1_000_000 / rate) > 150_000) {
            source.anchor = now; source.samples = 0; source.rate = rate; source.channels = channels; source.anchored = true;
        }
        long start = source.anchor + source.samples * 1_000_000 / rate;
        if (start - now > 500_000) { dropped++; source.anchored = false; return; }
        submitted++;
        if ((long) pendingBytes + length > MAX_PENDING_BYTES || queue.remainingCapacity() == 0) {
            dropped++; source.anchored = false; return;
        }
        AudioFrame frame;
        try { frame = new AudioFrame(source.id, start, rate, channels, Arrays.copyOfRange(bytes, offset, offset + length)); }
        catch (IllegalArgumentException invalid) { dropped++; source.anchored = false; return; }
        if (!queue.offer(frame)) { dropped++; source.anchored = false; return; }
        source.samples += length / (channels * 2); pendingBytes += length;
    }
    synchronized void reset(Object owner, boolean close) {
        Source source = sources.get(owner);
        if (source != null) { source.epoch++; source.anchored = false; source.lastAt = Long.MIN_VALUE; source.closed |= close; }
    }
    synchronized void stopAccepting() { accepting = false; stopping = true; }
    private synchronized void stop(String reason) { failure = reason; stopAccepting(); }
    private void run() {
        List<AudioFrame> batch = new ArrayList<>(); int raw = 0;
        try {
            while (!stopping || !queue.isEmpty()) {
                AudioFrame frame = queue.poll(100, TimeUnit.MILLISECONDS);
                if (frame != null) {
                    if (!batch.isEmpty() && (raw + frame.byteCount() > AudioTrackFiles.MAX_CHUNK_PCM || batch.size() == AudioTrackFiles.MAX_CHUNK_FRAMES)) {
                        writer.append(batch); release(raw); batch.clear(); raw = 0;
                    }
                    batch.add(frame); raw += frame.byteCount();
                }
                if (!batch.isEmpty() && (frame == null || batch.size() >= 32 || stopping && queue.isEmpty())) {
                    writer.append(batch); release(raw); batch.clear(); raw = 0;
                }
            }
        } catch (Exception | LinkageError error) {
            stop("write_failed"); AudioBridge.warning("音轨录制达到配额或写入失败，原 Minecraft 录像继续。", error);
        } finally { synchronized (this) { queue.clear(); pendingBytes = 0; } }
    }
    private synchronized void release(int bytes) { pendingBytes -= bytes; }
    /** Call on Replay's save/finalization worker, never on a render/tick/device thread. */
    AudioTrackFiles.Summary finish() {
        stopAccepting(); worker.shutdown();
        try {
            if (!worker.awaitTermination(15, TimeUnit.SECONDS)) failure = "finish_timeout";
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); failure = "finish_interrupted"; }
        long submittedCount, droppedCount;
        synchronized (this) { submittedCount = submitted; droppedCount = dropped; }
        try {
            String result = !failure.equals("none") ? failure : submittedCount == 0 ? "empty_capture" : droppedCount > 0 ? "partial_capture" : "complete";
            var summary = writer.finish(submittedCount, droppedCount, result);
            AudioBridge.recorded(summary, result); return summary;
        } catch (Exception | LinkageError error) { AudioBridge.warning("音轨索引保存失败；不阻止主录像保存。", error); return null; }
    }
}
