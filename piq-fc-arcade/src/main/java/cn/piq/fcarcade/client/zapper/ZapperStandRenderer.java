package cn.piq.fcarcade.client.zapper;

import cn.piq.fcarcade.home.*;
import cn.piq.fcarcade.layout.ZapperStandGeometry;
import cn.piq.fcarcade.layout.RocketArcadeGeometry.Point;
import cn.piq.fcarcade.registry.ModBlockEntities;
import com.mojang.blaze3d.vertex.*;
import com.mojang.math.Axis;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.blockentity.*;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.*;
import net.minecraft.core.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.phys.*;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.*;

/** User's exact stand and docked gun. The borrowed gun is never drawn a second time here. */
public final class ZapperStandRenderer implements BlockEntityRenderer<ZapperStandBlockEntity> {
    private static final ModelResourceLocation STAND=model("stand"),CABLE=model("cable"),PLUG=model("connector"),BODY=gun("body"),TRIGGER=gun("trigger");
    private static final Map<ModelResourceLocation,Cache> CACHE=new HashMap<>();
    private record Cache(BakedModel source,List<BakedQuad> quads){}
    private static ModelResourceLocation model(String name){return ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath("piq_fc_arcade","block/zapper_stand/"+name));}
    private static ModelResourceLocation gun(String name){return ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath("piq_fc_arcade","item/zapper/"+name));}
    public ZapperStandRenderer(BlockEntityRendererProvider.Context ignored){}
    public static void register(IEventBus bus){bus.addListener(ZapperStandRenderer::models);bus.addListener(ZapperStandRenderer::renderers);}
    private static void models(ModelEvent.RegisterAdditional e){for(var model:List.of(STAND,CABLE,PLUG,BODY,TRIGGER))e.register(model);}
    private static void renderers(EntityRenderersEvent.RegisterRenderers e){e.registerBlockEntityRenderer(ModBlockEntities.ZAPPER_STAND.get(),ZapperStandRenderer::new);}
    @Override public boolean shouldRenderOffScreen(ZapperStandBlockEntity b){return true;}
    @Override public AABB getRenderBoundingBox(ZapperStandBlockEntity b){return new AABB(b.getBlockPos()).inflate(17);}
    @Override public void render(ZapperStandBlockEntity b,float partial,PoseStack poses,MultiBufferSource buffers,int light,int overlay){
        if(b.getLevel()==null)return;int turns=ZapperStandBlock.turns(b.getBlockState());
        HomeConsoleBlockEntity console=null;var link=b.link();
        if(link!=null&&link.stand().id().equals(b.identity())&&link.console().dimension().equals(b.getLevel().dimension().location().toString())){
            var p=new BlockPos(link.console().x(),link.console().y(),link.console().z());
            if(b.getLevel().hasChunkAt(p)&&b.getLevel().getBlockEntity(p) instanceof HomeConsoleBlockEntity c&&c.hardwareId().equals(link.console().id()))console=c;
        }
        poses.pushPose();try{
            poses.translate(.5,0,.5);poses.mulPose(Axis.YP.rotationDegrees(-90*turns));poses.translate(-.5,0,-.5);
            draw(STAND,poses,buffers,light,overlay);
            if(b.occupied()){draw(BODY,poses,buffers,light,overlay);draw(TRIGGER,poses,buffers,light,overlay);}
            if(b.occupied()&&console==null){draw(CABLE,poses,buffers,light,overlay);draw(PLUG,poses,buffers,light,overlay);}
        }finally{poses.popPose();}
        if(console==null)return;
        var direction=console.getBlockState().getValue(cn.piq.fcarcade.world.FcArcadeBlock.FACING);
        int ct=cn.piq.fcarcade.layout.RocketArcadeGeometry.quarterTurns(direction.getStepX(),direction.getStepZ());
        boolean subor=console.getBlockState().getBlock() instanceof SuborConsoleBlock;
        boolean wide=subor&&SuborConsoleBlock.wide(console.getBlockState());
        Point socket=cn.piq.fcarcade.layout.ControllerCableGeometry.socket(subor?(SuborConsoleBlock.compact(console.getBlockState())?cn.piq.fcarcade.layout.ControllerCableGeometry.Style.SUBOR_COMPACT:wide?cn.piq.fcarcade.layout.ControllerCableGeometry.Style.SUBOR_WIDE:cn.piq.fcarcade.layout.ControllerCableGeometry.Style.SUBOR):cn.piq.fcarcade.layout.ControllerCableGeometry.Style.FAMICOM,1,ct);
        var delta=console.getBlockPos().subtract(b.getBlockPos());Point end=new Point(socket.x()+delta.getX(),socket.y()+delta.getY(),socket.z()+delta.getZ());
        Point start=ZapperStandGeometry.rotate(ZapperStandGeometry.CORD,turns);
        if(!b.occupied()){
            var loan=b.loan();var player=loan==null?null:b.getLevel().getPlayerByUUID(loan.player());
            if(player==null||!ZapperStandService.originMatches(player.getMainHandItem(),b))return;
            // Presentation only, using the same side/eye offsets as the current gun hand pose.
            Vec3 forward=player.getLookAngle();Vec3 side=new Vec3(-forward.z,0,forward.x).normalize().scale(player.getMainArm()==HumanoidArm.RIGHT?1:-1);
            Vec3 grip=player.getEyePosition(partial).add(forward.scale(.62)).add(side.scale(.38)).add(0,-.53,0).subtract(Vec3.atLowerCornerOf(b.getBlockPos()));
            start=new Point(grip.x,grip.y,grip.z);
        }
        drawCable(ZapperStandGeometry.cable(start,end),poses,buffers,light,overlay);
        poses.pushPose();try{
            poses.translate(end.x(),end.y(),end.z());poses.mulPose(Axis.YP.rotationDegrees(-90*ct));
            // Source connector's nose points -X; point it into the north-facing console.
            poses.mulPose(Axis.YP.rotationDegrees(90));var p=ZapperStandGeometry.PLUG;poses.translate(-p.x(),-p.y(),-p.z());draw(PLUG,poses,buffers,light,overlay);
        }finally{poses.popPose();}
    }
    private static void draw(ModelResourceLocation id,PoseStack poses,MultiBufferSource buffers,int light,int overlay){var model=Minecraft.getInstance().getModelManager().getModel(id);var cached=CACHE.get(id);
        if(cached==null||cached.source()!=model){var random=RandomSource.create(0);var quads=new ArrayList<>(model.getQuads(null,null,random));for(Direction d:Direction.values()){random.setSeed(0);quads.addAll(model.getQuads(null,d,random));}cached=new Cache(model,List.copyOf(quads));CACHE.put(id,cached);}
        var out=buffers.getBuffer(RenderType.entityCutoutNoCull(TextureAtlas.LOCATION_BLOCKS));for(var q:cached.quads())out.putBulkData(poses.last(),q,1,1,1,1,light,overlay);}
    private static void drawCable(List<Point> path,PoseStack poses,MultiBufferSource buffers,int light,int overlay){
        var out=buffers.getBuffer(RenderType.entityCutoutNoCull(ResourceLocation.withDefaultNamespace("textures/block/black_concrete.png")));
        for(int i=1;i<path.size();i++){var a=path.get(i-1);var b=path.get(i);var axis=new Vec3(b.x()-a.x(),b.y()-a.y(),b.z()-a.z()).normalize();
            var u=axis.cross(Math.abs(axis.y)>.95?new Vec3(1,0,0):new Vec3(0,1,0)).normalize();var v=axis.cross(u).normalize();
            for(int side=0;side<8;side++){double t=side*Math.PI/4,s=(side+1)*Math.PI/4;var n=u.scale(Math.cos(t)).add(v.scale(Math.sin(t)));var m=u.scale(Math.cos(s)).add(v.scale(Math.sin(s)));
                vertex(out,poses,a,n,0,0,light,overlay);vertex(out,poses,b,n,0,1,light,overlay);vertex(out,poses,b,m,1,1,light,overlay);vertex(out,poses,a,m,1,0,light,overlay);}}
    }
    private static void vertex(VertexConsumer out,PoseStack poses,Point p,Vec3 n,float u,float v,int light,int overlay){out.addVertex(poses.last().pose(),(float)(p.x()+n.x*.006),(float)(p.y()+n.y*.006),(float)(p.z()+n.z*.006))
            .setColor(255,255,255,255).setUv(u,v).setOverlay(overlay).setLight(light).setNormal(poses.last(),(float)n.x,(float)n.y,(float)n.z);}
}
