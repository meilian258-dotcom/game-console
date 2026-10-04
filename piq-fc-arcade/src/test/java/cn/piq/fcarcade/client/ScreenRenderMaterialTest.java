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
    @Test void irisCompatibilityOnlyChangesTheShaderNotTheWorldMaterialContract()throws Exception{
        String material=Files.readString(Path.of("src/main/java/cn/piq/fcarcade/client/ScreenRenderMaterial.java"));
        assertTrue(material.contains("ScreenShaderCompatibility.discover()"));
        assertTrue(material.contains("new ShaderStateShard(ScreenRenderMaterial::activeShader)"));
        assertTrue(material.contains("SHADER_PACK.getAsBoolean() ? GameRenderer.getRendertypeEyesShader() : shader"));
        assertTrue(material.contains("DefaultVertexFormat.NEW_ENTITY,VertexFormat.Mode.QUADS"));
        assertTrue(material.contains("setTransparencyState(NO_TRANSPARENCY)"));
        assertTrue(material.contains("setCullState(NO_CULL)"));
        assertTrue(material.contains("setDepthTestState(LEQUAL_DEPTH_TEST).setWriteMaskState(COLOR_DEPTH_WRITE)"));
        assertTrue(material.contains("setLightmapState(NO_LIGHTMAP).setOverlayState(NO_OVERLAY)"));
        assertFalse(material.contains("RenderType.eyes("));
        assertFalse(material.contains("setOutputState("));
        assertFalse(material.contains("import net.irisshaders"));
        for(String file:List.of("ArcadeBlockScreenRenderer.java","HomeVideoDisplay.java","cabinet/CabinetVideoDisplay.java")){
            String renderer=Files.readString(Path.of("src/main/java/cn/piq/fcarcade/client",file));
            assertTrue(renderer.contains("RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES"),file);
            assertTrue(renderer.contains("endBatch("),file);
        }
    }
}
