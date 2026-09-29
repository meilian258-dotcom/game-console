package cn.piq.fcarcade.client;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LegacyControlsResourceTest {
    private JsonObject resource(String name)throws Exception{
        try(var in=getClass().getResourceAsStream("/assets/piq_fc_arcade/models/block/"+name+".json")){
            assertNotNull(in);return JsonParser.parseString(new String(in.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
    @Test void splitPartsExactlyPartitionOriginalWithoutChangingUvOrGeometry()throws Exception{
        var original=resource("rocket_arcade_body");var expected=new ArrayList<JsonElement>();original.getAsJsonArray("elements").forEach(expected::add);
        assertEquals(155,expected.size());int controls=0;
        for(String name:List.of("body","joystick","start","button_1","button_2","button_3","button_4","button_5","button_6")){
            var part=resource("legacy_animated/"+name);assertEquals(original.get("textures"),part.get("textures"));
            for(var e:part.getAsJsonArray("elements")){assertTrue(expected.remove(e),"duplicate, altered or unexpected element in "+name);if(!name.equals("body"))controls++;}
        }
        assertTrue(expected.isEmpty());assertEquals(61,controls);
    }
    @Test void allEightControlsRegisteredAndAnimatedEvenWithoutCustomSkin()throws Exception{
        var source=java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/cn/piq/fcarcade/client/LegacyArcadeSkinRenderer.java"));
        for(String name:List.of("joystick","start","button_1","button_2","button_3","button_4","button_5","button_6"))assertTrue(source.contains("\""+name+"\""));
        assertTrue(source.contains("DualCabinetControls.motion"));assertTrue(source.contains("ModelEvent.RegisterAdditional"));
        assertTrue(source.indexOf("renderControls(machine,")>=0&&source.indexOf("renderControls(machine,")<source.indexOf("if (texture == null)"));
    }
}
