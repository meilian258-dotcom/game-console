package cn.piq.replaycompat.replay;

import cn.piq.replaycompat.api.ScreenFrame;
import com.replaymod.replaystudio.replay.ReplayFile;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

/** Exact nonblocking selection from a bounded decoded window; all archive IO stays on one worker. */
final class PlaybackSession {
    static final long CACHE_BYTES = 96L * 1024 * 1024;
    static final int CACHE_CHUNKS = 32, PREFETCH_CHUNKS = 4;
    static final long PREFETCH_MILLIS = 1_000;
    private final ReplayFile file;
    private final ExecutorService worker;
    private final Object lock = new Object();
    private final long cacheBudget;
    private final ChunkDecoder decoder;
    @FunctionalInterface interface ChunkDecoder { List<ScreenFrame> decode(byte[] encoded) throws IOException; }
    private final LinkedHashMap<Integer, DecodedChunk> cache = new LinkedHashMap<>(16, .75f, true);
    // Only the current sixteen selected frames survive cache eviction. No previous timeline is held.
    private Map<TrackIndex.Ref, ScreenFrame> active = Map.of();
    private final Set<Integer> prefetchAttempted = new HashSet<>();
    private final Set<Integer> invalidChunks = new HashSet<>();
    private Map<Integer, List<TrackIndex.Ref>> refsByChunk = Map.of();
    private List<TrackIndex.Ref> ordered = List.of();
    private TrackIndex index;
    private List<TrackIndex.Ref> selected = List.of();
    private boolean closed, failed, scheduled;
    private long requested = -1, readyTime = -1, cacheBytes, generation, workedGeneration = -1;
    private record DecodedChunk(Map<TrackIndex.Ref, ScreenFrame> frames, long bytes) {}

