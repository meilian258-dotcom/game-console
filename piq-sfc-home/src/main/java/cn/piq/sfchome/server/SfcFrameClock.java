package cn.piq.sfchome.server;
/** Micro-Hz rational remainder: PAL alternates 2/3 frames, NTSC occasionally 4. */
public final class SfcFrameClock {
    private final long microFps; private long remainder;
    public SfcFrameClock(double fps){if(!Double.isFinite(fps)||!(fps>=49&&fps<=51||fps>=59&&fps<=61))throw new IllegalArgumentException("Unsupported FPS");microFps=Math.round(fps*1_000_000);}
    public int tick(){remainder+=microFps;int frames=(int)(remainder/20_000_000);remainder%=20_000_000;return frames;}
}
