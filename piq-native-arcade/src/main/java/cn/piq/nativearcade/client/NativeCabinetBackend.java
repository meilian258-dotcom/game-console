// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.nativearcade.client;

import cn.piq.fcarcade.cabinet.CabinetEmulator;
import cn.piq.fcarcade.cabinet.CabinetFrame;
import cn.piq.fcarcade.client.cabinet.CabinetBackend;
import cn.piq.fcarcade.client.rom.LocalRomLibrary;
import cn.piq.nativearcade.bridge.NativeProcessSession;
import cn.piq.nativearcade.bridge.NativeJniMediaSession;
import cn.piq.nativearcade.NativeSnapshotProfile;
import cn.piq.fcarcade.cabinet.CabinetRomBindings;
import cn.piq.fcarcade.cabinet.CabinetSyncCore;
import net.minecraft.network.Connection;
import net.minecraft.client.Minecraft;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** FC owns cabinet/GUI/leases. Media uses common JNI; legacy snapshot sync retains its verified worker. */
public final class NativeCabinetBackend implements CabinetBackend {
    @Override public String description(){return "JNI Netplay 使用可精确恢复的 FBNeo 游戏；普通音画使用 MAME JNI。旧本地同步仍为兼容后端";}
    @Override public String extensions(){return ".zip（如 dino.zip、kov.zip；独立完整 ROM 集，BIOS 放同目录）";}
    @Override public Path defaultRom(){return NativeArcadeClient.dataRoot().resolve("diagnostic/invaders.zip");}
    @Override public Path romDirectory(){return LocalRomLibrary.arcadeDirectory(Minecraft.getInstance().gameDirectory.toPath());}
    @Override public Set<String> romExtensions(){return Set.of(".zip");}
    @Override public Set<String> romExcludedNames(){return cn.piq.fcarcade.cabinet.CabinetGameManifest.BIOS;}
    @Override public String unavailableReason(){
        return NativeJniMediaSession.unavailableReason();
    }
    @Override public String syncUnavailableReason(){
        if(!System.getProperty("os.name","").startsWith("Windows")||!System.getProperty("os.arch","").matches("amd64|x86_64"))
            return "街机本地输入同步当前仅支持 Windows x64";
        Path runtime=NativeArcadeClient.dataRoot().resolve(NativeSnapshotProfile.DIRECTORY);
        for(String file:List.of(NativeSnapshotProfile.CORE_NAME,NativeSnapshotProfile.HELPER_NAME,NativeSnapshotProfile.JNA_NAME))
            if(!Files.isRegularFile(runtime.resolve(file),LinkOption.NOFOLLOW_LINKS))return "缺少本地同步运行库，请查看运行环境设置："+file;
        return NativeProcessSession.hasLiveSession()?"已有原生街机会话运行或正在关闭":null;
    }
    @Override public SyncFactory prepareSyncFactory(CabinetRomBindings.Key selection,UUID playerId,Connection connection){
        Objects.requireNonNull(selection);Objects.requireNonNull(playerId);Objects.requireNonNull(connection);
        // Called on the client thread. The worker never reads a later Minecraft instance/world.
        Path runtime=NativeArcadeClient.dataRoot().resolve(NativeSnapshotProfile.DIRECTORY).toAbsolutePath().normalize();
        return new SnapshotFactory(runtime);
    }
    @Override public String netplayUnavailableReason(){return System.getProperty("os.name","").startsWith("Windows")&&System.getProperty("os.arch","").matches("amd64|x86_64")?null:"Netplay 实验当前仅支持 Windows x64";}
    @Override public NetplayFactory prepareNetplayFactory(){
        return NativeNetplayContent::loadFiles;
    }
    @Override public CabinetSyncCore openSync(Path rom)throws Exception{
        throw new java.io.IOException("本地同步必须使用主线程捕获的开局上下文");
    }
    /** Named class deliberately leaves the existing media anonymous class identity unchanged. */
    private static final class SnapshotFactory implements SyncFactory{
        private final Path runtime;
        private final AtomicBoolean cancelled=new AtomicBoolean(),opened=new AtomicBoolean();
        private final AtomicReference<Runnable> signal=new AtomicReference<>();
        SnapshotFactory(Path runtime){this.runtime=runtime;}
        @Override public CabinetSyncCore open(Path rom)throws Exception{
            if(cancelled.get()||!opened.compareAndSet(false,true))throw new java.io.IOException("本地同步开局已取消或已使用");
            return new NativeSnapshotCore(runtime,rom,closer->{
                signal.set(closer);if(cancelled.get())closer.run();
            });
        }
        @Override public void requestClose(){cancelled.set(true);Runnable closer=signal.get();if(closer!=null)closer.run();}
    }
    @Override public CabinetEmulator open(Path rom)throws Exception{
        NativeJniMediaSession core=new NativeJniMediaSession(NativeArcadeClient.dataRoot().resolve("runtime"),rom);
        return new CabinetEmulator(){
            @Override public int maxPlayers(){return core.maxPlayers();}
            @Override public boolean isReady(){return core.isReady();}
            @Override public String error(){return core.error();}
            @Override public void offerInput(int p1,int p2){core.offerInput(p1,p2);}
            @Override public void offerInputs(int p1,int p2,int p3,int p4){core.offerInputs(p1,p2,p3,p4);}
            @Override public void releasePort(int port){core.releasePort(port);}
            @Override public boolean supportsCoinPreservingRelease(){return true;}
            @Override public void releaseGameplayPortKeepingCoin(int port){core.releaseGameplayPortKeepingCoin(port);}
            @Override public void clearInput(){core.clearInput();}
            @Override public CabinetFrame pollFrame(){
                var f=core.pollFrame();return f==null?null:new CabinetFrame(f.width(),f.height(),f.abgr(),f.displayAspect(),f.rotation(),f.pcm48k());
            }
            @Override public void close(){core.clearInput();core.close();}
        };
    }
}
