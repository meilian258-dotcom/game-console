package cn.piq.fcarcade.client;

import cn.piq.fcarcade.core.NesCore;
import cn.piq.fcarcade.server.hosted.NesHostedAudio;
import cn.piq.retro.api.RetroFrame;

/** Single core-thread owned conversion, independent of Minecraft and local sound gain. */
final class FcMediaFrames {
    private final NesHostedAudio audio = new NesHostedAudio();
    RetroFrame copy(byte[] rgba, float[] mono, int count) {
        if (rgba.length != NesCore.RGBA_BYTES) throw new IllegalArgumentException("FC frame size");
        int[] abgr = new int[NesCore.WIDTH * NesCore.HEIGHT];
        for (int i = 0; i < abgr.length; i++) {
            int at = i * 4;
            abgr[i] = 0xff000000 | (rgba[at] & 255) | (rgba[at + 1] & 255) << 8 | (rgba[at + 2] & 255) << 16;
        }
        return new RetroFrame(NesCore.WIDTH, NesCore.HEIGHT, abgr, 4F / 3F, 0, audio.convert(mono, count));
    }
    static RetroFrame silent(RetroFrame f) {
        return new RetroFrame(f.width(), f.height(), f.abgr(), f.displayAspect(), f.rotation(), new short[0]);
    }
}
