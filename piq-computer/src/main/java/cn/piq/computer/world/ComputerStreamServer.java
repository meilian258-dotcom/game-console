package cn.piq.computer.world;

import cn.piq.computer.ComputerMod;
import cn.piq.computer.net.ComputerStreamNetwork.*;
import cn.piq.computer.net.ComputerStreamNetwork;
import cn.piq.computer.stream.*;
import java.util.*;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** MC server-thread only. The server never starts a runtime or receives a local content path. */
@EventBusSubscriber(modid=ComputerMod.ID)
public final class ComputerStreamServer {
    private static final Map<ComputerEntity,Room> ROOMS=new IdentityHashMap<>();
    private static final Map<UUID,Long> STARTS=new HashMap<>();
    private static final class Room {
        final ComputerEntity pc;final Key key;final StreamAuthority auth;final int kind,tier;final StreamBudget budget;
        Set<UUID> viewers=new LinkedHashSet<>();long heartbeat=System.nanoTime(),lastStatus;
        Room(ComputerEntity pc,ServerPlayer host,int kind,int tier){this.pc=pc;this.key=new Key(host.serverLevel().dimension().location(),pc.getBlockPos(),pc.hardwareId());this.auth=new StreamAuthority(host.getUUID(),pc.operator);this.kind=kind;this.tier=tier;budget=new StreamBudget(tier,System.nanoTime());}
        Status status(boolean active){return new Status(key,auth.session,auth.host,auth.controller()==null?ComputerStreamNetwork.NONE:auth.controller(),auth.epoch(),auth.allowed(),kind,tier,viewers.size(),active);}
    }
    private static boolean viewable(ServerPlayer p,Room r){return p!=null&&p.level()==r.pc.getLevel()&&p.isAlive()&&!p.hasDisconnected()&&p.distanceToSqr(r.pc.getBlockPos().getCenter())<=1024;}
    private static ServerPlayer host(Room r){return r.pc.getLevel().getServer().getPlayerList().getPlayer(r.auth.host);}
    private static void send(ServerPlayer p,net.minecraft.network.protocol.common.custom.CustomPacketPayload m){if(p!=null&&!p.hasDisconnected())PacketDistributor.sendToPlayer(p,m);}
    public static void action(ServerPlayer p,Action a){
        var key=a.key();if(!p.serverLevel().dimension().location().equals(key.dimension())||!p.serverLevel().hasChunkAt(key.pos()))return;
        var pc=ComputerBlock.find(p.serverLevel(),key.pos());if(pc==null||!pc.getBlockPos().equals(key.pos())||!pc.hardwareId().equals(key.computer()))return;
        Room room=ROOMS.get(pc);
        if(a.operation()==0){
            long now=System.nanoTime(),last=STARTS.getOrDefault(p.getUUID(),0L);if(now-last<2_000_000_000L)return;STARTS.put(p.getUUID(),now);
            if(room!=null){send(p,room.status(true));return;}
            if(a.kind()<1||a.kind()>2||a.tier()<0||a.tier()>2||!p.getUUID().equals(pc.operator)||!pc.currentUser(p)||!ComputerAccess.allowed(p,pc.getBlockPos())||ROOMS.size()>=8||ROOMS.values().stream().anyMatch(r->r.auth.host.equals(p.getUUID())))return;
            room=new Room(pc,p,a.kind(),a.tier());ROOMS.put(pc,room);refresh(room);return;
        }
        if(room==null||!room.auth.host.equals(p.getUUID())||!room.auth.session.equals(a.session()))return;
        if(a.operation()==1){end(pc);return;}
        if(a.operation()==4){room.heartbeat=System.nanoTime();return;}
        if((a.operation()==2||a.operation()==3)&&ComputerAccess.allowed(p,pc.getBlockPos())){
            room.auth.setAllowed(p.getUUID(),a.operation()==2);
            if(a.operation()==3&&pc.operator!=null&&!pc.operator.equals(p.getUUID()))pc.release();
            refresh(room);
        }
    }
    public static boolean mayControl(ComputerEntity pc,ServerPlayer p){var r=ROOMS.get(pc);return r==null||r.auth.mayControl(p.getUUID())&&(r.auth.host.equals(p.getUUID())||r.viewers.contains(p.getUUID()));}
    public static void controller(ComputerEntity pc){var r=ROOMS.get(pc);if(r==null)return;r.auth.controller(pc.operator);send(host(r),new Input(pc.hardwareId(),r.auth.session,r.auth.epoch(),5,0,320,240,0));refresh(r);}
    public static boolean forward(ComputerEntity pc,int kind,int value,int x,int y,int buttons){var r=ROOMS.get(pc);if(r==null)return false;send(host(r),new Input(pc.hardwareId(),r.auth.session,r.auth.epoch(),kind,value,x,y,buttons));return true;}
    public static void media(ServerPlayer p,Media m){
        var part=m.part();Room r=null;for(var candidate:ROOMS.values())if(candidate.pc.hardwareId().equals(part.computer())&&candidate.auth.session.equals(part.session())){r=candidate;break;}
        if(r==null||!viewable(p,r)||!r.auth.host.equals(p.getUUID())||!r.pc.inputReady()||!r.auth.media(p.getUUID(),part)||!r.budget.take(part.bytes().length+96,System.nanoTime()))return;
        for(UUID id:r.viewers){var viewer=p.server.getPlayerList().getPlayer(id);if(viewable(viewer,r))send(viewer,m);}
    }
    private static void refresh(Room r){
        var server=r.pc.getLevel().getServer();var next=new LinkedHashSet<UUID>();
        if(r.pc.operator!=null&&!r.auth.host.equals(r.pc.operator)){var p=server.getPlayerList().getPlayer(r.pc.operator);if(viewable(p,r))next.add(p.getUUID());}
        for(var p:server.getPlayerList().getPlayers())if(next.size()<8&&!r.auth.host.equals(p.getUUID())&&viewable(p,r))next.add(p.getUUID());
        for(var old:r.viewers)if(!next.contains(old))send(server.getPlayerList().getPlayer(old),r.status(false));
        r.viewers=next;var s=r.status(true);send(host(r),s);for(var id:next)send(server.getPlayerList().getPlayer(id),s);r.lastStatus=System.nanoTime();
    }
    public static void end(ComputerEntity pc){var r=ROOMS.remove(pc);if(r==null)return;var s=r.status(false);send(host(r),s);var server=pc.getLevel().getServer();for(var id:r.viewers)send(server.getPlayerList().getPlayer(id),s);pc.release();}
    @SubscribeEvent public static void tick(ServerTickEvent.Post e){
        long now=System.nanoTime();for(var r:new ArrayList<>(ROOMS.values())){
            if(r.pc.getLevel().getServer()!=e.getServer())continue;
            if(r.pc.isRemoved()||!r.pc.inputReady()||!viewable(host(r),r)||now-r.heartbeat>15_000_000_000L){end(r.pc);continue;}
            if(now-r.lastStatus>=1_000_000_000L)refresh(r);
        }
    }
    @SubscribeEvent public static void stopped(ServerStoppedEvent e){ROOMS.entrySet().removeIf(x->x.getKey().getLevel().getServer()==e.getServer());STARTS.clear();}
    @SubscribeEvent public static void logout(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent e){STARTS.remove(e.getEntity().getUUID());}
    private ComputerStreamServer(){}
}
