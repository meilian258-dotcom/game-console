package cn.piq.sfchome.client;

import cn.piq.fcarcade.cabinet.WatchDescriptor;
import cn.piq.fcarcade.client.HomeVideoDisplay;
import cn.piq.fcarcade.client.watch.WatchClient;
import cn.piq.fcarcade.home.HomeTvBlockEntity;
import cn.piq.fcarcade.home.HomeTvStructure;
import cn.piq.fcarcade.world.ArcadeStructure;
import cn.piq.fcarcade.world.FcArcadeBlock;
import cn.piq.sfchome.SfcHomeMod;
import cn.piq.sfchome.net.SfcHomeNetwork;
import cn.piq.sfchome.world.SfcHomeConsoleBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/** Read-only television adapter for media and Netplay observers; never a controller. */
@EventBusSubscriber(modid="piq_sfc_home",value=Dist.CLIENT)
public final class SfcWatchClient implements WatchClient.DisplayAdapter {
    private SfcWatchClient() {}
    @EventBusSubscriber(modid="piq_sfc_home",value=Dist.CLIENT,bus=EventBusSubscriber.Bus.MOD)
    public static final class Setup {
        @SubscribeEvent public static void setup(FMLClientSetupEvent event){event.enqueueWork(()->{
            WatchClient.registerDisplay(SfcHomeMod.CABINET_BACKEND,new SfcWatchClient());
            WatchClient.registerHost(SfcHomeMod.CABINET_BACKEND,SfcWatchPublisher::demand);
            WatchClient.registerPendingControl("sfc-home",SfcHomeClient::pendingNativeControlClaims);
            cn.piq.fcarcade.client.watch.NetplayWatchContent.register(SfcHomeMod.CABINET_BACKEND,SfcNetplayWatchContent::prepare);
        });}
    }
    @SubscribeEvent public static void tick(ClientTickEvent.Post event){SfcWatchPublisher.tick();}
    @Override public boolean valid(WatchDescriptor descriptor){return hardwareCurrent(descriptor);}
    static boolean hardwareCurrent(WatchDescriptor descriptor){
        var level=Minecraft.getInstance().level;
        if(level==null||descriptor==null||!SfcHomeMod.CABINET_BACKEND.equals(descriptor.provider())
                ||!level.dimension().location().equals(descriptor.dimension())||descriptor.link()==null||descriptor.screens().size()!=1)return false;
        var origin=descriptor.origin();var screen=descriptor.screens().getFirst();
        if(!level.hasChunkAt(origin.pos())||!level.hasChunkAt(screen.pos()))return false;
        return level.getBlockEntity(origin.pos()) instanceof SfcHomeConsoleBlockEntity console
                &&level.getBlockEntity(screen.pos()) instanceof HomeTvBlockEntity tv
                &&origin.identity().equals(console.hardwareId())&&screen.identity().equals(tv.hardwareId())
                &&descriptor.link().equals(console.linkId())&&descriptor.link().equals(tv.linkId())
                &&origin.pos().equals(tv.consolePos())&&screen.pos().equals(console.televisionPos())
                &&HomeTvStructure.complete(level,screen.pos())&&ArcadeStructure.resolve(level,screen.pos()).anchor().equals(screen.pos())
                &&tv.getBlockState().getBlock() instanceof FcArcadeBlock;
    }
    static boolean matches(WatchDescriptor descriptor,SfcHomeNetwork.Session session){
        if(descriptor==null||session==null||descriptor.screens().size()!=1)return false;
        var tv=descriptor.screens().getFirst();
        return SfcHomeMod.CABINET_BACKEND.equals(descriptor.provider())&&session.dimension().equals(descriptor.dimension())
                &&session.mediaSource().equals(descriptor.source())&&session.mediaStream().equals(descriptor.hostLease())
                &&session.consolePos().equals(descriptor.origin().pos())&&session.consoleId().equals(descriptor.origin().identity())
                &&session.tvPos().equals(tv.pos())&&session.tvId().equals(tv.identity())&&session.linkId().equals(descriptor.link());
    }
    @Override public boolean isParticipant(WatchDescriptor descriptor){
        if(SfcLocalWatchClient.suppressMedia())return true;
        var session=SfcHomeClient.currentSession();
        return matches(descriptor,session)&&SfcHomeClient.sessionCurrent(session,Minecraft.getInstance().getConnection())
                ||SfcLocalWatchClient.busy(descriptor);
    }
    @Override public boolean blocksNetplay(WatchDescriptor descriptor){
        return SfcLocalWatchClient.preferenceMode()!=cn.piq.sfchome.net.SfcLocalWatchNetwork.LOCAL
                ||SfcLocalWatchClient.busy(descriptor)
                ||matches(descriptor,SfcHomeClient.currentSession())&&SfcHomeClient.sessionCurrent(SfcHomeClient.currentSession(),Minecraft.getInstance().getConnection())
                ||cn.piq.fcarcade.client.PrivateHomeClient.isActiveOrClosing();
    }
    @Override public void render(RenderLevelStageEvent event,WatchDescriptor descriptor,ResourceLocation texture,float aspect,int rotation){
        if(rotation!=0||!hardwareCurrent(descriptor))return;
        var tv=descriptor.screens().getFirst();
        HomeVideoDisplay.render(event,SfcHomeMod.CABINET_BACKEND,descriptor.origin().pos(),descriptor.origin().identity(),
                tv.pos(),tv.identity(),descriptor.link(),texture,aspect);
    }
    @Override public float volume(WatchDescriptor descriptor){
        var player=Minecraft.getInstance().player;
        if(player==null||!hardwareCurrent(descriptor))return 0;
        return cn.piq.fcarcade.home.HomeApplianceService.audioGain(player.level(),descriptor.screens().getFirst().pos())*(float)Math.max(0,1-Math.sqrt(player.distanceToSqr(descriptor.screens().getFirst().pos().getCenter()))/16);
    }
}
