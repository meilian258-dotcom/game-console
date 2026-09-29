package cn.piq.fcarcade.cabinet;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HostedInputQueueTest {
    @Test void tapEdgesSurviveBeforeWorkerPoll(){var q=new HostedInputQueue();assertTrue(q.offer(new int[]{1,0,0,0}));assertTrue(q.offer(new int[4]));assertEquals(1,q.poll()[0]);assertEquals(0,q.poll()[0]);assertNull(q.poll());}
    @Test void releaseStripsOnlyItsPortFromAllQueuedStates(){var q=new HostedInputQueue();q.offer(new int[]{1,2,3,4});q.offer(new int[]{5,6,7,8});q.release(1);assertArrayEquals(new int[]{1,0,3,4},q.poll());assertArrayEquals(new int[]{5,0,7,8},q.poll());}
    @Test void boundedQueueRejectsRatherThanDroppingARelease(){var q=new HostedInputQueue();for(int i=0;i<128;i++)assertTrue(q.offer(new int[]{i%2+1,0,0,0}));assertFalse(q.offer(new int[4]));for(int i=0;i<128;i++)assertNotNull(q.poll());assertNull(q.poll());assertTrue(q.offer(new int[4]));}
    @Test void callerArraysAreNotOwned(){var q=new HostedInputQueue();int[] a={1,2,3,4};q.offer(a);a[0]=4095;assertEquals(1,q.poll()[0]);}
    @Test void malformedAndUnsupportedBitsNeverEnterQueue(){var q=new HostedInputQueue();assertThrows(IllegalArgumentException.class,()->q.offer(null));assertThrows(IllegalArgumentException.class,()->q.offer(new int[]{1}));assertThrows(IllegalArgumentException.class,()->q.offer(new int[]{-1,0,0,0}));assertThrows(IllegalArgumentException.class,()->q.offer(new int[]{4096,0,0,0}));assertThrows(IllegalArgumentException.class,()->q.release(4));assertNull(q.poll());}
    @Test void identicalHeartbeatDoesNotGrowQueue(){var q=new HostedInputQueue();for(int i=0;i<1000;i++)assertTrue(q.offer(new int[]{1,0,0,0}));assertNotNull(q.poll());assertNull(q.poll());q.clear();assertTrue(q.offer(new int[4]));assertNull(q.poll());}
}
