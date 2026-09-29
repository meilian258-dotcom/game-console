package cn.piq.fcarcade.layout;

import cn.piq.fcarcade.layout.RocketArcadeGeometry.Point;
import cn.piq.fcarcade.layout.RocketArcadeGeometry.ScreenQuad;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScreenRayMappingTest {
    @Test void everyTvAndCabinetMapsPixelCentersAtFourFacingsWithoutMirroring() {
        for(var style:ArcadeDisplayStyle.values()) for(int turn=0;turn<4;turn++)
            for(boolean centered:new boolean[]{false,true}) {
                var surface=ScreenSurfaceGeometry.frame(style,turn,1,1,centered,ScreenAspectFit.Aspect.FOUR_THREE);
                var q=surface.image();
                for(int x:new int[]{0,1,63,127,128,254,255})for(int y:new int[]{0,1,59,119,120,238,239}) {
                    var destination=at(q,(x+.5)/256,(y+.5)/240);
                    var eye=add(add(destination,mul(q.normal(),3)),surface.translation());
                    var pixel=ScreenRayMapping.hit(surface,eye,mul(q.normal(),-2),4).orElseThrow();
                    assertEquals(x,pixel.x(),style+" "+turn);assertEquals(y,pixel.y());assertEquals(3,pixel.distance(),1e-9);
                }
            }
    }
    @Test void actualCornersIncludeLastPixelAndNotOnePastTheFrame() {
        for(int turn=0;turn<4;turn++) {
            var q=DualCabinetGeometry.screen(turn);
            for(int u=0;u<=1;u++)for(int v=0;v<=1;v++) {
                var p=at(q,u,v);var hit=ScreenRayMapping.hit(q,add(p,mul(q.normal(),2)),mul(q.normal(),-1),3).orElseThrow();
                assertEquals(u==0?0:255,hit.x());assertEquals(v==0?0:239,hit.y());
            }
        }
    }
    @Test void wideAndLargeLcdBlackPillarsAreNotGunTargets() {
        for(int turn=0;turn<4;turn++)for(var style:new ArcadeDisplayStyle[]{ArcadeDisplayStyle.HOME_WIDE_LCD_TV,ArcadeDisplayStyle.HOME_LARGE_LCD_TV}) {
            var surface=ScreenSurfaceGeometry.frame(style,turn,1,1,false,ScreenAspectFit.Aspect.FOUR_THREE);
            var q=surface.image();assertEquals(4D/3,q.aspectRatio(),1e-9);
            for(double u:new double[]{-.10,1.10})assertTrue(ScreenRayMapping.hit(surface,
                    add(at(q,u,.5),mul(q.normal(),2)),mul(q.normal(),-1),3).isEmpty());
        }
    }
    @Test void letterboxedDualAndPortraitImagesRejectGlassOutsideActualContent() {
        for(int turn=0;turn<4;turn++)for(double aspect:new double[]{16D/9,3D/4}) {
            var frame=CabinetVideoGeometry.frame(true,turn,aspect,0);var q=frame.image();var glass=frame.glass();
            var edge=aspect>4D/3?at(glass,.5,.01):at(glass,.01,.5);
            assertTrue(ScreenRayMapping.hit(q,add(edge,mul(q.normal(),2)),mul(q.normal(),-1),3).isEmpty());
        }
    }
    @Test void centeredCrtTranslationIsAppliedExactlyOnce() {
        for(int turn=0;turn<4;turn++) {
            var plain=ScreenSurfaceGeometry.frame(ArcadeDisplayStyle.HOME_RETRO_TV,turn,2,2,false,ScreenAspectFit.Aspect.FOUR_THREE);
            var centered=ScreenSurfaceGeometry.frame(ArcadeDisplayStyle.HOME_RETRO_TV,turn,2,2,true,ScreenAspectFit.Aspect.FOUR_THREE);
            assertEquals(plain.image(),centered.image());
            var q=plain.image();var aim=at(q,.13,.67);var eye=add(aim,mul(q.normal(),2));
            var a=ScreenRayMapping.hit(plain,eye,mul(q.normal(),-1),3).orElseThrow();
            var b=ScreenRayMapping.hit(centered,add(eye,centered.translation()),mul(q.normal(),-1),3).orElseThrow();
            assertEquals(a.x(),b.x());assertEquals(a.y(),b.y());
            var wrong=ScreenRayMapping.hit(centered,eye,mul(q.normal(),-1),3);
            assertTrue(wrong.isEmpty()||wrong.get().x()!=a.x());
        }
    }
    @Test void rejectsBackParallelOutsideRangeAndInvalidNumbers() {
        var q=DualCabinetGeometry.screen(0);var c=q.center();var n=q.normal();var eye=add(c,mul(n,2));
        assertTrue(ScreenRayMapping.hit(q,add(c,mul(n,-2)),n,3).isEmpty());
        assertTrue(ScreenRayMapping.hit(q,eye,new Point(1,0,0),3).isEmpty());
        assertTrue(ScreenRayMapping.hit(q,eye,n,3).isEmpty());
        assertTrue(ScreenRayMapping.hit(q,eye,mul(n,-1),1.9).isEmpty());
        assertTrue(ScreenRayMapping.hit(q,c,mul(n,-1),3).isEmpty());
        for(double u:new double[]{-1e-5,1.00001})assertTrue(ScreenRayMapping.hit(q,add(at(q,u,.5),mul(n,2)),mul(n,-1),3).isEmpty());
        for(double bad:new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY}) {
            assertTrue(ScreenRayMapping.hit(q,new Point(bad,0,0),mul(n,-1),3).isEmpty());
            assertTrue(ScreenRayMapping.hit(q,eye,new Point(bad,0,0),3).isEmpty());
            assertTrue(ScreenRayMapping.hit(q,eye,mul(n,-1),bad).isEmpty());
        }
        assertTrue(ScreenRayMapping.hit(q,eye,new Point(0,0,0),3).isEmpty());
        assertTrue(ScreenRayMapping.hit(q,eye,mul(n,-1),3,0,240).isEmpty());
        var broken=new ScreenQuad(c,c,c,c,n);assertTrue(ScreenRayMapping.hit(broken,eye,mul(n,-1),3).isEmpty());
        var twisted=new ScreenQuad(add(q.lowerMinX(),n),q.lowerMaxX(),q.upperMaxX(),q.upperMinX(),n);
        assertTrue(ScreenRayMapping.hit(twisted,eye,mul(n,-1),3).isEmpty());
    }
    @Test void obliqueNonUnitRayAndTranslatedWorldOriginHaveSamePixel() {
        var q=DualCabinetGeometry.screen(1);var p=at(q,.63,.29);var eye=add(add(p,mul(q.normal(),3)),new Point(0,1,.6));
        var d=subtract(p,eye);var expected=ScreenRayMapping.hit(q,eye,mul(d,9),10).orElseThrow();
        assertEquals(161,expected.x());assertEquals(69,expected.y());
        Point offset=new Point(106,-64,-202);var moved=new ScreenQuad(add(q.lowerMinX(),offset),add(q.lowerMaxX(),offset),add(q.upperMaxX(),offset),add(q.upperMinX(),offset),q.normal());
        var actual=ScreenRayMapping.hit(moved,add(eye,offset),d,10).orElseThrow();
        assertEquals(expected.x(),actual.x());assertEquals(expected.y(),actual.y());
    }
    private static Point at(ScreenQuad q,double u,double v) { return add(q.upperMaxX(),add(mul(subtract(q.upperMinX(),q.upperMaxX()),u),mul(subtract(q.lowerMaxX(),q.upperMaxX()),v))); }
    private static Point add(Point a,Point b){return new Point(a.x()+b.x(),a.y()+b.y(),a.z()+b.z());}
    private static Point subtract(Point a,Point b){return new Point(a.x()-b.x(),a.y()-b.y(),a.z()-b.z());}
    private static Point mul(Point a,double s){return new Point(a.x()*s,a.y()*s,a.z()*s);}
}
