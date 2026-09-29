package cn.piq.fcarcade.cabinet;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CabinetSeatsTest {
    private static CabinetLinkLedger.End end(int x,boolean dual){return new CabinetLinkLedger.End("minecraft:overworld",x,64,0,UUID.randomUUID(),dual);}
    @Test void allFourCombinationsRequireActualProviderCapacity(){for(boolean a:new boolean[]{false,true})for(boolean b:new boolean[]{false,true}){
        int expected=(a?2:1)+(b?2:1);assertEquals(expected,CabinetSeats.linkedCapacity(a,b));
        for(int supported=0;supported<=5;supported++){var ledger=new CabinetLinkLedger();var pair=ledger.connect(end(0,a),end(4,b),true,true,supported);assertEquals(supported>=expected&&supported<=4,pair!=null);}
    }}
    @Test void seatRangesHaveNoGapOverlapOrThirdSeatOnSingleCabinet(){for(boolean a:new boolean[]{false,true})for(boolean b:new boolean[]{false,true}){
        int count=CabinetSeats.linkedCapacity(a,b),left=0,right=0;
        for(int port=-1;port<=4;port++){boolean first=CabinetSeats.owns(a,true,true,count,port),second=CabinetSeats.owns(a,true,false,count,port);assertFalse(first&&second);assertEquals(port>=0&&port<count,first||second);if(first)left++;if(second)right++;}
        assertEquals(a?2:1,left);assertEquals(b?2:1,right);
    }}
    @Test void unlinkedRoomRetainsTwoSeatCompatibility(){for(boolean dual:new boolean[]{false,true}){assertTrue(CabinetSeats.owns(dual,false,true,2,0));assertTrue(CabinetSeats.owns(dual,false,true,2,1));assertFalse(CabinetSeats.owns(dual,false,false,2,1));assertFalse(CabinetSeats.owns(dual,false,true,2,2));}}
    @Test void actualLedgerAssignsEveryMixedCabinetSeatAndRejectsOverflow(){for(boolean a:new boolean[]{false,true})for(boolean b:new boolean[]{false,true}){
        var ledger=new CabinetRoomLedger<String>();var host=UUID.randomUUID();int count=CabinetSeats.linkedCapacity(a,b);var room=ledger.open(host,"primary","addon:test",count,0);assertNotNull(room);assertTrue(ledger.ready(host,room.id,room.host().id,1));
        for(int port=1;port<count;port++){var member=ledger.join(UUID.randomUUID(),room,2,port,port+1);assertNotNull(member);assertEquals(port,member.port);assertTrue(CabinetSeats.owns(a,true,port<CabinetSeats.physical(a),count,member.port));}
        assertNull(ledger.join(UUID.randomUUID(),room,3));assertNull(ledger.join(UUID.randomUUID(),room,3,count,count+1));
    }}
    @Test void approvalGateStillRequiredForSecondaryP2OnTwoSingles(){var host=UUID.randomUUID();var ledger=new CabinetRoomLedger<String>();var room=ledger.open(host,"first","sfc",2,0);assertTrue(ledger.ready(host,room.id,room.host().id,0));var gate=new CabinetJoinGate(host,room.host().id);var guest=UUID.randomUUID();assertNull(gate.request(guest,1,1));var offer=gate.offer(1);assertTrue(gate.allow(host,room.host().id,offer.token(),true,2));var pending=gate.request(guest,1,3);assertNotNull(pending);assertFalse(CabinetSeats.owns(false,true,true,2,pending.port()));assertTrue(CabinetSeats.owns(false,true,false,2,pending.port()));assertNull(gate.decide(host,room.host().id,UUID.randomUUID(),4));assertNotNull(gate.decide(host,room.host().id,pending.token(),4));assertNull(gate.decide(host,room.host().id,pending.token(),5));}
    @Test void reloadPreservesSingleAndMixedIdentities(){for(boolean a:new boolean[]{false,true})for(boolean b:new boolean[]{false,true}){var original=new CabinetLinkLedger();var pair=original.connect(end(0,a),end(4,b),true,true,4);var restored=new CabinetLinkLedger();assertTrue(restored.restore(pair));assertSame(pair,restored.find(pair.primary()));assertSame(pair,restored.find(pair.secondary()));assertEquals(pair.primary(),restored.find(pair.secondary()).primary());}}
    @Test void permissionsAndBusyRemainIndependentForEveryCombination(){for(boolean a:new boolean[]{false,true})for(boolean b:new boolean[]{false,true}){var ledger=new CabinetLinkLedger();var first=end(0,a);var second=end(4,b);assertNull(ledger.connect(first,second,false,true,4));assertNull(ledger.connect(first,second,true,false,4));var pair=ledger.connect(first,second,true,true,4);assertNotNull(pair);assertNull(ledger.disconnect(second,false,true));assertNull(ledger.disconnect(first,true,false));assertSame(pair,ledger.find(first));}}
}
