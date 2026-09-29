package cn.piq.fcarcade.cabinet;

import cn.piq.fcarcade.netplay.*;
import java.util.*;
import net.minecraft.network.Connection;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Read-only capabilities subordinate to the existing nearby-watch lease. Never a seat. */
public final class WatchNetplay {
    public record Offer(long wire,NetplayRelay<Connection> relay,ResourceLocation backend,String romHash,CabinetTarget target) {
        public Offer {if(wire<0||relay==null||backend==null||!CabinetGameManifest.hash(romHash))throw new IllegalArgumentException("Watch offer");}
    }
    private record Entry(ServerPlayer player,Connection connection,WatchNetwork.Start watch,Offer offer,UUID ticket) {}
    private static final Map<MinecraftServer,Map<UUID,Entry>> ENTRIES=new WeakHashMap<>();
    private WatchNetplay(){}
    static WatchNetwork.NetplayStart open(ServerPlayer p,WatchNetwork.Start watch,Offer offer){
        var ticket=offer.relay().grantObserver(p.connection.getConnection());
        if(ticket==null||ticket.player())throw new IllegalStateException("Netplay spectator capacity unavailable");
        var entry=new Entry(p,p.connection.getConnection(),watch,offer,ticket.id());
        ENTRIES.computeIfAbsent(p.getServer(),k->new HashMap<>()).put(watch.lease(),entry);
        NetplayNetwork.authorize(offer.wire(),entry.connection(),offer.relay());
        return new WatchNetwork.NetplayStart(watch,offer.wire(),ticket.id(),offer.backend(),offer.romHash());
    }
    static boolean contains(MinecraftServer server,UUID lease){var m=ENTRIES.get(server);return m!=null&&m.containsKey(lease);}
    private static boolean valid(Entry e){return e.connection()==e.player().connection.getConnection()
            &&WatchService.authorized(e.player(),e.watch())&&!e.offer().relay().closed();}
    static void close(MinecraftServer server,UUID lease){
        var m=ENTRIES.get(server);var e=m==null?null:m.remove(lease);
        // A controller grant can replace the spectator ticket before the next watch tick.
        if(e!=null)e.offer().relay().revoke(e.connection(),e.ticket());
    }
    public static Set<Connection> connections(MinecraftServer server,UUID source){
        var result=new HashSet<Connection>();var m=ENTRIES.get(server);if(m==null)return result;
        for(var e:List.copyOf(m.values()))if(e.watch().descriptor().source().equals(source)&&valid(e))result.add(e.connection());
        return result;
    }
    public static boolean authorizedRom(ServerPlayer p,ResourceLocation backend,String hash){
        var m=ENTRIES.get(p.getServer());return m!=null&&m.values().stream().anyMatch(e->e.player()==p
                &&e.offer().backend().equals(backend)&&e.offer().romHash().equals(hash)&&valid(e));
    }
    public static boolean observing(ServerPlayer p){var m=ENTRIES.get(p.getServer());return m!=null&&m.values().stream().anyMatch(e->e.player()==p&&valid(e));}
    /** Download-only game-store access: upload/configuration still require a physical controller lease. */
    static CabinetTarget gameTarget(ServerPlayer p,UUID lease,ResourceLocation backend){
        var m=ENTRIES.get(p.getServer());var e=m==null?null:m.get(lease);
        return e!=null&&e.player()==p&&e.offer().backend().equals(backend)&&valid(e)?e.offer().target():null;
    }
    static void stopped(MinecraftServer server){var m=ENTRIES.remove(server);if(m!=null)for(var e:m.values())e.offer().relay().revoke(e.connection(),e.ticket());}
}
