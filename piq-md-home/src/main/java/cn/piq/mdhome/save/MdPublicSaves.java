// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.mdhome.save;

import cn.piq.fcarcade.home.*;
import cn.piq.fcarcade.home.content.*;
import cn.piq.fcarcade.home.flow.*;
import cn.piq.fcarcade.netplay.*;
import cn.piq.mdhome.*;
import cn.piq.retro.storage.ConsoleStorage;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import static cn.piq.fcarcade.home.CartridgeSaveNetwork.*;

/** Server-thread capabilities and bounded metadata IO. This is not a Netplay emulator. */
public final class MdPublicSaves {
    public record SavePlan(NetplaySaveState.Identity identity,boolean enabled,boolean resume,int savePlayers,boolean allowSecondPort,
                           String ownerKey,String name,int slot,String expectedVersion,Path root){
        public SavePlan withJoin(boolean allowed){return new SavePlan(identity,enabled,resume,savePlayers,allowed,ownerKey,name,slot,expectedVersion,root);}
    }
    private static final Map<MinecraftServer,State> STATES=new WeakHashMap<>();
    private static final MdSaveTransactions TRANSACTIONS=new MdSaveTransactions();
    private static final ThreadPoolExecutor IO=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16),r->{var t=new Thread(r,"GameConsole-MD-save-catalog");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private static boolean installed;
    private static final class State {
        final Map<UUID,Pending> pending=new HashMap<>();final Map<UUID,HomeLaunchServer.Key> launches=new HashMap<>();final Map<UUID,Library> libraries=new HashMap<>();final Map<UUID,String> retiringOwners=new HashMap<>();boolean closed;
    }
    private static final class Pending {
        final ServerPlayer player;final Connection connection;final MdConsole console;final HomeSystems.Connection link;
        final ContentCardStore.Entry entry;final ItemStack snapshot;final UUID card,playerId;final int mode,maxPlayers;final boolean netplay;
        Pending(ServerPlayer p,MdConsole c,HomeSystems.Connection l,ContentCardStore.Entry e){
            player=p;playerId=p.getUUID();connection=p.connection.getConnection();console=c;link=l;entry=e;card=ContentCardData.ensureId(c.cartridge());
            c.setChanged();snapshot=c.cartridge().copy();mode=ContentCardData.saveMode(snapshot);maxPlayers=Math.min(2,ContentCardData.players(snapshot));netplay=c.netplayJniTrial();
        }
        boolean valid(){return online(player,connection)&&player.serverLevel()==link.level()
                &&!console.isRemoved()&&!console.running()&&console.netplayJniTrial()==netplay&&HomeSystems.isCurrent(link)&&link.television().powered()
                &&console.usable(player)&&HomeHardware.mayUse(player,console.getBlockPos())
                &&HomeHardware.mayUse(player,link.television().getBlockPos())&&ItemStack.isSameItemSameComponents(snapshot,console.cartridge())
                &&console.cartridge().getCount()==1&&card.equals(ContentCardData.id(console.cartridge()));}
    }
    private static final class Library {
        final UUID token=UUID.randomUUID();final ServerPlayer player;final Connection connection;final ContentCards.EditorGrant grant;
        final MdSaveCatalog catalog,netplayCatalog;final long deadline;Map<String,Managed> shown=Map.of();boolean loading,displayed;
        UUID confirmation;Managed deleting;long confirmUntil,lastAction=-100;MdSaveTransactions.Reservation mutation;
        Library(ServerPlayer p,ContentCards.EditorGrant g){player=p;connection=p.connection.getConnection();grant=g;catalog=new MdSaveCatalog(root(p.getServer()));netplayCatalog=new MdSaveCatalog(root(p.getServer(),true));deadline=tick(p.getServer())+1200;}
        boolean valid(){return online(player,connection)&&tick(player.getServer())<deadline&&grant.valid().getAsBoolean();}
    }
    private record Managed(MdSaveCatalog catalog,MdSaveCatalog.Row row,boolean netplay){
        String id(){return (netplay?"netplay-":"media-")+row.id();}
        String version(){return row.version();}String owner(){return row.owner();}
    }
    private record Listing(List<Managed> rows,boolean truncated){}
    private MdPublicSaves(){}
    /** Call after ContentCards Features registration during common setup. */
    public static synchronized void install(){if(installed)return;installed=true;
        ContentCards.saveLibrary(MdMod.SYSTEM,MdPublicSaves::openLibrary);
        CartridgeSaveNetwork.registerServerHandlers(MdMod.SYSTEM.toString(),(p,r)->{
            // The workbench is the only source of grants; client-provided hand/card fields grant nothing.
            var grant=ContentCards.authorizeEditor(p,MdMod.SYSTEM,r.editorToken());if(grant!=null)openLibrary(p,grant);
        },MdPublicSaves::request);
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post e)->maintenance(e.getServer()));
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent e)->stop(e.getServer()));
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent e)->{if(e.getEntity() instanceof ServerPlayer p)logout(p);});
    }
    public static NetplaySaveState.Identity identity(String rom){return MdSaveCatalog.identity(rom);}
    public static NetplaySaveState.Identity identity(String rom,boolean netplay){return netplay?MdNetplayProfile.identity(rom):identity(rom);}
    private static Path root(MinecraftServer server){return ConsoleStorage.root(server.getWorldPath(LevelResource.ROOT)).resolve("md-public-saves").resolve("v1");}
    private static Path root(MinecraftServer server,boolean netplay){return netplay?ConsoleStorage.root(server.getWorldPath(LevelResource.ROOT)).resolve("md-netplay-saves").resolve("v1"):root(server);}
    private static long tick(MinecraftServer server){return Integer.toUnsignedLong(server.getTickCount());}
    private static State state(MinecraftServer server){return STATES.computeIfAbsent(server,s->new State());}
    private static boolean online(ServerPlayer p,Connection c){return p.getServer()!=null&&!p.hasDisconnected()&&p.isAlive()&&!p.isSpectator()&&p.connection.getConnection()==c&&p.getServer().getPlayerList().getPlayer(p.getUUID())==p;}
    private static void say(ServerPlayer p,String message){if(!p.hasDisconnected())p.displayClientMessage(Component.literal(message),true);}
    public static boolean pending(MinecraftServer server,UUID console){var s=STATES.get(server);var key=s==null?null:s.launches.get(console);return key!=null&&HomeLaunchServer.pending(server,key);}
    public static boolean busy(MinecraftServer server,UUID console){var s=STATES.get(server);if(s==null)return false;String key=s.retiringOwners.get(console);var launch=s.launches.get(console);return launch!=null&&HomeLaunchServer.busy(server,launch)||key!=null&&(NetplaySaveServer.busy(server,key)||TRANSACTIONS.busy(key));}
    public static HomeLaunchServer.Key key(MdConsole console){return new HomeLaunchServer.Key(MdMod.SYSTEM,console.getLevel().dimension().location(),console.getBlockPos(),console.hardwareId());}
    public static boolean choose(ServerPlayer p,MdConsole console,HomeSystems.Connection link,ContentCardStore.Entry entry,BiConsumer<SavePlan,HomeLaunchServer.Handle> chosen){
        var server=p.getServer();if(server==null||!server.isSameThread()||entry==null||!Objects.equals(ContentCardData.read(console.cartridge(),MdMod.SYSTEM),entry))return false;
        var s=state(server);if(s.pending.size()>=16||s.pending.containsKey(console.hardwareId()))return false;
        // One choice UI per connection, never silently replace another console's pending choice.
        if(s.pending.values().stream().anyMatch(x->x.connection==p.connection.getConnection())){say(p,"请先完成或取消上一台 MD 的开机选择。");return false;}
        final Pending q;try{q=new Pending(p,console,link,entry);}catch(RuntimeException invalid){return false;}
        if(!q.valid())return false;s.pending.put(console.hardwareId(),q);
        var saveRoot=root(server,q.netplay);var saveIdentity=identity(entry.hash(),q.netplay);var catalog=new MdSaveCatalog(saveRoot);s.launches.put(console.hardwareId(),key(console));var handle=HomeLaunchServer.start(p,key(console),new HomeLaunchServer.Adapter<SavePlan>(){
            public HomeLaunchServer.Definition definition(){String title=ContentCardData.title(q.snapshot);String hint=q.mode==0?"本局不保存，旧档保留":q.mode==2?"个人总共 3 槽；选择后独立确认本局加入许可":"进度跟随这张实体卡；选择后独立确认本局加入许可";return new HomeLaunchServer.Definition("MD",title.isBlank()?entry.displayName():title,q.maxPlayers,q.mode,false,(q.netplay?"JNI Netplay 独立档，不迁移串流进度；":"JNI 串流；")+hint);}
            public boolean valid(){return !s.closed&&q.valid();}
            public void list(Consumer<List<HomeLaunchNetwork.Row>> success,Consumer<String> failure){work(server,s,()->{
                var rows=new ArrayList<HomeLaunchNetwork.Row>();for(int slot=1;slot<=(q.mode==1?1:3);slot++){var r=slot(slot,catalog.read(owner(q,slot)),q);rows.add(new HomeLaunchNetwork.Row(r.slot(),r.version(),r.name(),r.rom(),r.players(),r.modified(),r.compatible()));}return rows;
            },success,failure);}
            public void select(HomeLaunchNetwork.Choice choice,Consumer<SavePlan> success,Consumer<String> failure){
                if(q.mode==0){success.accept(new SavePlan(saveIdentity,false,false,1,false,"","",1,"",saveRoot));return;}
                if(choice==null||choice.slot()>(q.mode==1?1:3)||choice.savePlayers()>q.maxPlayers){failure.accept("存档选择无效");return;}
                final String name;try{name=MdSaveCatalog.name(choice.name());}catch(IllegalArgumentException bad){failure.accept(bad.getMessage());return;}
                String owner=owner(q,choice.slot());if(NetplaySaveServer.busy(server,catalog.lockKey(owner))||TRANSACTIONS.busy(catalog.lockKey(owner))){failure.accept("此存档正在使用或保存，请稍后重试");return;}
                work(server,s,()->catalog.read(owner),row->{
                    if(!Objects.equals(choice.version(),row==null?"":row.version())||choice.resume()&&(row==null||!row.identity().equals(saveIdentity))){failure.accept("存档已变化，请重新开机选择");return;}
                    success.accept(new SavePlan(saveIdentity,true,choice.resume(),choice.savePlayers(),false,owner,name,choice.slot(),choice.version(),saveRoot));
                },failure);
            }
            public void load(HomeLaunchServer.Launch<SavePlan> launch,HomeLaunchServer.Handle handle){s.pending.remove(console.hardwareId(),q);var plan=launch.save().withJoin(launch.allowSecondPort());if(plan.enabled())s.retiringOwners.put(console.hardwareId(),catalog.lockKey(plan.ownerKey()));chosen.accept(plan,handle);}
            public void cancelled(String reason){s.pending.remove(console.hardwareId(),q);MdPublicServer.cancelStart(console,reason);}
        });
        if(handle==null){s.pending.remove(console.hardwareId(),q);s.launches.remove(console.hardwareId());return false;}return true;
    }
    private static String owner(Pending q,int slot){return q.mode==1?MdSaveCatalog.cartridge(q.card):MdSaveCatalog.personal(q.playerId,slot);}
    private static MdSaveNetwork.Slot slot(int slot,MdSaveCatalog.Row row,Pending q){return row==null?new MdSaveNetwork.Slot(slot,"","Save "+slot,"",1,0,true):new MdSaveNetwork.Slot(slot,row.version(),row.name(),row.identity().content(),Math.min(q.maxPlayers,row.players()),row.modified(),row.identity().equals(identity(q.entry.hash(),q.netplay)));}
    /** Retained packet registration for explicit rejection of stale addon callers; no old UI authority. */
    public static void action(ServerPlayer p,MdSaveNetwork.Action a){}
    public static void attach(MinecraftServer server,long wire,Connection host,UUID ticket,SavePlan plan){
        var catalog=new MdSaveCatalog(plan.root());
        String key=plan.enabled()?catalog.lockKey(plan.ownerKey()):"md-no-save|"+wire;
        TRANSACTIONS.start(key,()->NetplaySaveServer.openPrepared(server,wire,host,ticket,plan.identity(),key,
                plan.enabled()?()->catalog.lease(plan.ownerKey(),plan.identity(),plan.expectedVersion(),plan.name(),plan.savePlayers(),plan.resume()):null));
    }
    public static void cancel(MinecraftServer server,UUID console){var s=STATES.get(server);if(s!=null){s.pending.remove(console);var key=s.launches.get(console);if(key!=null)HomeLaunchServer.cancel(server,key,"开机选择已取消");}}
    public static void stop(MinecraftServer server){var s=STATES.remove(server);if(s==null)return;s.closed=true;s.pending.clear();for(var l:s.libraries.values()){forgetRoute(l.token);if(l.mutation!=null)l.mutation.revoke();}s.libraries.clear();}
    private static void maintenance(MinecraftServer server){var s=STATES.get(server);if(s==null)return;
        s.retiringOwners.values().removeIf(key->!NetplaySaveServer.busy(server,key)&&!TRANSACTIONS.busy(key));
        s.launches.values().removeIf(key->!HomeLaunchServer.busy(server,key));
        for(var q:List.copyOf(s.pending.values()))if(!q.valid())cancel(server,q.console.hardwareId());
        if(tick(server)%10==0)for(var l:List.copyOf(s.libraries.values()))if(!l.valid())close(s,l,"存档管理授权已失效，请重新打开老式电脑");
    }
    private static void logout(ServerPlayer p){var server=p.getServer();var s=STATES.get(server);if(s==null)return;
        for(var q:List.copyOf(s.pending.values()))if(q.player==p)cancel(server,q.console.hardwareId());
        for(var l:List.copyOf(s.libraries.values()))if(l.player==p)close(s,l,"存档管理已关闭");
    }
    private static void openLibrary(ServerPlayer p,ContentCards.EditorGrant grant){
        if(grant.entry()==null||!MdMod.SYSTEM.equals(grant.system())||!grant.valid().getAsBoolean())return;
        var s=state(p.getServer());for(var l:List.copyOf(s.libraries.values()))if(l.player==p)close(s,l,"已打开新的存档管理");
        if(s.libraries.size()>=16){CartridgeSaveNetwork.reply(p,new Reply(grant.token(),MdMod.SYSTEM.toString(),grant.entry().hash(),"存档管理繁忙，请稍后重试",List.of(),"","",null,true,grant.token()));return;}var l=new Library(p,grant);s.libraries.put(l.token,l);refresh(s,l,"");
    }
    private static boolean current(State s,Library l){return !s.closed&&s.libraries.get(l.token)==l&&l.valid();}
    private static void refresh(State s,Library l,String message){if(l.loading||!current(s,l))return;l.loading=true;
        boolean op=l.player.hasPermissions(2);UUID playerId=l.player.getUUID();work(l.player.getServer(),s,()->{
            var media=l.catalog.list(l.grant.entry().hash(),playerId,l.grant.cardId(),op);var netplay=l.netplayCatalog.list(identity(l.grant.entry().hash(),true).content(),playerId,l.grant.cardId(),op);
            var all=new ArrayList<Managed>();for(var row:media.rows())all.add(new Managed(l.catalog,row,false));for(var row:netplay.rows())all.add(new Managed(l.netplayCatalog,row,true));all.sort(Comparator.comparingLong((Managed x)->x.row().modified()).reversed());boolean truncated=all.size()>MAX_ENTRIES;if(truncated)all.subList(MAX_ENTRIES,all.size()).clear();return new Listing(List.copyOf(all),truncated||media.truncated()||netplay.truncated());
        },listing->{
            l.loading=false;if(!current(s,l))return;var rows=new LinkedHashMap<String,Managed>();for(var row:listing.rows())rows.put(row.id(),row);l.shown=Map.copyOf(rows);l.confirmation=null;l.deleting=null;
            reply(l,message+(listing.truncated()?" 部分记录未列出或损坏；原文件保留。":""));
        },error->{l.loading=false;if(current(s,l))reply(l,"读取失败，原档保留："+error);});
    }
    private static boolean editable(Library l,Managed value){var row=value.row();return row.identity().content().equals(identity(l.grant.entry().hash(),value.netplay()).content())&&(l.player.hasPermissions(2)||MdSaveCatalog.owned(row.owner(),l.player.getUUID(),l.grant.cardId()));}
    private static void reply(Library l,String message){var entries=new ArrayList<CartridgeSaveNetwork.Entry>();
        for(var managed:l.shown.values()){var row=managed.row();String key=managed.catalog().lockKey(row.owner());boolean active=NetplaySaveServer.busy(l.player.getServer(),key)||TRANSACTIONS.busy(key);entries.add(new CartridgeSaveNetwork.Entry(managed.id(),row.version(),row.name(),MdSaveCatalog.card(row.owner())?"卡带 "+row.owner().substring(5):"个人 "+row.owner().substring(9),managed.netplay()?"MD · JNI Netplay · 独立服务器档":"MD · JNI 串流服务器档",row.modified(),row.bytes(),active,editable(l,managed)&&!active));}
        entries.sort(Comparator.comparingLong(CartridgeSaveNetwork.Entry::modified).reversed());
        l.displayed=true;CartridgeSaveNetwork.reply(l.player,new Reply(l.token,MdMod.SYSTEM.toString(),l.grant.entry().hash(),cut(message,240),entries,l.deleting==null?"":l.deleting.id(),l.deleting==null?"":l.deleting.version(),l.confirmation,false,l.grant.token()));
    }
    private static void close(State s,Library l,String message){s.libraries.remove(l.token,l);forgetRoute(l.token);if(l.mutation!=null)l.mutation.revoke();
        if(!l.player.hasDisconnected())CartridgeSaveNetwork.reply(l.player,new Reply(l.displayed?l.token:l.grant.token(),MdMod.SYSTEM.toString(),l.grant.entry().hash(),message,List.of(),"","",null,true,l.grant.token()));
    }
    private static void request(ServerPlayer p,Request request){var s=STATES.get(p.getServer());if(s==null)return;var l=s.libraries.get(request.token());
        if(l==null||l.player!=p||l.connection!=p.connection.getConnection())return;
        if(request.operation()==CLOSE){close(s,l,"");return;}if(!current(s,l)){close(s,l,"授权失效，请重新打开老式电脑");return;}
        long now=tick(p.getServer());if(l.loading||now-l.lastAction<10)return;l.lastAction=now;
        if(request.operation()==REFRESH){refresh(s,l,"");return;}
        var row=l.shown.get(request.id());if(row==null||!row.version().equals(request.version())||!editable(l,row)){reply(l,"存档或管理权限已变化，请刷新");return;}
        if(NetplaySaveServer.busy(p.getServer(),row.catalog().lockKey(row.owner()))||TRANSACTIONS.busy(row.catalog().lockKey(row.owner()))){reply(l,"此存档正在游戏、保存或管理，暂不能修改");return;}
        if(request.operation()==PREPARE_DELETE){l.deleting=row;l.confirmation=UUID.randomUUID();l.confirmUntil=now+200;reply(l,"确认删除所选存档？旧记录将保留为服务器恢复备份。");return;}
        if(request.operation()==CONFIRM_DELETE&&(l.deleting==null||!row.id().equals(l.deleting.id())||!row.version().equals(l.deleting.version())||!Objects.equals(l.confirmation,request.confirmation())||now>=l.confirmUntil)){reply(l,"删除确认已失效，请重新选择");return;}
        if(request.operation()!=RENAME&&request.operation()!=CONFIRM_DELETE)return;
        final String name;try{name=request.operation()==RENAME?MdSaveCatalog.name(request.name()):"";}catch(IllegalArgumentException invalid){reply(l,invalid.getMessage());return;}
        final MdSaveTransactions.Reservation reservation;String key=row.catalog().lockKey(row.owner());
        try{reservation=TRANSACTIONS.reserve(key,()->!NetplaySaveServer.busy(p.getServer(),key));}catch(IllegalStateException busy){reply(l,busy.getMessage());return;}
        l.loading=true;l.mutation=reservation;
        // A queued IO task asks the owning server thread again immediately before committing.
        // No IO worker reads player/world state, and no server thread waits for disk work.
        MinecraftServer server=p.getServer();boolean accepted=work(server,s,()->{
            try(reservation){
                var approval=new CompletableFuture<Boolean>();server.execute(()->{
                    boolean allowed=reservation.allowed()&&current(s,l)&&editable(l,row)&&!NetplaySaveServer.busy(p.getServer(),key)
                            &&(request.operation()!=CONFIRM_DELETE||l.deleting!=null&&row.id().equals(l.deleting.id())&&row.version().equals(l.deleting.version())&&Objects.equals(l.confirmation,request.confirmation())&&tick(p.getServer())<l.confirmUntil);
                    if(allowed){l.confirmation=null;l.deleting=null;}else reservation.revoke();approval.complete(allowed);
                });
                if(!approval.get(5,TimeUnit.SECONDS)||!reservation.allowed())throw new IllegalStateException("存档管理授权或确认已撤销");
                if(request.operation()==RENAME)row.catalog().rename(row.row(),name,reservation::allowed);else row.catalog().delete(row.row(),reservation::allowed);return true;
            }
        },done->{l.loading=false;l.mutation=null;if(current(s,l))refresh(s,l,request.operation()==RENAME?"已重命名":"已移入恢复备份");},error->{l.loading=false;l.mutation=null;if(current(s,l))refresh(s,l,"未修改存档："+error);});
        if(!accepted)reservation.close();
    }
    private static <T> boolean work(MinecraftServer server,State state,Callable<T> job,Consumer<T> success,Consumer<String> failure){
        try{IO.execute(()->{T result;try{result=job.call();}catch(Exception error){server.execute(()->{if(!state.closed)failure.accept(message(error));});return;}server.execute(()->{if(!state.closed)success.accept(result);});});return true;}
        catch(RejectedExecutionException full){failure.accept("存档服务繁忙，请稍后再试");return false;}
    }
    private static String message(Throwable error){String text=error.getMessage();return cut(text==null?error.getClass().getSimpleName():text,150);}
    private static String cut(String text,int max){text=text.replaceAll("[\\p{Cntrl}]"," ");return text.length()>max?text.substring(0,max):text;}
}
