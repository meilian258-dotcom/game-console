package cn.piq.fcarcade.cabinet;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CabinetSyncGateTest {
    final UUID room=UUID.randomUUID(),member=UUID.randomUUID();final Object connection=new Object();
    CabinetSyncGate guest(){return new CabinetSyncGate(room,member,connection,false);}
    @Test void exactConnectionRoomEpochAndLeaseAreAllRequired(){var g=guest();assertTrue(g.accepts(room,member,1,connection));assertFalse(g.accepts(UUID.randomUUID(),member,1,connection));assertFalse(g.accepts(room,UUID.randomUUID(),1,connection));assertFalse(g.accepts(room,member,2,connection));assertFalse(g.accepts(room,member,1,new Object()));}
    @Test void approvedButNotSyncedGuestHasNoInput(){var g=guest();assertFalse(g.input(room,member,1,connection));UUID token=UUID.randomUUID();assertTrue(g.begin(token,60,100));assertFalse(g.input(room,member,1,connection));assertFalse(g.acknowledge(token,60,60,false,1));assertTrue(g.acknowledge(token,60,60,true,1));assertTrue(g.input(room,member,1,connection));}
    @Test void expiredFutureEarlyAndWrongAcknowledgementsCannotCommit(){var g=guest();UUID t=UUID.randomUUID();g.begin(t,60,100);assertFalse(g.acknowledge(UUID.randomUUID(),60,60,true,1));assertFalse(g.acknowledge(t,59,60,true,1));assertFalse(g.acknowledge(t,61,60,true,1));assertFalse(g.acknowledge(t,60,60,true,100));assertFalse(g.active());}
    @Test void completedTokenCannotActivateAnotherRepair(){var g=guest();UUID old=UUID.randomUUID(),next=UUID.randomUUID();g.begin(old,10,100);assertTrue(g.acknowledge(old,10,10,true,1));assertFalse(g.begin(old,10,100));assertTrue(g.begin(next,20,100));assertFalse(g.acknowledge(old,20,20,true,2));assertFalse(g.active());assertTrue(g.acknowledge(next,20,20,true,2));}
    @Test void hostIsNeverLoadedFromGuestRestore(){var g=new CabinetSyncGate(room,member,connection,true);g.activateHost();assertTrue(g.active());assertFalse(g.begin(UUID.randomUUID(),100,200));assertFalse(g.acknowledge(UUID.randomUUID(),100,100,true,1));assertTrue(g.active());}
    @Test void closeAndReplacementConnectionDoNotReviveLease(){var g=guest();UUID t=UUID.randomUUID();g.begin(t,0,100);g.close();assertFalse(g.acknowledge(t,0,0,true,1));assertFalse(g.accepts(room,member,1,connection));assertFalse(g.begin(UUID.randomUUID(),0,100));}
    @Test void concurrentRestoreCannotReplaceOutstandingToken(){var g=guest();UUID a=UUID.randomUUID(),b=UUID.randomUUID();assertTrue(g.begin(a,5,100));assertFalse(g.begin(b,0,200));assertFalse(g.acknowledge(b,5,5,true,1));assertTrue(g.acknowledge(a,5,5,true,1));}
}
