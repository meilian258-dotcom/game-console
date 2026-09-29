package cn.piq.sfchome.client.cabinet;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SfcCabinetPortReleaseTest {
    @Test void p2ExitClearsHeldAndQueuedP2ButPreservesAllP1Edges(){
        var q=new SfcCabinetInputs();q.offer(1,256);q.nextFrame();q.offer(2,512);q.offer(0,0);q.offer(4,1024);q.releasePort(1);
        for(int expected:new int[]{2,0,4,4})assertEquals(new SfcCabinetInputs.Pair(expected,0),q.nextFrame());
    }
    @Test void p1ReleaseIsSymmetricAndDoesNotSwallowP2QuickTaps(){
        var q=new SfcCabinetInputs();q.offer(1,256);q.nextFrame();q.offer(2,512);q.offer(4,0);q.offer(8,1024);q.releasePort(0);
        for(int expected:new int[]{512,0,1024,1024})assertEquals(new SfcCabinetInputs.Pair(0,expected),q.nextFrame());
    }
    @Test void releaseChangesWantedSoNewLeaseMayPressSameOldMask(){
        var q=new SfcCabinetInputs();q.offer(1,256);q.nextFrame();q.releasePort(1);assertEquals(new SfcCabinetInputs.Pair(1,0),q.nextFrame());
        assertTrue(q.offer(1,256));assertEquals(new SfcCabinetInputs.Pair(1,256),q.nextFrame());
    }
    @Test void invalidPortDoesNotMutateEitherPlayer(){
        var q=new SfcCabinetInputs();q.offer(1,256);
        assertThrows(IllegalArgumentException.class,()->q.releasePort(-1));assertThrows(IllegalArgumentException.class,()->q.releasePort(2));
        assertEquals(new SfcCabinetInputs.Pair(1,256),q.nextFrame());
    }
    @Test void fullQueueReleaseDoesNotDropOtherPlayerTransitions(){
        var q=new SfcCabinetInputs();for(int i=0;i<128;i++)assertTrue(q.offer((i&1)+1,256));q.releasePort(1);
        for(int i=0;i<128;i++)assertEquals(new SfcCabinetInputs.Pair((i&1)+1,0),q.nextFrame());
    }
}
