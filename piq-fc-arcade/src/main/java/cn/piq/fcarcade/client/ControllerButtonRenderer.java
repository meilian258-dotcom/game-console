package cn.piq.fcarcade.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import java.util.ArrayList;
import java.util.List;

/** Only held controllers. Existing UVs, vertices and model identities survive unchanged. */
final class ControllerButtonRenderer {
    private record Partition(List<BakedQuad> source,List<List<BakedQuad>> parts) {}
    private static final ResourceLocation TEXTURE=ResourceLocation.fromNamespaceAndPath("piq_fc_arcade","textures/block/home_famicom_console.png");
    private static final Partition[] FAMICOM=new Partition[2];
    private ControllerButtonRenderer() {}

    /** Called AFTER the existing heldControllerYaw transform. Two identity caches only. */
    static void drawFamicom(int port,List<BakedQuad> quads,ItemStack stack,ItemDisplayContext context,
                           PoseStack poses,MultiBufferSource buffers,int light,int overlay) {
        Partition partition=FAMICOM[port];
        if(partition==null||partition.source()!=quads) {
            var parts=new ArrayList<List<BakedQuad>>(ControllerButtonGeometry.PARTS);
            for(int i=0;i<ControllerButtonGeometry.PARTS;i++)parts.add(new ArrayList<>());
            for(BakedQuad quad:quads) {
                int[] data=quad.getVertices();int stride=data.length/4;
                var points=new ControllerButtonGeometry.Point[4];
                for(int i=0;i<4;i++)points[i]=new ControllerButtonGeometry.Point(
                        Float.intBitsToFloat(data[i*stride])*16,Float.intBitsToFloat(data[i*stride+1])*16,Float.intBitsToFloat(data[i*stride+2])*16);
                parts.get(ControllerButtonGeometry.classify(points,false,port)).add(quad);
            }
            partition=new Partition(quads,parts.stream().map(List::copyOf).toList());FAMICOM[port]=partition;
        }
        var animation=ClientControllerAnimation.state(stack,context);
        VertexConsumer target=buffers.getBuffer(RenderType.entityCutoutNoCull(TEXTURE));
        for(int part=0;part<ControllerButtonGeometry.PARTS;part++) {
            poses.pushPose();
            try {
                if(moving(part,animation)) {
                    // Undo only the local port orientation, animate in canonical
                    // +Z-front coordinates, then restore it before raw baked vertices.
                    float yaw=port==0?-90:90;
                    poses.translate(.5,.5,.5);poses.mulPose(Axis.YP.rotationDegrees(-yaw));poses.translate(-.5,-.5,-.5);
                    apply(part,animation,false,poses);
                    poses.translate(.5,.5,.5);poses.mulPose(Axis.YP.rotationDegrees(yaw));poses.translate(-.5,-.5,-.5);
                }
                HomeHardwareRenderer.drawQuads(partition.parts().get(part),target,poses.last(),light,overlay);
            } finally { poses.popPose(); }
        }
    }
    static boolean moving(int part,ControllerButtonAnimation animation) {
        return part==ControllerButtonGeometry.DPAD?animation.pitch()!=0||animation.yaw()!=0
                :ControllerButtonGeometry.mask(part)!=0&&animation.value(ControllerButtonGeometry.mask(part))!=0;
    }
    static void apply(int part,ControllerButtonAnimation animation,boolean subor,PoseStack poses) {
        if(part==ControllerButtonGeometry.DPAD) {
            var p=ControllerButtonGeometry.pivot(subor);
            poses.translate(p.x()/16,p.y()/16,p.z()/16);
            poses.mulPose(Axis.XP.rotationDegrees((float)animation.pitch()));
            poses.mulPose(Axis.YP.rotationDegrees((float)animation.yaw()));
            poses.translate(-p.x()/16,-p.y()/16,-p.z()/16);
        } else if(ControllerButtonGeometry.mask(part)!=0) {
            poses.translate(0,0,-ControllerButtonGeometry.travel(subor,part)*animation.value(ControllerButtonGeometry.mask(part))/16);
        }
    }
    /** Called once on SB held-mesh resource reload, not per-frame. */
    static float[][] partitionSubor(float[] data) {
        var parts=new ArrayList<List<Float>>(ControllerButtonGeometry.PARTS);
        for(int i=0;i<ControllerButtonGeometry.PARTS;i++)parts.add(new ArrayList<>());
        for(int offset=0;offset<data.length;offset+=24) {
            var points=new ControllerButtonGeometry.Point[3];
            for(int i=0;i<3;i++)points[i]=new ControllerButtonGeometry.Point(data[offset+i*8]*16,data[offset+i*8+1]*16,data[offset+i*8+2]*16);
            var target=parts.get(ControllerButtonGeometry.classify(points,true,0));
            for(int i=0;i<24;i++)target.add(data[offset+i]);
        }
        float[][] result=new float[ControllerButtonGeometry.PARTS][];
        for(int p=0;p<result.length;p++) { result[p]=new float[parts.get(p).size()];for(int i=0;i<result[p].length;i++)result[p][i]=parts.get(p).get(i); }
        return result;
    }
}
