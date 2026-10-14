package cn.piq.replaycompat.replay;

import cn.piq.replaycompat.api.*;
import com.replaymod.replaystudio.replay.ReplayFile;
import com.replaymod.replaystudio.lib.guava.base.Optional;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Nonblocking interactive playback regressions; never starts Minecraft or touches user recordings. */
public final class PlaybackContinuityTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        adjacentAndPrefetch(); partialAndAtomic(); seekCloseAndDimension(); memoryBounds(); invalidPrefetch();
        System.out.println("PlaybackContinuityTest: " + checks + " checks passed");
        if (args.length > 0) realArchive(java.nio.file.Path.of(args[0]), args.length > 1 ? Double.parseDouble(args[1]) : 1);
    }
    private static void adjacentAndPrefetch() throws Exception {
        Archive archive = new Archive(); var writer = new TrackFiles.Writer(archive.file);
        for (int chunk = 0; chunk < 2; chunk++) {
            var frames = new ArrayList<ScreenFrame>();
            for (int n = 0; n < 8; n++) frames.add(frame("screen", (chunk * 8L + n) * 100, chunk * 8 + n));
            writer.append(frames);
        }
        writer.finish(); archive.gate(1);
        var player = new PlaybackSession(archive.file);
        try {
            check(player.prepareAt(0, 2_000), "initial frame ready without waiting for prefetch");
            check(archive.entered.await(2, TimeUnit.SECONDS), "next chunk is prefetched before its first frame");
            int reads = archive.reads(0);
            for (long time = 0; time < 800; time += 10) {
                var view = player.viewAt(time);
                check(view.ready() && view.frames().size() == 1 && view.frames().get(0).timestampMillis() == time / 100 * 100,
                        "same-chunk exact selection is immediate even while IO is stalled");
            }
            check(archive.reads(0) == reads && reads == 1, "same chunk is read once, not once per image");
            archive.release.countDown(); await(() -> player.cachedChunks() == 2);
            for (long time = 800; time < 1600; time += 10) {
                var view = player.viewAt(time);
                check(view.ready() && view.frames().get(0).timestampMillis() == time / 100 * 100,
                        "prefetched chunk boundary never blanks or uses previous pixels");
            }
            check(archive.reads(1) == 1, "prefetch reused by exact target");
        } finally { archive.release.countDown(); player.close(); }
    }
    private static void partialAndAtomic() throws Exception {
        Archive archive = new Archive(); var writer = new TrackFiles.Writer(archive.file);
        writer.append(List.of(frame("already-ready", 0, 1)));
        writer.append(List.of(frame("slow-screen", 20, 2))); writer.finish(); archive.gate(1);
        var player = new PlaybackSession(archive.file);
        try {
            check(player.prepareAt(0, 2_000), "first of two sources ready");
            check(archive.entered.await(2, TimeUnit.SECONDS), "second source read deliberately stalled");
            PlaybackView pending = player.viewAt(20);
            check(pending.status() == PlaybackView.Status.PARTIAL && pending.frames().size() == 1
                    && pending.frames().get(0).source().equals("already-ready"), "one pending source does not hide another");
            check(pending.activeSources().equals(Set.of("already-ready", "slow-screen")), "pending identity distinct from closed source");
            check(player.at(20).isEmpty() && !player.readyAt(20), "legacy exact-set API remains strict");
            archive.release.countDown(); check(player.prepareAt(20, 2_000), "both sources become exact");
            check(pending.status() == PlaybackView.Status.PARTIAL && pending.frames().size() == 1,
                    "previous atomic result cannot become ready-empty after worker publication");
            for (int n = 0; n < 20; n++) check(player.viewAt(20).frames().size() == 2, "paused view never alternates");
            check(player.viewAt(19).frames().size() == 1, "backwards seek removes not-yet-present source immediately");
        } finally { archive.release.countDown(); player.close(); }
    }
    private static void seekCloseAndDimension() throws Exception {
        Archive archive = new Archive(); var writer = new TrackFiles.Writer(archive.file);
        writer.append(List.of(frame("screen", 0, 1)));
        writer.append(List.of(new ScreenFrame("screen", "minecraft:the_nether", 5000, 1, 1,
                new int[]{2}, vertices()), ScreenFrame.closed("screen", "minecraft:the_nether", 5500)));
        writer.finish(); archive.gate(1); var player = new PlaybackSession(archive.file);
        try {
            check(player.prepareAt(0, 2_000), "seek baseline");
            check(player.viewAt(5000).frames().isEmpty(), "forward seek never draws an old cached frame");
            check(archive.entered.await(2, TimeUnit.SECONDS), "far seek IO deliberately stalled");
            var back = player.viewAt(0);
            check(back.ready() && back.frames().get(0).abgr()[0] == 1, "backwards seek immediately selects cached exact frame");
            archive.release.countDown(); await(() -> player.cachedChunks() == 2);
            check(player.viewAt(0).frames().get(0).timestampMillis() == 0, "late future decode never replaces earlier timeline");
            var future = player.viewAt(5000);
            check(future.ready() && future.frames().get(0).dimension().equals("minecraft:the_nether"), "dimension travels with exact frame only");
            check(player.viewAt(5500).status() == PlaybackView.Status.READY_EMPTY, "close removes frame immediately without decode");
            check(player.viewAt(2000).status() == PlaybackView.Status.READY_EMPTY, "expiry removes frame immediately without decode");
            check(player.prepareAt(5000, 2_000) && player.at(5000).get(0).abgr()[0] == 2, "export waits for exact seek target");
        } finally { archive.release.countDown(); player.close(); }
        check(player.viewAt(0).status() == PlaybackView.Status.CLOSED && player.cachedBytes() == 0, "close releases all retained pixels");
    }
    private static void memoryBounds() throws Exception {
        Archive archive = new Archive(); var writer = new TrackFiles.Writer(archive.file);
        for (int n = 0; n < 16; n++) writer.append(List.of(frame("source-" + n, 0, n)));
        writer.finish(); var player = new PlaybackSession(archive.file, 1024);
        try {
            check(player.prepareAt(0, 2_000) && player.viewAt(0).frames().size() == 16,
                    "sixteen independent selected frames survive a small chunk-cache budget");
            check(player.cachedBytes() <= 1024 && player.cachedChunks() <= 1, "cache obeys decoded-byte budget rather than compressed size");
        } finally { player.close(); }
        Archive many = new Archive(); writer = new TrackFiles.Writer(many.file);
        for (int n = 0; n < 40; n++) writer.append(List.of(frame("one", n * 5000L, n)));
        writer.finish(); var bounded = new PlaybackSession(many.file);
        try {
            for (int n = 0; n < 40; n++) {
                check(bounded.prepareAt(n * 5000L, 2_000), "distant chunk selected");
                check(bounded.cachedChunks() <= PlaybackSession.CACHE_CHUNKS && bounded.cachedBytes() <= PlaybackSession.CACHE_BYTES,
                        "count and decoded memory caps remain bounded after repeated seeks");
            }
        } finally { bounded.close(); }
    }
    private static void invalidPrefetch() throws Exception {
        Archive archive = new Archive(); var writer = new TrackFiles.Writer(archive.file);
        writer.append(List.of(frame("screen", 0, 1))); writer.append(List.of(frame("screen", 500, 2))); writer.finish();
        archive.entries.put(TrackIndex.entry(1), new byte[]{1, 2, 3});
        var player = new PlaybackSession(archive.file);
        try {
            check(player.prepareAt(0, 2000), "valid current image loads before bad future chunk");
            await(() -> archive.reads(1) > 0);
            check(player.viewAt(0).ready() && player.viewAt(0).frames().size() == 1, "invalid prefetch does not blank valid earlier timeline");
            check(!player.prepareAt(500, 2000), "export rejects an actually selected invalid chunk");
            check(player.viewAt(500).status() == PlaybackView.Status.FAILED, "bad selected image is failure, never a stale fallback");
        } finally { player.close(); }
    }
    /** Optional read-only real-distribution probe. No ReplayFile save or outer archive mutation. */
    private static void realArchive(java.nio.file.Path path, double speed) throws Exception {
        if (!Double.isFinite(speed) || speed <= 0 || speed > 32) throw new IllegalArgumentException("Probe speed");
        var reads = new ConcurrentHashMap<String, AtomicInteger>();
        try (var zip = new java.util.zip.ZipFile(path.toFile())) {
            ReplayFile file = (ReplayFile) Proxy.newProxyInstance(ReplayFile.class.getClassLoader(), new Class<?>[]{ReplayFile.class},
                    (proxy, method, arguments) -> {
                        if (method.getName().equals("get")) {
                            String entry = (String) arguments[0]; reads.computeIfAbsent(entry, ignored -> new AtomicInteger()).incrementAndGet();
                            var value = zip.getEntry(entry); return Optional.fromNullable(value == null ? null : zip.getInputStream(value));
                        }
                        if (method.getName().equals("toString")) return "ReadOnlyContinuityProbe";
                        throw new UnsupportedOperationException("Read-only probe: " + method.getName());
                    });
            TrackIndex index = TrackFiles.index(file);
            long start = index.frames().stream().filter(ref -> !ref.closed()).mapToLong(TrackIndex.Ref::time).min().orElse(0);
            long stop = index.frames().stream().mapToLong(TrackIndex.Ref::time).max().orElse(start);
            var player = new PlaybackSession(file); int requests = 0, missing = 0; long maxBytes = 0;
            var gaps = new ArrayList<Long>();
            try {
                if (!player.prepareAt(start, 5000)) throw new AssertionError("Initial real frame unavailable");
                long began = System.nanoTime();
                for (long time = start; time <= stop; time += 16) {
                    long due = began + (long) ((time - start) * 1_000_000 / speed);
                    while (System.nanoTime() < due) TimeUnit.NANOSECONDS.sleep(Math.min(1_000_000, due - System.nanoTime()));
                    PlaybackView view = player.viewAt(time); requests++;
                    // Independent archive index, not the decoder's own active set: failures must count as gaps.
                    var expected = index.select(time);
                    boolean exact = view.ready() && view.frames().size() == expected.size();
                    for (var frame : view.frames()) exact &= expected.stream().anyMatch(ref -> ref.source().equals(frame.source())
                            && ref.time() == frame.timestampMillis());
                    if (!exact) { missing++; if (gaps.size() < 20) gaps.add(time); }
                    maxBytes = Math.max(maxBytes, player.cachedBytes());
                }
                long chunkReads = reads.entrySet().stream().filter(entry -> entry.getKey().contains("/chunks/"))
                        .mapToLong(entry -> entry.getValue().get()).sum();
                System.out.println("REAL_ARCHIVE_CONTINUITY speed=" + speed + " images=" + index.frames().stream().filter(ref -> !ref.closed()).count()
                        + " chunks=" + index.chunks().size() + " requests=" + requests + " missing=" + missing
                        + " chunkReads=" + chunkReads + " maxCacheBytes=" + maxBytes + " firstGaps=" + gaps);
                if (missing != 0) throw new AssertionError("Exact nonblocking playback gaps: " + missing);
            } finally { player.close(); }
        }
    }
    private static ScreenFrame frame(String source, long time, int pixel) {
        return new ScreenFrame(source, "minecraft:overworld", time, 1, 1, new int[]{pixel}, vertices());
    }
    private static List<ScreenVertex> vertices() {
        return List.of(new ScreenVertex(0,0,0,0,0,-1), new ScreenVertex(1,0,0,1,0,-1),
                new ScreenVertex(1,1,0,1,1,-1), new ScreenVertex(0,1,0,0,1,-1));
    }
    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(1);
        check(condition.getAsBoolean(), "bounded async completion");
    }
    private static void check(boolean condition, String name) {
        checks++; if (!condition) throw new AssertionError(name);
    }
    private static final class Archive implements InvocationHandler {
        final Map<String, byte[]> entries = new ConcurrentHashMap<>();
        final Map<String, AtomicInteger> readCounts = new ConcurrentHashMap<>();
        volatile String blocked;
        volatile CountDownLatch entered = new CountDownLatch(0), release = new CountDownLatch(0);
        final ReplayFile file = (ReplayFile) Proxy.newProxyInstance(ReplayFile.class.getClassLoader(), new Class<?>[]{ReplayFile.class}, this);
        void gate(int chunk) { blocked = TrackIndex.entry(chunk); entered = new CountDownLatch(1); release = new CountDownLatch(1); }
        int reads(int chunk) { return readCounts.getOrDefault(TrackIndex.entry(chunk), new AtomicInteger()).get(); }
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            return switch (method.getName()) {
                case "get" -> {
                    String entry = (String) args[0]; readCounts.computeIfAbsent(entry, ignored -> new AtomicInteger()).incrementAndGet();
                    if (entry.equals(blocked)) { entered.countDown(); if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Read gate timed out"); }
                    byte[] bytes = entries.get(entry); yield Optional.fromNullable(bytes == null ? null : new ByteArrayInputStream(bytes));
                }
                case "write" -> new ByteArrayOutputStream() { public void close() { entries.put((String) args[0], toByteArray()); } };
                case "close", "save" -> null;
                case "toString" -> "ContinuityTestArchive";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(method.getName());
            };
        }
    }
}
