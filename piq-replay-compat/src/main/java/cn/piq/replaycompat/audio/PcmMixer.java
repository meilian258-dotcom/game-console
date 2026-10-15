package cn.piq.replaycompat.audio;

import cn.piq.replaycompat.api.AudioFrame;
import java.io.IOException;
import java.util.*;

/** Sample-clock mixing, no volume reapplication. Each recorded terminal contributes once. */
final class PcmMixer {
    static final int RATE = 48_000, MAX_OUTPUT_FRAMES = 3840;
    private PcmMixer() {}
    static byte[] render(List<AudioFrame> input, long firstSample, int count) throws IOException {
        if (firstSample < 0 || count < 1 || count > MAX_OUTPUT_FRAMES || input.size() > 8192) throw new IOException("Audio mix bounds");
        Map<String, int[]> tracks = new LinkedHashMap<>();
        var frames = new ArrayList<>(input); frames.sort(Comparator.comparingLong(AudioFrame::startMicros));
        for (AudioFrame frame : frames) {
            long begin = Math.max(firstSample, ceilDiv(frame.startMicros() * RATE, 1_000_000));
            long end = Math.min(firstSample + count, ceilDiv(frame.endMicros() * RATE, 1_000_000));
            if (begin >= end) continue;
            int[] track = tracks.get(frame.source());
            if (track == null) {
                if (tracks.size() >= AudioRecording.MAX_ACTIVE_SOURCES) throw new IOException("Audio simultaneous source quota");
                track = new int[count * 2]; tracks.put(frame.source(), track);
            }
            for (long sample = begin; sample < end; sample++) {
                double position = ((sample * (1_000_000.0 / RATE)) - frame.startMicros()) * frame.sampleRate() / 1_000_000.0;
                int left = (int) Math.floor(position);
                if (left < 0 || left >= frame.sampleFrames()) continue;
                int right = Math.min(frame.sampleFrames() - 1, left + 1); double fraction = position - left;
                int out = (int) (sample - firstSample) * 2;
                for (int channel = 0; channel < 2; channel++) {
                    int inputChannel = frame.channels() == 1 ? 0 : channel;
                    int a = frame.sample(left, inputChannel), b = frame.sample(right, inputChannel);
                    track[out + channel] = (int) Math.round(a + (b - a) * fraction);
                }
            }
        }
        byte[] output = new byte[count * 4];
        for (int i = 0; i < count * 2; i++) {
            int sum = 0; for (int[] track : tracks.values()) sum += track[i];
            short value = (short) Math.clamp(sum, Short.MIN_VALUE, Short.MAX_VALUE);
            output[i * 2] = (byte) value; output[i * 2 + 1] = (byte) (value >>> 8);
        }
        return output;
    }
    private static long ceilDiv(long value, long divisor) { return -Math.floorDiv(-value, divisor); }
}
