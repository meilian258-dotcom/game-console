package cn.piq.fcarcade.client.watch;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WatchMonoResamplerTest {
    @Test void oneSecondHasCorrectRateAndDualMonoChannels(){
        float[] source=new float[44100];Arrays.fill(source,.25f);var converter=new WatchMonoResampler();
        short[] first=converter.convert(Arrays.copyOfRange(source,0,22050));
        short[] second=converter.convert(Arrays.copyOfRange(source,22050,44100));
        assertEquals(47999,first.length/2+second.length/2); // final interpolation waits for the next input sample
        for(short sample:first)assertEquals(8192,sample);
        assertEquals(2,converter.convert(new float[]{.25f}).length/2);
    }
    @Test void packetBoundariesDoNotChangeSamples(){
        float[] source=new float[2048];for(int i=0;i<source.length;i++)source[i]=(float)Math.sin(i*.09);
        short[] expected=new WatchMonoResampler().convert(source);var split=new WatchMonoResampler();
        short[] actual=new short[expected.length];int at=0;
        for(int from=0;from<source.length;from+=37){var part=split.convert(Arrays.copyOfRange(source,from,Math.min(from+37,source.length)));System.arraycopy(part,0,actual,at,part.length);at+=part.length;}
        assertEquals(expected.length,at);assertArrayEquals(expected,actual);
    }
    @Test void resetDiscardsPreviousSourcePhaseAndInvalidFloatsAreSilent(){
        var converter=new WatchMonoResampler();converter.convert(new float[]{1,1,1});converter.reset();
        assertArrayEquals(new WatchMonoResampler().convert(new float[]{0,.5f}),converter.convert(new float[]{0,.5f}));
        converter.reset();for(short value:converter.convert(new float[]{Float.NaN,Float.POSITIVE_INFINITY}))assertEquals(0,value);
        assertThrows(IllegalArgumentException.class,()->converter.convert(new float[32769]));
    }
}
