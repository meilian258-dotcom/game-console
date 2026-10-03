package cn.piq.sfchome.server;

import cn.piq.fcarcade.cabinet.WatchProvider;
import cn.piq.fcarcade.cabinet.WatchProviders;
import cn.piq.fcarcade.cabinet.WatchSource;
import cn.piq.sfchome.SfcHomeMod;
import java.util.List;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Read-only media discovery. It grants no cartridge, controller, ROM or join authority. */
public final class SfcWatchProvider implements WatchProvider {
    private SfcWatchProvider() {}
    public static void register(){WatchProviders.register(SfcHomeMod.CABINET_BACKEND,new SfcWatchProvider());}
    @Override public List<WatchSource> sources(MinecraftServer server){return SfcHomeServer.watchSources(server);}
    @Override public boolean isCurrent(MinecraftServer server,WatchSource expected){
        return expected!=null&&sources(server).contains(expected);
    }
    @Override public boolean isParticipant(MinecraftServer server,UUID player){return SfcHomeServer.watchParticipant(server,player);}
    @Override public boolean isParticipant(MinecraftServer server,WatchSource source,UUID player){return SfcHomeServer.watchParticipant(server,source,player);}
    @Override public boolean serverHosted(MinecraftServer server,WatchSource source){return SfcHomeServer.watchHosted(server,source);}
    @Override public int controlRecipients(MinecraftServer server,WatchSource source){return SfcHomeServer.watchControlRecipients(server,source);}
    @Override public void relayControls(MinecraftServer server,WatchSource source,List<cn.piq.fcarcade.cabinet.CabinetMediaPacket> batch){SfcHomeServer.relayPlayerMedia(server,source,batch);}
    @Override public boolean canObserve(ServerPlayer player,WatchSource source){
        return observableHardware(player,source)&&(SfcLocalWatchServer.mediaAllowed(player,source)||netplay(player,source)!=null);
    }
    @Override public cn.piq.fcarcade.cabinet.WatchNetplay.Offer netplay(ServerPlayer p,WatchSource source){
        return SfcLocalWatchServer.netplayAllowed(p)?SfcHomeServer.netplayWatch(p.getServer(),source):null;
    }
    static boolean observableHardware(ServerPlayer player,WatchSource source){
        if(player==null||source==null||player.hasDisconnected()
                ||!player.serverLevel().dimension().location().equals(source.descriptor().dimension()))return false;
        var descriptor=source.descriptor();var level=player.serverLevel();
        return level.hasChunkAt(descriptor.origin().pos())&&level.getWorldBorder().isWithinBounds(descriptor.origin().pos())
                &&descriptor.screens().stream().allMatch(screen->level.hasChunkAt(screen.pos())&&level.getWorldBorder().isWithinBounds(screen.pos()));
    }
}
