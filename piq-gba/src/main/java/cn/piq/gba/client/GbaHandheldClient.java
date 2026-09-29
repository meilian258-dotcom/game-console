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
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.lwjgl.glfw.GLFW;
import java.nio.file.Path;

/** Personal main-hand GBA. No cabinet/network lease, ROM upload or server-supplied path. */
@EventBusSubscriber(modid=GbaMod.ID,value=Dist.CLIENT)
public final class GbaHandheldClient {
    @EventBusSubscriber(modid=GbaMod.ID,bus=EventBusSubscriber.Bus.MOD,value=Dist.CLIENT)
    public static final class Setup {
        @SubscribeEvent public static void setup(FMLClientSetupEvent event){
            event.enqueueWork(()->ControllerCapture.registerRuntime(GbaHandheldClient::refreshInput));
        }
    }
    static final class Binding {
        final GbaHandheldGate gate;
        final ItemStack original;
        final Object listener;
        final Path root,gameRoot;
        final GbaSaveScope scope;
        Binding(Minecraft mc,String context){
            listener=mc.getConnection();gameRoot=mc.gameDirectory.toPath();root=cn.piq.retro.storage.ConsoleStorage.root(gameRoot).resolve("piq-gba").toAbsolutePath().normalize();
            original=mc.player.getMainHandItem().copy();
            gate=new GbaHandheldGate(mc.getConnection().getConnection(),mc.level,mc.player.getUUID(),mc.player.getInventory().selected);
            scope=GbaSaveScope.of(context,mc.player.getUUID());
        }
        boolean current(){
            var mc=Minecraft.getInstance();
            if(mc.player==null||mc.level==null||mc.getConnection()==null||mc.getConnection()!=listener)return false;
            var held=mc.player.getMainHandItem();
            return gate.valid(mc.getConnection().getConnection(),mc.level,mc.player.getUUID(),mc.player.getInventory().selected,
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
        Play(Binding binding,Path rom){
            this.binding=binding;this.rom=rom;
            // Capture BEFORE attach: asking this API from ownerLegacy itself would recurse.
            legacy=KeyboardInput.displayLegacyKeys(KeyboardConfig.Profile.SFC).stream().map(key->new int[]{key}).toArray(int[][]::new);
        }
    }
    private static final GbaHandheldGate.UseGate USE=new GbaHandheldGate.UseGate();
    private static Play play;
    private static boolean shutdown;
    private GbaHandheldClient(){}
    private static boolean handheld(ItemStack stack){return !stack.isEmpty()&&stack.is(GbaMod.HANDHELD.get());}
    private static Binding capture(){
        var mc=Minecraft.getInstance();
        if(shutdown||mc.player==null||mc.level==null||mc.getConnection()==null||!mc.getConnection().getConnection().isConnected()
                ||!mc.player.isAlive()||mc.player.isSpectator()||!handheld(mc.player.getMainHandItem())||mc.player.getMainHandItem().getCount()!=1)return null;
        // Same real context rules as CabinetGameSelection, without fabricating a cabinet/device Key.
        String context;
        if(mc.getSingleplayerServer()!=null)context="world:"+mc.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        else{var server=mc.getCurrentServer();if(server==null||server.ip==null||server.ip.isBlank())return null;context="server:"+server.ip.strip();}
        return new Binding(mc,context);
    }
    private static boolean localUse(PlayerInteractEvent event){
        var mc=Minecraft.getInstance();return event.getLevel().isClientSide&&event.getEntity()==mc.player
                &&mc.player!=null&&handheld(mc.player.getMainHandItem());
    }
    @SubscribeEvent public static void useBlock(PlayerInteractEvent.RightClickBlock event){
        if(!localUse(event))return;
        event.setUseBlock(TriState.FALSE);event.setUseItem(TriState.FALSE);
        event.setCancellationResult(InteractionResult.SUCCESS);event.setCanceled(true);
        if(event.getHand()==InteractionHand.MAIN_HAND)use();
    }
    @SubscribeEvent public static void useItem(PlayerInteractEvent.RightClickItem event){
        if(!localUse(event))return;
        event.setCancellationResult(InteractionResult.SUCCESS);event.setCanceled(true);
        if(event.getHand()==InteractionHand.MAIN_HAND)use();
    }
    @SubscribeEvent public static void useEntity(PlayerInteractEvent.EntityInteract event){
        if(!localUse(event))return;
        event.setCancellationResult(InteractionResult.SUCCESS);event.setCanceled(true);
        if(event.getHand()==InteractionHand.MAIN_HAND)use();
    }
    @SubscribeEvent public static void useEntityPart(PlayerInteractEvent.EntityInteractSpecific event){
        if(!localUse(event))return;
        event.setCancellationResult(InteractionResult.SUCCESS);event.setCanceled(true);
        if(event.getHand()==InteractionHand.MAIN_HAND)use();
    }
    private static void use(){
        var mc=Minecraft.getInstance();if(mc.screen!=null||!mc.isWindowActive()||!USE.press())return;
        try{
            if(play!=null&&!mc.player.isShiftKeyDown()){stop("掌机已关闭；电池存档正在本机后台收尾");return;}
            var binding=capture();if(binding==null){notice("当前玩家或世界不可用，无法打开掌机");return;}
            mc.setScreen(new GbaHandheldScreen(binding,play==null&&!mc.player.isShiftKeyDown()));
        }catch(RuntimeException failure){cn.piq.fcarcade.client.ui.DeviceNotices.record("GBA","掌机不可用："+failure.getMessage(),failure);}
    }
    static boolean start(Binding binding,Path rom,String helperSha){
        if(shutdown||play!=null||!binding.current()){notice("请先关闭当前掌机，或重新手持掌机打开设置");return false;}
        if(GbaProcessSession.active()||GbaJniSession.active()){notice("本机 GBA 正在运行或保存退出，请稍后重试");return false;}
        var next=new Play(binding,rom);
        if(ClientArcadeEvents.isControlling()||!CabinetClientOwner.acquire(next.owner)){notice("请先退出其它模拟器的控制席位");return false;}
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
    private static boolean valid(Play value){return play==value&&value.binding.current()&&InputOwnership.owns(value.owner)&&!ClientArcadeEvents.isControlling();}
    private static boolean ready(Play value){return valid(value)&&value.core!=null&&value.core.isReady()&&value.core.error()==null;}
    private static void refreshInput(){
        var value=play;if(value==null)return;
        if(!valid(value)){stop("掌机已退出：手持物品、玩家或连接发生变化");return;}
        KeyboardInput.attach(value.owner,KeyboardConfig.Profile.SFC,()->value.legacy,()->valid(value),()->ready(value),
                ()->clear(value),GbaHandheldClient::input);
    }
    private static void clear(Play value){
        value.mask=0;GamepadInput.pause(value.owner);if(value.core!=null)value.core.clearInput();
    }
    private static void input(){
        var value=play;if(value==null)return;
        if(!valid(value)){stop("掌机已退出：手持物品、玩家或连接发生变化");return;}
        var mc=Minecraft.getInstance();boolean focused=mc.screen==null&&mc.isWindowActive()&&!mc.isPaused();
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
        USE.observe(useDown());safePump();
    }
    @SubscribeEvent public static void frame(RenderFrameEvent.Pre event){safePump();}
    @SubscribeEvent public static void screen(ScreenEvent.Opening event){
        var value=play;if(value!=null&&event.getNewScreen()!=null){KeyboardInput.pause(value.owner);clear(value);value.audio.silence();value.focused=false;}
    }
    @SubscribeEvent public static void shutdown(GameShuttingDownEvent event){shutdown=true;try{stop(null);}finally{GbaProcessSession.shutdown();GbaJniSession.shutdown();}}
    private static void safePump(){try{pump();}catch(RuntimeException|LinkageError failure){fail("掌机输出异常："+failure.getMessage(),failure);}}
    private static boolean useDown(){
        var mc=Minecraft.getInstance();var key=mc.options.keyUse.getKey();long window=mc.getWindow().getWindow();
        if(key.getType()==InputConstants.Type.MOUSE)return GLFW.glfwGetMouseButton(window,key.getValue())==GLFW.GLFW_PRESS;
        if(key.getType()==InputConstants.Type.KEYSYM)return InputConstants.isKeyDown(window,key.getValue());
        return mc.options.keyUse.isDown();
    }
    static void stop(String reason){
        var value=play;if(value==null)return;play=null;
        // Schedule SRAM shutdown before optional input/texture cleanup can throw.
        value.core.close();value.audio.close();
        KeyboardInput.release(value.owner);clear(value);GamepadInput.release(value.owner);CabinetClientOwner.release(value.owner);
        if(value.textureId!=null)Minecraft.getInstance().getTextureManager().release(value.textureId);
        else if(value.texture!=null)value.texture.close();
        notice(reason);
    }
    public static boolean running(){var value=play;return value!=null&&ready(value);}
    public static boolean visualMatches(ItemStack stack){var mc=Minecraft.getInstance();return running()&&mc.player!=null&&stack==mc.player.getMainHandItem();}
    public static ResourceLocation screenTexture(){return running()?play.textureId:null;}
    public static int visualInputMask(){return running()?play.mask:0;}
    static boolean openingOrRunning(){return play!=null;}
    static Path currentRom(){return play==null?null:play.rom;}
    private static void fail(String reason,Throwable failure){cn.piq.fcarcade.client.ui.DeviceNotices.record("GBA",reason,failure);stop(null);}
    static void notice(String text){cn.piq.fcarcade.client.ui.DeviceNoticesClient.message("GBA",text);}
}
