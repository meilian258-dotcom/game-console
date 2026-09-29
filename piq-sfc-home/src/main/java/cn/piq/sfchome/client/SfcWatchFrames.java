package cn.piq.sfchome.client;

import cn.piq.retro.api.RetroFrame;
import java.util.Arrays;

/** Copies the core's borrowed RGBA/PCM arrays; never exposes core-owned memory to a worker. */
final class SfcWatchFrames {
    private SfcWatchFrames() {}

    static RetroFrame copy(int width,int height,int stride,float aspect,byte[] rgba,
                           short[] pcm,int stereoFrames) {
        if(width<1||width>512||height<1||height>478||stride<(long)width*4
                ||rgba==null||(long)stride*height>rgba.length
                ||!Float.isFinite(aspect)||aspect<.25f||aspect>4f
                ||stereoFrames<0||stereoFrames>4096||pcm==null||(long)stereoFrames*2>pcm.length)
            throw new IllegalArgumentException("Invalid SFC spectator frame");
        int[] pixels=new int[width*height];
        for(int y=0;y<height;y++)for(int x=0;x<width;x++) {
            int p=y*stride+x*4;
            pixels[y*width+x]=(rgba[p]&255)|((rgba[p+1]&255)<<8)
                    |((rgba[p+2]&255)<<16)|((rgba[p+3]&255)<<24);
        }
        return new RetroFrame(width,height,pixels,aspect,0,Arrays.copyOf(pcm,stereoFrames*2));
    }

    /** Pixel array is already immutable after copy; pauses must never replay its sound. */
    static RetroFrame silent(RetroFrame frame) {
        return new RetroFrame(frame.width(),frame.height(),frame.abgr(),frame.displayAspect(),0,new short[0]);
    }
}
