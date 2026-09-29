package cn.piq.sfchome.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SfcWatchFramesTest {
    @Test void rgbaIsConvertedToAbgrWithRowPadding(){
        byte[] rgba={1,2,3,4,99,99,99,99,5,6,7,8,99,99,99,99};
        var frame=SfcWatchFrames.copy(1,2,8,.5f,rgba,new short[0],0);
        assertArrayEquals(new int[]{0x04030201,0x08070605},frame.abgr());
        assertEquals(.5f,frame.displayAspect());assertEquals(0,frame.rotation());
    }
    @Test void stereoFramesAreTwoShortsAndOnlyValidAudioIsCopied(){
        short[] pcm={-32768,32767,123,-456,88,99};
        var frame=SfcWatchFrames.copy(1,1,4,1,new byte[4],pcm,2);
        assertArrayEquals(new short[]{-32768,32767,123,-456},frame.pcm48k());
        pcm[0]=0;assertEquals(-32768,frame.pcm48k()[0]);
    }
    @Test void borrowedPixelsCannotMutatePublishedFrame(){
        byte[] rgba={(byte)255,1,2,(byte)255};
        var frame=SfcWatchFrames.copy(1,1,4,1,rgba,new short[0],0);
        java.util.Arrays.fill(rgba,(byte)0);assertEquals(0xff0201ff,frame.abgr()[0]);
    }
    @Test void pauseResendsPixelsButNeverReplaysAudio(){
        var active=SfcWatchFrames.copy(1,1,4,1,new byte[]{1,2,3,4},new short[]{11,22},1);
        var still=SfcWatchFrames.silent(active);
        assertSame(active.abgr(),still.abgr());assertEquals(0,still.pcm48k().length);
        for(int i=0;i<60;i++){still=SfcWatchFrames.silent(still);assertEquals(0,still.pcm48k().length);}
        assertArrayEquals(new short[]{11,22},active.pcm48k());
    }
    @Test void maximumCoreModeAndAudioAreAccepted(){
        var frame=SfcWatchFrames.copy(512,478,2048,4f/3,new byte[2048*478],new short[8192],4096);
        assertEquals(512*478,frame.abgr().length);assertEquals(8192,frame.pcm48k().length);
    }
    @Test void invalidAndOverflowingStridesFailBeforeAccess(){
        for(int stride:new int[]{-1,0,3,Integer.MAX_VALUE})
            assertThrows(IllegalArgumentException.class,()->SfcWatchFrames.copy(1,2,stride,1,new byte[8],new short[0],0));
        assertThrows(IllegalArgumentException.class,()->SfcWatchFrames.copy(512,479,2048,1,new byte[2048*479],new short[0],0));
        assertThrows(IllegalArgumentException.class,()->SfcWatchFrames.copy(513,1,2052,1,new byte[2052],new short[0],0));
    }
    @Test void invalidPcmFramesDoNotExposeStaleTail(){
        for(int count:new int[]{-1,2,4097,Integer.MAX_VALUE})
            assertThrows(IllegalArgumentException.class,()->SfcWatchFrames.copy(1,1,4,1,new byte[4],new short[2],count));
        assertThrows(IllegalArgumentException.class,()->SfcWatchFrames.copy(1,1,4,1,new byte[4],null,0));
    }
    @Test void invalidAspectAndMissingPixelsAreRejected(){
        for(float aspect:new float[]{Float.NaN,Float.POSITIVE_INFINITY,0,.249f,4.01f})
            assertThrows(IllegalArgumentException.class,()->SfcWatchFrames.copy(1,1,4,aspect,new byte[4],new short[0],0));
        assertThrows(IllegalArgumentException.class,()->SfcWatchFrames.copy(1,1,4,1,null,new short[0],0));
    }
}
