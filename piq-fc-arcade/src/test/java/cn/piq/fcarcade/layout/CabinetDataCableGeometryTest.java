package cn.piq.fcarcade.layout;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import cn.piq.fcarcade.layout.RocketArcadeGeometry.Point;

class CabinetDataCableGeometryTest {
    @Test void parallelCabinetsUseRearPlinthAndFloorRunInAllFourDirections(){
        for(boolean compact:new boolean[]{false,true})for(boolean a:new boolean[]{false,true})for(boolean b:new boolean[]{false,true})for(int turn=0;turn<4;turn++)for(int sign:new int[]{-1,1}){
            Point delta=RocketArcadeGeometry.rotate(new Point(.5+sign*3,0,.5),turn);
            double dx=delta.x()-.5,dz=delta.z()-.5;
            var path=CabinetDataCableGeometry.path(a,turn,b,turn,dx,0,dz,compact,compact);
            assertTrue(path.size()>=4&&path.size()<=14);
            var first=CabinetDataCableGeometry.body(a,0,compact);var second=CabinetDataCableGeometry.body(b,0,compact);
            Point start=RocketArcadeGeometry.rotate(path.getFirst(),-turn),end=RocketArcadeGeometry.rotate(path.getLast(),-turn);
            assertEquals(a?1:.5,start.x(),1e-8);
            assertEquals(sign*3+(b?1:.5),end.x(),1e-8);
            assertEquals(first.maxZ()+.012,start.z(),1e-8);assertEquals(second.maxZ()+.012,end.z(),1e-8);
            for(var p:path)assertEquals(CabinetDataCableGeometry.FLOOR_HEIGHT,p.y(),1e-8,"entire path exits at floor level");
            for(var p:path){var local=RocketArcadeGeometry.rotate(p,-turn);
                assertTrue(local.z()>Math.min(first.maxZ(),second.maxZ()));
            }
            assertTrue(CabinetDataCableGeometry.build(a,turn,b,turn,dx,0,dz,compact,compact).size()<=CabinetDataCableGeometry.MAX_QUADS);
        }
    }
    @Test void narrowAndWideSideGapsKeepGroundContactAndDoNotFoldBack(){
        for(double gap:new double[]{.081,.1,.25,.5,1,4,12})for(double dy:new double[]{-.25,0,.25}){
            double dx=1.5+.024+gap;
            var path=CabinetDataCableGeometry.path(true,0,true,0,dx,dy,0,true,true);
            assertTrue(path.size()>=4&&path.size()<=14);
            double floor=Math.min(0,dy)+CabinetDataCableGeometry.FLOOR_HEIGHT;
            assertEquals(CabinetDataCableGeometry.FLOOR_HEIGHT,path.getFirst().y(),1e-8);
            assertEquals(dy+CabinetDataCableGeometry.FLOOR_HEIGHT,path.getLast().y(),1e-8);
            for(int i=1;i<path.size();i++){
                var a=path.get(i-1);var b=path.get(i);
                assertTrue(b.x()>=a.x()-1e-8);assertTrue(b.y()>=floor-1e-8);
                assertTrue(Math.abs(a.x()-b.x())+Math.abs(a.y()-b.y())+Math.abs(a.z()-b.z())>1e-8);
            }
            if(dy==0)for(var p:path)assertEquals(floor,p.y(),1e-8);
            var mesh=CabinetDataCableGeometry.build(true,0,true,0,dx,dy,0,true,true);
            assertTrue(mesh.size()<=CabinetDataCableGeometry.MAX_QUADS);
            for(var q:mesh)for(var p:List.of(q.a(),q.b(),q.c(),q.d()))assertTrue(p.y()>=Math.min(0,dy)-1e-8,"tube must stay above support plane");
        }
    }
    @Test void floorRunSurvivesSmallForeAftOffsetAndMixedFootprints(){
        for(boolean ca:new boolean[]{false,true})for(boolean cb:new boolean[]{false,true})for(double dz:new double[]{-.25,0,.25}){
            var path=CabinetDataCableGeometry.path(true,0,true,0,3,0,dz,ca,cb);
            assertTrue(path.size()>=4&&path.size()<=14);
            for(var p:path)assertEquals(CabinetDataCableGeometry.FLOOR_HEIGHT,p.y(),1e-8);
            var a=CabinetDataCableGeometry.body(true,0,ca);var b=CabinetDataCableGeometry.body(true,0,cb);
            assertEquals(a.maxZ()+.012,path.getFirst().z(),1e-8);assertEquals(dz+b.maxZ()+.012,path.getLast().z(),1e-8);
        }
    }
    @Test void neighboringTubeSegmentsShareExactRingsWithoutCracks(){
        for(int turn=0;turn<4;turn++)for(int secondTurn=0;secondTurn<4;secondTurn++){
            var path=CabinetDataCableGeometry.path(true,turn,true,secondTurn,3,0,0,true,true);
            var mesh=CabinetDataCableGeometry.build(true,turn,true,secondTurn,3,0,0,true,true);
            assertFalse(path.isEmpty());
            int sides=CabinetDataCableGeometry.SIDES;
            for(int i=0;i<path.size()-2;i++)for(int side=0;side<sides;side++){
                var a=mesh.get(i*sides+side);var b=mesh.get((i+1)*sides+side);
                assertEquals(a.d(),b.a());assertEquals(a.c(),b.b());
            }
            for(int i=0;i<path.size()-1;i++)for(int side=0;side<sides;side++){
                var a=mesh.get(i*sides+side);var b=mesh.get(i*sides+(side+1)%sides);
                assertEquals(a.b(),b.a());assertEquals(a.c(),b.d());
            }
        }
    }
    @Test void adjacentSingleRearPanelsKeepVisibleCableWithoutLongLeadPenetration(){
        for(int turn=0;turn<4;turn++){
            Point delta=RocketArcadeGeometry.rotate(new Point(.5,0,1.5),turn);double dx=delta.x()-.5,dz=delta.z()-.5;
            var path=CabinetDataCableGeometry.path(false,turn,false,turn+2,dx,0,dz);assertFalse(path.isEmpty());
            var first=CabinetDataCableGeometry.body(false,turn);var second=CabinetDataCableGeometry.body(false,turn+2);
            for(int i=0;i<path.size()-1;i++)for(int j=0;j<=20;j++){double t=j/20D;Point a=path.get(i),b=path.get(i+1);double x=a.x()+(b.x()-a.x())*t,z=a.z()+(b.z()-a.z())*t;
                assertFalse(x>first.minX()&&x<first.maxX()&&z>first.minZ()&&z<first.maxZ());
                assertFalse(x>second.minX()+dx&&x<second.maxX()+dx&&z>second.minZ()+dz&&z<second.maxZ()+dz);
            }
            assertFalse(CabinetDataCableGeometry.build(false,turn,false,turn+2,dx,0,dz).isEmpty());
        }
    }
    @Test void allCabinetTypesAndRotationsProduceFiniteBoundedMesh(){for(boolean a:new boolean[]{false,true})for(boolean b:new boolean[]{false,true})for(int ta=0;ta<4;ta++)for(int tb=0;tb<4;tb++){
        var path=CabinetDataCableGeometry.path(a,ta,b,tb,6,0,4);var mesh=CabinetDataCableGeometry.build(a,ta,b,tb,6,0,4);assertFalse(path.isEmpty());assertFalse(mesh.isEmpty());assertTrue(path.size()<=14);assertTrue(mesh.size()<=CabinetDataCableGeometry.MAX_QUADS);
        assertEquals(CabinetDataCableGeometry.socket(a,ta),path.getFirst());Point end=CabinetDataCableGeometry.socket(b,tb);assertEquals(new Point(end.x()+6,end.y(),end.z()+4),path.getLast());
        for(var q:mesh)for(Point p:List.of(q.a(),q.b(),q.c(),q.d(),q.normal()))assertTrue(Double.isFinite(p.x())&&Double.isFinite(p.y())&&Double.isFinite(p.z()));
    }}
    @Test void socketFollowsExactRearPlaneAndRotation(){for(boolean dual:new boolean[]{false,true})for(int turn=0;turn<4;turn++){var north=CabinetDataCableGeometry.socket(dual,0);assertEquals(CabinetDataCableGeometry.body(dual,0).maxZ()+.012,north.z());assertEquals(RocketArcadeGeometry.rotate(north,turn),CabinetDataCableGeometry.socket(dual,turn));}}
    @Test void horizontalFloorRouteDoesNotCrossEitherExpandedFootprint(){for(boolean a:new boolean[]{false,true})for(boolean b:new boolean[]{false,true})for(int ta=0;ta<4;ta++)for(int tb=0;tb<4;tb++){
        var path=CabinetDataCableGeometry.path(a,ta,b,tb,5,0,0);assertFalse(path.isEmpty());var first=CabinetDataCableGeometry.body(a,ta);var second=CabinetDataCableGeometry.body(b,tb);
        for(int i=2;i<path.size()-2;i++){Point left=path.get(i),right=path.get(i+1);for(int sample=0;sample<=100;sample++){double t=sample/100D;double x=left.x()+(right.x()-left.x())*t,z=left.z()+(right.z()-left.z())*t;
            assertFalse(x>first.minX()&&x<first.maxX()&&z>first.minZ()&&z<first.maxZ());assertFalse(x>second.minX()+5&&x<second.maxX()+5&&z>second.minZ()&&z<second.maxZ());}}
    }}
    @Test void normalsAreOutwardAndUnitLength(){for(var q:CabinetDataCableGeometry.build(true,3,false,1,8,2,0)){Point a=q.a(),b=q.b(),c=q.c(),n=q.normal();double ux=b.x()-a.x(),uy=b.y()-a.y(),uz=b.z()-a.z(),vx=c.x()-a.x(),vy=c.y()-a.y(),vz=c.z()-a.z();assertTrue((uy*vz-uz*vy)*n.x()+(uz*vx-ux*vz)*n.y()+(ux*vy-uy*vx)*n.z()>0);assertEquals(1,n.x()*n.x()+n.y()*n.y()+n.z()*n.z(),1e-8);}}
    @Test void invalidDistanceNanAndCoincidentEndpointsReject(){for(double v:new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,17,1e30})assertTrue(CabinetDataCableGeometry.build(false,0,false,0,v,0,0).isEmpty());assertTrue(CabinetDataCableGeometry.build(false,0,false,0,0,0,0).isEmpty());assertFalse(CabinetDataCableGeometry.build(false,0,false,0,16,0,0).isEmpty());}
    @Test void immutableResultsCannotBeChangedByRenderingConsumer(){var mesh=CabinetDataCableGeometry.build(false,0,true,0,5,0,0);assertThrows(UnsupportedOperationException.class,mesh::clear);var path=CabinetDataCableGeometry.path(false,0,true,0,5,0,0);assertThrows(UnsupportedOperationException.class,path::clear);}
}
