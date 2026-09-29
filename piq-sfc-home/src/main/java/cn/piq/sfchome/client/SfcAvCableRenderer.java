package cn.piq.sfchome.client;

import cn.piq.fcarcade.client.HomeAvCableMesh;
import cn.piq.fcarcade.client.HomeHardwareRenderLayout;
import cn.piq.fcarcade.client.HomeHardwareRenderer;
import cn.piq.fcarcade.client.UserTvCableMesh;
import cn.piq.fcarcade.home.UserTvLayout;
import cn.piq.fcarcade.layout.ArcadeDisplayStyle;
import cn.piq.fcarcade.home.HomeTvBlockEntity;
import cn.piq.fcarcade.home.HomeTvStructure;
import cn.piq.fcarcade.home.LargeLcdTvBlock;
import cn.piq.fcarcade.home.LcdTvBlock;
import cn.piq.fcarcade.home.VintageTvBlock;
import cn.piq.fcarcade.home.WideLcdTvBlock;
import cn.piq.sfchome.world.SfcHomeConsoleBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;

/** SFC-only AV adapter. FC public television coordinates are read, never changed. */
public final class SfcAvCableRenderer {
    private static final ResourceLocation SOLID = ResourceLocation.fromNamespaceAndPath(
            "piq_fc_arcade", "textures/block/home_retro_tv_white.png");
    // Values hold only immutable geometry/coordinates, never their weak BE key or world.
    private final Map<SfcHomeConsoleBlockEntity, Cached> cache = new WeakHashMap<>();
    private record Key(SfcAvCableGeometry.Endpoint source,SfcAvCableGeometry.Endpoint target,
                       ArcadeDisplayStyle style,int consoleTurns,int tvTurns,double dx,double dy,double dz) {}
    private record Cached(Key key,SfcAvCableGeometry.Mesh mesh) {}

    /** Visual validity only; never grants input, cartridge or link mutation authority. */
    private static HomeTvBlockEntity connectedTv(SfcHomeConsoleBlockEntity console) {
        var level=console.getLevel();BlockPos pos=console.televisionPos();
        if(level==null||console.isRemoved()||pos==null||console.linkId()==null
                ||console.getBlockPos().distSqr(pos)>64||!level.hasChunkAt(console.getBlockPos())
                ||!level.hasChunkAt(pos)||level.getBlockEntity(console.getBlockPos())!=console
                ||!console.isHardwareComplete())return null;
        if(!(level.getBlockEntity(pos) instanceof HomeTvBlockEntity tv)||tv.isRemoved()
                ||!console.getBlockPos().equals(tv.consolePos())||!console.linkId().equals(tv.linkId())
                ||!tv.structureInstalled()||!HomeTvStructure.complete(level,pos))return null;
        return tv;
    }

    public static int turns(BlockState state) {
        Direction facing=state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
                ?state.getValue(BlockStateProperties.HORIZONTAL_FACING):Direction.NORTH;
        return switch(facing){case EAST->1;case SOUTH->2;case WEST->3;default->0;};
    }

    public AABB bounds(SfcHomeConsoleBlockEntity console) {
        var b=cn.piq.sfchome.layout.SfcConsoleScale.render(turns(console.getBlockState()));
        AABB local=new AABB(b.minX()/16,b.minY()/16,b.minZ()/16,b.maxX()/16,b.maxY()/16,b.maxZ()/16).move(console.getBlockPos());HomeTvBlockEntity tv=connectedTv(console);
        return tv==null?local:local.minmax(HomeHardwareRenderer.tvRenderBounds(tv.getBlockPos(),tv.getBlockState())).inflate(.45);
    }

    public void render(SfcHomeConsoleBlockEntity console,PoseStack.Pose pose,MultiBufferSource buffers,int light,int overlay) {
        HomeTvBlockEntity tv=connectedTv(console);
        if(tv==null){cache.remove(console);return;}
        Key key=key(console,tv);Cached prior=cache.get(console);
        if(prior==null||!prior.key.equals(key)){prior=new Cached(key,build(key));cache.put(console,prior);}
        if(!prior.mesh.visible())return;
        VertexConsumer out=buffers.getBuffer(RenderType.entityCutoutNoCull(SOLID));
        for(var q:prior.mesh.quads()) {
            vertex(out,pose,q.a(),q.normal(),q.color(),light,overlay);
            vertex(out,pose,q.b(),q.normal(),q.color(),light,overlay);
            vertex(out,pose,q.c(),q.normal(),q.color(),light,overlay);
            vertex(out,pose,q.d(),q.normal(),q.color(),light,overlay);
        }
    }

