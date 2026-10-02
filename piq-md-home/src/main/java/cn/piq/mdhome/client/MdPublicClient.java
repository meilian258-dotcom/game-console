// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.mdhome.client;

import cn.piq.mdhome.*;
import cn.piq.fcarcade.cabinet.*;
import cn.piq.fcarcade.client.*;
import cn.piq.fcarcade.client.cabinet.*;
import cn.piq.fcarcade.client.watch.WatchClient;
import cn.piq.fcarcade.client.ui.*;
import cn.piq.fcarcade.home.*;
import cn.piq.fcarcade.home.content.ContentCardNetwork;
import cn.piq.fcarcade.netplay.NetplaySaveState;
import cn.piq.retro.api.RetroFrame;
import cn.piq.retro.client.*;
import cn.piq.retro.input.InputOwnership;
import cn.piq.retro.libretro.LibretroRuntimes;
import com.mojang.blaze3d.platform.InputConstants;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.*;

/** Client adapter: one public host or one explicitly granted receive/input seat. */
public final class MdPublicClient implements MdPublicNetwork.Client,WatchClient.DisplayAdapter {
    private static final Object OWNER=new Object();
    private static final MdClient.Provider PROVIDER=new MdClient.Provider();
    private static final MdContentRouting ROUTING=new MdContentRouting();
    private static Connection connection;
    private static MdPublicNetwork.Start host;
    private static MdPublicNetwork.Seat seat;
    private static MdEngine engine;
    private static volatile WatchMediaStream publisher;
    private static WatchMediaStream receiver;
    private static WatchAudio audio;
    private static DynamicTexture texture;private static ResourceLocation textureId;
    private static float aspect=4f/3;
    private static long demandRevision,demandExpires,inputSequence,lastInput;private static int lastMask;
    private static final long[] inputVersions={-1,-1};private static final UUID[] inputLoans=new UUID[2];
    private static final MdControllerFrames visual=new MdControllerFrames();
    private static boolean closing,privatePreference;
    private static long privatePending;
    private MdPublicClient(){}
    public static void install(){
        var sink=new MdPublicClient();MdPublicNetwork.client(sink);WatchClient.registerDisplay(MdMod.SYSTEM,sink);WatchClient.registerHost(MdMod.SYSTEM,MdPublicClient::demand);
        ContentCardClient.registerRuntime(MdMod.SYSTEM,new ContentCardClient.Runtime(){
            public boolean accept(ContentCardNetwork.Message m){var lane=route(m);return lane==MdContentRouting.Lane.PRIVATE||lane==MdContentRouting.Lane.PUBLIC&&same(m)&&host.rom().equals(m.hash());}
            public String start(ContentCardNetwork.Message m,Path path){
                if(route(m)==MdContentRouting.Lane.PRIVATE)return PrivateHomeClient.startCartridge(m.system(),m.pos(),path);
                if(!same(m)||!connected()||closing)return "MD 公共开机授权已失效";
                try{var stream=new WatchMediaStream(host.display().descriptor().source(),host.display().descriptor().hostLease(),true);publisher=stream;
                    engine=new MdEngine(path,LibretroRuntimes.Backend.JNI_TRIAL,NetplaySaveClient.open(connection,host.wire(),host.ticket()),new NetplaySaveState.Identity(host.profile(),host.rom()),host.resume(),stream::offer);
                    audio=new WatchAudio();return null;
                }catch(RuntimeException|LinkageError failure){notice("MD 启动失败："+failure.getMessage());shutdown("MD 启动失败");return "MD 公共核心启动失败";}
            }
            public int state(ContentCardNetwork.Message m){return route(m)==MdContentRouting.Lane.PRIVATE?PrivateHomeClient.cartridgeState(m.system(),m.pos()):!same(m)||engine==null||engine.error()!=null||closing?-1:engine.isReady()?1:0;}
            public void stop(ContentCardNetwork.Message m){var lane=route(m);if(same(m))shutdown("MD 关机，等待最终存档确认");else if(lane==MdContentRouting.Lane.PRIVATE)PrivateHomeClient.stopCartridge(m.system(),m.pos());var c=Minecraft.getInstance().getConnection();if(c!=null)ROUTING.retire(c.getConnection(),m.token());}
            public void reset(ContentCardNetwork.Message m){if(same(m)&&engine!=null){engine.clearInput();engine.requestReset();}else if(route(m)==MdContentRouting.Lane.PRIVATE)PrivateHomeClient.resetCartridge(m.system(),m.pos());}
        });
        ControllerCapture.registerRuntime(MdPublicClient::refreshInput);
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e)->tick());
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut e)->{shutdown("连接已断开");ROUTING.clear();privatePreference=false;privatePending=0;});
        NeoForge.EVENT_BUS.addListener((RenderLevelStageEvent e)->{if(e.getStage()==RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES){pump();sendInput(false);renderOwn(e);}});
        HomeSyncSettingsScreen.registerDeviceActions(MdMod.SYSTEM,new HomeSyncSettingsScreen.DeviceActions(){
            public void open(Screen parent,net.minecraft.core.BlockPos pos){Minecraft.getInstance().setScreen(new Options(parent));}
            public boolean choosesSecondPortAtStartup(){return true;}
            public String footer(net.minecraft.core.BlockPos pos){return "公开：JNI 玩家串流 · 个人/卡带服务器档 · 2P开局选择 · 自动旁观；私人：独立本机档。";}
        });
    }
    private static boolean same(ContentCardNetwork.Message m){return host!=null&&host.content().equals(m.token())&&host.display().descriptor().origin().pos().equals(m.pos())&&MdMod.SYSTEM.equals(m.system());}
    private static MdContentRouting.Lane route(ContentCardNetwork.Message m){var c=Minecraft.getInstance().getConnection();return c==null||!MdMod.SYSTEM.equals(m.system())?MdContentRouting.Lane.REJECT:ROUTING.route(c.getConnection(),m.token());}
    @Override public boolean current(Connection source){var c=Minecraft.getInstance().getConnection();return c!=null&&c.getConnection()==source&&source.isConnected();}
    @Override public void start(MdPublicNetwork.Start grant){
        if(host!=null||seat!=null||closing||MdEngine.active()||PrivateHomeClient.isActiveOrClosing()||!hardware(grant.display().descriptor())||!InputOwnership.acquire(OWNER)){rejectUnused(grant);notice("MD 无法接入：请先结束本机其他游戏或等待保存完成。");return;}
        connection=Minecraft.getInstance().getConnection().getConnection();host=grant;demandRevision=0;demandExpires=0;Arrays.fill(inputVersions,-1);Arrays.fill(inputLoans,null);
        if(!ROUTING.grant(connection,grant.content(),MdContentRouting.Lane.PUBLIC,true)){host=null;InputOwnership.release(OWNER);rejectUnused(grant);notice("MD 仍有前一份下载授权，拒绝新会话。");}
        else WatchClient.controlStarting();
    }
    private static void rejectUnused(MdPublicNetwork.Start grant){var c=Minecraft.getInstance().getConnection();if(c!=null)try{NetplaySaveClient.open(c.getConnection(),grant.wire(),grant.ticket()).abort();}catch(RuntimeException ignored){}}
    @Override public void privateStart(MdPublicNetwork.PrivateStart value){var c=Minecraft.getInstance().getConnection();if(c==null)return;if(!value.start()){ROUTING.retire(c.getConnection(),value.content());return;}ROUTING.grant(c.getConnection(),value.content(),MdContentRouting.Lane.PRIVATE,host==null&&seat==null&&!closing&&!MdEngine.active()&&!PrivateHomeClient.isActiveOrClosing());}
    @Override public void seat(MdPublicNetwork.Seat grant){
        if(seat!=null&&seat.equals(grant))return;
        if(closing||!hardware(grant.display().descriptor())||host!=null&&host.wire()!=grant.wire()||seat!=null&&!seat.loan().equals(grant.loan())||!InputOwnership.acquire(OWNER)){MdPublicNetwork.send(new MdPublicNetwork.Release(grant.wire(),grant.port(),grant.loan()));notice("MD 手柄未接入：请先结束当前本机游戏再领取。");return;}
        connection=Minecraft.getInstance().getConnection().getConnection();seat=grant;inputSequence=0;lastInput=0;lastMask=0;clearVisual();
        WatchClient.controlStarting();
        if(host==null){receiver=new WatchMediaStream(grant.display().descriptor().source(),grant.display().descriptor().hostLease(),false);audio=new WatchAudio();}
        refreshInput();
    }
    @Override public void end(MdPublicNetwork.End end){
        if(host!=null&&host.wire()==end.wire()&&end.shutdown()){shutdown(end.reason());return;}
        if(seat!=null&&seat.wire()==end.wire()&&seat.loan().equals(end.loan())){
            clearControls();seat=null;if(host==null)shutdown(end.reason());
        }
    }
    @Override public void input(MdPublicNetwork.Input value){
        if(host==null||closing||host.wire()!=value.wire()||engine==null)return;
        int p=value.port();if(!value.loan().equals(inputLoans[p])){inputLoans[p]=value.loan();inputVersions[p]=-1;engine.releasePort(p);}
        if(value.sequence()<=inputVersions[p])return;inputVersions[p]=value.sequence();engine.offerPort(p,value.mask());
    }
    @Override public void media(MdPublicNetwork.Media value){if(connected()&&seat!=null&&host==null&&!closing&&receiver!=null&&MdPublicNetwork.belongsTo(value,seat.wire(),seat.loan(),seat.display().descriptor()))receiver.accept(value.packet());}
    @Override public void preference(MdPublicNetwork.Preference value){privatePreference=value.privatePlay();privatePending=0;}
    @Override public void visual(MdPublicNetwork.Visual value){MdControllerVisual.accept(value);}
    static void clearVisual(){visual.clear(0);}
    static Object visualSession(net.minecraft.world.item.ItemStack stack){return seat!=null&&seat.loan().equals(MdController.loan(stack))&&seat.port()==MdController.port(stack)&&held()&&InputOwnership.owns(OWNER)?seat:null;}
    static int visualInput(net.minecraft.world.item.ItemStack stack){return visualSession(stack)==null?-1:visual.present(seat.port());}
    private static WatchDescriptor descriptor(){return host!=null?host.display().descriptor():seat!=null?seat.display().descriptor():null;}
    private static boolean connected(){var c=Minecraft.getInstance().getConnection();return c!=null&&c.getConnection()==connection&&connection.isConnected();}
    private static boolean held(){
        var mc=Minecraft.getInstance();if(seat==null||mc.player==null||mc.level==null||!connected()||closing||!hardware(seat.display().descriptor()))return false;
        for(var stack:List.of(mc.player.getMainHandItem(),mc.player.getOffhandItem()))if(seat.loan().equals(MdController.loan(stack))&&seat.port()==MdController.port(stack)&&PROVIDER.locate(mc.player,stack)!=null&&ControllerCapture.unique(mc.player,stack,seat.loan(),PROVIDER::identity))return true;
        return false;
    }
    private static void refreshInput(){
        if(!held()||!InputOwnership.owns(OWNER)){KeyboardInput.release(OWNER);GamepadInput.release(OWNER);return;}
        KeyboardInput.attach(OWNER,KeyboardConfig.Profile.SFC,PROVIDER::keys,MdPublicClient::held,()->held()&&InputOwnership.owns(OWNER),()->sendInput(true),()->sendInput(false));
    }
    private static boolean sendingInput;
    private static void sendInput(boolean forceZero){
        if(sendingInput||seat==null||!connected()||closing)return;sendingInput=true;
        try{var mc=Minecraft.getInstance();boolean active=!forceZero&&held()&&InputOwnership.owns(OWNER)&&mc.screen==null&&mc.isWindowActive()&&!mc.isPaused();int raw=0;
            if(active){int[][] keys=PROVIDER.keys();for(int bit=0;bit<keys.length;bit++)for(int key:keys[bit])if(key>=0&&InputConstants.isKeyDown(mc.getWindow().getWindow(),key)){raw|=1<<bit;break;}}
            int mask=forceZero?0:KeyboardInput.poll(OWNER,raw,active).mask();mask=GamepadInput.mix(OWNER,GamepadInput.ProfileKind.SFC,mask,active)&4095;
            if(active)visual.offer(seat.port(),mask);else clearVisual();
            long now=System.nanoTime();if(forceZero||mask!=lastMask||now-lastInput>=200_000_000L){lastMask=mask;lastInput=now;MdPublicNetwork.send(new MdPublicNetwork.Input(seat.wire(),seat.port(),seat.loan(),++inputSequence,mask));}
        }finally{sendingInput=false;}
    }
    private static void clearControls(){sendInput(true);clearVisual();KeyboardInput.release(OWNER);GamepadInput.release(OWNER);}
    private static void demand(WatchNetwork.HostDemand demand){
        if(host==null||closing||!connected()||!host.display().descriptor().equals(demand.descriptor())||demand.revision()<demandRevision)return;
        demandRevision=demand.revision();demandExpires=demand.needed()?System.nanoTime()+5_000_000_000L:0;var p=publisher;if(p!=null)p.sending(demand.needed());
    }
    private static void tick(){
        if(host==null&&seat==null)return;
        // A host may put down its controller and walk away; the server owns hardware lifetime.
        // Its local chunk/view disappearing must not kill the still-nearby second player or viewers.
        if(!connected()||host==null&&!hardware(descriptor())){shutdown("MD 主机或显示连接已失效");return;}
        refreshInput();sendInput(false);pump();
        var p=publisher;if(p!=null){if(System.nanoTime()>demandExpires)p.sending(false);for(int n=0;n<8;n++){var batch=p.pollOutbound();if(batch==null)break;p.transportResult(batch,CabinetMediaSender.watchServerbound(connection,batch));}}
        if(receiver!=null&&receiver.error()!=null&&seat!=null){notice("MD 音画接收失败："+receiver.error()+"；已归还本机手柄，主持继续运行。");MdPublicNetwork.send(new MdPublicNetwork.Release(seat.wire(),seat.port(),seat.loan()));shutdown("音画接收失败");return;}
        if(host!=null&&engine!=null&&engine.error()!=null){notice("MD 核心停止："+engine.error());shutdown("核心停止，等待保存结果");}
    }
    private static void pump(){
        if(closing)return;RetroFrame frame=null;
        if(engine!=null){var nativeFrame=engine.pollFrame();if(nativeFrame!=null){frame=new RetroFrame(nativeFrame.width(),nativeFrame.height(),nativeFrame.abgr(),nativeFrame.displayAspect(),nativeFrame.rotation(),nativeFrame.pcm48k());if(audio!=null)audio.offer(frame.pcm48k());}}
        else if(receiver!=null){frame=receiver.pollVideo();for(int n=0;n<16;n++){var pcm=receiver.pollAudio();if(pcm==null)break;if(audio!=null)audio.offer(pcm);}}
        var d=descriptor();var mc=Minecraft.getInstance();if(audio!=null&&d!=null)audio.gain(.6f*gain(d)*mc.options.getSoundSourceVolume(SoundSource.MASTER)*mc.options.getSoundSourceVolume(SoundSource.BLOCKS));
        if(frame!=null&&frame.rotation()==0&&visibleOwn(d)){
            if(texture==null||texture.getPixels()==null||texture.getPixels().getWidth()!=frame.width()||texture.getPixels().getHeight()!=frame.height()){
                dropTexture();texture=new DynamicTexture(frame.width(),frame.height(),false);texture.setFilter(false,false);textureId=mc.getTextureManager().register("md_public",texture);
            }
            for(int y=0;y<frame.height();y++)for(int x=0;x<frame.width();x++)texture.getPixels().setPixelRGBA(x,y,frame.abgr()[y*frame.width()+x]|0xff000000);
            texture.upload();aspect=frame.displayAspect();
        }
    }
    private static boolean visibleOwn(WatchDescriptor d){var mc=Minecraft.getInstance();return d!=null&&mc.player!=null&&hardware(d)&&HomeApplianceService.videoAllowed(mc.level,d.screens().getFirst().pos())&&mc.player.distanceToSqr(d.screens().getFirst().pos().getCenter())<=20*20;}
    private static void renderOwn(RenderLevelStageEvent event){var d=descriptor();if(d!=null&&textureId!=null&&visibleOwn(d))renderPicture(event,d,textureId,aspect);}
    private static void shutdown(String why){
        if(closing)return;clearControls();seat=null;var previous=host;host=null;InputOwnership.release(OWNER);
        if(previous!=null)ROUTING.retire(connection,previous.content());
        if(publisher!=null){publisher.close();publisher=null;}if(receiver!=null){receiver.close();receiver=null;}if(audio!=null){audio.close();audio=null;}dropTexture();
        var origin=connection;var stopping=engine;engine=null;if(stopping!=null){closing=true;stopping.stopAndSave().whenComplete((result,failure)->Minecraft.getInstance().execute(()->{closing=false;if(sameConnection(origin))notice(failure!=null?"MD 保存失败；旧档保留":result.message());}));}
        else if(previous!=null&&connection!=null&&connection.isConnected()){
            // No owner was created: explicitly release the unused authorized channel, not a normal final-save path.
            try{NetplaySaveClient.open(connection,previous.wire(),previous.ticket()).abort();}catch(RuntimeException ignored){}
        }
        connection=null;
    }
    private static void dropTexture(){if(textureId!=null)Minecraft.getInstance().getTextureManager().release(textureId);else if(texture!=null)texture.close();texture=null;textureId=null;}
    private static boolean sameConnection(Connection origin){var c=Minecraft.getInstance().getConnection();return origin!=null&&c!=null&&c.getConnection()==origin&&origin.isConnected();}
    public static void manualSave(){var e=engine;var origin=connection;if(e==null||!e.canSave()){notice("当前 MD 主持会话没有可保存的进度。");return;}e.requestSave().whenComplete((saved,problem)->Minecraft.getInstance().execute(()->{if(sameConnection(origin)&&engine==e)notice(problem==null?saved.message():"MD 保存失败，旧档保留");}));}
    public static boolean canSave(){return engine!=null&&engine.canSave()&&!closing;}
    public static String saveStatus(){return engine==null?closing?"正在结束并等待保存确认":"当前没有本机主持的 MD 公共会话":engine.saveStatus();}
    private static void notice(String value){var p=Minecraft.getInstance().player;if(p!=null)p.displayClientMessage(Component.literal(value),false);}
    static boolean hardware(WatchDescriptor d){
        var l=Minecraft.getInstance().level;if(l==null||d==null||!MdMod.SYSTEM.equals(d.provider())||!l.dimension().location().equals(d.dimension())||d.link()==null||d.screens().size()!=1)return false;
        var a=d.origin();var b=d.screens().getFirst();if(!l.hasChunkAt(a.pos())||!l.hasChunkAt(b.pos()))return false;
        return l.getBlockEntity(a.pos()) instanceof MdConsole c&&l.getBlockEntity(b.pos()) instanceof HomeTvBlockEntity t
            &&a.identity().equals(c.hardwareId())&&b.identity().equals(t.hardwareId())&&d.link().equals(c.linkId())&&d.link().equals(t.linkId())
            &&a.pos().equals(t.consolePos())&&b.pos().equals(c.televisionPos())&&HomeTvStructure.complete(l,b.pos());
    }
    private static float gain(WatchDescriptor d){var p=Minecraft.getInstance().player;return p==null||!hardware(d)?0:HomeApplianceService.audioGain(p.level(),d.screens().getFirst().pos())*(float)Math.max(0,1-Math.sqrt(p.distanceToSqr(d.screens().getFirst().pos().getCenter()))/16);}
    private static void renderPicture(RenderLevelStageEvent e,WatchDescriptor d,ResourceLocation texture,float aspect){var tv=d.screens().getFirst();HomeVideoDisplay.render(e,MdMod.SYSTEM,d.origin().pos(),d.origin().identity(),tv.pos(),tv.identity(),d.link(),texture,aspect);}
    @Override public boolean valid(WatchDescriptor d){return hardware(d);}
    @Override public boolean isParticipant(WatchDescriptor d){return host!=null||seat!=null||closing;}
    @Override public void render(RenderLevelStageEvent e,WatchDescriptor d,ResourceLocation texture,float aspect,int rotation){if(rotation==0&&hardware(d))renderPicture(e,d,texture,aspect);}
    @Override public float volume(WatchDescriptor d){return gain(d);}
    private static final class Options extends DeviceScreen {
        private final Screen parent;private DeviceFormLayout layout;
        Options(Screen parent){super(Component.literal("MD · 本机游玩设置"));this.parent=parent;}
        protected void init(){layout=DeviceFormLayout.of(width,height,4);
            addRenderableWidget(Button.builder(Component.literal("下次开机：切换公开 / 私人"),b->{if(privatePending==0){privatePending=System.nanoTime();MdPublicNetwork.send(new MdPublicNetwork.Preference(!privatePreference));}}).bounds(layout.left(),layout.rowY(0),layout.bodyWidth(),20).build());
            addRenderableWidget(Button.builder(Component.literal("本机控制 / 运行环境…"),b->HomeSyncSettingsScreen.openRuntimeSettings(this,MdMod.SYSTEM,"MD")).bounds(layout.left(),layout.rowY(1),layout.bodyWidth(),20).build());
            addRenderableWidget(Button.builder(Component.literal("手动保存公共会话"),b->manualSave()).bounds(layout.left(),layout.rowY(2),layout.bodyWidth(),20).build());
            addRenderableWidget(Button.builder(Component.literal("返回设备设置"),b->onClose()).bounds(layout.left(),layout.footerY(),layout.bodyWidth(),20).build());
        }
        public boolean isPauseScreen(){return false;}public void onClose(){minecraft.setScreen(parent);}
        public void tick(){if(privatePending!=0&&System.nanoTime()-privatePending>5_000_000_000L){privatePending=0;notice("切换未确认，请重试。");}}
        public void render(GuiGraphics g,int mx,int my,float pt){g.fill(0,0,width,height,DeviceUi.BG);var p=layout.panel();DeviceUi.panel(g,font,p.x(),p.y(),p.width(),p.height(),"MD · 本机游玩设置",privatePending!=0?"等待服务器确认":privatePreference?"下次：私人单人；不上传本机进度":"下次：公开 JNI 串流；允许旁观");g.drawWordWrap(font,Component.literal(saveStatus()),layout.left(),layout.statusY(),layout.bodyWidth(),DeviceUi.MUTED);super.render(g,mx,my,pt);}
    }
}
