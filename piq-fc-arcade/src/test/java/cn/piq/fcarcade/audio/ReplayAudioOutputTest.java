package cn.piq.fcarcade.audio;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.SourceDataLine;

/** Stub audio device: never opens host audio, a core, or a Minecraft class. */
public final class ReplayAudioOutputTest {
    private static int assertions;

    @Test void acceptedBytesAndIdentity() {
        Sink sink = new Sink(); Device device = new Device();
        byte[] bytes = {99, 98, 1, 2, 3, 4, 5, 6, 77, 76};
        byte[] before = bytes.clone(); device.written = 4;
        equal(4, ReplayAudioOutput.write(sink, device.line, bytes, 2, 6));
        equal(1, device.writes); same(bytes, device.input);
        equal(2, device.offset); equal(6, device.length);
        equal(1, sink.chunks.size()); same(device.line, sink.chunks.getFirst().source());
        check(Arrays.equals(new byte[]{1, 2, 3, 4}, sink.chunks.getFirst().bytes()));
        check(Arrays.equals(before, bytes));
        equal(48_000, sink.chunks.getFirst().rate()); equal(2, sink.chunks.getFirst().channels());

        device.written = 2; device.format = new AudioFormat(44_100, 16, 1, true, false);
        equal(2, ReplayAudioOutput.write(sink, device.line, bytes, 1, 2));
        // Offset is an array address, not a frame count: an odd offset is legal.
        check(Arrays.equals(new byte[]{98, 1}, sink.chunks.getLast().bytes()));
        same(sink.chunks.getFirst().source(), sink.chunks.getLast().source());
        Device other = new Device(); other.written = 4;
        ReplayAudioOutput.write(sink, other.line, bytes, 0, 4);
        check(sink.chunks.getLast().source() != sink.chunks.getFirst().source());
    }

    @Test void zeroInvalidAndOriginalFailures() {
        Sink sink = new Sink(); Device device = new Device(); byte[] bytes = new byte[8];
        for (int count : new int[]{0, -1, 9, 3}) {
            device.written = count;
            equal(count, ReplayAudioOutput.write(sink, device.line, bytes, 0, 8));
        }
        equal(0, sink.chunks.size()); equal(4, device.writes);
        device.written = 4;
        equal(4, ReplayAudioOutput.write(sink, device.line, bytes, 7, 8));
        equal(4, ReplayAudioOutput.write(sink, device.line, null, 0, 4));
        equal(0, sink.chunks.size());
        IllegalArgumentException failure = new IllegalArgumentException("device sentinel");
        device.writeFailure = failure;
        same(failure, thrown(() -> ReplayAudioOutput.write(sink, device.line, bytes, 0, 8)));
        equal(7, device.writes); equal(0, sink.chunks.size());
        AssertionError fatal = new AssertionError("device error sentinel"); device.writeFailure = fatal;
        same(fatal, thrown(() -> ReplayAudioOutput.write(sink, device.line, bytes, 0, 8)));
    }

