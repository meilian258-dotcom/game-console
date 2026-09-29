package cn.piq.fcarcade.client;

/** Deterministic sharp color bars; no external texture, animation, or audible test tone. */
public final class NoSignalPattern {
    public static final int WIDTH = 224, HEIGHT = 168;
    private static final int[] BARS = {0xbfbfbf,0xbfbf00,0x00bfbf,0x00bf00,0xbf00bf,0xbf0000,0x0000bf};
    private static final int[] LOWER = {0x0000bf,0x101010,0xbf00bf,0x101010,0x00bfbf,0x101010,0xbfbfbf};
    private NoSignalPattern() {}
    public static int pixel(int x,int y) {
        if (x < 0 || x >= WIDTH || y < 0 || y >= HEIGHT) throw new IllegalArgumentException("Color bar pixel");
        int c = y < 120 ? BARS[x / 32] : y < 136 ? LOWER[x / 32]
                : x < 40 ? 0x082b47 : x < 80 ? 0xffffff : x < 120 ? 0x32134a : x < 152 ? 0x101010
                : x < 176 ? 0x050505 : x < 200 ? 0x151515 : 0x101010;
        return 0xff000000 | ((c & 255) << 16) | (c & 0xff00) | ((c >> 16) & 255);
    }
}
