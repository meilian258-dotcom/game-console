package cn.piq.fcarcade.layout;

import cn.piq.fcarcade.cabinet.CabinetCoinGeometry;
import cn.piq.fcarcade.layout.RocketArcadeGeometry.Point;
import cn.piq.fcarcade.layout.RocketArcadeGeometry.ScreenQuad;
import cn.piq.fcarcade.world.DualCabinetFootprint;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class DualCabinetCompactGeometryTest {
    @Test void legacySignaturesAndCompactDisplacementAreIndependentInEveryFacing() {
        for(int t=0;t<4;t++) {
            assertSame(DualCabinetGeometry.screen(t),DualCabinetGeometry.screen(t,false));
            assertEquals(DualCabinetGeometry.bounds(t),DualCabinetGeometry.bounds(t,false));
            assertEquals(DualCabinetGeometry.occupancy(t),DualCabinetGeometry.occupancy(t,false));
            assertEquals(DualCabinetGeometry.leaderboardTextOrigin(t),DualCabinetGeometry.leaderboardTextOrigin(t,false));
            var old=DualCabinetGeometry.screen(t);var compact=DualCabinetGeometry.screen(t,true);
            shifted(old.lowerMinX(),compact.lowerMinX(),t);shifted(old.lowerMaxX(),compact.lowerMaxX(),t);
            shifted(old.upperMinX(),compact.upperMinX(),t);shifted(old.upperMaxX(),compact.upperMaxX(),t);
            shifted(DualCabinetGeometry.occupancy(t),DualCabinetGeometry.occupancy(t,true),t);
            shifted(DualCabinetGeometry.leaderboardTextOrigin(t),DualCabinetGeometry.leaderboardTextOrigin(t,true),t);
            assertEquals(old.normal(),compact.normal());assertEquals(old.width(),compact.width(),1e-12);
            assertEquals(old.height(),compact.height(),1e-12);assertEquals(4D/3,compact.aspectRatio(),1e-12);
        }
    }
    @Test void visualBoundsMatchWorldAndOnlyOwnedCellsHaveCollision() {
        for(boolean compact:new boolean[]{false,true})for(int t=0;t<4;t++) {
            var facing=DualCabinetFootprint.Facing.values()[t];var b=DualCabinetGeometry.bounds(t,compact);
            var world=DualCabinetFootprint.bounds(facing,compact);
            assertEquals(b.minX()*16,world.minX(),1e-10);assertEquals(b.maxX()*16,world.maxX(),1e-10);
            assertEquals(b.minZ()*16,world.minZ(),1e-10);assertEquals(b.maxZ()*16,world.maxZ(),1e-10);
            assertEquals(compact?4:12,DualCabinetFootprint.cells(facing,compact).size());
            for(var cell:DualCabinetFootprint.cells(facing,compact)) {
                var collision=DualCabinetFootprint.clipped(facing,cell.part(),compact);
                assertTrue(collision.minX()>=0&&collision.minY()>=0&&collision.minZ()>=0);
                assertTrue(collision.maxX()<=16&&collision.maxY()<=16&&collision.maxZ()<=16);
                if(compact)assertEquals(0,cell.z()*(t%2==0?1:0));
            }
            assertTrue(b.contains(DualCabinetGeometry.screen(t,compact).lowerMinX()));
            assertTrue(b.contains(DualCabinetGeometry.screen(t,compact).upperMaxX()));
        }
        assertEquals(-.1,DualCabinetGeometry.bounds(0,true).minZ(),1e-12);
        assertEquals(1,DualCabinetGeometry.bounds(0,true).maxZ(),1e-12);
    }
    @Test void nesAndExternalFittedScreensUseSeparateCachesAndSamePhysicalGlass() {
        for(int t=0;t<4;t++)for(var aspect:ScreenAspectFit.Aspect.values()) {
            var old=DualScreenPresentation.frame(t,aspect);var compact=DualScreenPresentation.frame(t,aspect,true);
            assertSame(old,DualScreenPresentation.frame(t,aspect,false));
            assertSame(compact,DualScreenPresentation.frame(t+4,aspect,true));
            shifted(old.center(),compact.center(),t);assertEquals(old.aspectRatio(),compact.aspectRatio(),1e-12);
            var surface=ScreenSurfaceGeometry.frame(ArcadeDisplayStyle.DUAL_CABINET,t,2,2,false,aspect,true);
            assertSame(compact,surface.image());
            for(int rotation=0;rotation<4;rotation++) {
                var a=CabinetVideoGeometry.frame(true,t,aspect.ratio(),rotation);
                var b=CabinetVideoGeometry.frame(true,t,aspect.ratio(),rotation,true);
                assertNotSame(a,b);assertSame(a,CabinetVideoGeometry.frame(true,t,aspect.ratio(),rotation,false));
                assertSame(b,CabinetVideoGeometry.frame(true,t,aspect.ratio(),rotation,true));
                assertSame(DualCabinetGeometry.screen(t,true),b.glass());shifted(a.image().center(),b.image().center(),t);
                for(int i=0;i<4;i++) { assertEquals(a.vertices().get(i).u(),b.vertices().get(i).u());assertEquals(a.vertices().get(i).v(),b.vertices().get(i).v()); }
            }
        }
        assertSame(CabinetVideoGeometry.frame(false,0,1,0),CabinetVideoGeometry.frame(false,0,1,0,true));
    }
    @Test void everyControlPivotTravelsWithTheBodyWithoutChangingSpacingOrAnimation() {
        for(var part:DualCabinetControls.PARTS)for(int t=0;t<4;t++) {
            var old=RocketArcadeGeometry.rotate(new Point(part.x()/16+.25,part.y()/16,part.z()/16+DualCabinetGeometry.modelZOffset(false)),t);
            var compact=RocketArcadeGeometry.rotate(new Point(part.x()/16+.25,part.y()/16,part.z()/16+DualCabinetGeometry.modelZOffset(true)),t);
            shifted(old,compact,t);assertEquals(old.y(),compact.y());
        }
    }
    @Test void bothCoinSlotsAcceptOnlyTheSelectedLayoutFrontPlaneInFourDirections() {
        for(boolean compact:new boolean[]{false,true})for(int t=0;t<4;t++)for(double x:new double[]{6,18}) {
            double z=2.53/16+DualCabinetGeometry.modelZOffset(compact);
            var eye=RocketArcadeGeometry.rotate(new Point(x/16+.25,9/16D,z-.05),t);
            var end=RocketArcadeGeometry.rotate(new Point(x/16+.25,9/16D,z+.05),t);
            assertTrue(CabinetCoinGeometry.hits(true,t,eye,end,compact));
            assertFalse(CabinetCoinGeometry.hits(true,t,end,eye,compact));
            assertFalse(CabinetCoinGeometry.hits(true,t,eye,end,!compact));
        }
    }
    @Test void mixedNewOldAndSingleCableEndsFollowTheirOwnLayout() {
        for(boolean firstCompact:new boolean[]{false,true})for(boolean secondCompact:new boolean[]{false,true})
            for(boolean secondDual:new boolean[]{false,true})for(int a=0;a<4;a++)for(int b=0;b<4;b++) {
                var path=CabinetDataCableGeometry.path(true,a,secondDual,b,6,0,4,firstCompact,secondCompact);
                assertFalse(path.isEmpty());assertEquals(CabinetDataCableGeometry.socket(true,a,firstCompact),path.getFirst());
                var end=CabinetDataCableGeometry.socket(secondDual,b,secondCompact);
                assertEquals(new Point(end.x()+6,end.y(),end.z()+4),path.getLast());
                var mesh=CabinetDataCableGeometry.build(true,a,secondDual,b,6,0,4,firstCompact,secondCompact);
                assertFalse(mesh.isEmpty());assertTrue(mesh.size()<=CabinetDataCableGeometry.MAX_QUADS);
            }
        for(int t=0;t<4;t++)shifted(CabinetDataCableGeometry.socket(true,t),CabinetDataCableGeometry.socket(true,t,true),t);
    }
    @Test void physicalProductionCallersSelectTheLiveInstanceNotAGlobalShift() throws Exception {
        String base="src/main/java/cn/piq/fcarcade/";
        for(String file:new String[]{"client/DualCabinetRenderer.java","client/ArcadeBlockScreenRenderer.java",
                "client/cabinet/CabinetVideoDisplay.java","client/CabinetDataCableRenderer.java",
                "server/ArcadeOccupancyDisplay.java","cabinet/CabinetCoinService.java"})
            assertTrue(Files.readString(Path.of(base+file)).contains("compactFootprint()"),file);
        var renderer=Files.readString(Path.of(base+"client/DualCabinetRenderer.java"));
        var item=renderer.substring(renderer.indexOf("private static final class ItemRenderer"));
        assertFalse(item.contains("compactFootprint"));assertFalse(item.contains("modelZOffset"));
    }
    private static void shifted(Point before,Point after,int t) {
        var delta=RocketArcadeGeometry.rotate(new Point(.5,0,-.5),t);
        assertEquals(before.x()+delta.x()-.5,after.x(),1e-12);assertEquals(before.y(),after.y(),1e-12);
        assertEquals(before.z()+delta.z()-.5,after.z(),1e-12);
    }
}
