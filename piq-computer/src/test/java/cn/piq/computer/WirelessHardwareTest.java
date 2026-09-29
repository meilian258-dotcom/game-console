package cn.piq.computer;

import com.google.gson.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WirelessHardwareTest {
    private static final Path RES=Path.of("src/main/resources");
    private JsonObject model(String name)throws Exception{return JsonParser.parseString(Files.readString(RES.resolve("assets/piq_computer/models/block/"+name+".json"))).getAsJsonObject();}
    @Test void mouseIsOnViewerRightAndNoPartCrossesTheOneBlockFootprint()throws Exception{
        for(String color:new String[]{"black","white"}){
            var keyboard=model("keyboard_body_"+color).getAsJsonArray("elements");
            var all=model("keyboard_mouse_"+color).getAsJsonArray("elements");
            double keyboardMin=16,mouseMax=0;
            for(int i=0;i<all.size();i++){
                var e=all.get(i).getAsJsonObject();double lo=e.getAsJsonArray("from").get(0).getAsDouble(),hi=e.getAsJsonArray("to").get(0).getAsDouble();
                assertTrue(lo>=0&&hi<=16);
                if(i<keyboard.size())keyboardMin=Math.min(keyboardMin,lo);else mouseMax=Math.max(mouseMax,hi);
            }
            assertTrue(mouseMax<keyboardMin,"Front is -Z: viewer right is -X, not +X");
        }
    }
    @Test void oldIdsRemainButCannotBeCraftedAndNewKitNeedsNoRetiredItems()throws Exception{
        for(String color:new String[]{"black","white"}){
            for(String part:new String[]{"keyboard","mouse"}){
                assertTrue(ComputerRegistry.legacyPeripheral(part+"_"+color));
                assertFalse(Files.exists(RES.resolve("data/piq_computer/recipe/"+part+"_"+color+".json")));
                assertTrue(Files.exists(RES.resolve("assets/piq_computer/blockstates/"+part+"_"+color+".json")));
            }
            String direct=Files.readString(RES.resolve("data/piq_computer/recipe/keyboard_mouse_"+color+".json"));
            var recipe=JsonParser.parseString(direct).getAsJsonObject();
            for(var i:recipe.getAsJsonArray("ingredients"))assertTrue(i.getAsJsonObject().get("item").getAsString().startsWith("minecraft:"));
            assertTrue(Files.exists(RES.resolve("data/piq_computer/recipe/keyboard_mouse_"+color+"_legacy.json")));
        }
    }
    @Test void receiverStraddlesRealFrontPortAndUsesCommonCaseTransform()throws Exception{
        var parts=model("wireless_receiver").getAsJsonArray("elements");assertEquals(2,parts.size());
        var usb=parts.get(0).getAsJsonObject();var lo=usb.getAsJsonArray("from");var hi=usb.getAsJsonArray("to");
        assertTrue(lo.get(0).getAsDouble()<5&&hi.get(0).getAsDouble()>5);
        assertTrue(lo.get(1).getAsDouble()<16.55&&hi.get(1).getAsDouble()>16.55);
        assertTrue(lo.get(2).getAsDouble()<.62&&hi.get(2).getAsDouble()>.62);
        var source=Files.readString(Path.of("src/main/java/cn/piq/computer/client/ComputerRenderer.java"));
        assertFalse(source.contains("Peripherals"));assertFalse(source.contains("private void wire("));
        assertTrue(source.contains("pc.peripheral(true)!=null||pc.peripheral(false)!=null"));
        assertTrue(source.contains("videoCable(pc,poses,buffers,light,overlay)"));
    }
}
