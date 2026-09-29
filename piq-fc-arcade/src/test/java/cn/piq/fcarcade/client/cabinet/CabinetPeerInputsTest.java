package cn.piq.fcarcade.client.cabinet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CabinetPeerInputsTest {
    @Test void mixedCabinetVisualRangesPreservePhysicalButtonOrder(){var p=new CabinetPeerInputs();for(int i=0;i<4;i++){var member=UUID.randomUUID();assertTrue(p.seat(member,i,true));assertTrue(p.input(member,i,0,1<<i));}
        assertArrayEquals(new int[]{1,0},p.visualPair(0,1,0,1,true));assertArrayEquals(new int[]{2,4},p.visualPair(1,2,0,1,true));
        assertArrayEquals(new int[]{1,2},p.visualPair(0,2,0,1,true));assertArrayEquals(new int[]{4,0},p.visualPair(2,1,0,1,true));
        assertArrayEquals(new int[]{0,4},p.visualPair(1,2,2,4,false));assertArrayEquals(new int[]{0,0},p.visualPair(0,1,2,4,false));
        assertArrayEquals(new int[]{0,0},p.visualPair(1,0,1,true));assertArrayEquals(new int[]{0,0},p.visualPair(3,2,3,8,true));
        assertEquals(2,p.mask(1));assertEquals(4,p.mask(2));
    }
    @Test void fixedSeatsAndLateInput(){var p=new CabinetPeerInputs();var a=UUID.randomUUID();var b=UUID.randomUUID();assertTrue(p.seat(a,1,true));assertTrue(p.input(a,1,0,7));assertFalse(p.input(b,1,1,8));assertFalse(p.input(a,1,0,9));assertFalse(p.input(a,2,2,10));assertTrue(p.seat(a,1,false));assertTrue(p.seat(b,1,true));assertFalse(p.input(a,1,100,11));assertTrue(p.input(b,1,0,12));assertEquals(12,p.mask(1));}
    @Test void everyPortIndependent(){var p=new CabinetPeerInputs();var ids=new UUID[4];for(int i=0;i<4;i++){ids[i]=UUID.randomUUID();assertTrue(p.seat(ids[i],i,true));assertTrue(p.input(ids[i],i,0,1<<i));}assertEquals(3,p.guests());assertTrue(p.seat(ids[2],2,false));assertEquals(0,p.mask(2));assertEquals(1,p.mask(0));assertEquals(2,p.mask(1));assertEquals(8,p.mask(3));assertEquals(2,p.guests());}
    @Test void seatCannotBeReplacedOrDuplicated(){var p=new CabinetPeerInputs();var a=UUID.randomUUID();assertTrue(p.seat(a,0,true));assertFalse(p.seat(a,0,true));assertFalse(p.seat(a,1,true));assertFalse(p.seat(UUID.randomUUID(),0,true));assertFalse(p.seat(UUID.randomUUID(),0,false));assertFalse(p.input(a,0,0,4096));assertTrue(p.input(a,0,0,4095));}
    @Test void zeroHeartbeatRetainsOrderedEdges(){var p=new CabinetPeerInputs();var id=UUID.randomUUID();p.seat(id,3,true);for(int i=0;i<120;i++){assertTrue(p.input(id,3,i,i%2));assertEquals(i%2,p.mask(3));}assertTrue(p.input(id,3,120,0));assertFalse(p.input(id,3,119,1));}
    @Test void replayHistoryIsConnectionScopedAndBounded(){var h=new CabinetAssignmentHistory();Object a=new Object(),b=new Object();UUID old=UUID.randomUUID();assertTrue(h.accept(a,old));assertFalse(h.accept(a,old));for(int i=1;i<512;i++)assertTrue(h.accept(a,UUID.randomUUID()));assertFalse(h.accept(a,UUID.randomUUID()));assertTrue(h.seen(a,old));assertFalse(h.seen(b,old));assertTrue(h.accept(b,old));assertFalse(h.accept(null,UUID.randomUUID()));}
}
