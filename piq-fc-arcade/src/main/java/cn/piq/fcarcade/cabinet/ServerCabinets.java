package cn.piq.fcarcade.cabinet;

import cn.piq.fcarcade.server.ServerArcadeSessions;
import cn.piq.fcarcade.world.LegacyFcArcadeBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/** Physical cabinet authorization. No emulator core, client class, ROM or save ownership lives here. */
public final class ServerCabinets {
    private static final int MENU_TICKS=600, MAX_MENUS=64, CLICK_COOLDOWN=6;
    private static final double DISTANCE_SQUARED=64;
    private static final Map<MinecraftServer,State> STATES=new WeakHashMap<>();
    private static final ThreadLocal<Boolean> CHECKING=ThreadLocal.withInitial(()->false);
    private ServerCabinets() {}
    public static void register() {
        NeoForge.EVENT_BUS.addListener(ServerCabinets::tick);
        NeoForge.EVENT_BUS.addListener(ServerCabinets::logout);
        NeoForge.EVENT_BUS.addListener(ServerCabinets::stopped);
    }
    private static final class State {
        final Map<UUID,MenuBinding> menus=new HashMap<>();
        final Map<UUID,Long> nextClick=new HashMap<>();
        final CabinetLeaseLedger<CabinetTarget> leases=new CabinetLeaseLedger<>();
        final Map<UUID,Binding> bindings=new HashMap<>();
        final Map<UUID,Long> nextHeartbeat=new HashMap<>();
    }
    record Binding(CabinetTarget target,BlockPos clicked,BlockHitResult hit,
                           LegacyFcArcadeBlockEntity cabinet,BlockEntity clickedEntity) {}
    private record MenuBinding(UUID token,Binding binding,long expires) {}
    private static State state(MinecraftServer server){return STATES.computeIfAbsent(server,s->new State());}
    private static long now(MinecraftServer server){return Integer.toUnsignedLong(server.getTickCount());}
    private static void notice(ServerPlayer player,String text){player.displayClientMessage(Component.literal(text),true);}
    static void pressPower(ServerPlayer player){
        var server=player.getServer();
        if(server==null||!server.isSameThread()||server.getPlayerList().getPlayer(player.getUUID())!=player||CHECKING.get())return;
        var s=state(server);long tick=now(server);
        if(tick<s.nextClick.getOrDefault(player.getUUID(),0L))return;
        var hit=CabinetPowerPicking.pick(player);
        if(hit!=null)interact(player,hit.getBlockPos(),hit);
        else allowClick(s,player,tick);
    }
    /** Link selection uses the exact ordinary physical authorization path, including event revalidation. */
    public static CabinetTarget validatedTarget(ServerPlayer player,BlockPos clicked,BlockHitResult hit){
        return validatedTarget(player,clicked,hit,InteractionHand.MAIN_HAND);
    }
    static CabinetTarget validatedTarget(ServerPlayer player,BlockPos clicked,BlockHitResult hit,InteractionHand hand){
        var server=player.getServer();if(server==null||!server.isSameThread()||CHECKING.get())return null;
        if(clicked==null||hit==null||!clicked.equals(hit.getBlockPos()))return null;
        var target=CabinetTarget.resolve(player.serverLevel(),clicked);if(target==null)return null;
        var cabinet=(LegacyFcArcadeBlockEntity)player.serverLevel().getBlockEntity(target.anchor());
        var binding=new Binding(target,clicked.immutable(),hit,cabinet,player.serverLevel().getBlockEntity(clicked));
        return valid(player,binding,true,hand)?target:null;
    }
    /** Settings-only entry: deliberately creates no menu-choice or emulator launch authority. */
    public static void openDebugSettings(ServerPlayer player,cn.piq.fcarcade.home.DeviceDebugService.Selection selection){
        if(!cn.piq.fcarcade.home.DeviceDebugService.current(player,selection))return;
        var target=validatedTarget(player,selection.hit().getBlockPos(),selection.hit(),selection.hand());
        if(target==null||!cn.piq.fcarcade.home.DeviceDebugService.current(player,selection))return;
        var cabinet=(LegacyFcArcadeBlockEntity)player.serverLevel().getBlockEntity(target.anchor());
        CabinetSyncSettings.openDebug(player,selection,target,cabinet.cabinetBackend());
    }
    public static boolean isCabinetBusy(MinecraftServer server,CabinetTarget target){
        if(server==null||target==null||!server.isSameThread())return true;
        return hasLocalTarget(server,target)||CabinetRooms.hasTarget(server,target)||CabinetHostedSessions.isTargetBusy(server,target)
                ||ServerArcadeSessions.hasCabinetSession(server,net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,target.dimension()),target.anchor());
    }

    /** Called after the ordinary physical click/proxy permission path. False preserves the original NES behavior. */
    public static boolean interact(ServerPlayer player,BlockPos clicked,BlockHitResult hit) {
        var server=player.getServer();
        if(server==null||!server.isSameThread()||CHECKING.get())return true;
        var target=CabinetTarget.resolve(player.serverLevel(),clicked);
        if(target==null)return false;
        var cabinet=(LegacyFcArcadeBlockEntity)player.serverLevel().getBlockEntity(target.anchor());
        boolean menu=player.isShiftKeyDown()&&player.getMainHandItem().isEmpty();
        var selected=cabinet.cabinetBackend();
        var state=state(server);
        if(!menu&&CabinetBackends.NES.equals(selected)&&state.leases.target(target)==null&&!CabinetRooms.hasTarget(server,target))return false;
        if(!allowClick(state,player,now(server)))return true;
        var binding=new Binding(target,clicked.immutable(),hit,cabinet,player.serverLevel().getBlockEntity(clicked));
        if(!valid(player,binding,true))return true;
        if(!menu&&player.getMainHandItem().isEmpty()&&CabinetCoinPolicy.supported(selected.toString())){
            var eye=player.getEyePosition().subtract(target.anchor().getX(),target.anchor().getY(),target.anchor().getZ());
            var end=eye.add(player.getViewVector(1).scale(Math.min(6,player.blockInteractionRange())));
            var facing=cabinet.getBlockState().getValue(cn.piq.fcarcade.world.FcArcadeBlock.FACING);
            boolean compact=cabinet instanceof cn.piq.fcarcade.world.DualCabinetBlockEntity dual&&dual.compactFootprint();
            boolean power=cn.piq.fcarcade.layout.CabinetPowerGeometry.hits(cabinet instanceof cn.piq.fcarcade.world.PortraitCabinetBlockEntity?cn.piq.fcarcade.layout.PortraitCabinetGeometry.powerBoxes():cn.piq.fcarcade.layout.CabinetPowerGeometry.boxes(target.dual(),compact),
                    cn.piq.fcarcade.layout.RocketArcadeGeometry.quarterTurns(facing.getStepX(),facing.getStepZ()),
                    new cn.piq.fcarcade.layout.RocketArcadeGeometry.Point(eye.x,eye.y,eye.z),new cn.piq.fcarcade.layout.RocketArcadeGeometry.Point(end.x,end.y,end.z));
            if(power){
                // Protection callbacks may have changed occlusion without replacing this cabinet.
                var currentPowerHit=CabinetPowerPicking.pick(player);
                if(currentPowerHit==null||!target.equals(CabinetTarget.resolve(player.serverLevel(),currentPowerHit.getBlockPos())))return true;
                boolean wasPowered=cabinet.visualPowered();
                if(!CabinetRooms.powerOff(player,target))launch(player,state,binding,selected);
                // Only the accepted physical press sounds. Denials, NBT updates and the
                // mirrored state on the linked peer must not generate extra clicks.
                boolean powered=cabinet.visualPowered();
                if(wasPowered!=powered)cn.piq.fcarcade.home.HomeInteractionSounds.play(player.serverLevel(),clicked,
                        powered?cn.piq.fcarcade.home.HomeInteractionSounds.Action.POWER_ON:cn.piq.fcarcade.home.HomeInteractionSounds.Action.POWER_OFF);
                return true;
            }
            if(!CabinetRooms.hasTarget(server,target)){notice(player,"空手右键机柜正面左下红色开关键启动；Shift 空手右键设置游戏");return true;}
        }
        var active=state.leases.target(target);
        if(!menu&&active!=null&&active.owner().equals(player.getUUID())){
            close(server,state,active,"已结束街机；再次右键启动，Shift 空手右键配置游戏");return true;
        }
        if(menu){
            if(state.menus.size()>=MAX_MENUS&&!state.menus.containsKey(player.getUUID()))return true;
            CabinetSyncSettings.clearDebug(player); // The ordinary empty-hand entry keeps its original authority.
            var token=UUID.randomUUID();
            state.menus.put(player.getUUID(),new MenuBinding(token,binding,now(server)+MENU_TICKS));
            PacketDistributor.sendToPlayer(player,new CabinetNetwork.Menu(target,token,CabinetBackends.entries(),selected,false,CabinetGameInfo.describe(server,target,selected)));
        }else launch(player,state,binding,selected);
        return true;
    }
    private static boolean allowClick(State state,ServerPlayer player,long now) {
        if(now<state.nextClick.getOrDefault(player.getUUID(),0L))return false;
        if(state.nextClick.size()>=MAX_MENUS&&!state.nextClick.containsKey(player.getUUID()))return false;
        state.nextClick.put(player.getUUID(),now+CLICK_COOLDOWN);return true;
    }
    static void choose(ServerPlayer player,CabinetNetwork.Choose request) {
        var server=player.getServer();if(server==null||!server.isSameThread())return;
        var state=STATES.get(server);if(state==null)return;
        var menu=state.menus.get(player.getUUID());
        if(menu==null||!menu.token().equals(request.token()))return;
        state.menus.remove(player.getUUID()); // One attempt, including invalid/expired choices.
        if(now(server)>=menu.expires()||!player.getMainHandItem().isEmpty()||!valid(player,menu.binding(),true))return;
        var backend=CabinetBackends.find(request.backend());if(backend==null)return;
        var target=menu.binding().target();var cabinet=menu.binding().cabinet();
        if(CabinetRooms.hasTarget(server,target)||CabinetRooms.hasPlayer(player)){
            notice(player,"请先退出联机街机，再配置或切换模拟器。");return;
        }
        if(!backend.id().equals(cabinet.cabinetBackend())&&!player.hasPermissions(2)){
            notice(player,"只有管理员可以更换机柜模拟器；当前模拟器仍可正常使用。");return;
        }
        if(backend.localOnly()&&CabinetBackends.maxPlayers(backend.id())==0&&!localAllowed(server)){
            notice(player,"此模拟器目前仅支持未开放局域网的本机单人世界。");return;
        }
        if(ServerArcadeSessions.hasCabinetSession(server,player.level().dimension(),target.anchor())
                &&!(CabinetBackends.NES.equals(backend.id())&&CabinetBackends.NES.equals(cabinet.cabinetBackend()))){
            notice(player,"请先正常结束这台机柜的 FC 会话，再切换模拟器。");return;
        }
        var existing=state.leases.target(target);
        if(existing!=null&&!existing.owner().equals(player.getUUID())){
            notice(player,"此机柜正由其他玩家使用。");return;
        }
        var own=state.leases.owner(player.getUUID());
        if(own!=null&&!own.target().equals(target)){
            notice(player,"请先退出当前的模拟器。");return;
        }
        if(existing!=null)close(server,state,existing,"已切换模拟器");
        cabinet.setCabinetBackend(backend.id());
        if(CabinetBackends.NES.equals(backend.id())){
            if(cn.piq.fcarcade.access.PlayerContentAccess.canBrowse(player))ServerArcadeSessions.openLibrary(player,target.anchor());
            else ServerArcadeSessions.interact(player,target.anchor());
        }else launch(player,state,menu.binding(),backend.id());
    }
    private static void launch(ServerPlayer player,State state,Binding binding,ResourceLocation backendId) {
        var backend=CabinetBackends.find(backendId);var server=player.getServer();
        if(backend==null){notice(player,"此机柜选择的模拟器附属未安装；管理员可 Shift 空手右键重新选择。");return;}
        if(CabinetBackends.NES.equals(backendId))return;
        if(backend.localOnly()&&CabinetBackends.maxPlayers(backendId)==0&&!localAllowed(server)){
            notice(player,"此模拟器目前仅支持未开放局域网的本机单人世界。");return;
        }
        if(!valid(player,binding,false)||ServerArcadeSessions.hasCabinetSession(server,player.level().dimension(),binding.target().anchor())
                ||ServerArcadeSessions.hasPlayerCabinetSession(player)){
            notice(player,"请先正常结束当前 FC 会话。");return;
        }
        if(CabinetBackends.maxPlayers(backendId)>0){CabinetRooms.interact(player,binding,backendId);return;}
        if(CabinetRooms.hasPlayer(player)||CabinetRooms.hasTarget(server,binding.target())){
            notice(player,"请先退出当前联机街机。");return;
        }
        var old=state.leases.target(binding.target());
        if(old!=null){notice(player,old.owner().equals(player.getUUID())?"请先退出当前模拟器，再重新进入。":"此机柜正由其他玩家使用。");return;}
        var lease=state.leases.acquire(player.getUUID(),binding.target(),backendId.toString(),now(server));
        if(lease==null){notice(player,"请先退出当前模拟器，或稍后重试。");return;}
        state.bindings.put(lease.id(),binding);
        ServerArcadeSessions.removeMachineDisplays(server,player.level().dimension(),binding.target().anchor());
        PacketDistributor.sendToPlayer(player,new CabinetNetwork.Launch(binding.target(),backendId,lease.id()));
    }
    private static boolean localAllowed(MinecraftServer server) {
        return server!=null&&!server.isDedicatedServer()&&!server.isPublished()&&server.getPlayerList().getPlayerCount()==1;
    }

    /** Public addon authorization: a guessed UUID/coordinate is never accepted, and use does not refresh the lease. */
    public static CabinetTarget validateLease(ServerPlayer player,UUID id,ResourceLocation backend) {
        var server=player.getServer();if(server==null||!server.isSameThread()||id==null||backend==null)return null;
        if(CabinetRooms.contains(server,id))return CabinetRooms.validateLease(player,id,backend);
        var state=STATES.get(server);if(state==null)return null;
        var lease=state.leases.get(id);var binding=state.bindings.get(id);var entry=CabinetBackends.find(backend);
        if(lease==null||binding==null||entry==null||!lease.owner().equals(player.getUUID())||!lease.backend().equals(backend.toString())
                ||now(server)>=lease.expires()||!backend.equals(binding.cabinet().cabinetBackend())
                ||(entry.localOnly()&&!localAllowed(server))||!valid(player,binding,false)
                ||ServerArcadeSessions.hasCabinetSession(server,player.level().dimension(),lease.target().anchor())
                ||ServerArcadeSessions.hasPlayerCabinetSession(player))return null;
        return lease.target();
    }
    static void heartbeat(ServerPlayer player,UUID id) {
        var server=player.getServer();if(server==null||!server.isSameThread())return;
        if(CabinetRooms.heartbeat(player,id))return;
        var state=STATES.get(server);if(state==null)return;var lease=state.leases.get(id);
        if(lease==null||!lease.owner().equals(player.getUUID()))return;
        long now=now(server);
        if(now<state.nextHeartbeat.getOrDefault(id,0L))return;
        state.nextHeartbeat.put(id,now+5);
        if(validateLease(player,id,ResourceLocation.parse(lease.backend()))==null){close(server,state,lease,"机柜连接已失效");return;}
        state.leases.heartbeat(player.getUUID(),id,now);
    }
    public static void release(ServerPlayer player,UUID id) {
        var server=player.getServer();if(server==null||!server.isSameThread())return;
        if(CabinetRooms.release(player,id))return;
        var state=STATES.get(server);if(state==null)return;var lease=state.leases.get(id);
        if(lease!=null&&lease.owner().equals(player.getUUID()))close(server,state,lease,"已退出模拟器");
    }

    /** Scoped guard for old NES packets. Other FC blocks, TVs, and existing NES multiplayer retain their paths. */
    public static boolean blocksNes(ServerLevel level,BlockPos pos) {
        var target=CabinetTarget.resolve(level,pos);
        if(target==null)return false;
        var entity=(LegacyFcArcadeBlockEntity)level.getBlockEntity(target.anchor());
        var state=STATES.get(level.getServer());
        return !CabinetBackends.NES.equals(entity.cabinetBackend())||(state!=null&&state.leases.target(target)!=null)||CabinetRooms.hasTarget(level.getServer(),target);
    }
    public static boolean hasExternalLease(ServerPlayer player) {
        return hasLocalLease(player)||CabinetRooms.hasPlayer(player);
    }
    static boolean hasLocalLease(ServerPlayer player){var state=STATES.get(player.getServer());return state!=null&&state.leases.owner(player.getUUID())!=null;}
    static boolean hasLocalTarget(MinecraftServer server,CabinetTarget target){var state=STATES.get(server);return state!=null&&state.leases.target(target)!=null;}
    /** Snapshot only validated live leases; reads never renew authority or load a chunk. */
    public static Map<CabinetTarget,String> occupancy(MinecraftServer server){
        if(server==null||!server.isSameThread())return Map.of();
        var result=new java.util.LinkedHashMap<>(CabinetRooms.occupancy(server));var state=STATES.get(server);
        if(state!=null)for(var lease:state.leases.all()){
            var player=server.getPlayerList().getPlayer(lease.owner());
            if(player!=null&&validateLease(player,lease.id(),ResourceLocation.parse(lease.backend()))!=null)
                result.put(lease.target(),player.getGameProfile().getName());
        }
        return Map.copyOf(result);
    }
    public static void removed(ServerLevel level,BlockPos pos,UUID identity) {
        CabinetRooms.removed(level,pos,identity);
        var state=STATES.get(level.getServer());if(state==null)return;
        for(var lease:state.leases.all())if(lease.target().dimension().equals(level.dimension().location())
                &&lease.target().anchor().equals(pos)&&lease.target().identity().equals(identity))
            close(level.getServer(),state,lease,"机柜已拆除或卸载");
        state.menus.entrySet().removeIf(e->e.getValue().binding().target().dimension().equals(level.dimension().location())
                &&e.getValue().binding().target().anchor().equals(pos)&&e.getValue().binding().target().identity().equals(identity));
    }
    static boolean valid(ServerPlayer player,Binding binding,boolean permissionEvent) {
        return valid(player,binding,permissionEvent,InteractionHand.MAIN_HAND);
    }
    private static boolean valid(ServerPlayer player,Binding binding,boolean permissionEvent,InteractionHand hand) {
        return valid(player,binding,permissionEvent,hand,false);
    }
    /** Only an existing, ready native-arcade host can retain computation beyond controller range. */
    static boolean validLease(ServerPlayer player,Binding binding,boolean permissionEvent,boolean computingHost) {
        return valid(player,binding,permissionEvent,InteractionHand.MAIN_HAND,computingHost);
    }
    static boolean withinControlRange(ServerPlayer player,CabinetTarget target){
        return player.distanceToSqr(target.anchor().getCenter())<=DISTANCE_SQUARED;
    }
    private static boolean valid(ServerPlayer player,Binding binding,boolean permissionEvent,InteractionHand hand,boolean computingHost) {
        var level=player.serverLevel();var target=binding.target();
        if(player.getServer()==null||player.getServer().getPlayerList().getPlayer(player.getUUID())!=player
                ||!player.isAlive()||player.isSpectator()||!target.matches(level)||!level.hasChunkAt(binding.clicked())
                ||level.getBlockEntity(target.anchor())!=binding.cabinet()||level.getBlockEntity(binding.clicked())!=binding.clickedEntity()
                ||!computingHost&&!withinControlRange(player,target)
                ||!level.mayInteract(player,binding.clicked())||!level.mayInteract(player,target.anchor())
                ||!player.mayUseItemAt(binding.clicked(),binding.hit().getDirection(),player.getItemInHand(hand))
                ||!player.mayUseItemAt(target.anchor(),binding.hit().getDirection(),player.getItemInHand(hand)))return false;
        if(!permissionEvent)return true;
        if(CHECKING.get())return false;CHECKING.set(true);
        try{
            if(!eventAllows(player,binding.clicked(),binding.hit(),hand))return false;
            if(!binding.clicked().equals(target.anchor())){
                var hit=new BlockHitResult(binding.hit().getLocation(),binding.hit().getDirection(),target.anchor(),binding.hit().isInside());
                if(!eventAllows(player,target.anchor(),hit,hand))return false;
            }
        }finally{CHECKING.remove();}
        return valid(player,binding,false,hand,computingHost); // Event listeners may replace/unload the cabinet or revoke permission.
    }
    private static boolean eventAllows(ServerPlayer player,BlockPos pos,BlockHitResult hit,InteractionHand hand) {
        var event=NeoForge.EVENT_BUS.post(new PlayerInteractEvent.RightClickBlock(player,hand,pos,hit));
        return !event.isCanceled()&&event.getUseBlock()!=TriState.FALSE&&event.getUseItem()!=TriState.FALSE;
    }
    private static void close(MinecraftServer server,State state,CabinetLeaseLedger.Lease<CabinetTarget> lease,String reason) {
        if(state.leases.release(lease.owner(),lease.id())==null)return;
        state.bindings.remove(lease.id());
        state.nextHeartbeat.remove(lease.id());
        var player=server.getPlayerList().getPlayer(lease.owner());
        if(player!=null)PacketDistributor.sendToPlayer(player,new CabinetNetwork.Closed(lease.id(),reason));
    }
    private static void tick(ServerTickEvent.Post event) {
        CabinetSyncSettings.tick(event.getServer());
        CabinetRooms.tick(event.getServer());
        var server=event.getServer();var state=STATES.get(server);if(state==null)return;long now=now(server);
        state.nextClick.entrySet().removeIf(e->now>=e.getValue());
        state.menus.entrySet().removeIf(e->{var player=server.getPlayerList().getPlayer(e.getKey());
            return now>=e.getValue().expires()||player==null||!valid(player,e.getValue().binding(),false);});
        for(var lease:state.leases.all()){
            var player=server.getPlayerList().getPlayer(lease.owner());var binding=state.bindings.get(lease.id());
            if(player==null||binding==null||now>=lease.expires()||validateLease(player,lease.id(),ResourceLocation.parse(lease.backend()))==null
                    ||(now%20==0&&!valid(player,binding,true)))close(server,state,lease,"机柜连接已结束");
        }
    }
    private static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if(!(event.getEntity() instanceof ServerPlayer player))return;
        CabinetSyncSettings.clearDebug(player);
        CabinetRooms.logout(player);
        var state=STATES.get(player.getServer());if(state==null)return;
        state.menus.remove(player.getUUID());state.nextClick.remove(player.getUUID());
        var lease=state.leases.owner(player.getUUID());if(lease!=null)close(player.getServer(),state,lease,"玩家已离线");
    }
    private static void stopped(ServerStoppedEvent event){CabinetSyncSettings.stopped(event.getServer());CabinetRooms.stopped(event.getServer());STATES.remove(event.getServer());}
}
