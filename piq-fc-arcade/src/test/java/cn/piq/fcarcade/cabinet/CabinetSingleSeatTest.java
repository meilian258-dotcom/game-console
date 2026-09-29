package cn.piq.fcarcade.cabinet;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CabinetSingleSeatTest {
    @Test void explicitOnePlayerCapabilityWorksOnEitherPhysicalCabinetWithoutASecondSeat(){
        for(boolean dual:new boolean[]{false,true}){
            assertEquals(1,CabinetSeats.capacity(1,false,dual,false));
            assertTrue(CabinetSeats.validCapacity(dual,false,false,1));
            for(int port=-1;port<=4;port++)assertEquals(port==0,CabinetSeats.owns(dual,false,true,1,port));
            assertFalse(CabinetSeats.owns(dual,false,false,1,0));
            assertEquals(1,CabinetSeats.end(dual,false,true,1));
        }
    }
    @Test void onePlayerCannotLinkAnyTwoSingleOrDualCabinets(){
        for(boolean first:new boolean[]{false,true})for(boolean second:new boolean[]{false,true}){
            assertEquals(0,CabinetSeats.capacity(1,true,first,second));
            assertFalse(CabinetSeats.validCapacity(first,true,second,1));
            var links=new CabinetLinkLedger();
            var a=new CabinetLinkLedger.End("minecraft:overworld",0,64,0,UUID.randomUUID(),first);
            var b=new CabinetLinkLedger.End("minecraft:overworld",2,64,0,UUID.randomUUID(),second);
            assertNull(links.connect(a,b,true,true,1));
            assertNull(links.find(a));assertNull(links.find(b));
        }
    }
    @Test void capabilityAndTopologyMatrixRetainsAllExistingTwoThroughFourPlayerCombinations(){
        for(int supported=-1;supported<=5;supported++)for(boolean linked:new boolean[]{false,true})for(boolean first:new boolean[]{false,true})for(boolean second:new boolean[]{false,true}){
            int physical=linked?CabinetSeats.linkedCapacity(first,second):Math.min(2,supported);
            int expected=supported>=1&&supported<=4&&physical<=supported?physical:0;
            assertEquals(expected,CabinetSeats.capacity(supported,linked,first,second));
            for(int capacity=-1;capacity<=5;capacity++)assertEquals(linked?capacity==CabinetSeats.linkedCapacity(first,second):capacity==1||capacity==2,CabinetSeats.validCapacity(first,linked,second,capacity));
        }
    }
    @Test void singleSeatHostHasARealLeaseAndCanBecomeReadyButNeverGrantP2(){
        var ledger=new CabinetRoomLedger<String>();var host=UUID.randomUUID();var guest=UUID.randomUUID();
        var room=ledger.open(host,"cabinet","gba",1,0);assertNotNull(room);assertEquals(1,room.members.length);assertEquals(0,room.host().port);
        assertNull(ledger.join(guest,room,0));assertFalse(ledger.ready(guest,room.id,room.host().id,0));
        assertTrue(ledger.ready(host,room.id,room.host().id,1));
        for(int first=-1;first<=4;first++)for(int end=-1;end<=5;end++)assertNull(ledger.join(guest,room,2,first,end));
        assertNull(ledger.player(guest));assertEquals(1,ledger.allMembers().size());
        assertSame(room.host(),ledger.valid(host,room.id,room.host().id,2));
    }
    @Test void singleSeatStillRejectsForgedOwnerRoomLeaseEpochEquivalentAndOversizedInput(){
        var ledger=new CabinetRoomLedger<String>();var host=UUID.randomUUID();var stranger=UUID.randomUUID();var room=ledger.open(host,"cab","gba",1,0);var lease=room.host().id;
        assertNull(ledger.input(host,room.id,lease,0,1,0));assertTrue(ledger.ready(host,room.id,lease,0));
        assertNull(ledger.input(stranger,room.id,lease,0,1,0));assertNull(ledger.input(host,UUID.randomUUID(),lease,0,1,0));assertNull(ledger.input(host,room.id,UUID.randomUUID(),0,1,0));
        assertNull(ledger.input(host,room.id,lease,-1,1,0));assertNull(ledger.input(host,room.id,lease,0,4096,0));
        assertEquals(1,ledger.input(host,room.id,lease,0,1,0).mask());assertNull(ledger.input(host,room.id,lease,0,0,0));assertEquals(1,room.host().mask);
    }
    @Test void hostQuickTapAndForcedResetKeepTheirDistinctOriginalSequenceSemantics(){
        var ledger=new CabinetRoomLedger<String>();var host=UUID.randomUUID();var room=ledger.open(host,"cab","gba",1,0);var lease=room.host().id;ledger.ready(host,room.id,lease,0);
        var down=ledger.input(host,room.id,lease,0,1,1);var up=ledger.input(host,room.id,lease,1,0,1);
        assertNotNull(down);assertNotNull(up);assertFalse(down.reset());assertFalse(up.reset());assertTrue(up.sequence()>down.sequence());
        var reset=ledger.resetInput(host,room.id,lease,2,2);assertTrue(reset.reset());assertEquals(0,reset.mask());assertNull(ledger.input(host,room.id,lease,2,1,2));
        assertNotNull(ledger.input(host,room.id,lease,3,1,2));
    }
    @Test void singleSeatSilenceClearsInputWithoutRemovingTheHostOrReadyRoom(){
        var ledger=new CabinetRoomLedger<String>();var host=UUID.randomUUID();var room=ledger.open(host,"cab","gba",1,0);var lease=room.host().id;ledger.ready(host,room.id,lease,0);
        ledger.input(host,room.id,lease,0,1,0);assertTrue(ledger.silence(39).isEmpty());var reset=ledger.silence(40);assertEquals(1,reset.size());assertEquals(0,reset.getFirst().port());assertTrue(reset.getFirst().reset());
        assertSame(room,ledger.get(room.id));assertTrue(room.ready);assertEquals(0,room.host().mask);
    }
    @Test void expiredHostLeaseCannotBeRevivedOrTransferredToAnObserver(){
        var ledger=new CabinetRoomLedger<String>();var host=UUID.randomUUID();var viewer=UUID.randomUUID();var room=ledger.open(host,"cab","gba",1,0);var lease=room.host().id;ledger.ready(host,room.id,lease,0);
        assertFalse(ledger.heartbeat(viewer,lease,1));assertFalse(ledger.heartbeat(host,lease,80));assertNull(ledger.valid(host,room.id,lease,80));assertNull(ledger.input(host,room.id,lease,100,1,80));
        assertTrue(ledger.remove(viewer,lease).isEmpty());assertEquals(1,ledger.remove(host,lease).size());assertTrue(ledger.allMembers().isEmpty());assertTrue(ledger.all().isEmpty());
    }
    @Test void closeThenReopenHasFreshRoomAndLeaseAndOldPacketsCannotTouchIt(){
        var ledger=new CabinetRoomLedger<String>();var host=UUID.randomUUID();var room=ledger.open(host,"cab","gba",1,0);var old=room.host().id;ledger.remove(host,old);
        var newer=ledger.open(host,"cab","gba",1,1);assertNotEquals(old,newer.host().id);assertNotEquals(room.id,newer.id);
        assertFalse(ledger.ready(host,room.id,old,1));assertFalse(ledger.heartbeat(host,old,1));assertNull(ledger.resetInput(host,room.id,old,999,1));assertTrue(ledger.remove(host,old).isEmpty());assertSame(newer.host(),ledger.player(host));
    }
    @Test void globalRoomLimitAndOneRuntimePerPlayerApplyToSingleSeatToo(){
        var ledger=new CabinetRoomLedger<String>();UUID first=null;
        for(int i=0;i<4;i++){var player=UUID.randomUUID();if(i==0)first=player;assertNotNull(ledger.open(player,"cab"+i,"gba",1,0));}
        assertNull(ledger.open(first,"other","gba",1,0));assertNull(ledger.open(UUID.randomUUID(),"cab4","gba",1,0));assertEquals(4,ledger.all().size());
    }
}
