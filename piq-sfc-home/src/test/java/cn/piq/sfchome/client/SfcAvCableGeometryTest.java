package cn.piq.sfchome.client;

import cn.piq.sfchome.client.SfcAvCableGeometry.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SfcAvCableGeometryTest {
    private static Endpoint shifted(Endpoint e,double dx,double dy,double dz) {
        Vec d=new Vec(dx,dy,dz);Box b=e.housing();
        return new Endpoint(e.sockets().stream().map(p->p.add(d)).toList(),
                new Box(b.minX()+dx,b.minY()+dy,b.minZ()+dz,b.maxX()+dx,b.maxY()+dy,b.maxZ()+dz),e.outward(),e.baseY()+dy,e.plugScale());
    }
    @Test void actualNativeSocketsAndFourRotations() {
        for(int t=0;t<4;t++) {
            var point=cn.piq.sfchome.layout.SfcConsoleScale.console(5.55,.695,15.5375);
            Vec expected=SfcAvCableGeometry.rotate(new Vec(point.x()/16,point.y()/16,point.z()/16),t);
            assertEquals(1,SfcAvCableGeometry.console(t).sockets().size());
            assertEquals(expected,SfcAvCableGeometry.console(t).sockets().getFirst());
            assertEquals(1,SfcAvCableGeometry.outward(t).length(),1e-10);
        }
    }
    @Test void allSixteenRelativeFacingsHaveTabletopTrunk() {
        for(int a=0;a<4;a++)for(int b=0;b<4;b++) {
            Mesh m=SfcAvCableGeometry.build(SfcAvCableGeometry.console(a),shifted(SfcAvCableGeometry.console(b),-4,0,0));
            assertTrue(m.visible(),m.rejection());assertTrue(m.quads().size()<6000);
            for(Vec p:m.trunk())assertEquals(.022,p.y(),1e-9);
            for(Quad q:m.quads())for(Vec p:List.of(q.a(),q.b(),q.c(),q.d()))assertTrue(p.finite()&&p.y()>=0);
        }
    }
    @Test void sameEndpointsCannotEmitDegenerateCable() {
        Endpoint c=SfcAvCableGeometry.console(0);assertFalse(SfcAvCableGeometry.build(c,c).visible());
    }
    @Test void malformedAndFarCoordinatesFailClosed() {
        Endpoint c=SfcAvCableGeometry.console(0);
        assertFalse(SfcAvCableGeometry.build(null,c).visible());
        assertFalse(SfcAvCableGeometry.build(c,shifted(c,100,0,0)).visible());
        assertFalse(SfcAvCableGeometry.build(c,new Endpoint(c.sockets(),null,c.outward(),0,.45)).visible());
        assertFalse(SfcAvCableGeometry.build(c,new Endpoint(c.sockets(),c.housing(),new Vec(0,1,0),0,.45)).visible());
        assertFalse(SfcAvCableGeometry.build(c,new Endpoint(c.sockets(),c.housing(),c.outward(),Double.NaN,.45)).visible());
    }
    @Test void wronglySpacedFullSizePlugsFailClosed() {
        Endpoint c=SfcAvCableGeometry.console(0),t=shifted(c,-4,0,0);
        Vec p=c.sockets().getFirst();
        assertFalse(SfcAvCableGeometry.build(new Endpoint(List.of(p,p.add(new Vec(.04,0,0)),p.add(new Vec(.08,0,0))),c.housing(),c.outward(),0,1),t).visible());
    }
    @Test void mixedHeightIsExplicitBridgeNotTableInference() {
        Endpoint c=SfcAvCableGeometry.console(0);Mesh m=SfcAvCableGeometry.build(c,shifted(c,-4,1,0));
        assertTrue(m.visible(),m.rejection());assertEquals(1.022,m.supportY(),1e-9);
    }
    @Test void recordsCannotBeMutatedAfterCaching() {
        Endpoint c=SfcAvCableGeometry.console(0);Mesh m=SfcAvCableGeometry.build(c,shifted(c,-4,0,0));
        assertThrows(UnsupportedOperationException.class,()->c.sockets().clear());
        assertThrows(UnsupportedOperationException.class,()->m.trunk().clear());
        assertThrows(UnsupportedOperationException.class,()->m.quads().clear());
    }
    @Test void exactTriangleSatDistinguishesContactAndOutside() {
        Box b=new Box(0,0,0,1,1,1);
        assertTrue(SfcAvCableGeometry.triangleIntersects(new Vec(.2,.2,.5),new Vec(.8,.2,.5),new Vec(.5,.8,.5),b));
        assertFalse(SfcAvCableGeometry.triangleIntersects(new Vec(.2,.2,1.1),new Vec(.8,.2,1.1),new Vec(.5,.8,1.1),b));
    }
}
