package cn.piq.fcarcade.cabinet;

import java.util.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.network.PacketDistributor;
import cn.piq.fcarcade.home.DeviceDebugService;
import cn.piq.fcarcade.home.DeviceDebugPolicy;
import cn.piq.fcarcade.world.LegacyFcArcadeBlockEntity;
import net.minecraft.world.InteractionHand;

/** Server-world preferences. The running room has an immutable copy, never follows live config. */
public final class CabinetSyncSettings extends SavedData {
    private final Map<String,CabinetSyncMode> modes=new HashMap<>();
    private final Set<String> netplayModes=new HashSet<>();
    private final Map<String,Integer> saveModes=new HashMap<>();
    static int saveMode(MinecraftServer s,CabinetTarget t,ResourceLocation b){return data(s).saveModes.getOrDefault(key(t,b),0);}
    static boolean netplay(MinecraftServer s,CabinetTarget t,ResourceLocation b){return data(s).netplayModes.contains(key(t,b))&&get(s,t,b)==CabinetSyncMode.LOCAL_SYNC;}
    private static final Map<ServerPlayer,DebugIntent> DEBUG=new WeakHashMap<>();
    private static final ThreadLocal<Boolean> CHECKING=ThreadLocal.withInitial(()->false);
    private static final ThreadLocal<Boolean> COMMAND=ThreadLocal.withInitial(()->false);
    private static final class DebugIntent {
        final UUID token=UUID.randomUUID();
        final DeviceDebugService.Selection tool;final CabinetTarget target,primary,secondary;final ResourceLocation backend;
        final LegacyFcArcadeBlockEntity clicked,master,peer;final long expires;
        long lastRequest=Long.MIN_VALUE;boolean invalid;CabinetSyncNetwork.Setting lastSetting;
        DebugIntent(ServerPlayer p,DeviceDebugService.Selection tool,CabinetTarget target,ResourceLocation backend){
            this.tool=tool;this.target=target;this.backend=backend;var server=p.getServer();
            primary=CabinetLinks.master(server,target);secondary=CabinetLinks.peer(server,primary);
            clicked=(LegacyFcArcadeBlockEntity)p.serverLevel().getBlockEntity(target.anchor());
            master=primary.matches(p.serverLevel())?(LegacyFcArcadeBlockEntity)p.serverLevel().getBlockEntity(primary.anchor()):null;
            peer=secondary!=null&&secondary.matches(p.serverLevel())?(LegacyFcArcadeBlockEntity)p.serverLevel().getBlockEntity(secondary.anchor()):null;
            expires=Integer.toUnsignedLong(server.getTickCount())+1200L;
        }
    }
    static void clearDebug(ServerPlayer player){DEBUG.remove(player);}
    static void stopped(MinecraftServer server){DEBUG.entrySet().removeIf(e->e.getKey().getServer()==server);}
    static void tick(MinecraftServer server){
        long now=Integer.toUnsignedLong(server.getTickCount());if(now%100!=0)return;
        DEBUG.entrySet().removeIf(e->e.getKey().getServer()==server&&(e.getKey().hasDisconnected()||now>e.getValue().expires));
    }
    static void openDebug(ServerPlayer p,DeviceDebugService.Selection tool,CabinetTarget target,ResourceLocation backend){
        if(CHECKING.get()||!DeviceDebugService.current(p,tool))return;
        DEBUG.entrySet().removeIf(e->e.getKey().hasDisconnected());
        if(DEBUG.size()>=256&&!DEBUG.containsKey(p))return;
        var intent=new DebugIntent(p,tool,target,backend);
        if(!debugValid(p,intent))return;
        DEBUG.put(p,intent);
        // This token authorizes settings only: no Choose or launch capability is registered.
        PacketDistributor.sendToPlayer(p,new CabinetNetwork.Menu(target,intent.token,CabinetBackends.entries(),backend,true,CabinetGameInfo.describe(p.getServer(),target,backend)));
    }
    private static boolean debugValid(ServerPlayer p,DebugIntent i){
        if(i.invalid||p.getServer()==null||Integer.toUnsignedLong(p.getServer().getTickCount())>i.expires
                ||!DeviceDebugService.current(p,i.tool))return false;
        var level=p.serverLevel();var server=p.getServer();
        if(!i.target.matches(level)||!i.primary.matches(level)||level.getBlockEntity(i.target.anchor())!=i.clicked
                ||level.getBlockEntity(i.primary.anchor())!=i.master||i.master==null||!i.backend.equals(i.master.cabinetBackend())
                ||!i.backend.equals(i.clicked.cabinetBackend())||!i.primary.equals(CabinetLinks.master(server,i.target))
                ||!Objects.equals(i.secondary,CabinetLinks.peer(server,i.primary))
                ||CabinetLinks.hasLink(server,i.primary)!=(i.secondary!=null)
                ||!level.getWorldBorder().isWithinBounds(i.target.anchor())||!level.getWorldBorder().isWithinBounds(i.primary.anchor()))return false;
        return i.secondary==null||i.secondary.matches(level)&&level.getBlockEntity(i.secondary.anchor())==i.peer
                &&i.peer!=null&&i.backend.equals(i.peer.cabinetBackend())&&level.getWorldBorder().isWithinBounds(i.secondary.anchor());
    }
    private static void debugRejected(ServerPlayer p,DebugIntent i,String reason){
        var old=i.lastSetting;if(old==null){p.displayClientMessage(net.minecraft.network.chat.Component.literal(reason),true);return;}
        PacketDistributor.sendToPlayer(p,new CabinetSyncNetwork.Setting(old.target(),old.backend(),old.mode(),old.supported(),!i.invalid&&old.editable(),reason,
                old.hosted(),old.playerMedia(),old.hostedReason(),i.token,old.coinRequired(),!i.invalid&&old.coinEditable(),old.gameInfo(),old.autoPowerOff(),old.idleSeconds(),old.renderDistance(),old.saveMode()));
    }
    private static CabinetSyncSettings data(MinecraftServer s){return s.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(CabinetSyncSettings::new,CabinetSyncSettings::load),"piq_cabinet_sync_modes");}
    private static String key(CabinetTarget t,ResourceLocation b){return t.dimension()+"|"+t.identity()+"|"+b;}
    static CabinetSyncMode get(MinecraftServer s,CabinetTarget t,ResourceLocation b){
        if(b.equals(CabinetBackends.NES))return CabinetSyncMode.LOCAL_SYNC; // Original physical FC cabinet has its own input-sync sessions.
        var saved=data(s).modes.get(key(t,b));if(saved!=null)return saved;
        var level=s.getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,t.dimension()));
        if(level!=null&&t.matches(level)&&level.getBlockEntity(t.anchor()) instanceof LegacyFcArcadeBlockEntity placed) {
            int desired=placed.placedDefaultMode();
            var secondary=CabinetLinks.peer(s,t);
            boolean local=CabinetHostingConfig.localAllowed()&&CabinetBackends.supportsSync(b)
                    &&CabinetSeats.capacity(CabinetBackends.syncMaxPlayers(b),secondary!=null,t.dual(),secondary!=null&&secondary.dual())>0;
            if(desired>=0) {
                boolean hosted=CabinetHostedSessions.unavailable(s,t,b)==null;
                int fallback=hosted?2:b.toString().equals("piq_sfc_home:sfc")&&local?1:0;
                int supported=(CabinetHostingConfig.playerAllowed()?1:0)|(local?2:0)|(hosted?4:0);
                var selected=CabinetSyncMode.checked(cn.piq.fcarcade.config.GameConsoleAdminPolicy.selectNewMode(desired,supported,fallback));
                // Resolve once per backend. Later policy/runtime changes must not silently activate a formerly unavailable default.
                var store=data(s);String identity=key(t,b);
                if(store.modes.size()<4096||store.modes.containsKey(identity)){store.modes.put(identity,selected);store.setDirty();return selected;}
            }
        }
        // Never rewrite explicit preferences. Unconfigured public cabinets prefer server hosting
        // only after the administrator opted in and that backend is available on this server.
        if(CabinetHostedSessions.unavailable(s,t,b)==null)return CabinetSyncMode.SERVER_MEDIA;
        if(b.toString().equals("piq_sfc_home:sfc")&&CabinetHostingConfig.localAllowed()&&CabinetBackends.supportsSync(b))return CabinetSyncMode.LOCAL_SYNC;
        return CabinetSyncMode.MEDIA;
    }
    static void request(ServerPlayer p,CabinetSyncNetwork.Mode packet){
        if(CHECKING.get())return;
        var debug=packet.debugToken()==null?null:DEBUG.get(p);
        if(packet.debugToken()!=null){
            // Tool provenance is in the packet: expiry or a new ordinary menu never falls through to legacy authority.
            if(debug==null||!DeviceDebugPolicy.matchesToken(packet.debugToken(),debug.token)||!debug.target.equals(packet.target())||!debug.backend.equals(packet.backend()))return;
            if(p.getServer()==null||!p.getServer().isSameThread()||p.connection.getConnection()!=debug.tool.connection()||!debug.tool.connection().isConnected())return;
            long now=Integer.toUnsignedLong(p.getServer().getTickCount());
            if(debug.lastRequest!=Long.MIN_VALUE&&now-debug.lastRequest<DeviceDebugPolicy.REQUEST_TICKS){debugRejected(p,debug,"操作过快，设置未修改；请稍后重试。");return;}
            debug.lastRequest=now;
        }
        CHECKING.set(true);
        try { DeviceDebugService.guard(()->{requestChecked(p,packet,debug);return null;}); }
        catch(RuntimeException|LinkageError rejected){if(debug!=null){debug.invalid=true;debugRejected(p,debug,"调试授权失效，请重新用螺丝刀打开。");}
            com.mojang.logging.LogUtils.getLogger().warn("Cabinet sync settings rejected",rejected);}
        finally{CHECKING.remove();}
    }
    /** Commands are conveniences, never a bypass around the ordinary physical settings gate. */
    public static boolean adminCommand(ServerPlayer p,BlockHitResult hit,int mode,Integer range) {
        if(p==null||hit==null||!p.hasPermissions(2)||p.getServer()==null||!p.getServer().isSameThread()||CHECKING.get()
                ||mode < -1||mode > 3||range!=null&&!cn.piq.fcarcade.config.GameConsoleAdminPolicy.validRange(range))return false;
        CabinetTarget target;
        CHECKING.set(true);
        try { target=ServerCabinets.validatedTarget(p,hit.getBlockPos(),hit); }
        finally { CHECKING.remove(); }
        if(target==null||!(p.serverLevel().getBlockEntity(target.anchor()) instanceof LegacyFcArcadeBlockEntity block))return false;
        if(range==null) {
            COMMAND.set(true);
            try { request(p,new CabinetSyncNetwork.Mode(target,block.cabinetBackend(),mode)); }
            finally { COMMAND.remove(); }
            return true;
        }
        p.displayClientMessage(net.minecraft.network.chat.Component.literal("街机范围已改为全服统一设置，请使用管理终端 → 街机全服。"),false);return true;
    }
    private static void requestChecked(ServerPlayer p,CabinetSyncNetwork.Mode packet,DebugIntent debug){
        if(debug!=null&&!debugValid(p,debug)){debug.invalid=true;debugRejected(p,debug,"工具、距离、机柜或数据线已变化，请重新打开。");return;}
        var server=p.getServer();var target=packet.target();var connection=p.connection.getConnection();
        if(server==null||!target.matches(p.serverLevel()))return;
        var hit=debug==null?new BlockHitResult(Vec3.atCenterOf(target.anchor()),Direction.UP,target.anchor(),false):debug.tool.hit();
        var hand=debug==null?InteractionHand.MAIN_HAND:debug.tool.hand();
        if(!target.equals(ServerCabinets.validatedTarget(p,hit.getBlockPos(),hit,hand)))return;
        if(debug!=null&&!debugValid(p,debug)){debug.invalid=true;return;}
        var primary=CabinetLinks.master(server,target);var secondary=CabinetLinks.peer(server,primary);
        if(!primary.matches(p.serverLevel())||!(p.serverLevel().getBlockEntity(primary.anchor()) instanceof cn.piq.fcarcade.world.LegacyFcArcadeBlockEntity block)||!block.cabinetBackend().equals(packet.backend()))return;
        boolean registered=CabinetBackends.supportsSync(packet.backend());
        int syncPlayers=CabinetBackends.syncMaxPlayers(packet.backend());
        boolean supported=CabinetHostingConfig.localAllowed()&&registered&&CabinetSeats.capacity(syncPlayers,secondary!=null,primary.dual(),secondary!=null&&secondary.dual())>0;
        boolean netplaySupported=CabinetHostingConfig.localAllowed()&&CabinetSeats.capacity(CabinetNetplay.maxPlayers(packet.backend()),secondary!=null,primary.dual(),secondary!=null&&secondary.dual())>0;
        String hostedReason=CabinetHostedSessions.unavailable(server,primary,packet.backend());
        boolean hosted=hostedReason==null;
        boolean editable=p.hasPermissions(2)&&!ServerCabinets.isCabinetBusy(server,primary)&&(secondary==null||!ServerCabinets.isCabinetBusy(server,secondary));
        String unavailable=!CabinetHostingConfig.localAllowed()?"服主已禁用本地输入同步":registered?"此核心本地同步最多支持 "+syncPlayers+" 席，当前通讯线席位超限；请先断开通讯线或使用音画串流。":packet.backend().toString().equals("piq_native_arcade:mame")?"当前附属未注册匹配的本地同步核心，请更新配套模组或使用音画串流。":"该核心尚未通过本地确定性同步验证，请使用音画串流";
        String reason=supported?(CabinetBackends.hostSnapshotSync(packet.backend())?"本地输入同步仅限已核验的游戏版本；最多 "+syncPlayers+" 席，下次启动生效":"同步方式只对下次启动生效"):unavailable;
        if(!editable)reason=p.hasPermissions(2)?"整组街机仍在运行或退出收尾中；请等待释放后刷新，不要强制切换":"只有管理员可以更改同步方式";
        if(packet.mode()>=4){
            if(!editable||!netplaySupported)reason="存档策略仅允许管理员在整组街机空闲时设置";
            else{
                for(var end:secondary==null?List.of(primary):List.of(primary,secondary))if(!end.equals(ServerCabinets.validatedTarget(p,end.anchor(),new BlockHitResult(Vec3.atCenterOf(end.anchor()),Direction.UP,end.anchor(),false),hand)))return;
                if(debug!=null&&!debugValid(p,debug)||!p.hasPermissions(2)||p.hasDisconnected()||server.getPlayerList().getPlayer(p.getUUID())!=p||p.connection.getConnection()!=connection||!connection.isConnected()||!primary.matches(p.serverLevel())||p.serverLevel().getBlockEntity(primary.anchor())!=block||!primary.equals(CabinetLinks.master(server,target))||!Objects.equals(secondary,CabinetLinks.peer(server,primary))||ServerCabinets.isCabinetBusy(server,primary)||secondary!=null&&ServerCabinets.isCabinetBusy(server,secondary))return;
                var store=data(server);String k=key(primary,packet.backend());
                if(store.modes.size()>=4096&&!store.modes.containsKey(k))reason="配置数量已达上限";
                else{store.modes.putIfAbsent(k,get(server,primary,packet.backend()));store.saveModes.put(k,packet.mode()-4);store.setDirty();reason="Netplay 存档策略已保存，下次开机生效；个人档属于开机玩家，机器档属于主柜，旧档不迁移。";}
            }
        }else if(packet.mode()>=0){
            if(packet.backend().equals(CabinetBackends.NES))reason="FC 街机仍使用原有本地输入同步；家用游戏机可另选服务端托管";
            else if(!editable)reason="请先结束两端游戏；只有管理员可更改同步方式";
            else if(packet.mode()==1&&!supported)reason=unavailable;
            else if(packet.mode()==3&&!netplaySupported)reason="Netplay 实验需允许本地同步及配套附属，席位数不能超过附属声明上限";
            else if(packet.mode()==2&&!hosted)reason=hostedReason;
            else if(packet.mode()==0&&!CabinetHostingConfig.playerAllowed())reason="服主已禁用玩家托管";
            else {
                for(var end:secondary==null?List.of(primary):List.of(primary,secondary)){
                    var h=new BlockHitResult(Vec3.atCenterOf(end.anchor()),Direction.UP,end.anchor(),false);
                    if(!end.equals(ServerCabinets.validatedTarget(p,end.anchor(),h,hand)))return;
                }
                if(!p.hasPermissions(2)||p.hasDisconnected()||p.connection.getConnection()!=connection||!connection.isConnected()||server.getPlayerList().getPlayer(p.getUUID())!=p
                        ||!primary.matches(p.serverLevel())||!block.cabinetBackend().equals(packet.backend())||!primary.equals(CabinetLinks.master(server,target))||!Objects.equals(secondary,CabinetLinks.peer(server,primary))
                        ||ServerCabinets.isCabinetBusy(server,primary)||secondary!=null&&(!secondary.matches(p.serverLevel())||ServerCabinets.isCabinetBusy(server,secondary)))return;
                if(debug!=null&&!debugValid(p,debug)){debug.invalid=true;return;}
                if(!p.hasPermissions(2)||!primary.matches(p.serverLevel())||p.serverLevel().getBlockEntity(primary.anchor())!=block
                        ||!block.cabinetBackend().equals(packet.backend())||!primary.equals(CabinetLinks.master(server,target))
                        ||!Objects.equals(secondary,CabinetLinks.peer(server,primary))||ServerCabinets.isCabinetBusy(server,primary)
                        ||secondary!=null&&ServerCabinets.isCabinetBusy(server,secondary))return;
                var store=data(server);String key=key(primary,packet.backend());if(store.modes.size()>=4096&&!store.modes.containsKey(key))reason="同步配置数量已达上限";
                else{store.modes.put(key,CabinetSyncMode.checked(packet.mode()==3?1:packet.mode()));if(packet.mode()==3)store.netplayModes.add(key);else store.netplayModes.remove(key);store.setDirty();reason=packet.mode()==3?"Netplay 实验已选：最多 "+CabinetNetplay.maxPlayers(packet.backend())+" 席；实体投币仍有效，保存按本机策略执行":"已保存；下次启动生效，不同托管位置的存档不会自动互转";}
            }
        }
        boolean coinSupported=CabinetCoinPolicy.supported(packet.backend().toString());
        boolean coinEditable=coinSupported&&debug!=null&&editable;
        if(packet.coinMode()>=0){
            if(!coinEditable)reason="投币设置仅允许创造模式管理员持调试螺丝刀，在整组街机空闲时修改";
            else{
                for(var end:secondary==null?List.of(primary):List.of(primary,secondary)){
                    var h=new BlockHitResult(Vec3.atCenterOf(end.anchor()),Direction.UP,end.anchor(),false);
                    if(!end.equals(ServerCabinets.validatedTarget(p,end.anchor(),h,hand)))return;
                }
                if(!debugValid(p,debug)||!p.hasPermissions(2)||p.hasDisconnected()||p.connection.getConnection()!=connection||!connection.isConnected()
                        ||server.getPlayerList().getPlayer(p.getUUID())!=p||!primary.matches(p.serverLevel())||p.serverLevel().getBlockEntity(primary.anchor())!=block
                        ||!block.cabinetBackend().equals(packet.backend())||!primary.equals(CabinetLinks.master(server,target))
                        ||!Objects.equals(secondary,CabinetLinks.peer(server,primary))||ServerCabinets.isCabinetBusy(server,primary)
                        ||secondary!=null&&(!secondary.matches(p.serverLevel())||ServerCabinets.isCabinetBusy(server,secondary)))return;
                // All endpoints passed protection/identity checks before either is changed.
                for(var end:secondary==null?List.of(primary):List.of(primary,secondary))
                    ((LegacyFcArcadeBlockEntity)p.serverLevel().getBlockEntity(end.anchor())).setCoinRequired(packet.coinMode()==1);
                reason=packet.coinMode()==1?"已启用整组实体投币：一枚银币触发一次游戏投币；键盘/手柄免费投币已禁用":"已设为免费模式；保留原投币按键，不消耗银币";
            }
        }
        if(packet.autoPowerOff()>=0)reason="街机关机与显示范围由全服统一管理，请使用管理终端 → 街机全服。";
        if(debug!=null){
            if(!debugValid(p,debug)){debug.invalid=true;return;}
            reason="点中 "+target.anchor().toShortString()+"；"+(secondary==null?"仅此机柜。":"数据线两台共用主机 "+primary.anchor().toShortString()+" 的同步模式。")+reason;
        }
        if(reason.length()>256)reason=reason.substring(0,255)+"…";
        if(COMMAND.get())p.displayClientMessage(net.minecraft.network.chat.Component.literal(reason+"；当前模式："+get(server,primary,packet.backend())+"。街机GUI可用调试螺丝刀打开。"),false);
        var response=new CabinetSyncNetwork.Setting(target,packet.backend(),netplay(server,primary,packet.backend())?3:get(server,primary,packet.backend()).ordinal(),supported,editable,reason,hosted,CabinetHostingConfig.playerAllowed(),hostedReason==null?"服务器运算，所有玩家接收音画；停机后才能切换":hostedReason,debug==null?null:debug.token,coinSupported&&block.coinRequired(),coinEditable,CabinetGameInfo.describe(server,target,packet.backend()),block.autoPowerOffOnExit(),block.idleShutdownSeconds(),block.screenRenderDistance(),saveMode(server,primary,packet.backend()));
        if(debug!=null)debug.lastSetting=response;PacketDistributor.sendToPlayer(p,response);
    }
    static CabinetSyncSettings load(CompoundTag tag,HolderLookup.Provider registries){var value=new CabinetSyncSettings();var rows=tag.getList("Modes",10);for(int i=0;i<Math.min(4096,rows.size());i++){var row=rows.getCompound(i);String key=row.getString("Key");int mode=row.getInt("Mode");if(key.length()<=512&&!key.isBlank()&&mode>=0&&mode<=2){value.modes.put(key,CabinetSyncMode.checked(mode));int save=row.getInt("SaveMode");if(save>=0&&save<=2)value.saveModes.put(key,save);if(mode==1&&row.getBoolean("Netplay"))value.netplayModes.add(key);}}return value;}
    @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider registries){var rows=new ListTag();modes.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e->{var row=new CompoundTag();row.putString("Key",e.getKey());row.putInt("Mode",e.getValue().ordinal());row.putInt("SaveMode",saveModes.getOrDefault(e.getKey(),0));row.putBoolean("Netplay",netplayModes.contains(e.getKey())&&e.getValue()==CabinetSyncMode.LOCAL_SYNC);rows.add(row);});tag.put("Modes",rows);return tag;}
}
