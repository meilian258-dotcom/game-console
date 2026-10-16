// PIQ-authored code: additional permission in piq-fc-arcade/MD-ARCADE-LINKING-EXCEPTION.txt.
// PIQ-authored code: additional permission in piq-fc-arcade/SNES9X-LINKING-EXCEPTION.txt.
package cn.piq.fcarcade.client.watch;

import cn.piq.fcarcade.cabinet.WatchNetwork;
import cn.piq.fcarcade.client.cabinet.CabinetBackend;
import net.minecraft.network.Connection;
import net.minecraft.resources.ResourceLocation;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import cn.piq.fcarcade.netplay.NetplayProcess;
import cn.piq.fcarcade.netplay.NetplayChunk;

/** Main-thread capture, bounded worker IO, explicit cancellation; no input or save ownership. */
public final class NetplayWatchContent {
    /** Local, trusted provider declaration; never supplied by a server packet. */
    public enum Execution {
        UNDECLARED, JNI, PROCESS;
        Boolean nativeMode(){return this==UNDECLARED?null:this==JNI;}
        void verify(boolean actualJni){
            if(this!=UNDECLARED&&(this==JNI)!=actualJni)
                throw new IllegalStateException("Observer profile differs from its registered execution mode");
        }
    }
    /** Trusted adapter code only: preserves special legacy handshake/input profiles, never read from a packet. */
    @FunctionalInterface public interface ProcessFactory {
        NetplayProcess open(NetplayProcess.Grant grant,CabinetBackend.NetplayContent content,Consumer<NetplayChunk> sender);
    }
    public record Preparation(Callable<CabinetBackend.NetplayContent> load,Runnable cancel,boolean cabinetTopology,ProcessFactory factory) {
        public Preparation(Callable<CabinetBackend.NetplayContent> load,Runnable cancel,boolean cabinetTopology){this(load,cancel,cabinetTopology,null);}
        /** Existing home-console observers retain the two-port timeline. */
        public Preparation(Callable<CabinetBackend.NetplayContent> load,Runnable cancel){this(load,cancel,false);}
        public Preparation{Objects.requireNonNull(load);Objects.requireNonNull(cancel);}
        public NetplayProcess open(NetplayProcess.Grant grant,CabinetBackend.NetplayContent content,Consumer<NetplayChunk> sender){
            if(grant.host()||grant.player()||grant.port()!=-1)throw new IllegalArgumentException("Observer authority");
            if(factory!=null)return factory.open(grant,content,sender);
            var process=new NetplayProcess(grant,()->content.rom(),sender,content.profile(),content::auxiliary,cabinetTopology);
            if(content.files()!=null)process.fileContent(content.files());
            return process;
        }
    }
    @FunctionalInterface public interface Provider {Preparation prepare(WatchNetwork.NetplayStart start,Connection connection)throws Exception;}
    private record Registration(Execution execution,Provider factory) {}
    private static final Map<ResourceLocation,Registration> PROVIDERS=new HashMap<>();
    private NetplayWatchContent(){}
    /** Binary-compatible legacy entry point: the loaded profile still decides its execution mode. */
    public static synchronized void register(ResourceLocation provider,Provider factory){
        register(provider,Execution.UNDECLARED,factory);
    }
    /** Declare a fixed mode so admission can reject incompatible sources before downloading content. */
    public static synchronized void register(ResourceLocation provider,Execution execution,Provider factory){
        var registration=new Registration(Objects.requireNonNull(execution),Objects.requireNonNull(factory));
        if(PROVIDERS.size()>=8||PROVIDERS.putIfAbsent(Objects.requireNonNull(provider),registration)!=null)throw new IllegalArgumentException("Duplicate observer provider");
    }
    static synchronized Execution execution(ResourceLocation provider){
        var registration=PROVIDERS.get(provider);
        return registration==null?Execution.UNDECLARED:registration.execution();
    }
    static Preparation prepare(WatchNetwork.NetplayStart start,Connection connection)throws Exception{
        var provider=PROVIDERS.get(start.watch().descriptor().provider());
        if(provider==null)throw new IllegalStateException("未安装此旁观设备的配套附属");
        return provider.factory().prepare(start,connection);
    }
}
