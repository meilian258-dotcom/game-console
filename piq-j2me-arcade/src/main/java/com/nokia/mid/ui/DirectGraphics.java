package com.nokia.mid.ui;

import javax.microedition.lcdui.Image;

/** Public Nokia DirectGraphics API, implemented on top of MIDP 2 graphics. */
public interface DirectGraphics {
    int FLIP_HORIZONTAL = 0x2000;
    int FLIP_VERTICAL = 0x4000;
    int ROTATE_90 = 90;
    int ROTATE_180 = 180;
    int ROTATE_270 = 270;

    int TYPE_BYTE_1_GRAY = 1;
    int TYPE_BYTE_1_GRAY_VERTICAL = 2;
    int TYPE_BYTE_2_GRAY = 3;
    int TYPE_BYTE_4_GRAY = 4;
    int TYPE_BYTE_8_GRAY = 5;
    int TYPE_BYTE_332_RGB = 6;
    int TYPE_USHORT_444_RGB = 7;
    int TYPE_USHORT_4444_ARGB = 8;
    int TYPE_USHORT_555_RGB = 9;
    int TYPE_USHORT_1555_ARGB = 10;
    int TYPE_USHORT_565_RGB = 11;
    int TYPE_INT_888_RGB = 12;
    int TYPE_INT_8888_ARGB = 13;

    void setARGBColor(int argbColor);

    int getAlphaComponent();

    int getNativePixelFormat();

    void drawTriangle(int x1, int y1, int x2, int y2, int x3, int y3, int argbColor);

    void fillTriangle(int x1, int y1, int x2, int y2, int x3, int y3, int argbColor);

    void drawPolygon(int[] xPoints, int xOffset, int[] yPoints, int yOffset,
                     int nPoints, int argbColor);

    void fillPolygon(int[] xPoints, int xOffset, int[] yPoints, int yOffset,
                     int nPoints, int argbColor);

    void drawPixels(int[] pixels, boolean transparency, int offset, int scanLength,
                    int x, int y, int width, int height, int manipulation, int format);

    void drawPixels(byte[] pixels, byte[] transparencyMask, int offset, int scanLength,
                    int x, int y, int width, int height, int manipulation, int format);

    void drawPixels(short[] pixels, boolean transparency, int offset, int scanLength,
                    int x, int y, int width, int height, int manipulation, int format);

    void getPixels(int[] pixels, int offset, int scanLength, int x, int y,
                   int width, int height, int format);

    void getPixels(byte[] pixels, byte[] transparencyMask, int offset, int scanLength,
                   int x, int y, int width, int height, int format);

    void getPixels(short[] pixels, int offset, int scanLength, int x, int y,
                   int width, int height, int format);

    void drawImage(Image image, int x, int y, int anchor, int manipulation);
}
