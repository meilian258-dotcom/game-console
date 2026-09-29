// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.retro.libretro;

import cn.piq.retro.api.RetroFrame;
import java.util.Arrays;

/** Owner-thread conversion to the public unrotated ABGR / 48 kHz stereo contract. */
public final class LibretroFrameConverter {
    private double sampleRate, nextSample;
    private long sourceFrames;
    private short previousLeft, previousRight;
    /** Reset on core reset, restore, pause discontinuity or content change. */
    public void reset() { sampleRate = nextSample = 0; sourceFrames = 0; previousLeft = previousRight = 0; }
    public RetroFrame convert(LibretroProcess.Output output, int clockwiseRotation) {
        var info = output.info(); int width = info.width(), height = info.height();
        if (width > 2048 || height > 2048 || output.rgba().length != (long) width * height * 4)
            throw new IllegalArgumentException("Frame exceeds public video contract");
        int[] pixels = new int[width * height]; byte[] rgba = output.rgba();
        for (int i = 0, j = 0; i < pixels.length; i++, j += 4)
            pixels[i] = (rgba[j] & 255) | ((rgba[j + 1] & 255) << 8) | ((rgba[j + 2] & 255) << 16) | ((rgba[j + 3] & 255) << 24);
        float aspect = info.aspect() > 0 ? info.aspect() : (float) width / height;
        return new RetroFrame(width, height, pixels, aspect, clockwiseRotation, audio48k(output.stereo(), info.sampleRate()));
    }
    public short[] audio48k(short[] stereo, double sourceRate) {
        if (stereo == null || stereo.length > 32768 || (stereo.length & 1) != 0
                || !Double.isFinite(sourceRate) || sourceRate < 8000 || sourceRate > 192000)
            throw new IllegalArgumentException("Source audio bounds");
        if (sampleRate != sourceRate) { reset(); sampleRate = sourceRate; }
        if (sourceRate == 48000) return stereo.clone();
        if (stereo.length == 0) return new short[0];
        long finalIndex = sourceFrames + stereo.length / 2 - 1;
        double step = sourceRate / 48000;
        int count = (int) Math.max(0, Math.floor((finalIndex - nextSample) / step + 1e-9) + 1);
        if (count > 16384) throw new IllegalArgumentException("Converted audio exceeds public frame budget");
        short[] result = new short[count * 2]; int offset = 0;
        for (int i = 0; i < stereo.length; i += 2) {
            long index = sourceFrames++; short left = stereo[i], right = stereo[i + 1];
            while (nextSample <= index + 1e-9) {
                double fraction = index == 0 ? 1 : Math.clamp(nextSample - (index - 1), 0, 1);
                if (offset + 2 > result.length) result = Arrays.copyOf(result, offset + 2);
                if (result.length > 32768) throw new IllegalArgumentException("Converted audio budget");
                result[offset++] = (short) Math.round(previousLeft + (left - previousLeft) * fraction);
                result[offset++] = (short) Math.round(previousRight + (right - previousRight) * fraction);
                nextSample += step;
            }
            previousLeft = left; previousRight = right;
        }
        return offset == result.length ? result : Arrays.copyOf(result, offset);
    }
}
