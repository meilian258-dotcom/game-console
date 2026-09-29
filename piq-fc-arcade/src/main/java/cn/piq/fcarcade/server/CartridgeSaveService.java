package cn.piq.fcarcade.server;

import cn.piq.fcarcade.access.PlayerContentAccess;
import cn.piq.fcarcade.home.*;
import cn.piq.fcarcade.home.CartridgeSaveNetwork.*;
import java.util.*;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Independent computer/card lease, restricted to real existing FC saves for the held ROM. */
public final class CartridgeSaveService {
    private static final Map<MinecraftServer,Map<UUID,Session>> SESSIONS=new WeakHashMap<>();
    private static final Map<MinecraftServer,Map<UUID,Long>> LAST_OPEN=new WeakHashMap<>();
    private static final ThreadLocal<Boolean> BUSY=ThreadLocal.withInitial(()->false);
    private static boolean registered;
    public record Grant(CartridgeComputerBlockEntity computer,InteractionHand hand,ItemStack stack,UUID card,String rom){}
    private static final class Session {
        final UUID token=UUID.randomUUID(),editor,player,computerId,card;final Object connection;final ServerLevel level;
        final CartridgeComputerBlockEntity computer;final InteractionHand hand;final ItemStack stack,snapshot;final int slot;
        final String rom;final FcSaveManagementStore store;long expires,lastRequest=-100,lastRateReply=-100;Map<String,Entry> shown=Map.of();
        UUID confirmation;String pendingId="",pendingVersion="";long confirmExpires;
        Session(ServerPlayer p,Open request,Grant grant){editor=request.editorToken();player=p.getUUID();connection=p.connection.getConnection();level=p.serverLevel();computer=grant.computer();computerId=computer.computerId();hand=grant.hand();stack=grant.stack();snapshot=stack.copy();slot=hand==InteractionHand.MAIN_HAND?p.getInventory().selected:40;card=grant.card();rom=grant.rom();expires=p.getServer().getTickCount()+1200L;
            store=new FcSaveManagementStore(cn.piq.fcarcade.storage.FcStoragePaths.prepareUnchecked(p.getServer().getServerDirectory(),cn.piq.fcarcade.storage.FcStoragePaths.Area.SAVES));}
        void clearConfirmation(){confirmation=null;pendingId="";pendingVersion="";confirmExpires=0;}
    }
    private CartridgeSaveService(){}
    static boolean editing(ServerPlayer player,ItemStack stack){var map=SESSIONS.get(player.getServer());var s=map==null?null:map.get(player.getUUID());return s!=null&&s.stack==stack;}
    public static void register(){if(registered)return;registered=true;CartridgeSaveNetwork.setServerHandlers(CartridgeSaveService::open,CartridgeSaveService::handle);
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent e)->{SESSIONS.remove(e.getServer());LAST_OPEN.remove(e.getServer());});
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post e)->{if(e.getServer().getTickCount()%20!=0)return;
            var rates=LAST_OPEN.get(e.getServer());if(rates!=null)rates.entrySet().removeIf(row->e.getServer().getPlayerList().getPlayer(row.getKey())==null||e.getServer().getTickCount()-row.getValue()>1200);
            var map=SESSIONS.get(e.getServer());if(map==null)return;
            for(var s:List.copyOf(map.values())){var p=e.getServer().getPlayerList().getPlayer(s.player);if(p==null||!facts(p,s)){map.remove(s.player,s);if(current(p))reply(p,s,"电脑、卡带或连接已改变，存档管理已关闭",true);}}});}
    private static boolean current(ServerPlayer p){return p!=null&&p.getServer()!=null&&p.getServer().isSameThread()&&!p.hasDisconnected()&&p.isAlive()&&!p.isSpectator()&&p.connection.getConnection().isConnected()&&p.getServer().getPlayerList().getPlayer(p.getUUID())==p;}
    private static boolean facts(ServerPlayer p,Session s){
        if(!current(p)||p.connection.getConnection()!=s.connection||p.serverLevel()!=s.level||s.expires<=p.getServer().getTickCount()||!PlayerContentAccess.canBrowse(p)
                ||!s.level.hasChunkAt(s.computer.getBlockPos())||s.computer.isRemoved()||s.level.getBlockEntity(s.computer.getBlockPos())!=s.computer||!s.computer.computerId().equals(s.computerId)
                ||!s.level.getWorldBorder().isWithinBounds(s.computer.getBlockPos())||!s.level.mayInteract(p,s.computer.getBlockPos())||p.distanceToSqr(s.computer.getBlockPos().getCenter())>25
                ||p.containerMenu!=p.inventoryMenu||p.getItemInHand(s.hand)!=s.stack||s.stack.getCount()!=1||!FcCartridgeData.supportsAssembly(s.stack)
                ||s.hand==InteractionHand.MAIN_HAND&&p.getInventory().selected!=s.slot||!ItemStack.isSameItemSameComponents(s.stack,s.snapshot)||!s.card.equals(FcCartridgeData.id(s.stack)))return false;
        Set<ItemStack> seen=Collections.newSetFromMap(new IdentityHashMap<>());for(int i=0;i<p.getInventory().getContainerSize();i++)seen.add(p.getInventory().getItem(i));for(var slot:p.inventoryMenu.slots)seen.add(slot.getItem());seen.add(p.containerMenu.getCarried());
        int copies=0;for(var item:seen)if(s.card.equals(FcCartridgeData.id(item)))copies+=item.getCount();return copies==1;
    }
    private static boolean permitted(ServerPlayer p,Session s){if(!facts(p,s))return false;var pos=s.computer.getBlockPos();
        var event=NeoForge.EVENT_BUS.post(new PlayerInteractEvent.RightClickBlock(p,s.hand,pos,new BlockHitResult(pos.getCenter(),Direction.UP,pos,false)));
        return !event.isCanceled()&&event.getUseBlock()!=TriState.FALSE&&event.getUseItem()!=TriState.FALSE&&facts(p,s);
    }
    private static void reject(ServerPlayer p,Open r,String message){CartridgeSaveNetwork.reply(p,new Reply(r.editorToken(),r.system(),"",message,List.of(),"","",null,true,r.editorToken()));}
    private static void open(ServerPlayer p,Open r){if(!current(p)||BUSY.get())return;
        var rates=LAST_OPEN.computeIfAbsent(p.getServer(),key->new HashMap<>());long now=p.getServer().getTickCount();
        if(now-rates.getOrDefault(p.getUUID(),now-20)<20||!rates.containsKey(p.getUUID())&&rates.size()>=256)return;
        rates.put(p.getUUID(),now);
        if(!r.system().equals("fc")){reject(p,r,"SFC 托管 SRAM 存档管理尚未接入；本机恢复备份和私人存档不在此目录");return;}
        BUSY.set(true);try{
            var grant=ServerCartridgeService.authorizeSaveManager(p,new CartridgeEditBinding(r.editorToken(),r.cartridgeId(),r.hand(),r.slot()));
            if(grant==null||!grant.rom().matches("[0-9a-f]{64}")){reject(p,r,"编辑授权失效、上传进行中或卡带为空；请重新打开电脑");return;}
            var map=SESSIONS.computeIfAbsent(p.getServer(),key->new HashMap<>());var old=map.get(p.getUUID());
            if(old!=null&&p.getServer().getTickCount()-old.lastRequest<10||old==null&&map.size()>=32){reject(p,r,"存档管理请求过快或名额已满");return;}
            var s=new Session(p,r,grant);if(!permitted(p,s)){reject(p,r,"没有此电脑的交互权限，存档未改动");return;}
            s.lastRequest=p.getServer().getTickCount()-20L;map.put(p.getUUID(),s);refresh(p,s,"仅显示当前卡带 ROM 的真实服务器存档；私人档不上传");
        }catch(Exception error){reject(p,r,"存档目录未打开，原文件未改动；请检查服务器日志");cn.piq.fcarcade.FcArcadeMod.LOGGER.warn("Cartridge save manager open rejected",error);}finally{BUSY.remove();}}
    private static void handle(ServerPlayer p,Request r){if(!current(p)||BUSY.get())return;var map=SESSIONS.get(p.getServer());var s=map==null?null:map.get(p.getUUID());if(s==null||!s.token.equals(r.token())||p.connection.getConnection()!=s.connection)return;
        BUSY.set(true);try{
            if(r.operation()==CartridgeSaveNetwork.CLOSE){map.remove(p.getUUID(),s);return;}
            long tick=p.getServer().getTickCount();
            // The full bounded directory read is still disk work: at most one admitted action per second.
            // Rate feedback reuses the last authorized metadata and does not scan or fire protection hooks.
            if(tick-s.lastRequest<20){if(tick-s.lastRateReply>=20&&facts(p,s)){s.lastRateReply=tick;reply(p,s,"操作间隔至少 1 秒，请稍后重试；存档未改动",false);}return;}s.lastRequest=tick;
            if(!permitted(p,s)||SESSIONS.get(p.getServer())!=map||map.get(p.getUUID())!=s){map.remove(p.getUUID(),s);reply(p,s,"授权已失效，存档未改动",true);return;}
            s.expires=tick+1200;
            if(r.operation()==CartridgeSaveNetwork.REFRESH){s.clearConfirmation();refresh(p,s,"目录已刷新");return;}
            Entry shown=s.shown.get(r.id());if(shown==null||!shown.version().equals(r.version())||!shown.canEdit()){s.clearConfirmation();refresh(p,s,"请选择有权限管理的当前存档版本");return;}
            var row=s.store.current(r.id());
            if(!row.rom().equals(s.rom)||!row.version().equals(r.version())||!FcSaveManagementStore.allowed(row,p.getUUID(),p.hasPermissions(2),s.card)||row.format()!=3
                    ||ServerArcadeSessions.cartridgeSaveActive(p.getServer(),row.key(),row.rom())){s.clearConfirmation();refresh(p,s,"存档已变化、没有权限或游戏仍在运行/保存；本次未改动");return;}
            if(r.operation()==CartridgeSaveNetwork.PREPARE_DELETE){s.pendingId=r.id();s.pendingVersion=r.version();s.confirmation=UUID.randomUUID();s.confirmExpires=tick+300;reply(p,s,"确认删除此存档？删除后移入服务器回收目录，不会自动恢复。",false);return;}
            if(r.operation()==CartridgeSaveNetwork.CONFIRM_DELETE){
                boolean confirmed=s.confirmation!=null&&s.confirmation.equals(r.confirmation())&&s.pendingId.equals(r.id())&&s.pendingVersion.equals(r.version())&&s.confirmExpires>tick;
                s.clearConfirmation();if(!confirmed){refresh(p,s,"删除确认已失效，请重新选择并确认");return;}
                s.store.delete(r.id(),r.version());refresh(p,s,"存档已删除并移入服务器回收目录；卡带与其他存档未改动");return;
            }
            s.clearConfirmation();s.store.rename(r.id(),r.version(),r.name());refresh(p,s,"存档名称已修改；状态数据和存档格式未改变");
        }catch(Exception error){s.clearConfirmation();try{refresh(p,s,"操作失败或存档已经变化；请刷新后重试，未自动修复/迁移");}catch(Exception refreshFailure){map.remove(p.getUUID(),s);reply(p,s,"存档目录不可用，管理已关闭；请检查服务器日志",true);}cn.piq.fcarcade.FcArcadeMod.LOGGER.warn("Cartridge save manager operation rejected",error);}finally{BUSY.remove();}}
    private static void refresh(ServerPlayer p,Session s,String message)throws Exception{
        var listing=s.store.list(s.rom,p.getUUID(),p.hasPermissions(2),s.card);var entries=new LinkedHashMap<String,Entry>();
        for(var row:listing.rows()){String owner=FcSaveManagementStore.owner(row.key());if(owner.isEmpty())owner=CartridgeSaveIdentity.owns(row.key(),s.card)?"当前卡带":"其他卡带 / 旧版机器存档";
            boolean active=ServerArcadeSessions.cartridgeSaveActive(p.getServer(),row.key(),row.rom());String name=row.name().isBlank()?"未命名存档":row.name();
            entries.put(row.id(),new Entry(row.id(),row.version(),name,owner,CartridgeSaveIdentity.owns(row.key(),s.card)?"FC · 卡带存档":FcSaveManagementStore.source(row.key()),row.modified(),row.bytes(),active,row.format()==3&&FcSaveManagementStore.allowed(row,p.getUUID(),p.hasPermissions(2),s.card)));}
        if(!facts(p,s))throw new IllegalStateException("Save binding changed");s.shown=Collections.unmodifiableMap(new LinkedHashMap<>(entries));
        reply(p,s,listing.truncated()?"目录达到安全扫描/数据上限，仅显示部分结果；空列表不代表没有存档。旧版存档只读。":message+"（旧版存档只读）",false);
    }
    private static void reply(ServerPlayer p,Session s,String message,boolean closed){if(closed)s.clearConfirmation();CartridgeSaveNetwork.reply(p,new Reply(s.token,"fc",s.rom,message,List.copyOf(s.shown.values()),s.pendingId,s.pendingVersion,s.confirmation,closed,s.editor));}
}
