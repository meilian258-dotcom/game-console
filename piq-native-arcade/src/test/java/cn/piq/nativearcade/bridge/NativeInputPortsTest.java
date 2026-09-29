package cn.piq.nativearcade.bridge;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeInputPortsTest {
    @Test void everyPortAndSixteenBitsSurvive(){for(int p=0;p<4;p++)for(int bit=0;bit<16;bit++){var q=new NativeInputPorts();int[] masks=new int[4];masks[p]=1<<bit;assertTrue(q.offer(masks[0],masks[1],masks[2],masks[3]));assertArrayEquals(masks,q.nextFrame());}}
    @Test void eachFrameConsumesOneEdgePerPortNotOneEdgeForAllPlayers(){var q=new NativeInputPorts();assertTrue(q.offer(1,2,4,8));assertTrue(q.offer(0,0,0,0));assertTrue(q.offer(16,32,64,128));assertArrayEquals(new int[]{1,2,4,8},q.nextFrame());assertArrayEquals(new int[4],q.nextFrame());assertArrayEquals(new int[]{16,32,64,128},q.nextFrame());}
    @Test void independentReleasePreservesOthersHeldAndAllQueuedTransitions(){for(int p=0;p<4;p++){var q=new NativeInputPorts();assertTrue(q.offer(1,2,4,8));q.nextFrame();assertTrue(q.offer(16,32,64,128));assertTrue(q.offer(0,0,0,0));q.releasePort(p);assertEquals(0,q.pending(p));int[] next={16,32,64,128};next[p]=0;assertArrayEquals(next,q.nextFrame());assertArrayEquals(new int[4],q.nextFrame());}}
    @Test void clearingUnusedPortCannotDropQuickTapOnAnotherPort(){var q=new NativeInputPorts();assertTrue(q.offer(1,0,4,0));assertTrue(q.offer(0,0,0,0));q.releasePort(1);q.releasePort(3);assertArrayEquals(new int[]{1,0,4,0},q.nextFrame());assertArrayEquals(new int[4],q.nextFrame());}
    @Test void sameMasksDoNotFillQueues(){var q=new NativeInputPorts();for(int n=0;n<1000;n++)assertTrue(q.offer(1,2,3,4));for(int p=0;p<4;p++)assertEquals(1,q.pending(p));}
    @Test void overflowingOnePortRejectsAllPortsAtomically(){var q=new NativeInputPorts();for(int n=0;n<128;n++)assertTrue(q.offer((n&1)==0?1:0,0,0,0));assertFalse(q.offer(1,2,4,8));for(int p=1;p<4;p++)assertEquals(0,q.pending(p));assertEquals(128,q.pending(0));}
    @Test void releasedPortCanStartFreshWithoutReplayingOldPress(){var q=new NativeInputPorts();assertTrue(q.offer(0,0,1,0));assertTrue(q.offer(0,0,2,0));q.releasePort(2);assertTrue(q.offer(0,0,4,0));assertArrayEquals(new int[]{0,0,4,0},q.nextFrame());assertEquals(0,q.pending(2));}
    @Test void invalidMasksAndPortsDoNotAlterState(){var q=new NativeInputPorts();assertTrue(q.offer(1,2,4,8));assertThrows(IllegalArgumentException.class,()->q.offer(16,32,65536,128));assertThrows(IllegalArgumentException.class,()->q.offer(-1,0,0,0));assertThrows(IllegalArgumentException.class,()->q.releasePort(-1));assertThrows(IllegalArgumentException.class,()->q.releasePort(4));assertArrayEquals(new int[]{1,2,4,8},q.nextFrame());}
    @Test void returnedFrameCannotMutateHeldState(){var q=new NativeInputPorts();assertTrue(q.offer(1,2,4,8));int[] got=q.nextFrame();got[0]=65535;assertArrayEquals(new int[]{1,2,4,8},q.nextFrame());}
    @Test void wholeSessionClearRemainsExplicit(){var q=new NativeInputPorts();assertTrue(q.offer(1,2,4,8));q.nextFrame();assertTrue(q.offer(16,32,64,128));q.clear();for(int p=0;p<4;p++)assertEquals(0,q.pending(p));assertArrayEquals(new int[4],q.nextFrame());}
}
