package cn.piq.replaycompat.audio;

import com.replaymod.replaystudio.lib.guava.base.Optional;
import com.replaymod.replaystudio.replay.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import javax.sound.sampled.SourceDataLine;

final class AudioTestSupport {
    static int checks;
    static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    static void await(BooleanSupplier condition, String message) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < until) Thread.sleep(2);
        check(condition.getAsBoolean(), message);
    }
    static byte[] pcm(int samples, int channels, int value) {
        byte[] bytes = new byte[samples * channels * 2];
        for (int i = 0; i < samples * channels; i++) { bytes[i * 2] = (byte) value; bytes[i * 2 + 1] = (byte) (value >>> 8); }
        return bytes;
    }
    static int sample(byte[] bytes, int index) { return (short) ((bytes[index * 2] & 255) | bytes[index * 2 + 1] << 8); }
    static final class Archive implements InvocationHandler {
        final Map<String, byte[]> entries = new ConcurrentHashMap<>();
        final ReplayMetaData metadata = new ReplayMetaData();
        Set<com.replaymod.replaystudio.data.Marker> markers = Set.of();
        volatile CountDownLatch readEntered, readRelease, writeEntered, writeRelease;
        volatile String failWrite;
        final ReplayFile file;
        Archive(int millis) {
            metadata.setDuration(millis);
            file = (ReplayFile) Proxy.newProxyInstance(ReplayFile.class.getClassLoader(), new Class<?>[]{ReplayFile.class}, this);
        }
        @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            return switch (method.getName()) {
                case "get" -> {
                    if (readEntered != null) {
                        readEntered.countDown();
                        if (!readRelease.await(5, TimeUnit.SECONDS)) throw new IOException("Read test gate timed out");
                    }
                    byte[] bytes = entries.get((String) args[0]);
                    yield Optional.fromNullable(bytes == null ? null : new ByteArrayInputStream(bytes));
                }
                case "write" -> {
                    String name = (String) args[0];
                    if (name.equals(failWrite)) throw new IOException("Injected write failure");
                    if (name.endsWith(".pcmz") && writeEntered != null) {
                        writeEntered.countDown();
                        if (!writeRelease.await(5, TimeUnit.SECONDS)) throw new IOException("Write test gate timed out");
                    }
                    yield new ByteArrayOutputStream() { @Override public void close() { entries.put(name, toByteArray()); } };
                }
                case "getMetaData" -> metadata;
                case "getMarkers" -> Optional.of(markers);
                case "toString" -> "AudioTestArchive";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "save", "saveTo", "close", "remove" -> null;
                default -> throw new UnsupportedOperationException(method.getName());
            };
        }
    }
    static final class Sink implements PcmSink {
        long generation; int resets, offers; boolean closed;
        final List<byte[]> chunks = new ArrayList<>();
        @Override public synchronized void reset(long value) { generation = value; resets++; chunks.clear(); }
        @Override public synchronized boolean offer(long value, byte[] bytes) {
            if (closed || value != generation) return false;
            offers++; chunks.add(bytes.clone()); return true;
        }
        @Override public synchronized void close() { closed = true; chunks.clear(); }
        synchronized int offers() { return offers; }
        synchronized int queued() { return chunks.size(); }
        synchronized byte[] first() { return chunks.get(0).clone(); }
    }
    /** Real wall-clock 48kHz consumer, with only a 20ms hardware buffer, not an instant sink. */
    static final class PacedDevice implements InvocationHandler {
        final SourceDataLine line = (SourceDataLine) Proxy.newProxyInstance(SourceDataLine.class.getClassLoader(),
                new Class<?>[]{SourceDataLine.class}, this);
        private long updated = System.nanoTime(), stallUntil, consumedFrames, writtenFrames;
        private double fractional;
        private int bufferedFrames, maxBufferedFrames, writes, flushes, lastSample;
        private boolean started, closed, wrongThread;
        synchronized void stall(long millis) { update(); stallUntil = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis); }
        private void update() {
            long now = System.nanoTime(), elapsed = Math.max(0, now - Math.max(updated, stallUntil)); updated = now;
            if (!started) return;
            double frames = elapsed * (48000.0 / 1_000_000_000) + fractional;
            int whole = (int) frames; fractional = frames - whole;
            int consumed = Math.min(bufferedFrames, whole); bufferedFrames -= consumed; consumedFrames += consumed;
        }
        synchronized long consumedFrames() { update(); return consumedFrames; }
        synchronized long writtenFrames() { return writtenFrames; }
        synchronized int writes() { return writes; }
        synchronized int flushes() { return flushes; }
        synchronized int lastSample() { return lastSample; }
        synchronized boolean closed() { return closed; }
        synchronized int maxBufferedFrames() { return maxBufferedFrames; }
        synchronized boolean wrongThread() { return wrongThread; }
        @Override public synchronized Object invoke(Object proxy, Method method, Object[] args) {
            wrongThread |= !Thread.currentThread().getName().equals("PIQ-Replay-Audio-Device"); update();
            return switch (method.getName()) {
                case "open" -> { if ((int) args[1] != 3840) throw new AssertionError("hardware buffer changed"); yield null; }
                case "available" -> System.nanoTime() < stallUntil ? 0 : (960 - bufferedFrames) * 4;
                case "write" -> {
                    int frames = (int) args[2] / 4;
                    if (frames + bufferedFrames > 960) throw new AssertionError("hardware overrun");
                    bufferedFrames += frames; maxBufferedFrames = Math.max(maxBufferedFrames, bufferedFrames);
                    writtenFrames += frames; writes++; byte[] bytes = (byte[]) args[0]; int offset = (int) args[1];
                    lastSample = (short) ((bytes[offset] & 255) | bytes[offset + 1] << 8); yield args[2];
                }
                case "start" -> { started = true; yield null; }
                case "stop" -> { started = false; yield null; }
                case "flush" -> { bufferedFrames = 0; flushes++; yield null; }
                case "close" -> { closed = true; started = false; yield null; }
                default -> throw new UnsupportedOperationException(method.getName());
            };
        }
    }
    private AudioTestSupport() {}
}
