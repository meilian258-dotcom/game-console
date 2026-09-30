package cn.piq.fcarcade.client;

import cn.piq.fcarcade.home.*;
import cn.piq.fcarcade.client.HomeHardwareRenderLayout.Point;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import java.util.*;

/** Shared client-only rear MULTI OUT adapter. No addon dependency or world mutation. */
public final class ExternalHomeAvRenderer<T extends ExternalHomeConsoleBlockEntity> implements BlockEntityRenderer<T> {
    private static final ResourceLocation SOLID=ResourceLocation.fromNamespaceAndPath("piq_fc_arcade","textures/block/home_retro_tv_white.png");
    private final Point socket;
    private final UserTvCableMesh.Bounds housing;
    private final double scale;
    private final Map<T,Cached> cache=new WeakHashMap<>();
    private record Key(int turns,BlockState tv,double x,double y,double z,UUID link){}
    private record Cached(Key key,List<HomeAvCableMesh.Quad> mesh,AABB bounds){}
    /** North-facing block-local coordinates, exact model socket and conservative body. */
    public ExternalHomeAvRenderer(Point socket,UserTvCableMesh.Bounds housing,double scale){this.socket=socket;this.housing=housing;this.scale=scale;}
    private static int turns(BlockState s){return switch(s.getValue(BlockStateProperties.HORIZONTAL_FACING)){case EAST->1;case SOUTH->2;case WEST->3;default->0;};}
    private HomeTvBlockEntity linked(T c){
        var l=c.getLevel();var pos=c.televisionPos();
        if(l==null||c.isRemoved()||pos==null||c.linkId()==null||c.getBlockPos().distSqr(pos)>64||!l.hasChunkAt(c.getBlockPos())||!l.hasChunkAt(pos)
                ||l.getBlockEntity(c.getBlockPos())!=c||!c.isHardwareComplete())return null;
        return l.getBlockEntity(pos) instanceof HomeTvBlockEntity tv&&!tv.isRemoved()&&c.getBlockPos().equals(tv.consolePos())
                &&c.linkId().equals(tv.linkId())&&tv.structureInstalled()&&HomeTvStructure.complete(l,pos)?tv:null;
    }
    private Cached geometry(T c){
        var tv=linked(c);if(tv==null){cache.remove(c);return null;}
        var p=c.getBlockPos();var t=tv.getBlockPos();var state=tv.getBlockState();int turn=turns(c.getBlockState()),tt=turns(state);
        var key=new Key(turn,state,t.getX()-p.getX(),t.getY()-p.getY(),t.getZ()-p.getZ(),c.linkId());var old=cache.get(c);
        if(old!=null&&old.key.equals(key))return old;
        var s=HomeHardwareRenderLayout.rotate(socket,turn);
        var a=HomeHardwareRenderLayout.rotate(new Point(housing.minX(),housing.minY(),housing.minZ()),turn);
        var b=HomeHardwareRenderLayout.rotate(new Point(housing.maxX(),housing.maxY(),housing.maxZ()),turn);
        var bounds=new UserTvCableMesh.Bounds(Math.min(a.x(),b.x()),housing.minY(),Math.min(a.z(),b.z()),Math.max(a.x(),b.x()),housing.maxY(),Math.max(a.z(),b.z()));
        var style=((cn.piq.fcarcade.world.FcArcadeBlock)state.getBlock()).displayStyle();
        var mesh=UserTvLayout.supports(style)?UserTvCableMesh.buildMultiOut(s,bounds,turn,scale,style,tt,key.x,key.y,key.z)
                :UserTvCableMesh.buildMultiOutLegacy(s,bounds,turn,scale,HomeTvStructure.centered(state),state.getBlock() instanceof LcdTvBlock,
                state.getBlock() instanceof WideLcdTvBlock,state.getBlock() instanceof LargeLcdTvBlock,state.getBlock() instanceof VintageTvBlock,tt,key.x,key.y,key.z);
        var box=new AABB(0,0,0,1,1,1);
        for(var q:mesh)for(var v:List.of(q.a(),q.b(),q.c(),q.d()))box=box.minmax(new AABB(v.x(),v.y(),v.z(),v.x(),v.y(),v.z()));
        var result=new Cached(key,mesh,box.move(p).inflate(.02));cache.put(c,result);return result;
    }
    @Override public void render(T c,float partial,PoseStack stack,MultiBufferSource buffers,int light,int overlay){
        var g=geometry(c);if(g==null||g.mesh.isEmpty())return;
        var out=buffers.getBuffer(RenderType.entityCutoutNoCull(SOLID));var pose=stack.last();
        for(var q:g.mesh){vertex(out,pose,q,q.a(),light,overlay);vertex(out,pose,q,q.b(),light,overlay);
            vertex(out,pose,q,q.c(),light,overlay);vertex(out,pose,q,q.d(),light,overlay);}
    }
    private static void vertex(VertexConsumer out,PoseStack.Pose pose,HomeAvCableMesh.Quad q,Point p,int light,int overlay){
        out.addVertex(pose,(float)p.x(),(float)p.y(),(float)p.z()).setColor(q.color()>>16&255,q.color()>>8&255,q.color()&255,255)
                .setUv(.5f,.5f).setOverlay(overlay).setLight(light).setNormal(pose,(float)q.normal().x(),(float)q.normal().y(),(float)q.normal().z());
    }
    @Override public boolean shouldRenderOffScreen(T c){return true;}
    @Override public AABB getRenderBoundingBox(T c){var g=geometry(c);return g==null?new AABB(c.getBlockPos()):g.bounds;}
}
