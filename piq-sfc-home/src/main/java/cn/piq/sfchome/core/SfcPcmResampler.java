// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfchome.core;

import java.util.Arrays;

/** Streaming causal linear interpolation from the pinned Mesen-S 32040 Hz to our 48000 Hz contract. */
final class SfcPcmResampler {
    static final int INPUT=32040, OUTPUT=48000;
    int phase; short left,right;
    short[] convert(short[] input) {
        if((input.length&1)!=0||input.length>8192)throw new IllegalArgumentException("SFC PCM bounds");
        short[] output=new short[(input.length/2*OUTPUT/INPUT+2)*2];int count=0;
        for(int i=0;i<input.length;i+=2){
            phase+=OUTPUT;
            while(phase>=INPUT){
                phase-=INPUT;int weight=OUTPUT-phase;
                output[count++]=(short)(left+((long)(input[i]-left)*weight)/OUTPUT);
                output[count++]=(short)(right+((long)(input[i+1]-right)*weight)/OUTPUT);
            }
            left=input[i];right=input[i+1];
        }
        return Arrays.copyOf(output,count);
    }
    void clear(){phase=0;left=right=0;}
}
