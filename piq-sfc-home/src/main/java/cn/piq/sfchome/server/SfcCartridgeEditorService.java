package cn.piq.sfchome.server;
import cn.piq.fcarcade.home.CartridgeComputerBlockEntity;
import cn.piq.fcarcade.access.PlayerContentAccess;
import cn.piq.sfchome.data.SfcCartridgeData;
import cn.piq.sfchome.net.SfcHomeNetwork;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.core.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/** Computer-bound hand-held transaction. Cancellation never consumes or replaces the old cartridge. */
public final class SfcCartridgeEditorService {
    private static final Map<MinecraftServer,State> STATES=new WeakHashMap<>();
    private static final ExecutorService IO=new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(32),r->{Thread t=new Thread(r,"piq-sfc-home-files");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private static final ThreadLocal<Boolean> CHECKING=ThreadLocal.withInitial(()->false);
    private SfcCartridgeEditorService(){}
    private static State state(MinecraftServer s){return STATES.computeIfAbsent(s,k->new State(new SfcRomStore(cn.piq.retro.storage.ConsoleStorage.root(s.getWorldPath(LevelResource.ROOT)).resolve("piq-sfc-home/roms"))));}
    public static void openAt(ServerPlayer p,InteractionHand hand,BlockPos pos){
        if(p==null||p.getServer()==null||!p.getServer().isSameThread())return;
        if(CHECKING.get()||!p.serverLevel().hasChunkAt(pos)||!(p.serverLevel().getBlockEntity(pos) instanceof CartridgeComputerBlockEntity computer))return;
        ItemStack stack=p.getItemInHand(hand);if(!PlayerContentAccess.canBrowse(p)){SfcHomeServer.feedback(p,"管理员尚未授权你使用服务器游戏库");return;}
        if(!SfcCartridgeData.supported(stack)){SfcHomeServer.feedback(p,"请手持单张无附加组件的 SFC 卡带");return;}
        State st=state(p.getServer());if(st.tick-st.lastOpen.getOrDefault(p.getUUID(),-100L)<20||st.edits.size()>=4&&!st.edits.containsKey(p.getUUID())){SfcHomeServer.feedback(p,"编辑工作台繁忙，请稍后重试");return;}st.lastOpen.put(p.getUUID(),st.tick);
        Edit old=st.edits.get(p.getUUID());if(old!=null)cancel(p,st,old,"已切换编辑目标");
        Edit edit=new Edit(p,hand,stack,computer,st.tick);
        if(!valid(p,edit)){SfcHomeServer.feedback(p,"电脑或卡带授权失效");return;}
        SfcCartridgeData.ensureId(stack);edit.snapshot=stack.copy();st.edits.put(p.getUUID(),edit);
        if(!valid(p,edit)){cancel(p,st,edit,"电脑或卡带授权失效");return;}refresh(p,st,edit,true,"选择 SFC ROM 写入卡带");
    }
    private static boolean facts(ServerPlayer p,Edit e){
        if(p.hasDisconnected()||!p.isAlive()||p.isSpectator()||!PlayerContentAccess.canBrowse(p)||p.connection.getConnection()!=e.connection||p.getServer().getPlayerList().getPlayer(p.getUUID())!=p||p.serverLevel()!=e.level||!e.level.hasChunkAt(e.pos)||e.level.getBlockEntity(e.pos)!=e.computer||e.computer.isRemoved()||!e.computer.computerId().equals(e.computerId)||p.distanceToSqr(e.pos.getCenter())>25||!e.level.getWorldBorder().isWithinBounds(e.pos)||!e.level.mayInteract(p,e.pos)||p.getItemInHand(e.hand)!=e.stack||e.hand==InteractionHand.MAIN_HAND&&p.getInventory().selected!=e.slot||!SfcCartridgeData.supported(e.stack)||!ItemStack.isSameItemSameComponents(e.stack,e.snapshot))return false;
        int copies=0;UUID id=SfcCartridgeData.id(e.stack);Set<ItemStack>seen=Collections.newSetFromMap(new IdentityHashMap<>());List<ItemStack> candidates=new ArrayList<>();for(int i=0;i<p.getInventory().getContainerSize();i++)candidates.add(p.getInventory().getItem(i));for(var slot:p.inventoryMenu.slots)candidates.add(slot.getItem());candidates.add(p.containerMenu.getCarried());for(ItemStack s:candidates)if(seen.add(s)&&SfcCartridgeData.isCartridge(s)&&(id==null?s==e.stack:Objects.equals(id,SfcCartridgeData.id(s))))copies+=s.getCount();return copies==1;
    }
    private static boolean valid(ServerPlayer p,Edit e){
        if(CHECKING.get()||!facts(p,e))return false;CHECKING.set(true);try{var hit=new BlockHitResult(e.pos.getCenter(),Direction.UP,e.pos,false);var event=NeoForge.EVENT_BUS.post(new PlayerInteractEvent.RightClickBlock(p,e.hand,e.pos,hit));return !event.isCanceled()&&event.getUseBlock()!=TriState.FALSE&&event.getUseItem()!=TriState.FALSE&&facts(p,e);}catch(RuntimeException ignored){return false;}finally{CHECKING.remove();}
    }
    public static void handle(ServerPlayer p,SfcHomeNetwork.EditorAction a){if(p==null||p.getServer()==null||!p.getServer().isSameThread())return;State st=state(p.getServer());Edit e=st.edits.get(p.getUUID());if(e==null||!e.token.equals(a.token()))return;if(!valid(p,e)){cancel(p,st,e,"电脑或卡带授权失效，编辑已取消");return;}
        if(e.packetTick!=st.tick){e.packetTick=st.tick;e.packetCount=0;}if(++e.packetCount>16){cancel(p,st,e,"请求过快，编辑已取消");return;}e.last=st.tick;
        if(a.operation()==SfcHomeNetwork.CANCEL){cancel(p,st,e,"编辑已取消");return;}
        if(e.upload==null&&a.hash().equals(e.abortedUploadHash)&&(a.operation()==SfcHomeNetwork.UPLOAD_CHUNK||a.operation()==SfcHomeNetwork.UPLOAD_FINISH))return;
        if(e.upload!=null&&!uploadAllowed(p,e.uploadCover)){abortUpload(p,st,e);return;}
        if(e.committingCover!=null&&!uploadAllowed(p,e.committingCover))e.commitRevoked=true;
        if(e.busy){reply(p,e,false,"正在处理，请稍候",e.catalog);return;}
        switch(a.operation()){
            case SfcHomeNetwork.SET_SAVE_MODE->{
                if(e.upload!=null||a.offset()!=0||a.data().length!=0||!a.hash().equals(SfcCartridgeData.romSha(e.stack))||a.total()<0||a.total()>2||!a.name().isEmpty()){reply(p,e,false,"存档设置参数无效",e.catalog);return;}
                if(cn.piq.fcarcade.netplay.NetplaySaveServer.busyPrefix("sfc-card|"+SfcCartridgeData.id(e.stack)+"|")){reply(p,e,false,"此卡带的 Netplay 仍在运行或保存，请稍后修改",e.catalog);return;}
                SfcCartridgeData.setSaveMode(e.stack,a.total());changed(p,e,"Netplay 存档归属已保存；不迁移旧档，下次开机生效");
            }
            case SfcHomeNetwork.SAVE_NAME, SfcHomeNetwork.SET_PLAYERS, SfcHomeNetwork.CLEAR_COVER, SfcHomeNetwork.RESTORE_COVER->{
                boolean players=a.operation()==SfcHomeNetwork.SET_PLAYERS;
                if(players&&!p.hasPermissions(2)){reply(p,e,false,"人数设置仅管理员可修改；名称仍可保存到自己手中的卡带",e.catalog);return;}
                if(e.upload!=null||a.offset()!=0||a.data().length!=0||!a.hash().equals(SfcCartridgeData.romSha(e.stack))||(!players&&a.total()!=0)||(players&&a.total()!=1&&a.total()!=2)||a.name()==null||a.name().length()>128||a.name().chars().anyMatch(Character::isISOControl)){reply(p,e,false,"设置参数无效，旧卡带未改动",e.catalog);return;}
                try{
                if(a.operation()==SfcHomeNetwork.SAVE_NAME){SfcCartridgeData.write(e.stack,SfcCartridgeData.romSha(e.stack),a.name().strip());changed(p,e,"名称已保存");}
                else if(players){SfcCartridgeData.setPlayers(e.stack,a.total());changed(p,e,a.total()==1?"已设为单人":"已设为双人：领取两个手柄后开始");}
                else if(a.operation()==SfcHomeNetwork.CLEAR_COVER){SfcCartridgeData.setCover(e.stack,"");changed(p,e,"封面已清空");}
                else if(e.originalCover.isEmpty()){SfcCartridgeData.setCover(e.stack,"");changed(p,e,"已恢复打开时的空白封面");}
                else {e.busy=true;submit(p,st,e,()->SfcCoverService.store(p.getServer()).read(e.originalCover),png->{SfcCartridgeData.setCover(e.stack,e.originalCover);changed(p,e,"已恢复打开时的封面");});}
                }catch(RuntimeException invalid){reply(p,e,false,"设置未保存，请检查卡带和名称",e.catalog);}
            }
            case SfcHomeNetwork.REFRESH->{if(e.upload!=null){reply(p,e,false,"上传进行中",e.catalog);return;}refresh(p,st,e,false,"目录已刷新");}
            case SfcHomeNetwork.USE_COVER->{
                if(e.upload!=null||!PlayerContentAccess.canUseServerCover(p)||a.total()!=0||a.offset()!=0||a.data().length!=0||!a.name().isEmpty()||!e.covers.contains(a.hash())){reply(p,e,false,"请选择有权限使用的服务器封面",e.catalog);return;}
                e.busy=true;submit(p,st,e,()->SfcCoverService.store(p.getServer()).read(a.hash()),png->{
                    if(!PlayerContentAccess.canUseServerCover(p)){reply(p,e,false,"封面使用权限已关闭，旧卡带未改动",e.catalog);return;}
                    SfcCartridgeData.setCover(e.stack,a.hash());changed(p,e,"服务器封面已应用");
                });
            }
            case SfcHomeNetwork.WRITE->{if(e.upload!=null||a.total()!=0||a.offset()!=0||a.data().length!=0||a.hash().isEmpty()||e.catalog.stream().noneMatch(x->x.sha256().equals(a.hash()))){reply(p,e,false,"请选择当前目录中的 ROM",e.catalog);return;}write(p,st,e,a.hash(),a.name());}
            case SfcHomeNetwork.UPLOAD_START,SfcHomeNetwork.COVER_START->{boolean cover=a.operation()==SfcHomeNetwork.COVER_START;if(!uploadAllowed(p,cover)){reply(p,e,false,cover?"管理员未开放封面上传":"管理员未开放 ROM 上传，可选择服务器现有游戏",e.catalog);return;}if(e.upload!=null||a.total()<(cover?33:32768)||(cover&&a.total()>SfcHomeNetwork.MAX_COVER)||a.hash().isEmpty()||a.offset()!=0||a.data().length!=0||!(cover?safeCoverName(a.name()):safeName(a.name()))||st.tick-st.lastUpload.getOrDefault(p.getUUID(),-100L)<40||!st.uploadBudget.reserve(a.total())){reply(p,e,false,"上传参数无效或总量达到限制",e.catalog);return;}st.lastUpload.put(p.getUUID(),st.tick);e.upload=new byte[a.total()];e.uploadHash=a.hash();e.uploadName=a.name();e.uploadCover=cover;e.offset=0;reply(p,e,false,"UPLOAD_READY",e.catalog);}
            case SfcHomeNetwork.UPLOAD_CHUNK->{byte[]data=a.data();if(e.upload==null||a.offset()!=e.offset||a.total()!=e.upload.length||!a.hash().equals(e.uploadHash)||data.length<1||(long)e.offset+data.length>e.upload.length){cancel(p,st,e,"上传顺序错误，编辑已取消");return;}System.arraycopy(data,0,e.upload,e.offset,data.length);e.offset+=data.length;}
            case SfcHomeNetwork.UPLOAD_FINISH->{if(e.upload==null||e.offset!=e.upload.length||a.total()!=e.upload.length||a.offset()!=e.offset||!a.hash().equals(e.uploadHash)||a.data().length!=0){reply(p,e,false,"上传尚未完成",e.catalog);return;}byte[]data=e.upload;String hash=e.uploadHash,name=e.uploadName;boolean cover=e.uploadCover;e.upload=null;e.busy=true;e.committingCover=cover;e.commitRevoked=false;
                // FINISH admits immutable storage work; revocation prevents applying it to the card, not deleting committed files.
                if(cover){submit(p,st,e,()->{SfcCoverService.store(p.getServer()).store(hash,data);return hash;},stored->{if(!commitUploadAllowed(p,e,cover))return;SfcCartridgeData.setCover(e.stack,stored);changed(p,e,"封面已上传并应用");},()->{e.committingCover=null;e.commitRevoked=false;st.uploadBudget.release(data.length);});}
                else submit(p,st,e,()->{st.store.store(name,hash,data);return st.store.list();},catalog->{if(!valid(p,e)){cancel(p,st,e,"授权失效，旧卡带未改动");return;}if(!commitUploadAllowed(p,e,cover))return;if(catalog.stream().noneMatch(x->x.sha256().equals(hash))){reply(p,e,false,"ROM 已被移除，旧卡带未改动",catalog);return;}writeRom(e.stack,hash,name);e.snapshot=e.stack.copy();e.catalog=catalog;p.getInventory().setChanged();reply(p,e,false,"写入完成",catalog);},()->{e.committingCover=null;e.commitRevoked=false;st.uploadBudget.release(data.length);});}
            default->{}
        }
    }
    private static boolean uploadAllowed(ServerPlayer p,boolean cover){return cover?PlayerContentAccess.canUploadCover(p):PlayerContentAccess.canUploadRom(p);}
    private static void abortUpload(ServerPlayer p,State st,Edit e){if(e.upload!=null){e.abortedUploadHash=e.uploadHash;st.uploadBudget.release(e.upload.length);e.upload=null;}reply(p,e,false,"上传权限已关闭，未写入卡带",e.catalog);}
    private static boolean commitUploadAllowed(ServerPlayer p,Edit e,boolean cover){if(e.commitRevoked||!uploadAllowed(p,cover)){e.commitRevoked=true;reply(p,e,false,"上传权限已关闭，卡带未改动；已提交的文件可能已保存到服务器库",e.catalog);return false;}return true;}
    private static boolean safeCoverName(String name){return name!=null&&!name.isBlank()&&name.length()<=128&&!name.contains("/")&&!name.contains("\\")&&name.toLowerCase(Locale.ROOT).endsWith(".png");}
    private static void changed(ServerPlayer p,Edit e,String message){e.snapshot=e.stack.copy();p.getInventory().setChanged();reply(p,e,false,message,e.catalog);}
    private static void writeRom(ItemStack stack,String hash,String title){boolean fresh=SfcCartridgeData.romSha(stack).isEmpty()&&!SfcCartridgeData.hasExplicitPlayerCount(stack);SfcCartridgeData.write(stack,hash,title);if(fresh)SfcCartridgeData.setPlayers(stack,2);}
    private static boolean safeName(String name){return name!=null&&!name.isBlank()&&name.length()<=128&&!name.contains("/")&&!name.contains("\\")&&(name.toLowerCase(Locale.ROOT).endsWith(".sfc")||name.toLowerCase(Locale.ROOT).endsWith(".smc"));}
    private static void write(ServerPlayer p,State st,Edit e,String hash,String name){
        boolean needsPermission=!hash.equals(SfcCartridgeData.romSha(e.stack));
        if(needsPermission&&!PlayerContentAccess.canUseServerRom(p)){reply(p,e,false,"管理员未开放服务器ROM使用权限",e.catalog);return;}
        e.busy=true;submit(p,st,e,()->{st.store.read(hash);return st.store.list();},catalog->{
            if(needsPermission&&!PlayerContentAccess.canUseServerRom(p)){reply(p,e,false,"服务器ROM使用权限已关闭，旧卡带未改动",e.catalog);return;}
            if(catalog.stream().noneMatch(x->x.sha256().equals(hash))){reply(p,e,false,"此 ROM 已被移除，旧卡带未改动",catalog);return;}writeRom(e.stack,hash,name.isBlank()?catalog.stream().filter(x->x.sha256().equals(hash)).findFirst().orElseThrow().fileName():name);e.snapshot=e.stack.copy();e.catalog=catalog;p.getInventory().setChanged();reply(p,e,false,"写入完成",catalog);});}
    private record Catalog(List<SfcHomeNetwork.RomEntry> roms,List<String> covers){}
    private static void refresh(ServerPlayer p,State st,Edit e,boolean open,String message){boolean covers=PlayerContentAccess.canUseServerCover(p);var coverStore=SfcCoverService.store(p.getServer());e.busy=true;submit(p,st,e,()->new Catalog(st.store.list(),covers?coverStore.list():List.of()),c->{e.catalog=c.roms();e.covers=PlayerContentAccess.canUseServerCover(p)?c.covers():List.of();reply(p,e,open,message,e.catalog);});}
    private interface Work<T>{T run()throws Exception;}
    private static <T>void submit(ServerPlayer p,State st,Edit e,Work<T> work,java.util.function.Consumer<T> commit){submit(p,st,e,work,commit,()->{});}
    private static <T>void submit(ServerPlayer p,State st,Edit e,Work<T> work,java.util.function.Consumer<T> commit,Runnable completed){MinecraftServer server=p.getServer();try{IO.execute(()->{T result=null;Exception error=null;try{result=work.run();}catch(Exception ex){error=ex;}T value=result;Exception failure=error;server.execute(()->{try{if(STATES.get(server)!=st||st.edits.get(p.getUUID())!=e)return;e.busy=false;if(!valid(p,e)){cancel(p,st,e,"授权失效，旧卡带未改动");return;}if(failure!=null){reply(p,e,false,"文件操作失败，旧卡带未改动",e.catalog);return;}try{commit.accept(value);}catch(RuntimeException ex){reply(p,e,false,"写入失败，旧卡带未改动",e.catalog);}}finally{completed.run();}});});}catch(RejectedExecutionException ex){completed.run();e.busy=false;reply(p,e,false,"文件任务繁忙，请稍后重试",e.catalog);}}
    private static void reply(ServerPlayer p,Edit e,boolean open,String text,List<SfcHomeNetwork.RomEntry> catalog){e.lastCapabilities=PlayerContentAccess.capabilities(p);SfcHomeNetwork.send(p,new SfcHomeNetwork.Editor(e.token,open,text,SfcCartridgeData.romSha(e.stack),SfcCartridgeData.title(e.stack),SfcCartridgeData.coverSha(e.stack),SfcCartridgeData.maxPlayers(e.stack),SfcCartridgeData.hasExplicitPlayerCount(e.stack),e.lastCapabilities,PlayerContentAccess.canBrowse(p)?catalog:List.of(),PlayerContentAccess.canUseServerCover(p)?e.covers:List.of(),SfcCartridgeData.saveMode(e.stack)));}
    private static void cancel(ServerPlayer p,State st,Edit e,String reason){if(st.edits.remove(p.getUUID(),e)){if(e.upload!=null){st.uploadBudget.release(e.upload.length);e.upload=null;}removeDownload(st,p.getUUID());reply(p,e,false,reason,List.of());}}
    private static boolean downloadAuthorized(ServerPlayer p,String hash,State st){if(SfcHomeServer.authorizedRom(p,hash)
            ||cn.piq.fcarcade.cabinet.WatchNetplay.authorizedRom(p,cn.piq.sfchome.SfcHomeMod.CABINET_BACKEND,hash))return true;
        Edit e=st.edits.get(p.getUUID());return p.hasPermissions(2)&&e!=null&&valid(p,e)&&e.catalog.stream().anyMatch(x->x.sha256().equals(hash));}
    public static void download(ServerPlayer p,String hash){State st=state(p.getServer());if(!downloadAuthorized(p,hash,st)||st.downloads.containsKey(p.getUUID())||st.downloads.size()>=4||st.tick-st.lastDownload.getOrDefault(p.getUUID(),-100L)<40)return;st.lastDownload.put(p.getUUID(),st.tick);Download pending=new Download(hash);st.downloads.put(p.getUUID(),pending);MinecraftServer server=p.getServer();try{IO.execute(()->{byte[]data=null;try{data=st.store.read(hash);}catch(Exception ignored){}byte[]result=data;server.execute(()->{if(STATES.get(server)!=st||st.downloads.get(p.getUUID())!=pending)return;if(result==null||!downloadAuthorized(p,hash,st)||!st.downloadBudget.reserve(result.length)){removeDownload(st,p.getUUID());return;}pending.bytes=result;});});}catch(RejectedExecutionException ex){removeDownload(st,p.getUUID());}}
    private static void removeDownload(State st,UUID player){Download d=st.downloads.remove(player);if(d!=null&&d.bytes!=null)st.downloadBudget.release(d.bytes.length);}
    public static void tick(MinecraftServer server){SfcCoverService.tick(server);State st=state(server);st.tick++;for(var entry:List.copyOf(st.edits.entrySet())){ServerPlayer p=server.getPlayerList().getPlayer(entry.getKey());Edit e=entry.getValue();if(p==null){st.edits.remove(entry.getKey());if(e.upload!=null){st.uploadBudget.release(e.upload.length);e.upload=null;}continue;}if(st.tick-e.last>1200||!valid(p,e)){cancel(p,st,e,"电脑授权失效或超时，编辑已取消");continue;}if(e.upload!=null&&!uploadAllowed(p,e.uploadCover))abortUpload(p,st,e);if(e.committingCover!=null&&!uploadAllowed(p,e.committingCover))e.commitRevoked=true;if(e.lastCapabilities!=PlayerContentAccess.capabilities(p))reply(p,e,false,"PERMISSIONS_UPDATED",e.catalog);}
        for(var entry:List.copyOf(st.downloads.entrySet())){ServerPlayer p=server.getPlayerList().getPlayer(entry.getKey());Download d=entry.getValue();if(p==null||!downloadAuthorized(p,d.hash,st)){removeDownload(st,entry.getKey());continue;}if(d.bytes==null)continue;int n=Math.min(SfcHomeNetwork.CHUNK,d.bytes.length-d.offset);SfcHomeNetwork.send(p,new SfcHomeNetwork.RomChunk(d.hash,d.bytes.length,d.offset,Arrays.copyOfRange(d.bytes,d.offset,d.offset+n)));d.offset+=n;if(d.offset==d.bytes.length)removeDownload(st,entry.getKey());}
    }
    public static void closeServer(MinecraftServer s){STATES.remove(s);SfcCoverService.close(s);}
    private static final class State{final SfcRomStore store;final SfcTransferBudget uploadBudget=new SfcTransferBudget(),downloadBudget=new SfcTransferBudget();long tick;final Map<UUID,Edit>edits=new HashMap<>();final Map<UUID,Download>downloads=new HashMap<>();final Map<UUID,Long>lastDownload=new HashMap<>(),lastOpen=new HashMap<>(),lastUpload=new HashMap<>();State(SfcRomStore s){store=s;}}
    private static final class Download{final String hash;byte[]bytes;int offset;Download(String h){hash=h;}}
    private static final class Edit{final UUID token=UUID.randomUUID(),computerId;final String originalCover;final Object connection;final ServerLevel level;final BlockPos pos;final CartridgeComputerBlockEntity computer;final InteractionHand hand;final int slot;final ItemStack stack;ItemStack snapshot;long last,lastUpload=-100,packetTick=-1;int packetCount,offset,lastCapabilities=-1;boolean busy,uploadCover,commitRevoked;Boolean committingCover;byte[]upload;String uploadHash,uploadName,abortedUploadHash="";List<SfcHomeNetwork.RomEntry>catalog=List.of();List<String>covers=List.of();Edit(ServerPlayer p,InteractionHand h,ItemStack s,CartridgeComputerBlockEntity c,long tick){connection=p.connection.getConnection();level=p.serverLevel();pos=c.getBlockPos().immutable();computer=c;computerId=c.computerId();hand=h;slot=p.getInventory().selected;stack=s;snapshot=s.copy();originalCover=SfcCartridgeData.coverSha(s);last=tick;}}
}
