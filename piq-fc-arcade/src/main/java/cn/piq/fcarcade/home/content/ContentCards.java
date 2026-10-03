package cn.piq.fcarcade.home.content;

import cn.piq.fcarcade.access.PlayerContentAccess;
import cn.piq.fcarcade.home.CartridgeComputerBlockEntity;
import cn.piq.fcarcade.home.CartridgeComputerBinding;
import cn.piq.fcarcade.home.CartridgeTransfer;
import cn.piq.retro.storage.ConsoleStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.*;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import static cn.piq.fcarcade.home.content.ContentCardNetwork.*;

/** Shared writer, one controlled runtime/player, and bounded read-only content transfers. */
public final class ContentCards {
    public record Adapter(Supplier<Item> item,String label,Set<String> extensions,ContentCardStore.Validator validator,int maxBytes){
        public Adapter(Supplier<Item> item,String label,Set<String> extensions,ContentCardStore.Validator validator){this(item,label,extensions,validator,ContentCardStore.DEFAULT_MAX_BYTES);}
        public Adapter{Objects.requireNonNull(item);Objects.requireNonNull(label);extensions=Set.copyOf(extensions);Objects.requireNonNull(validator);if(maxBytes<1||maxBytes>ContentCardStore.MAX_BYTES)throw new IllegalArgumentException("Card size budget");}
    }
    private static final Map<ResourceLocation,Adapter> ADAPTERS=new ConcurrentHashMap<>();
    public record Features(boolean covers,boolean localSaveSettings,boolean publicSaves,int maxPlayers){
        public Features(boolean covers,boolean localSaveSettings){this(covers,localSaveSettings,false,1);}
        public Features{if(maxPlayers<1||maxPlayers>4||publicSaves&&!localSaveSettings)throw new IllegalArgumentException("Content-card capabilities");}
        public boolean allowsSaveMode(int mode){return localSaveSettings&&mode>=0&&mode<=2&&(mode!=1||publicSaves);}
    }
    private static final Map<ResourceLocation,Features> FEATURES=new ConcurrentHashMap<>();
    public static void features(ResourceLocation id,Features features){if(!ADAPTERS.containsKey(id)||FEATURES.putIfAbsent(id,features)!=null)throw new IllegalArgumentException("Content-card features");}
    public static Features features(ResourceLocation id){return FEATURES.getOrDefault(id,new Features(false,false));}
    /** An independent, short-lived capability: no file path, client-selected owner or live stack escapes. */
    public record EditorGrant(ResourceLocation system,UUID token,BlockPos computerPos,UUID cardId,
                              ContentCardStore.Entry entry,int saveMode,int players,ItemStack snapshot,BooleanSupplier valid){
        public EditorGrant{computerPos=computerPos.immutable();snapshot=snapshot.copy();Objects.requireNonNull(valid);}
        @Override public ItemStack snapshot(){return snapshot.copy();}
    }
    private static final Map<ResourceLocation,BiConsumer<ServerPlayer,EditorGrant>> SAVE_LIBRARIES=new ConcurrentHashMap<>();
    public static void saveLibrary(ResourceLocation system,BiConsumer<ServerPlayer,EditorGrant> opener){
        if(!features(system).publicSaves()||SAVE_LIBRARIES.putIfAbsent(system,Objects.requireNonNull(opener))!=null)
            throw new IllegalArgumentException("Content-card save library registration");
    }
    private static final ThreadLocal<Boolean> PROTECTING=ThreadLocal.withInitial(()->false);
    private static cn.piq.fcarcade.home.CartridgeCoverRepository covers(ServerPlayer p){
        return new cn.piq.fcarcade.home.CartridgeCoverRepository(cn.piq.fcarcade.storage.FcStoragePaths.prepareUnchecked(
                p.getServer().getServerDirectory(),cn.piq.fcarcade.storage.FcStoragePaths.Area.SHARED_COVERS));
    }
    private static final Map<MinecraftServer,State> STATES=new WeakHashMap<>();
    private static final ThreadPoolExecutor IO=new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(8),r->{var t=new Thread(r,"GameConsole-content-card-io");t.setDaemon(true);return t;});
    private static boolean installed;
    public static void register(ResourceLocation system,Adapter adapter){if(ADAPTERS.putIfAbsent(system,adapter)!=null)throw new IllegalArgumentException("Duplicate cartridge system");}
    public static Adapter adapter(ResourceLocation system){return ADAPTERS.get(system);}
    public static synchronized void install(){if(installed)return;installed=true;
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event)->tick(event.getServer()));
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event)->{var s=STATES.remove(event.getServer());if(s!=null){s.closed=true;s.edits.clear();s.plays.clear();s.reads.clear();}});
    }
    private static State state(ServerPlayer p){return STATES.computeIfAbsent(p.getServer(),s->new State());}
    // New content-card storage has no legacy directory to migrate. Resolve only here;
    // directory creation, validation and file reads belong to the bounded IO worker.
    private static ContentCardStore store(ServerPlayer p,ResourceLocation system){var a=ADAPTERS.get(system);return new ContentCardStore(ContentCardDirectories.roms(p.getServer().getServerDirectory(),system),a.extensions,a.validator,a.maxBytes,ContentCardDirectories.metadata(p.getServer().getServerDirectory(),system));}
    private static boolean online(ServerPlayer p,Object connection){return p!=null&&p.getServer()!=null&&p.getServer().isSameThread()&&!p.hasDisconnected()&&p.connection.getConnection()==connection&&p.getServer().getPlayerList().getPlayer(p.getUUID())==p&&p.isAlive()&&!p.isSpectator();}
    public static void open(ServerPlayer p,InteractionHand hand,BlockPos pos,ResourceLocation system){
        var a=ADAPTERS.get(system);if(a==null||!PlayerContentAccess.canBrowse(p)) {say(p,"没有游戏库访问权，请联系管理员。");return;}
        var level=p.serverLevel();var stack=p.getItemInHand(hand);
        if(!level.hasChunkAt(pos)||!(level.getBlockEntity(pos) instanceof CartridgeComputerBlockEntity computer)||!stack.is(a.item.get())||stack.getCount()!=1
                ||p.distanceToSqr(pos.getCenter())>25||!level.mayInteract(p,pos))return;
        var s=state(p);var old=s.edits.get(p.getUUID());
        if(old!=null&&System.nanoTime()-old.opened<500_000_000L)return;
        if(old==null&&s.edits.size()>=4){say(p,"写卡服务繁忙，请稍后重试。");return;}
        if(features(system).publicSaves()){ContentCardData.ensureId(stack);p.inventoryMenu.broadcastChanges();}
        var e=new Edit(p,hand,pos,computer,system,stack);s.edits.put(p.getUUID(),e);
        e.originalCover=ContentCardData.cover(stack);
        reply(p,e,OPEN,a.label+"：选择游戏后明确写入卡带",0,List.of());card(p,e);list(p,s,e,0,"");
    }
    private static boolean valid(ServerPlayer p,State s,Edit e){return !s.closed&&s.edits.get(p.getUUID())==e&&facts(p,e,e.snapshot)
            &&System.nanoTime()-e.opened<300_000_000_000L;}
    private static boolean facts(ServerPlayer p,Edit e,ItemStack snapshot){return online(p,e.connection)&&p.serverLevel()==e.level&&e.level.hasChunkAt(e.pos)
            &&!e.computer.isRemoved()&&e.level.getWorldBorder().isWithinBounds(e.pos)&&p.containerMenu==p.inventoryMenu
            &&e.binding.permits(e.computer.computerId(),p.serverLevel().dimension().location().toString(),e.level.getBlockEntity(e.pos)==e.computer,
                    e.level.hasChunkAt(e.pos),p.isAlive()&&!p.isSpectator(),PlayerContentAccess.canBrowse(p),e.level.mayInteract(p,e.pos),p.distanceToSqr(e.pos.getCenter()))
            &&p.getItemInHand(e.hand)==e.stack&&(e.hand==InteractionHand.OFF_HAND||p.getInventory().selected==e.slot)
            &&e.stack.getCount()==1&&ItemStack.matches(e.stack,snapshot)&&p.distanceToSqr(e.pos.getCenter())<=25&&e.level.mayInteract(p,e.pos)&&PlayerContentAccess.canBrowse(p);}
    private static boolean uniqueCard(ServerPlayer p,UUID id){
        Set<ItemStack> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        for(int i=0;i<p.getInventory().getContainerSize();i++)seen.add(p.getInventory().getItem(i));
        for(var slot:p.inventoryMenu.slots)seen.add(slot.getItem());seen.add(p.containerMenu.getCarried());
        int copies=0;for(var stack:seen)if(id.equals(ContentCardData.id(stack)))copies+=stack.getCount();return copies==1;
    }
    private static boolean permitted(ServerPlayer p,Edit e,ItemStack snapshot,UUID id){
        if(PROTECTING.get()||!facts(p,e,snapshot)||!uniqueCard(p,id))return false;
        PROTECTING.set(true);try{
            var hit=new net.minecraft.world.phys.BlockHitResult(e.pos.getCenter(),net.minecraft.core.Direction.UP,e.pos,false);
            var event=NeoForge.EVENT_BUS.post(new net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock(p,e.hand,e.pos,hit));
            return !event.isCanceled()&&event.getUseBlock()!=net.neoforged.neoforge.common.util.TriState.FALSE
                    &&event.getUseItem()!=net.neoforged.neoforge.common.util.TriState.FALSE&&facts(p,e,snapshot)&&uniqueCard(p,id);
        }finally{PROTECTING.remove();}
    }
    public static EditorGrant authorizeEditor(ServerPlayer p,ResourceLocation system,UUID token){
        var s=STATES.get(p.getServer());var e=s==null?null:s.edits.get(p.getUUID());
        if(e==null||!e.token.equals(token)||!e.system.equals(system)||!features(system).publicSaves()||e.busy||e.upload!=null||!valid(p,s,e))return null;
        var entry=ContentCardData.read(e.stack,system);UUID id=ContentCardData.id(e.stack);var snapshot=e.stack.copy();
        if(entry==null||id==null||!permitted(p,e,snapshot,id))return null;
        long until=System.nanoTime()+60_000_000_000L;
        return new EditorGrant(system,e.token,e.pos,id,entry,ContentCardData.saveMode(e.stack),ContentCardData.players(e.stack),snapshot,
                ()->System.nanoTime()<until&&permitted(p,e,snapshot,id));
    }
    public static void handle(ServerPlayer p,Message m){
        var s=STATES.get(p.getServer());if(s==null)return;
        var play=s.plays.get(p.getUUID());
        if(play!=null&&play.token.equals(m.token())&&play.system.equals(m.system())&&play.pos.equals(m.pos())){playMessage(p,s,play,m);return;}
        var read=s.reads.get(p.getUUID(),m.token(),p.connection.getConnection());
        if(read!=null&&read.system.equals(m.system())&&read.pos.equals(m.pos())){playMessage(p,s,read,m);return;}
        var e=s.edits.get(p.getUUID());if(e==null||!e.token.equals(m.token())||!e.system.equals(m.system())||!e.pos.equals(m.pos()))return;
        if(!valid(p,s,e)){s.edits.remove(p.getUUID());reply(p,e,CANCEL,"写卡已取消：卡带、电脑、距离或权限改变",0,List.of());return;}
        if(m.op()==CANCEL){s.edits.remove(p.getUUID());return;}
        if(e.busy)return;
        try{
            if(e.upload!=null&&m.op()!=PART)return;
            if(m.op()==RENAME||m.op()==WRITE||m.op()==UPLOAD||m.op()==COVER_WRITE||m.op()==COVER_UPLOAD||m.op()==SAVE_MODE||m.op()==PLAYERS||m.op()==SAVE_LIBRARY){
                long now=System.nanoTime();if(now-e.lastMutation<250_000_000L){reply(p,e,STATUS,"操作过快，请稍后重试",0,List.of());return;}e.lastMutation=now;
            }
            if(m.op()==COVER_LIST){
                if(!features(e.system).covers()||!PlayerContentAccess.canUseServerCover(p))throw new IllegalArgumentException("封面库不可用或未授权");
                if(System.nanoTime()-e.lastCoverList<1_000_000_000L)throw new IllegalArgumentException("刷新过快，请稍后重试");
                ContentCardWorkbench.page(List.of(),m.name(),m.offset());e.lastCoverList=System.nanoTime();
                job(p,s,e,()->covers(p).scan(),scan->{
                    if(!PlayerContentAccess.canUseServerCover(p))throw new IllegalArgumentException("封面权限已撤销");
                    var entries=scan.entries();logFailures(e,scan);
                    e.coverCatalog=entries;var result=ContentCardWorkbench.page(entries,m.name(),m.offset());
                    send(p,new Message(COVER_LIST,e.system,e.token,e.pos,"",scan.summary("服务器封面"),result.total(),result.index(),scan.diagnostics(),result.entries()));
                });
            }else if(m.op()==COVER_WRITE){
                if(!features(e.system).covers())throw new IllegalArgumentException("此机型尚未接入封面");
                if(m.hash().isEmpty()){applyCover(p,e,"");}
                else{
                    if(!PlayerContentAccess.canUseServerCover(p)||(!m.hash().equals(e.originalCover)&&e.coverCatalog.stream().noneMatch(c->c.hash().equals(m.hash()))))throw new IllegalArgumentException("请先刷新并选择服务器封面");
                    job(p,s,e,()->covers(p).read(m.hash()),bytes->{if(!PlayerContentAccess.canUseServerCover(p))throw new IllegalArgumentException("封面权限已撤销");applyCover(p,e,m.hash());});
                }
            }else if(m.op()==SAVE_MODE){
                if(!features(e.system).allowsSaveMode(m.offset()))throw new IllegalArgumentException("此机型尚未接入所选保存方式");
                ContentCardData.saveMode(e.stack,m.offset());e.snapshot=e.stack.copy();p.inventoryMenu.broadcastChanges();card(p,e);reply(p,e,STATUS,"保存方式已设置；已有存档保留，下次开机生效",0,List.of());
            }else if(m.op()==PLAYERS){
                if(m.offset()<1||m.offset()>features(e.system).maxPlayers())throw new IllegalArgumentException("人数超过此机型已接入的端口能力");
                ContentCardData.players(e.stack,m.offset());e.snapshot=e.stack.copy();p.inventoryMenu.broadcastChanges();card(p,e);
                reply(p,e,STATUS,"人数标签已设置；只用于加入限制，不会把单人游戏变成双人",0,List.of());
            }else if(m.op()==SAVE_LIBRARY){
                var library=SAVE_LIBRARIES.get(e.system);var grant=authorizeEditor(p,e.system,e.token);
                if(library==null||grant==null)throw new IllegalArgumentException("存档库未接入或电脑／卡带授权失效，请重新打开");
                library.accept(p,grant);
            }else if(m.op()==COVER_UPLOAD){
                if(!features(e.system).covers()||!PlayerContentAccess.canUploadCover(p))throw new IllegalArgumentException("管理员未允许上传封面");
                if(m.size()<1||m.size()>cn.piq.fcarcade.home.CartridgeLimits.MAX_COVER_BYTES||reserved(s)+m.size()>64L*1024*1024)throw new IllegalArgumentException("封面超出传输预算");
                e.entry=new ContentCardStore.Entry(m.hash(),"cover.png",m.size());e.uploadCover=true;e.upload=new CartridgeTransfer(m.size(),System.nanoTime());reply(p,e,READY,"开始上传封面",0,List.of());
            }else if(m.op()==LIST){if(System.nanoTime()-e.lastList<1_000_000_000L){reply(p,e,STATUS,"刷新过快，请稍后再试",0,List.of());return;}list(p,s,e,m.offset(),m.name());}
            else if(m.op()==RENAME&&e.upload==null){
                var current=ContentCardData.read(e.stack,e.system);
                if(current==null||!current.hash().equals(m.hash()))throw new IllegalArgumentException("卡带内容已变化，请重新打开");
                write(p,e,current,ContentCardWorkbench.title(m.name()));
            }
            else if(m.op()==WRITE&&e.upload==null){
                if(!PlayerContentAccess.canUseServerRom(p))throw new IllegalArgumentException("没有使用服务器 ROM 的权限");
                var selected=e.catalog.stream().filter(v->v.hash().equals(m.hash())).findFirst().orElseThrow();
                String title=ContentCardWorkbench.title(m.name().isBlank()?selected.displayName():m.name());
                job(p,s,e,()->{e.store.read(selected);return selected;},entry->{
                    if(!PlayerContentAccess.canUseServerRom(p))throw new IllegalArgumentException("服务器 ROM 权限已撤销");write(p,e,entry,title);
                });
            }else if(m.op()==UPLOAD){
                e.uploadCover=false;
                if(!PlayerContentAccess.canUploadRom(p))throw new IllegalArgumentException("管理员未允许上传 ROM");
                if(e.upload!=null||m.size()<1||m.size()>ADAPTERS.get(e.system).maxBytes||reserved(s)+m.size()>64L*1024*1024)throw new IllegalArgumentException("上传大小或总预算无效，请稍后重试");
                var entry=new ContentCardStore.Entry(m.hash(),m.name(),m.size());
                if(!e.store.accepts(entry.name()))throw new IllegalArgumentException("文件扩展名不匹配");
                e.uploadTitle=ContentCardWorkbench.uploadTitle(m.data(),entry.name());
                e.upload=new CartridgeTransfer(m.size(),System.nanoTime());e.entry=entry;reply(p,e,READY,"开始上传到服务器；完成后写卡",0,List.of());
            }else if(m.op()==PART){
                if(!(e.uploadCover?PlayerContentAccess.canUploadCover(p):PlayerContentAccess.canUploadRom(p))||e.upload==null||e.upload.expired(System.nanoTime()))throw new IllegalArgumentException("上传已取消或超时");
                e.upload.append(m.offset(),m.data());
                if(e.upload.received()<e.upload.total())reply(p,e,READY,"正在上传…",e.upload.received(),List.of());
                else {byte[] complete=e.upload.finish();e.upload=null;var entry=e.entry;
                    if(e.uploadCover){
                        job(p,s,e,()->{covers(p).store(entry.hash(),complete);return entry.hash();},hash->{
                            if(!PlayerContentAccess.canUploadCover(p))throw new IllegalArgumentException("封面上传权限已撤销");applyCover(p,e,hash);
                        });return;
                    }
                    job(p,s,e,()->e.store.store(entry.name(),entry.hash(),complete),stored->{
                        if(!PlayerContentAccess.canUploadRom(p))throw new IllegalArgumentException("上传权限已撤销；原卡未改动");write(p,e,stored,e.uploadTitle);
                    });
                }
            }
        }catch(RuntimeException error){e.upload=null;reply(p,e,STATUS,"操作失败，原卡未改动："+clean(error),0,List.of());}
    }
    private static void list(ServerPlayer p,State s,Edit e,int page,String query){
        ContentCardWorkbench.page(List.of(),query,page);e.lastList=System.nanoTime();
        job(p,s,e,e.store::scan,scan->{var entries=scan.entries();e.catalog=entries;logFailures(e,scan);var result=ContentCardWorkbench.page(entries,query,page);
            send(p,new Message(LIST,e.system,e.token,e.pos,"",scan.summary("服务器游戏")+(result.total()==0&&!entries.isEmpty()?"；没有匹配搜索结果":""),result.total(),result.index(),scan.diagnostics(),result.entries()));});
    }
    private static void logFailures(Edit e,ContentCardStore.Scan scan){for(var failure:scan.failures())cn.piq.fcarcade.FcArcadeMod.LOGGER.warn("[ContentCard {}] 扫描拒绝 {}",e.system,failure);for(var warning:scan.warnings())cn.piq.fcarcade.FcArcadeMod.LOGGER.warn("[ContentCard {}] 名称警告 {}",e.system,warning);}
    private static void card(ServerPlayer p,Edit e){
        var entry=ContentCardData.read(e.stack,e.system);
        send(p,new Message(CARD,e.system,e.token,e.pos,entry==null?"":entry.hash(),ContentCardData.title(e.stack),
                PlayerContentAccess.capabilities(p),ContentCardData.saveMode(e.stack),ContentCardData.cover(e.stack).getBytes(java.nio.charset.StandardCharsets.US_ASCII),entry==null?List.of():List.of(entry)));
        send(p,msg(CARD_OPTIONS,e.system,e.token,e.pos,"","",0,ContentCardData.players(e.stack),new byte[0]));
    }
    private static void applyCover(ServerPlayer p,Edit e,String hash){ContentCardData.cover(e.stack,hash);e.snapshot=e.stack.copy();p.inventoryMenu.broadcastChanges();card(p,e);reply(p,e,STATUS,"卡带封面已更新",0,List.of());}
    private static void write(ServerPlayer p,Edit e,ContentCardStore.Entry entry,String title){
        ContentCardData.write(e.stack,e.system,entry,title);e.snapshot=e.stack.copy();p.inventoryMenu.broadcastChanges();card(p,e);reply(p,e,STATUS,"写卡成功："+title,0,List.of());
    }
    private static <T> void job(ServerPlayer p,State s,Edit e,Callable<T> task,Consumer<T> done){
        e.busy=true;var server=p.getServer();
        try{IO.execute(()->{T result=null;Exception failure=null;try{result=task.call();}catch(Exception error){failure=error;}var value=result;var error=failure;
            server.execute(()->{e.busy=false;if(!valid(p,s,e))return;if(error!=null){reply(p,e,STATUS,"操作失败，原卡未改动："+clean(error),0,List.of());return;}
                try{done.accept(value);}catch(RuntimeException invalid){reply(p,e,STATUS,"操作取消："+clean(invalid),0,List.of());}});
        });}catch(RejectedExecutionException full){e.busy=false;reply(p,e,STATUS,"IO 队列已满，请稍后重试",0,List.of());}
    }
    /** Caller supplies a live world/lease/power grant. Callback false means shutdown, never native success. */
    public static UUID play(ServerPlayer p,ResourceLocation system,BlockPos pos,ContentCardStore.Entry entry,BooleanSupplier authorized,Consumer<Boolean> status){
        return transfer(p,system,UUID.randomUUID(),pos,entry,authorized,status,false);
    }
    /** A server-validated seat/watch grant; the request UUID correlates a client expectation, never grants access.
     * Status true means verified bytes were received, NOT that a core started. No runtime/heartbeat is created. */
    public static UUID downloadOnly(ServerPlayer p,ResourceLocation system,UUID request,BlockPos pos,ContentCardStore.Entry entry,
                                    BooleanSupplier authorized,Consumer<Boolean> status){
        Objects.requireNonNull(request);Objects.requireNonNull(authorized);Objects.requireNonNull(status);
        if(p==null||!online(p,p.connection.getConnection()))return null;
        return transfer(p,system,request,pos,entry,authorized,status,true);
    }
    private static UUID transfer(ServerPlayer p,ResourceLocation system,UUID request,BlockPos pos,ContentCardStore.Entry entry,
                                 BooleanSupplier authorized,Consumer<Boolean> status,boolean downloadOnly){
        var s=state(p);var adapter=ADAPTERS.get(system);
        if(adapter==null||entry.size()>adapter.maxBytes||reserved(s)+entry.size()>64L*1024*1024||s.closed
                ||!downloadOnly&&(s.plays.containsKey(p.getUUID())||s.plays.size()>=4)||!authorized.getAsBoolean())return null;
        var existing=s.plays.get(p.getUUID());if(existing!=null&&existing.token.equals(request))return null;
        var play=new Play(p,system,request,pos,entry,authorized,status,downloadOnly);
        if(downloadOnly){if(!s.reads.add(p.getUUID(),request,play.connection,entry.size(),play,reserved(s)-s.reads.reservedBytes()))return null;}
        else s.plays.put(p.getUUID(),play);
        var server=p.getServer();var files=store(p,system);
        try{IO.execute(()->{byte[] data=null;Exception error=null;try{data=files.read(entry);}catch(Exception ex){error=ex;}var bytes=data;var failure=error;
            server.execute(()->{if(!valid(p,s,play))return;if(failure!=null){say(p,"卡带启动失败："+clean(failure));stop(p,s,play);return;}
                play.bytes=bytes;send(p,msg(downloadOnly?DOWNLOAD_ONLY:DOWNLOAD,system,play.token,pos,entry.hash(),entry.name(),entry.size(),0,new byte[0]));});
        });}catch(RejectedExecutionException full){stop(p,s,play);return null;}return play.token;
    }
    private static boolean valid(ServerPlayer p,State s,Play play){return !s.closed
            &&(play.downloadOnly?s.reads.get(p.getUUID(),play.token,play.connection)==play:s.plays.get(p.getUUID())==play)
            &&online(p,play.connection)&&play.authorized.getAsBoolean();}
    /** Server world action only; clients cannot send this opcode to gain reset authority. */
    public static boolean reset(ServerPlayer p,UUID token){
        var s=STATES.get(p.getServer());var play=s==null?null:s.plays.get(p.getUUID());
        if(play==null||play.downloadOnly||!play.token.equals(token)||!play.started||!valid(p,s,play))return false;
        send(p,msg(RESET,play.system,play.token,play.pos,"","",0,0,new byte[0]));return true;
    }
    private static long reserved(State s){
        long bytes=0;for(var e:s.edits.values()){if(e.upload!=null)bytes+=e.upload.total();else if(e.busy&&e.entry!=null)bytes+=e.entry.size();}
        for(var p:s.plays.values())if(!p.started)bytes+=p.entry.size();return bytes+s.reads.reservedBytes();
    }
    private static void playMessage(ServerPlayer p,State s,Play play,Message m){
        if(!valid(p,s,play)){stop(p,s,play);return;}
        if(m.op()==STOP){stop(p,s,play);return;}
        if(m.op()==GET&&play.bytes!=null&&m.offset()==play.offset&&play.offset<play.bytes.length){
            int end=Math.min(play.offset+ContentCardStore.CHUNK,play.bytes.length);byte[] part=Arrays.copyOfRange(play.bytes,play.offset,end);
            send(p,msg(DATA,play.system,play.token,play.pos,play.entry.hash(),play.entry.name(),play.entry.size(),play.offset,part));play.offset=end;
            if(end==play.bytes.length)play.bytes=null;
        }else if(m.op()==STARTED&&play.offset==play.entry.size()&&!play.started){
            play.started=true;play.last=System.nanoTime();
            if(play.downloadOnly){s.reads.remove(p.getUUID(),play.token,play);play.bytes=null;}
            play.status.accept(true);
        }else if(play.downloadOnly){stop(p,s,play);return;}
        if(m.op()==HEARTBEAT&&play.started)play.last=System.nanoTime();
    }
    public static void stop(ServerPlayer p,UUID token){var s=STATES.get(p.getServer());if(s==null)return;var play=s.plays.get(p.getUUID());
        if(play!=null&&play.token.equals(token))stop(p,s,play);
        else{var read=s.reads.get(p.getUUID(),token,p.connection.getConnection());if(read!=null)stop(p,s,read);}
    }
    private static void stop(ServerPlayer p,State s,Play play){
        if(!(play.downloadOnly?s.reads.remove(p.getUUID(),play.token,play):s.plays.remove(p.getUUID(),play)))return;play.bytes=null;
        if(online(p,play.connection))send(p,msg(STOP,play.system,play.token,play.pos,"","",0,0,new byte[0]));play.status.accept(false);
    }
    private static void tick(MinecraftServer server){
        var s=STATES.get(server);if(s==null)return;
        for(var id:List.copyOf(s.edits.keySet())){var p=server.getPlayerList().getPlayer(id);var e=s.edits.get(id);
            if(p==null||!valid(p,s,e)){s.edits.remove(id);if(p!=null&&online(p,e.connection))reply(p,e,CANCEL,"离开电脑或卡带改变，写卡已取消",0,List.of());}}
        for(var id:List.copyOf(s.plays.keySet())){var play=s.plays.get(id);var p=server.getPlayerList().getPlayer(id);
            if(p==null){s.plays.remove(id);play.bytes=null;play.status.accept(false);}
            else if(!valid(p,s,play)||System.nanoTime()-play.last>(play.started?15:120)*1_000_000_000L)stop(p,s,play);}
        for(var entry:s.reads.snapshot()){var read=entry.value();var p=server.getPlayerList().getPlayer(entry.player());
            if(p==null){if(s.reads.remove(entry.player(),entry.token(),read)){read.bytes=null;read.status.accept(false);}}
            else if(!valid(p,s,read)||System.nanoTime()-read.last>120_000_000_000L)stop(p,s,read);
        }
    }
    private static void reply(ServerPlayer p,Edit e,int op,String text,int offset,List<ContentCardStore.Entry> entries){send(p,new Message(op,e.system,e.token,e.pos,"",text.substring(0,Math.min(250,text.length())),0,offset,new byte[0],entries));}
    private static String clean(Throwable e){var m=e.getMessage();if(m==null)m=e.getClass().getSimpleName();return m.replaceAll("[\\p{Cntrl}]"," ").substring(0,Math.min(160,m.length()));}
    private static void say(ServerPlayer p,String text){p.displayClientMessage(net.minecraft.network.chat.Component.literal(text),false);}
    private static final class State{boolean closed;final Map<UUID,Edit> edits=new HashMap<>();final Map<UUID,Play> plays=new HashMap<>();final ContentCardReadTransfers<Play> reads=new ContentCardReadTransfers<>();}
    private static final class Edit{
        final UUID token=UUID.randomUUID(),computerId;final Object connection;final ServerLevel level;final BlockPos pos;final CartridgeComputerBlockEntity computer;
        final ResourceLocation system;final InteractionHand hand;final int slot;final ItemStack stack;final CartridgeComputerBinding binding;ItemStack snapshot;final long opened=System.nanoTime();
        final ContentCardStore store;long lastList,lastCoverList,lastMutation;boolean busy,uploadCover;CartridgeTransfer upload;String uploadTitle="",originalCover;ContentCardStore.Entry entry;List<ContentCardStore.Entry> catalog=List.of(),coverCatalog=List.of();
        Edit(ServerPlayer p,InteractionHand h,BlockPos pos,CartridgeComputerBlockEntity c,ResourceLocation system,ItemStack stack){connection=p.connection.getConnection();level=p.serverLevel();this.pos=pos.immutable();computer=c;computerId=c.computerId();binding=new CartridgeComputerBinding(computerId,level.dimension().location().toString(),pos.getX(),pos.getY(),pos.getZ());this.system=system;store=store(p,system);hand=h;slot=p.getInventory().selected;this.stack=stack;snapshot=stack.copy();}
    }
    private static final class Play{
        final UUID token;final Object connection;final ResourceLocation system;final BlockPos pos;final ContentCardStore.Entry entry;final BooleanSupplier authorized;final Consumer<Boolean> status;final boolean downloadOnly;
        long last=System.nanoTime();int offset;byte[] bytes;boolean started;
        Play(ServerPlayer p,ResourceLocation system,UUID token,BlockPos pos,ContentCardStore.Entry entry,BooleanSupplier auth,Consumer<Boolean> status,boolean downloadOnly){connection=p.connection.getConnection();this.system=system;this.token=token;this.pos=pos.immutable();this.entry=entry;authorized=auth;this.status=status;this.downloadOnly=downloadOnly;}
    }
    private ContentCards(){}
}
