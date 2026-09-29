// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfcarcade.audio;

import cn.piq.sfcarcade.SfcArcadeMod;
import cn.piq.sfcarcade.core.SfcFrameResult;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Low-latency bounded PCM player owned by one local SFC session. */
public final class SfcAudioPlayer implements AutoCloseable {
    private static final int LINE_BUFFER_MILLIS = 80;
    private static final int QUEUED_FRAME_LIMIT = 5;

    private final ArrayBlockingQueue<byte[]> queue =
            new ArrayBlockingQueue<>(QUEUED_FRAME_LIMIT);
    private final Thread worker;

    private volatile SourceDataLine line;
    private volatile Throwable failure;
    private volatile boolean closed;

    public SfcAudioPlayer() {
        worker = Thread.ofPlatform()
                .daemon(true)
                .name("PIQ SFC Audio")
                .start(this::playbackLoop);
    }

    public void submit(short[] samples, int stereoFrames, float volume) {
        if (closed || failure != null || stereoFrames <= 0) return;
        int shortCount = Math.multiplyExact(stereoFrames, SfcFrameResult.AUDIO_CHANNELS);
        if (shortCount > samples.length) {
            throw new IllegalArgumentException("SFC PCM 缓冲区不足：" + shortCount);
        }
        float gain = Math.max(0.0F, Math.min(1.0F, volume));
        byte[] pcm = new byte[Math.multiplyExact(shortCount, Short.BYTES)];
        for (int index = 0; index < shortCount; index++) {
            int scaled = Math.round(samples[index] * gain);
            short value = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, scaled));
            pcm[index * 2] = (byte) value;
            pcm[index * 2 + 1] = (byte) (value >>> 8);
        }

        // Never let a slow audio device back-pressure the emulator. Keeping a
        // small newest-only queue bounds latency and recovers quickly.
        while (!queue.offer(pcm)) queue.poll();
    }

    public boolean failed() {
        return failure != null;
    }

    public String failureMessage() {
        Throwable problem = failure;
        if (problem == null) return null;
        return problem.getMessage() == null
                ? problem.getClass().getSimpleName()
                : problem.getMessage();
    }

    private void playbackLoop() {
        AudioFormat format = new AudioFormat(
                SfcFrameResult.AUDIO_SAMPLE_RATE,
                16,
                SfcFrameResult.AUDIO_CHANNELS,
                true,
                false);
        try {
            SourceDataLine openedLine = AudioSystem.getSourceDataLine(format);
            int lineBytes = SfcFrameResult.AUDIO_SAMPLE_RATE
                    * format.getFrameSize() * LINE_BUFFER_MILLIS / 1000;
            openedLine.open(format, lineBytes);
            line = openedLine;
            openedLine.start();

            while (!closed) {
                byte[] pcm = queue.poll(100, TimeUnit.MILLISECONDS);
                if (pcm == null) continue;
                int written = 0;
                while (!closed && written < pcm.length) {
                    written += openedLine.write(pcm, written, pcm.length - written);
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Throwable throwable) {
            if (!closed) {
                failure = throwable;
                SfcArcadeMod.LOGGER.error("SFC 音频设备初始化或播放失败", throwable);
            }
        } finally {
            SourceDataLine activeLine = line;
            line = null;
            if (activeLine != null) {
                activeLine.stop();
                activeLine.flush();
                activeLine.close();
            }
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        queue.clear();
        worker.interrupt();
        SourceDataLine activeLine = line;
        if (activeLine != null) {
            activeLine.stop();
            activeLine.flush();
            activeLine.close();
        }
    }
}
