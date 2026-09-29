package cn.piq.fcarcade.home;

import cn.piq.fcarcade.layout.RocketArcadeGeometry.Point;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Independent mirrored photo-facing contract; does not read the generator or native mesh. */
class VintageTvLayoutTest {
    @Test void screenHasExactFourThreeWindowAndMirroredSidePosition() {
        var q=VintageTvLayout.screen(0);
        point(new Point(4.35/16,2.0/16,3.35/16-.0015),q.lowerMinX());
        point(new Point(14.55/16,2.0/16,3.35/16-.0015),q.lowerMaxX());
        point(new Point(14.55/16,9.65/16,3.35/16-.0015),q.upperMaxX());
        point(new Point(4.35/16,9.65/16,3.35/16-.0015),q.upperMinX());
        assertEquals(4.0/3,q.aspectRatio(),1e-12);
        assertEquals(10.2/16,q.width(),1e-12);assertEquals(7.65/16,q.height(),1e-12);
    }
    @Test void commonBoundsMatchRecessedGlassWithoutApplyingDynamicOffsetTwice() {
        var b=cn.piq.fcarcade.layout.ArcadeScreenBounds.resolve(1,1,cn.piq.fcarcade.layout.ArcadeDisplayStyle.HOME_VINTAGE_TV);
        assertEquals(4.35/16,b.min(),1e-7);assertEquals(14.55/16,b.max(),1e-7);
        assertEquals(4.35/16,b.negativeMin(),1e-7);assertEquals(14.55/16,b.positiveMax(),1e-7);
        assertEquals(2.0/16,b.bottom(),1e-7);assertEquals(9.65/16,b.top(),1e-7);
        assertEquals(3.35/16,b.frontInset(),1e-7);assertEquals(1.35,b.frontInset()*16-2,1e-6);
        assertEquals(4.0/3,b.aspectRatio(),1e-6);
        assertEquals(b.frontInset()-.0015,VintageTvLayout.screen(0).lowerMinX().z(),1e-7);
    }
    @Test void screenAndNormalRotateTogetherInAllFacings() {
        var base=VintageTvLayout.screen(0);
        for(int t=-4;t<8;t++) {
            var q=VintageTvLayout.screen(t);
            point(rotate(base.lowerMinX(),t),q.lowerMinX());point(rotate(base.lowerMaxX(),t),q.lowerMaxX());
            point(rotate(base.upperMaxX(),t),q.upperMaxX());point(rotate(base.upperMinX(),t),q.upperMinX());
            Point n=rotate(new Point(.5,0,-.5),t);point(new Point(n.x()-.5,0,n.z()-.5),q.normal());
            assertSame(q,VintageTvLayout.screen(Math.floorMod(t,4)));
        }
    }
    @Test void modelBoundsAreSingleCellAndContainScreenAfterRemovingNormalOffset() {
        for(int t=0;t<4;t++) {
            var b=VintageTvLayout.bounds(t);var q=VintageTvLayout.screen(t);
            assertTrue(b.minX()>=0 && b.minZ()>=0 && b.maxX()<=16 && b.maxZ()<=16);
            assertEquals(0,b.minY(),1e-12);assertEquals(14.3,b.maxY(),1e-12);
            for(var p:new Point[]{q.lowerMinX(),q.lowerMaxX(),q.upperMaxX(),q.upperMinX()}) {
                double x=(p.x()-q.normal().x()*.0015)*16,z=(p.z()-q.normal().z()*.0015)*16;
                assertTrue(x>=b.minX() && x<=b.maxX() && z>=b.minZ() && z<=b.maxZ());
            }
            Point a=rotate(new Point(.2/16,0,1.8/16),t),c=rotate(new Point(15.8/16,14.3/16,14.2/16),t);
            assertEquals(Math.min(a.x(),c.x())*16,b.minX(),1e-12);assertEquals(Math.max(a.x(),c.x())*16,b.maxX(),1e-12);
            assertEquals(Math.min(a.z(),c.z())*16,b.minZ(),1e-12);assertEquals(Math.max(a.z(),c.z())*16,b.maxZ(),1e-12);
        }
    }
    @Test void mirroredYellowWhiteRedSocketsHaveNegativeXChannelProgression() {
        for(int t=-4;t<8;t++)for(int c=0;c<3;c++)point(rotate(new Point((9.5-c*2)/16,3.1/16,14.04/16),t),VintageTvLayout.socket(t,c));
        assertThrows(IllegalArgumentException.class,()->VintageTvLayout.socket(0,-1));
        assertThrows(IllegalArgumentException.class,()->VintageTvLayout.socket(0,3));
    }
    static Point rotate(Point p,int turns){for(int i=0;i<Math.floorMod(turns,4);i++)p=new Point(1-p.z(),p.y(),p.x());return p;}
    static void point(Point a,Point b){assertEquals(a.x(),b.x(),1e-12);assertEquals(a.y(),b.y(),1e-12);assertEquals(a.z(),b.z(),1e-12);}
    public static void main(String[] args)throws Exception{var i=new VintageTvLayoutTest();int n=0;for(var m:VintageTvLayoutTest.class.getDeclaredMethods())if(m.isAnnotationPresent(Test.class)){m.invoke(i);n++;}System.out.println("Vintage TV layout tests passed: "+n);}
}
