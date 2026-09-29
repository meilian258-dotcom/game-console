package cn.piq.retro.client;

/** Pure, bounded layout so remapping controls remain usable at vanilla's 320x240 minimum. */
public record GamepadSettingsLayout(int left, int width, int rows, int pageSize, int pages,
                                    int bindingY, int statusY, int pagerY, int footerY) {
    public static GamepadSettingsLayout of(int width, int height, int bindings) {
        int panel = Math.max(180, Math.min(420, width - 24));
        int rows = Math.max(1, Math.min(6, (height - 194) / 22));
        int count = rows * 2;
        return new GamepadSettingsLayout((width - panel) / 2, panel, rows, count,
                Math.max(1, (bindings + count - 1) / count), 124, height - 66, height - 48, height - 24);
    }
}
