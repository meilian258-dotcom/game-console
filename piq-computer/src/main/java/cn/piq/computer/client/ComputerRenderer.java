package cn.piq.computer.client;

import cn.piq.computer.*;
import cn.piq.computer.world.*;
import cn.piq.fcarcade.client.*;
import cn.piq.fcarcade.client.HomeHardwareRenderLayout.Point;
import cn.piq.fcarcade.home.*;
import cn.piq.fcarcade.world.FcArcadeBlock;
import com.mojang.blaze3d.vertex.*;
import com.mojang.math.Axis;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.blockentity.*;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.*;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.client.event.*;

public final class ComputerRenderer implements BlockEntityRenderer<ComputerEntity> {
    private static final ResourceLocation SOLID=ResourceLocation.fromNamespaceAndPath("piq_fc_arcade","textures/block/home_retro_tv_white.png");
    private static final Map<String,Cached> CACHE=new HashMap<>();
    private record Cached(BakedModel model,List<BakedQuad> quads){}
    private record CableKey(Object style,int pcTurns,int tvTurns,net.minecraft.core.BlockPos delta){}
    private record Cable(CableKey key,List<HomeAvCableMesh.Quad> quads){}
    private final Map<ComputerEntity,Cable> cables=new WeakHashMap<>();
    public ComputerRenderer(BlockEntityRendererProvider.Context ignored){}
    public static void register(EntityRenderersEvent.RegisterRenderers e){e.registerBlockEntityRenderer(ComputerRegistry.COMPUTER.get(),ComputerRenderer::new);}
    private static ModelResourceLocation model(String id){return ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath(ComputerMod.ID,"block/"+id));}
    public static void models(ModelEvent.RegisterAdditional e){for(var id:List.of("case_static","case_white_static","side_panel","side_panel_white","motherboard","cpu","cooler_static","ram","ram_2","gpu_static","hdd","psu","cables","wireless_receiver"))e.register(model(id));}
    public static int turns(BlockState s){return switch(s.getValue(ComputerBlock.FACING)){case EAST->1;case SOUTH->2;case WEST->3;default->0;};}
    @Override public void render(ComputerEntity pc,float partial,PoseStack poses,MultiBufferSource buffers,int light,int overlay){
        FirmwareDisplay.seen(pc);videoCable(pc,poses,buffers,light,overlay);
        int turns=turns(pc.getBlockState());boolean white=pc.getBlockState().is(ComputerRegistry.WHITE.get());
        poses.pushPose();try{
            poses.translate(.5,0,.5);poses.mulPose(Axis.YP.rotationDegrees(-90*turns));float scale=(float)ComputerGeometry.SCALE;poses.scale(scale,scale,scale);poses.translate(-.5,0,-.5);
            draw(white?"case_white_static":"case_static",poses,buffers,light,overlay);
            if(pc.peripheral(true)!=null||pc.peripheral(false)!=null)draw("wireless_receiver",poses,buffers,light,overlay);
            for(var part:Assembly.Part.values())if(Assembly.has(pc.installed,part))draw(part==Assembly.Part.COOLER||part==Assembly.Part.GPU?part.model+"_static":part.model,poses,buffers,light,overlay);
            double angle=pc.powered?((pc.getLevel().getGameTime()+partial)*.42)%(Math.PI*2):0;
            var fanOut=buffers.getBuffer(RenderType.entityCutoutNoCull(SOLID));
            fan(fanOut,poses.last(),new Vec3(6.975/16,13.55/16,15.29/16),new Vec3(1,0,0),new Vec3(0,1,0),2.32/16,angle,light,overlay);
            if(Assembly.has(pc.installed,Assembly.Part.COOLER))fan(fanOut,poses.last(),new Vec3(9.02/16,13/16,10.8/16),new Vec3(0,0,1),new Vec3(0,1,0),1.60/16,angle,light,overlay);
            if(Assembly.has(pc.installed,Assembly.Part.GPU))fan(fanOut,poses.last(),new Vec3(8.915/16,5.10/16,10.545/16),new Vec3(1,0,0),new Vec3(0,0,1),1.82/16,angle,light,overlay);
            if(Assembly.ready(pc.installed))draw("cables",poses,buffers,light,overlay);
            if(!pc.panelOpen)draw(white?"side_panel_white":"side_panel",poses,buffers,light,overlay);
            if(pc.powered){var out=buffers.getBuffer(RenderType.entityCutoutNoCull(SOLID));quad(out,poses.last(),new Vec3(11.79/16,14.1/16,.69/16),new Vec3(12.04/16,14.1/16,.69/16),new Vec3(12.04/16,14.52/16,.69/16),new Vec3(11.79/16,14.52/16,.69/16),new Vec3(0,0,-1),0x55ee88,0xf000f0,overlay);}
        }finally{poses.popPose();}
    }
    private static void draw(String id,PoseStack poses,MultiBufferSource buffers,int light,int overlay){
        var m=Minecraft.getInstance().getModelManager().getModel(model(id));var cached=CACHE.get(id);
        if(cached==null||cached.model!=m){var rand=RandomSource.create(42);var faces=new ArrayList<>(m.getQuads(null,null,rand));for(var d:Direction.values()){rand.setSeed(42);faces.addAll(m.getQuads(null,d,rand));}cached=new Cached(m,List.copyOf(faces));CACHE.put(id,cached);}
        var out=buffers.getBuffer(RenderType.entityCutoutNoCull(TextureAtlas.LOCATION_BLOCKS));for(var q:cached.quads)out.putBulkData(poses.last(),q,1,1,1,1,light,overlay);
    }
    private void videoCable(ComputerEntity pc,PoseStack poses,MultiBufferSource buffers,int light,int overlay){
        var l=pc.getLevel();var p=pc.televisionPos();if(l==null||p==null||!l.hasChunkAt(p)||!(l.getBlockEntity(p) instanceof HomeTvBlockEntity tv)||!HomeHardware.connected(l,pc,tv)||!(tv.getBlockState().getBlock() instanceof FcArcadeBlock block)||!UserTvLayout.supports(block.displayStyle())){cables.remove(pc);return;}
        var delta=p.subtract(pc.getBlockPos());int pt=turns(pc.getBlockState()),tt=turns(tv.getBlockState());var key=new CableKey(block.displayStyle(),pt,tt,delta);var cache=cables.get(pc);
        if(cache==null||!key.equals(cache.key)){
            cache=new Cable(key,ComputerHdmiMesh.build(pt,block.displayStyle(),tt,delta));cables.put(pc,cache);
        }
        var out=buffers.getBuffer(RenderType.entityCutoutNoCull(SOLID));for(var q:cache.quads)quad(out,poses.last(),v(q.a()),v(q.b()),v(q.c()),v(q.d()),v(q.normal()),q.color(),light,overlay);
    }
    private static Vec3 v(Point p){return new Vec3(p.x(),p.y(),p.z());}
    private static Vec3 ring(Vec3 center,Vec3 right,Vec3 up,double radius,double angle){return center.add(right.scale(Math.cos(angle)*radius)).add(up.scale(Math.sin(angle)*radius));}
    private static void fan(VertexConsumer out,PoseStack.Pose pose,Vec3 center,Vec3 right,Vec3 up,double radius,double angle,int light,int overlay){
        var normal=right.cross(up).normalize();
        for(int i=0;i<32;i++){double a=i*Math.PI/16,b=(i+1)*Math.PI/16;
            quad(out,pose,ring(center,right,up,radius,a),ring(center,right,up,radius,b),ring(center,right,up,radius*.92,b),ring(center,right,up,radius*.92,a),normal,0x454952,light,overlay);
            quad(out,pose,center,ring(center,right,up,radius*.2,a),ring(center,right,up,radius*.2,b),center,normal,0x717780,light,overlay);
        }
        for(int i=0;i<7;i++){double a=angle+i*Math.PI*2/7;
            quad(out,pose,ring(center,right,up,radius*.18,a),ring(center,right,up,radius*.88,a+.24),ring(center,right,up,radius*.78,a+.65),ring(center,right,up,radius*.23,a+.72),normal,0x555c65,light,overlay);
        }
    }
    private static void quad(VertexConsumer out,PoseStack.Pose pose,Vec3 a,Vec3 b,Vec3 c,Vec3 d,Vec3 n,int rgb,int light,int overlay){for(var v:List.of(a,b,c,d))out.addVertex(pose,(float)v.x,(float)v.y,(float)v.z).setColor((rgb>>16)&255,(rgb>>8)&255,rgb&255,255).setUv(.5f,.5f).setOverlay(overlay).setLight(light).setNormal(pose,(float)n.x,(float)n.y,(float)n.z);}
    @Override public boolean shouldRenderOffScreen(ComputerEntity pc){return true;}
    @Override public AABB getRenderBoundingBox(ComputerEntity pc){var box=new AABB(pc.getBlockPos()).expandTowards(0,1,0);var p=pc.televisionPos();return p!=null?box.minmax(new AABB(p)).inflate(1):box;}

}
