package cn.piq.fcarcade.cabinet;

import cn.piq.fcarcade.access.PlayerContentAccess;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Metadata-only catalog. Selecting never grants arbitrary files or bypasses the live host lease. */
final class CabinetGameLibraryService {
    private static final org.slf4j.Logger LOGGER=com.mojang.logging.LogUtils.getLogger();
    private static final Map<MinecraftServer,State> STATES=new WeakHashMap<>();
    private static final ThreadPoolExecutor IO=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(8),r->{var t=new Thread(r,"PIQ-Cabinet-Library-IO");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private static final class State {final Map<Connection,Long> last=new WeakHashMap<>();final Map<UUID,Selection> pending=new HashMap<>();final CabinetLibraryReplyQueue<Connection,Delivery> replies=new CabinetLibraryReplyQueue<>();}
    private record Selection(ServerPlayer player,Connection connection,CabinetGameNetwork.LibraryRequest request,CabinetTarget target,CabinetGameManifest previous,CabinetGameManifest selected,long began) {}
    private record Delivery(ServerPlayer player,Connection connection,CabinetGameNetwork.LibraryRequest request,CabinetTarget target,CabinetGameNetwork.LibraryReply response) {}
    private CabinetGameLibraryService(){}
    static void register(){NeoForge.EVENT_BUS.addListener(CabinetGameLibraryService::tick);NeoForge.EVENT_BUS.addListener(CabinetGameLibraryService::stopped);}
    private static boolean current(ServerPlayer p,Connection c){return p!=null&&p.getServer()!=null&&p.getServer().isSameThread()&&p.isAlive()&&!p.isSpectator()&&!p.hasDisconnected()&&p.connection.getConnection()==c&&c.isConnected()&&p.getServer().getPlayerList().getPlayer(p.getUUID())==p;}
    private static CabinetTarget target(ServerPlayer p,CabinetGameNetwork.LibraryRequest r){var t=ServerCabinets.validateLease(p,r.lease(),r.backend());if(t==null)return null;var primary=CabinetRooms.gameTarget(p,r.lease(),r.backend());return primary==null?t:primary;}
    static boolean isSelecting(ServerPlayer p,UUID lease){var s=STATES.get(p.getServer());var selection=s==null?null:s.pending.get(p.getUUID());return selection!=null&&selection.request.lease().equals(lease);}
    private static boolean valid(Selection s){return current(s.player,s.connection)&&System.nanoTime()-s.began<30_000_000_000L&&CabinetRooms.canConfigureGame(s.player,s.request.lease())&&s.target.equals(target(s.player,s.request))&&Objects.equals(s.previous,CabinetSharedGameData.get(s.player.getServer()).find(s.target,s.request.backend().toString()))&&CabinetSharedGameData.get(s.player.getServer()).catalog(s.request.backend().toString()).contains(s.selected)&&current(s.player,s.connection)&&PlayerContentAccess.canUseServerRom(s.player);}
    static void handle(ServerPlayer p,CabinetGameNetwork.LibraryRequest r){
        if(p==null||p.getServer()==null||!p.getServer().isSameThread())return;var connection=p.connection.getConnection();if(!current(p,connection))return;
        var server=p.getServer();var state=STATES.computeIfAbsent(server,k->new State());long now=System.nanoTime();Long previousTime=state.last.put(connection,now);if(previousTime!=null&&now-previousTime<250_000_000L)return;
        state.replies.remove(connection);
        var target=target(p,r);if(target==null||!CabinetRooms.canConfigureGame(p,r.lease())||!PlayerContentAccess.canBrowse(p)){reply(p,r,false,"需要内容使用权限及空闲主机控制权",0,0,List.of());return;}
        var catalog=CabinetSharedGameData.get(server).catalog(r.backend().toString());
        if(r.edit()!=null){
            if(!p.hasPermissions(2)||!PlayerContentAccess.canUseServerRom(p)||!CabinetCoinPolicy.supported(r.backend().toString())){reply(p,r,false,"只有管理员可以修改服务器街机游戏资料",0,0,List.of());return;}
            var game=catalog.stream().filter(m->m.contentId().equals(r.selectedContentId())).findFirst().orElse(null);
            if(game==null){reply(p,r,false,"游戏已不在目录中，请刷新",0,0,List.of());return;}
            try{
                if(!CabinetGameProfiles.get(server).update(game,r.edit())){reply(p,r,false,"其他管理员已修改，请返回并刷新后重试",0,0,List.of());return;}
                CabinetRooms.refreshGameProfiles(server);
                int offset=(catalog.indexOf(game)/CabinetLibraryPage.SIZE)*CabinetLibraryPage.SIZE;
                reply(p,r,true,"已保存游戏资料；人数/横竖屏仅标注，不修改游戏内设置",offset,catalog.size(),catalog.subList(offset,Math.min(offset+CabinetLibraryPage.SIZE,catalog.size())));
            }catch(IllegalStateException invalid){reply(p,r,false,"游戏资料无法保存："+invalid.getMessage(),0,0,List.of());}
            return;
        }
        if(r.selectedContentId().isEmpty()){
            if(!PlayerContentAccess.canUseServerRom(p)){reply(p,r,true,"服务器已有 ROM 选用已关闭；本地上传按独立权限控制",0,0,List.of());return;}
            int offset=pageOffset(r.offset(),catalog.size());reply(p,r,true,"服务器已共享游戏（仅当前模拟器）",offset,catalog.size(),catalog.subList(offset,Math.min(offset+CabinetLibraryPage.SIZE,catalog.size())));return;
        }
        if(!PlayerContentAccess.canUseServerRom(p)||state.pending.containsKey(p.getUUID())||state.pending.size()>=4||CabinetSharedGameService.hasTransfer(p)){reply(p,r,false,"没有已有 ROM 选用权限，或机柜正在传输/选择",0,0,List.of());return;}
        var selected=catalog.stream().filter(m->m.contentId().equals(r.selectedContentId())).findFirst().orElse(null);if(selected==null){reply(p,r,false,"游戏不在当前服务器目录中，请刷新",0,0,List.of());return;}
        var selection=new Selection(p,connection,r,target,CabinetSharedGameData.get(server).find(target,r.backend().toString()),selected,now);state.pending.put(p.getUUID(),selection);
        var store=CabinetServerContent.store(server);var directory=CabinetServerContent.directory(server);
        try{IO.execute(()->{boolean verified=true;try{for(var file:selected.files())if(!store.contains(file)){verified=false;break;}if(verified)CabinetContentIndex.write(directory,selected);}catch(Exception error){verified=false;}boolean ready=verified;
            server.execute(()->{if(state.pending.get(p.getUUID())!=selection)return;boolean authorized=valid(selection);state.pending.remove(p.getUUID(),selection);if(!authorized||!ready){if(current(p,connection))reply(p,r,false,ready?"权限或机柜已改变，原游戏未替换":"服务器文件不完整，请管理员重新上传",0,0,List.of());return;}
                try{CabinetSharedGameData.get(server).put(target,selected);reply(p,r,true,"已选择服务器游戏，可启动",0,0,List.of());}catch(RuntimeException failure){reply(p,r,false,"机柜绑定已满，原游戏未替换",0,0,List.of());}});
        });}catch(RejectedExecutionException busy){state.pending.remove(p.getUUID(),selection);reply(p,r,false,"服务器目录校验繁忙，请稍后重试",0,0,List.of());}
    }
    static int pageOffset(int requested,int total){return CabinetLibraryPage.offset(requested,total);}
    private static void reply(ServerPlayer p,CabinetGameNetwork.LibraryRequest r,boolean ok,String message,int offset,int total,List<CabinetGameManifest> games){
        var connection=p.connection.getConnection();if(!current(p,connection))return;
        var response=new CabinetGameNetwork.LibraryReply(r.request(),r.lease(),r.backend(),ok,message,PlayerContentAccess.capabilities(p),offset,total,games,games.stream().map(CabinetGameProfiles.get(p.getServer())::find).toList(),p.hasPermissions(2)&&CabinetCoinPolicy.supported(r.backend().toString()));
        var delivery=new Delivery(p,connection,r,target(p,r),response);
        var state=STATES.computeIfAbsent(p.getServer(),k->new State());state.replies.remove(connection);
        if(!send(delivery)&&!state.replies.offer(connection,delivery,System.nanoTime()))failed(delivery);
    }
    private static boolean send(Delivery d){
        if(!current(d.player,d.connection))return true;
        var p=d.player;var r=d.request;var response=d.response;
        // A queued catalog is never authority: recheck lease, target and permissions on every attempt.
        if(response.success()&&(d.target==null||!d.target.equals(target(p,r))||!CabinetRooms.canConfigureGame(p,r.lease())||!PlayerContentAccess.canBrowse(p)
                ||(!response.games().isEmpty()||!r.selectedContentId().isEmpty())&&!PlayerContentAccess.canUseServerRom(p))){
            response=new CabinetGameNetwork.LibraryReply(r.request(),r.lease(),r.backend(),false,"权限或机柜已变化，请重新打开目录",PlayerContentAccess.capabilities(p),0,0,List.of());
        }else{
            response=new CabinetGameNetwork.LibraryReply(response.request(),response.lease(),response.backend(),response.success(),response.message(),PlayerContentAccess.capabilities(p),response.offset(),response.total(),response.games(),response.profiles(),p.hasPermissions(2)&&CabinetCoinPolicy.supported(r.backend().toString()));
        }
        return CabinetMediaSender.sendPayload(d.connection,response,CabinetLibraryPage.conservativeBytes(response.games().size()),false);
    }
    private static void failed(Delivery d){
        if(!current(d.player,d.connection))return;
        var r=d.request;
        var error=new CabinetGameNetwork.LibraryReply(r.request(),r.lease(),r.backend(),false,"服务器目录发送繁忙，请稍后刷新；文件未删除",PlayerContentAccess.capabilities(d.player),0,0,List.of());
        if(!CabinetMediaSender.sendPayload(d.connection,error,CabinetLibraryPage.conservativeBytes(0),false))
            LOGGER.warn("[PIQ FC] Cabinet library reply could not be sent; request={}, backend={}",r.request(),r.backend());
    }
    private static void tick(ServerTickEvent.Post event){var state=STATES.get(event.getServer());if(state==null)return;state.pending.values().removeIf(s->!valid(s));state.replies.tick(System.nanoTime(),d->current(d.player,d.connection),CabinetGameLibraryService::send,CabinetGameLibraryService::failed);}
    private static void stopped(ServerStoppedEvent event){STATES.remove(event.getServer());}
}
