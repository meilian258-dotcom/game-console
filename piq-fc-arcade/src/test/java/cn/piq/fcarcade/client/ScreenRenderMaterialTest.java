package cn.piq.fcarcade.client;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ScreenRenderMaterialTest {
    @Test void everyMainDisplayUsesEmissiveMaterialWithoutChangingPowerOrScanlines()throws Exception{
        var root=Path.of("src/main/java/cn/piq/fcarcade/client");
        var material=Files.readString(root.resolve("ScreenRenderMaterial.java"));
        assertTrue(material.contains("setDepthTestState(LEQUAL_DEPTH_TEST)"));assertTrue(material.contains("NO_LIGHTMAP"));
        String vertex=Files.readString(Path.of("src/main/resources/assets/piq_fc_arcade/shaders/core/screen.vsh"));
        assertTrue(vertex.contains("vertexColor = Color;"));assertFalse(vertex.contains("minecraft_mix_light"));
        for(String file:List.of("ArcadeBlockScreenRenderer.java","HomeVideoDisplay.java","PrivateHomeClient.java","FcHomeWatchDisplay.java","cabinet/CabinetVideoDisplay.java"))
            assertTrue(Files.readString(root.resolve(file)).contains("ScreenRenderMaterial.of("),file);
        String renderer=Files.readString(root.resolve("ArcadeBlockScreenRenderer.java"));
        assertTrue(renderer.contains("TelevisionPowerTransition.brightness(amount)"));assertTrue(renderer.contains("tv.scanlinesEnabled()"));
        assertFalse(Files.readString(root.resolve("HomeHardwareRenderer.java")).contains("ScreenRenderMaterial.of("));
    }
}
