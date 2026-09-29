// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.mdhome.client;
import java.util.Arrays;
/** Streaming stereo adapter to the platform's 48 kHz presentation contract. */
public final class MdAudio {
    private long index,next;private int rate,left,right;private boolean previous;
    public short[] convert(short[] in,int hz){
        if(in.length>32768||(in.length&1)!=0||hz<8000||hz>192000)throw new IllegalArgumentException("MD PCM bounds");
        if(rate!=hz){index=next=0;previous=false;rate=hz;}
        if(hz==48000)return in.clone();
        short[] out=new short[Math.min(32768,(int)Math.ceil((in.length/2.0+2)*48000/hz)*2)];int at=0;
        for(int i=0;i<in.length;i+=2,index++){
            int l=in[i],r=in[i+1];if(!previous){left=l;right=r;previous=true;}
            while(next<=index*48000){long f=Math.clamp(next-(index-1)*48000,0,48000);if(at+2>out.length)throw new IllegalArgumentException("MD resample overflow");out[at++]=(short)(left+(l-left)*f/48000);out[at++]=(short)(right+(r-right)*f/48000);next+=hz;}
            left=l;right=r;
        }return Arrays.copyOf(out,at);
    }
}
