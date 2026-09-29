package cn.piq.fcarcade.home;

import cn.piq.fcarcade.layout.RocketArcadeGeometry;
import cn.piq.fcarcade.layout.RocketArcadeGeometry.Point;
import cn.piq.fcarcade.layout.LargeLcdPresentation;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class LargeLcdTvGeometryTest {
    @Test void centeredSixCellsHaveHalfWidthSidesAndShortTopCollision() {
        for(var f:LargeLcdTvFootprint.Facing.values()) {
            var cells=LargeLcdTvFootprint.cells(f,false);assertEquals(6,cells.size());
            assertEquals(6,cells.stream().distinct().count());double volume=0;
            var whole=LargeLcdTvLayout.bounds(f.ordinal());
            for(var c:cells) {
                var b=LargeLcdTvFootprint.clipped(f,c.part(),false);
                assertTrue(b.minX()>=0&&b.maxX()<=16&&b.minZ()>=0&&b.maxZ()<=16&&b.minY()>=0&&b.maxY()<=16);
                assertEquals(c.y()==0?16:3.5,b.maxY(),1e-10);
                double area=(b.maxX()-b.minX())*(b.maxZ()-b.minZ());
                assertEquals((c.part()%3==0?16:8)*6.4,area,1e-9);
                volume+=area*b.maxY();
                var s=LargeLcdTvFootprint.selection(f,c.part(),false);
                assertEquals(whole.minX(),s.minX()+c.x()*16,1e-10);
                assertEquals(whole.maxY(),s.maxY()+c.y()*16,1e-10);
                assertEquals(whole.maxZ(),s.maxZ()+c.z()*16,1e-10);
            }
            assertEquals(32*19.5*6.4,volume,1e-8);
            for(int blocked=0;blocked<6;blocked++){final int p=blocked;assertFalse(LargeLcdTvFootprint.canPlace(f,false,c->c.part()!=p));}
            assertTrue(LargeLcdTvFootprint.canPlace(f,false,c->true));
            assertThrows(IllegalArgumentException.class,()->LargeLcdTvFootprint.cell(f,6,false));
        }
    }
    @Test void physicalScreenAndPortsRotateExactlyAroundClickedBlockCenter() {
        var north=LargeLcdTvLayout.screen(0);
        for(int t=0;t<4;t++) {
            var q=LargeLcdTvLayout.screen(t);assertSame(q,LargeLcdTvLayout.screen(t+4));
            assertEquals(30D/16,distance(q.lowerMinX(),q.lowerMaxX()),1e-12);
            assertEquals(16.875/16,distance(q.lowerMinX(),q.upperMinX()),1e-12);
            assertEquals(RocketArcadeGeometry.rotate(north.lowerMinX(),t),q.lowerMinX());
            for(int c=0;c<3;c++)assertEquals(RocketArcadeGeometry.rotate(new Point((5+c*3D)/16,.25,8.23/16),t),LargeLcdTvLayout.socket(t,c));
        }
        assertEquals(.5,north.center().x(),1e-12);
        assertEquals(6D/16-.0015,north.center().z(),1e-12);
        assertThrows(IllegalArgumentException.class,()->LargeLcdTvLayout.socket(0,3));
    }
    @Test void actualProductionVideoFrameIsFullFourThreeWithSymmetricBars() {
        for(int t=0;t<4;t++){
            var g=LargeLcdTvLayout.screen(t);var v=LargeLcdPresentation.frame(t);
            assertEquals(4D/3,distance(v.lowerMinX(),v.lowerMaxX())/distance(v.lowerMinX(),v.upperMinX()),1e-12);
            assertEquals(g.center(),v.center());assertEquals(g.normal(),v.normal());
            assertEquals(3.75/16,distance(v.lowerMinX(),g.lowerMinX()),1e-12);
            assertEquals(3.75/16,distance(v.lowerMaxX(),g.lowerMaxX()),1e-12);
            assertSame(v,LargeLcdPresentation.frame(t+4));
        }
    }
    @Test void everyFacingAndEveryPartCanClaimExactlyOnceAcrossDeferredReloads() {
        for(var f:LargeLcdTvFootprint.Facing.values())for(int clicked=0;clicked<6;clicked++){
            var l=new LargeLcdTvAssemblyLedger();var id=UUID.randomUUID();
            assertTrue(l.restore(new LargeLcdTvAssemblyLedger.Assembly(id,15,63,15,f,false,0)));
            var c=LargeLcdTvFootprint.cell(f,clicked,false);
            assertTrue(l.close(id,clicked,15+c.x(),63+c.y(),15+c.z()));
            l.acknowledge(id,clicked);var restored=new LargeLcdTvAssemblyLedger();assertTrue(restored.restore(l.get(id)));
            for(var other:LargeLcdTvFootprint.cells(f,false)){
                assertFalse(restored.close(id,other.part(),15+other.x(),63+other.y(),15+other.z()));
                restored.acknowledge(id,other.part());
            }
            assertNull(restored.get(id));
        }
    }
    @Test void pendingCancellationNeverGrantsDropOrAcceptsForeignCoordinates() {
        var l=new LargeLcdTvAssemblyLedger();var id=UUID.randomUUID();
        l.restore(new LargeLcdTvAssemblyLedger.Assembly(id,0,0,0,LargeLcdTvFootprint.Facing.NORTH,false,0));
        assertFalse(l.close(id,1,0,0,0));assertFalse(l.close(id,6,0,2,0));
        l.awaitPlacementEvent(id);assertFalse(l.close(id,1,-1,0,0));assertFalse(l.cancelPlacement(id,1,9,0,0));
        assertTrue(l.cancelPlacement(id,1,-1,0,0));assertFalse(l.cancelPlacement(id,0,0,0,0));assertNull(l.get(id));
        assertFalse(l.restore(new LargeLcdTvAssemblyLedger.Assembly(id,0,0,0,LargeLcdTvFootprint.Facing.NORTH,false,0,true)));
        assertFalse(l.restore(new LargeLcdTvAssemblyLedger.Assembly(id,0,0,0,LargeLcdTvFootprint.Facing.NORTH,true,64)));
    }
    @Test void confirmedPlacementCannotBeCancelledLater() {
        var l=new LargeLcdTvAssemblyLedger();var id=UUID.randomUUID();
        l.restore(new LargeLcdTvAssemblyLedger.Assembly(id,0,0,0,LargeLcdTvFootprint.Facing.EAST,false,0));
        l.awaitPlacementEvent(id);l.confirmPlacement(id);assertFalse(l.cancelPlacement(id,0,0,0,0));
        assertTrue(l.close(id,5,0,1,1));
    }
    @Test void anyUnloadedPartStopsBeforeProtectionEventsOrCommit() {
        for(int blocked=0;blocked<6;blocked++){
            final int part=blocked;var events=new AtomicInteger();var commits=new AtomicInteger();
            var r=LargeLcdTvRemovalGate.attempt(LargeLcdTvFootprint.Facing.NORTH,0,c->c.part()!=part,c->true,c->true,c->{events.incrementAndGet();return true;},()->true,commits::incrementAndGet);
            assertEquals(LargeLcdTvRemovalGate.Result.UNLOADED,r);assertEquals(0,events.get());assertEquals(0,commits.get());
        }
    }
    @Test void anyDeniedBasicPermissionOrOtherCellBreakEventPreventsCommit() {
        for(int blocked=0;blocked<6;blocked++){
            final int part=blocked;var commits=new AtomicInteger();
            var r=LargeLcdTvRemovalGate.attempt(LargeLcdTvFootprint.Facing.NORTH,0,c->true,c->c.part()!=part,c->true,c->true,()->true,commits::incrementAndGet);
            assertEquals(LargeLcdTvRemovalGate.Result.DENIED,r);assertEquals(0,commits.get());
            if(blocked!=0){r=LargeLcdTvRemovalGate.attempt(LargeLcdTvFootprint.Facing.NORTH,0,c->true,c->true,c->true,c->c.part()!=part,()->true,commits::incrementAndGet);assertEquals(LargeLcdTvRemovalGate.Result.DENIED,r);assertEquals(0,commits.get());}
        }
    }
    @Test void replacementDuringEventCancelsAndForeignCellIsNeverTargeted() {
        var identity=new AtomicInteger(1);var commits=new AtomicInteger();var events=new ArrayList<Integer>();
        var r=LargeLcdTvRemovalGate.attempt(LargeLcdTvFootprint.Facing.WEST,0,c->true,c->true,c->c.part()!=2,c->{identity.set(2);events.add(c.part());return true;},()->identity.get()==1,commits::incrementAndGet);
        assertEquals(LargeLcdTvRemovalGate.Result.REPLACED,r);assertFalse(events.contains(2));assertEquals(0,commits.get());
    }
    @Test void successfulProxyBreakFiresOtherFiveEventsOnceThenCommitsOnce() {
        var commits=new AtomicInteger();var events=new ArrayList<Integer>();
        var r=LargeLcdTvRemovalGate.attempt(LargeLcdTvFootprint.Facing.SOUTH,4,c->true,c->true,c->true,c->{events.add(c.part());return true;},()->true,commits::incrementAndGet);
        assertEquals(LargeLcdTvRemovalGate.Result.REMOVED,r);assertEquals(5,events.size());assertEquals(5,events.stream().distinct().count());assertFalse(events.contains(4));assertEquals(1,commits.get());
    }
    static double distance(Point a,Point b){return Math.sqrt(Math.pow(a.x()-b.x(),2)+Math.pow(a.y()-b.y(),2)+Math.pow(a.z()-b.z(),2));}
    public static void main(String[] args)throws Exception{var instance=new LargeLcdTvGeometryTest();int count=0;for(var m:LargeLcdTvGeometryTest.class.getDeclaredMethods())if(m.isAnnotationPresent(Test.class)){m.invoke(instance);count++;}System.out.println("Large LCD pure runtime tests passed: "+count);}
}
