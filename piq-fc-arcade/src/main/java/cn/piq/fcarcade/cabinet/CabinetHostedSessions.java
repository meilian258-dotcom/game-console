package cn.piq.fcarcade.cabinet;

import cn.piq.fcarcade.server.hosted.*;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/** Main-thread admission/lifecycle, with immutable work delegated to bounded owner workers. */
final class CabinetHostedSessions {
    private static final Map<MinecraftServer,Map<UUID,HostedCabinetWorker>> RUNS=new WeakHashMap<>();
    private CabinetHostedSessions(){}
    private static ServerCoreContext context(MinecraftServer server,CabinetTarget target,UUID owner,UUID room){
        Path world=server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        return new ServerCoreContext(server.getServerDirectory().toAbsolutePath().normalize(),cn.piq.retro.storage.ConsoleStorage.root(world).resolve("piq-cabinet/hosted-saves"),owner,target.identity());
    }
    static String unavailable(MinecraftServer server,CabinetTarget target,ResourceLocation backend){
        if(!CabinetHostingConfig.enabled())return "服主未启用服务端托管（piq-sync-server.toml）";
        var factory=ServerCoreRegistry.find(backend);if(factory==null)return "此附属未注册服务端核心";
        var peer=CabinetLinks.peer(server,target);
        if(CabinetSeats.capacity(factory.maxPlayers(),peer!=null,target.dual(),peer!=null&&peer.dual())==0)return "服务端核心不支持此通讯线的席位数量";
        try{return factory.unavailableReason(context(server,target,new UUID(0,0),new UUID(0,0)));}
        catch(RuntimeException|LinkageError failure){return "服务端运行库检查失败："+failure.getClass().getSimpleName();}
    }
    static String start(MinecraftServer server,CabinetRoomLedger.Room<CabinetTarget> room,CabinetGameManifest manifest){
        if(!server.isSameThread()||manifest==null||!room.backend.equals(manifest.backend()))return "机柜没有经过校验的共享游戏";
        var runs=RUNS.computeIfAbsent(server,k->new HashMap<>());runs.values().removeIf(HostedCabinetWorker::terminated);
        if(runs.containsKey(room.id))return null;
        String reason=unavailable(server,room.target,ResourceLocation.parse(room.backend));if(reason!=null)return reason;
        var peer=CabinetLinks.peer(server,room.target);
        if(isTargetBusy(server,room.target)||peer!=null&&isTargetBusy(server,peer))return "上一局仍在关闭或保存，请稍后再开机";
        var lease=HostedServerLimits.tryAcquire(server);
        if(lease==null)return "服务器托管席位已达上限或上一局仍在关闭";
        Path world=server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        try {
            runs.put(room.id,new HostedCabinetWorker(room.id,room.streamHostId,ResourceLocation.parse(room.backend),context(server,room.target,room.ownerId,room.id),manifest,
                CabinetServerContent.store(server),cn.piq.retro.storage.ConsoleStorage.root(world).resolve("piq-cabinet/hosted-running").resolve(room.id.toString()),lease,
                peer==null?Set.of(room.target.identity()):Set.of(room.target.identity(),peer.identity())));
        } catch (RuntimeException | LinkageError failure) {
            lease.close();
            return "服务端托管启动失败："+failure.getClass().getSimpleName();
        }
        return null;
    }
    static HostedCabinetWorker get(MinecraftServer server,UUID room){var runs=RUNS.get(server);return runs==null?null:runs.get(room);}
    static boolean isTargetBusy(MinecraftServer server,CabinetTarget target){var runs=RUNS.get(server);return runs!=null&&runs.values().stream().anyMatch(worker->worker.uses(target.identity()));}
    static void input(MinecraftServer server,CabinetRoomLedger.Room<CabinetTarget> room){var run=get(server,room.id);if(run!=null){int[] masks=new int[4];for(var m:room.members)if(m!=null)masks[m.port]=m.mask;run.inputs(masks);}}
    static void release(MinecraftServer server,CabinetRoomLedger.Room<CabinetTarget> room,int port){var run=get(server,room.id);if(run!=null)run.releasePort(port);input(server,room);}
    static void releaseGameplay(MinecraftServer server,CabinetRoomLedger.Room<CabinetTarget> room,int port){
        var run=get(server,room.id);if(run!=null)run.releaseGameplayPort(port,room.coinRequired);input(server,room);
    }
    static boolean budget(MinecraftServer server,UUID room,List<CabinetMediaPacket> batch){
        int bytes=batch.stream().mapToInt(p->p.data().length+256).sum();
        var worker=get(server,room);return worker!=null&&worker.budget(server,bytes);
    }
    static void close(MinecraftServer server,UUID room){var run=get(server,room);if(run!=null)run.close();}
    static void stopped(MinecraftServer server){var runs=RUNS.remove(server);if(runs!=null)runs.values().forEach(HostedCabinetWorker::close);}
}