    private static Key key(SfcHomeConsoleBlockEntity console,HomeTvBlockEntity tv) {
        BlockPos from=console.getBlockPos(),to=tv.getBlockPos();BlockState state=tv.getBlockState();
        double dx=to.getX()-from.getX(),dy=to.getY()-from.getY(),dz=to.getZ()-from.getZ();
        boolean vintage=state.getBlock() instanceof VintageTvBlock,large=state.getBlock() instanceof LargeLcdTvBlock,
                wide=state.getBlock() instanceof WideLcdTvBlock,lcd=state.getBlock() instanceof LcdTvBlock;
        int tvTurns=turns(state);
        var style=((cn.piq.fcarcade.world.FcArcadeBlock)state.getBlock()).displayStyle();
        var sockets=UserTvLayout.supports(style)?UserTvCableMesh.tvSockets(style,tvTurns,new HomeHardwareRenderLayout.Point(dx,dy,dz))
                :HomeAvCableMesh.tvSockets(HomeTvStructure.centered(state),lcd,wide,large,vintage,tvTurns,
                new HomeHardwareRenderLayout.Point(dx,dy,dz));
        var points=new ArrayList<SfcAvCableGeometry.Vec>();
        for(var p:sockets)points.add(new SfcAvCableGeometry.Vec(p.x(),p.y(),p.z()));
        AABB b=HomeHardwareRenderer.tvRenderBounds(to,state).move(-from.getX(),-from.getY(),-from.getZ());
        var target=new SfcAvCableGeometry.Endpoint(points,new SfcAvCableGeometry.Box(b.minX,b.minY,b.minZ,b.maxX,b.maxY,b.maxZ),
                SfcAvCableGeometry.outward(tvTurns),dy,1);
        return new Key(SfcAvCableGeometry.console(turns(console.getBlockState())),target,style,turns(console.getBlockState()),tvTurns,dx,dy,dz);
    }

    private static SfcAvCableGeometry.Mesh build(Key key) {
        if(!UserTvLayout.supports(key.style))return SfcAvCableGeometry.build(key.source,key.target);
        var source=key.source;var p=source.sockets().getFirst();var b=source.housing();
        var shared=UserTvCableMesh.buildMultiOut(new HomeHardwareRenderLayout.Point(p.x(),p.y(),p.z()),
                new UserTvCableMesh.Bounds(b.minX(),b.minY(),b.minZ(),b.maxX(),b.maxY(),b.maxZ()),
                key.consoleTurns,source.plugScale(),key.style,key.tvTurns,key.dx,key.dy,key.dz);
        var quads=new ArrayList<SfcAvCableGeometry.Quad>();
        for(var q:shared)quads.add(new SfcAvCableGeometry.Quad(vector(q.a()),vector(q.b()),vector(q.c()),vector(q.d()),vector(q.normal()),q.color(),"user-tv"));
        return new SfcAvCableGeometry.Mesh(quads,java.util.List.of(),Math.min(0,key.dy),shared.isEmpty()?"new-tv-safe-route-unavailable":"");
    }
    private static SfcAvCableGeometry.Vec vector(HomeHardwareRenderLayout.Point p){return new SfcAvCableGeometry.Vec(p.x(),p.y(),p.z());}

    private static void vertex(VertexConsumer out,PoseStack.Pose pose,SfcAvCableGeometry.Vec p,
                               SfcAvCableGeometry.Vec n,int color,int light,int overlay) {
        out.addVertex(pose,(float)p.x(),(float)p.y(),(float)p.z())
                .setColor((color>>16)&255,(color>>8)&255,color&255,255).setUv(.5f,.5f)
                .setOverlay(overlay).setLight(light).setNormal(pose,(float)n.x(),(float)n.y(),(float)n.z());
    }
}
