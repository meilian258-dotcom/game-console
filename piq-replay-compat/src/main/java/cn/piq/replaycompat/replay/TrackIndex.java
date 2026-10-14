package cn.piq.replaycompat.replay;

import java.io.*;
import java.util.*;

/** Small bounded metadata; pixel data is kept in independently decodable eight-frame chunks. */
public final class TrackIndex {
    public static final String INDEX = "piq-screen/v1/index.bin";
    public static final int MAX_FRAMES = 72_000, MAX_CHUNKS = 9_000, MAX_SOURCES = 1024, MAX_ACTIVE_SOURCES = 16;
    public static final long FRAME_TTL_MILLIS = 2_000;
    public static final int MAX_CHUNK_BYTES = 40 * 1024 * 1024;
    public static final long MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024;
    public static final int MAX_INDEX_BYTES = 16 * 1024 * 1024;
    private static final int MAGIC = 0x50535231;
    public record Ref(String source, long time, int chunk, int frame, boolean closed, long expiresAt) {
        public Ref(String source, long time, int chunk, int frame) { this(source, time, chunk, frame, false, time + FRAME_TTL_MILLIS); }
    }
    public record Chunk(int number, int bytes) {}
    private final List<Ref> frames;
    private final List<Chunk> chunks;
    private final Map<String, List<Ref>> bySource;

    public TrackIndex(List<Ref> frames, List<Chunk> chunks) {
        this.frames = List.copyOf(frames); this.chunks = List.copyOf(chunks);
        if (frames.size() > MAX_FRAMES || chunks.size() > MAX_CHUNKS) throw new IllegalArgumentException("Screen track index limit");
        var grouped = new LinkedHashMap<String, List<Ref>>();
        long bytes = 0;
        for (int i = 0; i < chunks.size(); i++) {
            Chunk c = chunks.get(i);
            if (c.number != i || c.bytes < 1 || c.bytes > MAX_CHUNK_BYTES) throw new IllegalArgumentException("Screen chunk descriptor");
            bytes += c.bytes;
        }
        if (bytes > MAX_TOTAL_BYTES) throw new IllegalArgumentException("Screen track byte limit");
        Set<Long> positions = new HashSet<>();
        for (Ref r : frames) {
            if (r.source == null || r.source.isEmpty() || r.source.length() > 256 || r.time < 0 || r.time > Integer.MAX_VALUE
                    || r.expiresAt < r.time || r.expiresAt > r.time + FRAME_TTL_MILLIS || r.closed && r.expiresAt != r.time
                    || r.chunk < 0 || r.chunk >= chunks.size() || r.frame < 0 || r.frame >= 8
                    || !positions.add(((long) r.chunk << 4) | r.frame)) throw new IllegalArgumentException("Screen frame index");
            grouped.computeIfAbsent(r.source, ignored -> new ArrayList<>()).add(r);
        }
        if (grouped.size() > MAX_SOURCES) throw new IllegalArgumentException("Too many screen sources");
        grouped.replaceAll((key, values) -> {
            values.sort(Comparator.comparingLong(Ref::time).thenComparingInt(Ref::chunk).thenComparingInt(Ref::frame));
            return List.copyOf(values);
        });
        this.bySource = Collections.unmodifiableMap(grouped);
    }
    public List<Ref> frames() { return frames; }
    public List<Chunk> chunks() { return chunks; }
    public List<Ref> select(long time) {
        var result = new ArrayList<Ref>();
        for (List<Ref> rows : bySource.values()) {
            int low = 0, high = rows.size();
            while (low < high) { int mid = (low + high) >>> 1; if (rows.get(mid).time <= time) low = mid + 1; else high = mid; }
            if (low > 0) {
                Ref latest = rows.get(low - 1);
                if (!latest.closed && time < latest.expiresAt) result.add(latest);
            }
        }
        // Limit untrusted snapshots before any chunk/pixel allocation, regardless of manifest claims.
        result.sort(Comparator.comparingLong(Ref::time).reversed().thenComparing(Ref::source));
        if (result.size() > MAX_ACTIVE_SOURCES) result.subList(MAX_ACTIVE_SOURCES, result.size()).clear();
        return List.copyOf(result);
    }
    public static String entry(int number) {
        if (number < 0 || number >= MAX_CHUNKS) throw new IllegalArgumentException("Chunk number");
        return "piq-screen/v1/chunks/" + String.format(Locale.ROOT, "%05d", number) + ".bin";
    }
    public void write(OutputStream stream) throws IOException {
        DataOutputStream out = new DataOutputStream(stream);
        out.writeInt(MAGIC); out.writeInt(chunks.size());
        for (Chunk chunk : chunks) out.writeInt(chunk.bytes);
        out.writeInt(frames.size());
        for (Ref r : frames) { out.writeUTF(r.source); out.writeLong(r.time); out.writeInt(r.chunk); out.writeByte(r.frame); out.writeBoolean(r.closed); out.writeLong(r.expiresAt); }
        out.flush();
    }
    public static TrackIndex read(InputStream stream) throws IOException {
        DataInputStream in = new DataInputStream(new LimitedInputStream(stream, MAX_INDEX_BYTES));
        if (in.readInt() != MAGIC) throw new IOException("Unsupported screen track index");
        int count = in.readInt(); if (count < 0 || count > MAX_CHUNKS) throw new IOException("Screen chunk count limit");
        var chunks = new ArrayList<Chunk>(count);
        for (int i = 0; i < count; i++) chunks.add(new Chunk(i, in.readInt()));
        int length = in.readInt(); if (length < 0 || length > MAX_FRAMES) throw new IOException("Screen frame count limit");
        var refs = new ArrayList<Ref>(length);
        for (int i = 0; i < length; i++) refs.add(new Ref(in.readUTF(), in.readLong(), in.readInt(), in.readUnsignedByte(), in.readBoolean(), in.readLong()));
        if (in.read() != -1) throw new IOException("Trailing screen index bytes");
        try { return new TrackIndex(refs, chunks); } catch (IllegalArgumentException bad) { throw new IOException(bad.getMessage(), bad); }
    }
    static final class LimitedInputStream extends FilterInputStream {
        private long remaining;
        LimitedInputStream(InputStream in, long max) { super(in); remaining = max; }
        @Override public int read() throws IOException {
            int value = super.read(); if (value >= 0 && --remaining < 0) throw new IOException("Screen entry exceeds limit"); return value;
        }
        @Override public int read(byte[] data, int offset, int length) throws IOException {
            int n = super.read(data, offset, (int) Math.min(length, Math.max(1, remaining + 1)));
            if (n > 0 && (remaining -= n) < 0) throw new IOException("Screen entry exceeds limit"); return n;
        }
    }
}
