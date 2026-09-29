// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.gba.bridge;

import java.util.Arrays;

/** Bounded streaming stereo conversion, keeping fractional phase across callbacks/frames. */
public final class PcmResampler {
    private long inputIndex,nextOutput; private int rate,lastLeft,lastRight; private boolean haveLast;
    public short[] convert(short[] input,int count,int sampleRate){
        if(input==null||count<0||count>input.length||count>32768||(count&1)!=0||sampleRate<8000||sampleRate>192000)throw new IllegalArgumentException("PCM bounds");
        if(rate!=sampleRate){reset();rate=sampleRate;}
        if(sampleRate==48000)return Arrays.copyOf(input,count);
        short[] out=new short[Math.min(32768,(int)Math.ceil((count/2.0+2)*48000/sampleRate)*2)];int at=0;
        for(int i=0;i<count;i+=2,inputIndex++){
            int left=input[i],right=input[i+1];if(!haveLast){lastLeft=left;lastRight=right;haveLast=true;}
            while(nextOutput<=inputIndex*48000){
                long fraction=nextOutput-(inputIndex-1)*48000;fraction=Math.max(0,Math.min(48000,fraction));
                if(at+2>out.length)throw new IllegalStateException("Resampled PCM overflow");
                out[at++]=(short)(lastLeft+(left-lastLeft)*fraction/48000);
                out[at++]=(short)(lastRight+(right-lastRight)*fraction/48000);
                nextOutput+=rate;
            }
            lastLeft=left;lastRight=right;
        }
        return Arrays.copyOf(out,at);
    }
    public void reset(){inputIndex=nextOutput=0;haveLast=false;rate=0;}
}
