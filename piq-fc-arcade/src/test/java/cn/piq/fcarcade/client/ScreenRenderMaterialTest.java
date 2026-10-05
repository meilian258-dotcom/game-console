package cn.piq.fcarcade.client;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ScreenRenderMaterialTest {
    // Source-wiring guards only; these cannot prove an Iris shader's final GPU blend state.
    @Test void everyMainDisplayUsesSharedMaterialWithoutChangingPowerOrScanlines()throws Exception{
        var root=Path.of("src/main/java/cn/piq/fcarcade/client");
        var material=Files.readString(root.resolve("ScreenRenderMaterial.java"));
        assertTrue(material.contains("setDepthTestState(LEQUAL_DEPTH_TEST)"));
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
        assertTrue(material.contains("SHADER_PACK.getAsBoolean() ? GameRenderer.getRendertypeEntityCutoutNoCullShader() : shader"));
        assertTrue(material.contains("DefaultVertexFormat.NEW_ENTITY,VertexFormat.Mode.QUADS"));
        assertTrue(material.contains("setTransparencyState(NO_TRANSPARENCY)"));
        assertTrue(material.contains("setCullState(NO_CULL)"));
        assertTrue(material.contains("setDepthTestState(LEQUAL_DEPTH_TEST).setWriteMaskState(COLOR_DEPTH_WRITE)"));
        assertTrue(material.contains("setLightmapState(LIGHTMAP).setOverlayState(OVERLAY)"));
        assertFalse(material.contains("GameRenderer.getRendertypeEyesShader()"));
        assertFalse(material.contains("GameRenderer.getRendertypeEntityTranslucentEmissiveShader()"));
        assertFalse(material.contains("RenderType.eyes("));
        assertFalse(material.contains("setOutputState("));
        assertFalse(material.contains("import net.irisshaders"));
        for(String file:List.of("ArcadeBlockScreenRenderer.java","HomeVideoDisplay.java","cabinet/CabinetVideoDisplay.java")){
            String renderer=Files.readString(Path.of("src/main/java/cn/piq/fcarcade/client",file));
            assertTrue(renderer.contains("RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES"),file);
            assertTrue(renderer.contains("endBatch("),file);
        }
    }

    @Test void opaqueEntityFallbackReceivesBrightLightAndNeutralOverlay()throws Exception{
        for(String file:List.of("ArcadeBlockScreenRenderer.java","cabinet/CabinetVideoDisplay.java")){
            String renderer=Files.readString(Path.of("src/main/java/cn/piq/fcarcade/client",file));
            assertTrue(renderer.contains("LightTexture.FULL_BRIGHT"),file);
            assertTrue(renderer.contains("OverlayTexture.NO_OVERLAY"),file);
        }
    }
}
