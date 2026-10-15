// PIQ-authored code: additional permission in piq-fc-arcade/SNES9X-LINKING-EXCEPTION.txt.
package cn.piq.fcarcade.audio;

import cn.piq.retro.replay.ReplayAudio;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.SourceDataLine;

/** Final JavaSound output only. No global hook, device registry, I/O or second audio queue. */
public final class ReplayAudioOutput {
    private static final Sink REPLAY = new Sink() {
        public Object token(Object source) { return ReplayAudio.captureToken(source); }
        public void capture(Object token, Object source, byte[] pcm, int offset, int length, int rate, int channels) {
            ReplayAudio.capture(token, source, pcm, offset, length, rate, channels);
        }
        public void reset(Object source) { ReplayAudio.reset(source); }
        public void close(Object source) { ReplayAudio.close(source); }
    };

    private ReplayAudioOutput() {}
    public static int write(SourceDataLine line, byte[] pcm, int offset, int length) {
        return write(REPLAY, line, pcm, offset, length);
    }
    public static void flush(SourceDataLine line) { flush(REPLAY, line); }
    public static void close(SourceDataLine line) { close(REPLAY, line); }

    // Package-local test seam; production has only the immutable public API adapter above.
    interface Sink {
        Object token(Object source);
        void capture(Object token, Object source, byte[] pcm, int offset, int length, int rate, int channels);
        void reset(Object source);
        void close(Object source);
    }

    static int write(Sink sink, SourceDataLine line, byte[] pcm, int offset, int length) {
        Object token = null;
        AudioFormat format = null;
        try {
            token = sink.token(line);
            if (token != null) format = line.getFormat();
        } catch (Throwable ignored) { token = null; }

        // Deliberately outside capture's catch: exact original arguments, one invocation,
        // original return value/exception. Recording cannot change live playback.
        int written = line.write(pcm, offset, length);
        if (token != null && written > 0) {
            try {
                if (supported(format) && pcm != null && offset >= 0 && length >= written
                        && offset <= pcm.length - written && written % format.getFrameSize() == 0) {
                    sink.capture(token, line, pcm, offset, written, (int) format.getSampleRate(), format.getChannels());
                }
            } catch (Throwable ignored) { /* Optional capture cannot mute live audio. */ }
        }
        return written;
    }

    static void flush(Sink sink, SourceDataLine line) {
        // Invalidate first: device flush/close may release a blocked write on another thread.
        try { sink.reset(line); } catch (Throwable ignored) { }
        line.flush();
    }
    static void close(Sink sink, SourceDataLine line) {
        try { sink.close(line); } catch (Throwable ignored) { }
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