    @Test void recordingAndFormatBoundaries() {
        Sink sink = new Sink(); Device device = new Device(); byte[] bytes = new byte[4];
        sink.recording = false;
        equal(4, ReplayAudioOutput.write(sink, device.line, bytes, 0, 4));
        equal(0, device.formats); equal(0, sink.chunks.size());
        sink.recording = true;
        for (AudioFormat format : List.of(
                new AudioFormat(8_000, 16, 1, true, false),
                new AudioFormat(96_000, 16, 2, true, false),
                new AudioFormat(44_100, 16, 2, true, false))) {
            check(ReplayAudioOutput.supported(format)); device.format = format;
            equal(4, ReplayAudioOutput.write(sink, device.line, bytes, 0, 4));
        }
        equal(3, sink.chunks.size());
        List<AudioFormat> rejected = new ArrayList<>(List.of(
                new AudioFormat(7_999, 16, 1, true, false),
                new AudioFormat(96_001, 16, 1, true, false),
                new AudioFormat(44_100.5f, 16, 1, true, false),
                new AudioFormat(Float.NaN, 16, 1, true, false),
                new AudioFormat(48_000, 16, 3, true, false),
                new AudioFormat(48_000, 8, 1, true, false),
                new AudioFormat(48_000, 16, 1, false, false),
                new AudioFormat(48_000, 16, 1, true, true),
                new AudioFormat(AudioFormat.Encoding.PCM_FLOAT, 48_000, 16, 1, 2, 48_000, false),
                new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 48_000, 16, 2, 8, 48_000, false),
                new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 48_000, 16, 2, 4, 44_100, false)));
        rejected.add(null);
        for (AudioFormat format : rejected) {
            check(!ReplayAudioOutput.supported(format)); device.format = format;
            equal(4, ReplayAudioOutput.write(sink, device.line, bytes, 0, 4));
        }
        equal(3, sink.chunks.size());
    }

    @Test void captureFailuresAreIsolated() {
        Sink sink = new Sink(); Device device = new Device(); byte[] bytes = new byte[4];
        sink.tokenFailure = true;
        equal(4, ReplayAudioOutput.write(sink, device.line, bytes, 0, 4));
        equal(0, device.formats); sink.tokenFailure = false;
        device.formatFailure = true;
        equal(4, ReplayAudioOutput.write(sink, device.line, bytes, 0, 4));
        device.formatFailure = false; sink.captureFailure = true;
        equal(4, ReplayAudioOutput.write(sink, device.line, bytes, 0, 4));
        equal(3, device.writes); equal(0, sink.chunks.size());
    }

    @Test void replacementAndResetRaces() {
        Sink sink = new Sink(); Device device = new Device(); byte[] bytes = new byte[4];
        device.duringWrite = () -> sink.session = new Object();
        equal(4, ReplayAudioOutput.write(sink, device.line, bytes, 0, 4)); equal(0, sink.chunks.size());
        device.duringWrite = () -> ReplayAudioOutput.flush(sink, device.line);
        equal(4, ReplayAudioOutput.write(sink, device.line, bytes, 0, 4)); equal(0, sink.chunks.size());
        equal(List.of("reset", "flush"), device.eventsWith(sink));
        device.duringWrite = null;
        equal(4, ReplayAudioOutput.write(sink, device.line, bytes, 0, 4)); equal(1, sink.chunks.size());
    }

    @Test void recordingStartAndStopDuringWriteCannotMovePcmAcrossTheBoundary() {
        Sink sink = new Sink(); Device device = new Device(); byte[] bytes = new byte[4];
        sink.recording = false;
        device.duringWrite = () -> sink.recording = true;
        equal(4, ReplayAudioOutput.write(sink, device.line, bytes, 0, 4));
        equal(0, sink.chunks.size()); equal(0, device.formats);
        device.duringWrite = () -> sink.recording = false;
        equal(4, ReplayAudioOutput.write(sink, device.line, bytes, 0, 4));
        equal(0, sink.chunks.size());
        sink.recording = true; device.duringWrite = null;
        equal(4, ReplayAudioOutput.write(sink, device.line, bytes, 0, 4));
        equal(1, sink.chunks.size());
    }

    @Test void publicFacadeRouteWithoutAConsumerPreservesLiveWriteAndLifecycle() {
        // Load the real helper + API without the installed consumer of any other test.
        ClassLoader isolated = new ClassLoader(ClassLoader.getPlatformClassLoader()) {
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                if (!name.startsWith("cn.piq.fcarcade.audio.ReplayAudioOutput")
                        && !name.startsWith("cn.piq.retro.replay.ReplayAudio")) throw new ClassNotFoundException(name);
                try (var input = ReplayAudioOutputTest.class.getResourceAsStream("/" + name.replace('.', '/') + ".class")) {
                    if (input == null) throw new ClassNotFoundException(name);
                    byte[] bytes = input.readAllBytes(); return defineClass(name, bytes, 0, bytes.length);
                } catch (java.io.IOException failure) { throw new ClassNotFoundException(name, failure); }
            }
        };
        try {
            Class<?> output = isolated.loadClass("cn.piq.fcarcade.audio.ReplayAudioOutput");
            Device device = new Device(); byte[] pcm = {1, 2, 3, 4};
            equal(4, output.getMethod("write", SourceDataLine.class, byte[].class, int.class, int.class)
                    .invoke(null, device.line, pcm, 0, 4));
            equal(1, device.writes); same(pcm, device.input); equal(0, device.formats);
            output.getMethod("flush", SourceDataLine.class).invoke(null, device.line);
            output.getMethod("close", SourceDataLine.class).invoke(null, device.line);
            equal(1, device.flushes); equal(1, device.closes);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    @Test void closeUnblocksAnOldWrite() throws Exception {
        Sink sink = new Sink(); Device device = new Device();
        CountDownLatch entered = new CountDownLatch(1), released = new CountDownLatch(1);
        device.duringWrite = () -> { entered.countDown(); await(released); };
        device.duringClose = () -> { same(null, sink.token(device.line)); released.countDown(); };
        AtomicReference<Throwable> failed = new AtomicReference<>();
        Thread writer = Thread.ofPlatform().daemon().start(() -> {
            try { equal(4, ReplayAudioOutput.write(sink, device.line, new byte[4], 0, 4)); }
            catch (Throwable failure) { failed.set(failure); }
        });
        check(entered.await(2, TimeUnit.SECONDS));
        ReplayAudioOutput.close(sink, device.line);
        writer.join(2_000); check(!writer.isAlive()); same(null, failed.get());
        equal(0, sink.chunks.size()); equal(1, device.closes);
    }

    @Test void lifecyclePreservesOriginalErrors() {
        Sink sink = new Sink(); Device device = new Device();
        sink.lifecycleFailure = true;
        ReplayAudioOutput.flush(sink, device.line); ReplayAudioOutput.close(sink, device.line);
        equal(1, device.flushes); equal(1, device.closes);
        RuntimeException failure = new RuntimeException("line lifecycle sentinel");
        device.lifecycleFailure = failure;
        same(failure, thrown(() -> ReplayAudioOutput.flush(sink, device.line)));
        same(failure, thrown(() -> ReplayAudioOutput.close(sink, device.line)));
        equal(2, device.flushes); equal(2, device.closes);
    }

    private record Chunk(Object source, byte[] bytes, int rate, int channels) {}
    private record Token(Object session, Object source, Object epoch) {}
    private static final class Sink implements ReplayAudioOutput.Sink {
        final IdentityHashMap<Object, Object> epochs = new IdentityHashMap<>();
        final IdentityHashMap<Object, Boolean> closed = new IdentityHashMap<>();
        final List<Chunk> chunks = new ArrayList<>();
        final List<String> events = new ArrayList<>();
        Object session = new Object(); boolean recording = true;
        boolean tokenFailure, captureFailure, lifecycleFailure;
        public synchronized Object token(Object source) {
            if (tokenFailure) throw new AssertionError("optional token failure");
            if (!recording || closed.containsKey(source)) return null;
            return new Token(session, source, epochs.computeIfAbsent(source, key -> new Object()));
        }
        public synchronized void capture(Object value, Object source, byte[] data, int offset, int length, int rate, int channels) {
            if (captureFailure) throw new AssertionError("optional capture failure");
            Token token = (Token) value;
            if (recording && token.session == session && token.source == source
                    && token.epoch == epochs.get(source) && !closed.containsKey(source))
                chunks.add(new Chunk(source, Arrays.copyOfRange(data, offset, offset + length), rate, channels));
        }
        public synchronized void reset(Object source) {
            if (lifecycleFailure) throw new AssertionError("optional reset failure");
            epochs.put(source, new Object()); events.add("reset");
        }
        public synchronized void close(Object source) {
            if (lifecycleFailure) throw new AssertionError("optional close failure");
            epochs.put(source, new Object()); closed.put(source, true); events.add("close");
        }
    }

    private static final class Device {
        AudioFormat format = new AudioFormat(48_000, 16, 2, true, false);
        int written = 4, writes, formats, flushes, closes, offset, length;
        byte[] input; Throwable writeFailure, lifecycleFailure; boolean formatFailure;
        Runnable duringWrite, duringClose;
        final List<String> events = new ArrayList<>();
        final SourceDataLine line = (SourceDataLine) Proxy.newProxyInstance(
                ReplayAudioOutputTest.class.getClassLoader(), new Class<?>[]{SourceDataLine.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "write":
                            writes++; input = (byte[]) args[0]; offset = (int) args[1]; length = (int) args[2];
                            if (duringWrite != null) duringWrite.run();
                            if (writeFailure != null) throw writeFailure;
                            return written;
                        case "getFormat":
                            formats++;
                            if (formatFailure) throw new AssertionError("optional format failure");
                            return format;
                        case "flush":
                            flushes++; events.add("flush");
                            if (lifecycleFailure != null) throw lifecycleFailure;
                            return null;
                        case "close":
                            closes++; events.add("close");
                            if (duringClose != null) duringClose.run();
                            if (lifecycleFailure != null) throw lifecycleFailure;
                            return null;
                        default: throw new AssertionError("Unexpected device access: " + method.getName());
                    }
                });
        List<String> eventsWith(Sink sink) { List<String> all = new ArrayList<>(sink.events); all.addAll(events); return all; }
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(2, TimeUnit.SECONDS)) throw new AssertionError("stub device wait timed out"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
    }
    private static Throwable thrown(Runnable action) {
        try { action.run(); } catch (Throwable failure) { return failure; }
        throw new AssertionError("Expected original device failure");
    }
    private static void check(boolean value) { assertions++; if (!value) throw new AssertionError("assertion " + assertions); }
    private static void same(Object expected, Object actual) { check(expected == actual); }
    private static void equal(Object expected, Object actual) { check(java.util.Objects.equals(expected, actual)); }
}
