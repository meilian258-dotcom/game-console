package cn.piq.fcarcade.client;

import cn.piq.fcarcade.cabinet.WatchDescriptor;
import cn.piq.fcarcade.client.watch.WatchClient;
import cn.piq.fcarcade.home.*;
import cn.piq.fcarcade.world.ArcadeStructure;
import cn.piq.fcarcade.world.FcArcadeBlock;
import cn.piq.fcarcade.layout.RocketArcadeGeometry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import cn.piq.fcarcade.server.ServerArcadeSessions;
import cn.piq.fcarcade.netplay.*;
import cn.piq.fcarcade.client.watch.NetplayWatchContent;
import cn.piq.fcarcade.client.cabinet.CabinetBackend;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Common physical FC display; MEDIA decodes frames, JNI spectators use a read-only replica. */
final class FcHomeWatchDisplay implements WatchClient.DisplayAdapter {
    static final ResourceLocation PROVIDER = ResourceLocation.fromNamespaceAndPath("piq_fc_arcade", "home_player");
    static void register() {
        WatchClient.registerDisplay(PROVIDER, new FcHomeWatchDisplay());
        WatchClient.registerHost(PROVIDER, ClientArcadeEvents::playerMediaDemand);
        WatchClient.registerDisplay(ServerArcadeSessions.JNI_WATCH_PROVIDER,new FcHomeWatchDisplay());
        NetplayWatchContent.register(ServerArcadeSessions.JNI_WATCH_PROVIDER,(start,connection)->{
            var mc=Minecraft.getInstance();
            if(mc.getConnection()==null||mc.getConnection().getConnection()!=connection||!new FcHomeWatchDisplay().valid(start.watch().descriptor()))throw new IllegalStateException("FC 旁观设备或连接已失效");
            if(!JniNetplayConsent.allowed())throw new IllegalStateException("本机已停用 FC JNI 或平台不支持");
            boolean gun=ServerArcadeSessions.JNI_GUN_BACKEND.equals(start.backend());
            if(!gun&&!ServerArcadeSessions.JNI_PAD_BACKEND.equals(start.backend()))throw new IllegalArgumentException("FC observer backend");
            // This declaration supplies the trusted resource/budget. The factory below preserves
            // FC's existing handshake/save namespace, including the special gun timeline.
            var jni=JniNetplaySession.profile(false);var artifact=jni.cores().get("windows-x64");
            var profile=new NetplayProfile(NetplayProcess.class,artifact.resource(),artifact.sha256(),"content.nes",jni.options(),257,44100,16*1024*1024,2,jni);
            var loaded=new CompletableFuture<cn.piq.fcarcade.rom.RomDescriptor>();
            Runnable cancel=ClientRomTransfers.observeContent(start.romHash(),loaded::complete);
            return new NetplayWatchContent.Preparation(()->{
                var rom=loaded.get(60,TimeUnit.SECONDS);
                if(!start.romHash().equalsIgnoreCase(rom.sha256()))throw new IllegalStateException("FC 旁观内容校验失败");
                return new CabinetBackend.NetplayContent(profile,rom.bytes(),Map.of());
            },()->{cancel.run();loaded.cancel(false);},false,(grant,content,sender)->new NetplayProcess(grant,content::rom,sender,true,gun));
        });
    }
    @Override public boolean valid(WatchDescriptor d) {
        var level = Minecraft.getInstance().level;
        if (level == null || !(PROVIDER.equals(d.provider())||ServerArcadeSessions.JNI_WATCH_PROVIDER.equals(d.provider())) || !level.dimension().location().equals(d.dimension())
                || d.link() == null || d.screens().size() != 1) return false;
        var screen = d.screens().getFirst();
        if (!level.hasChunkAt(d.origin().pos()) || !level.hasChunkAt(screen.pos())) return false;
        return level.getBlockEntity(d.origin().pos()) instanceof HomeConsoleBlockEntity console
                && level.getBlockEntity(screen.pos()) instanceof HomeTvBlockEntity tv
                && d.origin().identity().equals(console.hardwareId()) && screen.identity().equals(tv.hardwareId())
                && d.link().equals(console.linkId()) && d.link().equals(tv.linkId())
                && screen.pos().equals(console.tvPos()) && d.origin().pos().equals(tv.consolePos())
                && HomeTvStructure.complete(level, screen.pos())
                && ArcadeStructure.resolve(level, screen.pos()).anchor().equals(screen.pos());
    }
    @Override public boolean isParticipant(WatchDescriptor d) { return ClientArcadeEvents.hasHomeParticipant(d); }
    @Override public float volume(WatchDescriptor d) {
        if (!valid(d)) return 0;
        var mc = Minecraft.getInstance(); if (mc.player == null) return 0;
        return HomeApplianceService.audioGain(mc.level, d.screens().getFirst().pos())
                * (float)Math.max(0, 1 - Math.sqrt(mc.player.distanceToSqr(d.screens().getFirst().pos().getCenter())) / 16);
    }
    @Override public void render(RenderLevelStageEvent event, WatchDescriptor d, ResourceLocation texture, float aspect, int rotation) {
        if (rotation != 0 || !valid(d) || event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) return;
        var mc = Minecraft.getInstance(); var pos = d.screens().getFirst().pos();
        if (!HomeApplianceService.videoAllowed(mc.level,pos)) return;
        var tv = (HomeTvBlockEntity)mc.level.getBlockEntity(pos);
        if (!(tv.getBlockState().getBlock() instanceof FcArcadeBlock block)) return;
        var layout = ArcadeStructure.resolve(mc.level,pos);
        var stack = event.getPoseStack();
        stack.pushPose();
        try {
            if (!HomeScreenRenderPose.apply(event,mc.level,pos)) return;
            if (block.displayStyle() == cn.piq.fcarcade.layout.ArcadeDisplayStyle.HOME_RETRO_TV) {
                var offset = cn.piq.fcarcade.layout.ScreenSurfaceGeometry.translation(block.displayStyle(),
                        RocketArcadeGeometry.quarterTurns(layout.facing().getStepX(), layout.facing().getStepZ()),
                        HomeTvStructure.centered(tv.getBlockState()));
                stack.translate(offset.x(), offset.y(), offset.z());
            }
            var buffers = mc.renderBuffers().bufferSource(); var type = ScreenRenderMaterial.of(texture);
            var consumer = buffers.getBuffer(type);
            if (tv.scanlinesEnabled()) consumer = new CrtScanlineVertexConsumer(consumer,event.getProjectionMatrix(),
                    mc.getWindow().getWidth(),mc.getWindow().getHeight());
            ArcadeBlockScreenRenderer.drawFace(consumer,stack.last(),layout.facing(),layout.width(),layout.height(),
                    block.displayStyle(),HomeApplianceClient.powerAmount(tv));
            buffers.endBatch(type);
        } finally { stack.popPose(); }
    }
}
