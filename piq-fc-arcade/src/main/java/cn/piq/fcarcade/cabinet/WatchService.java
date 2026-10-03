package cn.piq.fcarcade.cabinet;

import cn.piq.fcarcade.server.ServerArcadeSessions;
import java.util.*;
import net.minecraft.network.Connection;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.PacketDistributor;

/** Server-thread automatic observation. Independent leases cannot be used as controller authority. */
public final class WatchService {
    private WatchService() {}
    private static final Map<MinecraftServer,State> STATES=new WeakHashMap<>();
    static final class Control {
        final Connection connection;boolean enabled=true;int capacity=1;long nextChange;
        final Map<WatchLedger.Source,Long> blocked=new HashMap<>();
        final Map<UUID,Long> nextHeartbeat=new HashMap<>();
        final WatchPreferenceState preferences=new WatchPreferenceState();
        Control(Connection connection){this.connection=connection;preferences.connection(connection);}
        boolean available(WatchNetwork.Available packet,long now){
            // Reductions are immediate, while increases cannot manufacture rapid assignments.
            if(packet.enabled()&&enabled&&packet.capacity()>capacity&&now<nextChange)return false;
            if(packet.enabled()&&!enabled&&now<nextChange)return false;
            enabled=packet.enabled();capacity=packet.enabled()?packet.capacity():0;nextChange=now+5;return true;
        }
        boolean allows(WatchLedger.Source source,long now){return now>=blocked.getOrDefault(source,0L);}
        void block(WatchLedger.Source source,long until){blocked.put(source,until);}
        boolean heartbeat(UUID token,long now){if(now<nextHeartbeat.getOrDefault(token,0L))return false;nextHeartbeat.put(token,now+5);return true;}
    }
    private static final class Live {
        final WatchSource source;final WatchProvider provider;final Connection hostConnection;
        final CabinetRoomMedia ingress=new CabinetRoomMedia();int watchers=-1,cursor;long demandRevision,nextDemand;
        Live(WatchSource source,WatchProvider provider,Connection connection){this.source=source;this.provider=provider;hostConnection=connection;}
        WatchLedger.Source key(){return new WatchLedger.Source(source.descriptor().source(),source.descriptor().hostLease());}
    }
    private static final class State {
        final WatchLedger ledger=new WatchLedger();final WatchBudget budget=new WatchBudget();
        final Map<UUID,Live> sources=new LinkedHashMap<>();final Map<UUID,Control> controls=new HashMap<>();
        long demandRevision;
    }
    private static long now(MinecraftServer server){return Integer.toUnsignedLong(server.getTickCount());}
    static boolean current(ServerPlayer player){var server=player.getServer();return server!=null&&server.isSameThread()
            &&server.getPlayerList().getPlayer(player.getUUID())==player&&player.connection.getConnection().isConnected();}
    private static Control control(State state,ServerPlayer player){
        var result=state.controls.get(player.getUUID());var connection=player.connection.getConnection();
        if(result==null||result.connection!=connection){result=new Control(connection);state.controls.put(player.getUUID(),result);}return result;
    }
    static void available(ServerPlayer player,WatchNetwork.Available packet){
        if(!current(player))return;var server=player.getServer();var state=STATES.computeIfAbsent(server,s->new State());var c=control(state,player);long now=now(server);
        if(!c.available(packet,now))return;
        int retained=0;
        for(var lease:state.ledger.all(player.getUUID()))if(!c.enabled
                ||WatchNetplay.contains(server,lease.token())&&retained++>=c.capacity)
            stop(server,state,state.ledger.remove(lease),"旁观预算已调整");
        demands(server,state,now);
    }
    static void heartbeat(ServerPlayer player,WatchNetwork.Heartbeat packet){
        if(!current(player))return;var server=player.getServer();var state=STATES.get(server);if(state==null)return;var c=control(state,player);long now=now(server);
        // Authenticate first: invented lease IDs must not grow the throttle table.
        if(state.ledger.authorized(player.getUUID(),c.connection,packet.lease(),packet.revision(),now)==null||!c.heartbeat(packet.lease(),now))return;
        state.ledger.heartbeat(player.getUUID(),c.connection,packet.lease(),packet.revision(),now);
    }
    static void release(ServerPlayer player,WatchNetwork.Release packet){
        if(!current(player))return;var server=player.getServer();var state=STATES.get(server);if(state==null)return;long now=now(server);
        var lease=state.ledger.release(player.getUUID(),player.connection.getConnection(),packet.lease(),packet.revision(),now);
        if(lease!=null){control(state,player).block(lease.source(),now+40);stop(server,state,lease,"已停止旁观");demands(server,state,now);}
    }
    static void preference(ServerPlayer player,WatchNetwork.Preference packet){
        if(!current(player))return;var server=player.getServer();var state=STATES.get(server);if(state==null)return;
        var c=control(state,player);long now=now(server);var live=state.sources.get(packet.source().source());
        var lease=state.ledger.authorized(player.getUUID(),c.connection,packet.lease(),packet.revision(),now);
        boolean authorized=live!=null&&WatchPreferenceState.Source.of(live.source.descriptor()).equals(packet.source())
                &&lease!=null&&lease.source().equals(live.key())&&validSource(server,live);
        var result=c.preferences.change(c.connection,packet.sequence(),packet.source(),packet.paused(),authorized);
        if(result==WatchPreferenceState.Result.APPLIED&&packet.paused()&&live!=null
                &&WatchPreferenceState.Source.of(live.source.descriptor()).equals(packet.source())){
            for(var existing:state.ledger.all(player.getUUID()))if(existing.connection()==c.connection
                    &&existing.source().id().equals(packet.source().source())&&existing.source().hostLease().equals(packet.source().hostLease()))
                stop(server,state,state.ledger.remove(existing),"本连接已暂停这一局旁观");
            demands(server,state,now);
        }
        String reason=switch(result){case APPLIED->packet.paused()?"已暂停本连接对这一局的自动旁观":"已恢复这一局自动旁观；仍须符合距离与权限";
            case STALE->"旁观偏好回复已过期";case FULL->"本连接暂停记录已满，请先恢复部分来源";case UNAUTHORIZED->"原旁观关系已结束；本机仍暂停，若再次授权会重试";};
        send(player,new WatchNetwork.PreferenceResult(packet.sequence(),packet.source(),packet.paused(),result==WatchPreferenceState.Result.APPLIED,reason));
    }
    /** Home host upload route; existing cabinet rooms never accept uploads through this route. */
    static void media(ServerPlayer player,WatchNetwork.Media payload){
        if(!current(player))return;var server=player.getServer();var state=STATES.get(server);if(state==null)return;var packet=payload.media();var live=state.sources.get(packet.room());
        if(live==null||serverHosted(server,live)||!uploads(live)||!live.source.hostPlayer().equals(player.getUUID())
                ||live.hostConnection!=player.connection.getConnection()||!live.source.descriptor().hostLease().equals(packet.hostMember())
                ||!validSource(server,live)||mediaRecipients(state.ledger.count(live.key()),controlRecipients(server,live.provider,live.source))==0)return;
        var complete=live.ingress.accept(packet.part(),now(server));if(complete.isEmpty())return;
        var batch=complete.stream().map(p->new CabinetMediaPacket(packet.room(),packet.hostMember(),p.sequence(),p.kind(),p.index(),p.count(),p.width(),p.height(),p.aspect(),p.rotation(),p.rawLength(),p.data())).toList();
        // Control recipients are an independent, existing authority. Never manufacture watch leases.
        // Charge their complete copies before optional observers so controls get bounded priority.
        if(validSource(server,live))relayControlBatch(server,live.provider,live.source,batch,state.budget,now(server));
        relay(server,packet.room(),packet.hostMember(),batch);
    }
    static int controlRecipients(MinecraftServer server,WatchProvider provider,WatchSource source){
        try{int count=provider.controlRecipients(server,source);return count>=0&&count<=2?count:0;}
        catch(RuntimeException|LinkageError failure){return 0;}
    }
    /** HostDemand's legacy field is only a bounded media-needed hint, not an authority count. */
    static int mediaRecipients(int watchers,int controls){
        int validControls=controls>=0&&controls<=2?controls:0;
        return Math.min(WatchLedger.MAX_VIEWERS,Math.max(0,Math.min(WatchLedger.MAX_VIEWERS,watchers))+validControls);
    }
    /** Called only after media() authenticates the current host connection and source generation. */
    static boolean relayControlBatch(MinecraftServer server,WatchProvider provider,WatchSource source,
                                     List<CabinetMediaPacket> batch,WatchBudget budget,long now){
        int recipients=controlRecipients(server,provider,source);if(recipients==0)return false;
        var descriptor=source.descriptor();int bytes=batchBytes(descriptor.source(),descriptor.hostLease(),batch);
        if(bytes==0||!budget.tryReserve(descriptor.source(),now,bytes*recipients))return false;
        try{provider.relayControls(server,source,List.copyOf(batch));return true;}
        catch(RuntimeException|LinkageError failure){return false;} // Keep the real/uncertain egress charge.
    }
    /** Already validated complete media only; call AFTER player media forwarding. Does not retain batches. */
    public static void relay(MinecraftServer server,UUID source,UUID hostLease,List<CabinetMediaPacket> batch){
        if(server==null||!server.isSameThread())return;var state=STATES.get(server);if(state==null)return;var live=state.sources.get(source);
        if(live==null||!live.source.descriptor().hostLease().equals(hostLease)||state.ledger.count(live.key())==0)return;
        if(!validSource(server,live)){drop(server,state,live,"画面来源已结束");return;}
        int bytes=batchBytes(source,hostLease,batch);if(bytes==0)return;long now=now(server);
        var viewers=state.ledger.all().stream().filter(l->l.source().equals(live.key())).toList();if(viewers.isEmpty())return;
        int start=Math.floorMod(live.cursor++,viewers.size());
        for(int offset=0;offset<viewers.size();offset++){
            var lease=viewers.get((start+offset)%viewers.size());var viewer=server.getPlayerList().getPlayer(lease.player());
            if(WatchNetplay.contains(server,lease.token()))continue;
            if(viewer==null||viewer.connection.getConnection()!=lease.connection()||now>=lease.expires()
                    ||!eligible(server,state,viewer,live,false,now)||!canSee(viewer,live)){
                stop(server,state,state.ledger.remove(lease),"已离开旁观范围");continue;
            }
            // Charge each recipient copy including headers, atomically at whole-frame granularity.
            // Failed physical writes conservatively remain charged; never refund/retry into an unbounded queue.
            if(state.budget.tryReserve(source,now,bytes))CabinetMediaSender.watchClientbound(viewer.connection.getConnection(),lease.token(),batch);
        }
        demands(server,state,now);
    }
    private static int batchBytes(UUID source,UUID host,List<CabinetMediaPacket> batch){
        if(batch==null||batch.isEmpty()||batch.size()>6)return 0;var first=batch.getFirst();if(first==null||first.index()!=0||first.count()!=batch.size())return 0;
        int bytes=0,raw=0;for(int i=0;i<batch.size();i++){var p=batch.get(i);if(p==null||!source.equals(p.room())||!host.equals(p.hostMember())
                ||p.index()!=i||p.count()!=batch.size()||p.sequence()!=first.sequence()||p.kind()!=first.kind()||p.width()!=first.width()
                ||p.height()!=first.height()||p.aspect()!=first.aspect()||p.rotation()!=first.rotation()||p.rawLength()!=first.rawLength())return 0;
            int length=p.data().length;raw+=length;bytes+=length+256;}
        return raw<=CabinetRoomMedia.MAX_FRAME?bytes:0;
    }
    private static boolean validSource(MinecraftServer server,Live live){
        var d=live.source.descriptor();var host=server.getPlayerList().getPlayer(live.source.hostPlayer());
        if(!serverHosted(server,live)&&(host==null||!current(host)||host.connection.getConnection()!=live.hostConnection||!host.isAlive()
                ||!host.level().dimension().location().equals(d.dimension())))return false;
        var level=server.getLevel(ResourceKey.create(Registries.DIMENSION,d.dimension()));
        if(level==null||!level.hasChunkAt(d.origin().pos()))return false;
        for(var screen:d.screens())if(!level.hasChunkAt(screen.pos()))return false;
        try{return live.provider.isCurrent(server,live.source);}catch(RuntimeException|LinkageError failure){return false;}
    }
    private static boolean uploads(Live live){try{return live.provider.acceptsUpload();}catch(RuntimeException|LinkageError failure){return false;}}
    private static boolean serverHosted(MinecraftServer server,Live live){try{return live.provider.serverHosted(server,live.source);}catch(RuntimeException|LinkageError failure){return false;}}
    private static double distance(ServerPlayer player,WatchDescriptor d){
        double result=Double.POSITIVE_INFINITY;for(var screen:d.screens())result=Math.min(result,player.distanceToSqr(screen.pos().getX()+.5,screen.pos().getY()+.5,screen.pos().getZ()+.5));return result;
    }
    private static int range(ServerPlayer player,Live live){
        return cn.piq.fcarcade.config.GameConsoleAdminSettings.watchRange(player.serverLevel(),live.source.descriptor().origin().pos());
    }
    private static boolean canSee(ServerPlayer player,Live live){
        if(!player.level().dimension().location().equals(live.source.descriptor().dimension()))return false;
        int exit=range(player,live)+4;
        if(distance(player,live.source.descriptor())>(double)exit*exit)return false;
        try{return live.provider.canObserve(player,live.source);}catch(RuntimeException|LinkageError failure){return false;}
    }
    private static boolean eligible(MinecraftServer server,State state,ServerPlayer player,Live live,boolean netplay,long now){
        if(!current(player)||!player.isAlive())return false;var c=control(state,player);
        if(!c.enabled||!c.allows(live.key(),now)||c.preferences.paused(WatchPreferenceState.Source.of(live.source.descriptor())))return false;
        if(netplay){
            if(c.capacity==0||live.source.hostPlayer().equals(player.getUUID()))return false;
            try{return !live.provider.isParticipant(server,live.source,player.getUUID());}catch(RuntimeException|LinkageError failure){return false;}
        }
        // The legacy MEDIA lane remains single-source and keeps its old global participation guard.
        if(ServerCabinets.hasLocalLease(player)||ServerArcadeSessions.hasPlayerCabinetSession(player))return false;
        for(var provider:WatchProviders.entries().values())try{if(provider.isParticipant(server,player.getUUID()))return false;}catch(RuntimeException|LinkageError failure){return false;}
        return true;
    }
    record Selection(List<WatchLedger.Candidate> candidates,int capacity) {}
    /** Keep the existing lane while it is valid. MEDIA and Netplay never share a player selection. */
    static Selection selectLane(WatchLedger ledger,UUID player,Object connection,List<WatchLedger.Candidate> candidates,
                                Set<WatchLedger.Source> netplay,int capacity,long now){
        Boolean lane=null;
        for(var lease:ledger.all(player))if(lease.connection()==connection&&now<lease.expires()
                &&(!netplay.contains(lease.source())||capacity>0)
                &&candidates.stream().anyMatch(c->c.source().equals(lease.source())&&c.distanceSquared()<=c.exitSquared())){
            lane=netplay.contains(lease.source());break;
        }
        if(lane==null){
            var first=candidates.stream().filter(c->(!netplay.contains(c.source())||capacity>0)&&c.distanceSquared()<=c.enterSquared()
                    &&ledger.count(c.source())<WatchLedger.MAX_VIEWERS)
                    .min(Comparator.comparingDouble(WatchLedger.Candidate::distanceSquared).thenComparing(c->c.source().id())).orElse(null);
            if(first==null)return new Selection(List.of(),0);lane=netplay.contains(first.source());
        }
        boolean selectedLane=lane;
        return new Selection(candidates.stream().filter(c->netplay.contains(c.source())==selectedLane).toList(),selectedLane?capacity:1);
    }
    static void tick(MinecraftServer server){
        if(!server.isSameThread())return;long now=now(server);if(now%10!=0)return;
        var state=STATES.computeIfAbsent(server,s->new State());refreshSources(server,state);
        // Clear stale slots before selecting new viewers, so disconnected viewers cannot hold capacity.
        for(var lease:state.ledger.all()){
            var player=server.getPlayerList().getPlayer(lease.player());var live=state.sources.get(lease.source().id());
            if(player==null||player.connection.getConnection()!=lease.connection()||now>=lease.expires()||live==null||!live.key().equals(lease.source())
                    ||!eligible(server,state,player,live,WatchNetplay.contains(server,lease.token()),now)||!canSee(player,live))
                stop(server,state,state.ledger.remove(lease),"旁观已结束");
        }
        for(var player:server.getPlayerList().getPlayers()){
            var c=control(state,player);var candidates=new ArrayList<WatchLedger.Candidate>();
            var offers=new HashMap<WatchLedger.Source,WatchNetplay.Offer>();var netplay=new HashSet<WatchLedger.Source>();
            c.blocked.entrySet().removeIf(e->now>=e.getValue()||!state.sources.containsKey(e.getKey().id())
                    ||!state.sources.get(e.getKey().id()).key().equals(e.getKey()));
            if(c.enabled&&current(player)&&player.isAlive())for(var live:state.sources.values())if(c.allows(live.key(),now)
                    &&!c.preferences.paused(WatchPreferenceState.Source.of(live.source.descriptor()))&&canSee(player,live)){
                try{
                    var offer=live.provider.netplay(player,live.source);
                    if(!eligible(server,state,player,live,offer!=null,now))continue;
                    // Providers may explicitly select a different lane per player (e.g. SFC preference).
                    // A lane change needs a fresh grant; failures below never manufacture MEDIA.
                    for(var lease:state.ledger.all(player.getUUID()))if(lease.source().equals(live.key())
                            &&WatchNetplay.contains(server,lease.token())!=(offer!=null))
                        stop(server,state,state.ledger.remove(lease),"旁观方式已调整");
                    if(offer!=null){offers.put(live.key(),offer);netplay.add(live.key());}
                    int enter=range(player,live),exit=enter+4;
                    candidates.add(new WatchLedger.Candidate(live.key(),distance(player,live.source.descriptor()),(double)enter*enter,(double)exit*exit));
                }catch(RuntimeException|LinkageError failed){c.block(live.key(),now+200);}
            }
            var before=state.ledger.all(player.getUUID());var selection=selectLane(state.ledger,player.getUUID(),c.connection,candidates,netplay,c.capacity,now);
            var after=state.ledger.selectMany(player.getUUID(),c.connection,selection.candidates(),selection.capacity(),now);
            var oldTokens=new HashSet<UUID>();for(var lease:before)oldTokens.add(lease.token());
            var newTokens=new HashSet<UUID>();for(var lease:after)newTokens.add(lease.token());
            for(var lease:before)if(!newTokens.contains(lease.token()))stop(server,state,lease,"已切换旁观画面");
            for(var lease:after)if(!oldTokens.contains(lease.token())){
                var live=state.sources.get(lease.source().id());var start=new WatchNetwork.Start(lease.revision(),lease.token(),live.source.descriptor());
                try{var offer=offers.get(lease.source());send(player,offer==null?start:WatchNetplay.open(player,start,offer));}
                catch(RuntimeException|LinkageError failed){c.block(lease.source(),now+200);stop(server,state,state.ledger.remove(lease),"旁观连接暂不可用");}
            }
            else if(now%40==0)send(player,new WatchNetwork.Heartbeat(lease.revision(),lease.token()));
        }
        state.controls.entrySet().removeIf(e->{var player=server.getPlayerList().getPlayer(e.getKey());return player==null||player.connection.getConnection()!=e.getValue().connection;});
        demands(server,state,now);
    }
    private static void refreshSources(MinecraftServer server,State state){
        var found=new LinkedHashMap<UUID,Live>();
        for(var entry:WatchProviders.entries().entrySet()){
            List<WatchSource> offered;try{offered=entry.getValue().sources(server);}catch(RuntimeException|LinkageError failure){continue;}
            if(offered==null)continue;int scanned=0;
            for(var source:offered){if(++scanned>WatchLedger.MAX_SOURCES||found.size()>=WatchLedger.MAX_SOURCES)break;
                if(source==null||!entry.getKey().equals(source.descriptor().provider())||found.containsKey(source.descriptor().source()))continue;
                var host=server.getPlayerList().getPlayer(source.hostPlayer());
                boolean hosted;try{hosted=entry.getValue().serverHosted(server,source);}catch(RuntimeException|LinkageError failure){continue;}
                if(host==null&&!hosted)continue;var hostConnection=hosted?null:host.connection.getConnection();
                var old=state.sources.get(source.descriptor().source());
                var live=old!=null&&old.source.equals(source)&&old.provider==entry.getValue()&&old.hostConnection==hostConnection?old:new Live(source,entry.getValue(),hostConnection);
                if(validSource(server,live))found.put(source.descriptor().source(),live);
            }
        }
        for(var old:List.copyOf(state.sources.values()))if(found.get(old.source.descriptor().source())!=old)drop(server,state,old,"画面来源已结束");
        state.sources.clear();state.sources.putAll(found);
    }
    private static void demands(MinecraftServer server,State state,long now){
        for(var live:state.sources.values()){
            int observers=(int)state.ledger.all().stream().filter(l->l.source().equals(live.key())&&!WatchNetplay.contains(server,l.token())).count();
            int count=mediaRecipients(observers,controlRecipients(server,live.provider,live.source));boolean changed=count!=live.watchers;
            if(changed){live.watchers=count;live.demandRevision=++state.demandRevision;}
            if(changed||now>=live.nextDemand){live.nextDemand=now+40;var host=server.getPlayerList().getPlayer(live.source.hostPlayer());
                if(host!=null&&host.connection.getConnection()==live.hostConnection)send(host,new WatchNetwork.HostDemand(live.demandRevision,live.source.descriptor(),count));}
        }
    }
    private static void drop(MinecraftServer server,State state,Live live,String reason){
        for(var lease:state.ledger.removeSource(live.key()))stop(server,state,lease,reason);
        state.sources.remove(live.source.descriptor().source());state.budget.remove(live.source.descriptor().source());
        var host=server.getPlayerList().getPlayer(live.source.hostPlayer());if(host!=null&&host.connection.getConnection()==live.hostConnection)
            send(host,new WatchNetwork.HostDemand(++state.demandRevision,live.source.descriptor(),0));
    }
    /** Immediate host shutdown hook; only this exact source generation is affected. */
    public static void closed(MinecraftServer server,UUID source,UUID hostLease){
        if(server==null||!server.isSameThread())return;var state=STATES.get(server);if(state==null)return;var live=state.sources.get(source);if(live!=null&&live.source.descriptor().hostLease().equals(hostLease))drop(server,state,live,"画面来源已结束");
    }
    private static void stop(MinecraftServer server,State state,WatchLedger.Lease lease,String reason){
        if(lease==null)return;var player=server.getPlayerList().getPlayer(lease.player());
        WatchNetplay.close(server,lease.token());
        var c=state.controls.get(lease.player());if(c!=null&&c.connection==lease.connection())c.nextHeartbeat.remove(lease.token());
        if(player!=null&&player.connection.getConnection()==lease.connection())send(player,new WatchNetwork.Stop(lease.revision(),lease.token(),reason));
    }
    static void logout(ServerPlayer player){
        var server=player.getServer();var state=STATES.get(server);if(state==null)return;var connection=player.connection.getConnection();
        for(var lease:state.ledger.all(player.getUUID()))if(lease.connection()==connection){state.ledger.remove(lease);WatchNetplay.close(server,lease.token());}
        var c=state.controls.get(player.getUUID());if(c!=null&&c.connection==connection)state.controls.remove(player.getUUID());
        for(var live:List.copyOf(state.sources.values()))if(live.hostConnection==connection)drop(server,state,live,"主持玩家已离线");
        demands(server,state,now(server));
    }
    static void stopped(MinecraftServer server){WatchNetplay.stopped(server);STATES.remove(server);}
    /** Exact live watch generation; no input, upload, editing or seat authority is implied. */
    static boolean authorized(ServerPlayer player,WatchNetwork.Start watch){
        if(!current(player))return false;var server=player.getServer();var state=STATES.get(server);if(state==null)return false;
        long time=now(server);var lease=state.ledger.authorized(player.getUUID(),player.connection.getConnection(),watch.lease(),watch.revision(),time);
        var live=state.sources.get(watch.descriptor().source());
        return lease!=null&&lease.source().equals(new WatchLedger.Source(watch.descriptor().source(),watch.descriptor().hostLease()))
                &&live!=null&&live.source.descriptor().equals(watch.descriptor())
                &&validSource(server,live)&&eligible(server,state,player,live,true,time)&&canSee(player,live);
    }
    private static void send(ServerPlayer player,CustomPacketPayload payload){
        if(!current(player))return;
        try{PacketDistributor.sendToPlayer(player,payload);
            if(payload instanceof WatchNetwork.Heartbeat h&&!player.connection.getConnection().isMemoryConnection())
                cn.piq.fcarcade.network.ServerTrafficMeter.heartbeat(true,WatchNetwork.heartbeatBytes(h.revision()));
        }catch(RuntimeException|LinkageError failure){/* Optional observation must not stop a controller session. */}
    }
}
