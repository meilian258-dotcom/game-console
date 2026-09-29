package cn.piq.fcarcade.client;
import cn.piq.fcarcade.client.HomeHardwareRenderLayout.Point;
import cn.piq.fcarcade.layout.ArcadeDisplayStyle;
import cn.piq.fcarcade.home.UserTvLayout;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class UserTvSocketAlignedCableTest {
    @Test void equalHeightOverlappingHousingsStillRejectTheCable() {
        for(var tv:List.of(ArcadeDisplayStyle.HOME_PANEL_2_WALL,ArcadeDisplayStyle.HOME_PANEL_3_WALL))
            assertTrue(UserTvCableMesh.buildFc(false,false,false,0,tv,0,1-UserTvLayout.width(tv),0,0).isEmpty());
    }
    @Test void allFlexibleTrunkCrossSectionsHaveTheSameRadius() {
        for(var tv:Arrays.stream(ArcadeDisplayStyle.values()).filter(UserTvLayout::supports).toList())
        for(int type=0;type<5;type++) {
            var mesh=type<4?UserTvCableMesh.buildFc(type!=0,type>=2,type==3,0,tv,0,0,UserTvLayout.wall(tv)?2:0,4)
                :UserTvCableMesh.buildMultiOut(new Point(4.325/16,1.0425/16,17.976125/16),
                new UserTvCableMesh.Bounds(2D/16,0,3.344375/16,14D/16,7.62/16,17.976125/16),0,.675,tv,0,0,UserTvLayout.wall(tv)?2:0,4);
            assertFalse(mesh.isEmpty());
            // Production tube's first eight side quads expose its first ring.
            var ring=mesh.subList(0,8).stream().map(HomeAvCableMesh.Quad::a).toList();
            Point center=new Point(ring.stream().mapToDouble(Point::x).average().orElseThrow(),
                ring.stream().mapToDouble(Point::y).average().orElseThrow(),ring.stream().mapToDouble(Point::z).average().orElseThrow());
            for(var p:ring)assertEquals(.015,Math.sqrt(Math.pow(p.x()-center.x(),2)+Math.pow(p.y()-center.y(),2)+Math.pow(p.z()-center.z(),2)),1e-9,tv+" type="+type);
        }
    }
    @Test void noExtraRedTraysButBothControllerRecessesRemain() throws Exception {
        for(String model:List.of("home_console_body","home_famicom_console")) {
            var json=com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/assets/piq_fc_arcade/models/block/"+model+".json"))).getAsJsonObject();
            var names=new HashSet<String>();
            for(var element:json.getAsJsonArray("elements"))names.add(element.getAsJsonObject().get("name").getAsString());
            for(int p=1;p<=2;p++) {
                assertTrue(names.contains("FC48 P"+p+" visible well back"));
                for(String part:List.of("support shelf","front stop","rear stop"))assertFalse(names.contains("FC48 P"+p+" "+part));
            }
        }
    }
    @Test void exportSocketAlignedCaseForActualMeshReview() throws Exception {
        var scenes=new LinkedHashMap<String,Object>();
        for(int width:new int[]{2,3}) {
            var tv=width==2?ArcadeDisplayStyle.HOME_PANEL_2_WALL:ArcadeDisplayStyle.HOME_PANEL_3_WALL;
            var mesh=UserTvCableMesh.buildFc(false,false,false,0,tv,0,1-width,2,0);assertFalse(mesh.isEmpty());
            scenes.put("fc-socket-aligned-"+width,Map.of("tv","panel_tv_"+width+"_wall","source",0,"offset",new double[]{1-width,2,0},"quads",mesh));
        }
        java.nio.file.Files.writeString(java.nio.file.Path.of("build/cable51-preview.json"),new com.google.gson.Gson().toJson(scenes));
    }
    @Test void fcDirectlyBelowAvSocketsStaysVisibleAcrossWidthsHeightsAndRotations() {
        for(var tv:List.of(ArcadeDisplayStyle.HOME_PANEL_2_WALL,ArcadeDisplayStyle.HOME_PANEL_3_WALL))
        for(int turn=0;turn<4;turn++)for(int height:new int[]{1,2,3}) {
            double x=1-UserTvLayout.width(tv);
            Point p=HomeHardwareRenderLayout.rotate(new Point(x+.5,0,.5),turn);
            var mesh=UserTvCableMesh.buildFc(false,false,false,turn,tv,turn,p.x()-.5,height,p.z()-.5);
            assertFalse(mesh.isEmpty(),"Actual AV socket alignment "+tv+" turn="+turn+" y="+height);
        }
    }
}
