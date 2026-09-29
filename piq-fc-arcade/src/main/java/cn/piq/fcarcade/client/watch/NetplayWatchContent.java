package cn.piq.fcarcade.client.watch;

import cn.piq.fcarcade.cabinet.WatchNetwork;
import cn.piq.fcarcade.client.cabinet.CabinetBackend;
import net.minecraft.network.Connection;
import net.minecraft.resources.ResourceLocation;
import java.util.*;
import java.util.concurrent.Callable;

/** Main-thread capture, bounded worker IO, explicit cancellation; no input or save ownership. */
public final class NetplayWatchContent {
    public record Preparation(Callable<CabinetBackend.NetplayContent> load,Runnable cancel) {
        public Preparation{Objects.requireNonNull(load);Objects.requireNonNull(cancel);}
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
