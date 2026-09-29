package cn.piq.fcarcade.client.runtime;

/** Native GUI pixels; never scales or blurs rendered text. */
public record RuntimePanelLayout(int x, int y, int width, int height, boolean supported) {
    public static RuntimePanelLayout of(int width, int height) {
        int w = Math.max(1, Math.min(420, width - 32));
        int h = Math.max(1, Math.min(258, height - 32));
        return new RuntimePanelLayout((width-w)/2, (height-h)/2, w, h, width >= 320 && height >= 240);
    }
    public int innerX() { return x + 10; }
    public int innerWidth() { return width - 20; }
    public int row(int index) { return y + 44 + index * 15; }
    public int footer() { return y + height - 54; }
    public int buttonWidth() { return (innerWidth() - 8) / 3; }
    public int buttonX(int index) { return innerX() + index * (buttonWidth() + 4); }
}
