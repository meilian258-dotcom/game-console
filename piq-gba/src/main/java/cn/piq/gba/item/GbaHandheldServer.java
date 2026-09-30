package cn.piq.gba.item;

import cn.piq.gba.GbaMod;
import cn.piq.fcarcade.access.PlayerContentAccess;
import cn.piq.fcarcade.home.content.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import java.util.*;

/** Server-thread card transactions and a held-item grant for the shared downloader. */
public final class GbaHandheldServer {
    private static final Map<ServerPlayer,Use> USES=new WeakHashMap<>();
    private static final Map<ServerPlayer,UUID> ACTIVE=new WeakHashMap<>();
    private static final Map<ServerPlayer,UUID> REQUESTS=new WeakHashMap<>();
    public static UUID playToken(ServerPlayer p,GbaHandheldNetwork.Request r){return r.action()==GbaHandheldNetwork.POWER&&r.nonce().equals(REQUESTS.get(p))?ACTIVE.getOrDefault(p,GbaCartridgeSlot.EMPTY_ID):GbaCartridgeSlot.EMPTY_ID;}
    private static final class Use{long time;final ArrayDeque<UUID> seen=new ArrayDeque<>();}
    public static void handle(ServerPlayer p,GbaHandheldNetwork.Request r){
        if(p.hasDisconnected()||!p.isAlive()||p.isSpectator()||p.getServer()==null||!p.getServer().isSameThread())return;
        var machine=p.getItemInHand(r.hand());
        if(!machine.is(GbaMod.HANDHELD.get())||machine.getCount()!=1||!GbaCartridgeSlot.id(machine).equals(r.device()))return;
        var use=USES.computeIfAbsent(p,k->new Use());long now=System.nanoTime();
        if(use.seen.contains(r.nonce())||(!use.seen.isEmpty()&&now-use.time<200_000_000L))return;
        use.time=now;use.seen.addLast(r.nonce());if(use.seen.size()>32)use.seen.removeFirst();
        if(!GbaCartridgeSlot.valid(machine)){say(p,"掌机卡槽数据异常，未改动任何物品");return;}
        var other=r.hand()==InteractionHand.MAIN_HAND?InteractionHand.OFF_HAND:InteractionHand.MAIN_HAND;
        UUID active=ACTIVE.get(p);
        if(r.action()==GbaHandheldNetwork.OFF){if(active!=null)ContentCards.stop(p,active);return;}
        if(active!=null){say(p,"请先 Shift＋右键关机，再插拔卡带");return;}
        if(r.action()==GbaHandheldNetwork.INSERT){
            var incoming=p.getItemInHand(other);if(!incoming.is(GbaMod.CARTRIDGE.get())||incoming.getCount()!=1)return;
            var old=GbaCartridgeSlot.card(machine);GbaCartridgeSlot.set(machine,incoming);
            p.setItemInHand(other,old);p.inventoryMenu.broadcastChanges();say(p,"已插入 GBA 卡带；Shift＋右键开机");return;
        }
        if(r.action()==GbaHandheldNetwork.EJECT){
            var card=GbaCartridgeSlot.card(machine);if(card.isEmpty()){say(p,"掌机未插卡");return;}
            if(p.getItemInHand(other).isEmpty())p.setItemInHand(other,card);
            else if(!p.getInventory().add(card)){say(p,"背包已满，请先腾出副手或一个物品格");return;}
            GbaCartridgeSlot.set(machine,ItemStack.EMPTY);p.inventoryMenu.broadcastChanges();say(p,"已退出卡带");return;
        }
        var entry=ContentCardData.read(GbaCartridgeSlot.card(machine),GbaMod.BACKEND);
        if(entry==null){say(p,"请先用 GBA 卡带右键老式电脑写入游戏，再将卡带与掌机分持两手右键插卡");return;}
        if(!PlayerContentAccess.canBrowse(p)||!PlayerContentAccess.canUseServerRom(p)){say(p,"没有服务器游戏使用权限，请联系管理员");return;}
        GbaCartridgeSlot.identify(machine);p.inventoryMenu.broadcastChanges();
        var snapshot=machine.copy();var connection=p.connection.getConnection();var level=p.serverLevel();int slot=p.getInventory().selected;
        UUID token=ContentCards.play(p,GbaMod.BACKEND,BlockPos.ZERO,entry,()->
                p.connection.getConnection()==connection&&p.serverLevel()==level&&p.getItemInHand(r.hand())==machine
                &&(r.hand()==InteractionHand.OFF_HAND||p.getInventory().selected==slot)&&ItemStack.matches(machine,snapshot)
                &&PlayerContentAccess.canBrowse(p)&&PlayerContentAccess.canUseServerRom(p),started->{if(!started)ACTIVE.remove(p);});
        if(token==null){say(p,"卡带服务繁忙或已有其它设备运行，请稍后重试");return;}
        ACTIVE.put(p,token);REQUESTS.put(p,r.nonce());say(p,"正在读取卡带；右键举起操作，Shift＋右键可取消开机");
    }
    private static void say(ServerPlayer p,String message){p.displayClientMessage(net.minecraft.network.chat.Component.literal(message),true);}
    private GbaHandheldServer(){}
}
