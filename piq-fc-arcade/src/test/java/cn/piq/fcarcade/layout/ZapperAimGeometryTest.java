package cn.piq.fcarcade.layout;

import cn.piq.fcarcade.layout.RocketArcadeGeometry.Point;
import org.junit.jupiter.api.Test;
import java.util.HashSet;
import static org.junit.jupiter.api.Assertions.*;

class ZapperAimGeometryTest {
    @Test void axialWalkVisitsEveryPotentialObstacleInBothDirections() {
        for(int axis=0;axis<3;axis++)for(int sign:new int[]{-1,1}) {
            double[] a={.25,.25,.25},b={.25,.25,.25};b[axis]+=sign*8;
            var cells=ZapperAimGeometry.cells(new Point(a[0],a[1],a[2]),new Point(b[0],b[1],b[2]));
            assertEquals(9,cells.size());assertEquals(cells.size(),new HashSet<>(cells).size());
            assertEquals(new ZapperAimGeometry.Cell(0,0,0),cells.getFirst());
            assertEquals(new ZapperAimGeometry.Cell((int)Math.floor(b[0]),(int)Math.floor(b[1]),(int)Math.floor(b[2])),cells.getLast());
        }
    }
    @Test void diagonalAndNegativeBoundaryWalksAreBoundedAndContiguous() {
        for(int x=-8;x<=8;x+=4)for(int y=-8;y<=8;y+=4)for(int z=-8;z<=8;z+=4) {
            var cells=ZapperAimGeometry.cells(new Point(0,0,0),new Point(x,y,z));
            assertFalse(cells.isEmpty());assertTrue(cells.size()<=28);
            for(int i=1;i<cells.size();i++){var a=cells.get(i-1);var b=cells.get(i);assertEquals(1,Math.abs(a.x()-b.x())+Math.abs(a.y()-b.y())+Math.abs(a.z()-b.z()));}
            assertEquals(new ZapperAimGeometry.Cell(x,y,z),cells.getLast());
        }
    }
    @Test void invalidOrTooLongRaysCannotBeReportedAsClear() {
        var p=new Point(0,0,0);
        assertTrue(ZapperAimGeometry.cells(p,new Point(16.1,0,0)).isEmpty());
        assertTrue(ZapperAimGeometry.cells(new Point(Double.NaN,0,0),p).isEmpty());
        assertTrue(ZapperAimGeometry.cells(p,new Point(Double.POSITIVE_INFINITY,0,0)).isEmpty());
        assertTrue(ZapperAimGeometry.cells(p,new Point(3e9,0,0)).isEmpty());
        assertThrows(UnsupportedOperationException.class,()->ZapperAimGeometry.cells(p,p).clear());
    }
    @Test void negativeIntegerEndpointTieDoesNotWalkBeyondDestinationCell() {
        var cells=ZapperAimGeometry.cells(new Point(0,0,0),new Point(-8,8,0));
        assertFalse(cells.isEmpty());assertEquals(new ZapperAimGeometry.Cell(-8,8,0),cells.getLast());
        assertTrue(cells.stream().allMatch(c->c.x()>=-8&&c.x()<=0&&c.y()>=0&&c.y()<=8&&c.z()==0));
    }
    @Test void obstaclesMustActuallyBeInFrontOfScreenAndInvalidDistanceFailsClosed() {
        assertTrue(ZapperAimGeometry.beforeScreen(2,3));assertFalse(ZapperAimGeometry.beforeScreen(3,3));
        assertFalse(ZapperAimGeometry.beforeScreen(4,3));assertTrue(ZapperAimGeometry.beforeScreen(Double.NaN,3));
    }
    @Test void everyActualTvSurfaceMapsCenterFromAllFacingsWithoutDoubleCenterOffset() {
        for(var style:new ArcadeDisplayStyle[]{ArcadeDisplayStyle.HOME_RETRO_TV,ArcadeDisplayStyle.HOME_VINTAGE_TV,
                ArcadeDisplayStyle.HOME_LCD_TV,ArcadeDisplayStyle.HOME_LARGE_LCD_TV,ArcadeDisplayStyle.HOME_WIDE_LCD_TV})
            for(int turn=0;turn<4;turn++)for(boolean centered:new boolean[]{false,true}) {
                var s=ScreenSurfaceGeometry.frame(style,turn,2,2,centered,ScreenAspectFit.Aspect.FOUR_THREE);
                var n=s.image().normal();var c=s.image().center();var t=s.translation();
                var eye=new Point(c.x()+t.x()+n.x()*4,c.y()+t.y()+n.y()*4,c.z()+t.z()+n.z()*4);
                var pixel=ScreenRayMapping.hit(s,eye,new Point(-n.x(),-n.y(),-n.z()),16).orElseThrow();
                assertEquals(.5,pixel.u(),1e-12);assertEquals(.5,pixel.v(),1e-12);assertEquals(4,pixel.distance(),1e-10);
            }
    }
}
