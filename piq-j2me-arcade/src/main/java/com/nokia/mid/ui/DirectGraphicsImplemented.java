package com.nokia.mid.ui;

import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;
import javax.microedition.lcdui.game.Sprite;

/** MIDP-backed DirectGraphics implementation. */
public class DirectGraphicsImplemented implements DirectGraphics {
    private final Graphics graphics;
    private int alpha = 255;

    protected DirectGraphicsImplemented(Graphics graphics) {
        if (graphics == null) {
            throw new NullPointerException("graphics");
        }
        this.graphics = graphics;
    }

    @Override
    public void setARGBColor(int argbColor) {
        alpha = (argbColor >>> 24) & 0xff;
        graphics.setColor(argbColor & 0x00ffffff);
    }

    @Override
    public int getAlphaComponent() {
        return alpha;
    }

    @Override
    public int getNativePixelFormat() {
        return TYPE_INT_8888_ARGB;
    }

    @Override
    public void drawTriangle(int x1, int y1, int x2, int y2, int x3, int y3, int color) {
        setARGBColor(color);
        graphics.drawLine(x1, y1, x2, y2);
        graphics.drawLine(x2, y2, x3, y3);
        graphics.drawLine(x3, y3, x1, y1);
    }

    @Override
    public void fillTriangle(int x1, int y1, int x2, int y2, int x3, int y3, int color) {
        setARGBColor(color);
        graphics.fillTriangle(x1, y1, x2, y2, x3, y3);
    }

    @Override
    public void drawPolygon(int[] xs, int xo, int[] ys, int yo, int count, int color) {
        validatePoints(xs, xo, ys, yo, count);
        setARGBColor(color);
        if (count < 2) {
            return;
        }
        for (int i = 1; i < count; i++) {
            graphics.drawLine(xs[xo + i - 1], ys[yo + i - 1], xs[xo + i], ys[yo + i]);
        }
        graphics.drawLine(xs[xo + count - 1], ys[yo + count - 1], xs[xo], ys[yo]);
    }

    @Override
    public void fillPolygon(int[] xs, int xo, int[] ys, int yo, int count, int color) {
        validatePoints(xs, xo, ys, yo, count);
        if (count < 3) {
            drawPolygon(xs, xo, ys, yo, count, color);
            return;
        }
        setARGBColor(color);
        // Nokia games overwhelmingly use convex HUD polygons. A triangle fan
        // covers those exactly and remains deterministic for older titles.
        for (int i = 2; i < count; i++) {
            graphics.fillTriangle(xs[xo], ys[yo], xs[xo + i - 1], ys[yo + i - 1],
                    xs[xo + i], ys[yo + i]);
        }
    }

    @Override
    public void drawImage(Image image, int x, int y, int anchor, int manipulation) {
        if (image == null) {
            throw new NullPointerException("image");
        }
        if (manipulation == 0) {
            graphics.drawImage(image, x, y, anchor);
            return;
        }
        Image transformed = Image.createImage(image, 0, 0, image.getWidth(), image.getHeight(),
                toSpriteTransform(manipulation));
        graphics.drawImage(transformed, x, y, anchor);
    }

    @Override
    public void drawPixels(int[] pixels, boolean transparency, int offset, int scanLength,
                           int x, int y, int width, int height, int manipulation, int format) {
        int[] argb = copyIntPixels(pixels, offset, scanLength, width, height, format);
        drawPixelImage(argb, transparency, x, y, width, height, manipulation);
    }

