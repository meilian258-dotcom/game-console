package cn.piq.fcarcade.audio;

/** Continuous, quiet 1 kHz test tone; original generated PCM, no external sound assets. */
public final class NoSignalTone {
    public static final int SAMPLE_RATE = 44_100;
    public static final int FREQUENCY = 1_000;
    public static final double AMPLITUDE = .08;
    private NoSignalTone() {}
    public static short sample(long index) {
        if (index < 0) throw new IllegalArgumentException("Negative sample index");
        double envelope = Math.min(1, index / 220.0);
        // Integer phase avoids drift and loss of precision in long-running televisions.
        double phase = ((index % SAMPLE_RATE) * FREQUENCY) % SAMPLE_RATE;
        return (short) Math.round(Math.sin(2 * Math.PI * phase / SAMPLE_RATE) * 32767 * AMPLITUDE * envelope);
    }
    public static boolean audible(boolean powered, boolean signal, int volume, double distanceSquared) {
        return powered && !signal && volume > 0 && Double.isFinite(distanceSquared)
                && distanceSquared >= 0 && distanceSquared < 144;
    }
}
