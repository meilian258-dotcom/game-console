package cn.piq.fcarcade.layout;

import cn.piq.fcarcade.cabinet.CabinetSeats;
import cn.piq.fcarcade.layout.RocketArcadeGeometry.*;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PortraitCabinetTest {
    private static double distance(Point a,Point b){return Math.sqrt(Math.pow(a.x()-b.x(),2)+Math.pow(a.y()-b.y(),2)+Math.pow(a.z()-b.z(),2));}
    @Test void glassIsThreeByFourAndUniformInEveryFacing(){
        for(int t=0;t<4;t++){
            var q=PortraitCabinetGeometry.screen(t);
            assertEquals(.75,distance(q.lowerMinX(),q.lowerMaxX())/distance(q.lowerMinX(),q.upperMinX()),1e-9);
            assertEquals(1,distance(q.normal(),new Point(0,0,0)),1e-9);
            for(var p:List.of(q.lowerMinX(),q.lowerMaxX(),q.upperMinX(),q.upperMaxX()))assertTrue(p.y()>0&&p.y()<2);
            var p=PortraitCabinetGeometry.point(7.3,16.4,2.9);var original=PortraitCabinetGeometry.source(p);
            assertEquals(7.3/16,original.x(),1e-9);assertEquals(16.4/16,original.y(),1e-9);assertEquals(2.9/16,original.z(),1e-9);
        }
    }
    @Test void switchIsSmallFrontLeftAndActuallyClickable(){
        var boxes=PortraitCabinetGeometry.powerBoxes();assertEquals(1,boxes.size());var b=boxes.getFirst();
        double y=(b.minY()+b.maxY())/2,z=b.minZ(),x=(b.minX()+b.maxX())/2;
        assertTrue(y>.6&&y<.9);assertEquals(.11,b.maxY()-b.minY(),1e-8);
        for(int t=0;t<4;t++){
            var eye=RocketArcadeGeometry.rotate(new Point(x,y,z-2),t);var end=RocketArcadeGeometry.rotate(new Point(x,y,z+.1),t);
            assertTrue(CabinetPowerGeometry.hits(boxes,t,eye,end));assertFalse(CabinetPowerGeometry.hits(boxes,t,end,eye));
        }
        assertNotEquals(CabinetPowerMesh.faces(false),CabinetPowerMesh.faces(true));
    }
    @Test void portraitHasOneSeatButLinkedPhysicalPanelsStillWork(){
        for(int supported=1;supported<=4;supported++)assertEquals(1,CabinetSeats.capacity(supported,false,false,false,true));
        assertEquals(2,CabinetSeats.capacity(4,true,false,false,true));
        assertEquals(3,CabinetSeats.capacity(4,true,false,true,true));
        assertEquals(0,CabinetSeats.capacity(1,true,false,false,true));
        assertEquals(2,CabinetSeats.capacity(4,false,false,false)); // Old cabinet compatibility.
    }
    @Test void retainedBandGeometryRemainsValidInEveryFacing(){
        for(boolean portrait:new boolean[]{false,true})for(boolean compact:new boolean[]{false,true})for(int turn=0;turn<4;turn++){
            var boxes=CabinetOutlineGeometry.boxes(portrait,compact,turn);assertTrue(boxes.size()>8&&boxes.size()<=24);
            double oldVolume=portrait?1*2*1.1:2*2*1.3,volume=0;
            for(var b:boxes){assertTrue(b.maxX()>b.minX()&&b.maxY()>b.minY()&&b.maxZ()>b.minZ());assertTrue(b.minY()>=0&&b.maxY()<=2);volume+=(b.maxX()-b.minX())*(b.maxY()-b.minY())*(b.maxZ()-b.minZ());}
            assertTrue(volume<oldVolume*.95,"Outline must eliminate empty space, not simply rename a box");
        }
    }
    @Test void portraitCableUsesActualBodyAndRestsOnFloor(){
        for(int t=0;t<4;t++){
            var offset=RocketArcadeGeometry.rotate(new Point(2.5,0,.5),t);
            var path=CabinetDataCableGeometry.path(false,t,false,t,offset.x()-.5,0,offset.z()-.5,false,false,true,true);
            assertFalse(path.isEmpty());assertTrue(path.stream().anyMatch(p->Math.abs(p.y()-CabinetDataCableGeometry.FLOOR_HEIGHT)<1e-9));
            var first=RocketArcadeGeometry.rotate(path.getFirst(),-t);
            assertEquals(.5,first.x(),1e-9);
            assertEquals(PortraitCabinetGeometry.bounds(0).maxZ()+.012,first.z(),1e-9);
            assertFalse(CabinetDataCableGeometry.build(false,t,false,t,offset.x()-.5,0,offset.z()-.5,false,false,true,true).isEmpty());
        }
    }
    @Test void allNineModelsAreValidAndAllEightControlsAreRegistered()throws Exception{
        var dir=Path.of("src/main/resources/assets/piq_fc_arcade/models/block/portrait");int count=0,elements=0;
        var renderer=Files.readString(Path.of("src/main/java/cn/piq/fcarcade/client/PortraitCabinetRenderer.java"));
        try(var paths=Files.list(dir)){
            for(var file:paths.toList()){
                var model=JsonParser.parseString(Files.readString(file)).getAsJsonObject();count++;
                var name=file.getFileName().toString().replace(".json","");assertTrue(renderer.contains("\""+name+"\""));
                for(var e:model.getAsJsonArray("elements")){elements++;var obj=e.getAsJsonObject();
                    for(String key:List.of("from","to"))for(var n:obj.getAsJsonArray(key))assertTrue(n.getAsDouble()>=-16&&n.getAsDouble()<=32);
                    for(var face:obj.getAsJsonObject("faces").entrySet())for(var uv:face.getValue().getAsJsonObject().getAsJsonArray("uv"))assertTrue(uv.getAsDouble()>=0&&uv.getAsDouble()<=16);
                }
            }
        }
        assertEquals(9,count);assertTrue(elements>30);assertTrue(renderer.contains("DualCabinetControls.motion"));assertTrue(renderer.contains("CabinetPowerRenderer.render"));
    }
    @Test void upperHalfAndCompatibilityAreWiredWithoutDuplicateDrops()throws Exception{
        var source=Files.readString(Path.of("src/main/java/cn/piq/fcarcade/world/PortraitCabinetBlock.java"));
        assertTrue(source.contains("UPPER)?null:new PortraitCabinetBlockEntity"));
        assertTrue(source.contains("UPPER)?List.of():List.of(new ItemStack(asItem()))"));
        assertTrue(source.contains("CommonHooks.fireBlockBreak"));assertTrue(source.contains("player.isCreative()&&s.getValue(UPPER)"));
        assertTrue(source.contains("CabinetLinks.removed"));assertTrue(source.contains("ServerCabinets.validatedTarget"));
        var target=Files.readString(Path.of("src/main/java/cn/piq/fcarcade/cabinet/CabinetTarget.java"));assertTrue(target.contains("PortraitCabinetBlock.resolveAnchor"));
    }
}
