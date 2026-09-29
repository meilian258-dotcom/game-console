package cn.piq.sfchome.server;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SfcHomeStartPolicyTest {
    @Test void legacyCardWithoutSecondLeaseStartsSingle(){assertEquals(SfcHomeStartPolicy.Plan.SINGLE,SfcHomeStartPolicy.plan(2,false,false));}
    @Test void legacyCardIncludesAnExistingSecondLease(){assertEquals(SfcHomeStartPolicy.Plan.DUAL,SfcHomeStartPolicy.plan(2,false,true));}
    @Test void explicitSingleNeverWaitsForOrIncludesP2(){assertEquals(SfcHomeStartPolicy.Plan.SINGLE,SfcHomeStartPolicy.plan(1,true,false));assertEquals(SfcHomeStartPolicy.Plan.SINGLE,SfcHomeStartPolicy.plan(1,true,true));}
    @Test void explicitDualDoesNotBlockP1AndOnlyIncludesApprovedP2(){assertEquals(SfcHomeStartPolicy.Plan.SINGLE,SfcHomeStartPolicy.plan(2,true,false));assertEquals(SfcHomeStartPolicy.Plan.DUAL,SfcHomeStartPolicy.plan(2,true,true));}
    @Test void invalidPlayerCountsAreRejected(){assertThrows(IllegalArgumentException.class,()->SfcHomeStartPolicy.plan(0,true,false));assertThrows(IllegalArgumentException.class,()->SfcHomeStartPolicy.plan(3,true,true));}
    @Test void claimThenUseOnThenUseCannotReturnInSameTick(){var gate=new SfcHomeStartPolicy.InteractionGate();UUID p=UUID.randomUUID();assertTrue(gate.allow(p,10));assertFalse(gate.allow(p,10));assertFalse(gate.allow(p,10));assertTrue(gate.allow(p,11));}
    @Test void returnThenOldEmptyHandEventCannotReclaimInSameTick(){var gate=new SfcHomeStartPolicy.InteractionGate();UUID p=UUID.randomUUID();assertTrue(gate.allow(p,99));assertFalse(gate.allow(p,99));assertFalse(gate.allow(p,98));assertTrue(gate.allow(p,100));}
    @Test void gateSeparatesPlayersAndExpiresOldEntries(){var gate=new SfcHomeStartPolicy.InteractionGate();UUID a=UUID.randomUUID(),b=UUID.randomUUID();assertTrue(gate.allow(a,50));assertTrue(gate.allow(b,50));gate.expireBefore(49);assertFalse(gate.allow(a,50));gate.expireBefore(100);assertTrue(gate.allow(a,100));}
    @Test void detachedP2QueueIsZeroWhileP1SequenceContinues(){var a=new SfcInputTimeline();var b=new SfcInputTimeline();assertTrue(a.offer(0,1,false));assertTrue(b.offer(0,2,false));assertEquals(1,a.next());assertEquals(2,b.next());assertTrue(b.offer(1,4,false));b.clear();assertEquals(0,b.pending());for(int i=0;i<10;i++){assertEquals(0,b.next());assertEquals(1,a.next());}assertTrue(a.offer(1,8,false));assertEquals(8,a.next());}
}
