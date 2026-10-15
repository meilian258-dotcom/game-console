// PIQ-authored code: additional permission in piq-fc-arcade/MD-ARCADE-LINKING-EXCEPTION.txt.
// PIQ-authored code: additional permission in piq-fc-arcade/SNES9X-LINKING-EXCEPTION.txt.
package cn.piq.fcarcade.client.cabinet;

/** One optional audio device per spectator source; the presentation policy selects one audible source. */
public final class WatchAudio implements AutoCloseable {
    private final CabinetAudio audio = new CabinetAudio();
    public void gain(float value) { audio.gain(Float.isFinite(value) ? value : 0); }
    public void offer(short[] samples) { audio.offer(samples); }
    public void reset() { audio.reset(); }
    @Override public void close() { audio.close(); }
}
