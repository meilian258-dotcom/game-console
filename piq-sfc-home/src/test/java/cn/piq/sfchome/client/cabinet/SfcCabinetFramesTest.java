package cn.piq.sfchome.client.cabinet;
import cn.piq.sfcarcade.core.SfcVideoMode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SfcCabinetFramesTest {
    static final SfcVideoMode MODE=new SfcVideoMode(1,2,8,2,50);
    static byte[] pixels(){return new byte[]{1,2,3,0,99,99,99,99,4,5,6,0,99,99,99,99};}
    @Test void strideOpaqueAbgrAspectAndRotation(){var q=new SfcCabinetFrames();q.publish(MODE,pixels(),new short[]{1,2},2);var f=q.poll();assertArrayEquals(new int[]{0xff030201,0xff060504},f.abgr());assertEquals(1f,f.displayAspect());assertEquals(0,f.rotation());assertNull(q.poll());}
    @Test void audioAggregatesAcrossSkippedPicturesAndClones(){var q=new SfcCabinetFrames();short[] pcm={1,2};byte[] rgba=pixels();q.publish(MODE,rgba,pcm,2);pcm[0]=99;rgba[0]=99;q.publish(MODE,pixels(),new short[]{3,4},2);var f=q.poll();assertArrayEquals(new short[]{1,2,3,4},f.pcm48k());assertEquals(0xff030201,f.abgr()[0]);}
    @Test void overrunKeepsBoundedNewestStereoSamples(){var q=new SfcCabinetFrames();short[] pcm=new short[SfcCabinetFrames.MAX_PCM];for(int i=0;i<pcm.length;i++)pcm[i]=(short)i;q.publish(MODE,pixels(),pcm,pcm.length);q.publish(MODE,pixels(),new short[]{7,8},2);var f=q.poll();assertEquals(32768,f.pcm48k().length);assertEquals(2,f.pcm48k()[0]);assertEquals(7,f.pcm48k()[32766]);assertEquals(8,f.pcm48k()[32767]);}
    @Test void clearDiscardsPictureAndAudio(){var q=new SfcCabinetFrames();q.publish(MODE,pixels(),new short[]{1,2},2);q.clear();assertNull(q.poll());q.publish(MODE,pixels(),new short[0],0);assertEquals(0,q.poll().pcm48k().length);}
    @Test void mismatchedRgbaOrOddPcmRejected(){var q=new SfcCabinetFrames();assertThrows(IllegalArgumentException.class,()->q.publish(MODE,new byte[8],new short[0],0));assertThrows(IllegalArgumentException.class,()->q.publish(MODE,pixels(),new short[]{1},1));assertThrows(IllegalArgumentException.class,()->q.publish(MODE,pixels(),new short[]{1,2},4));}
}
