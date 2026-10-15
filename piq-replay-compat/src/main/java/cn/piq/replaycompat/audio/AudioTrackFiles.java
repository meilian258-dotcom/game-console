package cn.piq.replaycompat.audio;

import cn.piq.replaycompat.api.AudioFrame;
import com.replaymod.replaystudio.replay.ReplayFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import java.util.function.BooleanSupplier;

/** Versioned bounded PCM archive. Only ReplayFile owns the outer recording. */
final class AudioTrackFiles {
    static final String ROOT = "piq-audio/v1/", INDEX = ROOT + "index.bin", WARNING = ROOT + "warning.txt";
    static final int MAGIC = 0x50415131, CHUNK_MAGIC = 0x50414331;
    static final int MAX_CHUNKS = 30_000, MAX_FRAMES = 1_000_000, MAX_SOURCES = 256;
    static final int MAX_CHUNK_FRAMES = 64, MAX_CHUNK_PCM = 524_288, MAX_CHUNK_BYTES = 1_048_576;
    static final long MAX_BYTES = 512L * 1024 * 1024, MAX_RAW_BYTES = 1024L * 1024 * 1024;
    record Ref(int number, long fromMicros, long toMicros, int bytes, int rawBytes, int frames, long crc) {}
    record Index(List<Ref> refs) {
        Index { refs = List.copyOf(refs); }
        List<Ref> between(long fromMicros, long toMicros) throws IOException {
            List<Ref> selected = new ArrayList<>();
            for (Ref ref : refs) if (ref.fromMicros < toMicros && ref.toMicros > fromMicros) {
                if (selected.size() >= 128) throw new IOException("Excessive overlapping audio chunks");
                selected.add(ref);
            }
            return selected;
        }
    }
    record Summary(int chunks, int frames, int sources, long bytes, long rawBytes) {}
    private AudioTrackFiles() {}
    static String entry(int number) { return ROOT + String.format(Locale.ROOT, "%05d.pcmz", number); }
    static Index readIndex(ReplayFile file) throws IOException { return readIndex(file, () -> false); }
    static Index readIndex(ReplayFile file, BooleanSupplier cancelled) throws IOException {
        synchronized (file) {
            if (cancelled.getAsBoolean()) throw new InterruptedIOException("Audio reader closed");
            var warning = file.get(WARNING);
            if (warning.isPresent()) {
                try (InputStream in = warning.get()) {
                    if (in.readNBytes(4097).length > 4096) throw new IOException("Audio warning byte quota");
                }
                throw new IOException("Audio track marked incomplete; keep the original recording");
            }
            if (cancelled.getAsBoolean()) throw new InterruptedIOException("Audio reader closed");
            var found = file.get(INDEX);
            if (!found.isPresent()) return null;
            try (DataInputStream in = new DataInputStream(new BoundedInput(found.get(), 64 + MAX_CHUNKS * 48L))) {
                if (in.readInt() != MAGIC) throw new IOException("Unknown audio track version");
                int count = in.readInt();
                if (count < 0 || count > MAX_CHUNKS) throw new IOException("Audio index count");
                List<Ref> refs = new ArrayList<>(count);
                long bytes = 0, raw = 0, frames = 0;
                for (int i = 0; i < count; i++) {
                    Ref ref = new Ref(in.readInt(), in.readLong(), in.readLong(), in.readInt(), in.readInt(), in.readInt(), in.readLong());
                    if (ref.number != i || ref.fromMicros < 0 || ref.toMicros <= ref.fromMicros
                            || ref.toMicros > AudioFrame.MAX_TIME_MICROS || ref.bytes <= 0 || ref.bytes > MAX_CHUNK_BYTES
                            || ref.rawBytes <= 0 || ref.rawBytes > MAX_CHUNK_PCM
                            || ref.frames < 1 || ref.frames > MAX_CHUNK_FRAMES || ref.crc < 0 || ref.crc > 0xffff_ffffL)
                        throw new IOException("Invalid audio chunk reference");
                    bytes += ref.bytes; raw += ref.rawBytes; frames += ref.frames;
                    if (bytes > MAX_BYTES || raw > MAX_RAW_BYTES || frames > MAX_FRAMES) throw new IOException("Audio track quota");
                    refs.add(ref);
                }
                if (in.read() != -1) throw new IOException("Trailing audio index bytes");
                return new Index(refs);
            }
        }
    }
    static List<AudioFrame> readChunk(ReplayFile file, Ref ref) throws IOException { return readChunk(file, ref, () -> false); }
    static List<AudioFrame> readChunk(ReplayFile file, Ref ref, BooleanSupplier cancelled) throws IOException {
        byte[] encoded;
        synchronized (file) {
            if (cancelled.getAsBoolean()) throw new InterruptedIOException("Audio reader closed");
            var found = file.get(entry(ref.number));
            if (!found.isPresent()) throw new IOException("Missing audio chunk");
            try (InputStream in = found.get()) {
                encoded = in.readNBytes(ref.bytes + 1);
                if (encoded.length != ref.bytes) throw new IOException("Audio chunk length mismatch");
            }
        }
        if (cancelled.getAsBoolean()) throw new InterruptedIOException("Audio reader closed");
        CRC32 crc = new CRC32(); crc.update(encoded);
        if (crc.getValue() != ref.crc) throw new IOException("Audio chunk checksum mismatch");
        ByteArrayInputStream packed = new ByteArrayInputStream(encoded);
        Inflater inflater = new Inflater();
        try (DataInputStream in = new DataInputStream(new BoundedInput(new InflaterInputStream(packed, inflater), MAX_CHUNK_PCM + 16_384L))) {
            if (in.readInt() != CHUNK_MAGIC || in.readInt() != ref.frames) throw new IOException("Audio chunk header");
            List<AudioFrame> frames = new ArrayList<>(ref.frames); int raw = 0;
            long from = Long.MAX_VALUE, to = 0;
            for (int i = 0; i < ref.frames; i++) {
                if (cancelled.getAsBoolean()) throw new InterruptedIOException("Audio reader closed");
                String source = in.readUTF(); long start = in.readLong(); int rate = in.readInt(), channels = in.readUnsignedByte(), length = in.readInt();
                if (source.length() > 128 || length <= 0 || length > AudioFrame.MAX_PCM_BYTES || (long) raw + length > MAX_CHUNK_PCM)
                    throw new IOException("Audio PCM length/source quota");
                byte[] pcm = in.readNBytes(length);
                if (pcm.length != length) throw new EOFException("Truncated audio PCM");
                AudioFrame frame;
                try { frame = new AudioFrame(source, start, rate, channels, pcm); }
                catch (IllegalArgumentException invalid) { throw new IOException("Invalid audio PCM metadata", invalid); }
                raw += length; from = Math.min(from, start); to = Math.max(to, frame.endMicros()); frames.add(frame);
            }
            if (in.read() != -1 || raw != ref.rawBytes || from != ref.fromMicros || to != ref.toMicros)
                throw new IOException("Audio chunk/index identity mismatch");
            if (!inflater.finished() || inflater.getRemaining() != 0 || packed.available() != 0)
                throw new IOException("Trailing or incomplete compressed audio");
            return List.copyOf(frames);
        } finally { inflater.end(); }
    }
    static final class Writer {
        private final ReplayFile file;
        private final List<Ref> refs = new ArrayList<>();
        private final Set<String> sources = new HashSet<>();
        private long bytes, rawBytes; private int frameCount; private boolean finished;
        private Summary summary;
        Writer(ReplayFile file) { this.file = Objects.requireNonNull(file); }
        void append(List<AudioFrame> frames) throws IOException {
            if (frames.isEmpty()) return;
            if (frames.size() > MAX_CHUNK_FRAMES) throw new IOException("Audio batch frame quota");
            int raw = 0; long from = Long.MAX_VALUE, to = 0; Set<String> addedSources = new HashSet<>(sources);
            for (AudioFrame frame : frames) {
                raw = Math.addExact(raw, frame.byteCount()); from = Math.min(from, frame.startMicros());
                to = Math.max(to, frame.endMicros()); addedSources.add(frame.source());
            }
            if (raw > MAX_CHUNK_PCM || refs.size() >= MAX_CHUNKS || frameCount + frames.size() > MAX_FRAMES
                    || rawBytes + raw > MAX_RAW_BYTES || addedSources.size() > MAX_SOURCES) throw new IOException("Audio recording quota reached");
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(new DeflaterOutputStream(buffer))) {
                out.writeInt(CHUNK_MAGIC); out.writeInt(frames.size());
                for (AudioFrame frame : frames) {
                    out.writeUTF(frame.source()); out.writeLong(frame.startMicros()); out.writeInt(frame.sampleRate());
                    out.writeByte(frame.channels()); out.writeInt(frame.byteCount()); out.write(frame.pcm());
                }
            }
            if (buffer.size() > MAX_CHUNK_BYTES || bytes + buffer.size() > MAX_BYTES) throw new IOException("Audio compressed quota reached");
            byte[] encoded = buffer.toByteArray(); CRC32 crc = new CRC32(); crc.update(encoded);
            synchronized (file) {
                if (finished) return;
                int number = refs.size();
                try (OutputStream out = file.write(entry(number))) { out.write(encoded); }
                refs.add(new Ref(number, from, to, encoded.length, raw, frames.size(), crc.getValue()));
                sources.clear(); sources.addAll(addedSources); bytes += encoded.length; rawBytes += raw; frameCount += frames.size();
            }
        }
        Summary finish(long submitted, long dropped, String result) throws IOException {
            synchronized (file) {
                if (finished) {
                    if (summary == null) throw new IOException("Previous audio finalization failed");
                    return summary;
                }
                finished = true;
                try (DataOutputStream out = new DataOutputStream(file.write(INDEX))) {
                    out.writeInt(MAGIC); out.writeInt(refs.size());
                    for (Ref ref : refs) {
                        out.writeInt(ref.number); out.writeLong(ref.fromMicros); out.writeLong(ref.toMicros); out.writeInt(ref.bytes);
                        out.writeInt(ref.rawBytes); out.writeInt(ref.frames); out.writeLong(ref.crc);
                    }
                }
                String diagnostic = "format=piq-audio-v1\nresult=" + result + "\nsubmitted=" + submitted + "\ndropped=" + dropped
                        + "\nchunks=" + refs.size() + "\nframes=" + frameCount + "\nsources=" + sources.size() + "\nbytes=" + bytes + "\n";
                try (OutputStream out = file.write(ROOT + "diagnostic.txt")) { out.write(diagnostic.getBytes(StandardCharsets.UTF_8)); }
                if (!Set.of("complete", "empty_capture", "cut_mapped").contains(result)) {
                    try (OutputStream out = file.write(WARNING)) {
                        out.write("此次设备音轨不完整，回放将静音；原 Minecraft 录像仍保留，请检查音轨诊断并保留原始录像。".getBytes(StandardCharsets.UTF_8));
                    }
                }
                summary = new Summary(refs.size(), frameCount, sources.size(), bytes, rawBytes); return summary;
            }
        }
    }
    /** Reject decompression bombs without buffering an unbounded inflated stream. */
    private static final class BoundedInput extends FilterInputStream {
        private long remaining;
        BoundedInput(InputStream in, long maximum) { super(in); remaining = maximum; }
        @Override public int read() throws IOException {
            int value = in.read(); if (value != -1 && --remaining < 0) throw new IOException("Audio decode byte quota"); return value;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            if (length == 0) return 0;
            int read = in.read(bytes, offset, (int) Math.min(length, Math.max(1, remaining + 1)));
            if (read > 0 && (remaining -= read) < 0) throw new IOException("Audio decode byte quota"); return read;
        }
    }
}
