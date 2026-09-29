package cn.piq.fcarcade.cabinet;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import net.minecraft.network.Connection;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Server-controlled manifests; immutable data IO is isolated from the server thread. */
public final class CabinetSharedGameService {
    private static final Map<MinecraftServer,State> STATES=new WeakHashMap<>();
    private static final ThreadPoolExecutor IO=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(64),r->{Thread t=new Thread(r,"PIQ-Cabinet-Game-IO");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private static final long TIMEOUT=300_000_000_000L, PROGRESS_TIMEOUT=20_000_000_000L;
    private static final class State {
        final Map<UUID,Job> jobs=new LinkedHashMap<>();final Map<UUID,Grant> grants=new HashMap<>();
        final Map<Connection,CabinetGameBudget> outbound=new WeakHashMap<>(),inbound=new WeakHashMap<>();
        final CabinetGameBudget totalOut=new CabinetGameBudget(2L*1024*1024,512*1024,System.nanoTime());
        final CabinetGameBudget totalIn=new CabinetGameBudget(8L*1024*1024,512*1024,System.nanoTime());
    }
    private record Grant(ServerPlayer player,Connection connection,UUID lease,ResourceLocation backend,CabinetTarget target,CabinetGameManifest manifest){}
    private record Pending(CabinetGameNetwork.Reply reply,boolean terminal){}
    private static final class Job {
        final UUID id,lease;final ServerPlayer player;final Connection connection;final ResourceLocation backend;final CabinetTarget target;final CabinetGameManifest manifest;final CabinetGameStore store;final boolean upload;
        final long began=System.nanoTime();final AtomicBoolean closed=new AtomicBoolean();final CabinetGameTransfer flow;
        // Replies/progress are server-thread-only; plan construction/temporary belong to the single IO worker.
        final ArrayDeque<Pending> replies=new ArrayDeque<>();long progress=began;volatile CabinetGameUploadPlan uploadPlan;Path temporary;
        Job(ServerPlayer p,CabinetGameNetwork.Command c,CabinetTarget target,CabinetGameManifest m){id=c.transaction();lease=c.lease();player=p;connection=p.connection.getConnection();backend=c.backend();this.target=target;manifest=m;upload=c.manifest()!=null;flow=new CabinetGameTransfer(m.files().stream().mapToInt(CabinetGameManifest.Entry::size).toArray());store=new CabinetGameStore(cn.piq.retro.storage.ConsoleStorage.root(p.getServer().getWorldPath(LevelResource.ROOT)).resolve("piq-cabinet/shared-games/objects"));}
    }
    private CabinetSharedGameService(){}
    public static void register(){NeoForge.EVENT_BUS.addListener(CabinetSharedGameService::tick);NeoForge.EVENT_BUS.addListener(CabinetSharedGameService::stopped);CabinetGameLibraryService.register();}
    private static State state(MinecraftServer s){return STATES.computeIfAbsent(s,k->new State());}
    private static boolean current(ServerPlayer p,Connection c){return c!=null&&p.getServer()!=null&&p.getServer().isSameThread()&&p.isAlive()&&!p.isSpectator()&&p.getServer().getPlayerList().getPlayer(p.getUUID())==p&&!p.hasDisconnected()&&p.connection.getConnection()==c&&c.isConnected();}
    private static CabinetTarget target(ServerPlayer p,UUID lease,ResourceLocation backend){
        CabinetTarget value=ServerCabinets.validateLease(p,lease,backend);if(value==null)return WatchNetplay.gameTarget(p,lease,backend);
        CabinetTarget primary=CabinetRooms.gameTarget(p,lease,backend);return primary==null?value:primary;
    }
    private static boolean valid(Job j){return !j.closed.get()&&System.nanoTime()-j.began<TIMEOUT&&current(j.player,j.connection)&&j.target.equals(target(j.player,j.lease,j.backend))&&(!j.upload||CabinetRooms.canConfigureGame(j.player,j.lease)&&cn.piq.fcarcade.access.PlayerContentAccess.canUploadRom(j.player))&&current(j.player,j.connection);}
    /** Only an exact authorized job with recent real reply progress may extend loading. */
    static boolean hasTransfer(ServerPlayer p){var s=STATES.get(p.getServer());return s!=null&&s.jobs.values().stream().anyMatch(j->j.player==p);}
    public static boolean isLoading(ServerPlayer p,UUID lease){
        MinecraftServer server=p.getServer();if(server==null||!server.isSameThread())return false;
        State s=STATES.get(server);if(s==null)return false;long now=System.nanoTime();
        return s.jobs.values().stream().anyMatch(j->j.player==p&&j.lease.equals(lease)&&now-j.progress<=PROGRESS_TIMEOUT&&valid(j));
    }
    public static String validatedGameHash(ServerPlayer p,UUID lease,ResourceLocation backend){Grant g=grant(p,lease,backend);return g==null?null:g.manifest.gameHash();}
    public static String validatedContentId(ServerPlayer p,UUID lease,ResourceLocation backend){Grant g=grant(p,lease,backend);return g==null?null:g.manifest.contentId();}
    static CabinetGameManifest validatedManifest(ServerPlayer p,UUID lease,ResourceLocation backend){Grant g=grant(p,lease,backend);return g==null?null:g.manifest;}
    private static Grant grant(ServerPlayer p,UUID lease,ResourceLocation backend){State s=STATES.get(p.getServer());Grant g=s==null?null:s.grants.get(lease);if(g==null||g.player!=p||!g.backend.equals(backend)||!current(p,g.connection)||!g.target.equals(target(p,lease,backend)))return null;CabinetGameManifest active=CabinetSharedGameData.get(p.getServer()).find(g.target,backend.toString());return g.manifest.equals(active)?g:null;}
    static void handle(ServerPlayer player,CabinetGameNetwork.Command command){
        MinecraftServer server=player.getServer();Connection connection=player.connection.getConnection();
        if(server==null||!server.isSameThread()||!current(player,connection))return;State s=state(server);
        if(!receive(s,connection,command.dataLength()+2048))return;
        if(command.operation()==CabinetGameNetwork.OPEN){open(player,command,s);return;}
        Job j=s.jobs.get(command.transaction());
        if(j==null||j.player!=player||j.connection!=player.connection.getConnection()||!j.lease.equals(command.lease())||!j.backend.equals(command.backend()))return;
        if(command.operation()==CabinetGameNetwork.CANCEL){close(s,j);return;}
        if(!valid(j)){fail(s,j,command.sequence(),"机柜权限或连接已改变，请重新右键");return;}
        if(!j.flow.reserve(command.sequence(),command.operation()==CabinetGameNetwork.END)){fail(s,j,command.sequence(),"游戏传输序列或队列超限");return;}
        try{IO.execute(()->process(server,s,j,command));}catch(RejectedExecutionException full){fail(s,j,command.sequence(),"游戏传输繁忙，请稍后重试");}
    }
    private static void open(ServerPlayer p,CabinetGameNetwork.Command c,State s){
        CabinetTarget t=target(p,c.lease(),c.backend());
        if(c.sequence()!=0||t==null){reply(s,p,c,false,null,0,0,new byte[0],"请先右键有效机柜");return;}
        if(s.jobs.containsKey(c.transaction())||s.jobs.values().stream().anyMatch(j->j.player==p)||s.jobs.size()>=4){reply(s,p,c,false,null,0,0,new byte[0],"游戏传输正在进行，请稍后重试");return;}
        if(c.manifest()!=null&&(!cn.piq.fcarcade.access.PlayerContentAccess.canUploadRom(p)||!CabinetRooms.canConfigureGame(p,c.lease())||CabinetGameLibraryService.isSelecting(p,c.lease())||!c.manifest().backend().equals(c.backend().toString()))){reply(s,p,c,false,null,0,0,new byte[0],"需要上传 ROM 权限且机柜空闲，才能上传并更换游戏");return;}
        CabinetGameManifest m=c.manifest()!=null?c.manifest():CabinetSharedGameData.get(p.getServer()).find(t,c.backend().toString());
        if(m==null){reply(s,p,c,false,null,0,0,new byte[0],"尚未共享此机柜游戏；请管理员 Shift 空手右键重新选择一次并上传");return;}
        Job j=new Job(p,c,t,m);s.jobs.put(j.id,j);MinecraftServer server=p.getServer();
        try{IO.execute(()->{String error=null;try{if(j.closed.get())return;
            if(!j.upload){for(var f:m.files())if(!j.store.contains(f))throw new java.io.IOException("服务器缺少游戏文件，请管理员重新上传");}
            else{
                int missingMask=0;for(int i=0;i<m.files().size();i++)if(!j.store.contains(m.files().get(i)))missingMask|=1<<i;
                var plan=CabinetGameUploadPlan.fromVerifiedMissing(m,missingMask);
                // A fully verified hit creates no staging/object and must work even at the cache cap.
                if(plan.missingBytes()>0)j.store.checkQuota(plan.missingBytes());
                j.uploadPlan=plan;
            }
        }catch(Exception ex){error=message(ex);}String result=error;
            server.execute(()->{if(s.jobs.get(j.id)!=j)return;if(!valid(j)||result!=null){fail(s,j,0,result==null?"连接已改变":result);return;}
                enqueue(s,j,new CabinetGameNetwork.Reply(j.id,0,true,m,j.upload?j.uploadPlan.missingMask():0,0,0,new byte[0],j.upload?(j.uploadPlan.missingMask()==0?"已复用服务器游戏文件":"正在上传缺失游戏文件"):"正在检查机柜游戏"),false);});});}
        catch(RejectedExecutionException full){fail(s,j,0,"游戏传输繁忙");}
    }
    private static void process(MinecraftServer server,State s,Job j,CabinetGameNetwork.Command c){
        byte[] bytes=new byte[0];String error=null;
        try{
            if(j.closed.get())return;
            switch(c.operation()){
                case CabinetGameNetwork.PUT -> {
                    if(!j.upload||j.uploadPlan==null)throw new java.io.IOException("上传分片顺序错误");
                    byte[] data=c.bytes();j.uploadPlan.validate(c.file(),c.offset(),data.length);
                    var entry=j.manifest.files().get(c.file());
                    if(c.offset()==0)j.temporary=j.store.temporary(j.id,c.file());j.store.append(j.temporary,c.offset(),data);
                    if(c.offset()+data.length==entry.size()){j.store.commit(j.temporary,entry);j.temporary=null;}
                    j.uploadPlan.accepted(c.file(),c.offset(),data.length);
                }
                case CabinetGameNetwork.GET -> {
                    if(j.upload)throw new java.io.IOException("不允许下载此文件");
                    int expected=j.flow.download(c.file(),c.offset());bytes=j.store.chunk(j.manifest.files().get(c.file()),c.offset());
                    if(bytes.length!=expected)throw new java.io.IOException("下载大小已改变");
                }
                case CabinetGameNetwork.END -> {
                    if(j.upload){if(j.uploadPlan==null)throw new java.io.IOException("游戏尚未上传完成");j.uploadPlan.requireComplete();}
                    for(var entry:j.manifest.files())if(!j.store.contains(entry))throw new java.io.IOException("游戏校验失败");
                }
                default -> throw new java.io.IOException("Unsupported transfer operation");
            }
        }catch(Exception failure){error=message(failure);}
        byte[] result=bytes;String problem=error;
        server.execute(()->{
            if(s.jobs.get(j.id)!=j)return;
            if(!valid(j)||problem!=null){fail(s,j,c.sequence(),problem==null?"机柜权限或连接已改变":problem);return;}
            if(c.operation()==CabinetGameNetwork.END){
                try{Runnable commit=()->{if(j.upload)CabinetSharedGameData.get(server).put(j.target,j.manifest);
                        s.grants.put(j.lease,new Grant(j.player,j.connection,j.lease,j.backend,j.target,j.manifest));};
                    if(j.upload)j.uploadPlan.finish(commit);else commit.run();
                    enqueue(s,j,new CabinetGameNetwork.Reply(j.id,c.sequence(),true,j.manifest,0,0,new byte[0],"机柜游戏已同步"),true);
                }catch(RuntimeException failed){fail(s,j,c.sequence(),message(failed));}
            }else enqueue(s,j,new CabinetGameNetwork.Reply(j.id,c.sequence(),true,null,c.file(),c.offset(),result,""),false);
        });
    }
    private static void enqueue(State s,Job j,CabinetGameNetwork.Reply reply,boolean terminal){
        if(s.jobs.get(j.id)!=j)return;
        if(j.replies.size()>=CabinetGameTransfer.PIPELINE){fail(s,j,reply.sequence(),"游戏回复队列超限");return;}
        j.replies.addLast(new Pending(reply,terminal));flush(s,j);
    }
    private static void flush(State s,Job j){
        while(s.jobs.get(j.id)==j&&!j.replies.isEmpty()){
            if(!valid(j)){fail(s,j,0,"机柜权限或连接已改变");return;}
            Pending pending=j.replies.getFirst();if(!send(s,j.connection,pending.reply))return;
            j.replies.removeFirst();j.flow.delivered(pending.reply.sequence());j.progress=System.nanoTime();
            if(pending.terminal){close(s,j);return;}
        }
    }
    private static boolean receive(State s,Connection connection,int bytes){
        long now=System.nanoTime();CabinetGameBudget budget=s.inbound.computeIfAbsent(connection,c->new CabinetGameBudget(2L*1024*1024,128*1024,now));
        if(!budget.permits(bytes,now)||!s.totalIn.permits(bytes,now))return false;
        budget.charge(bytes,now);s.totalIn.charge(bytes,now);return true;
    }
    private static boolean send(State s,Connection connection,CabinetGameNetwork.Reply reply){
        // Bulk replies have no manifest; 1 KiB still bounds fields/UTF-8 text while leaving
        // room to transfer the full 128 MiB manifest within the fixed 300-second lifetime.
        int bytes=reply.dataLength()+(reply.manifest()==null?1024:4096);long now=System.nanoTime();
        CabinetGameBudget budget=s.outbound.computeIfAbsent(connection,c->new CabinetGameBudget(512L*1024,128*1024,now));
        if(!budget.permits(bytes,now)||!s.totalOut.permits(bytes,now)||!CabinetMediaSender.sendPayload(connection,reply,bytes,false))return false;
        budget.charge(bytes,now);s.totalOut.charge(bytes,now);return true;
    }
    private static void fail(State s,Job j,int sequence,String error){if(current(j.player,j.connection))send(s,j.connection,new CabinetGameNetwork.Reply(j.id,sequence,false,null,0,0,new byte[0],shortMessage(error)));close(s,j);}
    private static void reply(State s,ServerPlayer p,CabinetGameNetwork.Command c,boolean ok,CabinetGameManifest m,int file,int offset,byte[] bytes,String text){send(s,p.connection.getConnection(),new CabinetGameNetwork.Reply(c.transaction(),c.sequence(),ok,m,file,offset,bytes,shortMessage(text)));}
    private static String message(Throwable e){return e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();}
    private static String shortMessage(String value){String s=value==null?"传输失败":value.replaceAll("[\\p{Cntrl}]"," ");return s.substring(0,Math.min(160,s.length()));}
    private static void close(State s,Job j){s.jobs.remove(j.id,j);if(!j.closed.compareAndSet(false,true))return;j.replies.clear();j.flow.close();var plan=j.uploadPlan;if(plan!=null)plan.close();try{IO.execute(()->{try{j.store.discard(j.temporary);}catch(Exception ignored){}});}catch(RejectedExecutionException ignored){/* Bounded staging remains subject to storage quota. */}}
    private static void tick(ServerTickEvent.Post e){State s=STATES.get(e.getServer());if(s==null)return;for(Job j:List.copyOf(s.jobs.values())){if(!valid(j))fail(s,j,0,"游戏同步取消：已离开、权限改变或传输超时");else flush(s,j);}s.grants.entrySet().removeIf(e2->grant(e2.getValue().player,e2.getKey(),e2.getValue().backend)==null);}
    private static void stopped(ServerStoppedEvent e){State s=STATES.remove(e.getServer());if(s!=null)for(Job j:List.copyOf(s.jobs.values()))close(s,j);}
}
