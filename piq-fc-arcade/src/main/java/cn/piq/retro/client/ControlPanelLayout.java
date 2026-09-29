package cn.piq.retro.client;

/** Bounded GUI coordinates, never scaled text or a full-screen settings panel. */
public record ControlPanelLayout(int left, int top, int width, int height) {
    public static ControlPanelLayout of(int screenWidth, int screenHeight) {
        int w = Math.max(1, Math.min(440, screenWidth - 16));
        int h = Math.max(1, Math.min(284, screenHeight - 16));
        return new ControlPanelLayout((screenWidth - w) / 2, (screenHeight - h) / 2, w, h);
    }
    public int innerX() { return left + 10; }
    public int innerWidth() { return Math.max(1, width - 20); }
    public int footer() { return top + height - 30; }
    public boolean compact() { return height < 284; }
    public boolean usable() { return width >= 280 && height >= 224; }
}
