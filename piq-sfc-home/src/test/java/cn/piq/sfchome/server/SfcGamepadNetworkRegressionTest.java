package cn.piq.sfchome.server;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Actual pure gate/queue/watchdog regression, not a simulated Minecraft server. */
class SfcGamepadNetworkRegressionTest {
    private final UUID host=UUID.randomUUID(), guest=UUID.randomUUID();
    private SfcJoinGate gate(long now) {
        return new SfcJoinGate(UUID.randomUUID(),host,guest,UUID.randomUUID(),UUID.randomUUID(),now);
    }
    private SfcJoinGate capture(long now) {
        var gate=gate(now);
        assertTrue(gate.approve(host,gate.token,true,now));
        assertTrue(gate.capture(guest,420,now));
        return gate;
    }

    @Test void everyExistingTwelveBitMaskSurvivesTransportUnchanged() {
        for(int mask=0;mask<4096;mask++) {
            var timeline=new SfcInputTimeline();
            assertTrue(timeline.offer(0,mask,false));
            assertEquals(mask,timeline.next());
            assertEquals(mask,timeline.next());
        }
    }

    @Test void padTapAndKeyboardChordRemainDistinctOrderedFrames() {
        var timeline=new SfcInputTimeline();
        // B, then B+X, release X, release B: either source can remain held.
        int[] masks={1,1|512,1,0,256,0};
        for(int seq=0;seq<masks.length;seq++)assertTrue(timeline.offer(seq,masks[seq],false));
        for(int mask:masks)assertEquals(mask,timeline.next());
        assertEquals(0,timeline.next());
    }

    @Test void focusReleaseDropsPendingP2TapsWithoutTouchingP1() {
        var p1=new SfcInputTimeline();var p2=new SfcInputTimeline();
        assertTrue(p1.offer(200,16|256,false));
        assertTrue(p2.offer(30,1024,false));assertTrue(p2.offer(31,0,false));
        assertTrue(p2.offer(32,0,true));
        assertEquals(0,p2.pending());assertEquals(0,p2.next());
        assertEquals(16|256,p1.next());
        assertFalse(p2.offer(31,2048,false));
        assertTrue(p2.offer(33,2048,false));assertEquals(2048,p2.next());
    }

    @Test void invalidCandidateSnapshotCannotConsumeP1Input() {
        var p1=new SfcInputTimeline();assertTrue(p1.offer(70,1,false));assertTrue(p1.offer(71,0,false));
        var gate=capture(10);
        assertFalse(gate.append(host,gate.token,420,3,0,"a".repeat(64),new byte[]{1,2,3},11));
        assertEquals(SfcJoinGate.Phase.CLOSED,gate.phase());assertNull(gate.bytes());
        assertEquals(1,p1.next());assertEquals(0,p1.next());
        assertTrue(p1.offer(72,256,false));assertEquals(256,p1.next());
    }

    @Test void expiredTransferAndOldNonceCannotCommitNewCandidate() {
        var old=capture(10);var current=capture(610);byte[] state={3,2,1};String digest=SfcJoinGate.sha(state);
        assertFalse(old.live(610));
        assertFalse(current.append(host,old.token,420,3,0,digest,state,611));
        assertNull(current.bytes());assertTrue(current.live(611));
        assertTrue(current.append(host,current.token,420,3,0,digest,state,612));
        assertFalse(current.commit(guest,old.token,420,digest,613));
        assertTrue(current.commit(guest,current.token,420,digest,613));
        assertFalse(current.commit(guest,current.token,420,digest,614));
    }

    @Test void cancellingCandidateLeavesP1AntiReplaySequenceIntact() {
        var p1=new SfcInputTimeline();assertTrue(p1.offer(90,1,false));
        var gate=capture(10);gate.close();
        p1.clear(); // Existing pause/abort behavior, without replacing P1 timeline.
        assertEquals(0,p1.next());assertFalse(p1.offer(89,4095,false));
        assertFalse(p1.offer(90,4095,false));assertTrue(p1.offer(91,512,false));
        assertEquals(512,p1.next());assertFalse(gate.live(11));
    }

    @Test void p2BudgetOrTimeoutDoesNotInvalidateHealthyP1() {
        var health=new SfcInputHealth();health.start(0);
        var p1=new SfcInputTimeline();assertTrue(p1.offer(0,64|1,false));
        for(int n=0;n<64;n++)assertTrue(health.packet(1,1));
        assertFalse(health.packet(1,1));assertTrue(health.packet(0,1));
        for(int tick=10;tick<=150;tick+=10)assertTrue(health.packet(0,tick));
        assertTrue(health.expiredPort(1,150));assertFalse(health.expiredPort(0,150));
        assertEquals(64|1,p1.next());
    }

    @Test void p2RejoinResetsOnlyItsQueueWhileP1KeepsSequence() {
        var p1=new SfcInputTimeline();var oldP2=new SfcInputTimeline();
        assertTrue(p1.offer(400,1,false));assertTrue(oldP2.offer(90,2048,false));
        oldP2.clear();p1.clear();var newP2=new SfcInputTimeline();
        assertFalse(p1.offer(399,4095,false));assertTrue(p1.offer(401,256,false));
        assertTrue(newP2.offer(0,512,false));
        assertEquals(256,p1.next());assertEquals(512,newP2.next());assertEquals(0,oldP2.next());
    }
}
