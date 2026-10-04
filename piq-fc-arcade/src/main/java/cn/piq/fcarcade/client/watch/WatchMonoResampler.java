// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.fcarcade.client.watch;

import java.util.Arrays;

/** Presentation-only 44.1 kHz mono to 48 kHz stereo. Integer phase survives packet boundaries. */
final class WatchMonoResampler {
    private long index,next;
    private float previous;
    void reset(){index=next=0;previous=0;}
    short[] convert(float[] source){
        if(source.length>32768)throw new IllegalArgumentException("Oversized observer audio");
        short[] out=new short[((source.length*160+146)/147+2)*2];int count=0;
        for(float sample:source){
            float current=Float.isFinite(sample)?Math.max(-1,Math.min(1,sample)):0;
            if(index==0)previous=current;
            long boundary=index*48000;
            while(next<=boundary){
                double fraction=index==0?1:(next-(boundary-48000))/48000.0;
                double value=previous+(current-previous)*fraction;
                short pcm=(short)Math.max(-32768,Math.min(32767,Math.round(value*32768)));
                out[count++]=pcm;out[count++]=pcm;next+=44100;
            }
            previous=current;index++;
        }
        return Arrays.copyOf(out,count);
    }
}
