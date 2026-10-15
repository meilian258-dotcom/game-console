package cn.piq.replaycompat.audio.capture;

import cn.piq.replaycompat.api.AudioReplay;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.SourceDataLine;

/** Final, post-volume device writes only; never a microphone or a global JavaSound hook. */
public final class JavaSoundCapture {
    private static final Sink REPLAY = new Sink() {
        public Object token(Object source) { return AudioReplay.captureToken(source); }
        public void capture(Object token, Object source, byte[] data, int offset, int length, int rate, int channels) {
            AudioReplay.capture(token, source, data, offset, length, rate, channels);
        }
        public void reset(Object source) { AudioReplay.reset(source); }
        public void close(Object source) { AudioReplay.close(source); }
    };

    private JavaSoundCapture() {}

    public static int write(SourceDataLine line, byte[] data, int offset, int length) {
        return write(REPLAY, line, data, offset, length);
    }

    public static void flush(SourceDataLine line) { flush(REPLAY, line); }
    public static void close(SourceDataLine line) { close(REPLAY, line); }

    // A narrow test seam, not a replaceable global callback or a source registry.
    interface Sink {
        Object token(Object source);
        void capture(Object token, Object source, byte[] data, int offset, int length, int rate, int channels);
        void reset(Object source);
        void close(Object source);
    }

    static int write(Sink sink, SourceDataLine line, byte[] data, int offset, int length) {
        Object token = null;
        AudioFormat format = null;
        try {
            // Bind before a potentially blocking write. The audio service rejects this
            // token after recording replacement, flush or close, including a partial write.
            token = sink.token(line);
            if (token != null) format = line.getFormat();
        } catch (Throwable ignored) { token = null; }

        // Keep this outside every capture catch: preserve the device return value and
        // exact exception, arguments, byte array identity and number of invocations.
        int written = line.write(data, offset, length);
        if (token != null && written > 0) {
            try {
                if (supported(format) && data != null && offset >= 0 && length >= written
                        && offset <= data.length - written && written % format.getFrameSize() == 0) {
                    // The service makes a bounded copy immediately, without I/O or waits.
                    // Do not capture the caller's requested but unaccepted tail.
                    sink.capture(token, line, data, offset, written,
                            (int) format.getSampleRate(), format.getChannels());
                }
            } catch (Throwable ignored) { /* Optional recording must not mute live audio. */ }
        }
        return written;
    }

    static void flush(Sink sink, SourceDataLine line) {
        // Invalidate first: flush can release a write blocked on another thread.
        try { sink.reset(line); } catch (Throwable ignored) {}
        line.flush();
    }

    static void close(Sink sink, SourceDataLine line) {
        try { sink.close(line); } catch (Throwable ignored) {}
        line.close();
    }

    static boolean supported(AudioFormat format) {
        if (format == null || !AudioFormat.Encoding.PCM_SIGNED.equals(format.getEncoding())
                || format.getSampleSizeInBits() != 16 || format.isBigEndian()) return false;
        int channels = format.getChannels();
        float rate = format.getSampleRate();
        return (channels == 1 || channels == 2) && format.getFrameSize() == channels * 2
                && Float.isFinite(rate) && rate >= 8_000 && rate <= 96_000
                && rate == (int) rate && format.getFrameRate() == rate;
    }
}
