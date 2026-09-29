// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.gba.client;

import cn.piq.fcarcade.cabinet.CabinetEmulator;
import cn.piq.fcarcade.cabinet.CabinetFrame;
import cn.piq.fcarcade.cabinet.CabinetRomBindings;
import cn.piq.fcarcade.client.cabinet.CabinetBackend;
import cn.piq.fcarcade.client.cabinet.CabinetClientBackends;
import cn.piq.fcarcade.client.cabinet.CabinetGameSelection;
import cn.piq.gba.GbaMod;
import cn.piq.gba.bridge.GbaProcessSession;
import cn.piq.gba.bridge.GbaSession;
import cn.piq.gba.bridge.GbaJniSession;
import cn.piq.gba.bridge.GbaSaveScope;
import cn.piq.retro.api.RetroEmulatorFactory;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Reuses FC's approved cabinet lease/connection/owner/input/GUI; never opens a second core for a viewer. */
public final class GbaCabinetBackend implements CabinetBackend {
    @EventBusSubscriber(modid=GbaMod.ID,bus=EventBusSubscriber.Bus.MOD,value=Dist.CLIENT)
    public static final class Setup {
        @SubscribeEvent public static void setup(FMLClientSetupEvent event){event.enqueueWork(()->CabinetClientBackends.register(GbaMod.BACKEND,new GbaCabinetBackend()));}
    }
    private static Path root(){return cn.piq.retro.storage.ConsoleStorage.root(Minecraft.getInstance().gameDirectory.toPath()).resolve("piq-gba");}
    @Override public String description(){return "GBA 单席街机：支持服务器游玩和附近旁观；游戏与电池存档在本机，按服务器/世界及玩家隔离。也可使用独立 GBA 掌机物品游玩；掌机不广播画面，不支持GBA通讯线或多人同局。";}
    @Override public String extensions(){return ".gba";}
    @Override public Path romDirectory(){return root().resolve("roms");}
    @Override public Set<String> romExtensions(){return Set.of(".gba");}
    @Override public Set<String> romExcludedNames(){return Set.of("gba_bios.bin");}
    @Override public String unavailableReason(){
        if(!System.getProperty("os.name","").startsWith("Windows")||!Set.of("amd64","x86_64").contains(System.getProperty("os.arch","")))return "GBA 试玩当前仅支持 Windows x64";
        if(!GbaJniChoice.enabled())for(String name:List.of("mgba_libretro.dll","piq-gba-helper.jar","jna-5.14.0.jar"))if(!Files.isRegularFile(root().resolve("runtime").resolve(name),LinkOption.NOFOLLOW_LINKS))return "缺少 GBA 配套运行库："+name;
        if(GbaJniChoice.enabled()&&cn.piq.retro.libretro.LibretroRuntimes.isJniBusy())return "已有 JNI 试验正在运行或尚未安全退出";
        return GbaProcessSession.active()||GbaJniSession.active()?"GBA 正在运行或保存退出，请稍后再试":null;
    }
    /** Capture the exact connection and save owner before the asynchronous starter runs. */
    @Override public RetroEmulatorFactory prepareFactory(RetroEmulatorFactory registered,CabinetRomBindings.Key selection,UUID playerId,Connection connection)throws Exception {
        Minecraft mc=Minecraft.getInstance();
        if(!mc.isSameThread())throw new IOException("GBA launch context must be captured on the client thread");
        Objects.requireNonNull(registered,"registered GBA factory");
        if(selection==null||playerId==null||connection==null||!connection.isConnected()||mc.getConnection()==null
                ||mc.getConnection().getConnection()!=connection||mc.level==null||mc.player==null
                ||!mc.player.getUUID().equals(playerId)||!GbaMod.BACKEND.toString().equals(selection.backend()))
            throw new IOException("GBA current player/connection context is unavailable");
        CabinetRomBindings.Key current=CabinetGameSelection.key(mc.level.dimension().location(),selection.device(),GbaMod.BACKEND).orElse(null);
        if(!selection.equals(current))throw new IOException("GBA cabinet context changed before launch");
        Path base=cn.piq.retro.storage.ConsoleStorage.root(mc.gameDirectory.toPath()).resolve("piq-gba").toAbsolutePath().normalize();
        Launch launch=new Launch(base.resolve("runtime"),GbaSaveScope.of(selection.context(),playerId).resolve(base.resolve("saves")),connection,Thread.currentThread(),GbaJniChoice.enabled());
        return rom->openScoped(rom,launch).asRetro();
    }
    /** Old callers cannot silently write into a cross-server fallback namespace. */
    @Override public CabinetEmulator open(Path rom)throws Exception {
        throw new IOException("GBA requires a current cabinet launch context; open through the FC cabinet");
    }
    private record Launch(Path runtime,Path saves,Connection connection,Thread clientThread,boolean jni){}
    private static boolean connected(GbaSession core,Launch launch){
        if(launch.connection().isConnected())return true;
        core.clearInput();core.close();return false;
    }
    /** No Minecraft singleton, world/player lookup or server-provided path on the worker. */
    private static CabinetEmulator openScoped(Path rom,Launch launch)throws Exception {
        if(Thread.currentThread()==launch.clientThread())throw new IOException("GBA core startup must run off the client thread");
        if(!launch.connection().isConnected())throw new IOException("GBA connection closed before launch");
        Properties lock=new Properties();try(var in=GbaCabinetBackend.class.getResourceAsStream("/piq-gba-runtime.properties")){if(in==null)throw new IOException("GBA runtime lock missing");lock.load(in);}
        GbaSession core=launch.jni()?new GbaJniSession(rom,launch.saves()):new GbaProcessSession(launch.runtime(),rom,launch.saves(),lock.getProperty("helper.sha256"));
        if(!connected(core,launch))throw new IOException("GBA connection closed during launch");
        return new CabinetEmulator(){
            @Override public int maxPlayers(){return 1;}
            @Override public boolean isReady(){return connected(core,launch)&&core.isReady();}
            @Override public String error(){return connected(core,launch)?core.error():"GBA connection closed";}
            @Override public void offerInput(int p1,int p2){if(p2!=0)throw new IllegalArgumentException("GBA is single-player");if(connected(core,launch))core.offerInput(p1&0xDFD);}
            @Override public void releasePort(int port){if(port!=0)throw new IllegalArgumentException("GBA has one input port");core.clearInput();}
            @Override public void clearInput(){core.clearInput();}
            @Override public CabinetFrame pollFrame(){if(!connected(core,launch))return null;var f=core.pollFrame();return f==null?null:new CabinetFrame(240,160,f.abgr(),1.5F,0,f.pcm48k());}
            @Override public void close(){core.close();}
        };
    }
}
