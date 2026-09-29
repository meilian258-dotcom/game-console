package cn.piq.fcarcade.layout;
import org.junit.jupiter.api.Test;
import cn.piq.fcarcade.layout.RocketArcadeGeometry.Point;
import static org.junit.jupiter.api.Assertions.*;

class ZapperStandGeometryTest {
    @Test void originalModelEnvelopeRemainsSameSizeForEveryFacing(){for(int i=0;i<4;i++){var b=ZapperStandGeometry.bounds(i);assertEquals(5.274/16,b.maxY());assertEquals((9.0002*4.1002)/256,(b.maxX()-b.minX())*(b.maxZ()-b.minZ()),1e-10);assertTrue(b.minX()>0&&b.maxX()<1&&b.minZ()>0&&b.maxZ()<1);}}
    @Test void facingRotationIsInvertibleAndCordIsOriginalScale(){for(int i=0;i<4;i++){var p=ZapperStandGeometry.rotate(ZapperStandGeometry.CORD,i);var q=ZapperStandGeometry.rotate(p,4-i);assertEquals(ZapperStandGeometry.CORD.x(),q.x(),1e-12);assertEquals(ZapperStandGeometry.CORD.z(),q.z(),1e-12);}}
    @Test void dynamicCordHasExactEndpointsAndBoundedWork(){var a=new Point(0,1,0);for(int n=1;n<=20;n++){var b=new Point(n,3,0);var line=ZapperStandGeometry.cable(a,b);assertEquals(a,line.getFirst());var end=line.getLast();assertEquals(b.x(),end.x());assertEquals(b.y(),end.y(),1e-12);assertEquals(b.z(),end.z());assertTrue(line.size()<=65);assertThrows(UnsupportedOperationException.class,()->line.clear());}}
    @Test void malformedOrUnboundedCordIsInvisible(){var a=new Point(0,0,0);assertTrue(ZapperStandGeometry.cable(a,a).isEmpty());assertTrue(ZapperStandGeometry.cable(a,new Point(25,0,0)).isEmpty());assertTrue(ZapperStandGeometry.cable(a,new Point(Double.NaN,0,0)).isEmpty());}
}
