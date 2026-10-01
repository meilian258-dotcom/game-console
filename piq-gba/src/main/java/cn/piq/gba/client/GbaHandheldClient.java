// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.gba.client;

import cn.piq.fcarcade.client.ClientArcadeEvents;
import cn.piq.fcarcade.client.ControllerCapture;
import cn.piq.fcarcade.client.cabinet.CabinetClientOwner;
import cn.piq.gba.GbaMod;
import cn.piq.gba.bridge.GbaProcessSession;
import cn.piq.gba.bridge.GbaJniSession;
import cn.piq.gba.bridge.GbaSession;
import cn.piq.gba.bridge.GbaSaveScope;
import cn.piq.retro.client.GamepadInput;
import cn.piq.retro.client.KeyboardConfig;
import cn.piq.retro.client.KeyboardInput;
import cn.piq.retro.input.InputOwnership;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.lwjgl.glfw.GLFW;
import java.nio.file.Path;
import java.util.UUID;
import cn.piq.gba.item.*;
import cn.piq.fcarcade.client.ContentCardClient;
import cn.piq.fcarcade.home.content.ContentCardData;
import cn.piq.fcarcade.home.content.ContentCardNetwork;

/** Personal held-item GBA, using the shared authorized cartridge transfer, never server paths. */
@EventBusSubscriber(modid=GbaMod.ID,value=Dist.CLIENT)
public final class GbaHandheldClient {
    @EventBusSubscriber(modid=GbaMod.ID,bus=EventBusSubscriber.Bus.MOD,value=Dist.CLIENT)
    public static final class Setup {
        @SubscribeEvent public static void setup(FMLClientSetupEvent event){
            event.enqueueWork(()->{
                ControllerCapture.registerRuntime(GbaHandheldClient::refreshInput);
                ContentCardClient.registerRuntime(GbaMod.BACKEND,new ContentCardClient.Runtime(){
                    public boolean accept(ContentCardNetwork.Message m){
                        var entry=pending==null?null:ContentCardData.read(GbaCartridgeSlot.card(pending.original),GbaMod.BACKEND);
                        return pending!=null&&m.token().equals(pendingToken)&&pending.current()&&entry!=null&&entry.hash().equals(m.hash())&&entry.size()==m.size();
                    }
                    public String start(ContentCardNetwork.Message m,Path rom){
                        var binding=pending;
                        var entry=binding==null?null:ContentCardData.read(GbaCartridgeSlot.card(binding.original),GbaMod.BACKEND);
                        if(binding==null||!m.token().equals(pendingToken)||!binding.current()||entry==null||!entry.hash().equals(m.hash())||entry.size()!=m.size())return "掌机开机已取消或卡带改变，请重新开机";
                        clearPending();
                        String helper=GbaBundledHelper.helper();
                        if(!GbaHandheldClient.start(binding,rom,helper))return "GBA 启动未完成，请查看运行环境";
                        play.token=m.token();return null;
                    }
                    public int state(ContentCardNetwork.Message m){return play==null||!m.token().equals(play.token)?-1:running()?1:0;}
                    public void stop(ContentCardNetwork.Message m){if(play!=null&&m.token().equals(play.token))GbaHandheldClient.stop(null);if(m.token().equals(pendingToken)){pending=null;pendingToken=null;}}
                });
            });
        }
    }
    static final class Binding {
        final GbaHandheldGate gate;
        final ItemStack original;
        final Object listener;
        final Path root,gameRoot;
        final GbaSaveScope scope;
        final InteractionHand hand;
        Binding(Minecraft mc,String context,InteractionHand hand){
            this.hand=hand;
            listener=mc.getConnection();gameRoot=mc.gameDirectory.toPath();root=cn.piq.retro.storage.ConsoleStorage.root(gameRoot).resolve("piq-gba").toAbsolutePath().normalize();
            original=mc.player.getItemInHand(hand).copy();
            gate=new GbaHandheldGate(mc.getConnection().getConnection(),mc.level,mc.player.getUUID(),hand==InteractionHand.MAIN_HAND?mc.player.getInventory().selected:0);
            scope=GbaSaveScope.of(context,mc.player.getUUID());
        }
        boolean current(){
            var mc=Minecraft.getInstance();
            if(mc.player==null||mc.level==null||mc.getConnection()==null||mc.getConnection()!=listener)return false;
            var held=mc.player.getItemInHand(hand);
            return gate.valid(mc.getConnection().getConnection(),mc.level,mc.player.getUUID(),hand==InteractionHand.MAIN_HAND?mc.player.getInventory().selected:0,
                    mc.getConnection().getConnection().isConnected(),mc.player.isAlive(),mc.player.isSpectator(),
                    handheld(held)&&ItemStack.isSameItemSameComponents(original,held),held.getCount());
        }
        GbaHandheldSelectionStore store(){return new GbaHandheldSelectionStore(root,scope);}
    }
    private static final class Play {
        final Object owner=new Object();
        final Binding binding;
        final Path rom;
        final int[][] legacy;
        GbaSession core;
        GbaHandheldAudio audio;
        DynamicTexture texture;
        ResourceLocation textureId;
        boolean focused,announced,discardAudio=true;
        int mask;
        UUID token;
        Play(Binding binding,Path rom){
            this.binding=binding;this.rom=rom;
            // Capture BEFORE attach: asking this API from ownerLegacy itself would recurse.
            legacy=KeyboardInput.displayLegacyKeys(KeyboardConfig.Profile.SFC).stream().map(key->new int[]{key}).toArray(int[][]::new);
        }
    }
    private static final GbaHandheldGate.UseGate USE=new GbaHandheldGate.UseGate();
    private static final GbaHandheldGate.UseGate ATTACK=new GbaHandheldGate.UseGate();
    private static Play play;
    private static GbaSession closingCore;
    private static Binding closingBinding;
    private static Object ejecting;
    private static Binding view,pending;
    private static boolean raised;
    private static long requested;
    private static UUID pendingNonce;
    private static UUID pendingToken;
    private static boolean shutdown;
    private GbaHandheldClient(){}
    private static boolean handheld(ItemStack stack){return !stack.isEmpty()&&stack.is(GbaMod.HANDHELD.get());}
    static Binding capture(){
        var mc=Minecraft.getInstance();
        InteractionHand hand=heldHand();
        if(shutdown||mc.player==null||mc.level==null||mc.getConnection()==null||!mc.getConnection().getConnection().isConnected()
                ||!mc.player.isAlive()||mc.player.isSpectator()||hand==null||mc.player.getItemInHand(hand).getCount()!=1)return null;
        // Same real context rules as CabinetGameSelection, without fabricating a cabinet/device Key.
        String context;
        if(mc.getSingleplayerServer()!=null)context="world:"+mc.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        else{var server=mc.getCurrentServer();if(server==null||server.ip==null||server.ip.isBlank())return null;context="server:"+server.ip.strip();}
        return new Binding(mc,context,hand);
    }
    private static InteractionHand heldHand(){var p=Minecraft.getInstance().player;if(p==null)return null;
        boolean main=handheld(p.getMainHandItem()),off=handheld(p.getOffhandItem());
        return main==off?null:main?InteractionHand.MAIN_HAND:InteractionHand.OFF_HAND;
    }
    private static boolean localUse(PlayerInteractEvent event){
        var mc=Minecraft.getInstance();return event.getLevel().isClientSide&&event.getEntity()==mc.player
                &&mc.player!=null&&heldHand()!=null;
    }
    @SubscribeEvent public static void useBlock(PlayerInteractEvent.RightClickBlock event){
        if(!localUse(event))return;
        event.setUseBlock(TriState.FALSE);event.setUseItem(TriState.FALSE);
        event.setCancellationResult(InteractionResult.SUCCESS);event.setCanceled(true);
        use();
    }
    @SubscribeEvent public static void useItem(PlayerInteractEvent.RightClickItem event){
        if(!localUse(event))return;
        event.setCancellationResult(InteractionResult.SUCCESS);event.setCanceled(true);
        use();
    }
    @SubscribeEvent public static void useEntity(PlayerInteractEvent.EntityInteract event){
        if(!localUse(event))return;
        event.setCancellationResult(InteractionResult.SUCCESS);event.setCanceled(true);
        use();
    }
    @SubscribeEvent public static void useEntityPart(PlayerInteractEvent.EntityInteractSpecific event){
        if(!localUse(event))return;
        event.setCancellationResult(InteractionResult.SUCCESS);event.setCanceled(true);
        use();
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void mouse(InputEvent.MouseButton.Pre event){
        var mc=Minecraft.getInstance();
        if(event.isCanceled()||mc.screen!=null||mc.player==null||!mc.isWindowActive()||heldHand()==null)return;
        boolean attack=mc.options.keyAttack.matchesMouse(event.getButton())&&shiftDown();
        boolean use=mc.options.keyUse.matchesMouse(event.getButton());
        if(!attack&&!use)return;
        // Run before the shared gameplay router: its position lock intentionally
        // clears vanilla Shift and may capture mouse mappings while the GBA is raised.
        event.setCanceled(true);
        if(attack){
            mc.options.keyAttack.setDown(false);if(mc.gameMode!=null)mc.gameMode.stopDestroyBlock();
            if(event.getAction()==GLFW.GLFW_PRESS&&ATTACK.press())request(GbaHandheldNetwork.EJECT);
            if(event.getAction()==GLFW.GLFW_RELEASE)ATTACK.observe(false);
        }else{
            mc.options.keyUse.setDown(false);
            if(event.getAction()==GLFW.GLFW_PRESS)use();
            if(event.getAction()==GLFW.GLFW_RELEASE)USE.observe(false);
        }
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void attack(InputEvent.InteractionKeyMappingTriggered event){
        var mc=Minecraft.getInstance();
        if(!event.isAttack()||mc.screen!=null||mc.player==null||!mc.isWindowActive()
                ||!shiftDown()||heldHand()==null)return;
        // Same early interception as FC cartridge disassembly: covers air, blocks and
        // entities before vanilla attack/mining. Do not let a held press mine next tick.
        event.setCanceled(true);event.setSwingHand(false);mc.options.keyAttack.setDown(false);
        if(mc.gameMode!=null)mc.gameMode.stopDestroyBlock();
        if(ATTACK.press())request(GbaHandheldNetwork.EJECT);
    }
    private static void use(){
        var mc=Minecraft.getInstance();if(mc.screen!=null||!mc.isWindowActive()||!USE.press())return;
        try{
            var binding=capture();if(binding==null){notice("当前玩家或世界不可用，无法打开掌机");return;}
            var other=binding.hand==InteractionHand.MAIN_HAND?InteractionHand.OFF_HAND:InteractionHand.MAIN_HAND;
            var action=GbaHandheldGate.useAction(mc.player.getItemInHand(other).is(GbaMod.CARTRIDGE.get()),shiftDown());
            if(action==GbaHandheldGate.UseAction.INSERT){request(GbaHandheldNetwork.INSERT);return;}
            if(action==GbaHandheldGate.UseAction.POWER){request(GbaHandheldNetwork.POWER);return;}
            if(view==null||!view.current()){view=binding;raised=false;}
            raised=!raised;refreshInput();
            if(!raised&&play!=null)releaseInput(play);
            notice(raised?"已举起 GBA；Shift＋左键拔卡；无待插卡时 Shift＋右键开关机":"已放下 GBA；游戏继续运行；Shift＋左键拔卡");
        }catch(RuntimeException failure){cn.piq.fcarcade.client.ui.DeviceNotices.record("GBA","掌机不可用："+failure.getMessage(),failure);}
    }
    static void request(int action){
        var b=capture();if(b==null)return;
        if(ejecting!=null){notice("正在关闭并保存，请等退卡结果后再操作");return;}
        if(action==GbaHandheldNetwork.EJECT){eject(b);return;}
        var nonce=UUID.randomUUID();
        if(action==GbaHandheldNetwork.POWER){
            if(play!=null||pending!=null){action=GbaHandheldNetwork.OFF;clearPending();stop("掌机已关闭；正在保存电池存档");}
            else {clearPending();pending=b;pendingNonce=nonce;requested=System.nanoTime();}
        }
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(new GbaHandheldNetwork.Request(action,b.hand,GbaCartridgeSlot.id(b.original),nonce));
    }
    private static void eject(Binding binding){
        if(GbaCartridgeSlot.card(binding.original).isEmpty()){notice("掌机未插卡");return;}
        var lastBinding=play==null?closingBinding:play.binding;
        // An unrelated old session's save error must not lock a different handheld.
        // IDs and full components travel with the machine when moved between hands.
        var core=lastBinding!=null&&lastBinding.listener==binding.listener
                &&ItemStack.isSameItemSameComponents(lastBinding.original,binding.original)?play==null?closingCore:play.core:null;
        clearPending();raised=false;view=null;
        Object attempt=new Object();ejecting=attempt;
        try{stop(null);}catch(RuntimeException|LinkageError failure){
            // Still perform the bounded close check below; presentation cleanup must
            // never be taken as evidence that a save completed.
            cn.piq.fcarcade.client.ui.DeviceNotices.record("GBA","退卡前清理异常",failure);
        }
        notice("正在关闭掌机并保存电池存档，完成后退出卡带");
        Thread.ofPlatform().daemon(true).name("PIQ-GBA-safe-eject").start(()->{
            var result=GbaSafeEject.finish(core);
            Minecraft.getInstance().execute(()->{
                if(ejecting!=attempt)return;
                ejecting=null;
                if(shutdown||!binding.current())return; // never target another slot/world/connection
                if(!result.safe()){notice("未退出卡带："+result.failure()+"；原卡仍在掌机中");return;}
                if(closingCore==core){closingCore=null;closingBinding=null;}
                net.neoforged.neoforge.network.PacketDistributor.sendToServer(new GbaHandheldNetwork.Request(
                        GbaHandheldNetwork.EJECT,binding.hand,GbaCartridgeSlot.id(binding.original),UUID.randomUUID()));
            });
        });
    }
    private static void clearPending(){pending=null;pendingNonce=null;pendingToken=null;}
    public static void reply(GbaHandheldNetwork.Reply r){if(r.nonce().equals(pendingNonce)){if(r.starting())pendingToken=r.token();else clearPending();}}
    @SubscribeEvent public static void commands(RegisterClientCommandsEvent e){e.getDispatcher().register(net.minecraft.commands.Commands.literal("gameconsole-gba").executes(c->{openSettings=true;return 1;}));}
    private static boolean openSettings;
    static void settings(){var b=capture();if(b!=null)Minecraft.getInstance().setScreen(new GbaHandheldScreen(b,false));else notice("请先手持一台 GBA");}
    private static final class GbaBundledHelper {
        private static String helper(){try(var in=GbaHandheldClient.class.getResourceAsStream("/piq-gba-runtime.properties")){var p=new java.util.Properties();if(in==null)throw new IllegalStateException("缺少 GBA 固定清单");p.load(in);String h=p.getProperty("helper.sha256");if(h==null||!h.matches("[A-Fa-f0-9]{64}"))throw new IllegalStateException("helper SHA 无效");return h;}catch(java.io.IOException e){throw new IllegalStateException(e);}}
    }
    static boolean start(Binding binding,Path rom,String helperSha){
        if(shutdown||play!=null||!binding.current()){notice("请先关闭当前掌机，或重新手持掌机打开设置");return false;}
        if(GbaProcessSession.active()||GbaJniSession.active()){notice("本机 GBA 正在运行或保存退出，请稍后重试");return false;}
        var next=new Play(binding,rom);
        if(ClientArcadeEvents.isControlling()){notice("请先退出其它模拟器的控制席位");return false;}
        try{
            // The existing bridge starts its own bounded asynchronous worker and rechecks all runtime hashes.
            next.core=GbaJniChoice.enabled()?new GbaJniSession(rom,binding.scope.resolve(binding.root.resolve("saves")))
                    :new GbaProcessSession(binding.root.resolve("runtime"),rom,binding.scope.resolve(binding.root.resolve("saves")),helperSha);
            next.audio=new GbaHandheldAudio();play=next;refreshInput();if(play!=next)return false;
            Minecraft.getInstance().setScreen(null);notice(GbaJniChoice.enabled()?"正在启动 GBA JNI；使用本机独立试验电池档":"正在启动 GBA；游戏、电池存档仅在本机");return true;
        }catch(Exception failure){
            if(next.core!=null)next.core.close();if(next.audio!=null)next.audio.close();
            KeyboardInput.release(next.owner);GamepadInput.release(next.owner);CabinetClientOwner.release(next.owner);
            if(play==next)play=null;cn.piq.fcarcade.client.ui.DeviceNotices.record("GBA","GBA 启动失败："+failure.getMessage(),failure);return false;
        }
    }
    private static boolean valid(Play value){return play==value&&value.binding.current();}
    private static boolean ready(Play value){return valid(value)&&value.core!=null&&value.core.isReady()&&value.core.error()==null;}
    private static void refreshInput(){
        var value=play;if(value==null)return;
        if(!valid(value)){stop("掌机已退出：手持物品、玩家或连接发生变化");return;}
        if(!raised||view==null||!view.current()){releaseInput(value);return;}
        if(ClientArcadeEvents.isControlling()||!CabinetClientOwner.acquire(value.owner)){raised=false;releaseInput(value);return;}
        KeyboardInput.attach(value.owner,KeyboardConfig.Profile.SFC,()->value.legacy,()->valid(value)&&raised,()->ready(value),
                ()->clear(value),GbaHandheldClient::input);
    }
    private static void releaseInput(Play value){clear(value);KeyboardInput.release(value.owner);GamepadInput.release(value.owner);CabinetClientOwner.release(value.owner);value.focused=false;}
    private static void clear(Play value){
        value.mask=0;GamepadInput.pause(value.owner);if(value.core!=null)value.core.clearInput();
    }
    private static void input(){
        var value=play;if(value==null)return;
        if(!valid(value)){stop("掌机已退出：手持物品、玩家或连接发生变化");return;}
        var mc=Minecraft.getInstance();boolean focused=raised&&InputOwnership.owns(value.owner)&&mc.screen==null&&mc.isWindowActive()&&!mc.isPaused();
        if(!focused){KeyboardInput.pause(value.owner);clear(value);if(value.focused)value.audio.silence();value.focused=false;return;}
        if(!value.focused){value.audio.silence();value.discardAudio=true;}
        value.focused=true;
        int mouseFallback=0;long window=mc.getWindow().getWindow();
        for(int bit=0;bit<value.legacy.length;bit++)for(int key:value.legacy[bit])
            if(key<=-1000&&GLFW.glfwGetMouseButton(window,-1000-key)==GLFW.GLFW_PRESS)mouseFallback|=1<<bit;
        var sample=KeyboardInput.poll(value.owner,mouseFallback,ready(value));
        if(play!=value)return;
        if(!sample.enabled()||!sample.armed()){clear(value);return;}
        int mask=GbaHandheldGate.input(GamepadInput.mix(value.owner,GamepadInput.ProfileKind.SFC,sample.mask(),true));
        if(play==value&&ready(value)){value.mask=mask;value.core.offerInput(mask);}
    }
    private static void pump(){
        var value=play;if(value==null)return;
        if(!valid(value)){stop("掌机已退出：手持物品、玩家或连接发生变化");return;}
        if(value.core.error()!=null){stop("GBA 已退出："+value.core.error());return;}
        input();if(play!=value)return;
        var mc=Minecraft.getInstance();
        float gain=value.focused?.6F*mc.options.getSoundSourceVolume(SoundSource.MASTER)*mc.options.getSoundSourceVolume(SoundSource.BLOCKS):0;
        value.audio.gain(gain);
        var frame=value.core.pollFrame();
        if(frame!=null){
            if(frame.abgr().length!=240*160)throw new IllegalStateException("GBA frame dimensions");
            if(value.texture==null){
                value.texture=new DynamicTexture(240,160,false);value.texture.setFilter(false,false);
                value.textureId=ResourceLocation.fromNamespaceAndPath(GbaMod.ID,"personal_handheld_screen");
                mc.getTextureManager().register(value.textureId,value.texture);
            }
            var pixels=value.texture.getPixels();
            for(int y=0;y<160;y++)for(int x=0;x<240;x++)pixels.setPixelRGBA(x,y,frame.abgr()[y*240+x]|0xff000000);
            value.texture.upload();
            // Even when unfocused, drain the core mailbox and discard PCM, preventing resume bursts.
            if(value.focused&&!value.discardAudio)value.audio.offer(frame.pcm48k());
            value.discardAudio=!value.focused;
        }
        if(!value.announced&&value.core.isReady()){value.announced=true;}
    }
    @SubscribeEvent public static void tick(ClientTickEvent.Post event){
        while(GbaHandheldKeys.SETTINGS.consumeClick())openSettings=true;
        if(openSettings){openSettings=false;settings();}
        if(view!=null&&!view.current()){view=null;raised=false;}
        if(pending!=null&&(!pending.current()||System.nanoTime()-requested>120_000_000_000L))clearPending();
        USE.observe(useDown());ATTACK.observe(keyDown(Minecraft.getInstance().options.keyAttack));safePump();
    }
    @SubscribeEvent public static void frame(RenderFrameEvent.Pre event){safePump();}
    @SubscribeEvent public static void screen(ScreenEvent.Opening event){
        var value=play;if(value!=null&&event.getNewScreen()!=null){KeyboardInput.pause(value.owner);clear(value);value.audio.silence();value.focused=false;}
    }
    @SubscribeEvent public static void shutdown(GameShuttingDownEvent event){shutdown=true;try{stop(null);}finally{GbaProcessSession.shutdown();GbaJniSession.shutdown();}}
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut e){clearPending();ejecting=null;view=null;raised=false;openSettings=false;stop(null);}
    private static void safePump(){try{pump();}catch(RuntimeException|LinkageError failure){fail("掌机输出异常："+failure.getMessage(),failure);}}
    private static boolean useDown(){
        return keyDown(Minecraft.getInstance().options.keyUse);
    }
    private static boolean shiftDown(){var mc=Minecraft.getInstance();return keyDown(mc.options.keyShift)||(mc.player!=null&&mc.player.isShiftKeyDown());}
    private static boolean keyDown(net.minecraft.client.KeyMapping mapping){
        var mc=Minecraft.getInstance();var key=mapping.getKey();long window=mc.getWindow().getWindow();
        if(key.getType()==InputConstants.Type.MOUSE)return GLFW.glfwGetMouseButton(window,key.getValue())==GLFW.GLFW_PRESS;
        if(key.getType()==InputConstants.Type.KEYSYM)return InputConstants.isKeyDown(window,key.getValue());
        return mapping.isDown();
    }
    static void stop(String reason){
        var value=play;if(value==null)return;play=null;
        // Schedule SRAM shutdown before optional input/texture cleanup can throw.
        closingCore=value.core;closingBinding=value.binding;
        value.core.close();value.audio.close();
        KeyboardInput.release(value.owner);clear(value);GamepadInput.release(value.owner);CabinetClientOwner.release(value.owner);
        if(value.textureId!=null)Minecraft.getInstance().getTextureManager().release(value.textureId);
        else if(value.texture!=null)value.texture.close();
        notice(reason);
    }
    public static boolean running(){var value=play;return value!=null&&ready(value);}
    public static boolean visualMatches(ItemStack stack){var mc=Minecraft.getInstance();return running()&&mc.player!=null&&stack==mc.player.getItemInHand(play.binding.hand);}
    public static boolean raised(ItemStack stack){return raised&&view!=null&&view.current()&&stack==Minecraft.getInstance().player.getItemInHand(view.hand);}
    public static ResourceLocation screenTexture(){return running()?play.textureId:null;}
    public static int visualInputMask(){return running()?play.mask:0;}
    static boolean openingOrRunning(){return play!=null||pending!=null||ejecting!=null;}
    static Path currentRom(){return play==null?null:play.rom;}
    private static void fail(String reason,Throwable failure){cn.piq.fcarcade.client.ui.DeviceNotices.record("GBA",reason,failure);stop(null);}
    static void notice(String text){cn.piq.fcarcade.client.ui.DeviceNoticesClient.message("GBA",text);}
}
