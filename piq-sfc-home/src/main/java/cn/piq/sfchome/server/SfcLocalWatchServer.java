package cn.piq.sfchome.server;

import cn.piq.fcarcade.cabinet.WatchSource;
import cn.piq.sfchome.net.SfcHomeNetwork;
import cn.piq.sfchome.net.SfcLocalWatchNetwork;
import cn.piq.sfchome.net.SfcRepairNetwork;
import java.util.*;
import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** One receive-only observer per connection. It never enters a controller, join, repair or save table. */
public final class SfcLocalWatchServer {
    private static final Map<MinecraftServer,State> STATES=new WeakHashMap<>();
    private static final int MAX_BOOTSTRAPS=4, MAX_OBSERVERS=128;
    private SfcLocalWatchServer() {}
    private static State state(MinecraftServer server){return STATES.computeIfAbsent(server,k->new State());}
    private static final class State {long tick;final Map<UUID,Preference> preferences=new HashMap<>();final Map<UUID,Observer> observers=new HashMap<>();}
    private static final class Preference {
        final Connection connection;long revision,heartbeat,lastPacket=-1;int mode;boolean available,failed;
        Preference(Connection c){connection=c;}
    }
    private enum Phase { UPLOAD, SEND, RESTORED, REPLAY, LIVE }
    private static final class Observer {
        final UUID player,lease=UUID.randomUUID();final Connection connection,hostConnection;final long revision,deadline;
        final SfcHomeServer.LocalWatchSource source;Phase phase=Phase.UPLOAD;boolean coreReady;
        SfcLocalWatchTransfer transfer;byte[] bytes;int sent,next,lastDigest=-1;long uploadTick=-1;int uploads;
        SfcLocalWatchNetwork.Digest pendingDigest;
        Observer(ServerPlayer p,Preference preference,SfcHomeServer.LocalWatchSource source,long tick){
            player=p.getUUID();connection=p.connection.getConnection();revision=preference.revision;this.source=source;deadline=tick+1200;next=source.checkpoint();
            var host=p.getServer().getPlayerList().getPlayer(source.source().hostPlayer());hostConnection=host==null?null:host.connection.getConnection();
        }
        SfcRepairNetwork.Key key(int frame){return new SfcRepairNetwork.Key(source.session(),source.epoch(),lease,lease,frame);}
    }
    public static void preference(ServerPlayer p,SfcLocalWatchNetwork.Preference packet){
        State st=state(p.getServer());Preference pref=st.preferences.get(p.getUUID());
        if(pref==null||pref.connection!=p.connection.getConnection()){pref=new Preference(p.connection.getConnection());st.preferences.put(p.getUUID(),pref);}
        if(packet.revision()<pref.revision||pref.lastPacket==st.tick)return;
        pref.lastPacket=st.tick;
        if(packet.revision()>pref.revision){stop(st,p.getUUID(),"",false);pref.revision=packet.revision();pref.failed=false;}
        else if(packet.mode()!=pref.mode)return;
        pref.mode=packet.mode();pref.available=packet.available();pref.heartbeat=st.tick;
        if(pref.mode!=SfcLocalWatchNetwork.LOCAL||!pref.available)stop(st,p.getUUID(),"",false);
    }
    /** Default is safe/no unsolicited SFC audio-video until the client declares its preference. */
    static boolean netplayAllowed(ServerPlayer p){
        if(p==null)return false;State st=STATES.get(p.getServer());Preference pref=st==null?null:st.preferences.get(p.getUUID());
        return pref!=null&&pref.connection==p.connection.getConnection()&&st.tick-pref.heartbeat<=120
                &&pref.mode==SfcLocalWatchNetwork.LOCAL&&pref.available&&!pref.failed;
    }
    static boolean mediaAllowed(ServerPlayer p,WatchSource source){
        if(p==null||source==null)return false;State st=STATES.get(p.getServer());Preference pref=st==null?null:st.preferences.get(p.getUUID());
        if(pref==null||pref.connection!=p.connection.getConnection()||st.tick-pref.heartbeat>120||pref.mode==SfcLocalWatchNetwork.OFF)return false;
        if(pref.mode==SfcLocalWatchNetwork.MEDIA)return true;
        // Do not silently fall back to expensive media for an unsupported source or failed bootstrap.
        return false;
    }
    private static boolean observable(ServerPlayer p,SfcHomeServer.LocalWatchSource source,boolean existing){
        if(p==null||source==null||p.hasDisconnected()||!p.isAlive()||p.getServer().getPlayerList().getPlayer(p.getUUID())!=p
                ||SfcHomeServer.watchParticipant(p.getServer(),p.getUUID())||cn.piq.fcarcade.cabinet.WatchNetplay.observing(p)
                ||!SfcWatchProvider.observableHardware(p,source.source()))return false;
        var d=source.source().descriptor();
        int range=existing?cn.piq.fcarcade.config.GameConsoleAdminSettings.exitRange(p.serverLevel(),d.origin().pos())
                :cn.piq.fcarcade.config.GameConsoleAdminSettings.watchRange(p.serverLevel(),d.origin().pos());
        return p.distanceToSqr(d.screens().getFirst().pos().getCenter())<=(double)range*range;
    }
    private static boolean current(State st,Observer o,ServerPlayer p){
        Preference pref=st.preferences.get(o.player);
        if(p==null||p.connection.getConnection()!=o.connection||pref==null||pref.connection!=o.connection||pref.revision!=o.revision
                ||pref.mode!=SfcLocalWatchNetwork.LOCAL||!pref.available||pref.failed||st.tick-pref.heartbeat>120)return false;
        var source=SfcHomeServer.localWatchSource(p.getServer(),o.source.source().descriptor().source());
        return source!=null&&source.session()==o.source.session()&&source.epoch()==o.source.epoch()
                &&source.source().equals(o.source.source())&&observable(p,source,true);
    }
    private static Observer observer(ServerPlayer p,UUID lease){
        State st=STATES.get(p.getServer());Observer o=st==null?null:st.observers.get(p.getUUID());
        return o!=null&&o.lease.equals(lease)&&current(st,o,p)?o:null;
    }
    public static void ready(ServerPlayer p,SfcLocalWatchNetwork.Ready packet){
        Observer o=observer(p,packet.lease());if(o==null||o.coreReady)return;
        State st=state(p.getServer());
        if(!o.source.initial().equals(packet.initial())||Math.abs(o.source.fps()-packet.fps())>.01){stop(st,o.player,"本地 ROM／核心初始状态不一致",true);return;}
        o.coreReady=true;
    }
    public static void captureFailed(ServerPlayer p,SfcLocalWatchNetwork.CaptureFailed packet){
        State st=STATES.get(p.getServer());if(st==null)return;var k=packet.key();
        for(Observer o:List.copyOf(st.observers.values()))if(o.phase==Phase.UPLOAD&&o.lease.equals(k.token())
                &&o.hostConnection==p.connection.getConnection()&&o.source.source().hostPlayer().equals(p.getUUID())
                &&o.source.session()==k.session()&&o.source.epoch()==k.epoch()&&o.source.checkpoint()==k.frame()
                &&o.source.source().descriptor().hostLease().equals(k.lease())){stop(st,o.player,"主持检查点已过期或旁观准备繁忙，请手动重试",true);return;}
    }
    public static void upload(ServerPlayer p,SfcLocalWatchNetwork.Upload packet){
        State st=STATES.get(p.getServer());if(st==null)return;var part=packet.value();var key=part.key();
        Observer o=st.observers.values().stream().filter(v->v.lease.equals(key.token())).findFirst().orElse(null);
        if(o==null||o.phase!=Phase.UPLOAD||o.hostConnection!=p.connection.getConnection()||!o.source.source().hostPlayer().equals(p.getUUID())
                ||!o.source.source().descriptor().hostLease().equals(key.lease())||key.session()!=o.source.session()
                ||key.epoch()!=o.source.epoch()||key.frame()!=o.source.checkpoint())return;
        var target=p.getServer().getPlayerList().getPlayer(o.player);
        if(!current(st,o,target)){stop(st,o.player,"",false);return;}
        if(o.uploadTick!=st.tick){o.uploadTick=st.tick;o.uploads=0;}
        if(++o.uploads>8||!o.source.checkpointSha().equals(part.sha())){stop(st,o.player,"旁观检查点来源校验失败",true);return;}
        try{
            if(o.transfer==null){if(part.offset()!=0)throw new IllegalArgumentException();o.transfer=new SfcLocalWatchTransfer(part.total(),part.sha());}
            if(!o.transfer.append(part.total(),part.offset(),part.sha(),part.data()))throw new IllegalArgumentException();
            if(o.transfer.complete()){o.bytes=o.transfer.take();o.transfer=null;o.phase=Phase.SEND;}
        }catch(RuntimeException bad){stop(st,o.player,"旁观检查点损坏或顺序异常",true);}
    }
    public static void ack(ServerPlayer p,SfcLocalWatchNetwork.Ack packet){
        Observer o=observer(p,packet.lease());if(o==null)return;State st=state(p.getServer());
        if(!packet.success()||packet.phase()==2){stop(st,o.player,packet.success()?"":"本地旁观未就绪或已取消",!packet.success());return;}
        if(packet.phase()==0&&o.phase==Phase.RESTORED)o.phase=Phase.REPLAY;
        // A caught-up acknowledgement never changes membership or grants input.
    }
    public static void digest(ServerPlayer p,SfcLocalWatchNetwork.Digest packet){
        Observer o=observer(p,packet.lease());if(o==null||o.phase!=Phase.LIVE||packet.frame()<=o.lastDigest||packet.frame()>o.next)return;
        o.lastDigest=packet.frame();o.pendingDigest=packet;
    }
    public static void tick(MinecraftServer server){
        State st=state(server);st.tick++;
        for(Observer o:List.copyOf(st.observers.values())){
            ServerPlayer p=server.getPlayerList().getPlayer(o.player);
            if(!current(st,o,p)){stop(st,o.player,"",false);continue;}
            if(o.phase!=Phase.LIVE&&st.tick>o.deadline){stop(st,o.player,"旁观准备超时",true);continue;}
            if(o.pendingDigest!=null){String expected=SfcHomeServer.localWatchDigest(server,o.source,o.pendingDigest.frame());
                if(expected!=null){if(!expected.equals(o.pendingDigest.sha())){stop(st,o.player,"旁观状态不同步，已停止本地旁观",true);continue;}o.pendingDigest=null;}
                else if(o.next-o.pendingDigest.frame()>1200){stop(st,o.player,"旁观状态校验超时",true);continue;}
            }
            try{
                for(int budget=0;budget<2&&o.phase==Phase.SEND&&o.coreReady;budget++){
                    int end=Math.min(o.bytes.length,o.sent+SfcRepairLedger.CHUNK);
                    var part=new SfcRepairNetwork.State(o.key(o.source.checkpoint()),o.bytes.length,o.sent,o.source.checkpointSha(),Arrays.copyOfRange(o.bytes,o.sent,end));
                    if(!send(p,new SfcLocalWatchNetwork.State(part),end-o.sent+2048))break;
                    o.sent=end;if(end==o.bytes.length){o.bytes=null;o.phase=Phase.RESTORED;}
                }
                for(int budget=0;budget<2&&(o.phase==Phase.REPLAY||o.phase==Phase.LIVE);budget++){
                    var replay=SfcHomeServer.localWatchReplay(server,o.source,o.next);
                    if(replay.p1().length==0){
                        if(o.phase==Phase.REPLAY&&send(p,new SfcLocalWatchNetwork.Resume(o.key(o.next)),2048))o.phase=Phase.LIVE;
                        break;
                    }
                    var frames=new SfcRepairNetwork.Replay(o.key(replay.first()),replay.p1(),replay.p2());
                    if(!send(p,new SfcLocalWatchNetwork.Frames(o.lease,frames),4096))break;o.next+=replay.p1().length;
                }
                if(o.phase==Phase.LIVE&&st.tick%40==0)send(p,new SfcLocalWatchNetwork.Resume(o.key(o.next)),2048);
            }catch(RuntimeException failure){stop(st,o.player,"旁观追帧历史已过期或传输失败",true);}
        }
        if(st.tick%10!=0)return;
        for(var entry:List.copyOf(st.preferences.entrySet())){
            ServerPlayer p=server.getPlayerList().getPlayer(entry.getKey());Preference pref=entry.getValue();
            if(p==null||p.connection.getConnection()!=pref.connection||st.tick-pref.heartbeat>120){stop(st,entry.getKey(),"",false);st.preferences.remove(entry.getKey());continue;}
            if(pref.mode!=SfcLocalWatchNetwork.LOCAL||!pref.available||pref.failed||st.observers.containsKey(entry.getKey())||st.observers.size()>=MAX_OBSERVERS)continue;
            if(st.observers.values().stream().filter(o->o.phase!=Phase.LIVE).count()>=MAX_BOOTSTRAPS)continue;
            SfcHomeServer.LocalWatchSource nearest=null;double distance=Double.POSITIVE_INFINITY;
            for(var source:SfcHomeServer.watchSources(server)){
                var view=SfcHomeServer.localWatchSource(server,source.descriptor().source());if(!observable(p,view,false))continue;
                double d=p.distanceToSqr(source.descriptor().screens().getFirst().pos().getCenter());if(d<distance){nearest=view;distance=d;}
            }
            if(nearest==null)continue;
            Observer o=new Observer(p,pref,nearest,st.tick);st.observers.put(o.player,o);var d=nearest.source().descriptor();var tv=d.screens().getFirst();
            var session=new SfcHomeNetwork.Session(nearest.session(),nearest.epoch(),d.dimension(),d.origin().pos(),d.origin().identity(),tv.pos(),tv.identity(),d.link(),nearest.rom(),SfcHomeNetwork.CORE_BUILD,0,o.lease,false,1,d.source(),d.hostLease());
            SfcHomeNetwork.send(p,new SfcLocalWatchNetwork.Start(o.revision,o.lease,session,nearest.checkpoint(),nearest.checkpointSha(),nearest.initial(),nearest.fps(),
                    cn.piq.fcarcade.config.GameConsoleAdminSettings.exitRange(p.serverLevel(),d.origin().pos())));
            // Pin immediately, in parallel with ROM/core loading: the host only retains two checkpoints.
            var host=server.getPlayerList().getPlayer(nearest.source().hostPlayer());
            if(host==null){stop(st,o.player,"主持已离线",true);continue;}
            SfcHomeNetwork.send(host,new SfcLocalWatchNetwork.Capture(new SfcRepairNetwork.Key(nearest.session(),nearest.epoch(),d.hostLease(),o.lease,nearest.checkpoint())));
        }
    }
    private static boolean send(ServerPlayer p,net.minecraft.network.protocol.common.custom.CustomPacketPayload value,int bytes){
        return cn.piq.fcarcade.cabinet.CabinetMediaSender.sendPayload(p.connection.getConnection(),value,bytes,false);
    }
    private static void stop(State st,UUID player,String reason,boolean failed){
        Observer old=st.observers.remove(player);Preference pref=st.preferences.get(player);if(failed&&pref!=null)pref.failed=true;
        if(old!=null){old.bytes=null;old.transfer=null;
            if(old.hostConnection!=null&&old.hostConnection.isConnected())cn.piq.fcarcade.cabinet.CabinetMediaSender.sendPayload(old.hostConnection,new SfcLocalWatchNetwork.Cancel(old.lease),2048,false);
            if(old.connection.isConnected())
            cn.piq.fcarcade.cabinet.CabinetMediaSender.sendPayload(old.connection,new SfcLocalWatchNetwork.Stopped(old.revision,old.lease,reason),2048,false);}
    }
    public static void close(MinecraftServer server){STATES.remove(server);}
}
