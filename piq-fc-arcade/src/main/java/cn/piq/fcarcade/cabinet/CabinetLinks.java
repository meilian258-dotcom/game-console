package cn.piq.fcarcade.cabinet;

import cn.piq.fcarcade.world.DualCabinetBlock;
import cn.piq.fcarcade.world.LegacyFcArcadeBlockEntity;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Two single/dual cabinets form one bounded 2..4-port room. Topology owns no ROM, skin or core. */
public final class CabinetLinks {
    private static final int PENDING_TICKS=1200,MAX_PENDING=64;
    private static final Map<MinecraftServer,Map<UUID,Pending>> PENDING=new WeakHashMap<>();
    private static final ThreadLocal<Boolean> USING=ThreadLocal.withInitial(()->false);
    private static boolean registered;
    private record Pending(ServerPlayer player,CabinetTarget first,BlockPos clicked,BlockHitResult hit,
                           LegacyFcArcadeBlockEntity cabinet,BlockEntity clickedEntity,net.minecraft.world.item.ItemStack cable,long expires){}
    private CabinetLinks(){}

    public static void register(){
        if(registered)return;registered=true;
        NeoForge.EVENT_BUS.addListener(CabinetLinks::tick);
        NeoForge.EVENT_BUS.addListener(CabinetLinks::login);
        NeoForge.EVENT_BUS.addListener(CabinetLinks::logout);
        NeoForge.EVENT_BUS.addListener(CabinetLinks::stopped);
    }
    private static long now(MinecraftServer server){return Integer.toUnsignedLong(server.getTickCount());}
    private static void notice(ServerPlayer player,String text){player.displayClientMessage(Component.literal(text),true);}
    private static boolean current(ServerPlayer player){var server=player.getServer();return server!=null&&server.isSameThread()
            &&server.getPlayerList().getPlayer(player.getUUID())==player&&player.isAlive()&&!player.isSpectator();}
    private static Data data(MinecraftServer server){return server.overworld().getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(Data::new,Data::load),"piq_cabinet_links");}
    private static CabinetLinkLedger.End end(CabinetTarget target){var p=target.anchor();return new CabinetLinkLedger.End(target.dimension().toString(),p.getX(),p.getY(),p.getZ(),target.identity(),target.dual());}
    private static CabinetTarget target(CabinetLinkLedger.End end){return new CabinetTarget(ResourceLocation.parse(end.dimension()),new BlockPos(end.x(),end.y(),end.z()),end.identity(),end.dual());}
    private static ServerLevel level(MinecraftServer server,CabinetTarget target){return server.getLevel(ResourceKey.create(Registries.DIMENSION,target.dimension()));}
    private static LegacyFcArcadeBlockEntity entity(MinecraftServer server,CabinetTarget target){
        var level=level(server,target);return target.matches(level)?(LegacyFcArcadeBlockEntity)level.getBlockEntity(target.anchor()):null;
    }
    private static boolean validPair(MinecraftServer server,CabinetLinkLedger.Pair pair){
        var a=entity(server,target(pair.primary()));var b=entity(server,target(pair.secondary()));
        return a!=null&&b!=null&&CabinetBackends.maxPlayers(a.cabinetBackend())>=CabinetSeats.linkedCapacity(pair.primary().dual(),pair.secondary().dual())&&a.cabinetBackend().equals(b.cabinetBackend());
    }
    /** Topology survives unloading: a linked secondary must never silently become a standalone P1. */
    public static CabinetTarget master(MinecraftServer server,CabinetTarget supplied){
        if(server==null||supplied==null||!server.isSameThread())return supplied;
        var pair=data(server).ledger.find(end(supplied));return pair!=null?target(pair.primary()):supplied;
    }
    public static boolean hasLink(MinecraftServer server,CabinetTarget supplied){
        return server!=null&&supplied!=null&&server.isSameThread()&&data(server).ledger.find(end(supplied))!=null;
    }
    /** A saved but unloaded/invalid peer is unavailable, not an authorization to downgrade the room. */
    public static CabinetTarget peer(MinecraftServer server,CabinetTarget supplied){
        if(server==null||supplied==null||!server.isSameThread())return null;
        var pair=data(server).ledger.find(end(supplied));return pair!=null&&validPair(server,pair)?target(pair.other(end(supplied))):null;
    }

    /** Cable Item.useOn calls this; a denied recognized interaction is still consumed. */
    public static boolean use(ServerPlayer player,BlockPos clicked,BlockHitResult hit,boolean disconnect){
        if(player==null||clicked==null||hit==null||!current(player)||USING.get())return true;
        USING.set(true);
        try{return useChecked(player,clicked,hit,disconnect);}finally{USING.remove();}
    }
    private static boolean useChecked(ServerPlayer player,BlockPos clicked,BlockHitResult hit,boolean disconnect){
        var held=player.getMainHandItem();
        if(!validCable(player,held))return true;
        var server=player.getServer();var pending=PENDING.computeIfAbsent(server,s->new HashMap<>());
        pending.entrySet().removeIf(e->expired(server,e.getValue()));
        if(!player.hasPermissions(2)){pending.remove(player.getUUID());notice(player,"只有管理员可以连接或拆开街机通讯线。");return true;}
        var resolved=ServerCabinets.validatedTarget(player,clicked,hit);
        if(resolved==null){pending.remove(player.getUUID());notice(player,"机柜不可用，或没有操作权限。");return true;}
        if(!current(player)||!player.hasPermissions(2)){pending.remove(player.getUUID());return true;}
        var clickedBinding=binding(player,resolved,clicked,hit);
        if(clickedBinding==null)return true;
        var saved=data(server);pruneKnownReplacements(server,saved);
        if(disconnect){
            pending.remove(player.getUUID());var pair=saved.ledger.find(end(resolved));
            if(pair==null){notice(player,"这台街机没有连接通讯线。");return true;}
            var other=target(pair.other(end(resolved)));
            var otherHit=new BlockHitResult(hit.getLocation(),hit.getDirection(),other.anchor(),hit.isInside());
            var otherBinding=binding(player,other,other.anchor(),otherHit);
            if(otherBinding==null||!other.equals(ServerCabinets.validatedTarget(player,other.anchor(),otherHit))){
                notice(player,"请靠近两台机柜之间再拆线，并确认两端均可操作。");return true;
            }
            if(!resolved.equals(ServerCabinets.validatedTarget(player,clicked,hit))
                    ||!authorizedBoth(player,clickedBinding,otherBinding))return true;
            if(!validCable(player,held))return true;
            if(busy(server,resolved,other)){notice(player,"请先结束两台街机的游戏，再拆开通讯线。");return true;}
            if(saved.ledger.disconnect(end(resolved),true,true)!=null){saved.setDirty();clearVisuals(server,pair);
                refund(saved,pair,player.serverLevel(),resolved.anchor());
                cn.piq.fcarcade.home.HomeInteractionSounds.play(player.serverLevel(),resolved.anchor(),cn.piq.fcarcade.home.HomeInteractionSounds.Action.CABLE_DISCONNECT);
                notice(player,"街机通讯线已断开；两台外观保持不变。");}
            return true;
        }
        if(saved.ledger.find(end(resolved))!=null){pending.remove(player.getUUID());notice(player,"这台街机已经接线；Shift 右键可拆线。");return true;}
        if(ServerCabinets.isCabinetBusy(server,resolved)){pending.remove(player.getUUID());notice(player,"请先结束这台街机的游戏，再连接通讯线。");return true;}
        var first=pending.remove(player.getUUID());
        if(first==null){
            var cabinet=entity(server,resolved);
            if(cabinet==null||CabinetBackends.maxPlayers(cabinet.cabinetBackend())<CabinetSeats.physical(resolved.dual())+1){notice(player,"主柜模拟器的联机端口不足；请先选择支持本次机柜组合的附属模拟器，旧 FC 路径不支持此通讯线。");return true;}
            if(pending.size()>=MAX_PENDING){notice(player,"通讯线选择繁忙，请稍后重试。");return true;}
            pending.put(player.getUUID(),new Pending(player,resolved,clicked.immutable(),hit,cabinet,
                    player.serverLevel().getBlockEntity(clicked),held,now(server)+PENDING_TICKS));
            notice(player,"已选主街机；60 秒内右键另一台单人或双人街机，按两柜实际席位连接。");return true;
        }
        if(!CabinetLinkLedger.compatible(end(first.first()),end(resolved))){notice(player,"请选择同一维度、16 格内的另一台街机。");return true;}
        if(expired(server,first)||first.player()!=player||!sameFirstIdentity(player,first)
                ||!first.first().equals(ServerCabinets.validatedTarget(player,first.clicked(),first.hit()))){
            notice(player,"首台机柜已失效；请靠近两台之间并重新选取，两端都需在 8 格内。");return true;
        }
        // A permission listener may start a session, replace a block, reconnect a player or revoke OP.
        var primaryBinding=new ServerCabinets.Binding(first.first(),first.clicked(),first.hit(),first.cabinet(),first.clickedEntity());
        var secondary=clickedBinding.cabinet();var primary=first.cabinet();
        if(!current(player)||!player.hasPermissions(2)||!sameFirstIdentity(player,first)||primary!=first.cabinet()
                ||secondary==null||!resolved.equals(ServerCabinets.validatedTarget(player,clicked,hit))
                ||!authorizedBoth(player,primaryBinding,clickedBinding)){notice(player,"机柜状态在权限检查期间改变，请重试。");return true;}
        if(busy(server,first.first(),resolved)){notice(player,"请先结束两台街机的游戏，再连接通讯线。");return true;}
        int ports=CabinetBackends.maxPlayers(primary.cabinetBackend());
        if(first.cable()!=held||!validCable(player,held))return true;
        var pair=saved.ledger.connect(end(first.first()),end(resolved),true,true,ports);
        if(pair==null){notice(player,"无法连接：模拟器不足两柜实际席位数、机柜已有连接，或已达到 128 对上限。");return true;}
        // Setter preserves independent skins/ROM save data and emits the ordinary BE update.
        var oldBackend=secondary.cabinetBackend();
        try{secondary.setCabinetBackend(primary.cabinetBackend());saved.setDirty();syncVisuals(server,pair);
            if(!validCable(player,held)||saved.ledger.find(end(resolved))!=pair
                    ||!authorizedBoth(player,primaryBinding,clickedBinding))throw new IllegalStateException("Cable changed");
            if(!saved.paid.record(pair.id()))throw new IllegalStateException("Cable receipt unavailable");
        }
        catch(RuntimeException failure){saved.ledger.remove(end(first.first()));saved.setDirty();
            clearVisuals(server,pair);
            if(entity(server,resolved)==secondary&&secondary.cabinetBackend().equals(primary.cabinetBackend())){
                try{secondary.setCabinetBackend(oldBackend);}catch(RuntimeException ignored){/* No room remains authorized. */}
            }
            notice(player,"连接未完成，通讯线已取消；请检查机柜状态后重试。");return true;}
        held.shrink(1);saved.setDirty();player.getInventory().setChanged();player.inventoryMenu.broadcastChanges();
        int firstSecondary=CabinetSeats.physical(first.first().dual())+1;
        cn.piq.fcarcade.home.HomeInteractionSounds.play(player.serverLevel(),resolved.anchor(),cn.piq.fcarcade.home.HomeInteractionSounds.Action.CABLE_CONNECT);
        notice(player,"已连接 "+CabinetSeats.linkedCapacity(first.first().dual(),resolved.dual())+" 席：副柜从 P"+firstSecondary+" 开始；两台机柜外观各自保留。");return true;
    }
    private static boolean busy(MinecraftServer server,CabinetTarget first,CabinetTarget second){
        return ServerCabinets.isCabinetBusy(server,first)||ServerCabinets.isCabinetBusy(server,second);
    }
    private static boolean validCable(ServerPlayer player,net.minecraft.world.item.ItemStack held) {
        return current(player)&&player.getMainHandItem()==held&&held.getCount()==1
                &&held.getItem() instanceof cn.piq.fcarcade.home.ZapperStandCableItem;
    }
    private static void refund(Data saved,CabinetLinkLedger.Pair pair,ServerLevel level,BlockPos pos) {
        if(saved.paid.claim(pair.id())) {saved.setDirty();net.minecraft.world.level.block.Block.popResource(level,pos,
            new net.minecraft.world.item.ItemStack(cn.piq.fcarcade.registry.ModItems.ZAPPER_STAND_CABLE.get()));}
    }
    private static ServerCabinets.Binding binding(ServerPlayer player,CabinetTarget target,BlockPos clicked,BlockHitResult hit){
        var cabinet=entity(player.getServer(),target);var level=player.serverLevel();
        if(cabinet==null||!level.hasChunkAt(clicked))return null;
        return new ServerCabinets.Binding(target,clicked.immutable(),hit,cabinet,level.getBlockEntity(clicked));
    }
    /** Both original object bindings survive all protection callbacks; this does not repost events. */
    private static boolean authorizedBoth(ServerPlayer player,ServerCabinets.Binding first,ServerCabinets.Binding second){
        return current(player)&&player.hasPermissions(2)&&ServerCabinets.valid(player,first,false)
                &&ServerCabinets.valid(player,second,false)&&current(player)&&player.hasPermissions(2);
    }
    private static boolean sameFirstIdentity(ServerPlayer player,Pending first){
        var level=player.serverLevel();return first.first().dimension().equals(level.dimension().location())
                &&first.first().matches(level)&&level.hasChunkAt(first.clicked())
                &&level.getBlockEntity(first.first().anchor())==first.cabinet()&&level.getBlockEntity(first.clicked())==first.clickedEntity();
    }
    private static boolean expired(MinecraftServer server,Pending value){return now(server)>=value.expires()
            ||!validCable(value.player(),value.cable())
            ||server.getPlayerList().getPlayer(value.player().getUUID())!=value.player()
            ||!value.first().dimension().equals(value.player().serverLevel().dimension().location());}
    /** True block-removal hook only. Never call for chunk unload: that is not a physical cable deletion. */
    public static void removed(ServerLevel level,BlockPos pos,UUID identity){
        if(level==null||pos==null||identity==null||!level.getServer().isSameThread())return;
        var saved=data(level.getServer());
        for(boolean dual:new boolean[]{false,true}){var exact=new CabinetLinkLedger.End(level.dimension().location().toString(),pos.getX(),pos.getY(),pos.getZ(),identity,dual);
            var pair=saved.ledger.remove(exact);if(pair!=null){saved.setDirty();clearVisuals(level.getServer(),pair);refund(saved,pair,level,pos);}}
        var pending=PENDING.get(level.getServer());if(pending!=null)pending.entrySet().removeIf(e->{var first=e.getValue().first();return first.identity().equals(identity)&&first.anchor().equals(pos)&&first.dimension().equals(level.dimension().location());});
    }
    private static void pruneKnownReplacements(MinecraftServer server,Data saved){
        for(var pair:saved.ledger.snapshot())for(var endpoint:List.of(pair.primary(),pair.secondary())){
            var target=target(endpoint);var level=level(server,target);
            if(level!=null&&level.hasChunkAt(target.anchor())
                    &&(!(level.getBlockEntity(target.anchor()) instanceof LegacyFcArcadeBlockEntity entity)
                    ||!entity.cabinetId().equals(target.identity())
                    ||target.dual()!=(level.getBlockState(target.anchor()).getBlock() instanceof DualCabinetBlock)
                    ||!target.dual()&&!(level.getBlockState(target.anchor()).getBlock() instanceof cn.piq.fcarcade.world.LegacyFcArcadeBlock))){saved.ledger.remove(endpoint);saved.setDirty();clearVisuals(server,pair);refund(saved,pair,level,target.anchor());break;}
        }
    }
    /** Rebuild display-only metadata after a chunk load; persistent topology is not deleted on unload. */
    public static void loaded(ServerLevel level,BlockPos pos,UUID identity){
        if(level==null||!level.getServer().isSameThread())return;
        var target=CabinetTarget.resolve(level,pos);if(target==null||!target.identity().equals(identity))return;
        var pair=data(level.getServer()).ledger.find(end(target));
        if(pair!=null)syncVisuals(level.getServer(),pair);else ((LegacyFcArcadeBlockEntity)level.getBlockEntity(pos)).setVisualLink(null,null);
    }
    private static void syncVisuals(MinecraftServer server,CabinetLinkLedger.Pair pair){
        boolean valid=validPair(server,pair);
        for(var endpoint:List.of(pair.primary(),pair.secondary())){var block=entity(server,target(endpoint));if(block!=null)block.setVisualLink(valid?pair.id():null,valid?target(pair.other(endpoint)):null);}
    }
    private static void clearVisuals(MinecraftServer server,CabinetLinkLedger.Pair pair){
        for(var endpoint:List.of(pair.primary(),pair.secondary())){var block=entity(server,target(endpoint));if(block!=null)block.setVisualLink(null,null);}
    }
    private static void tick(ServerTickEvent.Post event){var map=PENDING.get(event.getServer());if(map!=null)map.entrySet().removeIf(e->expired(event.getServer(),e.getValue()));
        if(event.getServer().getTickCount()%20==0){var saved=data(event.getServer());pruneKnownReplacements(event.getServer(),saved);for(var pair:saved.ledger.snapshot())syncVisuals(event.getServer(),pair);}}
    private static void forget(ServerPlayer player){var map=PENDING.get(player.getServer());if(map!=null)map.remove(player.getUUID());}
    /** Shared cable switching to another device family cancels only this player's unfinished selection. */
    public static void cancelSelection(ServerPlayer player){if(player!=null&&player.getServer()!=null&&player.getServer().isSameThread())forget(player);}
    private static void login(PlayerEvent.PlayerLoggedInEvent event){if(event.getEntity() instanceof ServerPlayer p)forget(p);}
    private static void logout(PlayerEvent.PlayerLoggedOutEvent event){if(event.getEntity() instanceof ServerPlayer p)forget(p);}
    private static void stopped(ServerStoppedEvent event){PENDING.remove(event.getServer());}

    static final class Data extends SavedData{
        final CabinetLinkLedger ledger=new CabinetLinkLedger();
        final cn.piq.fcarcade.home.DataCablePayments paid=new cn.piq.fcarcade.home.DataCablePayments();
        static Data load(CompoundTag tag,HolderLookup.Provider registries){
            Data data=new Data();ListTag pairs=tag.getList("Pairs",Tag.TAG_COMPOUND);
            for(int i=0;i<Math.min(pairs.size(),512);i++)try{
                CompoundTag row=pairs.getCompound(i);
                if(!row.hasUUID("Id")||!row.contains("Primary",Tag.TAG_COMPOUND)||!row.contains("Secondary",Tag.TAG_COMPOUND))continue;
                var pair=new CabinetLinkLedger.Pair(row.getUUID("Id"),readEnd(row.getCompound("Primary")),readEnd(row.getCompound("Secondary")));
                if(data.ledger.restore(pair)&&row.getBoolean("PaidCable"))data.paid.record(pair.id());
            }catch(IllegalArgumentException ignored){/* Invalid persisted identity cannot authorize a connection. */}
            return data;
        }
        @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider registries){
            ListTag pairs=new ListTag();for(var pair:ledger.snapshot()){
                CompoundTag row=new CompoundTag();row.putUUID("Id",pair.id());row.put("Primary",writeEnd(pair.primary()));row.put("Secondary",writeEnd(pair.secondary()));row.putBoolean("PaidCable",paid.paid(pair.id()));pairs.add(row);
            }tag.put("Pairs",pairs);return tag;
        }
        private static CompoundTag writeEnd(CabinetLinkLedger.End end){CompoundTag t=new CompoundTag();t.putString("Dimension",end.dimension());t.putUUID("Id",end.identity());t.putInt("X",end.x());t.putInt("Y",end.y());t.putInt("Z",end.z());t.putBoolean("Dual",end.dual());return t;}
        private static CabinetLinkLedger.End readEnd(CompoundTag t){
            if(!t.contains("X",Tag.TAG_INT)||!t.contains("Y",Tag.TAG_INT)||!t.contains("Z",Tag.TAG_INT)||!t.contains("Dual",Tag.TAG_BYTE)||!t.hasUUID("Id"))throw new IllegalArgumentException("Missing cabinet endpoint");
            return new CabinetLinkLedger.End(t.getString("Dimension"),t.getInt("X"),t.getInt("Y"),t.getInt("Z"),t.getUUID("Id"),t.getBoolean("Dual"));
        }
    }
}
