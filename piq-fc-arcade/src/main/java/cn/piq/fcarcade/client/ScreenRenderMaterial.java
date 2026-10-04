package cn.piq.fcarcade.client;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import java.io.IOException;
import java.util.function.Function;
import java.util.function.BooleanSupplier;

/** Pixels emit their own light: no entity directional shading or world lightmap.
 * Keep vanilla depth testing/fog, the screen power envelope and CRT scanlines.
 * This material is for display quads only, never the casing or controllers.
 */
@EventBusSubscriber(modid="piq_fc_arcade",bus=EventBusSubscriber.Bus.MOD,value=Dist.CLIENT)
public final class ScreenRenderMaterial extends RenderType {
    private static ShaderInstance shader;
    private static final BooleanSupplier SHADER_PACK = ScreenShaderCompatibility.discover();
    private static final Function<ResourceLocation,RenderType> TYPES=net.minecraft.Util.memoize(texture ->
        create("piq_screen",DefaultVertexFormat.NEW_ENTITY,VertexFormat.Mode.QUADS,1536,false,false,
            CompositeState.builder().setShaderState(new ShaderStateShard(ScreenRenderMaterial::activeShader))
                .setTextureState(new TextureStateShard(texture,false,false))
                .setTransparencyState(NO_TRANSPARENCY).setCullState(NO_CULL)
                .setLightmapState(NO_LIGHTMAP).setOverlayState(NO_OVERLAY)
                .setDepthTestState(LEQUAL_DEPTH_TEST).setWriteMaskState(COLOR_DEPTH_WRITE)
                .createCompositeState(false)));
    private ScreenRenderMaterial(String name,VertexFormat format,VertexFormat.Mode mode,int size,boolean crumbling,boolean sorted,Runnable setup,Runnable clear) {
        super(name,format,mode,size,crumbling,sorted,setup,clear);
    }
    @SubscribeEvent public static void shaders(RegisterShadersEvent event)throws IOException {
        event.registerShader(new ShaderInstance(event.getResourceProvider(),
                ResourceLocation.fromNamespaceAndPath("piq_fc_arcade","screen"),DefaultVertexFormat.NEW_ENTITY),value->shader=value);
    }
    private static ShaderInstance activeShader() {
        // Iris replaces this vanilla NEW_ENTITY shader with its fullbright world program.
        // Keep our opaque/depth-tested material: RenderType.eyes itself uses additive blending.
        // The custom shader remains unchanged when Iris is absent or its shader pack is off.
        return SHADER_PACK.getAsBoolean() ? GameRenderer.getRendertypeEyesShader() : shader;
    }
    public static RenderType of(ResourceLocation texture) {
        return TYPES.apply(texture);
    }
}
