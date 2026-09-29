package cn.piq.fcarcade.client.cabinet;

/** One optional audio device for the one local spectator stream, never one per display. */
public final class WatchAudio implements AutoCloseable {
    private final CabinetAudio audio = new CabinetAudio();
    public void gain(float value) { audio.gain(Float.isFinite(value) ? value : 0); }
    public void offer(short[] samples) { audio.offer(samples); }
    @Override public void close() { audio.close(); }
}
