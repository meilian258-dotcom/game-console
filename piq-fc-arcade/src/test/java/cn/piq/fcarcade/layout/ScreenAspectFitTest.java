package cn.piq.fcarcade.layout;

import org.junit.jupiter.api.Test;
import cn.piq.fcarcade.layout.RocketArcadeGeometry.Point;
import cn.piq.fcarcade.layout.RocketArcadeGeometry.ScreenQuad;
import cn.piq.fcarcade.layout.ScreenAspectFit.Aspect;
import static org.junit.jupiter.api.Assertions.*;

class ScreenAspectFitTest {
    private static final ScreenQuad WIDE = new ScreenQuad(new Point(0,0,0),new Point(16,0,0),
            new Point(16,9,0),new Point(0,9,0),new Point(0,0,-1));

    @Test void fourThreeGetsEqualSideBarsWithoutLosingHeight() {
        var fit=ScreenAspectFit.fit(WIDE,4.0/3);
        assertEquals(new Point(2,0,0),fit.lowerMinX());
        assertEquals(new Point(14,9,0),fit.upperMaxX());
        assertEquals(WIDE.center(),fit.center());
    }
    @Test void squareGetsEqualSideBars() {
        var fit=ScreenAspectFit.fit(WIDE,1);
        assertEquals(new Point(3.5,0,0),fit.lowerMinX());
        assertEquals(new Point(12.5,9,0),fit.upperMaxX());
    }
    @Test void wideUsesWholeScreenAndRetainsTheSameImmutableQuad() {
        assertSame(WIDE,ScreenAspectFit.fit(WIDE,16.0/9));
    }
    @Test void WiderContentUsesTopBottomBarsInsteadOfCropping() {
        var fit=ScreenAspectFit.fit(WIDE,32.0/9);
        assertEquals(new Point(0,2.25,0),fit.lowerMinX());
        assertEquals(new Point(16,6.75,0),fit.upperMaxX());
    }
    @Test void actualTiltedScreenFitsForAllFourFacingsAndRatios() {
        for (int turn=0;turn<4;turn++) for(Aspect aspect:Aspect.values()) {
            var screen=DualCabinetGeometry.screen(turn);
            var fit=DualScreenPresentation.frame(turn,aspect);
            double w=fit.lowerMinX().distanceTo(fit.lowerMaxX()),h=fit.lowerMinX().distanceTo(fit.upperMinX());
            assertEquals(aspect.ratio(),w/h,1e-10);
            assertTrue(fit.center().distanceTo(screen.center())<1e-10);
            assertSame(screen.normal(),fit.normal());
            assertTrue(w<=screen.lowerMinX().distanceTo(screen.lowerMaxX())+1e-10);
            assertTrue(h<=screen.lowerMinX().distanceTo(screen.upperMinX())+1e-10);
            for(var point:new Point[]{fit.lowerMinX(),fit.lowerMaxX(),fit.upperMinX(),fit.upperMaxX()}) {
                var n=screen.normal();var c=screen.center();
                assertEquals(0,(point.x()-c.x())*n.x()+(point.y()-c.y())*n.y()+(point.z()-c.z())*n.z(),1e-10);
            }
            assertSame(fit,DualScreenPresentation.frame(turn+4,aspect));
        }
    }
    @Test void noWorldYAxisShortcutAndCornerOrderingPreserved() {
        for(int turn=0;turn<4;turn++) {
            var fit=DualScreenPresentation.frame(turn,Aspect.SQUARE);
            var a=fit.lowerMinX();var b=fit.lowerMaxX();var c=fit.upperMaxX();
            double x=(b.y()-a.y())*(c.z()-a.z())-(b.z()-a.z())*(c.y()-a.y());
            double y=(b.z()-a.z())*(c.x()-a.x())-(b.x()-a.x())*(c.z()-a.z());
            double z=(b.x()-a.x())*(c.y()-a.y())-(b.y()-a.y())*(c.x()-a.x());
            // The four stored corners have the same winding as the input screen.
            var n=fit.normal(); assertTrue(x*n.x()+y*n.y()+z*n.z()<0);
        }
    }
    @Test void invalidRatiosFailClosed() {
        for(double invalid:new double[]{0,-1,Double.NaN,Double.POSITIVE_INFINITY})
            assertThrows(IllegalArgumentException.class,()->ScreenAspectFit.fit(WIDE,invalid));
        assertThrows(IllegalArgumentException.class,()->Aspect.parse("stretch"));
        assertEquals(Aspect.SQUARE,Aspect.parse("1:1"));
    }
}
