package cn.piq.sfchome.server;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SfcInputTimelineTest {
    @Test void neutralStartsReleased(){assertEquals(0,new SfcInputTimeline().next());}
    @Test void shortPulseOccupiesOneFrame(){var q=new SfcInputTimeline();assertTrue(q.offer(0,1,false));assertTrue(q.offer(1,0,false));assertEquals(1,q.next());assertEquals(0,q.next());}
    @Test void twoPulsesAreNotMerged(){var q=new SfcInputTimeline();int[]v={1,0,1,0};for(int i=0;i<4;i++)assertTrue(q.offer(i,v[i],false));for(int n:v)assertEquals(n,q.next());}
    @Test void chordEdgesStayOrdered(){var q=new SfcInputTimeline();int[]v={1,2049,2048,0};for(int i=0;i<4;i++)q.offer(i,v[i],false);for(int n:v)assertEquals(n,q.next());}
    @Test void repeatDoesNotCreateTransitions(){var q=new SfcInputTimeline();q.offer(0,1,false);q.offer(1,1,false);assertEquals(1,q.pending());assertEquals(1,q.next());assertEquals(1,q.next());}
    @Test void allTwelveBitsSurvive(){var q=new SfcInputTimeline();assertTrue(q.offer(0,4095,false));assertEquals(4095,q.next());}
    @Test void replayAndInvalidMaskRejected(){var q=new SfcInputTimeline();assertTrue(q.offer(8,1,false));assertFalse(q.offer(8,0,true));assertFalse(q.offer(7,0,true));assertFalse(q.offer(9,4096,false));assertFalse(q.offer(9,1,true));assertEquals(1,q.next());}
    @Test void forceReleaseDiscardsOldEdges(){var q=new SfcInputTimeline();q.offer(0,1,false);q.offer(1,0,false);q.offer(2,2048,false);assertTrue(q.offer(3,0,true));assertEquals(0,q.pending());assertEquals(0,q.next());assertTrue(q.offer(4,8,false));assertEquals(8,q.next());}
    @Test void overflowFailsClosed(){var q=new SfcInputTimeline();for(int i=0;i<32;i++)assertTrue(q.offer(i,i%2==0?1:0,false));assertFalse(q.offer(32,2,false));assertEquals(0,q.next());assertEquals(0,q.pending());}
    @Test void clearDoesNotResetAntiReplay(){var q=new SfcInputTimeline();q.offer(20,1,false);q.clear();assertEquals(0,q.next());assertFalse(q.offer(19,1,false));assertTrue(q.offer(21,1,false));}
    @Test void stalePortDropsOldEdgesAndHeldHeartbeatUntilNeutral(){
        var q=new SfcInputTimeline();assertTrue(q.offer(10,1,false));assertEquals(1,q.next());
        assertTrue(q.offer(11,3,false));q.neutralizeStale();assertEquals(0,q.pending());assertEquals(0,q.next());
        for(int seq=12;seq<20;seq++){assertTrue(q.offer(seq,3,false));assertEquals(0,q.pending());assertEquals(0,q.next());}
        assertFalse(q.offer(19,0,true));assertTrue(q.offer(20,0,false));assertEquals(0,q.next());
        assertTrue(q.offer(21,3,false));assertEquals(3,q.next());
    }
    @Test void explicitRecoveryReleaseRearmsWithoutReplayingOldQueue(){
        var q=new SfcInputTimeline();q.offer(0,4095,false);q.neutralizeStale();
        assertTrue(q.offer(1,0,true));assertTrue(q.offer(2,2048,false));assertEquals(2048,q.next());assertEquals(0,q.pending());
    }
    @Test void ordinaryClearCannotAccidentallyUnlockStaleHeldKey(){
        var q=new SfcInputTimeline();q.offer(0,1,false);q.neutralizeStale();q.clear();
        assertTrue(q.offer(1,1,false));assertEquals(0,q.next());assertTrue(q.offer(2,0,false));
        assertTrue(q.offer(3,1,false));assertEquals(1,q.next());
    }
}
