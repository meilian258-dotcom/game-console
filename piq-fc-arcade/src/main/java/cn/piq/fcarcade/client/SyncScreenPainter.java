package cn.piq.fcarcade.client;

import cn.piq.fcarcade.core.NesCore;

import java.util.Map;

final class SyncScreenPainter {
    private static final int SCALE = 4;
    private static final int TEXT_Y = 92;
    private static final int BAR_LEFT = 32;
    private static final int BAR_TOP = 144;
    private static final int BAR_RIGHT = NesCore.WIDTH - 32;
    private static final int BAR_BOTTOM = 160;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int DARK_GRAY = 0x303030FF;
    private static final Map<Character, String[]> GLYPHS = Map.of(
            'S', new String[]{"1111", "1000", "1000", "1111", "0001", "0001", "1111"},
            'Y', new String[]{"1001", "1001", "0110", "0110", "0110", "0110", "0110"},
            'N', new String[]{"1001", "1101", "1101", "1011", "1011", "1001", "1001"},
            'C', new String[]{"1111", "1000", "1000", "1000", "1000", "1000", "1111"},
            'I', new String[]{"1111", "0110", "0110", "0110", "0110", "0110", "1111"},
            'G', new String[]{"1111", "1000", "1000", "1011", "1001", "1001", "1111"}
    );

    private SyncScreenPainter() {
    }

    static void paintPaused(byte[] rgba) {
        if (rgba.length != NesCore.RGBA_BYTES) {
            throw new IllegalArgumentException("暂停画面缓冲区大小错误");
        }
        fillRect(rgba, 0, 0, NesCore.WIDTH, NesCore.HEIGHT, 0x181C24FF);
        var glyphs = Map.of(
                'P', new String[]{"1111", "1001", "1001", "1111", "1000", "1000", "1000"},
                'A', new String[]{"0110", "1001", "1001", "1111", "1001", "1001", "1001"},
                'U', new String[]{"1001", "1001", "1001", "1001", "1001", "1001", "0110"},
                'S', GLYPHS.get('S'),
                'E', new String[]{"1111", "1000", "1000", "1110", "1000", "1000", "1111"},
                'D', new String[]{"1110", "1001", "1001", "1001", "1001", "1001", "1110"});
        String text = "PAUSED";
        int x = (NesCore.WIDTH - (text.length() * 5 - 1) * SCALE) / 2;
        for (char character : text.toCharArray()) {
            drawGlyph(rgba, x, TEXT_Y, glyphs.get(character));
            x += 5 * SCALE;
        }
        fillRect(rgba, 112, 144, 122, 176, WHITE);
        fillRect(rgba, 134, 144, 144, 176, WHITE);
    }

    static void paint(byte[] rgba, long completedFrame, long targetFrame) {
        if (rgba.length != NesCore.RGBA_BYTES) {
            throw new IllegalArgumentException("同步画面缓冲区大小错误");
        }
        java.util.Arrays.fill(rgba, (byte) 0);
        for (int pixel = 0; pixel < rgba.length; pixel += 4) rgba[pixel + 3] = (byte) 0xFF;

        String text = "SYNCING";
        int glyphWidth = 4 * SCALE;
        int spacing = SCALE;
        int textWidth = text.length() * glyphWidth + (text.length() - 1) * spacing;
        int x = (NesCore.WIDTH - textWidth) / 2;
        for (int index = 0; index < text.length(); index++) {
            drawGlyph(rgba, x, TEXT_Y, GLYPHS.get(text.charAt(index)));
            x += glyphWidth + spacing;
        }

        fillRect(rgba, BAR_LEFT, BAR_TOP, BAR_RIGHT, BAR_BOTTOM, WHITE);
        fillRect(rgba, BAR_LEFT + 2, BAR_TOP + 2, BAR_RIGHT - 2, BAR_BOTTOM - 2, DARK_GRAY);
        double progress = targetFrame <= 0
                ? 1.0D
                : Math.clamp((double) completedFrame / targetFrame, 0.0D, 1.0D);
        int fillRight = BAR_LEFT + 2
                + (int) Math.round((BAR_RIGHT - BAR_LEFT - 4) * progress);
        fillRect(rgba, BAR_LEFT + 2, BAR_TOP + 2, fillRight, BAR_BOTTOM - 2, WHITE);
    }

    private static void drawGlyph(byte[] rgba, int startX, int startY, String[] glyph) {
        for (int row = 0; row < glyph.length; row++) {
            for (int column = 0; column < glyph[row].length(); column++) {
                if (glyph[row].charAt(column) == '1') {
                    fillRect(
                            rgba,
                            startX + column * SCALE,
                            startY + row * SCALE,
                            startX + (column + 1) * SCALE,
                            startY + (row + 1) * SCALE,
                            WHITE);
                }
            }
        }
    }

    private static void fillRect(
            byte[] rgba,
            int left,
            int top,
            int right,
            int bottom,
            int color
    ) {
        for (int y = top; y < bottom; y++) {
            for (int x = left; x < right; x++) {
                int offset = (y * NesCore.WIDTH + x) * 4;
                rgba[offset] = (byte) (color >>> 24);
                rgba[offset + 1] = (byte) (color >>> 16);
                rgba[offset + 2] = (byte) (color >>> 8);
                rgba[offset + 3] = (byte) color;
            }
        }
    }
}
