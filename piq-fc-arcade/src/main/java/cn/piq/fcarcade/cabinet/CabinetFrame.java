// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.fcarcade.cabinet;

/** Owned immutable-by-contract pixels (ABGR) and stereo PCM48k; raw, unrotated display aspect. */
public record CabinetFrame(int width, int height, int[] abgr, float displayAspect, int rotation, short[] pcm48k) {
    public CabinetFrame {
        if(width<1||height<1||width>2048||height>2048||abgr==null||abgr.length!=(long)width*height
                ||!Float.isFinite(displayAspect)||displayAspect<.1F||displayAspect>10F||rotation<0||rotation>3
                ||pcm48k==null||pcm48k.length>32768||(pcm48k.length&1)!=0)
            throw new IllegalArgumentException("Invalid cabinet frame");
    }
}
