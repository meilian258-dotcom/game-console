package cn.piq.fcarcade.client;

import cn.piq.fcarcade.FcArcadeMod;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import java.util.List;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;

/** Color-lit solid geometry, not GL line width (which varies with GPU/driver). */
final class HomeAvCableRenderer {
    private static final ResourceLocation WHITE = ResourceLocation.fromNamespaceAndPath(FcArcadeMod.MOD_ID, "dynamic/av_solid_white");
    private static boolean ready;
    private HomeAvCableRenderer() {}
    static void registerReload(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener) manager -> ready = false);
    }

    private static void texture() {
        if (ready) return;
        NativeImage image = new NativeImage(1, 1, false);
        image.setPixelRGBA(0, 0, -1);
        Minecraft.getInstance().getTextureManager().register(WHITE, new DynamicTexture(image));
        ready = true;
    }

    static void draw(List<HomeAvCableMesh.Quad> quads, PoseStack.Pose pose, MultiBufferSource buffers, int light, int overlay) {
        if (quads.isEmpty()) return;
        texture();
        var target = buffers.getBuffer(RenderType.entityCutoutNoCull(WHITE));
        for (var quad : quads) {
            int color = quad.color();
            var normal = quad.normal();
            vertex(target,pose,quad.a(),normal,color,light,overlay);
            vertex(target,pose,quad.b(),normal,color,light,overlay);
            vertex(target,pose,quad.c(),normal,color,light,overlay);
            vertex(target,pose,quad.d(),normal,color,light,overlay);
        }
    }
    private static void vertex(VertexConsumer target, PoseStack.Pose pose, HomeHardwareRenderLayout.Point point,
                               HomeHardwareRenderLayout.Point normal, int color, int light, int overlay) {
        target.addVertex(pose, (float)point.x(), (float)point.y(), (float)point.z())
                .setColor((color >> 16)&255, (color >> 8)&255, color&255, 255)
                .setUv(.5f, .5f).setOverlay(overlay).setLight(light)
                .setNormal(pose, (float)normal.x(), (float)normal.y(), (float)normal.z());
    }
}