    @Override
    public void drawPixels(short[] pixels, boolean transparency, int offset, int scanLength,
                           int x, int y, int width, int height, int manipulation, int format) {
        int[] argb = new int[checkedArea(width, height)];
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                argb[row * width + col] = shortToArgb(pixels[offset + row * scanLength + col], format);
            }
        }
        drawPixelImage(argb, transparency, x, y, width, height, manipulation);
    }

    @Override
    public void drawPixels(byte[] pixels, byte[] mask, int offset, int scanLength,
                           int x, int y, int width, int height, int manipulation, int format) {
        int[] argb = new int[checkedArea(width, height)];
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                int source = offset + row * scanLength + col;
                int value = pixels[source] & 0xff;
                int color = byteToArgb(value, format);
                if (mask != null && source < mask.length && mask[source] == 0) {
                    color &= 0x00ffffff;
                }
                argb[row * width + col] = color;
            }
        }
        drawPixelImage(argb, true, x, y, width, height, manipulation);
    }

    @Override
    public void getPixels(int[] pixels, int offset, int scanLength, int x, int y,
                          int width, int height, int format) {
        clearDestination(pixels, offset, scanLength, width, height);
    }

    @Override
    public void getPixels(byte[] pixels, byte[] mask, int offset, int scanLength, int x, int y,
                          int width, int height, int format) {
        clearDestination(pixels, offset, scanLength, width, height);
        if (mask != null) {
            clearDestination(mask, offset, scanLength, width, height);
        }
    }

    @Override
    public void getPixels(short[] pixels, int offset, int scanLength, int x, int y,
                          int width, int height, int format) {
        clearDestination(pixels, offset, scanLength, width, height);
    }

    private void drawPixelImage(int[] argb, boolean transparency, int x, int y,
                                int width, int height, int manipulation) {
        Image image = Image.createRGBImage(argb, width, height, transparency);
        drawImage(image, x, y, Graphics.TOP | Graphics.LEFT, manipulation);
    }

    private static int[] copyIntPixels(int[] pixels, int offset, int scanLength,
                                       int width, int height, int format) {
        int[] result = new int[checkedArea(width, height)];
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                int value = pixels[offset + row * scanLength + col];
                result[row * width + col] = format == TYPE_INT_888_RGB
                        ? 0xff000000 | (value & 0x00ffffff) : value;
            }
        }
        return result;
    }

    private static int shortToArgb(short raw, int format) {
        int value = raw & 0xffff;
        return switch (format) {
            case TYPE_USHORT_444_RGB -> 0xff000000
                    | ((value & 0x0f00) << 12) | ((value & 0x0f00) << 8)
                    | ((value & 0x00f0) << 8) | ((value & 0x00f0) << 4)
                    | ((value & 0x000f) << 4) | (value & 0x000f);
            case TYPE_USHORT_4444_ARGB -> ((value & 0xf000) << 16) | ((value & 0xf000) << 12)
                    | ((value & 0x0f00) << 12) | ((value & 0x0f00) << 8)
                    | ((value & 0x00f0) << 8) | ((value & 0x00f0) << 4)
                    | ((value & 0x000f) << 4) | (value & 0x000f);
            case TYPE_USHORT_555_RGB, TYPE_USHORT_1555_ARGB -> {
                int r = (value >> 10) & 31;
                int g = (value >> 5) & 31;
                int b = value & 31;
                int a = format == TYPE_USHORT_1555_ARGB && (value & 0x8000) == 0 ? 0 : 255;
                yield (a << 24) | ((r << 3 | r >> 2) << 16)
                        | ((g << 3 | g >> 2) << 8) | (b << 3 | b >> 2);
            }
            case TYPE_USHORT_565_RGB -> {
                int r = (value >> 11) & 31;
                int g = (value >> 5) & 63;
                int b = value & 31;
                yield 0xff000000 | ((r << 3 | r >> 2) << 16)
                        | ((g << 2 | g >> 4) << 8) | (b << 3 | b >> 2);
            }
            default -> 0xff000000 | value;
        };
    }

    private static int byteToArgb(int value, int format) {
        if (format == TYPE_BYTE_332_RGB) {
            int r = (value >> 5) & 7;
            int g = (value >> 2) & 7;
            int b = value & 3;
            return 0xff000000 | ((r * 255 / 7) << 16) | ((g * 255 / 7) << 8) | (b * 255 / 3);
        }
        return 0xff000000 | (value << 16) | (value << 8) | value;
    }

    private static int toSpriteTransform(int manipulation) {
        boolean horizontal = (manipulation & FLIP_HORIZONTAL) != 0;
        boolean vertical = (manipulation & FLIP_VERTICAL) != 0;
        int rotation = manipulation & ~(FLIP_HORIZONTAL | FLIP_VERTICAL);
        rotation = ((rotation % 360) + 360) % 360;
        if (vertical) {
            horizontal = !horizontal;
            rotation = (rotation + 180) % 360;
        }
        if (horizontal) {
            return switch (rotation) {
                case 90 -> Sprite.TRANS_MIRROR_ROT90;
                case 180 -> Sprite.TRANS_MIRROR_ROT180;
                case 270 -> Sprite.TRANS_MIRROR_ROT270;
                default -> Sprite.TRANS_MIRROR;
            };
        }
        return switch (rotation) {
            case 90 -> Sprite.TRANS_ROT90;
            case 180 -> Sprite.TRANS_ROT180;
            case 270 -> Sprite.TRANS_ROT270;
            default -> Sprite.TRANS_NONE;
        };
    }

    private static int checkedArea(int width, int height) {
        if (width < 0 || height < 0) {
            throw new IllegalArgumentException("negative size");
        }
        return Math.multiplyExact(width, height);
    }

    private static void validatePoints(int[] xs, int xo, int[] ys, int yo, int count) {
        if (xs == null || ys == null || xo < 0 || yo < 0 || count < 0
                || xo + count > xs.length || yo + count > ys.length) {
            throw new ArrayIndexOutOfBoundsException();
        }
    }

    private static void clearDestination(int[] values, int offset, int scan, int width, int height) {
        for (int row = 0; row < height; row++) {
            java.util.Arrays.fill(values, offset + row * scan, offset + row * scan + width, 0);
        }
    }

    private static void clearDestination(byte[] values, int offset, int scan, int width, int height) {
        for (int row = 0; row < height; row++) {
            java.util.Arrays.fill(values, offset + row * scan, offset + row * scan + width, (byte) 0);
        }
    }

    private static void clearDestination(short[] values, int offset, int scan, int width, int height) {
        for (int row = 0; row < height; row++) {
            java.util.Arrays.fill(values, offset + row * scan, offset + row * scan + width, (short) 0);
        }
    }
}
