package cn.piq.fcarcade.home;

/** Persisted presentation and transient signal are deliberately separate. */
public final class TelevisionState {
    public static final int DEFAULT_VOLUME = 60;
    private boolean powered;
    private boolean signal;
    private int volume;
    public TelevisionState(boolean powered, int volume) { this.powered = powered; this.volume = clamp(volume); }
    public boolean powered() { return powered; }
    public boolean signal() { return powered && signal; }
    public boolean colorBars() { return powered && !signal; }
    public int volume() { return volume; }
    public float gain() { return powered ? volume / 100f : 0; }
    public void power(boolean on) { powered = on; if (!on) signal = false; }
    public void signal(boolean on) { signal = powered && on; }
    public void volume(int value) { volume = clamp(value); }
    public static int clamp(int value) { return Math.max(0, Math.min(100, value)); }
}
