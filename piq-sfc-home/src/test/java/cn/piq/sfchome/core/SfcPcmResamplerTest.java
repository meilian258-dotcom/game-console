package cn.piq.sfchome.core;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
class SfcPcmResamplerTest {
    @Test void exactly48kPerSecondAndStereoPreserved(){var r=new SfcPcmResampler();long count=0;for(int n=0;n<120;n++){
        short[] in=new short[534];for(int i=0;i<in.length;i+=2){in[i]=1234;in[i+1]=-4567;}
        short[] out=r.convert(in);count+=out.length/2;assertEquals(1234,out[out.length-2]);assertEquals(-4567,out[out.length-1]);
    }assertEquals(48000,count);assertEquals(0,r.phase);}
    @Test void chunkBoundariesDoNotAffectPcm(){short[] in=new short[3204];for(int i=0;i<in.length;i++)in[i]=(short)(i*13-19000);var a=new SfcPcmResampler();var b=new SfcPcmResampler();short[] expected=a.convert(in);short[] first=b.convert(Arrays.copyOfRange(in,0,802)),last=b.convert(Arrays.copyOfRange(in,802,in.length));short[] actual=Arrays.copyOf(first,first.length+last.length);System.arraycopy(last,0,actual,first.length,last.length);assertArrayEquals(expected,actual);assertEquals(a.phase,b.phase);}
    @Test void rejectsOddAndOverlargeAndResetClears(){var r=new SfcPcmResampler();assertThrows(IllegalArgumentException.class,()->r.convert(new short[3]));assertThrows(IllegalArgumentException.class,()->r.convert(new short[8194]));r.convert(new short[]{1,2});r.clear();assertEquals(0,r.phase);assertEquals(0,r.left);assertEquals(0,r.right);}
}
