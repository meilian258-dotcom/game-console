package cn.piq.fcarcade.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderType;

/** Opaque, depth-tested colored quads. The red ON paddle stays readable at night;
 * it is an indicator, not a world light source or a see-through debug box. */
final class CabinetPowerMaterial extends RenderType {
    static final RenderType SOLID=create("piq_power_rocker",DefaultVertexFormat.POSITION_COLOR,
            VertexFormat.Mode.QUADS,8192,false,false,CompositeState.builder()
                    .setShaderState(new ShaderStateShard(GameRenderer::getPositionColorShader))
                    .setTransparencyState(NO_TRANSPARENCY).setCullState(NO_CULL)
                    .setDepthTestState(LEQUAL_DEPTH_TEST).setWriteMaskState(COLOR_DEPTH_WRITE)
                    .createCompositeState(false));
    private CabinetPowerMaterial(String name,VertexFormat format,VertexFormat.Mode mode,int size,
                                 boolean crumbling,boolean sorted,Runnable setup,Runnable clear){
        super(name,format,mode,size,crumbling,sorted,setup,clear);
    }
}
