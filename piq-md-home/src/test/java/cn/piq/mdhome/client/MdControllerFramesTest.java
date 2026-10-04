// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.mdhome.client;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class MdControllerFramesTest {
    @Test void everyCanonicalBitRetainsAShortCompletedPressExactlyOnce(){
        for(int bit=0;bit<12;bit++){var f=new MdControllerFrames();f.complete(0,1<<bit,0);f.complete(0,0,0);assertEquals(1<<bit,f.present(0));assertEquals(0,f.present(0));assertEquals(0,f.present(1));}
    }
    @Test void clearingRejectsAnOldWorkerFrame(){var f=new MdControllerFrames();f.complete(0,4095,4095);f.clear(1);f.complete(0,1,2);assertEquals(0,f.present(0));assertEquals(0,f.present(1));f.complete(1,4,8);assertEquals(4,f.present(0));assertEquals(8,f.present(1));}
    @Test void visualGenerationIsIndependentOfGameInputGeneration(){
        var f=new MdControllerFrames();long sampledVisualEpoch=0;f.clear(1);f.complete(sampledVisualEpoch,2,0);assertEquals(0,f.present(0));
        f.complete(1,8,0);assertEquals(8,f.present(0));
    }
    @Test void releaseDoesNotReleaseTheOtherPortOrAcceptOldCompletion(){var f=new MdControllerFrames();f.complete(0,256,2048);f.release(0,1);f.complete(0,256,0);assertEquals(0,f.present(0));assertEquals(2048,f.present(1));f.complete(1,0,2048);assertEquals(2048,f.present(1));}
    @Test void heldInputPersistsButPulsesAreBounded(){var f=new MdControllerFrames();for(int n=0;n<10000;n++){f.offer(1,1024);f.offer(1,0);}assertEquals(1024,f.present(1));assertEquals(0,f.present(1));f.offer(0,16|1);assertEquals(17,f.present(0));assertEquals(17,f.present(0));f.clear(0);assertEquals(0,f.present(0));}
    @Test void rejectsBadPortAndBits(){var f=new MdControllerFrames();assertThrows(IllegalArgumentException.class,()->f.offer(2,0));assertThrows(IllegalArgumentException.class,()->f.offer(0,4096));assertThrows(IllegalArgumentException.class,()->f.present(-1));}
}
