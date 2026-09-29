package cn.piq.sfchome.client;

import cn.piq.sfchome.registry.SfcHomeRegistries;
import cn.piq.sfchome.world.SfcHomeConsoleBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ModelEvent;

/** Client-only native SFC meshes; no linkage from server registry or common startup. */
@EventBusSubscriber(modid = "piq_sfc_home", bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class SfcHardwareRenderer implements BlockEntityRenderer<SfcHomeConsoleBlockEntity> {
    private static final String[] MESH_GROUPS={"body","p1_docked","p2_docked","slot_cover","inserted"};
    private final SfcAvCableRenderer avCable = new SfcAvCableRenderer();

    public SfcHardwareRenderer(BlockEntityRendererProvider.Context context) {}

    private static ModelResourceLocation[] createModels() {
        var result = new ModelResourceLocation[SfcModelPresentation.MODEL_COUNT];
        for (int i = 0; i < result.length; i++) result[i] = ModelResourceLocation.standalone(
                ResourceLocation.fromNamespaceAndPath("piq_sfc_home", SfcModelPresentation.path(i)));
        return result;
    }

    @SubscribeEvent
    public static void registerModels(ModelEvent.RegisterAdditional event) {
        // Historical vanilla models remain available to resource packs; live geometry uses the free mesh.
        for (var model : createModels()) event.register(model);
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(SfcHomeRegistries.CONSOLE_ENTITY.get(), SfcHardwareRenderer::new);
    }

    @Override
    public void render(SfcHomeConsoleBlockEntity console, float partialTick, PoseStack poses,
                       MultiBufferSource buffers, int light, int overlay) {
        Direction facing = console.getBlockState().getValue(BlockStateProperties.HORIZONTAL_FACING);
        int turns = switch (facing) { case EAST -> 1; case SOUTH -> 2; case WEST -> 3; default -> 0; };
        boolean p1 = console.controllerDocked(0), p2 = console.controllerDocked(1), card = console.hasCartridge();
        poses.pushPose();
        try {
            poses.translate(.5, 0, .5);
            poses.mulPose(Axis.YP.rotationDegrees(SfcModelPresentation.yawDegrees(turns)));
            poses.translate(-.5, 0, -.5);
            // Block is ENTITYBLOCK_ANIMATED: base is drawn here exactly once, not baked again.
            // Docked pieces and inserted card already use block-local coordinates: no second scale.
            for (int i = 0; i < MESH_GROUPS.length; i++) {
                if (SfcModelPresentation.visible(i, p1, p2, card)) SfcHardwareMesh.draw(MESH_GROUPS[i], poses, buffers, light, overlay);
            }
            if (console.visualPowered()) {
                // Exact original console_print red lens, including the user's sloping front surface.
                var a = cn.piq.sfchome.layout.SfcConsoleScale.console(10.75,1.67234,6.58952);
                var b = cn.piq.sfchome.layout.SfcConsoleScale.console(10.955,1.81729,6.73448);
                cn.piq.fcarcade.client.PowerIndicatorRenderer.face(poses,buffers,light,overlay,true,
                        a.x()/16,a.y()/16,a.z()/16,b.x()/16,b.y()/16,b.z()/16);
            }
            if(card){
                poses.pushPose();
                try{
                    poses.translate(cn.piq.sfchome.layout.SfcConsoleScale.PIVOT_X/16,0,cn.piq.sfchome.layout.SfcConsoleScale.PIVOT_Z/16);
                    poses.scale(1.5f,1.5f,1.5f);
                    poses.translate(-cn.piq.sfchome.layout.SfcConsoleScale.PIVOT_X/16,0,-cn.piq.sfchome.layout.SfcConsoleScale.PIVOT_Z/16);
                    SfcCartridgeRenderer.label(console.insertedCartridge(),console.getBlockPos(),true,poses,buffers,light,overlay);
                }finally{poses.popPose();}
            }
        } finally { poses.popPose(); }
        // Cable coordinates already include both device facings in console-local world axes.
        avCable.render(console, poses.last(), buffers, light, overlay);
        SfcControllerCableRenderer.render(console, partialTick, poses, buffers, light, overlay);
    }

    @Override public boolean shouldRenderOffScreen(SfcHomeConsoleBlockEntity console) { return true; }

    @Override public net.minecraft.world.phys.AABB getRenderBoundingBox(SfcHomeConsoleBlockEntity console) {
        return avCable.bounds(console).minmax(SfcControllerCableRenderer.bounds(console));
    }

}
