package cn.piq.fcarcade.home;

import com.google.gson.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UserTvResourcesTest {
    private static final List<String> TVS=List.of("gray_crt_tv","red_crt_tv","panel_tv_2","panel_tv_2_wall","panel_tv_3","panel_tv_3_wall");
    private JsonObject json(String path) throws Exception {
        try(var stream=getClass().getResourceAsStream(path)){
            assertNotNull(stream,path);
            return JsonParser.parseReader(new InputStreamReader(stream,StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
    @Test void sixExportedModelsUseLegalVanillaElementsAndExistingTextures() throws Exception {
        for(String name:TVS){
            var model=json("/assets/piq_fc_arcade/models/block/user_tv48/"+name+".json");
            var textures=model.getAsJsonObject("textures");
            for(var entry:textures.entrySet()){
                String texture=entry.getValue().getAsString();
                if(texture.startsWith("#")){assertTrue(textures.has(texture.substring(1)));continue;}
                assertTrue(texture.startsWith("piq_fc_arcade:"));
                try(var in=getClass().getResourceAsStream("/assets/piq_fc_arcade/textures/"+texture.substring(texture.indexOf(':')+1)+".png")){
                    assertNotNull(in,texture);assertTrue(in.readAllBytes().length>8);
                }
            }
            for(var element:model.getAsJsonArray("elements")){
                var cube=element.getAsJsonObject();
                for(String edge:List.of("from","to"))for(var n:cube.getAsJsonArray(edge)){
                    double v=n.getAsDouble();assertTrue(Double.isFinite(v)&&v>=-16&&v<=32,name+" "+cube.get("name"));
                }
                if(cube.has("rotation"))assertTrue(List.of(-45D,-22.5D,0D,22.5D,45D).contains(cube.getAsJsonObject("rotation").get("angle").getAsDouble()));
                for(var face:cube.getAsJsonObject("faces").entrySet()){
                    var f=face.getValue().getAsJsonObject();
                    assertTrue(textures.has(f.get("texture").getAsString().substring(1)));
                    for(var uv:f.getAsJsonArray("uv"))assertTrue(uv.getAsDouble()>=0&&uv.getAsDouble()<=16);
                }
            }
            var states=json("/assets/piq_fc_arcade/blockstates/"+name+".json").getAsJsonObject("variants");
            assertEquals(4,states.size());
            for(var entry:states.entrySet())assertEquals("piq_fc_arcade:block/user_tv48/"+name,entry.getValue().getAsJsonObject().get("model").getAsString());
        }
    }
    @Test void newObtainableItemsHaveBothNamesAndModels() throws Exception {
        for(String language:List.of("en_us","zh_cn")){
            var lang=json("/assets/piq_fc_arcade/lang/"+language+".json");
            for(String name:List.of("gray_crt_tv","red_crt_tv","panel_tv_2","panel_tv_3","arcade_coin")){
                assertTrue(lang.has((name.equals("arcade_coin")?"item":"block")+".piq_fc_arcade."+name));
                assertFalse(json("/assets/piq_fc_arcade/models/item/"+name+".json").isEmpty());
            }
        }
    }
    @Test void singleCellCrtLootDropsOnlyItsOwnItem() throws Exception {
        for(String name:List.of("gray_crt_tv","red_crt_tv")){
            var loot=json("/data/piq_fc_arcade/loot_table/blocks/"+name+".json");
            var pools=loot.getAsJsonArray("pools");assertEquals(1,pools.size());
            var entries=pools.get(0).getAsJsonObject().getAsJsonArray("entries");assertEquals(1,entries.size());
            assertEquals("piq_fc_arcade:"+name,entries.get(0).getAsJsonObject().get("name").getAsString());
        }
    }
    @Test void twoWidePanelIsCentredInItemsWithoutMovingItsWorldModel() throws Exception {
        var item=json("/assets/piq_fc_arcade/models/item/panel_tv_2.json");
        assertEquals("piq_fc_arcade:block/user_tv48/panel_tv_2",item.get("parent").getAsString());
        var display=item.getAsJsonObject("display");
        String[] contexts={"gui","firstperson_righthand","firstperson_lefthand","thirdperson_righthand","thirdperson_lefthand"};
        double[][] expected={{2.097029,-1.380038,1.418323},{-2.424871,1,1.4},{2.424871,1,-1.4},{-2,2,0},{2,2,0}};
        for(int i=0;i<contexts.length;i++)for(int axis=0;axis<3;axis++)
            assertEquals(expected[i][axis],display.getAsJsonObject(contexts[i]).getAsJsonArray("translation").get(axis).getAsDouble(),1e-6);
        var model=json("/assets/piq_fc_arcade/models/block/user_tv48/panel_tv_2.json");
        double min=Double.POSITIVE_INFINITY,max=Double.NEGATIVE_INFINITY;
        for(var element:model.getAsJsonArray("elements")){
            min=Math.min(min,element.getAsJsonObject().getAsJsonArray("from").get(0).getAsDouble());
            max=Math.max(max,element.getAsJsonObject().getAsJsonArray("to").get(0).getAsDouble());
        }
        assertEquals(1,min,1e-6);assertEquals(31,max,1e-6);
    }
    @Test void mixedTvFamiliesDoNotThrashASingleFacingCache() throws Exception {
        String renderer=Files.readString(Path.of("src/main/java/cn/piq/fcarcade/client/HomeHardwareRenderer.java"));
        String tv=renderer.substring(renderer.indexOf("private static final class TvRenderer"));
        assertTrue(tv.contains("models.get(baked)"));assertTrue(tv.contains("models.size() >= 64"));
        assertFalse(tv.contains("models[facing]"));
    }
}
