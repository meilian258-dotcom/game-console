package cn.piq.retro.libretro;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;

class LibretroFrameConverterTest {
    @Test void videoChannelsAndRotationArePreserved() {
        var info = new LibretroProcess.Info(1,1,1,1,1,60,48000,1);
        var frame = new LibretroFrameConverter().convert(new LibretroProcess.Output(info,false,new byte[]{1,2,3,(byte)255},new short[]{4,5},new byte[0]),3);
        assertEquals(0xff030201, frame.abgr()[0]); assertEquals(3, frame.rotation()); assertArrayEquals(new short[]{4,5},frame.pcm48k());
    }
    @Test void chunkingDoesNotChangeContinuousResampling() {
        short[] source = new short[12000]; for(int i=0;i<source.length;i++)source[i]=(short)(Math.sin(i*.025)*10000);
        for(double rate:new double[]{32040,44100,96000}) {
            short[] whole = new LibretroFrameConverter().audio48k(source,rate);
            var stream=new LibretroFrameConverter();var chunks=new ArrayList<Short>();
            for(int i=0;i<source.length;i+=200)for(short v:stream.audio48k(Arrays.copyOfRange(source,i,i+200),rate))chunks.add(v);
            assertEquals(whole.length,chunks.size());for(int i=0;i<whole.length;i++)assertEquals(whole[i],chunks.get(i));
        }
    }
    @Test void constantStereoAndReset() {
        short[] in=new short[882];for(int i=0;i<in.length;i+=2){in[i]=1234;in[i+1]=-500;}
        var c=new LibretroFrameConverter();var initial=c.audio48k(in,44100);
        for(int i=0;i<initial.length;i+=2){assertEquals(1234,initial[i]);assertEquals(-500,initial[i+1]);}
        c.audio48k(in,44100);c.reset();assertArrayEquals(initial,c.audio48k(in,44100));
    }
    @Test void rejectsUnboundedAndMalformedAudio() {
        var c=new LibretroFrameConverter();assertThrows(IllegalArgumentException.class,()->c.audio48k(new short[1],48000));
        assertThrows(IllegalArgumentException.class,()->c.audio48k(new short[32768],8000));
        assertThrows(IllegalArgumentException.class,()->c.audio48k(new short[2],Double.NaN));
    }
}
