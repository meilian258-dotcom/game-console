package cn.piq.replaycompat;

import cn.piq.replaycompat.api.ScreenFrame;
import cn.piq.replaycompat.api.ScreenReplay;
import cn.piq.replaycompat.api.ScreenVertex;
import cn.piq.replaycompat.io.TrackCodec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.Adler32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/** Dependency-free behavior tests. Run in a fresh JVM with java -ea; no game or native code is loaded. */
public final class ContractTests {
    private static final int MAGIC = 0x50525131;
    private static final String SOURCE = "contract:screen/one";
    private static final String DIMENSION = "minecraft:overworld";
    private static int checks;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        boolean assertions = false;
        assert assertions = true;
        if (!assertions) throw new IllegalStateException("Run ContractTests with -ea");
        run("frame metadata bounds", ContractTests::metadataBounds);
        run("immutable owned snapshots", ContractTests::defensiveCopies);
        run("finite bounded world geometry", ContractTests::worldGeometry);
        run("byte roundtrip, close events and multiple frames", ContractTests::roundtrip);
        run("all truncations and trailing bytes", ContractTests::truncation);
        run("invalid wire metadata and counts", ContractTests::invalidWire);
        run("bounded decompression and compressed tails", ContractTests::decompressionBounds);
        run("compressed tail at inflater buffer boundary", ContractTests::bufferBoundaryTail);
        run("eight-frame chunk limit", ContractTests::chunkLimit);
        // These run last because the public service deliberately permits only one lifetime installation.
        run("optional API and playback isolation", ContractTests::apiLifecycle);
        System.out.println("CONTRACT_TESTS checks=" + checks + " failedGroups=" + failures.size());
        if (!failures.isEmpty()) throw new AssertionError(String.join("; ", failures));
    }

    private static void metadataBounds() throws Exception {
        ScreenFrame smallest = frame(SOURCE, 0);
        check(!smallest.closed(), "one-pixel frame is open");
        ScreenFrame largest = new ScreenFrame("s".repeat(192), "a:" + "b".repeat(126),
                ScreenFrame.MAX_TIME, 1024, 1024, new int[ScreenFrame.MAX_PIXELS],
                repeatedVertices(ScreenFrame.MAX_VERTICES));
        check(largest.width() == 1024 && largest.vertices().size() == 4096, "inclusive maxima");
        for (String source : new String[] {null, "", " ", "a\nb", "a\u0000b", "s".repeat(193)}) {
            reject(IllegalArgumentException.class,
                    () -> new ScreenFrame(source, DIMENSION, 0, 1, 1, new int[1], quad()), "bad source");
        }
        for (String dimension : new String[] {null, "", "overworld", "Minecraft:overworld", "a:b c", "a:" + "b".repeat(127)}) {
            reject(IllegalArgumentException.class,
                    () -> new ScreenFrame(SOURCE, dimension, 0, 1, 1, new int[1], quad()), "bad dimension");
        }
        for (long time : new long[] {-1, ScreenFrame.MAX_TIME + 1, Long.MAX_VALUE}) {
            reject(IllegalArgumentException.class,
                    () -> new ScreenFrame(SOURCE, DIMENSION, time, 1, 1, new int[1], quad()), "bad time");
        }
        for (int[] shape : new int[][] {{-1, 1}, {1, -1}, {1025, 1}, {1, 1025}, {Integer.MAX_VALUE, Integer.MAX_VALUE}}) {
            reject(IllegalArgumentException.class,
                    () -> new ScreenFrame(SOURCE, DIMENSION, 0, shape[0], shape[1], new int[0], quad()), "bad geometry");
        }
        reject(IllegalArgumentException.class, () -> new ScreenFrame(SOURCE, DIMENSION, 0, 1, 1, new int[0], quad()), "too few pixels");
        reject(IllegalArgumentException.class, () -> new ScreenFrame(SOURCE, DIMENSION, 0, 1, 1, new int[2], quad()), "too many pixels");
        reject(IllegalArgumentException.class, () -> new ScreenFrame(SOURCE, DIMENSION, 0, 1, 1, new int[1], List.of()), "open frame needs mesh");
        reject(IllegalArgumentException.class, () -> new ScreenFrame(SOURCE, DIMENSION, 0, 1, 1, new int[1], repeatedVertices(3)), "partial quad");
        reject(IllegalArgumentException.class, () -> new ScreenFrame(SOURCE, DIMENSION, 0, 1, 1, new int[1], repeatedVertices(4100)), "too many vertices");
        reject(NullPointerException.class, () -> new ScreenFrame(SOURCE, DIMENSION, 0, 1, 1, null, quad()), "null pixels");
        reject(NullPointerException.class, () -> new ScreenFrame(SOURCE, DIMENSION, 0, 1, 1, new int[1], null), "null mesh");
        List<ScreenVertex> nullVertex = new ArrayList<>(quad()); nullVertex.set(0, null);
        reject(NullPointerException.class, () -> new ScreenFrame(SOURCE, DIMENSION, 0, 1, 1, new int[1], nullVertex), "null vertex");
        for (int[] shape : new int[][] {{0, 1}, {1, 0}}) {
            reject(IllegalArgumentException.class,
                    () -> new ScreenFrame(SOURCE, DIMENSION, 0, shape[0], shape[1], new int[0], List.of()), "partial close dimensions");
        }
        reject(IllegalArgumentException.class, () -> new ScreenFrame(SOURCE, DIMENSION, 0, 0, 0, new int[0], quad()), "closed frame has no mesh");
        ScreenFrame closed = ScreenFrame.closed(SOURCE, DIMENSION, ScreenFrame.MAX_TIME);
        check(closed.closed() && closed.abgr().length == 0 && closed.vertices().isEmpty(), "close event");
        reject(IllegalArgumentException.class, () -> smallest.at(-1), "retiming is validated");
    }

    private static void defensiveCopies() throws Exception {
        int[] pixels = {0x12abcdef};
        List<ScreenVertex> mesh = new ArrayList<>(quad());
        ScreenFrame original = new ScreenFrame(SOURCE, DIMENSION, 1, 1, 1, pixels, mesh);
        pixels[0] = 0; mesh.clear();
        check(original.abgr()[0] == 0x12abcdef && original.vertices().size() == 4, "constructor copies callers");
        int[] exposed = original.abgr(); exposed[0] = 1;
        check(original.abgr()[0] == 0x12abcdef, "pixel accessor returns a copy");
        reject(UnsupportedOperationException.class, () -> original.vertices().clear(), "mesh accessor is immutable");
        ScreenFrame retimed = original.at(500);
        check(retimed.timestampMillis() == 500 && original.timestampMillis() == 1, "retime does not mutate original");
        check(Arrays.equals(original.abgr(), retimed.abgr()) && original.vertices().equals(retimed.vertices()), "retime preserves presentation");
        int[] retimedPixels = retimed.abgr(); retimedPixels[0] = 2;
        check(original.abgr()[0] == 0x12abcdef && retimed.abgr()[0] == 0x12abcdef, "retimed pixels remain owned");
    }

    private static void worldGeometry() throws Exception {
        ScreenVertex min = new ScreenVertex(-30_000_000, -4096, -30_000_000, -16, -16, 0);
        ScreenVertex max = new ScreenVertex(30_000_000, 4096, 30_000_000, 16, 16, -1);
        check(min.x() == -30_000_000 && max.v() == 16, "inclusive coordinate and UV bounds");
        for (double invalid : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 30_000_000.1, -30_000_000.1}) {
            reject(IllegalArgumentException.class, () -> new ScreenVertex(invalid, 0, 0, 0, 0, 0), "invalid x");
            reject(IllegalArgumentException.class, () -> new ScreenVertex(0, 0, invalid, 0, 0, 0), "invalid z");
        }
        for (double invalid : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 4096.1, -4096.1}) {
            reject(IllegalArgumentException.class, () -> new ScreenVertex(0, invalid, 0, 0, 0, 0), "invalid y");
        }
        for (float invalid : new float[] {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 16.01f, -16.01f}) {
            reject(IllegalArgumentException.class, () -> new ScreenVertex(0, 0, 0, invalid, 0, 0), "invalid u");
            reject(IllegalArgumentException.class, () -> new ScreenVertex(0, 0, 0, 0, invalid, 0), "invalid v");
        }
    }

    private static void roundtrip() throws Exception {
        int[] colors = {0x00000000, 0x11223344, 0xff0000ff, 0xff00ff00, 0xffff0000, 0xffffffff};
        List<ScreenFrame> originals = List.of(
                new ScreenFrame("contract:screen/彩色", DIMENSION, 0, 3, 2, colors, quad()),
                new ScreenFrame("contract:screen/second", "minecraft:the_nether", 99, 1, 1, new int[] {Integer.MIN_VALUE}, quad()),
                frame("contract:screen/彩色", 100),
                ScreenFrame.closed("contract:screen/second", "minecraft:the_nether", 101),
                ScreenFrame.closed("contract:screen/彩色", DIMENSION, ScreenFrame.MAX_TIME));
        byte[] bytes = encode(originals);
        List<ScreenFrame> decoded = TrackCodec.read(new ByteArrayInputStream(bytes));
        check(decoded.size() == originals.size(), "multiple frame count preserved");
        for (int i = 0; i < decoded.size(); i++) equalFrame(originals.get(i), decoded.get(i));
        check(Arrays.equals(bytes, encode(decoded)), "encoded bytes roundtrip identically");
        reject(UnsupportedOperationException.class, () -> decoded.clear(), "decoded list is immutable");
        check(TrackCodec.read(new ByteArrayInputStream(encode(List.of()))).isEmpty(), "empty chunk roundtrip");
        ScreenFrame max = new ScreenFrame(SOURCE, DIMENSION, 1, 1024, 1024, new int[ScreenFrame.MAX_PIXELS], quad());
        equalFrame(max, TrackCodec.read(new ByteArrayInputStream(encode(List.of(max)))).getFirst());
    }

    private static void truncation() throws Exception {
        byte[] complete = encode(List.of(frame(SOURCE, 0), ScreenFrame.closed(SOURCE, DIMENSION, 100)));
        for (int length = 0; length < complete.length; length++) {
            final byte[] truncated = Arrays.copyOf(complete, length);
            reject(IOException.class, () -> TrackCodec.read(new ByteArrayInputStream(truncated)), "truncated at byte " + length);
        }
        byte[] tail = Arrays.copyOf(complete, complete.length + 1);
        reject(IOException.class, () -> TrackCodec.read(new ByteArrayInputStream(tail)), "trailing chunk data");
    }

    private static void invalidWire() throws Exception {
        rejectRead(header(0x50525132, 0), "unknown version");
        for (int count : new int[] {-1, 9, Integer.MAX_VALUE}) rejectRead(header(MAGIC, count), "invalid count");
        for (int[] shape : new int[][] {{-1, 1}, {1, -1}, {1025, 1}, {1, 1025}, {Integer.MAX_VALUE, Integer.MAX_VALUE}}) {
            rejectRead(raw(SOURCE, DIMENSION, 0, shape[0], shape[1], 4, vertexBytes(quad()), deflate(new byte[4])), "bad wire dimensions");
        }
        for (int count : new int[] {-1, 1, 3, 4097, 4100, Integer.MAX_VALUE}) {
            rejectRead(raw(SOURCE, DIMENSION, 0, 1, 1, count, new byte[0], deflate(new byte[4])), "bad wire vertex count");
        }
        for (long time : new long[] {-1, ScreenFrame.MAX_TIME + 1}) {
            rejectRead(raw(SOURCE, DIMENSION, time, 1, 1, 4, vertexBytes(quad()), deflate(new byte[4])), "bad wire time");
        }
        for (String source : new String[] {"", " ", "a\nb", "s".repeat(193)}) {
            rejectRead(raw(source, DIMENSION, 0, 1, 1, 4, vertexBytes(quad()), deflate(new byte[4])), "bad wire source");
        }
        rejectRead(raw(SOURCE, "invalid", 0, 1, 1, 4, vertexBytes(quad()), deflate(new byte[4])), "bad wire dimension");
        rejectRead(raw(SOURCE, DIMENSION, 0, 1, 0, 0, new byte[0], deflate(new byte[0])), "incomplete wire close");
        rejectRead(raw(SOURCE, DIMENSION, 0, 1, 1, 0, new byte[0], deflate(new byte[4])), "wire open frame without mesh");
        rejectRead(raw(SOURCE, DIMENSION, 0, 0, 0, 4, vertexBytes(quad()), deflate(new byte[0])), "wire close with mesh");
        for (int offset : new int[] {0, 8, 16, 24, 28}) {
            byte[] mesh = vertexBytes(quad());
            java.nio.ByteBuffer invalid = java.nio.ByteBuffer.wrap(mesh);
            if (offset < 24) invalid.putDouble(offset, Double.NaN); else invalid.putFloat(offset, Float.POSITIVE_INFINITY);
            rejectRead(raw(SOURCE, DIMENSION, 0, 1, 1, 4, mesh, deflate(new byte[4])), "nonfinite wire vertex");
        }
        byte[] valid = raw(SOURCE, DIMENSION, 0, 1, 1, 4, vertexBytes(quad()), deflate(new byte[4]));
        int compressedSizeOffset = valid.length - deflate(new byte[4]).length - 4;
        for (int length : new int[] {-1, ScreenFrame.MAX_PIXELS * 4 + 8193, Integer.MAX_VALUE}) {
            byte[] corrupt = valid.clone(); java.nio.ByteBuffer.wrap(corrupt).putInt(compressedSizeOffset, length);
            rejectRead(corrupt, "invalid compressed length");
        }
    }

    private static void decompressionBounds() throws Exception {
        rejectRead(raw(SOURCE, DIMENSION, 0, 1, 1, 4, vertexBytes(quad()), deflate(new byte[3])), "short decompressed pixels");
        rejectRead(raw(SOURCE, DIMENSION, 0, 1, 1, 4, vertexBytes(quad()), deflate(new byte[5])), "excess decompressed pixels");
        rejectRead(raw(SOURCE, DIMENSION, 0, 0, 0, 0, new byte[0], deflate(new byte[1])), "close event carries no pixel bytes");
        rejectRead(raw(SOURCE, DIMENSION, 0, 1, 1, 4, vertexBytes(quad()), new byte[0]), "empty compressed input");
        rejectRead(raw(SOURCE, DIMENSION, 0, 1, 1, 4, vertexBytes(quad()), new byte[] {1, 2, 3, 4}), "invalid zlib input");
        byte[] zipped = deflate(new byte[4]);
        rejectRead(raw(SOURCE, DIMENSION, 0, 1, 1, 4, vertexBytes(quad()), Arrays.copyOf(zipped, zipped.length - 1)), "truncated zlib checksum");
        byte[] corrupt = zipped.clone(); corrupt[corrupt.length - 1] ^= 1;
        rejectRead(raw(SOURCE, DIMENSION, 0, 1, 1, 4, vertexBytes(quad()), corrupt), "bad zlib checksum");
        rejectRead(raw(SOURCE, DIMENSION, 0, 1, 1, 4, vertexBytes(quad()), Arrays.copyOf(zipped, zipped.length + 1000)), "compressed trailing data");
        byte[] concat = new byte[zipped.length * 2];
        System.arraycopy(zipped, 0, concat, 0, zipped.length); System.arraycopy(zipped, 0, concat, zipped.length, zipped.length);
        rejectRead(raw(SOURCE, DIMENSION, 0, 1, 1, 4, vertexBytes(quad()), concat), "concatenated zlib streams");
        ByteArrayOutputStream bomb = new ByteArrayOutputStream();
        try (var zippedBomb = new DeflaterOutputStream(bomb)) {
            byte[] block = new byte[8192];
            for (int i = 0; i < 4096; i++) zippedBomb.write(block);
        }
        check(bomb.size() < 64 * 1024, "32 MiB expansion fixture is compact");
        rejectRead(raw(SOURCE, DIMENSION, 0, 1, 1, 4, vertexBytes(quad()), bomb.toByteArray()), "32 MiB expansion into one pixel is rejected");
    }

    private static void bufferBoundaryTail() throws Exception {
        // This zlib stream ends exactly at InflaterInputStream's 512-byte boundary.
        // Tail rejection must not depend on bytes happening to have entered its internal buffer.
        byte[] pixels = new byte[124 * 4];
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(0x78); out.writeByte(0x01); // RFC 1950 zlib header, no dictionary.
        out.write(new byte[] {0, 0, 0, (byte) 255, (byte) 255}); // Non-final empty stored block.
        out.writeByte(1); // Final stored block; LEN/NLEN are little-endian.
        out.writeByte(pixels.length & 255); out.writeByte(pixels.length >>> 8);
        out.writeByte((~pixels.length) & 255); out.writeByte(((~pixels.length) >>> 8) & 255);
        out.write(pixels);
        Adler32 checksum = new Adler32(); checksum.update(pixels); out.writeInt((int) checksum.getValue());
        byte[] compressed = bytes.toByteArray();
        check(compressed.length == 512, "deterministic aligned zlib length");
        byte[] valid = raw(SOURCE, DIMENSION, 0, 124, 1, 4, vertexBytes(quad()), compressed);
        check(TrackCodec.read(new ByteArrayInputStream(valid)).getFirst().width() == 124, "aligned zlib fixture valid");
        byte[] tailed = Arrays.copyOf(compressed, compressed.length + 1); tailed[tailed.length - 1] = 42;
        rejectRead(raw(SOURCE, DIMENSION, 0, 124, 1, 4, vertexBytes(quad()), tailed), "aligned compressed tail");
    }

    private static void chunkLimit() throws Exception {
        List<ScreenFrame> eight = new ArrayList<>();
        for (int i = 0; i < 8; i++) eight.add(frame("contract:screen/" + i, i));
        check(TrackCodec.MAX_CHUNK_FRAMES == 8, "public chunk cap");
        check(TrackCodec.read(new ByteArrayInputStream(encode(eight))).size() == 8, "eight frames accepted");
        eight.add(frame("contract:screen/nine", 9));
        reject(IOException.class, () -> encode(eight), "nine-frame write refused");
        rejectRead(header(MAGIC, 9), "nine-frame read refused before allocation");
    }

    private static void apiLifecycle() throws Exception {
        check(!ScreenReplay.recording() && !ScreenReplay.playing() && ScreenReplay.recordingTime() == 0, "uninstalled state");
        ScreenReplay.submit(null); ScreenReplay.close(null, -1);
        check(ScreenReplay.at(100).isEmpty(), "uninstalled service is a no-op");
        reject(IllegalArgumentException.class, () -> ScreenReplay.register("contract:version", 0), "unknown old API");
        reject(IllegalArgumentException.class, () -> ScreenReplay.register("contract:version", 2), "unknown new API");
        for (String id : new String[] {null, "", "missing_namespace", "Upper:case", "a:b c", "a:" + "b".repeat(95)}) {
            reject(IllegalArgumentException.class, () -> ScreenReplay.register(id, ScreenReplay.API_VERSION), "invalid provider");
        }
        var publisher = ScreenReplay.register("contract:publisher", ScreenReplay.API_VERSION);
        String maximumProvider = "c:" + "b".repeat(94);
        check(ScreenReplay.register(maximumProvider, 1).id().equals(maximumProvider), "96-character provider accepted");
        check(!publisher.requested() && publisher.id().equals("contract:publisher"), "provider idle before install");
        publisher.frame(null, null, -1, -1, null, null);
        publisher.close("idle");
        reject(IllegalStateException.class, () -> ScreenReplay.register("contract:publisher", 1), "duplicate provider");
        FakeService service = new FakeService();
        reject(NullPointerException.class, () -> ScreenReplay.install(null), "null service");
        ScreenReplay.install(service); ScreenReplay.install(service);
        reject(IllegalStateException.class, () -> ScreenReplay.install(new FakeService()), "second service owner");
        check(!publisher.requested(), "idle installed provider");
        ScreenReplay.submit(null); check(service.frames.isEmpty(), "idle submission is a no-op");
        service.recording = true; service.time = 1234;
        check(publisher.requested() && ScreenReplay.recordingTime() == 1234, "recording request clock");
        int[] pixels = {0xabcdef01};
        publisher.frame("surface", DIMENSION, 1, 1, pixels, quad()); pixels[0] = 0;
        check(service.frames.size() == 1, "publisher submits once");
        ScreenFrame posted = service.frames.getFirst();
        check(posted.source().startsWith(publisher.id()) && posted.source().endsWith("surface"), "source belongs to provider");
        check(posted.timestampMillis() == 1234 && posted.abgr()[0] == 0xabcdef01, "publisher copies and timestamps");
        publisher.close("surface");
        check(service.closed.equals(List.of(posted.source())) && service.closeTime == 1234, "provider close owns identical source");
        for (String local : new String[] {null, "", " ", "a/b", "a\nb", "a\u0000b", "a".repeat(91)}) {
            reject(IllegalArgumentException.class, () -> publisher.frame(local, DIMENSION, 1, 1, new int[1], quad()), "invalid local source");
            reject(IllegalArgumentException.class, () -> publisher.close(local), "invalid local close source");
        }
        publisher.frame("a".repeat(90), DIMENSION, 1, 1, new int[1], quad());
        check(service.frames.getLast().source().endsWith("a".repeat(90)), "90-character local source accepted");
        reject(NullPointerException.class, () -> ScreenReplay.submit(null), "active null submission rejected");
        check(ScreenReplay.at(1234).isEmpty() && service.atCalls == 0, "recording cannot read playback frames");
        service.playing = true;
        int before = service.frames.size(), closedBefore = service.closed.size();
        ScreenReplay.submit(frame(SOURCE, 2)); ScreenReplay.close(SOURCE, 2);
        publisher.frame("surface", DIMENSION, 1, 1, new int[1], quad()); publisher.close("surface");
        check(!publisher.requested() && service.frames.size() == before && service.closed.size() == closedBefore,
                "playback never re-records even when service recording flag is true");
        List<ScreenFrame> playback = ScreenReplay.at(222);
        check(service.atCalls == 1 && service.atTime == 222 && playback.size() == 1, "playback reads correct clock");
        service.playback.clear(); check(playback.size() == 1, "playback list is copied");
        reject(UnsupportedOperationException.class, playback::clear, "playback list immutable");
        service.recording = false; ScreenReplay.submit(null);
        check(service.frames.size() == before, "playback-only submits are no-op");
        service.playing = false; service.recording = true;
        providerOwnership(service);
        // Include pre-registered providers without relying on a reset-only test hook in production.
        int accepted = 0;
        for (int i = 0; i < 129; i++) {
            try { ScreenReplay.register("contract:quota" + i, 1); accepted++; }
            catch (IllegalStateException full) { break; }
        }
        check(accepted == 124, "exactly 128 providers including the four earlier registrations");
        reject(IllegalStateException.class, () -> ScreenReplay.register("contract:over_limit", 1), "full provider registry rejects extra owner");
    }

    private static void providerOwnership(FakeService service) throws Exception {
        var parent = ScreenReplay.register("contract:owner", 1);
        var child = ScreenReplay.register("contract:owner/child", 1);
        child.frame("screen", DIMENSION, 1, 1, new int[1], quad());
        String childSource = service.frames.getLast().source();
        int before = service.frames.size();
        try { parent.frame("child/screen", DIMENSION, 1, 1, new int[1], quad()); }
        catch (IllegalArgumentException safeRejection) { check(service.frames.size() == before, "ambiguous local id rejected before publication"); return; }
        check(!service.frames.getLast().source().equals(childSource), "different providers cannot claim same source");
    }

    private static ScreenFrame frame(String source, long time) {
        return new ScreenFrame(source, DIMENSION, time, 1, 1, new int[] {0xff112233}, quad());
    }
    private static List<ScreenVertex> quad() {
        return List.of(new ScreenVertex(-0.25, 64.5, 1.75, 0, 0, 0x11223344),
                new ScreenVertex(0.75, 64.5, 1.75, 1, 0, 0xff0000ff),
                new ScreenVertex(0.75, 65.5, 1.75, 1, 1, 0xff00ff00),
                new ScreenVertex(-0.25, 65.5, 1.75, 0, 1, 0xffff0000));
    }
    private static List<ScreenVertex> repeatedVertices(int size) {
        List<ScreenVertex> result = new ArrayList<>(size);
        for (int i = 0; i < size; i++) result.add(quad().get(i % 4));
        return result;
    }
    private static byte[] encode(List<ScreenFrame> frames) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); TrackCodec.write(out, frames); return out.toByteArray();
    }
    private static byte[] header(int magic, int count) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes); out.writeInt(magic); out.writeInt(count); return bytes.toByteArray();
    }
    private static byte[] vertexBytes(List<ScreenVertex> vertices) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
        for (ScreenVertex v : vertices) { out.writeDouble(v.x()); out.writeDouble(v.y()); out.writeDouble(v.z()); out.writeFloat(v.u()); out.writeFloat(v.v()); out.writeInt(v.color()); }
        return bytes.toByteArray();
    }
    private static byte[] raw(String source, String dimension, long time, int width, int height,
                              int vertexCount, byte[] mesh, byte[] compressed) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(MAGIC); out.writeInt(1); out.writeUTF(source); out.writeUTF(dimension); out.writeLong(time);
        out.writeInt(width); out.writeInt(height); out.writeInt(vertexCount); out.write(mesh);
        out.writeInt(compressed.length); out.write(compressed); return bytes.toByteArray();
    }
    private static byte[] deflate(byte[] plain) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        try (var out = new DeflaterOutputStream(bytes, deflater)) { out.write(plain); } finally { deflater.end(); }
        return bytes.toByteArray();
    }
    private static void equalFrame(ScreenFrame expected, ScreenFrame actual) {
        check(expected.source().equals(actual.source()) && expected.dimension().equals(actual.dimension())
                && expected.timestampMillis() == actual.timestampMillis() && expected.width() == actual.width()
                && expected.height() == actual.height(), "frame metadata preserved");
        check(Arrays.equals(expected.abgr(), actual.abgr()), "all ABGR bytes preserved without channel or endian swap");
        check(expected.vertices().equals(actual.vertices()), "world coordinates, final UV and tint preserved");
        check(expected.closed() == actual.closed(), "close status preserved");
    }
    private static void rejectRead(byte[] bytes, String message) throws Exception {
        reject(IOException.class, () -> TrackCodec.read(new ByteArrayInputStream(bytes)), message);
    }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
    private static void reject(Class<? extends Throwable> type, Checked action, String message) throws Exception {
        checks++;
        try { action.run(); } catch (Throwable failure) {
            if (type.isInstance(failure)) return;
            throw new AssertionError(message + ": expected " + type.getSimpleName() + ", got " + failure, failure);
        }
        throw new AssertionError(message + ": expected " + type.getSimpleName());
    }
    private static void run(String name, Checked action) throws Exception {
        try { action.run(); System.out.println("PASS " + name); }
        catch (Exception | AssertionError failure) { failures.add(name + ": " + failure); System.out.println("FAIL " + name + ": " + failure); }
    }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
    private static final class FakeService implements ScreenReplay.Service {
        boolean recording, playing; long time, closeTime, atTime; int atCalls;
        final List<ScreenFrame> frames = new ArrayList<>();
        final List<String> closed = new ArrayList<>();
        final List<ScreenFrame> playback = new ArrayList<>(List.of(frame(SOURCE, 222)));
        public boolean recording() { return recording; }
        public boolean playing() { return playing; }
        public long recordingTime() { return time; }
        public void submit(ScreenFrame frame) { frames.add(frame); }
        public void close(String source, long time) { closed.add(source); closeTime = time; }
        public List<ScreenFrame> at(long time) { atCalls++; atTime = time; return playback; }
    }
}