    PlaybackSession(ReplayFile file) { this(file, CACHE_BYTES); }
    PlaybackSession(ReplayFile file, long cacheBudget) {
        this(file, cacheBudget, TrackFiles::decodeChunk);
    }
    PlaybackSession(ReplayFile file, long cacheBudget, ChunkDecoder decoder) {
        if (cacheBudget < 1 || cacheBudget > CACHE_BYTES) throw new IllegalArgumentException("Screen cache budget");
        this.file = file; this.cacheBudget = cacheBudget; this.decoder = Objects.requireNonNull(decoder);
        worker = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "PIQ-Replay-Decode"); thread.setDaemon(true); return thread;
        });
    }
    /** One atomic result distinguishes an empty timeline from an incomplete decode. */
    PlaybackView viewAt(long time) {
        synchronized (lock) { requestLocked(time); return viewLocked(); }
    }
    List<ScreenFrame> at(long time) {
        PlaybackView view = viewAt(time);
        return view.ready() ? view.frames() : List.of();
    }
    boolean readyAt(long time) { return viewAt(time).ready(); }
    long readyTime() { synchronized (lock) { return readyTime; } }
    boolean prepareAt(long time, long timeoutMillis) {
        long target = Math.max(0, time);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        synchronized (lock) {
            requestLocked(target);
            while (!closed && !failed) {
                if (requested == target && viewLocked().ready()) return true;
                long left = deadline - System.nanoTime(); if (left <= 0) return false;
                try { TimeUnit.NANOSECONDS.timedWait(lock, left); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return false; }
            }
            return false;
        }
    }
    private void requestLocked(long time) {
        if (closed || failed) return;
        long nextTime = Math.max(0, time);
        List<TrackIndex.Ref> next = index == null ? List.of() : index.select(nextTime);
        // A bounded moving prefetch window, not one new decode job per rendered frame.
        if (requested < 0 || nextTime < requested || nextTime / 250 != requested / 250 || !next.equals(selected)) {
            generation++; prefetchAttempted.clear();
        }
        requested = nextTime; selected = next;
        collectActiveLocked();
        if (!scheduled && (index == null || active.size() != selected.size() || workedGeneration != generation)) {
            scheduled = true; worker.execute(this::decode);
        }
    }
    private void collectActiveLocked() {
        var available = new LinkedHashMap<TrackIndex.Ref, ScreenFrame>();
        for (var ref : selected) {
            ScreenFrame frame = active.get(ref);
            if (frame == null) {
                DecodedChunk chunk = cache.get(ref.chunk());
                if (chunk != null) frame = chunk.frames.get(ref);
            }
            if (frame != null) available.put(ref, frame);
        }
        active = Map.copyOf(available);
        if (index != null && active.size() == selected.size()) readyTime = requested;
    }
    private PlaybackView viewLocked() {
        if (closed) return PlaybackView.closed(requested);
        if (failed) return new PlaybackView(PlaybackView.Status.FAILED, requested, List.of(), Set.of());
        if (index == null) return new PlaybackView(PlaybackView.Status.PENDING, requested, List.of(), Set.of());
        var frames = new ArrayList<ScreenFrame>(); var sources = new LinkedHashSet<String>();
        for (var ref : selected) {
            sources.add(ref.source()); ScreenFrame frame = active.get(ref);
            if (frame != null) frames.add(frame);
        }
        PlaybackView.Status status = frames.size() == selected.size()
                ? frames.isEmpty() ? PlaybackView.Status.READY_EMPTY : PlaybackView.Status.READY_FRAMES
                : frames.isEmpty() ? PlaybackView.Status.PENDING : PlaybackView.Status.PARTIAL;
        return new PlaybackView(status, requested, frames, sources);
    }
    private void decode() {
        try {
            boolean loadIndex;
            synchronized (lock) { loadIndex = index == null && !closed; }
            if (loadIndex) {
                TrackIndex loaded = TrackFiles.index(file, this::isClosed);
                var grouped = new HashMap<Integer, List<TrackIndex.Ref>>();
                for (var ref : loaded.frames()) grouped.computeIfAbsent(ref.chunk(), ignored -> new ArrayList<>()).add(ref);
                var timeline = new ArrayList<>(loaded.frames());
                timeline.sort(Comparator.comparingLong(TrackIndex.Ref::time).thenComparingInt(TrackIndex.Ref::chunk));
                synchronized (lock) {
                    if (closed) return;
                    index = loaded; refsByChunk = grouped; ordered = List.copyOf(timeline);
                    selected = index.select(requested); collectActiveLocked(); lock.notifyAll();
                }
            }
            while (true) {
                int number; TrackIndex loaded;
                synchronized (lock) {
                    if (closed || failed) return;
                    collectActiveLocked(); number = nextChunkLocked(); loaded = index;
                    if (number < 0) { workedGeneration = generation; scheduled = false; lock.notifyAll(); return; }
                }
                DecodedChunk chunk;
                try {
                    byte[] encoded = TrackFiles.readChunk(file, loaded, number, this::isClosed);
                    if (isClosed()) return;
                    List<ScreenFrame> decoded = decoder.decode(encoded);
                    var valid = new LinkedHashMap<TrackIndex.Ref, ScreenFrame>();
                    // Validate indexed members now, so a later render never validates/decodes pixels.
                    for (var ref : refsByChunk.getOrDefault(number, List.of())) valid.put(ref, TrackFiles.resolve(decoded, ref));
                    chunk = new DecodedChunk(Map.copyOf(valid), decodedBytes(decoded));
                } catch (IOException invalid) {
                    synchronized (lock) {
                        invalidChunks.add(number);
                        if (selected.stream().anyMatch(ref -> ref.chunk() == number)) throw invalid;
                    }
                    // Read-ahead must not disable an earlier valid timeline before the bad data is selected.
                    continue;
                }
                synchronized (lock) {
                    if (closed) return;
                    long bytes = chunk.bytes;
                    var current = new HashMap<>(active);
                    for (var ref : selected) { ScreenFrame frame = chunk.frames.get(ref); if (frame != null) current.put(ref, frame); }
                    active = Map.copyOf(current);
                    if (bytes <= cacheBudget) {
                        DecodedChunk old = cache.put(number, chunk); if (old != null) cacheBytes -= old.bytes;
                        cacheBytes += bytes;
                        while (cacheBytes > cacheBudget || cache.size() > CACHE_CHUNKS) {
                            var eldest = cache.entrySet().iterator(); var entry = eldest.next();
                            cacheBytes -= entry.getValue().bytes; eldest.remove();
                        }
                    }
                    collectActiveLocked(); lock.notifyAll();
                }
            }
        } catch (Exception | LinkageError failure) {
            synchronized (lock) { if (closed) return; failed = true; active = Map.of(); cache.clear(); cacheBytes = 0; }
            ReplayBridge.warn("录像屏幕轨无效或读取失败，已禁用画面轨；不会启动真实模拟器。", failure);
        } finally {
            synchronized (lock) { if (closed || failed) scheduled = false; lock.notifyAll(); }
        }
    }
    private int nextChunkLocked() throws IOException {
        for (var ref : selected) if (!active.containsKey(ref)) {
            if (invalidChunks.contains(ref.chunk())) throw new IOException("Invalid selected screen chunk " + ref.chunk());
            return ref.chunk();
        }
        int low = 0, high = ordered.size();
        while (low < high) { int mid = (low + high) >>> 1; if (ordered.get(mid).time() <= requested) low = mid + 1; else high = mid; }
        var candidates = new LinkedHashSet<Integer>();
        for (int n = low; n < ordered.size() && ordered.get(n).time() <= requested + PREFETCH_MILLIS; n++) {
            var ref = ordered.get(n);
            if (ref.closed()) continue;
            candidates.add(ref.chunk());
            if (candidates.size() >= PREFETCH_CHUNKS) break;
        }
        for (int number : candidates) if (!cache.containsKey(number) && !invalidChunks.contains(number)
                && prefetchAttempted.add(number)) return number;
        return -1;
    }
    // Conservative retained-heap accounting: no abgr() clone, compression size is irrelevant.
    static long decodedBytes(List<ScreenFrame> frames) {
        long bytes = 256;
        for (var frame : frames) bytes += 256L + (long) frame.width() * frame.height() * Integer.BYTES
                + frame.vertices().size() * 80L + (frame.source().length() + frame.dimension().length()) * 2L;
        return bytes;
    }
    long cachedBytes() { synchronized (lock) { return cacheBytes; } }
    int cachedChunks() { synchronized (lock) { return cache.size(); } }
    private boolean isClosed() { synchronized (lock) { return closed; } }
    void close() {
        synchronized (lock) { closed = true; active = Map.of(); cache.clear(); cacheBytes = 0; lock.notifyAll(); }
        worker.shutdownNow();
        synchronized (file) { /* No in-flight archive read remains before ReplayFile.close(). */ }
    }
}
