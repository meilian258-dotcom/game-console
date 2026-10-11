package cn.piq.replaycompat.replay;

import cn.piq.replaycompat.api.ScreenFrame;
import cn.piq.replaycompat.io.TrackCodec;
import com.replaymod.replaystudio.replay.ReplayFile;
import java.io.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** All archive access stays behind ReplayFile; never opens or overwrites the outer .mcpr. */
final class TrackFiles {
    static final String WARNING_ENTRY = "piq-screen/v1/warning.txt";
    static final String DIAGNOSTIC_ENTRY = "piq-screen/v1/diagnostic.txt";
    static final String EMPTY_WARNING = "此次录制已启用屏幕补丁，但没有保存任何屏幕画面。请确认录制时已看见运行中的世界屏幕，并检查 PIQ-ReplayScreen 日志；此录像无法补回画面。";
    static final String PARTIAL_WARNING = "此次屏幕录制存在丢帧、配额或写入/收尾异常，画面轨可能不完整；请检查 PIQ-ReplayScreen 日志，原 Minecraft 录像仍按 Replay 流程保存。";
    record Summary(int frames, int images, int sources, int chunks, long bytes) {}
    private TrackFiles() {}
    static TrackIndex index(ReplayFile file) throws IOException {
        return index(file, () -> false);
    }
    static TrackIndex index(ReplayFile file, BooleanSupplier cancelled) throws IOException {
        byte[] bytes;
        synchronized (file) {
            checkOpen(cancelled);
            var warning = file.get(WARNING_ENTRY);
            if (warning.isPresent()) {
                try (InputStream in = warning.get()) {
                    ReplayBridge.warn(new String(in.readNBytes(4096), java.nio.charset.StandardCharsets.UTF_8), null);
                }
            }
            var entry = file.get(TrackIndex.INDEX);
            if (!entry.isPresent()) {
                if (!warning.isPresent()) ReplayBridge.warn("此录像没有屏幕画面轨，无法补回当时画面；请安装补丁后重新录制。", null);
                return new TrackIndex(List.of(), List.of());
            }
            try (InputStream in = entry.get()) {
                bytes = in.readNBytes(TrackIndex.MAX_INDEX_BYTES + 1);
                if (bytes.length > TrackIndex.MAX_INDEX_BYTES) throw new IOException("Screen index exceeds limit");
            }
        }
        checkOpen(cancelled);
        return TrackIndex.read(new ByteArrayInputStream(bytes));
    }
    static List<ScreenFrame> chunk(ReplayFile file, TrackIndex index, int number) throws IOException {
        return decodeChunk(readChunk(file, index, number, () -> false));
    }
    /** Archive streams never escape this lock; only bounded owned bytes do. */
    static byte[] readChunk(ReplayFile file, TrackIndex index, int number, BooleanSupplier cancelled) throws IOException {
        int bytes = index.chunks().get(number).bytes();
        synchronized (file) {
            // Checked after acquiring the archive lock so close cannot be followed by a late read.
            checkOpen(cancelled);
            var entry = file.get(TrackIndex.entry(number));
            if (!entry.isPresent()) throw new IOException("Missing screen track chunk " + number);
            try (InputStream in = entry.get()) {
                byte[] encoded = new byte[bytes];
                new DataInputStream(in).readFully(encoded);
                if (in.read() != -1) throw new IOException("Screen entry exceeds declared length");
                return encoded;
            }
        }
    }
    static List<ScreenFrame> decodeChunk(byte[] encoded) throws IOException {
        List<ScreenFrame> frames = TrackCodec.read(new ByteArrayInputStream(encoded));
        if (frames.isEmpty() || frames.size() > 8) throw new IOException("Screen chunk frame count");
        return frames;
    }
    private static void checkOpen(BooleanSupplier cancelled) throws InterruptedIOException {
        if (cancelled.getAsBoolean()) throw new InterruptedIOException("Screen playback closed");
    }
    static ScreenFrame resolve(List<ScreenFrame> chunk, TrackIndex.Ref ref) throws IOException {
        if (ref.frame() >= chunk.size()) throw new IOException("Screen chunk frame missing");
        ScreenFrame frame = chunk.get(ref.frame());
        if (!frame.source().equals(ref.source()) || frame.timestampMillis() != ref.time() || frame.closed() != ref.closed()) throw new IOException("Screen chunk identity mismatch");
        return frame;
    }
    static final class Writer {
        private final ReplayFile file;
        private final List<TrackIndex.Ref> refs = new ArrayList<>();
        private final List<TrackIndex.Chunk> chunks = new ArrayList<>();
        private final Set<String> sources = new HashSet<>();
        private long bytes;
        private long indexBytes = 12;
        private volatile boolean finished;
        private Summary summary;
        Writer(ReplayFile file) { this.file = file; }
        void append(List<ScreenFrame> frames) throws IOException {
            append(frames, frames.stream().map(frame -> frame.timestampMillis() + (frame.closed() ? 0 : TrackIndex.FRAME_TTL_MILLIS)).toList());
        }
        void append(List<ScreenFrame> frames, List<Long> expiresAt) throws IOException {
            if (finished || frames.isEmpty()) return;
            if (expiresAt.size() != frames.size()) throw new IOException("Screen expiry count mismatch");
            if (frames.size() > 8 || refs.size() + frames.size() > TrackIndex.MAX_FRAMES || chunks.size() >= TrackIndex.MAX_CHUNKS)
                throw new IOException("Screen recording frame/chunk quota reached");
            for (int i = 0; i < frames.size(); i++) {
                ScreenFrame frame = frames.get(i);
                if (frame.source().length() > 256 || (long) frame.width() * frame.height() > 1_048_576L) throw new IOException("Screen source/frame quota reached");
                long expires = expiresAt.get(i);
                if (expires < frame.timestampMillis() || expires > frame.timestampMillis() + TrackIndex.FRAME_TTL_MILLIS
                        || frame.closed() && expires != frame.timestampMillis()) throw new IOException("Screen expiry metadata invalid");
                sources.add(frame.source());
            }
            if (sources.size() > TrackIndex.MAX_SOURCES) throw new IOException("Screen recording source quota reached");
            long metadataBytes = 4;
            for (ScreenFrame frame : frames) metadataBytes += 24L + modifiedUtfBytes(frame.source());
            if (indexBytes + metadataBytes > TrackIndex.MAX_INDEX_BYTES) throw new IOException("Screen recording index quota reached");
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (OutputStream bounded = new FilterOutputStream(buffer) {
                private int count;
                @Override public void write(int b) throws IOException { if (++count > TrackIndex.MAX_CHUNK_BYTES) throw new IOException("Screen chunk byte quota"); out.write(b); }
                @Override public void write(byte[] b, int off, int len) throws IOException { if ((long) count + len > TrackIndex.MAX_CHUNK_BYTES) throw new IOException("Screen chunk byte quota"); count += len; out.write(b, off, len); }
            }) { TrackCodec.write(bounded, frames); }
            if (bytes + buffer.size() > TrackIndex.MAX_TOTAL_BYTES) throw new IOException("Screen recording byte quota reached");
            int number = chunks.size();
            synchronized (file) {
                if (finished) return;
                try (OutputStream out = file.write(TrackIndex.entry(number))) { buffer.writeTo(out); }
                chunks.add(new TrackIndex.Chunk(number, buffer.size())); bytes += buffer.size();
                indexBytes += metadataBytes;
                for (int i = 0; i < frames.size(); i++) refs.add(new TrackIndex.Ref(frames.get(i).source(), frames.get(i).timestampMillis(), number, i, frames.get(i).closed(), expiresAt.get(i)));
            }
        }
        private static int modifiedUtfBytes(String value) {
            int length = 0;
            for (int i = 0; i < value.length(); i++) { int c = value.charAt(i); length += c >= 1 && c <= 127 ? 1 : c <= 2047 ? 2 : 3; }
            return length;
        }
        void finish() throws IOException { finish(-1, 0, "none"); }
        Summary finish(long submitted, long dropped, String failureCode) throws IOException {
            synchronized (file) {
                if (finished) {
                    if (summary == null) throw new IOException("Screen track finalization previously failed");
                    return summary;
                }
                finished = true;
                if (!refs.isEmpty()) {
                    var index = new TrackIndex(refs, chunks);
                    try (OutputStream out = file.write(TrackIndex.INDEX)) { index.write(out); }
                }
                var committed = new Summary(refs.size(), (int) refs.stream().filter(ref -> !ref.closed()).count(),
                        (int) refs.stream().map(TrackIndex.Ref::source).distinct().count(), chunks.size(), bytes);
                // Only recording sessions request this entry. Fixed reasons and aggregate counts
                // deliberately exclude source IDs, dimensions, coordinates, paths and game names.
                if (submitted >= 0) {
                    if (!Set.of("none", "write_failed", "finish_timeout", "finish_interrupted", "close_overflow").contains(failureCode))
                        throw new IOException("Unknown screen recording diagnostic code");
                    String result = !"none".equals(failureCode) ? failureCode
                            : committed.images() == 0 ? "empty_capture" : dropped > 0 ? "partial_capture" : "complete";
                    String diagnostic = "format=piq-screen-diagnostic-v1\nresult=" + result + "\nsubmitted=" + submitted
                            + "\ndropped=" + dropped + "\nframes=" + committed.frames() + "\nimages=" + committed.images()
                            + "\nsources=" + committed.sources() + "\nchunks=" + committed.chunks() + "\nbytes=" + committed.bytes() + "\n";
                    writeText(DIAGNOSTIC_ENTRY, diagnostic);
                    String warning = committed.images() == 0 ? EMPTY_WARNING : !"none".equals(failureCode) || dropped > 0 ? PARTIAL_WARNING : null;
                    if (warning != null) { writeText(WARNING_ENTRY, warning); ReplayBridge.warn(warning, null); }
                }
                summary = committed;
                return committed;
            }
        }
        private void writeText(String entry, String value) throws IOException {
            byte[] encoded = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (encoded.length > 4096) throw new IOException("Screen diagnostic byte quota");
            try (OutputStream out = file.write(entry)) { out.write(encoded); }
        }
    }
}
