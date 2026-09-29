package cn.piq.sfchome.server;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Tests the production authority and transaction/queue objects, without a Minecraft server. */
class SfcControllerAuthorityTest {
    final UUID first=UUID.randomUUID(),second=UUID.randomUUID();
    final UUID firstLease=UUID.randomUUID(),oldSecondLease=UUID.randomUUID(),newSecondLease=UUID.randomUUID();
    SfcControllerAuthority p1(){return new SfcControllerAuthority(firstLease,first,0);}
    SfcControllerAuthority p2(){return new SfcControllerAuthority(newSecondLease,second,1);}
    SfcJoinGate approved(){var gate=new SfcJoinGate(UUID.randomUUID(),first,second,firstLease,UUID.randomUUID(),0);assertTrue(gate.approve(first,gate.token,true,1));return gate;}

    @Test void currentLeaseAndOwnerBothRequiredForBothPorts(){
        assertTrue(p1().accepts(first,firstLease));assertTrue(p2().accepts(second,newSecondLease));
        assertFalse(p1().accepts(second,firstLease));assertFalse(p2().accepts(first,newSecondLease));
        assertFalse(p2().accepts(second,oldSecondLease));assertFalse(p2().accepts(second,null));assertFalse(p2().accepts(null,newSecondLease));
    }
    @Test void invalidAuthoritiesCannotBeCreated(){
        assertThrows(NullPointerException.class,()->new SfcControllerAuthority(null,first,0));
        assertThrows(NullPointerException.class,()->new SfcControllerAuthority(firstLease,null,0));
        assertThrows(IllegalArgumentException.class,()->new SfcControllerAuthority(firstLease,first,-1));
        assertThrows(IllegalArgumentException.class,()->new SfcControllerAuthority(firstLease,first,2));
    }
    @Test void validItemMustMatchTypeLeasePortAndSingleCount(){
        var authority=p2();assertTrue(authority.itemMatches(true,newSecondLease,1,1));
        assertFalse(authority.itemMatches(false,newSecondLease,1,1));assertFalse(authority.itemMatches(true,oldSecondLease,1,1));
        assertFalse(authority.itemMatches(true,null,1,1));assertFalse(authority.itemMatches(true,newSecondLease,0,1));
        for(int count:new int[]{-1,0,2,64})assertFalse(authority.itemMatches(true,newSecondLease,1,count));
    }
    @Test void exactItemIdentityIsRequiredAndTickCanInspectUnheldLease(){
        var authority=p1();assertTrue(authority.inventoryMatches(1,true,true));
        assertTrue(authority.inventoryMatches(1,false,false));assertFalse(authority.inventoryMatches(1,false,true));
        for(int copies:new int[]{-1,0,2,3}){assertFalse(authority.inventoryMatches(copies,true,true));assertFalse(authority.inventoryMatches(copies,false,false));}
    }
    @Test void staleSecondReadyCannotPauseCurrentP1OrCandidate(){
        var gate=approved();var firstInputs=new SfcInputTimeline();assertTrue(firstInputs.offer(50,257,false));
        if(p2().accepts(second,oldSecondLease))gate.capture(second,600,2);
        assertEquals(SfcJoinGate.Phase.LOADING,gate.phase());assertEquals(-1,gate.frame());assertNull(gate.bytes());
        assertEquals(257,firstInputs.next());assertTrue(firstInputs.offer(51,0,false));
        assertTrue(p2().accepts(second,newSecondLease));assertTrue(gate.capture(second,603,3));assertEquals(603,gate.frame());
    }
    @Test void staleSecondLeaveCannotCancelNewJoinButCurrentLeaseCan(){
        var gate=approved();assertTrue(gate.capture(second,90,2));
        if(p2().accepts(second,oldSecondLease))gate.close();
        assertEquals(SfcJoinGate.Phase.CAPTURE,gate.phase());assertTrue(gate.live(3));
        if(p2().accepts(second,newSecondLease))gate.close();
        assertEquals(SfcJoinGate.Phase.CLOSED,gate.phase());assertNull(gate.bytes());
    }
    @Test void secondCannotUseFirstLeaseForReadyOrLeave(){
        assertFalse(p1().accepts(second,firstLease));assertFalse(p2().accepts(second,firstLease));
        assertFalse(p2().accepts(first,newSecondLease));assertFalse(p1().accepts(first,newSecondLease));
    }
    @Test void successiveSamePlayerLeasesDoNotReviveAnyPreviousLease(){
        UUID[] retired=new UUID[24];for(int n=0;n<retired.length;n++){
            UUID current=UUID.randomUUID();var authority=new SfcControllerAuthority(current,second,1);
            for(int old=0;old<n;old++)assertFalse(authority.accepts(second,retired[old]));
            assertTrue(authority.accepts(second,current));retired[n]=current;
        }
    }
    @Test void candidateCommitAndP2ReleasePreserveP1Timeline(){
        var gate=approved();var inputs=new SfcInputTimeline();assertTrue(inputs.offer(600,64,false));
        assertTrue(p2().accepts(second,newSecondLease));assertTrue(gate.capture(second,999,2));
        byte[] state={1,2,3};String hash=SfcJoinGate.sha(state);assertTrue(gate.append(first,gate.token,999,state.length,0,hash,state,3));
        assertFalse(p2().accepts(second,oldSecondLease));assertEquals(SfcJoinGate.Phase.APPLYING,gate.phase());
        assertTrue(gate.commit(second,gate.token,999,hash,4));
        var secondInputs=new SfcInputTimeline();assertTrue(secondInputs.offer(0,1024,false));secondInputs.clear();
        assertEquals(0,secondInputs.next());assertEquals(64,inputs.next());assertFalse(inputs.offer(599,0,false));assertTrue(inputs.offer(601,0,false));
    }
}
