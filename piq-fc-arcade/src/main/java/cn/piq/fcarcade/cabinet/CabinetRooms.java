package cn.piq.fcarcade.cabinet;

import cn.piq.fcarcade.server.ServerArcadeSessions;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/** Server-thread room authority. Only a physically authorized host can send bounded media to its seats. */
public final class CabinetRooms {
    private CabinetRooms() {}
    private static final Map<MinecraftServer,State> STATES=new WeakHashMap<>();
    public static final ResourceLocation WATCH_PROVIDER=ResourceLocation.fromNamespaceAndPath("piq_fc_arcade","cabinet");
    private static boolean watchRegistered;
    static synchronized void registerWatchProvider(){
        if(watchRegistered)return;
        WatchProviders.register(WATCH_PROVIDER,new WatchProvider(){
            @Override public java.util.List<WatchSource> sources(MinecraftServer server){
                var state=STATES.get(server);if(state==null)return java.util.List.of();
                var result=new java.util.ArrayList<WatchSource>();
                for(var room:state.ledger.all())if(room.ready)result.add(watchSource(state,room));return java.util.List.copyOf(result);
            }
            @Override public boolean isCurrent(MinecraftServer server,WatchSource source){
                var state=STATES.get(server);if(state==null)return false;var room=state.ledger.get(source.descriptor().source());
                if(room==null||!room.ready||!watchSource(state,room).equals(source))return false;
                var host=server.getPlayerList().getPlayer(room.host().player);return host!=null&&validateLease(host,room.host().id,ResourceLocation.parse(room.backend))!=null;
            }
            @Override public boolean isParticipant(MinecraftServer server,UUID player){var state=STATES.get(server);return state!=null&&state.ledger.player(player)!=null;}
            @Override public boolean isParticipant(MinecraftServer server,WatchSource source,UUID player){var state=STATES.get(server);var member=state==null?null:state.ledger.player(player);return member!=null&&member.room.equals(source.descriptor().source());}
            @Override public boolean acceptsUpload(){return false;}
            @Override public WatchNetplay.Offer netplay(ServerPlayer p,WatchSource source){return CabinetNetplay.observation(p.getServer(),source.descriptor().source());}
            @Override public boolean serverHosted(MinecraftServer server,WatchSource source){var room=syncRoom(server,source.descriptor().source());return room!=null&&room.mode==CabinetSyncMode.SERVER_MEDIA;}
        });watchRegistered=true;
    }
    private static WatchSource watchSource(State state,CabinetRoomLedger.Room<CabinetTarget> room){
        var primary=new WatchAnchor(room.target.anchor(),room.target.identity());var secondary=state.secondary.get(room.id);
        var screens=secondary==null?java.util.List.of(primary):java.util.List.of(primary,new WatchAnchor(secondary.anchor(),secondary.identity()));
        return new WatchSource(new WatchDescriptor(WATCH_PROVIDER,room.id,room.streamHostId,room.target.dimension(),primary,null,screens),room.mode==CabinetSyncMode.SERVER_MEDIA?room.ownerId:room.host().player);
    }
    private static final class State {
        final CabinetRoomLedger<CabinetTarget> ledger=new CabinetRoomLedger<>();
        final Map<UUID,ServerCabinets.Binding> bindings=new HashMap<>();
        final Map<UUID,CabinetRoomMedia> media=new HashMap<>();
        final Map<UUID,CabinetTarget> secondary=new HashMap<>();
        final Map<UUID,Long> nextHeartbeat=new HashMap<>();
        final Map<UUID,CabinetJoinGate> joins=new HashMap<>();
        final Map<UUID,JoinRequest> pending=new HashMap<>();
        final Map<UUID,UUID> moderators=new HashMap<>();
        final Map<UUID,int[]> hostedRecipients=new HashMap<>();
        final CabinetIdleShutdown<UUID> idleShutdown=new CabinetIdleShutdown<>();
        int hostedRoundRobin;
        final Map<UUID,Long> pgmServiceNext=new HashMap<>();
    }
    private record JoinRequest(ServerPlayer player,net.minecraft.network.Connection connection,ServerCabinets.Binding binding,CabinetJoinGate.Pending pending){}
    private static long now(MinecraftServer server){return Integer.toUnsignedLong(server.getTickCount());}
    private static boolean current(ServerPlayer player){var server=player.getServer();return server!=null&&server.isSameThread()&&!player.hasDisconnected()&&player.connection.getConnection().isConnected()&&server.getPlayerList().getPlayer(player.getUUID())==player;}
    private static void notice(ServerPlayer player,String text){player.displayClientMessage(Component.literal(text),true);}
    static boolean hasPlayer(ServerPlayer player){var s=STATES.get(player.getServer());return s!=null&&s.ledger.player(player.getUUID())!=null;}
    private static CabinetRoomLedger.Room<CabinetTarget> roomAt(State state,CabinetTarget target){
        var primary=state.ledger.target(target);if(primary!=null)return primary;
        for(var entry:state.secondary.entrySet())if(entry.getValue().equals(target))return state.ledger.get(entry.getKey());
        return null;
    }
    static boolean hasTarget(MinecraftServer server,CabinetTarget target){var s=STATES.get(server);return s!=null&&roomAt(s,target)!=null;}
    /** One group label at the primary cabinet; both screens still receive video. */
    static Map<CabinetTarget,String> occupancy(MinecraftServer server){
        var result=new java.util.LinkedHashMap<CabinetTarget,String>();var state=STATES.get(server);
        if(state==null)return result;
        for(var room:state.ledger.all()){
            var backend=ResourceLocation.parse(room.backend);
            String names=state.ledger.occupantNames(room,member->{
                var player=server.getPlayerList().getPlayer(member.player);
                return player!=null&&validateLease(player,member.id,backend)!=null
                        ?player.getGameProfile().getName():null;
            });
            if(names.isBlank())continue;
            result.put(room.target,names);
        }
        return result;
    }
    static boolean contains(MinecraftServer server,UUID member){var s=STATES.get(server);return s!=null&&s.ledger.member(member)!=null;}
    record CoinAdmission(CabinetRoomLedger.Room<CabinetTarget> room,CabinetRoomLedger.Member member,CabinetTarget target,CabinetTarget secondary,
                         cn.piq.fcarcade.world.LegacyFcArcadeBlockEntity primaryEntity,cn.piq.fcarcade.world.LegacyFcArcadeBlockEntity secondaryEntity){}
    static CoinAdmission coinAdmission(ServerPlayer player,CabinetTarget target){
        if(!current(player))return null;var state=STATES.get(player.getServer());if(state==null)return null;
        var member=state.ledger.player(player.getUUID());if(member==null)return null;var room=state.ledger.get(member.room);
        if(room==null||!room.ready||!room.coinRequired||!CabinetCoinPolicy.supported(room.backend)
                ||!target.equals(validateLease(player,member.id,ResourceLocation.parse(room.backend))))return null;
        var secondary=state.secondary.get(room.id);var level=player.serverLevel();
        if(!(level.getBlockEntity(room.target.anchor()) instanceof cn.piq.fcarcade.world.LegacyFcArcadeBlockEntity primaryEntity)
                ||!room.backend.equals(primaryEntity.cabinetBackend().toString()))return null;
        cn.piq.fcarcade.world.LegacyFcArcadeBlockEntity secondaryEntity=null;
        if(secondary!=null){if(!(level.getBlockEntity(secondary.anchor()) instanceof cn.piq.fcarcade.world.LegacyFcArcadeBlockEntity peer)
                    ||!room.backend.equals(peer.cabinetBackend().toString()))return null;secondaryEntity=peer;}
        if(room.mode==CabinetSyncMode.MEDIA){var host=player.getServer().getPlayerList().getPlayer(room.host().player);
            if(host==null||validateLease(host,room.host().id,ResourceLocation.parse(room.backend))==null)return null;}
        return new CoinAdmission(room,member,target,secondary,primaryEntity,secondaryEntity);
    }
    /** Caller has just revalidated the exact physical interaction and ItemStack. No protection callbacks here. */
    static boolean insertCoin(ServerPlayer player,CoinAdmission admission){
        var room=admission.room();var member=admission.member();var server=player.getServer();var state=STATES.get(server);
        if(state==null||state.ledger.get(room.id)!=room||state.ledger.member(member.id)!=member||!room.ready||!room.coinRequired||coinSupportProblem(player,admission)!=null)return false;
        if(room.mode==CabinetSyncMode.LOCAL_SYNC&&!CabinetNetplay.active(room.id))return CabinetSynchronizer.coin(player,room,member);
        if(room.mode==CabinetSyncMode.SERVER_MEDIA){var run=CabinetHostedSessions.get(server,room.id);return run!=null&&run.coin(member.port);}
        var host=server.getPlayerList().getPlayer(room.host().player);
        if(host==null||host.connection.getConnection()!=room.host().connection||!host.connection.getConnection().isConnected()||room.coinSequence==Long.MAX_VALUE)return false;
        send(host,new CabinetRoomNetwork.Coin(room.id,room.host().id,member.id,member.port,++room.coinSequence));return true;
    }
    static String coinSupportProblem(ServerPlayer player,CoinAdmission admission){
        var room=admission.room();
        if(room.mode==CabinetSyncMode.LOCAL_SYNC)return null; // Authoritative timeline keeps paid edges independently.
        if(room.mode==CabinetSyncMode.MEDIA)return room.coinReleaseSupported?null:
                "未扣币：主持玩家需更新街机附属至 0.1.1 及配套运行库，当前核心不支持保币松键";
        var run=CabinetHostedSessions.get(player.getServer(),room.id);
        return run!=null&&run.supportsCoinPreservingRelease()?null:
                "未扣币：服务端需更新街机附属至 0.1.1 及配套运行库，当前核心不支持保币松键";
    }
    public static CabinetTarget gameTarget(ServerPlayer player,UUID lease,ResourceLocation backend){
        var target=validateLease(player,lease,backend);if(target==null)return null;var state=STATES.get(player.getServer());var member=state.ledger.member(lease);return state.ledger.get(member.room).target;
    }
    public static boolean canConfigureGame(ServerPlayer player,UUID lease){
        if(player==null||!current(player))return false;var state=STATES.get(player.getServer());if(state==null)return false;var member=state.ledger.member(lease);
        if(member==null||member.port!=0||!member.player.equals(player.getUUID()))return false;var room=state.ledger.get(member.room);
        return room!=null&&!room.ready&&CabinetHostedSessions.get(player.getServer(),room.id)==null&&!CabinetSynchronizer.started(player.getServer(),room.id)&&validateLease(player,lease,ResourceLocation.parse(room.backend))!=null;
    }
    static CabinetRoomLedger.Room<CabinetTarget> syncRoom(MinecraftServer server,UUID id){var s=STATES.get(server);return s==null?null:s.ledger.get(id);}
    static void syncClose(MinecraftServer server,CabinetRoomLedger.Member member,String reason){var s=STATES.get(server);if(s!=null)close(server,s,member,reason);}
    static CabinetRoomLedger.Change syncInput(ServerPlayer player,CabinetSyncNetwork.Input p){var s=STATES.get(player.getServer());return p.reset()?s.ledger.resetInput(player.getUUID(),p.room(),p.member(),p.sequence(),now(player.getServer())):s.ledger.input(player.getUUID(),p.room(),p.member(),p.sequence(),p.mask(),now(player.getServer()));}

