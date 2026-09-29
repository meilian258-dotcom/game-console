package cn.piq.fcarcade.server.hosted;

import java.util.Arrays;

/** Stateful 44.1k mono -> 48k stereo linear resampler; no client mixer or audio-device dependency. */
public final class NesHostedAudio {
    private float previousInput,previousOutput,previousFiltered;private long sourceTime=-48000,nextOutput;
    public short[] convert(float[] samples,int count){
        if(count<0||count>4096||count>samples.length)throw new IllegalArgumentException("NES audio bounds");
        short[] result=new short[(int)Math.ceil(count*48000D/44100D+2)*2];int size=0;
        for(int i=0;i<count;i++){
            float input=samples[i];if(!Float.isFinite(input))throw new IllegalArgumentException("Non-finite NES audio");
            float filtered=input-previousInput+.995F*previousOutput;previousInput=input;previousOutput=filtered;
            sourceTime+=48000;
            while(nextOutput<=sourceTime){
                double fraction=sourceTime==0?1D:(nextOutput-(sourceTime-48000))/48000D;
                float value=(float)(previousFiltered+(filtered-previousFiltered)*fraction);
                short scaled=(short)Math.round(Math.max(-1D,Math.min(1D,value*1.6D))*32767D);
                result[size++]=scaled;result[size++]=scaled;nextOutput+=44100;
            }
            previousFiltered=filtered;
        }
        return Arrays.copyOf(result,size);
    }
    public void reset(){previousInput=previousOutput=previousFiltered=0;sourceTime=-48000;nextOutput=0;}
}
