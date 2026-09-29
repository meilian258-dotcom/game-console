package cn.piq.fcarcade.server.hosted;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class NesHostedAudioTest {
    @Test void frameChunksDoNotChangeAudioBytesOrAccumulateSampleDrift(){
        float[] input=new float[2940];for(int i=0;i<input.length;i++)input[i]=(float)Math.sin(i*.1);
        var full=new NesHostedAudio();short[] expected=full.convert(input,input.length);var split=new NesHostedAudio();short[] joined=new short[expected.length];int at=0;
        for(int i=0;i<4;i++){var chunk=Arrays.copyOfRange(input,i*735,(i+1)*735);var encoded=split.convert(chunk,chunk.length);System.arraycopy(encoded,0,joined,at,encoded.length);at+=encoded.length;}
        assertEquals(expected.length,at);assertArrayEquals(expected,joined);assertTrue(Math.abs(expected.length/2-3200)<=1);
        for(int i=0;i<joined.length;i+=2)assertEquals(joined[i],joined[i+1]);
    }
    @Test void resetRestoresFilterAndResamplePhase(){
        float[] input={0,.1F,.4F,.2F,-.2F};var audio=new NesHostedAudio();var first=audio.convert(input,input.length);audio.convert(input,input.length);audio.reset();assertArrayEquals(first,audio.convert(input,input.length));
    }
    @Test void silenceBoundsAndNonFiniteValues(){
        assertArrayEquals(new short[0],new NesHostedAudio().convert(new float[0],0));for(short sample:new NesHostedAudio().convert(new float[735],735))assertEquals(0,sample);
        assertThrows(IllegalArgumentException.class,()->new NesHostedAudio().convert(new float[]{Float.NaN},1));assertThrows(IllegalArgumentException.class,()->new NesHostedAudio().convert(new float[1],2));
    }
    @Test void libretroFcMappingsKeepFaceButtonsAndDirectionsDistinct(){
        assertEquals(1,NesServerCoreFactory.nesMask(1<<8));assertEquals(2,NesServerCoreFactory.nesMask(1));
        for(int i=2;i<=7;i++)assertEquals(1<<i,NesServerCoreFactory.nesMask(1<<i));
        assertEquals(0,NesServerCoreFactory.nesMask((1<<1)|(1<<9)|(1<<10)|(1<<11)));assertThrows(IllegalArgumentException.class,()->NesServerCoreFactory.nesMask(-1));
    }
}
