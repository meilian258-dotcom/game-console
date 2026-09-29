package cn.piq.sfchome.client;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Current user mesh contract; older generated-model geometry is intentionally superseded. */
class SfcHardwareMeshDataTest {
    private JsonObject asset()throws Exception{return JsonParser.parseString(Files.readString(Path.of("src/main/resources/assets/piq_sfc_home/meshes/sfc_hardware.json"))).getAsJsonObject();}
    private void rejects(JsonObject bad){assertThrows(Exception.class,()->SfcHardwareMeshData.read(new StringReader(bad.toString())));}
    private JsonObject firstPart(JsonObject a){return a.getAsJsonObject("groups").getAsJsonObject("controller").getAsJsonArray("parts").get(0).getAsJsonObject();}
    private JsonObject triangle(JsonObject a){return firstPart(a).getAsJsonArray("triangles").get(0).getAsJsonObject();}
    private java.util.Map<String,java.util.List<SfcHardwareMeshData.Part>> parsed()throws Exception{return SfcHardwareMeshData.read(new StringReader(asset().toString()));}
    @Test void realAssetHasAllIndependentLayersAndFiniteVertices()throws Exception{var groups=parsed();assertEquals(SfcHardwareMeshData.GROUPS,groups.keySet());for(var parts:groups.values())for(var p:parts){assertTrue(p.vertices().length>0);assertEquals(0,p.vertices().length%24);for(float v:p.vertices())assertTrue(Float.isFinite(v));}assertThrows(UnsupportedOperationException.class,groups::clear);assertTrue(groups.get("slot_cover").isEmpty());}
    @Test void rejectsUnknownVersion()throws Exception{var a=asset();a.addProperty("version",2);rejects(a);}
    @Test void rejectsMissingGroup()throws Exception{var a=asset();a.getAsJsonObject("groups").remove("inserted");rejects(a);}
    @Test void rejectsArbitraryTextureSource()throws Exception{var a=asset();a.getAsJsonObject("materials").addProperty("hardware","file:///private.png");rejects(a);}
    @Test void rejectsOversizedCoordinate()throws Exception{var a=asset();triangle(a).getAsJsonArray("p").get(0).getAsJsonArray().set(0,new com.google.gson.JsonPrimitive(100));rejects(a);}
    @Test void rejectsNaN()throws Exception{var a=asset();triangle(a).getAsJsonArray("p").get(0).getAsJsonArray().set(0,new com.google.gson.JsonPrimitive("NaN"));rejects(a);}
    @Test void rejectsNonUnitNormal()throws Exception{var a=asset();triangle(a).getAsJsonArray("n").set(0,new com.google.gson.JsonPrimitive(1));triangle(a).getAsJsonArray("n").set(1,new com.google.gson.JsonPrimitive(1));rejects(a);}
    @Test void rejectsInvalidUv()throws Exception{var a=asset();triangle(a).getAsJsonArray("uv").get(0).getAsJsonArray().set(0,new com.google.gson.JsonPrimitive(-.1));rejects(a);}
    @Test void rejectsUnknownMaterial()throws Exception{var a=asset();firstPart(a).addProperty("material","missing");rejects(a);}
    @Test void rejectsWrongTriangleArity()throws Exception{var a=asset();triangle(a).getAsJsonArray("p").remove(2);rejects(a);}
    @Test void independentUserButtonGroupsHaveExactPivotAndTravel()throws Exception{
        var groups=parsed();var keys=new java.util.HashSet<String>();for(var p:groups.get("controller"))if(p.binding()!=null){keys.add(p.binding().key());assertEquals(.07/16,p.binding().press(),1e-7);assertEquals("piq_sfc_home:textures/block/user_sfc_20260911.png",p.texture());}
        assertEquals(SfcButtonAnimation.KEYS,keys);
        var a=groups.get("controller").stream().map(SfcHardwareMeshData.Part::binding).filter(b->b!=null&&b.key().equals("button_a")).findFirst().orElseThrow();
        assertEquals(((9.4-11.5)*2+8)/16,a.x(),1e-7);assertEquals(((.6255-.375)*2+8)/16,a.y(),1e-7);assertEquals(((1.7375-1.7225)*2+8)/16,a.z(),1e-7);
    }
    @Test void armPoseDoesNotConsumeOtherItemOrSpecialMovement(){assertTrue(SfcControllerPoseLayout.eligible(true,true,true,false,false,false,false));assertFalse(SfcControllerPoseLayout.eligible(true,false,true,false,false,false,false));assertFalse(SfcControllerPoseLayout.eligible(false,true,true,false,false,false,false));for(int i=0;i<4;i++)assertFalse(SfcControllerPoseLayout.eligible(true,true,true,i==0,i==1,i==2,i==3));assertFalse(SfcControllerPoseLayout.eligible(true,true,false,false,false,false,false));}
    @Test void rigIsClampedAndSymmetric(){assertEquals(SfcControllerPoseLayout.rig(0,0),SfcControllerPoseLayout.rig(-2,-2));assertEquals(SfcControllerPoseLayout.rig(1,1),SfcControllerPoseLayout.rig(2,2));var l=SfcControllerPoseLayout.arm(false);var r=SfcControllerPoseLayout.arm(true);assertEquals(-l.x(),r.x(),1e-12);assertEquals(l.y(),r.y(),1e-12);assertEquals(l.z(),r.z(),1e-12);assertEquals(-l.roll(),r.roll(),1e-12);}
    @Test void userAtlasNamespacesStaySeparate()throws Exception{var groups=parsed();for(String g:new String[]{"cartridge","inserted"})for(var p:groups.get(g))assertEquals("piq_sfc_home:textures/block/user_sfc_cartridge_20260911.png",p.texture());for(String g:new String[]{"body","controller","p1_docked","p2_docked"})for(var p:groups.get(g))assertEquals("piq_sfc_home:textures/block/user_sfc_20260911.png",p.texture());}
    @Test void exactUserMeshBudgetsAndNoDuplicateStaticCard()throws Exception{var groups=parsed();for(var e:java.util.Map.of("body",2402,"p1_docked",3628,"p2_docked",3628,"controller",3364,"cartridge",1012,"inserted",1012).entrySet())assertEquals(e.getValue().intValue(),groups.get(e.getKey()).stream().mapToInt(p->p.vertices().length/24).sum());assertFalse(groups.get("body").stream().anyMatch(p->p.name().contains("cartridge")));}
    @Test void independentCardGeometryIsTranslatedNotScaled()throws Exception{
        var g=parsed();var a=g.get("cartridge");var b=g.get("inserted");assertEquals(a.size(),b.size());
        for(int i=0;i<a.size();i++){assertEquals(a.get(i).name(),b.get(i).name());float[] x=a.get(i).vertices(),y=b.get(i).vertices();assertEquals(x.length,y.length);for(int v=0;v<x.length;v+=8){assertEquals(x[v],y[v]);assertEquals((2.18-6.55)/16,y[v+1]-x[v+1],1e-7);assertEquals(3.711/16,y[v+2]-x[v+2],1e-7);for(int k=3;k<8;k++)assertEquals(x[v+k],y[v+k],1e-8);}}
    }
    @Test void rejectsUnknownOrOversizedAnimationBinding()throws Exception{
        var a=asset();var p=firstPart(a);p.addProperty("motion","arbitrary");p.add("pivot",JsonParser.parseString("[8,8,8]"));p.addProperty("press",.07);rejects(a);
        p.addProperty("motion","button_a");p.addProperty("press",10);rejects(a);
    }
}
