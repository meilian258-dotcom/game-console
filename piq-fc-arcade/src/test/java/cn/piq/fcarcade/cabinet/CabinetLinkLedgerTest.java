package cn.piq.fcarcade.cabinet;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CabinetLinkLedgerTest {
    private static CabinetLinkLedger.End end(int x,int y,int z){return end("minecraft:overworld",x,y,z,UUID.randomUUID(),true);}
    private static CabinetLinkLedger.End end(String dimension,int x,int y,int z,UUID id,boolean dual){return new CabinetLinkLedger.End(dimension,x,y,z,id,dual);}
    private static CabinetLinkLedger.End changed(CabinetLinkLedger.End source,UUID id,boolean dual){return end(source.dimension(),source.x(),source.y(),source.z(),id,dual);}
    @Test void preservesOrderedSeatsAndBidirectionalPeer(){
        var ledger=new CabinetLinkLedger();var a=end(0,64,0);var b=end(2,64,0);
        var pair=ledger.connect(a,b,true,true,4);assertNotNull(pair);
        assertSame(pair,ledger.find(a));assertSame(pair,ledger.find(b));assertEquals(a,pair.primary());assertEquals(b,pair.secondary());
        assertEquals(b,pair.other(a));assertEquals(a,pair.other(b));assertNull(pair.other(end(3,64,0)));
    }
    @Test void onlyTwoDistinctCabinetsInSameDimension(){
        var a=end(0,64,0);var b=end(2,64,0);
        assertFalse(CabinetLinkLedger.compatible(a,a));assertFalse(CabinetLinkLedger.compatible(a,changed(a,UUID.randomUUID(),true)));
        assertFalse(CabinetLinkLedger.compatible(a,end("minecraft:the_nether",2,64,0,b.identity(),true)));
        assertFalse(CabinetLinkLedger.compatible(a,changed(b,a.identity(),true)));
        assertTrue(CabinetLinkLedger.compatible(changed(a,a.identity(),false),b));
        assertTrue(CabinetLinkLedger.compatible(a,changed(b,b.identity(),false)));
        assertFalse(CabinetLinkLedger.compatible(null,b));assertFalse(CabinetLinkLedger.compatible(a,null));
    }
    @Test void threeDimensionalDistanceBoundaryIsExact(){
        var origin=end(0,0,0);var otherId=UUID.randomUUID();
        for(int x=-18;x<=18;x++)for(int y=-18;y<=18;y++)for(int z=-18;z<=18;z++){
            var b=end("minecraft:overworld",x,y,z,otherId,true);int squared=x*x+y*y+z*z;
            assertEquals(squared>0&&squared<=256,CabinetLinkLedger.compatible(origin,b),"distance "+x+","+y+","+z);
        }
    }
    @Test void distanceArithmeticCannotOverflow(){
        assertFalse(CabinetLinkLedger.compatible(end(Integer.MIN_VALUE,0,0),end(Integer.MAX_VALUE,0,0)));
        assertTrue(CabinetLinkLedger.compatible(end(Integer.MAX_VALUE-16,0,0),end(Integer.MAX_VALUE,0,0)));
        assertFalse(CabinetLinkLedger.compatible(end(0,Integer.MIN_VALUE,0),end(0,Integer.MAX_VALUE,0)));
    }
    @Test void authorityIdleAndFourPortCapabilityAreIndependentRequirements(){
        var ledger=new CabinetLinkLedger();var a=end(0,64,0);var b=end(2,64,0);
        for(int ports=-1;ports<4;ports++)assertNull(ledger.connect(a,b,true,true,ports));
        assertNull(ledger.connect(a,b,false,true,4));assertNull(ledger.connect(a,b,true,false,4));assertNull(ledger.connect(a,b,false,false,4));
        assertTrue(ledger.snapshot().isEmpty());assertNotNull(ledger.connect(a,b,true,true,4));
    }
    @Test void duplicateCoordinatesIdentityReverseAndCyclesAreRejected(){
        var ledger=new CabinetLinkLedger();var a=end(0,64,0);var b=end(2,64,0);var c=end(4,64,0);
        assertNotNull(ledger.connect(a,b,true,true,4));
        assertNull(ledger.connect(a,b,true,true,4));assertNull(ledger.connect(b,a,true,true,4));
        assertNull(ledger.connect(b,c,true,true,4));assertNull(ledger.connect(c,a,true,true,4));
        assertNull(ledger.connect(changed(a,UUID.randomUUID(),true),c,true,true,4));
        assertNull(ledger.connect(end("minecraft:the_nether",0,64,0,a.identity(),true),end("minecraft:the_nether",2,64,0,UUID.randomUUID(),true),true,true,4));
        assertEquals(1,ledger.snapshot().size());
    }
    @Test void staleIdentityAndSingleCabinetImpersonationCannotDisconnect(){
        var ledger=new CabinetLinkLedger();var a=end(0,64,0);var b=end(2,64,0);var pair=ledger.connect(a,b,true,true,4);
        assertNull(ledger.disconnect(changed(a,UUID.randomUUID(),true),true,true));
        assertNull(ledger.disconnect(changed(a,a.identity(),false),true,true));
        assertNull(ledger.remove(end("minecraft:the_nether",a.x(),a.y(),a.z(),a.identity(),true)));
        assertSame(pair,ledger.find(a));assertEquals(1,ledger.snapshot().size());
    }
    @Test void busyOrUnprivilegedDisconnectDoesNotChangeEitherSide(){
        var ledger=new CabinetLinkLedger();var a=end(0,64,0);var b=end(2,64,0);var pair=ledger.connect(a,b,true,true,4);
        assertNull(ledger.disconnect(a,false,true));assertNull(ledger.disconnect(b,true,false));
        assertSame(pair,ledger.find(a));assertSame(pair,ledger.find(b));
        assertSame(pair,ledger.disconnect(b,true,true));assertNull(ledger.find(a));assertNull(ledger.find(b));
        assertNull(ledger.disconnect(a,true,true));assertNotNull(ledger.connect(a,b,true,true,4));
    }
    @Test void physicalRemovalRequiresExactIdentityAndFreesBothEnds(){
        var ledger=new CabinetLinkLedger();var a=end(0,64,0);var b=end(2,64,0);var pair=ledger.connect(a,b,true,true,4);
        assertSame(pair,ledger.remove(a));assertTrue(ledger.snapshot().isEmpty());
        assertNotNull(ledger.connect(b,end(4,64,0),true,true,4));
    }
    @Test void strictServerCapacityIs128PairsAndRemovalFreesOneSlot(){
        var ledger=new CabinetLinkLedger();
        for(int i=0;i<128;i++)assertNotNull(ledger.connect(end(i*40,64,0),end(i*40+2,64,0),true,true,4));
        assertEquals(128,ledger.snapshot().size());var a=end(8000,64,0);var b=end(8002,64,0);
        assertNull(ledger.connect(a,b,true,true,4));ledger.remove(ledger.snapshot().getFirst().secondary());
        assertNotNull(ledger.connect(a,b,true,true,4));assertEquals(128,ledger.snapshot().size());
    }
    @Test void restoreKeepsPrimaryIdentityButRejectsMalformedTopology(){
        var ledger=new CabinetLinkLedger();var a=end(0,64,0);var b=end(2,64,0);
        var pair=new CabinetLinkLedger.Pair(UUID.randomUUID(),a,b);assertTrue(ledger.restore(pair));
        assertFalse(ledger.restore(pair));assertFalse(ledger.restore(new CabinetLinkLedger.Pair(pair.id(),end(40,64,0),end(42,64,0))));
        assertFalse(ledger.restore(new CabinetLinkLedger.Pair(UUID.randomUUID(),b,a)));
        assertFalse(ledger.restore(new CabinetLinkLedger.Pair(UUID.randomUUID(),end(40,64,0),end(80,64,0))));
        assertFalse(ledger.restore(null));assertEquals(pair,ledger.snapshot().getFirst());
    }
    @Test void snapshotIsImmutableAndRemainsStableAcrossChanges(){
        var ledger=new CabinetLinkLedger();var a=end(0,64,0);var b=end(2,64,0);var pair=ledger.connect(a,b,true,true,4);
        var snapshot=ledger.snapshot();assertThrows(UnsupportedOperationException.class,()->snapshot.clear());
        ledger.remove(b);assertEquals(1,snapshot.size());assertEquals(pair,snapshot.getFirst());assertTrue(ledger.snapshot().isEmpty());
    }
    @Test void serializedEndpointsRejectInvalidIdentifiersAndNullIdentity(){
        for(String dimension:new String[]{"","overworld","Minecraft:overworld","minecraft:../bad?","x:"+"a".repeat(128)})
            assertThrows(IllegalArgumentException.class,()->end(dimension,0,0,0,UUID.randomUUID(),true));
        assertThrows(IllegalArgumentException.class,()->end(null,0,0,0,UUID.randomUUID(),true));
        assertThrows(IllegalArgumentException.class,()->end("minecraft:overworld",0,0,0,null,true));
        assertNotNull(end("example:custom/dimension",0,0,0,UUID.randomUUID(),true));
    }
}
