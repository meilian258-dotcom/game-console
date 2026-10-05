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
    private static final Map<ResourceLocation,Provider> PROVIDERS=new HashMap<>();
    private NetplayWatchContent(){}
    public static synchronized void register(ResourceLocation provider,Provider factory){
        if(PROVIDERS.size()>=8||PROVIDERS.putIfAbsent(Objects.requireNonNull(provider),Objects.requireNonNull(factory))!=null)throw new IllegalArgumentException("Duplicate observer provider");
    }
    static Preparation prepare(WatchNetwork.NetplayStart start,Connection connection)throws Exception{
        var provider=PROVIDERS.get(start.watch().descriptor().provider());
        if(provider==null)throw new IllegalStateException("未安装此旁观设备的配套附属");
        return provider.prepare(start,connection);
    }
}
