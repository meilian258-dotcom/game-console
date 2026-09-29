package cn.piq.fcarcade.audio;

import cn.piq.fcarcade.FcArcadeMod;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

public final class NesAudioPlayer implements AutoCloseable {
    public static final int SAMPLE_RATE = 44_100;
    private static final int LINE_BUFFER_MILLIS = 80;
    private static final int QUEUED_FRAME_LIMIT = 4;

    private final ArrayBlockingQueue<byte[]> queue =
            new ArrayBlockingQueue<>(QUEUED_FRAME_LIMIT);
    private final NesPcmEncoder encoder = new NesPcmEncoder();
    private final Thread worker;

    private volatile SourceDataLine line;
    private volatile Throwable failure;
    private volatile boolean closed;

    public NesAudioPlayer() {
        worker = new Thread(this::playbackLoop, "PIQ FC Audio");
        worker.setDaemon(true);
        worker.start();
    }

    public void submit(float[] samples, int count, float volume) {
        if (closed || failure != null || count <= 0) return;
        byte[] pcm;
        synchronized (encoder) {
            if (!(volume > 0.0F)) {
                encoder.reset();
                return;
            }
            pcm = encoder.encode(samples, count, volume);
        }
        if (closed) return;
        while (!queue.offer(pcm)) queue.poll();
    }

    public void clear() {
        queue.clear();
        synchronized (encoder) {
            encoder.reset();
        }
        SourceDataLine activeLine = line;
        if (activeLine != null) activeLine.flush();
    }

    public boolean failed() {
        return failure != null;
    }

    private void playbackLoop() {
        AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
        try {
            SourceDataLine openedLine = AudioSystem.getSourceDataLine(format);
            int lineBytes = SAMPLE_RATE * format.getFrameSize() * LINE_BUFFER_MILLIS / 1000;
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
        } catch (Throwable error) {
            if (!closed) {
                failure = error;
                FcArcadeMod.LOGGER.error("[PIQ FC] NES 音频设备初始化或播放失败", error);
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