    static void interact(ServerPlayer player,ServerCabinets.Binding binding,ResourceLocation backend){
        if(!current(player)||!ServerCabinets.valid(player,binding,false))return;
        var server=player.getServer();var state=STATES.computeIfAbsent(server,s->new State());
        var own=state.ledger.player(player.getUUID());
        if(own!=null){
            var room=state.ledger.get(own.room);
            if(state.bindings.get(own.id).target().equals(binding.target())){
                if((own.port==0||room.mode==CabinetSyncMode.SERVER_MEDIA)&&room.ready&&CabinetCoinPolicy.supported(room.backend)){
                    own.controlling=!own.controlling;forward(server,state,state.ledger.reset(own));
                    if(!own.controlling&&closeAfterManualExit(server,state,room,true))return;
                    updateIdleShutdown(state,room);
                    send(player,new CabinetRoomNetwork.Control(room.id,own.id,own.controlling));
                    notice(player,own.controlling?"已恢复操作":idleNotice(room));
                }else {
                    boolean wasControlling=own.controlling;
                    close(server,state,own,"已退出街机；再次右键加入或启动");
                    if(state.ledger.get(room.id)==room)closeAfterManualExit(server,state,room,wasControlling);
                }
            }
            else notice(player,"请先退出当前街机，再使用其他机柜。");
            return;
        }
        // A request grants no seat. Re-click its exact cabinet to withdraw it; never queue in two rooms.
        for(var entry:state.pending.entrySet())if(entry.getValue().player().getUUID().equals(player.getUUID())){
            var pendingRoom=state.ledger.get(entry.getKey());var gate=state.joins.get(entry.getKey());
            if(pendingRoom==null||gate==null)return;
            if(entry.getValue().player()!=player||entry.getValue().connection()!=player.connection.getConnection()){
                gate.clear();finishPending(server,state,pendingRoom,entry.getValue().pending(),"申请连接已失效");break;
            }
            if(entry.getValue().binding().target().equals(binding.target())){
                gate.cancel(player.getUUID());finishPending(server,state,pendingRoom,entry.getValue().pending(),"已取消加入申请");
            }
            else notice(player,"已有待批准的加入申请；再次右键原机柜可取消。");
            return;
        }
        if(ServerCabinets.hasLocalLease(player)||ServerArcadeSessions.hasPlayerCabinetSession(player)
                ||ServerCabinets.hasLocalTarget(server,binding.target())
                ||ServerArcadeSessions.hasCabinetSession(server,player.level().dimension(),binding.target().anchor())){
            notice(player,"请先正常结束当前模拟器会话。");return;
        }
        int supported=CabinetBackends.maxPlayers(backend);
        if(supported<1||supported>4)return;
        var primary=CabinetLinks.master(server,binding.target());var secondary=CabinetLinks.peer(server,primary);
        if(CabinetLinks.hasLink(server,binding.target())&&secondary==null){notice(player,"通讯线另一端未加载或不可用，请先恢复两台机柜。");return;}
        var room=roomAt(state,binding.target());
        var mode=room==null?CabinetSyncSettings.get(server,primary,backend):room.mode;
        boolean netplay=room==null?CabinetSyncSettings.netplay(server,primary,backend):CabinetNetplay.active(room.id);
        if(room==null){
            if(CabinetHostedSessions.isTargetBusy(server,primary)||secondary!=null&&CabinetHostedSessions.isTargetBusy(server,secondary)){notice(player,"上一局仍在关闭或保存，请稍后再开机。");return;}
            String denied=mode==CabinetSyncMode.SERVER_MEDIA?CabinetHostedSessions.unavailable(server,primary,backend):
                mode==CabinetSyncMode.LOCAL_SYNC&&!CabinetHostingConfig.localAllowed()?"服主已禁用本地同步":
                mode==CabinetSyncMode.MEDIA&&!CabinetHostingConfig.playerAllowed()?"服主已禁用玩家托管":null;
            if(denied!=null){notice(player,denied+"；请管理员在空闲时更改同步方式");return;}
        }
        if(mode==CabinetSyncMode.LOCAL_SYNC){
            supported=netplay?CabinetNetplay.maxPlayers(backend):CabinetBackends.syncMaxPlayers(backend);
            if(supported==0){notice(player,"此附属未支持已配置的本地同步模式，请在空闲时改为音画串流。");return;}
        }
        int capacity=CabinetSeats.capacity(supported,secondary!=null,primary.dual(),secondary!=null&&secondary.dual(),player.serverLevel().getBlockEntity(primary.anchor()) instanceof cn.piq.fcarcade.world.PortraitCabinetBlockEntity);
        if(capacity==0){notice(player,mode==CabinetSyncMode.LOCAL_SYNC?"本地同步最多支持 "+supported+" 席，当前通讯线席位超限；请断开通讯线或在空闲时改为音画串流。":"此模拟器的控制席位不支持当前通讯线组合，请先断开通讯线。");return;}
        if(room!=null){
            var host=server.getPlayerList().getPlayer(room.host().player);
            if(host==null||validateLease(host,room.host().id,backend)==null){close(server,state,room.host(),"主持连接已失效");return;}
            if(!room.ready){notice(player,"主持正在启动游戏，请稍后右键加入。");return;}
            if(!room.backend.equals(backend.toString()))return;
            requestJoin(player,state,room,binding);
            return;
        }
        if(!binding.target().equals(primary)){notice(player,"请先在通讯线的主街机启动游戏，再到副柜申请加入。");return;}
        if(secondary!=null&&(!secondary.matches(player.serverLevel())||ServerCabinets.isCabinetBusy(server,secondary))){notice(player,"通讯线另一端暂不可用。");return;}
        if(netplay&&(!CabinetNetplay.supported(backend)||capacity>CabinetNetplay.maxPlayers(backend))){notice(player,"Netplay 实验需配套附属；席位上限由附属声明。请管理员在设备设置修改，不会扣币。");return;}
        room=state.ledger.open(player.getUUID(),primary,backend.toString(),capacity,now(server));
        if(room==null){notice(player,"街机联机房间已满，请稍后重试。");return;}
        room.mode=mode;
        var primaryCabinet=(cn.piq.fcarcade.world.LegacyFcArcadeBlockEntity)player.serverLevel().getBlockEntity(primary.anchor());
        room.coinRequired=CabinetCoinPolicy.supported(backend.toString())&&primaryCabinet.coinRequired();
        copyServerRules(room,CabinetServerSettings.rules(server));
        room.host().connection=player.connection.getConnection();if(netplay)CabinetNetplay.open(server,room);else CabinetSynchronizer.open(server,room);
        var member=room.host();state.bindings.put(member.id,binding);state.media.put(room.id,new CabinetRoomMedia());if(secondary!=null)state.secondary.put(room.id,secondary);
        visualPower(server,state,room,true);
        if(room.capacity>1)state.joins.put(room.id,new CabinetJoinGate(player.getUUID(),member.id,mode==CabinetSyncMode.SERVER_MEDIA));
        ServerArcadeSessions.removeMachineDisplays(server,player.level().dimension(),room.target.anchor());
        if(secondary!=null)ServerArcadeSessions.removeMachineDisplays(server,player.level().dimension(),secondary.anchor());
        send(player,new CabinetRoomNetwork.Assignment(room.id,member.id,member.id,0,room.capacity,room.target,backend,room.target,secondary,room.mode,room.coinRequired));
        send(player,new CabinetRoomNetwork.Seat(room.id,member.id,member.id,0,true));
        if(mode==CabinetSyncMode.SERVER_MEDIA){state.moderators.put(room.id,member.id);send(player,new CabinetRoomNetwork.Moderator(room.id,member.id,true));}
        send(player,new CabinetNetwork.Launch(room.target,backend,member.id));
    }
    /** Same physical and legacy-session checks as the local lease path, without refreshing authority. */
    static CabinetTarget validateLease(ServerPlayer player,UUID id,ResourceLocation backend){
        if(!current(player)||id==null||backend==null)return null;
        var server=player.getServer();var state=STATES.get(server);if(state==null)return null;
        var member=state.ledger.member(id);var binding=state.bindings.get(id);
        if(member==null||binding==null||!member.player.equals(player.getUUID())||member.connection!=player.connection.getConnection()||now(server)>=member.expires)return null;
        var room=state.ledger.get(member.room);var entry=CabinetBackends.find(backend);
        if(room==null||entry==null||!room.backend.equals(backend.toString())||CabinetBackends.maxPlayers(backend)<room.capacity
                ||!backend.equals(binding.cabinet().cabinetBackend())||!ServerCabinets.validLease(player,binding,false,computingHost(room,member))
                ||!topology(server,state,room,player.serverLevel())
                ||ServerCabinets.hasLocalLease(player)||ServerArcadeSessions.hasPlayerCabinetSession(player)
                ||ServerArcadeSessions.hasCabinetSession(server,player.level().dimension(),room.target.anchor()))return null;
        return binding.target();
    }
    private static boolean computingHost(CabinetRoomLedger.Room<CabinetTarget> room,CabinetRoomLedger.Member member){
        return room!=null&&room.ready&&member==room.host()&&CabinetCoinPolicy.supported(room.backend);
    }
    private static boolean topology(MinecraftServer server,State state,CabinetRoomLedger.Room<CabinetTarget> room,ServerLevel level){
        var secondary=state.secondary.get(room.id);
        return room.target.matches(level)&&room.target.equals(CabinetLinks.master(server,room.target))
                &&java.util.Objects.equals(secondary,CabinetLinks.peer(server,room.target))
                &&room.capacity==CabinetSeats.capacity(CabinetNetplay.active(room.id)?CabinetNetplay.maxPlayers(ResourceLocation.parse(room.backend)):room.mode==CabinetSyncMode.LOCAL_SYNC?CabinetBackends.syncMaxPlayers(ResourceLocation.parse(room.backend)):CabinetBackends.maxPlayers(ResourceLocation.parse(room.backend)),secondary!=null,room.target.dual(),secondary!=null&&secondary.dual(),level.getBlockEntity(room.target.anchor()) instanceof cn.piq.fcarcade.world.PortraitCabinetBlockEntity)
                &&(secondary==null||secondary.matches(level));
    }
    static CabinetRoomLedger.Member authorized(ServerPlayer player,UUID roomId,UUID memberId){
        if(!current(player))return null;var state=STATES.get(player.getServer());if(state==null)return null;
        var room=state.ledger.get(roomId);if(room==null)return null;
        var member=state.ledger.valid(player.getUUID(),roomId,memberId,now(player.getServer()));
        return member!=null&&validateLease(player,memberId,ResourceLocation.parse(room.backend))!=null?member:null;
    }
    /** Server command entry: never grant a remote player or spectator control of P1. */
    public static String pgmService(ServerPlayer player){
        if(!current(player)||!player.hasPermissions(2))return "需要 OP2 权限。";
        var state=STATES.get(player.getServer());var member=state==null?null:state.ledger.player(player.getUUID());
        var room=member==null?null:state.ledger.get(member.room);
        if(room==null||!room.ready||member.port!=0||member!=room.host()||authorized(player,room.id,member.id)!=member)
            return "请先在主柜领取 1P 并等待游戏启动；副柜/旁观者不能操作维护菜单。";
        if(!CabinetNetplay.active(room.id))return "当前入口仅支持 FBNeo Netplay；旧 MAME/串流不使用这个入口。";
        var manifest=CabinetSharedGameService.validatedManifest(player,member.id,ResourceLocation.parse(room.backend));
        if(!PgmServicePolicy.supports(manifest))return "此附属尚未声明通用维护能力；FBNeo 街机需配套 0.1.5.2 或更新版本。";
        long tick=now(player.getServer());
        state.pgmServiceNext.keySet().removeIf(id->state.ledger.member(id)==null);
        if(tick<state.pgmServiceNext.getOrDefault(member.id,0L))return "维护按键刚发送过，请等待 15 秒后再试。";
        // Revalidate after all authorization callbacks, immediately before sending the bounded action.
        if(authorized(player,room.id,member.id)!=member)return "当前操作权限已改变，请重新领取 1P。";
        state.pgmServiceNext.put(member.id,tick+300);
        PacketDistributor.sendToPlayer(player,new CabinetRoomNetwork.PgmService(room.id,member.id));
        for(var seat:room.members)if(seat!=null){var other=player.getServer().getPlayerList().getPlayer(seat.player);
            if(other!=null)notice(other,"街机维护请求：请先松开游戏按键，由 OP 1P 调整；整局都会受影响。没有 Test 菜单的游戏不会响应。");}
        return null;
    }
    static void ready(ServerPlayer player,CabinetRoomNetwork.Ready packet){
        var r=syncRoom(player.getServer(),packet.room());if(r!=null&&r.mode==CabinetSyncMode.LOCAL_SYNC&&!CabinetNetplay.active(r.id))return;
        if(r!=null&&CabinetNetplay.active(r.id)&&CabinetSharedGameService.validatedManifest(player,packet.member(),ResourceLocation.parse(r.backend))==null)return;
        if(r!=null&&r.mode==CabinetSyncMode.SERVER_MEDIA){
            var member=authorized(player,packet.room(),packet.member());if(member==null||member!=r.host()||r.ready)return;
            var manifest=CabinetSharedGameService.validatedManifest(player,member.id,ResourceLocation.parse(r.backend));
            String problem=CabinetHostedSessions.start(player.getServer(),r,manifest);
            if(problem!=null)closeRoom(player.getServer(),STATES.get(player.getServer()),r,problem);
            return;
        }
        syncReady(player,packet);
    }
    static void syncReady(ServerPlayer player,CabinetRoomNetwork.Ready packet){
        var member=authorized(player,packet.room(),packet.member());if(member==null||member.port!=0)return;
        var state=STATES.get(player.getServer());long now=now(player.getServer());
        var room=state.ledger.get(member.room);if(room.ready)return;
        if(!state.ledger.ready(player.getUUID(),packet.room(),packet.member(),now))return;
        updateIdleShutdown(state,room);
        refreshGameProfiles(player.getServer());
        room.coinReleaseSupported=room.mode==CabinetSyncMode.LOCAL_SYNC||packet.coinReleaseSupported();
        if(room.coinRequired&&room.mode==CabinetSyncMode.MEDIA&&!room.coinReleaseSupported)
            notice(player,"实体投币尚不可用：请更新街机附属至 0.1.1 及配套运行库；当前不会收取银币");
        var gate=state.joins.get(packet.room());var offer=gate==null?null:gate.offer(now);
        if(offer!=null)send(player,new CabinetJoinNetwork.Offer(packet.room(),member.id,offer.token()));
    }
    static void allow(ServerPlayer player,CabinetJoinNetwork.Allow packet){
        var member=authorized(player,packet.room(),packet.hostMember());if(member==null||member!=syncRoom(player.getServer(),packet.room()).host())return;
        var state=STATES.get(player.getServer());var room=state.ledger.get(member.room);var gate=state.joins.get(member.room);
        var binding=state.bindings.get(member.id);
        if(gate==null||!gate.canAnswer(packet.token(),now(player.getServer()))||!room.ready||binding==null||!ServerCabinets.valid(player,binding,true)
                ||authorized(player,packet.room(),packet.hostMember())!=member)return;
        if(gate.allow(player.getUUID(),member.id,packet.token(),packet.enabled(),now(player.getServer())))
            send(player,new CabinetJoinNetwork.Result(room.id,packet.token(),packet.enabled()?(directJoin(room)?"本局允许 2P 直接加入":"已允许其他玩家申请加入；每次申请仍需你批准"):"本局不允许其他玩家加入；仍可旁观"));
    }
    private static boolean directJoin(CabinetRoomLedger.Room<CabinetTarget> room){return CabinetBackends.NES.toString().equals(room.backend);}
    private static void requestJoin(ServerPlayer player,State state,CabinetRoomLedger.Room<CabinetTarget> room,ServerCabinets.Binding binding){
        if(room.capacity==1){notice(player,"此模拟器只有一个控制席位；你可以在附近旁观。");return;}
        var gate=state.joins.get(room.id);var server=player.getServer();
        if(gate==null||!gate.enabled()){notice(player,directJoin(room)?"本局不允许其他玩家加入。":"主人尚未允许多人申请；你可以先旁观。");return;}
        if(gate.pending()!=null||state.pending.containsKey(room.id)){notice(player,"主人正在处理另一位玩家的申请，请稍后重试。");return;}
        boolean main=binding.target().equals(room.target),linked=state.secondary.containsKey(room.id);
        int first=Math.max(room.mode==CabinetSyncMode.SERVER_MEDIA?0:1,CabinetSeats.first(room.target.dual(),linked,main)),end=CabinetSeats.end(room.target.dual(),linked,main,room.capacity),port=-1;
        for(int i=first;i<end;i++)if(room.members[i]==null){port=i;break;}
        if(port<0){notice(player,"这台街机的玩家席位已满。");return;}
        var pending=gate.request(player.getUUID(),port,now(server));
        if(pending==null){notice(player,"申请操作过快，请稍后重试。");return;}
        var request=new JoinRequest(player,player.connection.getConnection(),binding,pending);state.pending.put(room.id,request);
        if(!validPending(server,state,room,pending,request,true)){
            if(gate.pending()==pending)gate.clear();finishPending(server,state,room,pending,"申请条件已失效，请重新右键");return;
        }
        var host=server.getPlayerList().getPlayer(room.host().player);
        // The FC host's one-off session choice authorizes direct joining; retain the exact
        // connection/seat/protection transaction, but never send an interruption to the host.
        if(directJoin(room)){decide(host,new CabinetJoinNetwork.Decision(room.id,room.host().id,pending.token(),true));return;}
        send(host,new CabinetJoinNetwork.Approval(room.id,room.host().id,pending.token(),player.getUUID(),player.getGameProfile().getName(),port));
        notice(player,"已申请加入 P"+(port+1)+"，等待主人同意；再次右键本机取消");
    }
    /** Both permission callbacks complete before a final side-effect-free recheck of BOTH players. */
    private static boolean validPending(MinecraftServer server,State state,CabinetRoomLedger.Room<CabinetTarget> room,
            CabinetJoinGate.Pending pending,JoinRequest request,boolean permissionEvents){
        if(pending==null||request==null||request.pending()!=pending||state.pending.get(room.id)!=request
                ||state.ledger.get(room.id)!=room||!room.ready||now(server)>=pending.expires())return false;
        var gate=state.joins.get(room.id);var applicant=request.player();var host=server.getPlayerList().getPlayer(room.host().player);
        var hostBinding=state.bindings.get(room.host().id);var binding=request.binding();
        if(gate==null||!gate.enabled()||host==null||hostBinding==null||!current(applicant)
                ||applicant.connection.getConnection()!=request.connection()||!pending.applicant().equals(applicant.getUUID())
                ||state.ledger.player(applicant.getUUID())!=null||ServerCabinets.hasLocalLease(applicant)
                ||ServerArcadeSessions.hasPlayerCabinetSession(applicant)||!room.backend.equals(binding.cabinet().cabinetBackend().toString())
                ||!ServerCabinets.valid(applicant,binding,false)||validateLease(host,room.host().id,ResourceLocation.parse(room.backend))==null
                ||pending.port()>=room.capacity||room.members[pending.port()]!=null)return false;
        boolean main=binding.target().equals(room.target);
        if(!main&&!binding.target().equals(state.secondary.get(room.id))
                ||!CabinetSeats.owns(room.target.dual(),state.secondary.containsKey(room.id),main,room.capacity,pending.port()))return false;
        if(!permissionEvents)return true;
        if(!ServerCabinets.valid(host,hostBinding,true)||!ServerCabinets.valid(applicant,binding,true))return false;
        return validPending(server,state,room,pending,request,false);
    }
    static void decide(ServerPlayer player,CabinetJoinNetwork.Decision packet){
        var host=authorized(player,packet.room(),packet.hostMember());if(host==null||host!=syncRoom(player.getServer(),packet.room()).host())return;
        var server=player.getServer();var state=STATES.get(server);var room=state.ledger.get(host.room);var gate=state.joins.get(room.id);
        if(gate==null)return;var pending=gate.decide(player.getUUID(),host.id,packet.token(),now(server));
        if(pending==null)return; // Wrong, late, cancelled and replayed answers never grant a seat.
        var request=state.pending.get(room.id);
        try{
        if(!packet.accepted()){finishPending(server,state,room,pending,"主人拒绝了加入申请");return;}
        if(!validPending(server,state,room,pending,request,true)){finishPending(server,state,room,pending,"加入申请已失效，请重新右键");return;}
        var member=state.ledger.join(pending.applicant(),room,now(server),pending.port(),pending.port()+1);
        if(member==null){finishPending(server,state,room,pending,"该席位暂不可用，请重新申请");return;}
        member.connection=request.connection();
        updateIdleShutdown(state,room);
        finishPending(server,state,room,pending,"已批准加入 P"+(member.port+1));
        var applicant=request.player();var binding=request.binding();state.bindings.put(member.id,binding);
        send(applicant,new CabinetRoomNetwork.Assignment(room.id,member.id,room.streamHostId,member.port,room.capacity,binding.target(),ResourceLocation.parse(room.backend),room.target,state.secondary.get(room.id),room.mode,room.coinRequired));
        send(player,new CabinetRoomNetwork.Seat(room.id,room.streamHostId,member.id,member.port,true));
        if(room.mode==CabinetSyncMode.SERVER_MEDIA)updateModerator(server,state,room);
        notice(applicant,"已加入街机 P"+(member.port+1)+"；再次右键退出");
        }finally{
            // Even a throwing protection callback cannot strand the consumed transaction or erase its replacement.
            if(request!=null&&state.pending.get(room.id)==request)finishPending(server,state,room,pending,"加入申请已结束，请重新右键");
        }
    }
    private static void finishPending(MinecraftServer server,State state,CabinetRoomLedger.Room<CabinetTarget> room,
            CabinetJoinGate.Pending pending,String reason){
        if(pending==null)return;
        var request=state.pending.get(room.id);
        if(request==null||request.pending()!=pending||!state.pending.remove(room.id,request))return;
        var host=server.getPlayerList().getPlayer(room.host().player);
        if(host!=null)send(host,new CabinetJoinNetwork.Result(room.id,pending.token(),reason));
        if(request!=null&&current(request.player())&&request.player().connection.getConnection()==request.connection()){
            send(request.player(),new CabinetJoinNetwork.Result(room.id,pending.token(),reason));notice(request.player(),reason);
        }
    }
    static void input(ServerPlayer player,CabinetRoomNetwork.Input packet){
        var r=syncRoom(player.getServer(),packet.room());if(r!=null&&r.mode==CabinetSyncMode.LOCAL_SYNC&&!paidNetplay(r))return;
        var member=authorized(player,packet.room(),packet.member());if(member==null)return;
        var state=STATES.get(player.getServer());
        long previous=member.sequence;
        int mask=packet.mask();
        if(CabinetNetplay.active(r.id)&&PgmServicePolicy.supportsBackend(r.backend)){
            long expiry=state.pgmServiceNext.getOrDefault(member.id,Long.MIN_VALUE);
            boolean granted=member.port==0&&member==r.host()&&player.hasPermissions(2)&&now(player.getServer())<expiry;
            mask=PgmServicePolicy.filterInput(mask,granted);
        }
        var change=state.ledger.input(player.getUUID(),packet.room(),packet.member(),packet.seq(),mask,now(player.getServer()));
        if(change==null&&paidNetplay(r)&&previous<packet.seq()&&member.sequence==packet.seq())change=new CabinetRoomLedger.Change(r.id,member.id,member.port,++member.forwardSequence,member.mask,false);
        if(member.rateLimited){close(player.getServer(),state,member,"输入发送过快，已退出本玩家席位");return;}
        // P1 already feeds its local core. Forced resets still return to P1.
        if(change!=null&&(member.port!=0||r.mode==CabinetSyncMode.SERVER_MEDIA||paidNetplay(r)))forward(player.getServer(),state,change);
    }
    static void reset(ServerPlayer player,CabinetRoomNetwork.Reset packet){
        var r=syncRoom(player.getServer(),packet.room());if(r!=null&&r.mode==CabinetSyncMode.LOCAL_SYNC&&!paidNetplay(r))return;
        var member=authorized(player,packet.room(),packet.member());if(member==null)return;
        var state=STATES.get(player.getServer());
        var change=state.ledger.resetInput(player.getUUID(),packet.room(),packet.member(),packet.seq(),now(player.getServer()));
        if(member.rateLimited){close(player.getServer(),state,member,"输入发送过快，已退出本玩家席位");return;}
        forward(player.getServer(),state,change);
    }
    static void media(ServerPlayer player,CabinetRoomNetwork.Media packet){
        var live=syncRoom(player.getServer(),packet.room());if(live!=null&&live.mode==CabinetSyncMode.SERVER_MEDIA)return;
        var member=authorized(player,packet.room(),packet.hostMember());if(member==null||member.port!=0)return;
        var server=player.getServer();var state=STATES.get(server);var room=state.ledger.get(member.room);
        if(!room.ready)return;
        var complete=state.media.get(room.id).accept(packet.part(),now(server));
        if(complete.isEmpty())return;
        var batch=complete.stream().map(p->new CabinetMediaPacket(room.id,room.host().id,p.sequence(),p.kind(),p.index(),p.count(),p.width(),p.height(),p.aspect(),p.rotation(),p.rawLength(),p.data())).toList();
        for(var seat:room.members)if(room.mode==CabinetSyncMode.MEDIA&&seat!=null&&seat.port!=0){
            var guest=server.getPlayerList().getPlayer(seat.player);
            if(guest!=null&&validateLease(guest,seat.id,ResourceLocation.parse(room.backend))!=null)
                CabinetMediaSender.clientbound(guest.connection.getConnection(),seat.id,batch);
        }
        // Existing player streams always get first use of their Connection windows.
        WatchService.relay(server,room.id,room.host().id,batch);
    }
    static boolean heartbeat(ServerPlayer player,UUID id){
        if(!current(player))return true;
        var server=player.getServer();var state=STATES.get(server);if(state==null||state.ledger.member(id)==null)return false;
        var member=state.ledger.member(id);if(!member.player.equals(player.getUUID()))return true;
        long now=now(server);if(now<state.nextHeartbeat.getOrDefault(id,0L))return true;state.nextHeartbeat.put(id,now+5);
        var room=state.ledger.get(member.room);
        if(validateLease(player,id,ResourceLocation.parse(room.backend))==null)close(server,state,member,"机柜连接已失效");
        else state.ledger.heartbeat(player.getUUID(),id,now);
        return true;
    }
    static boolean release(ServerPlayer player,UUID id){
        if(!current(player))return true;
        var state=STATES.get(player.getServer());if(state==null||state.ledger.member(id)==null)return false;
        var member=state.ledger.member(id);if(member.player.equals(player.getUUID()))close(player.getServer(),state,member,"已退出街机");
        return true;
    }
    private static void forward(MinecraftServer server,State state,CabinetRoomLedger.Change change){
        if(change==null)return;var room=state.ledger.get(change.room());if(room==null)return;
        if(room.mode==CabinetSyncMode.SERVER_MEDIA){if(change.reset())CabinetHostedSessions.releaseGameplay(server,room,change.port());else CabinetHostedSessions.input(server,room);return;}
        if(room.mode==CabinetSyncMode.LOCAL_SYNC&&!paidNetplay(room)){CabinetSynchronizer.release(server,room.id,change.port());return;}
        var host=server.getPlayerList().getPlayer(room.host().player);
        if(host!=null)send(host,new CabinetRoomNetwork.Buttons(room.id,room.host().id,change.member(),change.port(),change.sequence(),change.mask(),change.reset()));
    }
    // The same canonical lane gates maintenance on free-play cabinets, without charging coins.
    private static boolean paidNetplay(CabinetRoomLedger.Room<CabinetTarget> room){return room!=null&&(room.coinRequired||PgmServicePolicy.supportsBackend(room.backend))&&CabinetNetplay.active(room.id);}
    /** Exact protected physical button only; never called from an arbitrary coordinate packet. */
    static boolean powerOff(ServerPlayer player,CabinetTarget target){
        var state=STATES.get(player.getServer());var room=state==null?null:roomAt(state,target);if(room==null)return false;
        if(!player.hasPermissions(2)&&!room.ownerId.equals(player.getUUID())){notice(player,"只有当前主持玩家或管理员可以关闭整组街机");return true;}
        closeRoom(player.getServer(),state,room,"正面开关键：整组街机已关闭");return true;
    }
    private static void close(MinecraftServer server,State state,CabinetRoomLedger.Member member,String reason){
        if(state.ledger.member(member.id)!=member)return;var room=state.ledger.get(member.room);
        if(room.mode==CabinetSyncMode.SERVER_MEDIA){
            // Stop only this port. The server core is not owned by the departing player's connection.
            state.ledger.reset(member);CabinetHostedSessions.release(server,room,member.port);
            var request=state.pending.get(room.id);if(request!=null)finishPending(server,state,room,request.pending(),"管理者或席位已改变，请重新申请");
            for(var removed:state.ledger.remove(member.player,member.id))forgetMember(server,state,removed,reason);
            if(state.ledger.get(room.id)==null){cleanupRoom(server,state,room);return;}
            CabinetHostedSessions.input(server,room);updateModerator(server,state,room);updateIdleShutdown(state,room);return;
        }
        CabinetNetplay.removed(room.id,member.connection,member.port==0);
        CabinetSynchronizer.removed(server,room.id,member.id,member.port);
        if(member.port==0){
            var gate=state.joins.remove(room.id);
            if(gate!=null){
                gate.clear();var request=state.pending.get(room.id);
                if(request!=null)finishPending(server,state,room,request.pending(),reason);
                var token=gate.expireOffer(Long.MAX_VALUE);var host=server.getPlayerList().getPlayer(member.player);
                if(token!=null&&host!=null)send(host,new CabinetJoinNetwork.Result(room.id,token,reason));
            }
        }
        if(member.port!=0){
            forward(server,state,state.ledger.reset(member));
            var host=server.getPlayerList().getPlayer(room.host().player);
            if(host!=null)send(host,new CabinetRoomNetwork.Seat(room.id,room.host().id,member.id,member.port,false));
        }
        for(var removed:state.ledger.remove(member.player,member.id)){
            state.bindings.remove(removed.id);state.nextHeartbeat.remove(removed.id);
            var player=server.getPlayerList().getPlayer(removed.player);
            if(player!=null)send(player,new CabinetNetwork.Closed(removed.id,reason));
        }
        if(member.port==0){state.idleShutdown.cancel(room.id);visualPower(server,state,room,false);WatchService.closed(server,room.id,room.host().id);state.media.remove(room.id);state.secondary.remove(room.id);}
        else updateIdleShutdown(state,room);
    }
    private static void forgetMember(MinecraftServer server,State state,CabinetRoomLedger.Member member,String reason){
        state.bindings.remove(member.id);state.nextHeartbeat.remove(member.id);var player=server.getPlayerList().getPlayer(member.player);
        if(player!=null&&player.connection.getConnection()==member.connection)send(player,new CabinetNetwork.Closed(member.id,shortReason(reason)));
    }
    private static String shortReason(String reason){String value=reason==null?"托管已结束":reason.replaceAll("[\\p{Cntrl}]"," ");return value.substring(0,Math.min(160,value.length()));}
    private static void cleanupRoom(MinecraftServer server,State state,CabinetRoomLedger.Room<CabinetTarget> room){
        state.idleShutdown.cancel(room.id);
        visualPower(server,state,room,false);
        CabinetHostedSessions.close(server,room.id);WatchService.closed(server,room.id,room.streamHostId);
        state.media.remove(room.id);state.secondary.remove(room.id);state.joins.remove(room.id);state.pending.remove(room.id);state.moderators.remove(room.id);state.hostedRecipients.remove(room.id);
    }
    private static void closeRoom(MinecraftServer server,State state,CabinetRoomLedger.Room<CabinetTarget> room,String reason){
        if(room.mode!=CabinetSyncMode.SERVER_MEDIA){close(server,state,room.host(),reason);return;}
        var request=state.pending.get(room.id);if(request!=null)finishPending(server,state,room,request.pending(),shortReason(reason));
        for(var removed:state.ledger.removeRoom(room.id))forgetMember(server,state,removed,reason);
        cleanupRoom(server,state,room);
    }
    private static void updateModerator(MinecraftServer server,State state,CabinetRoomLedger.Room<CabinetTarget> room){
        if(room.mode!=CabinetSyncMode.SERVER_MEDIA||room.host()==null)return;var manager=room.host();
        if(manager.id.equals(state.moderators.get(room.id)))return;
        var request=state.pending.get(room.id);if(request!=null)finishPending(server,state,room,request.pending(),"管理者已更换，请重新申请");
        state.moderators.put(room.id,manager.id);var gate=new CabinetJoinGate(manager.player,manager.id,true);state.joins.put(room.id,gate);
        for(var member:room.members)if(member!=null){var player=server.getPlayerList().getPlayer(member.player);if(player!=null&&player.connection.getConnection()==member.connection)send(player,new CabinetRoomNetwork.Moderator(room.id,member.id,member==manager));}
        if(room.ready&&room.capacity>1){var offer=gate.offer(now(server));var player=server.getPlayerList().getPlayer(manager.player);if(offer!=null&&player!=null)send(player,new CabinetJoinNetwork.Offer(room.id,manager.id,offer.token()));}
    }
    private static void tickHosted(MinecraftServer server,State state,CabinetRoomLedger.Room<CabinetTarget> room){
        var run=CabinetHostedSessions.get(server,room.id);if(run==null)return;
        if(run.error()!=null){closeRoom(server,state,room,shortReason(run.error()));return;}
        if(!room.ready&&run.ready()){
            room.ready=true;updateIdleShutdown(state,room);var gate=state.joins.get(room.id);var offer=gate==null?null:gate.offer(now(server));
            refreshGameProfiles(server);
            var manager=server.getPlayerList().getPlayer(room.host().player);if(manager!=null&&offer!=null)send(manager,new CabinetJoinNetwork.Offer(room.id,room.host().id,offer.token()));
        }
        for(int i=0;i<8;i++){
            var batch=run.poll();if(batch==null)break;if(!room.ready)continue;
            var cursors=state.hostedRecipients.computeIfAbsent(room.id,key->new int[2]);
            int start=Math.floorMod(cursors[batch.getFirst().kind()]++,room.members.length);
            for(int seat=0;seat<room.members.length;seat++){var member=room.members[(start+seat)%room.members.length];if(member==null)continue;
                var player=server.getPlayerList().getPlayer(member.player);
                if(player!=null&&validateLease(player,member.id,ResourceLocation.parse(room.backend))!=null&&CabinetHostedSessions.budget(server,room.id,batch))CabinetMediaSender.clientbound(player.connection.getConnection(),member.id,batch);
            }
            WatchService.relay(server,room.id,room.streamHostId,batch);
        }
    }
    /** Called only when readiness or control seats change, never on each input/heartbeat. */
    private static String idleNotice(CabinetRoomLedger.Room<CabinetTarget> room){
        if(room.immediateOnExit)return "已退出操作；最后一人右键退出后整机关机，右键可恢复操作";
        return room.autoPowerOff?"已退出操作；所有操作席空闲 "+room.idleShutdownSeconds+" 秒后自动关机，右键恢复":"已退出操作，游戏继续运行；右键恢复，正面开关键关机";
    }
    private static boolean closeAfterManualExit(MinecraftServer server,State state,CabinetRoomLedger.Room<CabinetTarget> room,boolean wasControlling){
        if(!CabinetCoinPolicy.supported(room.backend))return false;
        var rules=new CabinetServerRules(room.immediateOnExit,room.idleShutdownSeconds,16);
        if(!rules.closeAfterExit(wasControlling,room.hasController(),room.ready))return false;
        closeRoom(server,state,room,"最后一名玩家已退出操作，街机已关闭");return true;
    }
    private static void copyServerRules(CabinetRoomLedger.Room<CabinetTarget> room,CabinetServerRules rules){
        room.immediateOnExit=rules.immediateOnExit();room.autoPowerOff=rules.timedShutdown();room.idleShutdownSeconds=rules.idleSeconds();
    }
    /** A settings change visits active rooms once; does not enumerate players, blocks or chunks. */
    static void serverRulesChanged(MinecraftServer server,CabinetServerRules rules){
        var state=STATES.get(server);if(state==null)return;
        for(var room:state.ledger.all())if(CabinetCoinPolicy.supported(room.backend)){
            copyServerRules(room,rules);state.idleShutdown.cancel(room.id);updateIdleShutdown(state,room);
        }
    }
    private static void updateIdleShutdown(State state,CabinetRoomLedger.Room<CabinetTarget> room){
        if(!room.ready||!CabinetCoinPolicy.supported(room.backend)){state.idleShutdown.cancel(room.id);return;}
        state.idleShutdown.update(room.id,room.hasController(),room.autoPowerOff,room.idleShutdownSeconds,System.nanoTime());
    }
    static void tick(MinecraftServer server){
        WatchService.tick(server);
        var state=STATES.get(server);if(state==null)return;long now=now(server);
        for(var member:state.ledger.allMembers()){
            if(state.ledger.member(member.id)!=member)continue;
            var player=server.getPlayerList().getPlayer(member.player);var binding=state.bindings.get(member.id);var room=state.ledger.get(member.room);
            if(player!=null&&binding!=null&&computingHost(room,member)&&member.controlling&&!ServerCabinets.withinControlRange(player,binding.target())){
                member.controlling=false;forward(server,state,state.ledger.reset(member));
                updateIdleShutdown(state,room);
                send(player,new CabinetRoomNetwork.Control(room.id,member.id,false));
                notice(player,idleNotice(room));
            }
            if(player==null||binding==null||validateLease(player,member.id,ResourceLocation.parse(room.backend))==null
                    ||(now%20==0&&!ServerCabinets.validLease(player,binding,true,computingHost(room,member))))close(server,state,member,"机柜连接已结束");
        }
        for(var id:state.idleShutdown.pollDue(System.nanoTime())){
            var room=state.ledger.get(id);
            if(room!=null&&room.ready&&room.autoPowerOff&&CabinetCoinPolicy.supported(room.backend)&&!room.hasController())
                closeRoom(server,state,room,"所有操作席已空闲 "+room.idleShutdownSeconds+" 秒，街机已自动关机");
        }
        for(var change:state.ledger.silence(now))forward(server,state,change);
        CabinetSynchronizer.tick(server);CabinetNetplay.tick(server);
        var hosted=state.ledger.all().stream().filter(room->room.mode==CabinetSyncMode.SERVER_MEDIA).toList();
        if(!hosted.isEmpty()){int start=Math.floorMod(state.hostedRoundRobin++,hosted.size());for(int i=0;i<hosted.size();i++)tickHosted(server,state,hosted.get((start+i)%hosted.size()));}
        for(var room:state.ledger.all()){
            var gate=state.joins.get(room.id);if(gate==null)continue;
            var token=gate.expireOffer(now);var host=server.getPlayerList().getPlayer(room.host().player);
            if(token!=null&&host!=null)send(host,new CabinetJoinNetwork.Result(room.id,token,"多人选择已超时，本局保持单人"));
            var pending=gate.pending();if(pending==null)continue;
            if(now>=pending.expires())finishPending(server,state,room,gate.expire(now),"加入申请已超时，请重新右键");
            else if(!validPending(server,state,room,pending,state.pending.get(room.id),now%20==0)){
                if(gate.pending()==pending)gate.clear();finishPending(server,state,room,pending,"加入申请条件已失效");
            }
        }
    }
    static void logout(ServerPlayer player){WatchService.logout(player);var state=STATES.get(player.getServer());if(state!=null){
        for(var room:state.ledger.all()){
            var gate=state.joins.get(room.id);var request=state.pending.get(room.id);
            if(gate!=null&&request!=null&&request.player()==player){
                gate.cancel(player.getUUID());finishPending(player.getServer(),state,room,request.pending(),"申请玩家已离线");
            }
        }
        var m=state.ledger.player(player.getUUID());if(m!=null)close(player.getServer(),state,m,"玩家已离线");
    }}
    static void removed(ServerLevel level,BlockPos pos,UUID identity){var state=STATES.get(level.getServer());if(state==null)return;
        for(var room:state.ledger.all()){
            var secondary=state.secondary.get(room.id);
            if(matches(room.target,level,pos,identity)||secondary!=null&&matches(secondary,level,pos,identity))closeRoom(level.getServer(),state,room,"机柜已拆除或卸载");
        }
    }
    private static boolean matches(CabinetTarget target,ServerLevel level,BlockPos pos,UUID id){return target.dimension().equals(level.dimension().location())&&target.anchor().equals(pos)&&target.identity().equals(id);}
    static void refreshGameProfiles(MinecraftServer server){
        var state=STATES.get(server);if(state==null)return;
        for(var room:state.ledger.all()){
            refreshGameProfile(server,room.target);refreshGameProfile(server,state.secondary.get(room.id));
        }
    }
    private static void refreshGameProfile(MinecraftServer server,CabinetTarget target){
        if(target==null)return;
        var level=server.getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,target.dimension()));
        if(level!=null&&level.hasChunkAt(target.anchor())&&level.getBlockEntity(target.anchor()) instanceof cn.piq.fcarcade.world.LegacyFcArcadeBlockEntity cabinet&&cabinet.cabinetId().equals(target.identity()))
            level.sendBlockUpdated(target.anchor(),cabinet.getBlockState(),cabinet.getBlockState(),net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
    }
    private static void visualPower(MinecraftServer server,State state,CabinetRoomLedger.Room<CabinetTarget> room,boolean on){
        visualPower(server,room.target,on);visualPower(server,state.secondary.get(room.id),on);
    }
    private static void visualPower(MinecraftServer server,CabinetTarget target,boolean on){
        if(target==null)return;
        var level=server.getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,target.dimension()));
        // Display only; do not force-load chunks or touch a replacement cabinet at this position.
        if(level!=null&&level.hasChunkAt(target.anchor())
                &&level.getBlockEntity(target.anchor()) instanceof cn.piq.fcarcade.world.LegacyFcArcadeBlockEntity cabinet
                &&cabinet.cabinetId().equals(target.identity()))cabinet.setVisualPowered(on);
    }
    static void stopped(MinecraftServer server){var state=STATES.get(server);if(state!=null)for(var room:state.ledger.all())visualPower(server,state,room,false);CabinetNetplay.stopped(server);CabinetHostedSessions.stopped(server);WatchService.stopped(server);CabinetSynchronizer.stopped(server);STATES.remove(server);}
    private static void send(ServerPlayer player,CustomPacketPayload payload){if(payload instanceof CabinetRoomNetwork.Assignment a&&CabinetNetplay.assignment(player,a))return;PacketDistributor.sendToPlayer(player,payload);}
}
