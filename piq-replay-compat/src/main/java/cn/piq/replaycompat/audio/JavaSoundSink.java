package cn.piq.replaycompat.audio;

import java.util.ArrayDeque;
import java.util.concurrent.locks.LockSupport;
import javax.sound.sampled.*;

/** Replay-only output; not one of the device capture targets. All line calls belong to its daemon. */
final class JavaSoundSink implements PcmSink {
    static final int MAX_QUEUED_BYTES = 30_720, MAX_REOPEN_ATTEMPTS = 3;
    private static final long RETRY_DELAY_NANOS = 250_000_000L;
    @FunctionalInterface interface LineFactory { SourceDataLine open(AudioFormat format) throws Exception; }
    private record Chunk(long generation, byte[] bytes) {}
    private final Object lock = new Object();
    private final ArrayDeque<Chunk> queue = new ArrayDeque<>();
    private final Thread worker; private final LineFactory factory;
    private volatile boolean closed, recovering, deviceFailed; private volatile long generation;
    private int queuedBytes, peakQueuedBytes;
    private long accepted, backpressure, stale, deviceFailures, reopens;
    record Diagnostics(int queuedBytes, int peakQueuedBytes, long accepted, long backpressure,
                       long stale, long deviceFailures, long reopens, boolean recovering, boolean failed) {}
    JavaSoundSink() { this(AudioSystem::getSourceDataLine); }
    JavaSoundSink(LineFactory factory) {
        this.factory = factory; worker = new Thread(this::run, "PIQ-Replay-Audio-Device"); worker.setDaemon(true); worker.start();
    }
    @Override public void reset(long next) {
        synchronized (lock) { generation = next; queue.clear(); queuedBytes = 0; }
        LockSupport.unpark(worker);
    }
    @Override public boolean offer(long token, byte[] bytes) {
        return offerResult(token, bytes) == OfferResult.ACCEPTED;
    }
    @Override public OfferResult offerResult(long token, byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > 15_360 || (bytes.length & 3) != 0) return OfferResult.INVALID;
        synchronized (lock) {
            if (closed || deviceFailed) return OfferResult.DEVICE_FAILED;
            if (token != generation) { stale++; return OfferResult.STALE; }
            if (recovering) return OfferResult.DEVICE_RECOVERING;
            if (queuedBytes + bytes.length > MAX_QUEUED_BYTES) { backpressure++; return OfferResult.BACKPRESSURE; }
            queue.addLast(new Chunk(token, bytes.clone())); queuedBytes += bytes.length;
            peakQueuedBytes = Math.max(peakQueuedBytes, queuedBytes); accepted++;
        }
        LockSupport.unpark(worker); return OfferResult.ACCEPTED;
    }
    private Chunk poll() { synchronized (lock) { Chunk value = queue.pollFirst(); if (value != null) queuedBytes -= value.bytes.length; return value; } }
    private void run() {
        SourceDataLine line = null;
        try {
            long applied = Long.MIN_VALUE; Chunk chunk = null; int offset = 0;
            int attempts = 0; long retryAt = 0;
            while (!closed) {
              try {
                if (recovering) {
                    long wait = retryAt - System.nanoTime();
                    if (wait > 0) { LockSupport.parkNanos(Math.min(wait, 20_000_000)); continue; }
                    // Reopening never needs old queued PCM and never happens on the client thread.
                    AudioFormat format = new AudioFormat(48_000, 16, 2, true, false);
                    synchronized (lock) { reopens++; }
                    line = factory.open(format); if (closed) break;
                    line.open(format, 3840); if (closed) break; line.start();
                    recovering = false; applied = generation;
                }
                long wanted = generation;
                if (wanted != applied) {
                    chunk = null; offset = 0;
                    if (line != null) { line.stop(); line.flush(); if (!closed) line.start(); }
                    applied = wanted; continue;
                }
                if (chunk == null) {
                    chunk = poll(); offset = 0;
                    if (chunk == null) { LockSupport.parkNanos(2_000_000); continue; }
                    // A reset may race poll(). Keep a new-generation chunk until the device flush.
                    if (chunk.generation != applied) {
                        if (line != null) { line.stop(); line.flush(); if (!closed) line.start(); }
                        applied = chunk.generation;
                    }
                }
                if (line == null) {
                    AudioFormat format = new AudioFormat(48_000, 16, 2, true, false);
                    line = factory.open(format); if (closed) break;
                    line.open(format, 3840); if (closed) break; line.start();
                }
                if (closed || generation != applied) continue;
                int count = Math.min(Math.min(line.available(), 1920), chunk.bytes.length - offset) & ~3;
                if (count <= 0) { LockSupport.parkNanos(2_000_000); continue; }
                // available() is a driver call too: a reset/close can happen while it is blocked.
                if (closed || generation != applied) continue;
                int written = line.write(chunk.bytes, offset, count);
                if (written < 0 || written > count || (written & 3) != 0) throw new IllegalStateException("Replay audio device write");
                offset += written;
                if (offset == chunk.bytes.length) { chunk = null; offset = 0; }
                if (written == 0) LockSupport.parkNanos(2_000_000);
              } catch (Exception | LinkageError failure) {
                synchronized (lock) {
                    deviceFailures++; queue.clear(); queuedBytes = 0;
                    recovering = !closed && attempts < MAX_REOPEN_ATTEMPTS;
                    deviceFailed = !closed && !recovering;
                }
                // A bounded retry budget belongs to this replay session, not each successful write.
                chunk = null; offset = 0; release(line); line = null;
                if (closed) break;
                if (deviceFailed) {
                    AudioBridge.deviceWarning(this, "回放音频设备重试失败，设备音轨已停止；录像内容未判损坏。", failure);
                    break;
                }
                attempts++; retryAt = System.nanoTime() + RETRY_DELAY_NANOS * attempts;
                AudioBridge.deviceWarning(this, "回放音频设备暂不可用，正在有限重试（" + attempts + "/" + MAX_REOPEN_ATTEMPTS + "）；画面继续。", failure);
              }
            }
        } finally {
            synchronized (lock) { closed = true; queue.clear(); queuedBytes = 0; }
            release(line);
        }
    }
    private static void release(SourceDataLine line) {
        if (line == null) return;
        try { line.stop(); } catch (Exception | LinkageError ignored) { }
        try { line.flush(); } catch (Exception | LinkageError ignored) { }
        try { line.close(); } catch (Exception | LinkageError ignored) { }
    }
    Diagnostics diagnostics() {
        synchronized (lock) { return new Diagnostics(queuedBytes, peakQueuedBytes, accepted, backpressure,
                stale, deviceFailures, reopens, recovering, deviceFailed); }
    }
    @Override public void close() { closed = true; reset(generation + 1); }
}
