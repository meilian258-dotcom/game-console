package cn.piq.fcarcade.layout;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CabinetPowerMeshTest {
    @Test void indicatorChangesColorAndRockerTiltsInOppositeDirections(){
        assertTrue(CabinetPowerMesh.surface(.06,true)<CabinetPowerMesh.surface(-.06,true));
        assertTrue(CabinetPowerMesh.surface(.06,false)>CabinetPowerMesh.surface(-.06,false));
        assertTrue(CabinetPowerMesh.faces(true).stream().anyMatch(f->f.rgb()==0xFF3023));
        assertTrue(CabinetPowerMesh.faces(false).stream().anyMatch(f->f.rgb()==0x761A17));
        assertEquals(CabinetPowerMesh.faces(false).size(),CabinetPowerMesh.faces(true).size());
        assertSame(CabinetPowerMesh.faces(true),CabinetPowerMesh.faces(true));
    }
    @Test void allVerticesStayInSharedClickableSilhouetteAndOutsideSidePanel(){
        for(boolean on:new boolean[]{false,true})for(var f:CabinetPowerMesh.faces(on))for(var v:f.vertices()){
            assertTrue(Double.isFinite(v.u())&&Double.isFinite(v.v())&&Double.isFinite(v.w()));
            assertTrue(Math.abs(v.u())<=CabinetPowerMesh.HALF_WIDTH+1e-9&&Math.abs(v.v())<=CabinetPowerMesh.HALF_HEIGHT+1e-9);
            assertTrue(v.w()>=0&&v.w()<.024);
        }
    }
    @Test void frontMountFitsBlankPanelAndMeshStaysOutsideCabinet(){
        for(int kind=0;kind<3;kind++)for(boolean compact:new boolean[]{false,true}){
            var b=(kind==2?PortraitCabinetGeometry.powerBoxes():CabinetPowerGeometry.boxes(kind==1,compact)).getFirst();
            assertEquals(.11,b.maxY()-b.minY(),1e-9);assertEquals(.08,b.maxX()-b.minX(),1e-9);
            assertTrue(b.minY()>.5&&b.maxY()<.9);
            double plane=kind==2?PortraitCabinetGeometry.point(0,0,3.02).z():(kind==1?DualCabinetGeometry.modelZOffset(compact):0)+3.02/16;
            assertEquals(.0005,b.minZ()-plane,1e-9);
            double doorMax=kind==2?PortraitCabinetGeometry.point(10.45,0,0).x():kind==1?.25+20.45/16:10.45/16;
            double trimMin=kind==2?PortraitCabinetGeometry.point(15.436,0,0).x():kind==1?.25+23.155/16:14.475/16;
            assertTrue(b.minX()>doorMax);assertTrue(b.maxX()<trimMin);
            for(boolean on:new boolean[]{false,true}){
                boolean outside=false;
                for(var f:CabinetPowerMesh.faces(on))for(var v:f.vertices()){
                    var p=CabinetPowerGeometry.meshPoint(b,v.u(),v.v(),v.w());
                    assertTrue(p.x()>=b.minX()-1e-9&&p.x()<=b.maxX()+1e-9);
                    assertTrue(p.y()>=b.minY()-1e-9&&p.y()<=b.maxY()+1e-9);
                    if(p.z()<plane)outside=true;
                }
                assertTrue(outside);
            }
        }
    }
}
