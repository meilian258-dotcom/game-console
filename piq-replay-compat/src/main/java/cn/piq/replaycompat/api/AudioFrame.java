package cn.piq.replaycompat.api;

import java.util.Arrays;

/** Immutable, already-volume-adjusted PCM16 signed little-endian output. No core or MC objects. */
public record AudioFrame(String source, long startMicros, int sampleRate, int channels, byte[] pcm) {
    public static final int MAX_PCM_BYTES = 65_536;
    public static final long MAX_TIME_MICROS = 7_200_000_000L;
    public AudioFrame {
        if (source == null || !source.matches("[A-Za-z0-9_.:/-]{1,128}")
                || startMicros < 0 || startMicros > MAX_TIME_MICROS
                || sampleRate < 8_000 || sampleRate > 192_000 || channels < 1 || channels > 2
                || pcm == null || pcm.length == 0 || pcm.length > MAX_PCM_BYTES
                || pcm.length % (channels * 2) != 0
                || (long) pcm.length * 1_000_000 / (sampleRate * channels * 2L) > 500_000) {
            throw new IllegalArgumentException("Invalid replay PCM frame");
        }
        pcm = pcm.clone();
        if (startMicros + durationMicros(pcm.length, sampleRate, channels) > MAX_TIME_MICROS)
            throw new IllegalArgumentException("Replay PCM duration quota");
    }
    @Override public byte[] pcm() { return pcm.clone(); }
    public int byteCount() { return pcm.length; }
    public int sampleFrames() { return pcm.length / (channels * 2); }
    public long endMicros() { return startMicros + durationMicros(pcm.length, sampleRate, channels); }
    public int sample(int frame, int channel) {
        if (frame < 0 || frame >= sampleFrames() || channel < 0 || channel >= channels)
            throw new IndexOutOfBoundsException();
        int i = (frame * channels + channel) * 2;
        return (short) ((pcm[i] & 255) | (pcm[i + 1] << 8));
    }
    /** Retain whole sample frames in the half-open source interval and shift to the output clock. */
    public AudioFrame slice(long fromMicros, long toMicros, long outputFromMicros) {
        long first = Math.max(0, ceilDiv((fromMicros - startMicros) * sampleRate, 1_000_000));
        long last = Math.min(sampleFrames(), ceilDiv((toMicros - startMicros) * sampleRate, 1_000_000));
        if (first >= last) return null;
        long originalStart = startMicros + first * 1_000_000 / sampleRate;
        return new AudioFrame(source, outputFromMicros + originalStart - fromMicros, sampleRate, channels,
                Arrays.copyOfRange(pcm, (int) first * channels * 2, (int) last * channels * 2));
    }
    private static long ceilDiv(long value, long divisor) { return -Math.floorDiv(-value, divisor); }
    private static long durationMicros(int bytes, int rate, int channels) {
        return ((long) bytes / (channels * 2) * 1_000_000 + rate - 1) / rate;
    }
}
