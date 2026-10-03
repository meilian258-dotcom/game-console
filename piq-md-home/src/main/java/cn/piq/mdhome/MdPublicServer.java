// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.mdhome;

import cn.piq.fcarcade.cabinet.*;
import cn.piq.fcarcade.home.*;
import cn.piq.fcarcade.home.content.*;
import cn.piq.fcarcade.home.flow.HomeLaunchServer;
import cn.piq.fcarcade.netplay.*;
import cn.piq.mdhome.save.MdPublicSaves;
import java.util.*;
import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/** Shared launch/save lifecycle; MEDIA streams pixels, JNI Netplay replicates trusted native state. */
@EventBusSubscriber(modid=MdMod.ID)
public final class MdPublicServer implements WatchProvider {
    private static final UUID ZERO=new UUID(0,0);
    private static final Map<MinecraftServer,Map<UUID,Session>> SESSIONS=new IdentityHashMap<>();
    private static final Set<Connection> PRIVATE=Collections.newSetFromMap(new IdentityHashMap<>());
    private MdPublicServer(){}
    public static void register(){WatchProviders.register(MdMod.SYSTEM,new MdPublicServer());}
    public static boolean current(ServerPlayer p){return p!=null&&!p.hasDisconnected()&&p.connection.getConnection().isConnected()&&p.getServer()!=null&&p.getServer().getPlayerList().getPlayer(p.getUUID())==p;}
    public static boolean privatePlay(ServerPlayer p){return PRIVATE.contains(p.connection.getConnection());}
    public static void preference(ServerPlayer p,boolean value){
        if(value)PRIVATE.add(p.connection.getConnection());else PRIVATE.remove(p.connection.getConnection());
        MdPublicNetwork.send(p,new MdPublicNetwork.Preference(value));
        say(p,value?"MD 下次开机：私人单人；卡带归属存档须改为公开游玩。":"MD 下次开机：公开游玩，沿用主机的运行模式；可允许第二手柄和附近旁观。");
    }
    private static Map<UUID,Session> sessions(MinecraftServer s){return SESSIONS.computeIfAbsent(s,k->new HashMap<>());}
    private static Session session(MdConsole c){return c.getLevel() instanceof net.minecraft.server.level.ServerLevel l?sessions(l.getServer()).get(c.hardwareId()):null;}
    public static boolean running(MdConsole c){return session(c)!=null;}
    public static boolean allowedPort(MdConsole c,int port){var s=session(c);return s==null||!s.closing&&(port==0||s.plan.allowSecondPort());}
    private static boolean modeAllowed(MdConsole c){return !c.netplayExperimental()&&(c.netplayJniTrial()?MdNetplayProfile.AVAILABLE&&CabinetHostingConfig.localAllowed():c.synchronizationMode()==CabinetSyncMode.MEDIA&&CabinetHostingConfig.playerAllowed());}
    public static boolean start(ServerPlayer p,MdConsole c,HomeSystems.Connection link,ContentCardStore.Entry entry){
        if(!modeAllowed(c)){say(p,c.netplayJniTrial()&&!MdNetplayProfile.AVAILABLE?MdNetplayProfile.UNAVAILABLE:"MD 当前运行模式未启用，请检查设备与服务器设置。");return false;}
        if(!current(p)||c.running()||sessions(p.getServer()).size()>=16||participant(p.getServer(),p.getUUID())){say(p,"已有 MD 会话或会话已满，请先结束原会话。");return false;}
        MdPublicSaves.choose(p,c,link,entry,(plan,flow)->begin(p,c,link,entry,plan,flow));return c.running();
    }
    private static void begin(ServerPlayer p,MdConsole c,HomeSystems.Connection link,ContentCardStore.Entry entry,MdPublicSaves.SavePlan plan,HomeLaunchServer.Handle flow){
        if(!current(p)||!modeAllowed(c)||c.running()||sessions(p.getServer()).size()>=16||!HomeSystems.isCurrent(link)||!link.television().powered()||!c.usable(p)||participant(p.getServer(),p.getUUID())){flow.fail("开局条件已变化，原进度保留");return;}
        long wire=NetplayNetwork.nextAddonId();var s=new Session(p,c,link,entry,plan,wire,flow);
        try{MdPublicSaves.attach(p.getServer(),wire,s.connection,s.ticket,plan);}
        catch(RuntimeException error){if(s.room!=null)NetplayNetwork.retire(s.room);flow.fail("MD 存档尚未准备好："+error.getMessage());return;}
        sessions(p.getServer()).put(c.hardwareId(),s);c.publicPreparing(s.source,p.getUUID());
        s.content=ContentCards.play(p,MdMod.SYSTEM,c.getBlockPos(),entry,()->valid(s),ready->{
            if(session(c)!=s)return;
            if(!ready)stop(c,"主持端停止或启动失败");else if(!s.closing&&flow.ready()){
                if(plan.enabled()&&!NetplaySaveServer.activate(p.getServer(),wire,s.connection)){stop(c,"存档写入授权未确认；开局取消，旧档保留");return;}
                c.publicPower(s.source,p.getUUID());s.ready=true;MdPublicNetwork.send(p,new MdPublicNetwork.Activated(wire));refreshSeats(s);HomeInteractionSounds.play(p.serverLevel(),c.getBlockPos(),HomeInteractionSounds.Action.POWER_ON);
            }else if(!s.closing)stop(c,"开局确认已失效");
        });
        if(s.content==null){stop(c,"MD 内容下载服务忙，请稍后重试");return;}
        MdPublicNetwork.send(p,new MdPublicNetwork.Start(wire,s.ticket,s.content,display(s),plan.enabled(),plan.resume(),plan.identity().profile(),entry.hash(),plan.identity().content(),s.netplay));
        if(!plan.allowSecondPort())c.clearLoan(1);
        say(p,"正在启动 MD "+(s.netplay?"JNI Netplay":"玩家串流")+(plan.allowSecondPort()?"；就绪后可取两只手柄。":"；就绪后附近玩家可旁观。"));
    }
    private static boolean valid(Session s){
        return session(s.console)==s&&modeAllowed(s.console)&&s.console.netplayJniTrial()==s.netplay&&current(s.host)&&s.host.connection.getConnection()==s.connection&&s.host.serverLevel()==s.link.level()
            &&s.host.isAlive()&&!s.host.isSpectator()&&HomeSystems.isCurrent(s.link)&&s.link.television().powered()
            &&cardUnchanged(s.card,s.cardSnapshot,s.console.cartridge())&&Objects.equals(ContentCardData.read(s.card,MdMod.SYSTEM),s.entry)
            &&s.host.serverLevel().mayInteract(s.host,s.console.getBlockPos())&&s.host.serverLevel().mayInteract(s.host,s.link.television().getBlockPos())
            &&s.host.serverLevel().getWorldBorder().isWithinBounds(s.console.getBlockPos())&&s.host.serverLevel().getWorldBorder().isWithinBounds(s.link.television().getBlockPos());
    }
    static boolean cardUnchanged(ItemStack physical,ItemStack snapshot,ItemStack current){return current==physical&&current.getCount()==1&&ItemStack.isSameItemSameComponents(snapshot,current);}
    public static void cancelStart(MdConsole c,String reason){var s=session(c);if(s!=null)stop(c,reason);}
    public static void stop(MdConsole c,String reason){
        var s=session(c);if(s==null){if(c.getLevel() instanceof net.minecraft.server.level.ServerLevel l)MdPublicSaves.cancel(l.getServer(),c.hardwareId());return;}
        if(s.closing)return;boolean wasReady=s.ready;s.closing=true;s.ready=false;s.closeDeadline=Integer.toUnsignedLong(s.host.getServer().getTickCount())+1600;s.flow.beginStopping();
        if(wasReady)NetplaySaveServer.retire(s.host.getServer(),s.wire);else NetplaySaveServer.abort(s.host.getServer(),s.wire,s.connection,"MD 开局尚未就绪；撤销写入，旧档保留");
        for(int port=0;port<2;port++)revoke(s,port,reason);
        WatchService.closed(s.host.getServer(),s.source,s.hostLease);if(s.room!=null)NetplayNetwork.retire(s.room);
        boolean awaiting=wasReady&&s.plan.enabled()&&NetplaySaveServer.awaitFinish(s.host.getServer(),s.wire,s.connection,result->{s.saveDone=true;s.saveSuccess=result.clean()&&result.persisted();s.finishReason=result.reason();maybeFinish(s);});
        if(current(s.host)&&s.host.connection.getConnection()==s.connection){
            MdPublicNetwork.send(s.host,new MdPublicNetwork.End(s.wire,ZERO,true,reason));
            if(s.content!=null)ContentCards.stop(s.host,s.content);
        }
        if(!awaiting){s.saveDone=true;s.saveSuccess=!s.plan.enabled()||!wasReady;s.finishReason=!wasReady?"MD 开局已取消，原进度保留":!s.plan.enabled()?"MD 已关闭；本局不保存，旧档保留":"MD 最终保存通道不可用；最近确认进度保留";}
        if(s.content==null)s.runtimeDone=true;maybeFinish(s);
    }
    public static void closed(ServerPlayer player,MdPublicNetwork.Closed reply){for(var s:List.copyOf(sessions(player.getServer()).values()))if(s.wire==reply.wire()&&s.host==player&&s.connection==player.connection.getConnection()){
        if(!s.closing)stop(s.console,"MD 主持核心已结束");if(session(s.console)!=s)return;s.runtimeDone=true;s.runtimeClosed=reply.closed();maybeFinish(s);return;
    }}
    private static void maybeFinish(Session s){if(s.saveDone&&s.runtimeDone)finish(s,s.saveSuccess&&s.runtimeClosed,s.runtimeClosed?s.finishReason:"MD 核心关闭未确认；已落盘版本保留，请正常重启客户端");}
    private static void finish(Session s,boolean success,String reason){if(session(s.console)!=s)return;sessions(s.host.getServer()).remove(s.console.hardwareId());s.console.publicStopped();s.flow.finished(success,reason);}
    public static void reset(ServerPlayer p,MdConsole c){var s=session(c);if(s!=null&&s.ready&&!s.closing&&s.host==p&&valid(s)&&s.content!=null){if(s.netplay){say(p,"JNI Netplay 重置需同步所有核心；请正常关机保存后从头开局，当前进度不变。");return;}ContentCards.reset(p,s.content);}else say(p,"仅当前开机玩家可重置已就绪的公共游戏。");}
    public static void loanChanged(MdConsole c){var s=session(c);if(s!=null&&s.ready)refreshSeats(s);}
    private static boolean participant(MinecraftServer server,UUID player){return sessions(server).values().stream().anyMatch(s->s.host.getUUID().equals(player)||Arrays.stream(s.seats).anyMatch(a->a!=null&&a.player.getUUID().equals(player)));}
    private static void refreshSeats(Session s){
        for(int port=0;port<2;port++){
            UUID loan=s.console.loan(port),borrower=s.console.borrower(port);var old=s.seats[port];
            var p=borrower==null?null:s.host.getServer().getPlayerList().getPlayer(borrower);
            boolean allowed=!s.closing&&(port==0||s.plan.allowSecondPort())&&p!=null&&s.console.authorized(p,port,loan,false)
                &&(p==s.host||!sessions(p.getServer()).values().stream().anyMatch(other->other!=s&&(other.host==p||Arrays.stream(other.seats).anyMatch(a->a!=null&&a.player==p))));
            if(old!=null&&(!allowed||old.player!=p||old.connection!=p.connection.getConnection()||!old.loan.equals(loan)))revoke(s,port,"手柄授权已撤销");
            if(allowed&&s.seats[port]==null){var seat=new SeatLease(p,loan,s.wire,port);
                UUID ticket=ZERO;if(s.netplay){if(p==s.host)ticket=s.ticket;else{
                    // Replace an automatic observer lease, so its late unsubscribe cannot revoke this seat's peer.
                    // Compute authority is not a controller seat. Both physical ports use MdPublicInputGate;
                    // replicas are read-only and advance from the host's canonical, server-authorized inputs.
                    s.room.revoke(seat.connection);var grant=s.room.grantObserver(seat.connection);
                    if(grant==null){say(p,"MD 同步房间已满，请稍后领取手柄。");s.console.clearLoan(port);continue;}
                    ticket=grant.id();NetplayNetwork.authorize(s.wire,seat.connection,s.room);
                }}seat.ticket=ticket;seat.ready=!s.netplay||p==s.host;s.seats[port]=seat;MdPublicNetwork.send(p,new MdPublicNetwork.Seat(s.wire,display(s),port,loan,ticket,s.netplay?s.entry.hash():"",s.netplay?s.entry.size():0,s.netplay));}
        }
    }
    private static void revoke(Session s,int port,String reason){
        var seat=s.seats[port];if(seat==null)return;s.seats[port]=null;
        if(s.room!=null&&seat.player!=s.host)s.room.revoke(seat.connection,seat.ticket);
        visual(s,port,seat,seat.visual.clear(Integer.toUnsignedLong(s.host.getServer().getTickCount())));
        if(current(s.host)&&s.host.connection.getConnection()==s.connection)MdPublicNetwork.send(s.host,new MdPublicNetwork.Input(s.wire,port,seat.loan,++seat.forwarded,0));
        if(current(seat.player)&&seat.player.connection.getConnection()==seat.connection)MdPublicNetwork.send(seat.player,new MdPublicNetwork.End(s.wire,seat.loan,false,reason));
    }
    public static void input(ServerPlayer p,MdPublicNetwork.Input input){
        for(var s:sessions(p.getServer()).values())if(s.wire==input.wire()){
            var seat=s.seats[input.port()];if(!s.ready||!valid(s)||seat==null||seat.player!=p||seat.connection!=p.connection.getConnection()
                ||!seat.loan.equals(input.loan()))return;
            long now=System.nanoTime();var accepted=seat.gate.accept(p.connection.getConnection(),input.wire(),input.port(),input.loan(),input.sequence(),input.mask(),now);
            if(accepted==MdPublicInputGate.Result.REJECT)return;if(accepted==MdPublicInputGate.Result.RATE_LIMIT){zero(s,input.port(),seat);return;}
            seat.last=now;
            int mask=seat.ready&&s.console.authorized(p,input.port(),input.loan(),true)?input.mask():0;
            seat.mask=mask;seat.visual.offer(mask);MdPublicNetwork.send(s.host,new MdPublicNetwork.Input(s.wire,input.port(),input.loan(),++seat.forwarded,mask));return;
        }
    }
    public static void release(ServerPlayer p,MdPublicNetwork.Release request){for(var s:sessions(p.getServer()).values())if(s.wire==request.wire()){var seat=s.seats[request.port()];if(seat!=null&&seat.player==p&&seat.connection==p.connection.getConnection()&&seat.loan.equals(request.loan()))s.console.clearLoan(request.port());return;}}
    public static void seatReady(ServerPlayer p,MdPublicNetwork.SeatReady reply){for(var s:sessions(p.getServer()).values())if(s.wire==reply.wire()&&s.netplay&&s.ready&&!s.closing&&valid(s)){
        var seat=s.seats[reply.port()];if(seat!=null&&seat.player==p&&seat.connection==p.connection.getConnection()&&seat.loan.equals(reply.loan())&&s.console.authorized(p,reply.port(),reply.loan(),false))seat.ready=true;return;
    }}
    public static void download(ServerPlayer p,MdPublicNetwork.Download request){for(var s:sessions(p.getServer()).values())if(s.wire==request.wire()&&s.netplay&&s.ready&&s.entry.hash().equals(request.rom())&&downloadAllowed(s,p)){
        var origin=p.connection.getConnection();var token=ContentCards.downloadOnly(p,MdMod.SYSTEM,request.request(),s.console.getBlockPos(),s.entry,()->downloadAllowed(s,p),ok->{if(!ok&&current(p)&&p.connection.getConnection()==origin)MdPublicNetwork.send(p,new MdPublicNetwork.DownloadDenied(request.request()));});
        if(token==null)MdPublicNetwork.send(p,new MdPublicNetwork.DownloadDenied(request.request()));return;
    }MdPublicNetwork.send(p,new MdPublicNetwork.DownloadDenied(request.request()));}
    private static boolean downloadAllowed(Session s,ServerPlayer p){return s.netplay&&s.ready&&!s.closing&&valid(s)&&current(p)&&(Arrays.stream(s.seats).anyMatch(a->a!=null&&a.player==p&&a.connection==p.connection.getConnection()&&s.console.authorized(p,a.gatePort,a.loan,false))||WatchNetplay.authorizedRom(p,MdMod.SYSTEM,s.entry.hash())&&WatchNetplay.connections(p.getServer(),s.source).contains(p.connection.getConnection()));}
    private static void zero(Session s,int port,SeatLease seat){visual(s,port,seat,seat.visual.cancel(Integer.toUnsignedLong(s.host.getServer().getTickCount())));if(seat.mask!=0){seat.mask=0;if(current(s.host))MdPublicNetwork.send(s.host,new MdPublicNetwork.Input(s.wire,port,seat.loan,++seat.forwarded,0));}}
    private static void visual(Session s,int port,SeatLease seat,MdVisualInputState.Sample sample){
        if(sample==null)return;
        var level=s.link.level();var operator=seat.player;
        if(operator.serverLevel()!=level||level.dimension().location().toString().length()>128)return; // Dimension changes expire remotely by TTL.
        var packet=new MdPublicNetwork.Visual(level.dimension().location(),s.console.getBlockPos(),s.console.hardwareId(),s.wire,
                operator.getUUID(),seat.loan,port,sample.sequence(),sample.mask(),sample.pressedMask(),sample.reset());
        int sent=0;
        for(var viewer:level.getEntitiesOfClass(ServerPlayer.class,operator.getBoundingBox().inflate(32))){
            if(viewer==operator||!MdVisualInputState.recipient(current(viewer),viewer.isAlive(),viewer.serverLevel()==level,
                    !operator.isInvisibleTo(viewer)&&viewer.getChunkTrackingView().contains(operator.chunkPosition()),viewer.distanceToSqr(operator)))continue;
            MdPublicNetwork.send(viewer,packet);if(++sent>=64)break;
        }
    }
    @SubscribeEvent public static void tick(ServerTickEvent.Post event){
        var server=event.getServer();PRIVATE.removeIf(c->!c.isConnected());
        for(var s:List.copyOf(sessions(server).values())){
            if(s.closing){if(Integer.toUnsignedLong(server.getTickCount())>=s.closeDeadline)finish(s,false,"MD 结束等待超时；最后进度或核心关闭未确认，旧档保留");continue;}
            if(!valid(s)){stop(s.console,"MD 主持或电视连接已失效；正在结束并保存");continue;}
            if(s.room!=null){if(s.room.closed()){stop(s.console,"MD 同步房间已关闭；正在保存");continue;}var members=new HashSet<Connection>();members.add(s.connection);for(var a:s.seats)if(a!=null)members.add(a.connection);members.addAll(WatchNetplay.connections(server,s.source));s.room.renew(members);NetplayNetwork.prune(s.room,members);}
            if(!s.ready)continue;refreshSeats(s);
            for(int port=0;port<2;port++){var seat=s.seats[port];if(seat!=null){
                if(!s.console.authorized(seat.player,port,seat.loan,true)||System.nanoTime()-seat.last>750_000_000L)zero(s,port,seat);
                visual(s,port,seat,seat.visual.poll(Integer.toUnsignedLong(server.getTickCount())));
            }}
        }
    }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event){var map=SESSIONS.remove(event.getServer());if(map!=null)for(var s:map.values()){PRIVATE.remove(s.connection);if(s.room!=null)NetplayNetwork.retire(s.room);}MdPublicSaves.stop(event.getServer());}
    private static WatchNetwork.Start display(Session s){return new WatchNetwork.Start(s.wire,s.ticket,s.watch.descriptor());}
    @Override public List<WatchSource> sources(MinecraftServer server){return sessions(server).values().stream().filter(s->s.ready&&valid(s)).map(s->s.watch).toList();}
    @Override public List<UUID> occupancyPlayers(MinecraftServer server,WatchSource source){
        var s=source(server,source);if(s==null)return List.of();
        return MdOccupancy.players(s.ready&&valid(s),s.seats,(port,seat)->
                current(seat.player)&&seat.player.connection.getConnection()==seat.connection
                        &&s.console.authorized(seat.player,port,seat.loan,false),seat->seat.player.getUUID());
    }
    private static Session source(MinecraftServer server,WatchSource source){return sessions(server).values().stream().filter(s->s.ready&&s.watch.equals(source)&&valid(s)).findFirst().orElse(null);}
    @Override public boolean isCurrent(MinecraftServer server,WatchSource source){return source(server,source)!=null;}
    @Override public WatchNetplay.Offer netplay(ServerPlayer p,WatchSource source){var s=source(p.getServer(),source);return s!=null&&s.netplay?new WatchNetplay.Offer(s.wire,s.room,MdMod.SYSTEM,s.entry.hash(),null):null;}
    @Override public boolean isParticipant(MinecraftServer server,UUID p){return participant(server,p);}
    @Override public boolean canObserve(ServerPlayer p,WatchSource source){return current(p)&&p.serverLevel().dimension().location().equals(source.descriptor().dimension())&&p.serverLevel().hasChunkAt(source.descriptor().origin().pos())&&source.descriptor().screens().stream().allMatch(a->p.serverLevel().hasChunkAt(a.pos())&&p.serverLevel().getWorldBorder().isWithinBounds(a.pos()));}
    private static List<SeatLease> recipients(Session s){var result=new ArrayList<SeatLease>(2);for(int port=0;port<2;port++){var a=s.seats[port];if(a!=null&&a.player!=s.host&&current(a.player)&&a.player.connection.getConnection()==a.connection&&s.console.authorized(a.player,port,a.loan,false))result.add(a);}return result;}
    @Override public int controlRecipients(MinecraftServer server,WatchSource source){var s=source(server,source);return s==null||s.netplay?0:recipients(s).size();}
    @Override public void relayControls(MinecraftServer server,WatchSource source,List<CabinetMediaPacket> batch){var s=source(server,source);if(s==null||s.netplay)return;for(var seat:recipients(s))MdPublicNetwork.media(seat.connection,s.wire,seat.loan,batch);}
    private static void say(ServerPlayer p,String text){p.displayClientMessage(net.minecraft.network.chat.Component.literal(text),true);}
    private static final class Session {
        final ServerPlayer host;final Connection connection;final MdConsole console;final HomeSystems.Connection link;final ContentCardStore.Entry entry;
        final ItemStack card,cardSnapshot;final MdPublicSaves.SavePlan plan;final long wire;final UUID ticket,source=UUID.randomUUID(),hostLease=UUID.randomUUID();final boolean netplay;final NetplayRelay<Connection> room;
        final HomeLaunchServer.Handle flow;final WatchSource watch;final SeatLease[] seats=new SeatLease[2];UUID content;boolean ready,closing,saveDone,saveSuccess,runtimeDone,runtimeClosed=true;long closeDeadline;String finishReason="";
        Session(ServerPlayer p,MdConsole c,HomeSystems.Connection link,ContentCardStore.Entry entry,MdPublicSaves.SavePlan plan,long wire,HomeLaunchServer.Handle flow){
            this.flow=flow;
            host=p;connection=p.connection.getConnection();console=c;this.link=link;this.entry=entry;card=c.cartridge();cardSnapshot=card.copy();this.plan=plan;this.wire=wire;
            netplay=c.netplayJniTrial();room=netplay?NetplayNetwork.room(wire,connection,2):null;ticket=room==null?UUID.randomUUID():room.grant(connection,true).id();
            watch=new WatchSource(new WatchDescriptor(MdMod.SYSTEM,source,hostLease,link.level().dimension().location(),new WatchAnchor(c.getBlockPos(),c.hardwareId()),c.linkId(),List.of(new WatchAnchor(link.television().getBlockPos(),link.television().hardwareId()))),p.getUUID());
        }
    }
    private static final class SeatLease {
        final ServerPlayer player;final Connection connection;final UUID loan;final MdPublicInputGate gate;final int gatePort;UUID ticket=ZERO;final MdVisualInputState visual=new MdVisualInputState();long forwarded,last=System.nanoTime();int mask;boolean ready;
        SeatLease(ServerPlayer p,UUID loan,long wire,int port){player=p;connection=p.connection.getConnection();this.loan=loan;gatePort=port;gate=new MdPublicInputGate(connection,loan,wire,port);}
    }
}
