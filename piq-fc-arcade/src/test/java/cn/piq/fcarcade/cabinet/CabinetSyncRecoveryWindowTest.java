package cn.piq.fcarcade.cabinet;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CabinetSyncRecoveryWindowTest {
    @Test void exactBoundaryReservesHalfOfOriginalHistory(){
        assertEquals(7200,CabinetSyncTimeline.MAX_HISTORY);assertEquals(3600,CabinetSyncRecoveryWindow.MAX_STATE_AGE);
        assertTrue(CabinetSyncRecoveryWindow.available(0,0));assertTrue(CabinetSyncRecoveryWindow.available(3599,0));
        assertFalse(CabinetSyncRecoveryWindow.available(3600,0));assertFalse(CabinetSyncRecoveryWindow.available(3601,0));
        assertTrue(CabinetSyncRecoveryWindow.available(5399,1800));assertFalse(CabinetSyncRecoveryWindow.available(5400,1800));
    }
    @Test void negativeFutureAndOverflowLikeFramesCannotEnter(){
        assertFalse(CabinetSyncRecoveryWindow.available(-1,0));assertFalse(CabinetSyncRecoveryWindow.available(0,-1));
        assertFalse(CabinetSyncRecoveryWindow.available(99,100));assertFalse(CabinetSyncRecoveryWindow.available(Long.MAX_VALUE,0));
        assertTrue(CabinetSyncRecoveryWindow.available(Long.MAX_VALUE,Long.MAX_VALUE-3599));
        assertFalse(CabinetSyncRecoveryWindow.available(Long.MAX_VALUE,Long.MAX_VALUE-3600));
    }
    @Test void actualMameClockAndFullStateTransferFitWithoutPausingHost(){
        var timeline=new CabinetSyncTimeline(59186);byte[] state=new byte[3334118];new Random(33).nextBytes(state);
        UUID token=UUID.randomUUID();var assembly=new CabinetSyncState(token,state.length,CabinetSyncState.hash(state));int offset=0,ticks=0,chunks=0;
        timeline.input(0,1);
        while(offset<state.length){
            var steps=timeline.tick();assertFalse(steps.isEmpty());assertTrue(steps.stream().allMatch(s->s.p1()==1));ticks++;
            for(int n=0;n<2&&offset<state.length;n++){
                byte[] part=Arrays.copyOfRange(state,offset,Math.min(state.length,offset+CabinetSyncState.CHUNK));
                assertTrue(assembly.append(token,offset,part));offset+=part.length;chunks++;
            }
            assertTrue(CabinetSyncRecoveryWindow.available(timeline.frame(),0));
        }
        assertEquals(136,chunks);assertEquals(68,ticks);assertEquals(201,timeline.frame());assertArrayEquals(state,assembly.finish());
    }
    @Test void slowGuestExpiryDoesNotRemoveHostOrOtherInputEdges(){
        var ledger=new CabinetRoomLedger<String>();UUID host=UUID.randomUUID(),guest=UUID.randomUUID();var room=ledger.open(host,"cabinet","lab",2,0);
        assertTrue(ledger.ready(host,room.id,room.host().id,0));var member=ledger.join(guest,room,0);
        var timeline=new CabinetSyncTimeline(59186);timeline.input(0,1);timeline.input(1,2);
        while(timeline.frame()<3600)timeline.tick();assertFalse(CabinetSyncRecoveryWindow.available(timeline.frame(),0));
        timeline.input(0,0);timeline.input(0,8);timeline.release(member.port);assertEquals(List.of(member),ledger.remove(guest,member.id));
        assertSame(room,ledger.get(room.id));assertSame(room.host(),ledger.player(host));assertNull(ledger.player(guest));
        long before=timeline.frame();var next=timeline.tick();assertTrue(timeline.frame()>before);
        assertEquals(0,next.getFirst().p1());assertEquals(8,next.get(1).p1());assertTrue(next.stream().allMatch(s->s.p2()==0));
        var fresh=ledger.join(guest,room,1);assertNotNull(fresh);assertNotEquals(member.id,fresh.id);
        assertNull(ledger.input(guest,room.id,member.id,1,4095,2));
    }
    @Test void updatedSnapshotRestoresEligibilityButOldTransferCannotBorrowItsAge(){
        assertFalse(CabinetSyncRecoveryWindow.available(5400,1800));assertTrue(CabinetSyncRecoveryWindow.available(5400,3600));
        assertFalse(CabinetSyncRecoveryWindow.available(5400,1800));
    }
    @Test void activationAllowsZeroThrough120FrameTransitNot121OrFuture(){
        assertEquals(120,CabinetSyncRecoveryWindow.MAX_ACTIVATION_LAG);
        assertTrue(CabinetSyncRecoveryWindow.canActivate(0,0));assertTrue(CabinetSyncRecoveryWindow.canActivate(1000,1000));
        assertTrue(CabinetSyncRecoveryWindow.canActivate(1000,880));assertFalse(CabinetSyncRecoveryWindow.canActivate(1000,879));
        assertFalse(CabinetSyncRecoveryWindow.canActivate(1000,1001));assertFalse(CabinetSyncRecoveryWindow.canActivate(0,-1));
        assertFalse(CabinetSyncRecoveryWindow.canActivate(-1,0));
        assertTrue(CabinetSyncRecoveryWindow.canActivate(Long.MAX_VALUE,Long.MAX_VALUE-120));
        assertFalse(CabinetSyncRecoveryWindow.canActivate(Long.MAX_VALUE,Long.MAX_VALUE-121));
        assertFalse(CabinetSyncRecoveryWindow.canActivate(Long.MAX_VALUE,0));
    }
    @Test void oldGoalAcknowledgementCannotActivateEvenThoughStateIsComplete(){
        UUID room=UUID.randomUUID(),member=UUID.randomUUID(),token=UUID.randomUUID();Object connection=new Object();
        var gate=new CabinetSyncGate(room,member,connection,false);assertTrue(gate.begin(token,1800,900));
        assertFalse(CabinetSyncRecoveryWindow.canActivate(2000,1800));assertFalse(gate.active());
        assertTrue(CabinetSyncRecoveryWindow.canActivate(2000,1994));
        assertTrue(gate.acknowledge(token,1994,2000,true,100));assertTrue(gate.active());
    }
}
