package cn.piq.fcarcade.cabinet;

import java.util.*;
import java.util.concurrent.*;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/** Server-thread coordinator. Core state is opaque; only the precise current host supplies snapshots. */
final class CabinetSynchronizer {
    private static final Map<MinecraftServer,Map<UUID,Sync>> STATES=new WeakHashMap<>();
    private static final ExecutorService VERIFY=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(4),r->{var t=new Thread(r,"PIQ-Cabinet-State-Verify");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private static final class Sync {
        final CabinetRoomLedger.Room<CabinetTarget> room;final long created;final CabinetSyncPolicy policy;
        CabinetSyncNetwork.Hello identity;CabinetSyncTimeline timeline;byte[] snapshot;String snapshotHash;long snapshotFrame=-1;
        CabinetSyncNetwork.StatePart offer;CabinetSyncState incoming;long uploadDeadline;boolean verifying;
        final Map<UUID,Peer> peers=new HashMap<>();
        final Map<UUID,Long> loadDeadlines=new HashMap<>();
        final Map<Long,String> digests=new TreeMap<>();
        Sync(CabinetRoomLedger.Room<CabinetTarget> room,long now){this.room=room;created=now;policy=Objects.requireNonNull(CabinetBackends.syncPolicy(ResourceLocation.parse(room.backend)));}
    }
    private static final class Peer {
        final Connection connection;final CabinetSyncGate gate;long cursor,nextRepair;int repairs;
        Transfer transfer;final Map<Long,String> digests=new TreeMap<>();
        Peer(UUID room,UUID member,Connection connection,boolean host){this.connection=connection;gate=new CabinetSyncGate(room,member,connection,host);}
    }
    private static final class Transfer {
        final UUID token=UUID.randomUUID();final byte[] state;final String hash;final long frame,goal,deadline;
        int offset;boolean begin;
        Transfer(byte[] state,String hash,long frame,long goal,long now){this.state=state;this.frame=frame;this.goal=goal;deadline=now+900;this.hash=hash;}
    }
    static long now(MinecraftServer server){return Integer.toUnsignedLong(server.getTickCount());}
    static void open(MinecraftServer server,CabinetRoomLedger.Room<CabinetTarget> room){if(room.mode==CabinetSyncMode.LOCAL_SYNC)STATES.computeIfAbsent(server,k->new HashMap<>()).put(room.id,new Sync(room,now(server)));}
    private static Sync get(MinecraftServer server,UUID room){var states=STATES.get(server);return states==null?null:states.get(room);}
    static boolean started(MinecraftServer server,UUID room){var s=get(server,room);return s!=null&&s.identity!=null;}
    private static CabinetRoomLedger.Member authorized(ServerPlayer player,UUID room,UUID member,int epoch){
        if(epoch!=1)return null;var s=get(player.getServer(),room);if(s==null||CabinetRooms.syncRoom(player.getServer(),room)!=s.room)return null;
        var m=CabinetRooms.authorized(player,room,member);if(m==null)return null;
        var peer=s.peers.get(member);return peer==null||peer.gate.accepts(room,member,epoch,player.connection.getConnection())?m:null;
    }
    static void hello(ServerPlayer player,CabinetSyncNetwork.Hello p){
        var m=authorized(player,p.room(),p.member(),p.epoch());if(m==null)return;var server=player.getServer();var s=get(server,p.room());
        var backend=ResourceLocation.parse(s.room.backend);
        if(!p.romHash().equals(CabinetSharedGameService.validatedGameHash(player,m.id,backend))
                ||!p.contentId().equals(CabinetSharedGameService.validatedContentId(player,m.id,backend))){CabinetRooms.syncClose(server,m,"共享游戏校验或租约失效");return;}
        if(!s.policy.acceptsHost(p.identity())){CabinetRooms.syncClose(server,m,"核心指纹与服务器已核验的同步配置不符，未启动本地同步");return;}
        if(s.peers.containsKey(m.id))return;
        if(m.port==0){
            if(s.identity!=null)return;s.identity=p;s.timeline=new CabinetSyncTimeline(p.fpsMilli());s.peers.put(m.id,new Peer(p.room(),m.id,player.connection.getConnection(),true));
        }else{
            var expected=s.identity;
            if(expected==null||!s.policy.acceptsGuest(expected.identity(),p.identity())){CabinetRooms.syncClose(server,m,"游戏/依赖/核心版本或初始状态不一致，未加入；可退出后选择媒体模式");return;}
            s.peers.put(m.id,new Peer(p.room(),m.id,player.connection.getConnection(),false));repair(server,s,m,false);
        }
    }
    static void input(ServerPlayer player,CabinetSyncNetwork.Input p){
        var m=authorized(player,p.room(),p.member(),p.epoch());if(m==null)return;var s=get(player.getServer(),p.room());var peer=s.peers.get(m.id);
        if(peer==null||!peer.gate.input(p.room(),p.member(),p.epoch(),player.connection.getConnection())||s.timeline==null)return;
        var change=CabinetRooms.syncInput(player,p);
        if(m.rateLimited){CabinetRooms.syncClose(player.getServer(),m,"同步输入发送过快，已释放本人席位");return;}
        if(change!=null){if(p.reset())s.timeline.releaseGameplay(m.port);else s.timeline.input(m.port,change.mask());}
    }
    static boolean coin(ServerPlayer player,CabinetRoomLedger.Room<CabinetTarget> room,CabinetRoomLedger.Member member){
        var s=get(player.getServer(),room.id);if(s==null||s.room!=room||s.timeline==null)return false;
        var peer=s.peers.get(member.id);
        return peer!=null&&peer.gate.input(room.id,member.id,1,player.connection.getConnection())&&s.timeline.coin(member.port);
    }
    static void upload(ServerPlayer player,CabinetSyncNetwork.Upload packet){
        var p=packet.part();var m=authorized(player,p.room(),p.member(),p.epoch());if(m==null||m.port!=0)return;
        var server=player.getServer();var s=get(server,p.room());
        if(s.identity==null||s.timeline==null||p.frame()>s.timeline.frame()||p.goal()!=p.frame())return;
        if(p.begin()){
            if(s.offer!=null&&s.offer.token().equals(p.token())){send(player,new CabinetSyncNetwork.UploadGrant(p.room(),p.member(),p.epoch(),p.token()));return;}
            // Max four rooms x (old cache + one incoming state) = 128 MiB. Restore readers retain the same cache array.
            if(s.incoming!=null||s.verifying||p.frame()<=s.snapshotFrame||s.peers.values().stream().anyMatch(v->v.transfer!=null)
                    ||s.snapshotFrame<0&&(p.frame()!=0||!p.hash().equals(s.identity.initialHash())))return;
            s.offer=p;s.incoming=new CabinetSyncState(p.token(),p.total(),p.hash());s.uploadDeadline=now(server)+900;
            send(player,new CabinetSyncNetwork.UploadGrant(p.room(),p.member(),p.epoch(),p.token()));return;
        }
        var offer=s.offer;
        if(offer==null||s.incoming==null||s.verifying||!offer.token().equals(p.token())||offer.total()!=p.total()||offer.frame()!=p.frame()||!offer.hash().equals(p.hash()))return;
        if(!s.incoming.append(p.token(),p.offset(),p.bytes())||!s.incoming.complete())return;
        s.verifying=true;var incoming=s.incoming;var connection=player.connection.getConnection();
        try{VERIFY.execute(()->{
            byte[] verified=null;try{verified=incoming.finish();}catch(RuntimeException ignored){}final byte[] result=verified;
            server.execute(()->{
                if(get(server,p.room())!=s||s.offer!=offer)return;
                s.incoming=null;s.offer=null;s.verifying=false;
                if(result==null||player.connection.getConnection()!=connection||authorized(player,p.room(),p.member(),p.epoch())!=m)return;
                s.snapshot=result;s.snapshotHash=p.hash();s.snapshotFrame=p.frame();
                if(!s.room.ready){var host=s.peers.get(m.id);host.gate.activateHost();host.cursor=0;CabinetRooms.syncReady(player,new CabinetRoomNetwork.Ready(s.room.id,m.id));send(player,new CabinetSyncNetwork.Active(s.room.id,m.id,1,s.room.id));}
            });
        });}catch(RejectedExecutionException failure){s.incoming=null;s.offer=null;s.verifying=false;}
    }
    static void ack(ServerPlayer player,CabinetSyncNetwork.Ack p){
        var m=authorized(player,p.room(),p.member(),p.epoch());if(m==null||m.port==0)return;var s=get(player.getServer(),p.room());var peer=s.peers.get(m.id);
        if(peer==null||peer.transfer==null||!peer.transfer.token.equals(p.token()))return;var t=peer.transfer;
        if(!CabinetSyncRecoveryWindow.available(s.timeline.frame(),t.frame)){CabinetRooms.syncClose(player.getServer(),m,"本次恢复的快照窗口已过期，请稍后重新申请，主持游戏继续");return;}
        if(!CabinetSyncRecoveryWindow.canActivate(s.timeline.frame(),p.frame())){CabinetRooms.syncClose(player.getServer(),m,"本地追赶尚未完成或确认帧无效；已释放本人席位，请稍后重新申请，主持游戏继续");return;}
        if(!peer.gate.acknowledge(p.token(),p.frame(),peer.cursor,t.offset==t.state.length,now(player.getServer())))return;
        peer.transfer=null;send(player,new CabinetSyncNetwork.Active(p.room(),p.member(),p.epoch(),p.token()));
    }
    static void digest(ServerPlayer player,CabinetSyncNetwork.Digest p){
        var m=authorized(player,p.room(),p.member(),p.epoch());if(m==null)return;var server=player.getServer();var s=get(server,p.room());var peer=s.peers.get(m.id);
        if(peer==null||!peer.gate.active()||s.timeline==null||p.frame()>s.timeline.frame()||s.timeline.frame()-p.frame()>1800)return;
        if(m.port==0){s.digests.putIfAbsent(p.frame(),p.hash());s.digests.keySet().removeIf(f->f<p.frame()-1800);}
        else{peer.digests.putIfAbsent(p.frame(),p.hash());peer.digests.keySet().removeIf(f->f<p.frame()-1800);}
        for(var member:s.room.members)if(member!=null&&member.port>0){var guest=s.peers.get(member.id);if(guest==null||!guest.gate.active())continue;
            var expected=s.digests.get(p.frame());var actual=guest.digests.get(p.frame());
            if(expected!=null&&actual!=null){guest.digests.remove(p.frame());if(!expected.equals(actual))repair(server,s,member,true);}
        }
    }
    static void resync(ServerPlayer player,CabinetSyncNetwork.Resync p){var m=authorized(player,p.room(),p.member(),p.epoch());if(m==null)return;var s=get(player.getServer(),p.room());if(m.port==0)CabinetRooms.syncClose(player.getServer(),m,"主持同步队列失效，已安全结束；未重置或切换模式");else repair(player.getServer(),s,m,true);}
    private static void repair(MinecraftServer server,Sync s,CabinetRoomLedger.Member m,boolean correction){
        var peer=s.peers.get(m.id);if(m.port==0||peer==null||peer.transfer!=null||s.snapshot==null)return;
        if(correction&&now(server)<peer.nextRepair)return;
        if(correction&&++peer.repairs>3){CabinetRooms.syncClose(server,m,"本地核心连续状态不一致，已退出本人席位；主持继续");return;}
        if(!CabinetSyncRecoveryWindow.available(s.timeline.frame(),s.snapshotFrame)){CabinetRooms.syncClose(server,m,"本次加入的同步快照已过期；请等待主持更新快照后重新申请，主持游戏继续");return;}
        var transfer=new Transfer(s.snapshot,s.snapshotHash,s.snapshotFrame,s.timeline.frame(),now(server));
        if(!peer.gate.begin(transfer.token,transfer.goal,transfer.deadline)){CabinetRooms.syncClose(server,m,"同步事务已失效");return;}
        peer.nextRepair=now(server)+40;peer.digests.clear();s.timeline.releaseGameplay(m.port);m.mask=0;m.inputSeen=false;
        peer.transfer=transfer;peer.cursor=s.snapshotFrame;
    }
    static void release(MinecraftServer server,UUID room,int port){var s=get(server,room);if(s!=null&&s.timeline!=null)s.timeline.releaseGameplay(port);}
    static void removed(MinecraftServer server,UUID room,UUID member,int port){var s=get(server,room);if(s==null)return;if(port==0){s.peers.values().forEach(p->p.gate.close());STATES.get(server).remove(room);return;}s.loadDeadlines.remove(member);var peer=s.peers.remove(member);if(peer!=null)peer.gate.close();if(s.timeline!=null)s.timeline.release(port);}
    private static boolean loadExpired(MinecraftServer server,Sync s,CabinetRoomLedger.Member member,long started){
        long time=now(server),deadline=s.loadDeadlines.getOrDefault(member.id,started+3600);
        var player=server.getPlayerList().getPlayer(member.player);
        if(player!=null&&CabinetSharedGameService.isLoading(player,member.id))deadline=Math.max(deadline,Math.min(started+6000,time+400));
        s.loadDeadlines.put(member.id,deadline);return time>=Math.min(started+6000,deadline);
    }
    static void tick(MinecraftServer server){
        var map=STATES.get(server);if(map==null)return;
        for(var s:List.copyOf(map.values())){
            if(CabinetRooms.syncRoom(server,s.room.id)!=s.room){map.remove(s.room.id);continue;}
            if(!s.room.ready&&loadExpired(server,s,s.room.host(),s.created)){CabinetRooms.syncClose(server,s.room.host(),"同步核心启动超时");continue;}
            if(s.incoming!=null&&!s.verifying&&now(server)>=s.uploadDeadline){s.incoming=null;s.offer=null;}
            if(s.timeline==null||!s.room.ready)continue;
            if(s.snapshot==null||s.timeline.frame()-s.snapshotFrame>=CabinetSyncTimeline.MAX_HISTORY-10){CabinetRooms.syncClose(server,s.room.host(),"未取得新快照，有界历史已满；安全结束而非重开");continue;}
            s.timeline.tick();
            for(var m:s.room.members.clone())if(m!=null){
                var player=server.getPlayerList().getPlayer(m.player);if(player==null||authorized(player,s.room.id,m.id,1)!=m)continue;
                var peer=s.peers.get(m.id);if(peer==null){if(loadExpired(server,s,m,m.lastInput))CabinetRooms.syncClose(server,m,"本地ROM/核心准备超时");continue;}s.loadDeadlines.remove(m.id);
                var transfer=peer.transfer;
                if(transfer!=null){
                    if(m.port!=0&&!CabinetSyncRecoveryWindow.available(s.timeline.frame(),transfer.frame)){CabinetRooms.syncClose(server,m,"本次恢复已用完旧快照的追帧窗口；已释放本人席位，请稍后重新申请，主持游戏继续");continue;}
                    if(now(server)>=transfer.deadline){CabinetRooms.syncClose(server,m,"本地追帧超时，已释放本人席位");continue;}
                    if(!transfer.begin){var part=new CabinetSyncNetwork.StatePart(s.room.id,m.id,1,transfer.token,transfer.frame,transfer.goal,transfer.state.length,0,transfer.hash,true,new byte[0]);
                        if(!sendBounded(player,new CabinetSyncNetwork.Restore(part),512))continue;transfer.begin=true;}
                    for(int n=0;n<2&&transfer.offset<transfer.state.length;n++){
                        int count=Math.min(CabinetSyncState.CHUNK,transfer.state.length-transfer.offset);byte[] bytes=Arrays.copyOfRange(transfer.state,transfer.offset,transfer.offset+count);
                        var part=new CabinetSyncNetwork.StatePart(s.room.id,m.id,1,transfer.token,transfer.frame,transfer.goal,transfer.state.length,transfer.offset,transfer.hash,false,bytes);
                        if(!sendBounded(player,new CabinetSyncNetwork.Restore(part),count+512))break;transfer.offset+=count;
                    }
                    if(transfer.offset<transfer.state.length)continue;
                }
                try{var steps=s.timeline.after(peer.cursor,CabinetSyncTimeline.MAX_BATCH);if(!steps.isEmpty()&&sendBounded(player,new CabinetSyncNetwork.Frames(s.room.id,m.id,1,steps),steps.size()*24+256))peer.cursor=steps.getLast().frame();}
                catch(IllegalArgumentException expired){if(m.port==0)CabinetRooms.syncClose(server,m,"主持端未能跟上权威帧");else repair(server,s,m,true);}
            }
        }
    }
    private static boolean sendBounded(ServerPlayer player,CustomPacketPayload payload,int size){return CabinetSyncSender.send(player.connection.getConnection(),payload,size);}
    private static void send(ServerPlayer player,CustomPacketPayload payload){if(player!=null&&!player.hasDisconnected()&&player.connection.getConnection().isConnected())PacketDistributor.sendToPlayer(player,payload);}
    static void stopped(MinecraftServer server){STATES.remove(server);}
}
