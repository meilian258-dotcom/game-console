package cn.piq.fcarcade.home;

import cn.piq.fcarcade.ArcadeZapperSessionPayload;
import cn.piq.fcarcade.FcNetwork;
import cn.piq.fcarcade.server.ServerArcadeSessions;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/** Main-thread physical gun leases; no controller port is borrowed or transferred. */
public final class HomeZapperService {
    private static final Map<MinecraftServer,Map<UUID,Grant>> STATES=new WeakHashMap<>();
    private static final ThreadLocal<Boolean> CHECKING=ThreadLocal.withInitial(()->false);
    private static final class Grant {
        final UUID player;final Connection connection;ZapperBinding binding;final int issuedTick;int lastAimTick;
        Grant(ServerPlayer p,ZapperBinding b){player=p.getUUID();connection=p.connection.getConnection();binding=b;issuedTick=lastAimTick=p.getServer().getTickCount();}
    }
    private HomeZapperService(){}
    private static Map<UUID,Grant> state(MinecraftServer server){return STATES.computeIfAbsent(server,ignored->new HashMap<>());}
    public static InteractionResult useOn(ServerPlayer player,UseOnContext context){
        if(CHECKING.get()||context.getHand()!=InteractionHand.MAIN_HAND||!current(player))return InteractionResult.CONSUME;
        ItemStack stack=context.getItemInHand();
        if(!(stack.getItem() instanceof HomeZapperItem)||stack.getCount()!=1||player.getMainHandItem()!=stack)return InteractionResult.PASS;
        var level=player.serverLevel();var clicked=context.getClickedPos();var endpoint=HomeHardware.loadedEndpoint(level,clicked);
        var button=HomeApplianceService.tryButton(player,clicked,context.getHand(),new BlockHitResult(context.getClickLocation(),context.getClickedFace(),clicked,context.isInside()));
        if(button!=InteractionResult.PASS)return button;
        if(endpoint instanceof HomeTvBlockEntity)return message(player,"请到主机或光枪支架归还光枪。");
        HomeConsoleBlockEntity console=endpoint instanceof HomeConsoleBlockEntity c?c:endpoint instanceof HomeTvBlockEntity t?HomeHardware.connectedConsole(level,t.getBlockPos()):null;
        if(console==null||console.tvPos()==null||!(HomeHardware.loadedEndpoint(level,console.tvPos()) instanceof HomeTvBlockEntity tv))return message(player,"请用光枪右键已接好电视的 FC 主机。");
        String rom=console.romSha();var originalClicked=level.getBlockEntity(clicked);
        if(!facts(player,console,tv)||!rom.matches("[0-9a-f]{64}"))return message(player,"请先插入卡带并连接电视，靠近设备后再试。");
        CHECKING.set(true);
        try {
            var hit=new BlockHitResult(context.getClickLocation(),context.getClickedFace(),clicked,context.isInside());
            if(!HomeHardware.allowAnchorInteraction(player,clicked,endpoint,context.getHand(),hit)
                    ||!permission(player,console,hit)||!permission(player,tv,hit))return message(player,"没有使用这台主机或电视的权限。");
            if(!current(player)||player.getMainHandItem()!=stack||stack.getCount()!=1||level.getBlockEntity(clicked)!=originalClicked
                    ||!facts(player,console,tv)||!rom.equals(console.romSha()))return message(player,"设备或卡带状态已改变，请重试。");
            Grant grant=state(player.getServer()).get(player.getUUID());
            if(grant!=null){
                if(grant.issuedTick==player.getServer().getTickCount())return InteractionResult.CONSUME;
                if(grant.binding.consoleId().equals(console.hardwareId())&&authorized(player,grant.binding,true)){
                    returnGun(player,stack);
                    return message(player,"光枪已归还；主机继续运行。");
                }
                return message(player,"请先归还当前绑定的光枪。");
            }
            if(ZapperData.binding(stack)!=null){ZapperData.clear(stack);player.getInventory().setChanged();}
            return ServerArcadeSessions.takeHomeZapper(player,console,stack)?message(player,"光枪已领取或等待主机批准；批准后可射击，并在 P1 空闲时操作游戏按键。")
                    :message(player,"请先连接光枪支架，并按主机电源开启光枪游戏；领取不再开关电源。");
        } finally {CHECKING.remove();}
    }
    public static boolean take(ServerPlayer p,HomeConsoleBlockEntity console,ItemStack stack){
        if(CHECKING.get()||!current(p)||console==null||console.tvPos()==null||!(HomeHardware.loadedEndpoint(p.serverLevel(),console.tvPos()) instanceof HomeTvBlockEntity tv))return false;
        if(!canBind(p,stack,console,tv))return false;
        return ServerArcadeSessions.takeHomeZapper(p,console,stack);
    }
    public static boolean returnGun(ServerPlayer p,ItemStack stack){
        if(p==null||p.getServer()==null)return false;
        ServerArcadeSessions.cancelHomeRequest(p);
        Grant g=state(p.getServer()).get(p.getUUID());var binding=ZapperData.binding(stack);
        if(g==null)return binding==null;
        if(binding==null||!binding.equals(g.binding)||g.connection!=p.connection.getConnection())return false;
        ServerArcadeSessions.releaseHomeZapper(p,g.binding.sessionId(),g.binding.lease());
        return true;
    }
    /** Reentrant protection callbacks cannot create or approve another transaction. */
    public static boolean permissionToControl(ServerPlayer p,HomeConsoleBlockEntity console,HomeTvBlockEntity tv){
        if(CHECKING.get())return facts(p,console,tv);
        if(!facts(p,console,tv))return false;CHECKING.set(true);
        try{var hit=new BlockHitResult(Vec3.atCenterOf(console.getBlockPos()),net.minecraft.core.Direction.UP,console.getBlockPos(),false);
            return permission(p,console,hit)&&permission(p,tv,hit)&&facts(p,console,tv);
        }finally{CHECKING.remove();}
    }
    private static boolean permission(ServerPlayer player,HomeEndpointBlockEntity endpoint,BlockHitResult original){
        var pos=endpoint.getBlockPos();var hit=new BlockHitResult(original.getLocation(),original.getDirection(),pos,original.isInside());
        var e=NeoForge.EVENT_BUS.post(new PlayerInteractEvent.RightClickBlock(player,InteractionHand.MAIN_HAND,pos,hit));
        return !e.isCanceled()&&e.getUseBlock()!=TriState.FALSE&&e.getUseItem()!=TriState.FALSE;
    }
    private static InteractionResult message(ServerPlayer player,String text){player.displayClientMessage(Component.literal(text),true);return InteractionResult.CONSUME;}
    private static boolean current(ServerPlayer p){return p.getServer()!=null&&p.getServer().isSameThread()&&p.isAlive()&&!p.isSpectator()
            &&p.connection.getConnection().isConnected()&&p.getServer().getPlayerList().getPlayer(p.getUUID())==p;}
    public static boolean facts(ServerPlayer p,HomeConsoleBlockEntity c,HomeTvBlockEntity tv){
        var l=p.serverLevel();return current(p)&&c!=null&&tv!=null&&!c.isRemoved()&&!tv.isRemoved()&&c.getLevel()==l&&tv.getLevel()==l
                &&l.hasChunkAt(c.getBlockPos())&&l.hasChunkAt(tv.getBlockPos())&&l.getBlockEntity(c.getBlockPos())==c&&l.getBlockEntity(tv.getBlockPos())==tv
                &&HomeHardware.connectedConsole(l,tv.getBlockPos())==c&&c.hasCartridge()&&l.mayInteract(p,c.getBlockPos())&&l.mayInteract(p,tv.getBlockPos())
                &&Math.min(p.distanceToSqr(Vec3.atCenterOf(c.getBlockPos())),p.distanceToSqr(Vec3.atCenterOf(tv.getBlockPos())))<=64;
    }
    public static boolean canBind(ServerPlayer p,ItemStack stack,HomeConsoleBlockEntity c,HomeTvBlockEntity tv){
        return permissionToControl(p,c,tv)&&p.getMainHandItem()==stack&&stack.getCount()==1&&stack.getItem() instanceof HomeZapperItem&&ZapperStandService.validOrigin(p,stack,c)
                &&!state(p.getServer()).containsKey(p.getUUID())&&state(p.getServer()).size()<128;
    }
    /** Called only during the server's non-reentrant roster transaction. */
    public static boolean bind(ServerPlayer p,ItemStack stack,ZapperBinding b){
        if(!current(p)||!p.serverLevel().hasChunkAt(b.consolePos())||!(p.serverLevel().getBlockEntity(b.consolePos()) instanceof HomeConsoleBlockEntity console)
                ||!console.hardwareId().equals(b.consoleId())||!java.util.Objects.equals(console.linkId(),b.linkId())||p.getMainHandItem()!=stack||stack.getCount()!=1||!(stack.getItem() instanceof HomeZapperItem)||!ZapperStandService.validOrigin(p,stack,console)||state(p.getServer()).containsKey(p.getUUID()))return false;
        ZapperData.bind(stack,b);state(p.getServer()).put(p.getUUID(),new Grant(p,b));p.getInventory().setChanged();p.inventoryMenu.broadcastChanges();return true;
    }
    public static boolean authorized(ServerPlayer p,ZapperBinding b,boolean held){
        if(!current(p)||b==null)return false;Grant g=state(p.getServer()).get(p.getUUID());
        if(g==null||!g.binding.equals(b)||g.connection!=p.connection.getConnection()||!b.dimension().equals(p.level().dimension().location()))return false;
        var l=p.serverLevel();
        if(!l.hasChunkAt(b.consolePos())||!l.hasChunkAt(b.tvPos())||!(l.getBlockEntity(b.consolePos()) instanceof HomeConsoleBlockEntity c)
                ||!(l.getBlockEntity(b.tvPos()) instanceof HomeTvBlockEntity tv)||!b.consoleId().equals(c.hardwareId())||!b.tvId().equals(tv.hardwareId())
                ||!b.linkId().equals(c.linkId())||!facts(p,c,tv))return false;
        var found=HomeControllerInventory.locate(b.lease(),personalItems(p),item->{var value=ZapperData.binding(item);return value==null?null:value.lease();},ItemStack::getCount);
        return found.status()==HomeControllerInventory.Status.UNIQUE&&found.value().getItem() instanceof HomeZapperItem
                &&ZapperData.matches(found.value(),b)&&ZapperStandService.validOrigin(p,found.value(),c)&&(!held||p.getMainHandItem()==found.value());
    }
    /** The existing granted gun, not a stack's self-declared NBT, may assist its owner's P1 keys. */
    public static UUID keyboardLease(ServerPlayer p,long session,int epoch){
        if(!current(p))return null;var grant=state(p.getServer()).get(p.getUUID());
        return grant!=null&&grant.binding.sessionId()==session&&grant.binding.epoch()==epoch&&authorized(p,grant.binding,true)?grant.binding.lease():null;
    }
    private static ArrayList<ItemStack> personalItems(ServerPlayer p){var items=new ArrayList<ItemStack>();for(int i=0;i<p.getInventory().getContainerSize();i++)items.add(p.getInventory().getItem(i));for(var slot:p.inventoryMenu.slots)items.add(slot.getItem());items.add(p.containerMenu.getCarried());return items;}
    public static void observedAim(ServerPlayer p,ZapperBinding b){var g=state(p.getServer()).get(p.getUUID());if(g!=null&&g.binding.equals(b))g.lastAimTick=p.getServer().getTickCount();}
    public static void tick(MinecraftServer server){
        for(var g:List.copyOf(state(server).values())){var p=server.getPlayerList().getPlayer(g.player);
            if(p==null||!authorized(p,g.binding,false)){if(p!=null)ServerArcadeSessions.releaseHomeZapper(p,g.binding.sessionId(),g.binding.lease());else closeSession(server,g.binding.sessionId());continue;}
            if(!authorized(p,g.binding,true)||server.getTickCount()-g.lastAimTick>20)ServerArcadeSessions.pauseHomeZapper(p,g.binding.sessionId());
        }
    }
    public static void updateEpoch(MinecraftServer server,long session,int epoch){for(var g:state(server).values())if(g.binding.sessionId()==session&&g.binding.epoch()!=epoch){
        var p=server.getPlayerList().getPlayer(g.player);if(p==null)continue;var old=g.binding;g.binding=old.withEpoch(epoch);
        for(var item:personalItems(p))if(ZapperData.matches(item,old))ZapperData.bind(item,g.binding);
        p.getInventory().setChanged();p.inventoryMenu.broadcastChanges();FcNetwork.sendZapperSession(p,new ArcadeZapperSessionPayload(g.binding,true));
    }}
    public static void closeSession(MinecraftServer server,long session){for(var g:List.copyOf(state(server).values()))if(g.binding.sessionId()==session){
        state(server).remove(g.player,g);var p=server.getPlayerList().getPlayer(g.player);if(p!=null){for(var item:personalItems(p)){var b=ZapperData.binding(item);if(b!=null&&b.lease().equals(g.binding.lease()))ZapperData.clear(item);}
            p.getInventory().setChanged();p.inventoryMenu.broadcastChanges();FcNetwork.sendZapperSession(p,new ArcadeZapperSessionPayload(g.binding,false));}
    }}
    public static void inventoryTick(ServerPlayer p,ItemStack stack){var b=ZapperData.binding(stack);if(b==null)return;var g=state(p.getServer()).get(p.getUUID());if(g==null||!g.binding.equals(b)){ZapperData.clear(stack);p.getInventory().setChanged();}}
    public static void stopped(MinecraftServer server){STATES.remove(server);}
}
