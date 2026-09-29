// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.retro.api;

/**
 * One completed, unrotated video/audio frame transferred from an emulator worker.
 * Pixels are row-major packed {@code 0xAABBGGRR}; on little-endian upload their
 * bytes are RGBA8. PCM is signed 16-bit, interleaved stereo at 48,000 Hz.
 * {@code displayAspect} is the raw, unrotated display width/height ratio, not
 * necessarily the pixel ratio. {@code rotation} is clockwise quarter turns;
 * the presenter applies it and inverts the display ratio once for odd turns.
 *
 * <p>Arrays are deliberately not cloned. Publishing transfers their ownership:
 * the producer must never reuse or mutate them, and consumers must treat them
 * as read-only. The limits match the existing cabinet boundary exactly.</p>
 */
public record RetroFrame(int width, int height, int[] abgr, float displayAspect,
                         int rotation, short[] pcm48k) {
    public RetroFrame {
        if (width < 1 || height < 1 || width > 2048 || height > 2048
                || abgr == null || abgr.length != (long) width * height
                || !Float.isFinite(displayAspect) || displayAspect < .1F || displayAspect > 10F
                || rotation < 0 || rotation > 3
                || pcm48k == null || pcm48k.length > 32768 || (pcm48k.length & 1) != 0) {
            throw new IllegalArgumentException("Invalid retro frame");
        }
    }
}
