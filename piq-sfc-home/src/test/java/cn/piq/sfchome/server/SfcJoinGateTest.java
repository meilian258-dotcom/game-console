package cn.piq.sfchome.server;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SfcJoinGateTest {
    final UUID host=UUID.randomUUID(),guest=UUID.randomUUID();
    SfcJoinGate gate(){return new SfcJoinGate(UUID.randomUUID(),host,guest,UUID.randomUUID(),UUID.randomUUID(),10);}
    SfcJoinGate capture(){var g=gate();assertTrue(g.approve(host,g.token,true,11));assertTrue(g.capture(guest,120,12));return g;}
    @Test void approvalRequiresHostNonceAndOneDecision(){var g=gate();assertFalse(g.approve(guest,g.token,true,11));assertFalse(g.approve(host,UUID.randomUUID(),true,11));assertTrue(g.approve(host,g.token,false,11));assertFalse(g.approve(host,g.token,true,11));assertEquals(SfcJoinGate.Phase.CLOSED,g.phase());}
    @Test void finiteLifetimeAndInvalidIdentities(){var g=gate();assertFalse(g.live(9));assertFalse(g.live(2410));assertFalse(g.approve(host,g.token,true,2410));assertThrows(IllegalArgumentException.class,()->new SfcJoinGate(UUID.randomUUID(),host,host,UUID.randomUUID(),UUID.randomUUID(),0));}
    @Test void onlyPausedPhaseGetsIndependentThirtySecondDeadline(){var g=gate();assertTrue(g.approve(host,g.token,true,11));assertTrue(g.live(1000));assertTrue(g.capture(guest,120,1000));assertTrue(g.live(1599));assertFalse(g.live(1600));assertFalse(g.append(host,g.token,120,1,0,SfcJoinGate.sha(new byte[]{1}),new byte[]{1},1600));}
    @Test void noCaptureBeforeApprovalOrByHost(){var g=gate();assertFalse(g.capture(guest,120,11));assertTrue(g.approve(host,g.token,true,11));assertFalse(g.capture(host,120,12));assertTrue(g.capture(guest,120,12));assertFalse(g.capture(guest,120,13));}
    @Test void receiverCannotWriteStateAndBoundsAreStrict(){var g=capture();byte[]a={1,2,3};String h=SfcJoinGate.sha(a);assertFalse(g.append(guest,g.token,120,3,0,h,a,13));assertFalse(g.append(host,g.token,119,3,0,h,a,13));assertFalse(g.append(host,g.token,120,SfcJoinGate.MAX_STATE+1,0,h,a,13));assertFalse(g.append(host,g.token,120,3,1,h,a,13));assertNull(g.bytes());}
    @Test void orderTotalHashAndReplayDoNotAdvance(){var g=capture();byte[]a={1,2,3,4};String h=SfcJoinGate.sha(a);assertTrue(g.append(host,g.token,120,4,0,h,new byte[]{1,2},13));assertFalse(g.append(host,g.token,120,4,0,h,new byte[]{1,2},13));assertFalse(g.append(host,g.token,120,5,2,h,new byte[]{3,4},13));assertFalse(g.append(host,g.token,120,4,2,"a".repeat(64),new byte[]{3,4},13));assertTrue(g.append(host,g.token,120,4,2,h,new byte[]{3,4},13));assertArrayEquals(a,g.bytes());}
    @Test void badDigestClosesAndDiscardsBudget(){var g=capture();assertFalse(g.append(host,g.token,120,3,0,"a".repeat(64),new byte[]{1,2,3},13));assertNull(g.bytes());assertFalse(g.live(14));}
    @Test void commitRequiresExactRecipientFrameHashAndIsOnce(){var g=capture();byte[]a={1};String h=SfcJoinGate.sha(a);assertTrue(g.append(host,g.token,120,1,0,h,a,13));assertFalse(g.commit(host,g.token,120,h,14));assertFalse(g.commit(guest,g.token,121,h,14));assertFalse(g.commit(guest,g.token,120,"a".repeat(64),14));assertTrue(g.commit(guest,g.token,120,h,14));assertNull(g.bytes());assertFalse(g.commit(guest,g.token,120,h,14));}
    @Test void cancelCannotBeReopenedOrAcknowledge(){var g=capture();g.close();assertFalse(g.append(host,g.token,120,1,0,SfcJoinGate.sha(new byte[]{1}),new byte[]{1},13));assertFalse(g.commit(guest,g.token,120,"a".repeat(64),14));assertNull(g.bytes());}
}
