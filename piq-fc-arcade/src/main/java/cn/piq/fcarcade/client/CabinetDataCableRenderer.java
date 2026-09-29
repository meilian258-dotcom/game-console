package cn.piq.fcarcade.client;

import cn.piq.fcarcade.cabinet.CabinetTarget;
import cn.piq.fcarcade.layout.CabinetDataCableGeometry;
import cn.piq.fcarcade.layout.RocketArcadeGeometry;
import cn.piq.fcarcade.layout.RocketArcadeGeometry.Point;
import cn.piq.fcarcade.world.LegacyFcArcadeBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;

/** Nearby BE visual pairs only: this renderer cannot create links, seats or runtime authorization. */
public final class CabinetDataCableRenderer {
    private static final ResourceLocation TEXTURE=ResourceLocation.withDefaultNamespace("textures/block/gray_concrete.png");
    private record Key(CabinetTarget first,CabinetTarget second,int firstTurns,int secondTurns,boolean firstCompact,boolean secondCompact,boolean firstPortrait,boolean secondPortrait) {}
    private record Cached(Key key,List<CabinetDataCableGeometry.Quad> mesh) {}
    private static final Map<UUID,Cached> CACHE=new LinkedHashMap<>();
    private static java.lang.ref.WeakReference<Object> world=new java.lang.ref.WeakReference<>(null);
    private CabinetDataCableRenderer() {}
    public static boolean matches(CabinetTarget first,UUID pair,CabinetTarget peer,CabinetTarget second,UUID otherPair,CabinetTarget otherPeer) {
        return first!=null&&second!=null&&pair!=null&&pair.equals(otherPair)&&second.equals(peer)&&first.equals(otherPeer)
                &&first.dimension().equals(second.dimension())&&!first.identity().equals(second.identity())&&!first.anchor().equals(second.anchor());
    }
    public static AABB bounds(LegacyFcArcadeBlockEntity machine,AABB normal) {
        var peer=machine.visualLinkPeer();return machine.visualLinkId()!=null&&peer!=null?normal.minmax(new AABB(peer.anchor()).inflate(2)).inflate(.5):normal;
    }
    public static void render(LegacyFcArcadeBlockEntity machine,PoseStack poses,MultiBufferSource buffers,int light,int overlay) {
        var mc=Minecraft.getInstance();var level=mc.level;
        if(world.get()!=level){world=new java.lang.ref.WeakReference<>(level);CACHE.clear();}
        var pair=machine.visualLinkId();var peer=machine.visualLinkPeer();
        if(level==null||machine.getLevel()!=level||machine.isRemoved()||mc.getConnection()==null||!mc.getConnection().getConnection().isConnected()
                ||pair==null||peer==null||machine.cabinetId().compareTo(peer.identity())>=0||!peer.matches(level))return;
        var first=CabinetTarget.resolve(level,machine.getBlockPos());
        if(first==null||level.getBlockEntity(machine.getBlockPos())!=machine||!(level.getBlockEntity(peer.anchor()) instanceof LegacyFcArcadeBlockEntity second)
                ||!matches(first,pair,peer,CabinetTarget.resolve(level,peer.anchor()),second.visualLinkId(),second.visualLinkPeer()))return;
        if(mc.gameRenderer.getMainCamera().getPosition().distanceToSqr(machine.getBlockPos().getCenter())>CabinetDataCableGeometry.VIEW_RANGE*CabinetDataCableGeometry.VIEW_RANGE)return;
        var face=machine.getBlockState().getValue(BlockStateProperties.HORIZONTAL_FACING);var otherFace=second.getBlockState().getValue(BlockStateProperties.HORIZONTAL_FACING);
        int turns=RocketArcadeGeometry.quarterTurns(face.getStepX(),face.getStepZ()),otherTurns=RocketArcadeGeometry.quarterTurns(otherFace.getStepX(),otherFace.getStepZ());
        boolean firstCompact=machine instanceof cn.piq.fcarcade.world.DualCabinetBlockEntity dual&&dual.compactFootprint();
        boolean secondCompact=second instanceof cn.piq.fcarcade.world.DualCabinetBlockEntity dual&&dual.compactFootprint();
        boolean firstPortrait=machine instanceof cn.piq.fcarcade.world.PortraitCabinetBlockEntity,secondPortrait=second instanceof cn.piq.fcarcade.world.PortraitCabinetBlockEntity;
        var key=new Key(first,peer,turns,otherTurns,firstCompact,secondCompact,firstPortrait,secondPortrait);var cached=CACHE.get(pair);
        if(cached==null||!cached.key().equals(key)){
            var a=first.anchor();var b=peer.anchor();cached=new Cached(key,CabinetDataCableGeometry.build(first.dual(),turns,peer.dual(),otherTurns,(double)b.getX()-a.getX(),(double)b.getY()-a.getY(),(double)b.getZ()-a.getZ(),firstCompact,secondCompact,firstPortrait,secondPortrait));
            if(CACHE.size()>=128)CACHE.clear();CACHE.put(pair,cached);
        }
        var out=buffers.getBuffer(RenderType.entityCutoutNoCull(TEXTURE));
        for(var q:cached.mesh()){vertex(out,poses,q.a(),q.normal(),q.color(),0,0,light,overlay);vertex(out,poses,q.b(),q.normal(),q.color(),0,1,light,overlay);vertex(out,poses,q.c(),q.normal(),q.color(),1,1,light,overlay);vertex(out,poses,q.d(),q.normal(),q.color(),1,0,light,overlay);}
    }
    private static void vertex(VertexConsumer out,PoseStack poses,Point p,Point normal,int color,float u,float v,int light,int overlay) {
        out.addVertex(poses.last().pose(),(float)p.x(),(float)p.y(),(float)p.z()).setColor(color>>16&255,color>>8&255,color&255,255).setUv(u,v).setOverlay(overlay).setLight(light).setNormal(poses.last(),(float)normal.x(),(float)normal.y(),(float)normal.z());
    }
}
