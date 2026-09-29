package cn.piq.fcarcade.home;

import cn.piq.fcarcade.layout.RocketArcadeGeometry;
import cn.piq.fcarcade.layout.RocketArcadeGeometry.Point;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class WideLcdTvGeometryTest {
    @Test void twoCellsRotateAndExactlyCoverVisibleBounds() {
        for (var f : WideLcdTvFootprint.Facing.values()) {
            var cells=WideLcdTvFootprint.cells(f,false);
            assertEquals(2,cells.size()); assertEquals(0,cells.get(0).part()); assertEquals(1,cells.get(1).part());
            var whole=WideLcdTvLayout.bounds(f.ordinal()); double volume=0;
            for(var c:cells) {
                var b=WideLcdTvFootprint.clipped(f,c.part(),false);
                assertTrue(b.minX()>=0&&b.maxX()<=16&&b.minZ()>=0&&b.maxZ()<=16);
                assertEquals(15,b.maxY(),1e-10);
                volume+=(b.maxX()-b.minX())*(b.maxZ()-b.minZ())*b.maxY();
                var selection=WideLcdTvFootprint.selection(f,c.part(),false);
                assertEquals(whole.minX(),selection.minX()+c.x()*16,1e-10);
                assertEquals(whole.maxZ(),selection.maxZ()+c.z()*16,1e-10);
            }
            assertEquals(24*6.4*15,volume,1e-8);
            assertFalse(WideLcdTvFootprint.canPlace(f,false,c->c.part()!=1));
            assertTrue(WideLcdTvFootprint.canPlace(f,false,c->true));
            assertThrows(IllegalArgumentException.class,()->WideLcdTvFootprint.cell(f,2,false));
        }
    }

    @Test void glassAndAllSocketsRotateOnSameAnchorAndKeepExactAspect() {
        var north=WideLcdTvLayout.screen(0);
        for(int turn=0;turn<4;turn++) {
            var q=WideLcdTvLayout.screen(turn);
            assertEquals(22D/16,distance(q.lowerMinX(),q.lowerMaxX()),1e-12);
            assertEquals(12.375/16,distance(q.lowerMinX(),q.upperMinX()),1e-12);
            assertEquals(16D/9,distance(q.lowerMinX(),q.lowerMaxX())/distance(q.lowerMinX(),q.upperMinX()),1e-12);
            assertEquals(RocketArcadeGeometry.rotate(north.lowerMinX(),turn),q.lowerMinX());
            for(int c=0;c<3;c++)assertEquals(RocketArcadeGeometry.rotate(new Point((9+c*3D)/16,.25,8.23/16),turn),WideLcdTvLayout.socket(turn,c));
        }
        assertEquals(6D/16-.0015,north.lowerMinX().z(),1e-12);
        assertThrows(IllegalArgumentException.class,()->WideLcdTvLayout.socket(0,3));
    }

    @Test void ledgerClaimsOnlyOnceFromEitherCellAndPersistsDeferredCleanup() {
        for(var f:WideLcdTvFootprint.Facing.values()) for(int clicked=0;clicked<2;clicked++) {
            var ledger=new WideLcdTvAssemblyLedger(); var id=UUID.randomUUID();
            assertTrue(ledger.restore(new WideLcdTvAssemblyLedger.Assembly(id,15,63,15,f,false,0)));
            var c=WideLcdTvFootprint.cell(f,clicked,false);
            assertTrue(ledger.close(id,clicked,15+c.x(),63,15+c.z()));
            assertFalse(ledger.close(id,clicked,15+c.x(),63,15+c.z()));
            ledger.acknowledge(id,clicked);
            var restored=new WideLcdTvAssemblyLedger(); assertTrue(restored.restore(ledger.get(id)));
            var other=WideLcdTvFootprint.cell(f,1-clicked,false);
            assertFalse(restored.close(id,1-clicked,15+other.x(),63,15+other.z()));
            restored.acknowledge(id,1-clicked); assertNull(restored.get(id));
        }
    }

    @Test void pendingCancelledPlacementCannotMintDropAndForeignCoordinatesCannotClaim() {
        var l=new WideLcdTvAssemblyLedger();var id=UUID.randomUUID();
        l.restore(new WideLcdTvAssemblyLedger.Assembly(id,0,0,0,WideLcdTvFootprint.Facing.NORTH,false,0));
        assertFalse(l.close(id,1,0,0,0));assertFalse(l.close(id,2,2,0,0));
        l.awaitPlacementEvent(id);assertFalse(l.close(id,1,1,0,0));
        assertFalse(l.cancelPlacement(id,1,9,0,0));
        assertTrue(l.cancelPlacement(id,1,1,0,0));assertFalse(l.cancelPlacement(id,0,0,0,0));
        assertNull(l.get(id));
        assertFalse(l.restore(new WideLcdTvAssemblyLedger.Assembly(id,0,0,0,WideLcdTvFootprint.Facing.NORTH,false,0,true)));
        assertFalse(l.restore(new WideLcdTvAssemblyLedger.Assembly(id,0,0,0,WideLcdTvFootprint.Facing.NORTH,true,4)));
    }

    @Test void confirmedPlacementSurvivesLateCancellation() {
        var l=new WideLcdTvAssemblyLedger();var id=UUID.randomUUID();
        l.restore(new WideLcdTvAssemblyLedger.Assembly(id,0,0,0,WideLcdTvFootprint.Facing.EAST,false,0));
        l.awaitPlacementEvent(id);l.confirmPlacement(id);
        assertFalse(l.cancelPlacement(id,0,0,0,0));assertTrue(l.close(id,1,0,0,1));
    }

    @Test void cancelledOtherCellProtectionEventCannotCommit() {
        var commits=new AtomicInteger();var events=new ArrayList<Integer>();
        var result=WideLcdTvRemovalGate.attempt(WideLcdTvFootprint.Facing.NORTH,1,
            c->true,c->true,c->true,c->{events.add(c.part());return false;},()->true,commits::incrementAndGet);
        assertEquals(WideLcdTvRemovalGate.Result.DENIED,result);assertEquals(java.util.List.of(0),events);assertEquals(0,commits.get());
    }

    @Test void unloadedCellNeverFiresEventOrLoadsChunk() {
        var events=new AtomicInteger();var commits=new AtomicInteger();
        var result=WideLcdTvRemovalGate.attempt(WideLcdTvFootprint.Facing.EAST,1,
            c->c.part()!=0,c->true,c->true,c->{events.incrementAndGet();return true;},()->true,commits::incrementAndGet);
        assertEquals(WideLcdTvRemovalGate.Result.UNLOADED,result);assertEquals(0,events.get());assertEquals(0,commits.get());
    }

    @Test void replacementAfterEventPreventsCommitAndForeignCellIsSkipped() {
        var identity=new AtomicInteger(1);var commits=new AtomicInteger();var events=new ArrayList<Integer>();
        var result=WideLcdTvRemovalGate.attempt(WideLcdTvFootprint.Facing.WEST,0,
            c->true,c->true,c->true,c->{identity.set(2);events.add(c.part());return true;},()->identity.get()==1,commits::incrementAndGet);
        assertEquals(WideLcdTvRemovalGate.Result.REPLACED,result);assertEquals(java.util.List.of(1),events);assertEquals(0,commits.get());
        events.clear();
        result=WideLcdTvRemovalGate.attempt(WideLcdTvFootprint.Facing.SOUTH,0,
            c->true,c->true,c->c.part()==0,c->{events.add(c.part());return true;},()->true,commits::incrementAndGet);
        assertEquals(WideLcdTvRemovalGate.Result.REMOVED,result);assertTrue(events.isEmpty());assertEquals(1,commits.get());
    }

    @Test void successfulProxyBreakEmitsExactlyOneAdditionalEvent() {
        var commits=new AtomicInteger();var events=new ArrayList<Integer>();
        var result=WideLcdTvRemovalGate.attempt(WideLcdTvFootprint.Facing.NORTH,1,
            c->true,c->true,c->true,c->{events.add(c.part());return true;},()->true,commits::incrementAndGet);
        assertEquals(WideLcdTvRemovalGate.Result.REMOVED,result);assertEquals(java.util.List.of(0),events);assertEquals(1,commits.get());
    }

    static double distance(Point a,Point b) {return Math.sqrt(Math.pow(a.x()-b.x(),2)+Math.pow(a.y()-b.y(),2)+Math.pow(a.z()-b.z(),2));}
    public static void main(String[] args)throws Exception {
        var instance=new WideLcdTvGeometryTest();int count=0;
        for(var m:WideLcdTvGeometryTest.class.getDeclaredMethods())if(m.isAnnotationPresent(Test.class)){m.invoke(instance);count++;}
        System.out.println("Wide LCD pure runtime tests passed: "+count);
    }
}
