package cn.piq.fcarcade.audio;

final class NesPcmEncoder {
    private static final float HIGH_PASS_DECAY = 0.995F;
    private static final float OUTPUT_GAIN = 1.6F;

    private float previousInput;
    private float previousOutput;

    byte[] encode(float[] samples, int count, float volume) {
        if (count < 0 || count > samples.length) {
            throw new IllegalArgumentException("非法音频采样数：" + count);
        }
        float normalizedVolume = Math.max(0.0F, Math.min(1.0F, volume));
        byte[] pcm = new byte[count * Short.BYTES];
        for (int i = 0; i < count; i++) {
            float input = samples[i];
            float filtered = input - previousInput + HIGH_PASS_DECAY * previousOutput;
            previousInput = input;
            previousOutput = filtered;

            float scaled = Math.max(-1.0F,
                    Math.min(1.0F, filtered * OUTPUT_GAIN * normalizedVolume));
            short value = (short) Math.round(scaled * Short.MAX_VALUE);
            pcm[i * 2] = (byte) value;
            pcm[i * 2 + 1] = (byte) (value >>> 8);
        }
        return pcm;
    }

    void reset() {
        previousInput = 0.0F;
        previousOutput = 0.0F;
    }
}
