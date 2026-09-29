// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfchome.client;

import com.mojang.blaze3d.vertex.PoseStack;
import cn.piq.sfchome.layout.SfcConsoleScale;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import java.io.Reader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Same free-triangle/raw-UV approach as the FC Subor mesh, in an isolated SFC resource namespace. */
@EventBusSubscriber(modid="piq_sfc_home",bus=EventBusSubscriber.Bus.MOD,value=Dist.CLIENT)
public final class SfcHardwareMesh {
    private static final ResourceLocation MODEL=ResourceLocation.fromNamespaceAndPath("piq_sfc_home","meshes/sfc_hardware.json");
    private record Part(RenderType material,float[] vertices,SfcButtonAnimation.Binding binding){}
    private static volatile Map<String,List<Part>> meshes=Map.of();
    private SfcHardwareMesh(){}
    @SubscribeEvent public static void reloadListener(RegisterClientReloadListenersEvent event){event.registerReloadListener((ResourceManagerReloadListener)SfcHardwareMesh::reload);}
    private static void reload(ResourceManager manager){
        try(Reader reader=manager.openAsReader(MODEL)){
            var loaded=SfcHardwareMeshData.read(reader);var next=new HashMap<String,List<Part>>();
            for(var group:loaded.entrySet())next.put(group.getKey(),group.getValue().stream().map(p->
                    new Part(RenderType.entityCutoutNoCull(ResourceLocation.parse(p.texture())),SfcConsoleScale.vertices(group.getKey(),p.name(),p.vertices()),binding(group.getKey(),p.name(),p.binding()))).toList());
            meshes=Map.copyOf(next);
        }catch(Exception error){meshes=Map.of();com.mojang.logging.LogUtils.getLogger().error("Cannot reload SFC hardware mesh",error);}
    }
    private static SfcButtonAnimation.Binding binding(String group,String name,SfcButtonAnimation.Binding original){
        if(original==null||group.equals("controller"))return original;
        var point=SfcConsoleScale.part(group,name,original.x()*16,original.y()*16,original.z()*16);
        return new SfcButtonAnimation.Binding(original.key(),(float)(point.x()/16),(float)(point.y()/16),(float)(point.z()/16),original.press());
    }
    static void draw(String name,PoseStack poses,MultiBufferSource buffers,int light,int overlay){
        draw(name,poses,buffers,light,overlay,0);
    }
    static void draw(String name,PoseStack poses,MultiBufferSource buffers,int light,int overlay,int inputMask){
        List<Part> parts=meshes.get(name);if(parts==null)return;
        for(Part part:parts){var target=buffers.getBuffer(part.material());float[] data=part.vertices();
            var transform=SfcButtonAnimation.sample(part.binding(),inputMask);boolean moved=!transform.equals(SfcButtonAnimation.Transform.REST);
            if(moved){
                var b=part.binding();poses.pushPose();poses.translate(b.x(),b.y()+transform.y(),b.z());
                poses.mulPose(Axis.XP.rotationDegrees(transform.pitch()));poses.mulPose(Axis.ZP.rotationDegrees(transform.roll()));
                poses.translate(-b.x(),-b.y(),-b.z());
            }
            var pose=poses.last();
            try{
            for(int triangle=0;triangle<data.length;triangle+=24)for(int vertex=0;vertex<4;vertex++){
                // QUADS buffer: repeat the last vertex, retaining the original triangle's UV and normal.
                int at=triangle+Math.min(vertex,2)*8;
                target.addVertex(pose,data[at],data[at+1],data[at+2]).setColor(255,255,255,255)
                        .setUv(data[at+3],data[at+4]).setOverlay(overlay).setLight(light).setNormal(pose,data[at+5],data[at+6],data[at+7]);
            }
            }finally{if(moved)poses.popPose();}
        }
    }
}
