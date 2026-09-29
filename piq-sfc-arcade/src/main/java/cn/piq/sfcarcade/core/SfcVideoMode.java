// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfcarcade.core;

/** Per-frame display metadata. SNES games can change resolution while running. */
public record SfcVideoMode(
        int width,
        int height,
        int rowStrideBytes,
        double pixelAspectRatio,
        double targetFramesPerSecond
) {
    public static final int BYTES_PER_PIXEL = 4;
    public static final int MAX_WIDTH = 512;
    public static final int MAX_HEIGHT = 478;

    public SfcVideoMode {
        if (width <= 0 || width > MAX_WIDTH) {
            throw new IllegalArgumentException("Invalid SFC width: " + width);
        }
        if (height <= 0 || height > MAX_HEIGHT) {
            throw new IllegalArgumentException("Invalid SFC height: " + height);
        }
        if (rowStrideBytes < Math.multiplyExact(width, BYTES_PER_PIXEL)) {
            throw new IllegalArgumentException("RGBA row stride is too small: " + rowStrideBytes);
        }
        if (!Double.isFinite(pixelAspectRatio) || pixelAspectRatio <= 0.0) {
            throw new IllegalArgumentException("Invalid pixel aspect ratio: " + pixelAspectRatio);
        }
        if (!Double.isFinite(targetFramesPerSecond) || targetFramesPerSecond <= 0.0) {
            throw new IllegalArgumentException("Invalid target FPS: " + targetFramesPerSecond);
        }
    }

    public int requiredRgbaBytes() {
        return Math.multiplyExact(rowStrideBytes, height);
    }
}

