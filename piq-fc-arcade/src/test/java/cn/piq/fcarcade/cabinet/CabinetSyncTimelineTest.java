package cn.piq.fcarcade.cabinet;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CabinetSyncTimelineTest {
    @Test void sameTickTapRetainsBothEdgesForEveryPhysicalPort(){for(int port=0;port<4;port++){var t=new CabinetSyncTimeline(60000);assertTrue(t.input(port,1<<port));assertTrue(t.input(port,0));var frames=t.tick();assertEquals(1<<port,frames.get(0).masks()[port]);assertEquals(0,frames.get(1).masks()[port]);assertEquals(0,frames.get(2).masks()[port]);}}
    @Test void releaseFlushesOnlyDepartingPortsCurrentAndQueuedInput(){var t=new CabinetSyncTimeline(60000);for(int i=0;i<4;i++)t.input(i,1<<i);t.tick();t.input(2,16);t.input(2,0);t.release(2);for(var s:t.tick()){assertArrayEquals(new int[]{1,2,0,8},s.masks());}}
    @Test void ntscAndPalUseExactFractionalServerTickAccumulator(){for(int fps:new int[]{50000,60000,60098}){var t=new CabinetSyncTimeline(fps);for(int i=0;i<2000;i++)t.tick();assertEquals(fps/10,t.frame());}}
    @Test void overloadedPortFailsClosedUntilGenuineNeutral(){var t=new CabinetSyncTimeline(60000);for(int i=0;i<32;i++)assertTrue(t.input(1,(i&1)+1));assertFalse(t.input(1,4));t.input(0,8);for(var s:t.tick()){assertEquals(0,s.p2());assertEquals(8,s.p1());}assertFalse(t.input(1,4));assertTrue(t.input(1,0));assertTrue(t.input(1,4));assertEquals(4,t.tick().getFirst().p2());}
    @Test void historyHasNoGapsAndIsBounded(){var t=new CabinetSyncTimeline(60000);for(int i=0;i<2401;i++)t.tick();assertThrows(IllegalArgumentException.class,()->t.after(0,120));var steps=t.after(3,120);assertEquals(4,steps.getFirst().frame());assertEquals(123,steps.getLast().frame());assertThrows(IllegalArgumentException.class,()->t.after(t.frame()+1,1));}
    @Test void noInvalidFrameRatePortOrMask(){assertThrows(IllegalArgumentException.class,()->new CabinetSyncTimeline(0));var t=new CabinetSyncTimeline(60000);assertThrows(IllegalArgumentException.class,()->t.release(4));assertFalse(t.input(0,4096));assertEquals(0,t.tick().getFirst().p1());}
}
