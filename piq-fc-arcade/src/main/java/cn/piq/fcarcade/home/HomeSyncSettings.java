package cn.piq.fcarcade.home;

import cn.piq.fcarcade.cabinet.CabinetSyncMode;
import cn.piq.fcarcade.server.ServerArcadeSessions;
import java.util.*;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/** Physical settings authority only: does not power off, lend controllers, or touch saves. */
@EventBusSubscriber(modid="piq_fc_arcade")
public final class HomeSyncSettings {
    private static final Map<ServerPlayer,Intent> INTENTS = new WeakHashMap<>();
    private static final ThreadLocal<Boolean> CHECKING = ThreadLocal.withInitial(() -> false);
    private HomeSyncSettings() {}
    private static final class Intent {
        final UUID token=UUID.randomUUID();final Connection connection;final HomeEndpointBlockEntity console;
        final BlockPos clicked;final UUID hardware,link;final BlockPos peer;final long expires;
        final DeviceDebugService.Selection tool;
        int revision;long lastRequest=Long.MIN_VALUE;HomeSyncNetwork.Setting lastSetting;
        Intent(ServerPlayer player,HomeEndpointBlockEntity console,BlockPos clicked,DeviceDebugService.Selection tool) {
            connection=player.connection.getConnection();this.console=console;this.clicked=clicked.immutable();
            hardware=console.hardwareId();link=console.linkId();peer=console.peerPos();
            expires=Integer.toUnsignedLong(player.getServer().getTickCount())+1200L;this.tool=tool;
        }
    }

    @SubscribeEvent public static void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("home-sync")
                .requires(source -> source.getEntity() instanceof ServerPlayer).executes(context -> {
                    var player = context.getSource().getPlayerOrException();
                    HitResult selected = player.pick(Math.min(6,player.blockInteractionRange()),0,false);
                    if (selected instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK)
                        open(player,hit.getBlockPos(),hit);
                    else feedback(player,"请瞄准家用主机机身，再使用 /home-sync。");
                    return 1;
                }));
    }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event) {
        INTENTS.entrySet().removeIf(entry -> entry.getKey().getServer() == event.getServer());
    }
    @SubscribeEvent public static void logout(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) INTENTS.remove(player);
    }
    @SubscribeEvent public static void tick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        long now=Integer.toUnsignedLong(event.getServer().getTickCount());if(now%100!=0)return;
        INTENTS.entrySet().removeIf(entry -> entry.getKey().getServer()==event.getServer()
                && (entry.getKey().hasDisconnected()||now>entry.getValue().expires));
    }
    public static void open(ServerPlayer player, BlockPos clicked, BlockHitResult hit) {
        open(player,clicked,hit,null);
    }
    static void openDebug(ServerPlayer player,DeviceDebugService.Selection selection) {
        if (!DeviceDebugService.current(player,selection)) return;
        open(player,selection.hit().getBlockPos(),selection.hit(),selection);
    }
    private static void open(ServerPlayer player,BlockPos clicked,BlockHitResult hit,DeviceDebugService.Selection tool) {
        if (player == null || clicked == null || hit == null || !clicked.equals(hit.getBlockPos()) || CHECKING.get()) return;
        var console = HomeHardware.loadedEndpoint(player.serverLevel(),clicked);
        if (!(console instanceof HomeConsoleBlockEntity) && !(console instanceof ExternalHomeConsoleBlockEntity)) {
            feedback(player,"同步设置在主机机身上；电视只操作物理按钮。");return;
        }
        if (!available(console)) { feedback(player,"此机型不支持同步设置。");return; }
        var intent = new Intent(player,console,clicked,tool);
        if (!authorized(player,intent,hit)) { feedback(player,"主机设置不可用：距离、硬件或交互权限已失效。");return; }
        if (INTENTS.size() >= 256 && !INTENTS.containsKey(player)) { feedback(player,"设置请求已达上限，请稍后再试。");return; }
        INTENTS.put(player,intent);
        reply(player,intent,true,"联机设置下次开机生效。");
    }
    static void request(ServerPlayer player, HomeSyncNetwork.Request request) {
        if(CHECKING.get())return;
        var intent = INTENTS.get(player);
        if (intent == null || !intent.token.equals(request.token())) return;
        if(player.getServer()==null||!player.getServer().isSameThread()||player.connection.getConnection()!=intent.connection||!intent.connection.isConnected())return;
        long now=Integer.toUnsignedLong(player.getServer().getTickCount());
        if(intent.lastRequest!=Long.MIN_VALUE&&now-intent.lastRequest<DeviceDebugPolicy.REQUEST_TICKS){
            cachedReply(player,intent,"操作过快，设置未修改；请稍后重试。");return;
        }
        intent.lastRequest=now;
        try { DeviceDebugService.guard(() -> { requestChecked(player,intent,request);return null; }); }
        catch(RuntimeException|LinkageError rejected){INTENTS.remove(player);com.mojang.logging.LogUtils.getLogger().warn("Home debug settings rejected",rejected);}
    }
    private static void requestChecked(ServerPlayer player,Intent intent,HomeSyncNetwork.Request request) {
        var hit = new BlockHitResult(Vec3.atCenterOf(intent.clicked),Direction.UP,intent.clicked,false);
        if (!authorized(player,intent,hit)) { INTENTS.remove(player);feedback(player,"设置授权已过期或硬件/权限发生变化，请重新打开。");return; }
        var old=intent.lastSetting;var c=intent.console;
        if(old!=null&&(old.mode()!=displayMode(c)||old.occupancy()!=c.occupancyVisible()||old.approval()!=c.joinApprovalRequired())){
            intent.revision++;reply(player,intent,false,"设置已被其他操作更新，请核对后再调整。");return;
        }
        if(request.revision()!=intent.revision){reply(player,intent,false,"状态已更新，请核对后再调整。");return;}
        String reason = "联机设置下次开机生效。";
        if (request.mode() >= 0 || request.occupancy() >= 0 || request.approval() >= 0) {
            boolean busy = busy(player,intent.console);
            if (!player.hasPermissions(2)) reason = "只有管理员可更改主机高级设置。";
            else if (diagnosticsOnly(c)) reason = "此设备仅提供诊断；没有可修改的公共联机设置。";
            else if (busy) reason = "请先关机并关闭加入、存档选择窗口；已借手柄无需归还。";
            else if (request.occupancy() >= 0 && !HomePresentationSettings.occupancySupported(c.getLevel(),c.getBlockPos())) reason="此机型不支持使用者标牌。";
            else if (request.mode() >= 0 && (supported(player,intent.console)&(1<<request.mode()))==0)
                reason = "此机型或服务器不支持所选联机方式。";
            else {
                // Re-run protection after all provider callbacks, then check the exact live instance again.
                if (!authorized(player,intent,hit)) return;
                int modes=supported(player,c);boolean stillBusy=busy(player,c);
                if(!DeviceDebugPolicy.mayEdit(player.hasPermissions(2),stillBusy,HomePresentationSettings.occupancySupported(c.getLevel(),c.getBlockPos()),
                        request.mode(),request.occupancy(),request.approval(),modes)||!basic(player,intent)||!player.hasPermissions(2)||!identity(player,intent))return;
                if(request.mode()>=0){
                    if(c instanceof HomeConsoleBlockEntity fc){fc.netplayExperimental(request.mode()>=3);fc.netplayJniTrial(request.mode()==4);}
                    if(c instanceof ExternalHomeConsoleBlockEntity external){external.netplayExperimental(request.mode()==3);external.netplayJniTrial(request.mode()==4);}
                    c.synchronizationMode(request.mode()>=3?CabinetSyncMode.LOCAL_SYNC:CabinetSyncMode.checked(request.mode()));
                }
                else if(request.occupancy()>=0)c.occupancyVisible(request.occupancy()!=0);
                else c.joinApprovalRequired(request.approval()!=0);
                intent.revision++;
                reason = request.mode()>=0?HomeSyncSaveHints.modeSaved(system(c),request.mode())
                        :request.occupancy()>=0?"使用者标牌设置已保存。":"加入确认已保存，下次开机生效；已加入玩家不受影响。";
            }
        }
        if (basic(player,intent)) reply(player,intent,false,reason);
    }
    private static void reply(ServerPlayer player, Intent intent, boolean open, String reason) {
        var c = intent.console;
        String system = system(c);
        int modes=supported(player,c);
        var modeReasons=new ArrayList<String>(5);
        for(int mode=0;mode<5;mode++)modeReasons.add(HomeSyncMenuPolicy.supported(modes,mode)?"":bounded(unavailableMode(player,c,mode)));
        if(reason.equals("联机设置下次开机生效。")&&!diagnosticsOnly(c)) {
            if(!player.hasPermissions(2))reason="仅管理员可修改设备设置。";
            else if(busy(player,c))reason="关机并关闭加入、存档窗口后可修改设置。";
        }
        if(diagnosticsOnly(c)&&c instanceof ExternalHomeConsoleBlockEntity external) {
            try { reason=(reason.equals("联机设置下次开机生效。")||open?"":reason+" ")+HomeSystems.applianceHooks(external.systemId()).deviceSettingsStatus(player.serverLevel(),external); }
            catch(RuntimeException|LinkageError failure){reason="读取设备状态失败，请刷新或查看日志。";}
        } else {
            reason=HomeSyncMenuPolicy.selectedStatus(reason,displayMode(c),modes,modeReasons.get(displayMode(c)));
        }
        if(reason.length()>256)reason=reason.substring(0,255)+"…";
        var setting=new HomeSyncNetwork.Setting(intent.token,intent.revision,player.serverLevel().dimension().location(),
                c.getBlockPos(),c.hardwareId(),system,displayMode(c),modes,
                !diagnosticsOnly(c)&&player.hasPermissions(2)&&!busy(player,c),reason,open,c.occupancyVisible(),c.joinApprovalRequired(),HomePresentationSettings.occupancySupported(c.getLevel(),c.getBlockPos()),intent.tool!=null,modeReasons);
        if(!identity(player,intent))return;
        intent.lastSetting=setting;HomeSyncNetwork.send(player,setting);
    }
    private static String system(HomeEndpointBlockEntity console) {
        return console instanceof ExternalHomeConsoleBlockEntity external ? external.systemId().getPath().toUpperCase(java.util.Locale.ROOT)
                : console.getBlockState().getBlock() instanceof SuborConsoleBlock ? "小霸王学习机（SB-926）" : "FC";
    }
    private static int displayMode(HomeEndpointBlockEntity c){if(c instanceof HomeConsoleBlockEntity fc&&fc.netplayJniTrial()||c instanceof ExternalHomeConsoleBlockEntity external&&external.netplayJniTrial())return 4;return c instanceof HomeConsoleBlockEntity fc&&fc.netplayExperimental()||c instanceof ExternalHomeConsoleBlockEntity external&&external.netplayExperimental()?3:c.synchronizationMode().ordinal();}
    /** A rate-limit rejection must not invoke shapes, addon hooks or protection callbacks again. */
    private static void cachedReply(ServerPlayer player,Intent intent,String reason) {
        var s=intent.lastSetting;if(s==null)return;
        HomeSyncNetwork.send(player,new HomeSyncNetwork.Setting(s.token(),s.revision(),s.dimension(),s.console(),s.hardware(),s.system(),s.mode(),s.supported(),
                s.editable(),reason,false,s.occupancy(),s.approval(),s.occupancySupported(),s.debugTool(),s.modeReasons()));
    }
    private static boolean diagnosticsOnly(HomeEndpointBlockEntity console) {
        return console instanceof ExternalHomeConsoleBlockEntity external&&HomeSystems.privateDeviceSettings(external.systemId());
    }
    private static boolean available(HomeEndpointBlockEntity console) {
        if (console instanceof HomeConsoleBlockEntity) return true;
        if (console instanceof ExternalHomeConsoleBlockEntity external) {
            var hooks = HomeSystems.applianceHooks(external.systemId());
            try{return hooks != null && hooks.deviceSettingsAvailable();}catch(RuntimeException|LinkageError failure){return false;}
        }
        return false;
    }
    private static int supported(ServerPlayer player,HomeEndpointBlockEntity console){
        return supported(player.serverLevel(), console);
    }
    private static int supported(net.minecraft.server.level.ServerLevel level,HomeEndpointBlockEntity console){
        int policyMask=cn.piq.fcarcade.cabinet.CabinetHostingConfig.localAllowed()?7:5;
        if(!cn.piq.fcarcade.cabinet.CabinetHostingConfig.playerAllowed())policyMask&=~1;
        if(console instanceof ExternalHomeConsoleBlockEntity external){var hooks=HomeSystems.applianceHooks(external.systemId());
            try{return hooks==null||!hooks.synchronizationSettingsAvailable()?0:HomeSyncMenuPolicy.externalModes(hooks.synchronizationSupportedModes(level,external),policyMask,
                    cn.piq.fcarcade.cabinet.CabinetHostingConfig.localAllowed(),hooks.jniNetplaySettingsAvailable());}catch(RuntimeException|LinkageError failure){return 0;}}
        if(!cn.piq.fcarcade.cabinet.CabinetHostingConfig.enabled()||cn.piq.fcarcade.core.libretro.LibretroNesCore.unavailableReason()!=null)policyMask&=~4;
        return (HomeSyncPolicy.supportedMask()&policyMask)|(cn.piq.fcarcade.cabinet.CabinetHostingConfig.localAllowed()?24:0);
    }
    static CabinetSyncMode placementMode(net.minecraft.server.level.ServerLevel level, HomeEndpointBlockEntity console, CabinetSyncMode fallback) {
        int desired=cn.piq.fcarcade.config.GameConsoleAdminSettings.defaultMode(level.getServer());
        int modes=supported(level,console);
        if(console instanceof HomeConsoleBlockEntity fc&&cn.piq.fcarcade.config.GameConsoleAdminPolicy.defaultFcJni(desired,modes)){
            fc.netplayExperimental(true);fc.netplayJniTrial(true);
            return CabinetSyncMode.LOCAL_SYNC;
        }
        return CabinetSyncMode.checked(cn.piq.fcarcade.config.GameConsoleAdminPolicy.selectNewMode(desired,modes,fallback.ordinal()));
    }
    /** OP command convenience; uses the same identity, protection, provider and idle gates as the GUI. */
    public static boolean adminCommand(ServerPlayer player, BlockHitResult hit, int mode, Integer range) {
        if (player==null||hit==null||!player.hasPermissions(2)||player.getServer()==null||!player.getServer().isSameThread()
                ||CHECKING.get()||mode < -1||mode > 2||range!=null&&!cn.piq.fcarcade.config.GameConsoleAdminPolicy.validRange(range)) return false;
        var console=HomeHardware.loadedEndpoint(player.serverLevel(),hit.getBlockPos());
        if (!(console instanceof HomeConsoleBlockEntity)&&!(console instanceof ExternalHomeConsoleBlockEntity)) return false;
        if(range==null) {
            INTENTS.remove(player); // A rejected new selection must not reuse a previous GUI capability.
            open(player,hit.getBlockPos(),hit);
            var intent=INTENTS.get(player);
            if(intent!=null&&intent.console==console&&mode>=0) request(player,new HomeSyncNetwork.Request(intent.token,intent.revision,mode,-1,-1));
            return true;
        }
        var intent=new Intent(player,console,hit.getBlockPos(),null);
        try { return DeviceDebugService.guard(() -> {
            if(!authorized(player,intent,hit)||busy(player,console)||!player.hasPermissions(2)) {
                feedback(player,"旁观范围未修改：需 OP2、主机空闲且有交互权限。"); return true;
            }
            if(!authorized(player,intent,hit)||busy(player,console)||!basic(player,intent)||!identity(player,intent)||!player.hasPermissions(2)) return true;
            console.setObservationRange(range);
            feedback(player,"本机旁观接收范围已设为 "+range+" 格；不改 Minecraft 区块渲染距离，下次开机使用。"); return true;
        }); } catch(RuntimeException|LinkageError rejected) { feedback(player,"旁观范围未确认，请重新选择设备。"); return true; }
    }
    private static String bounded(String reason){return reason==null||reason.isBlank()?"此机型尚未提供该运行方式。":reason.length()>256?reason.substring(0,255)+"…":reason;}
    private static String unavailableMode(ServerPlayer player,HomeEndpointBlockEntity console,int selected){
        if(console instanceof ExternalHomeConsoleBlockEntity external){
            var hooks=HomeSystems.applianceHooks(external.systemId());
            try {
                int implemented=hooks!=null&&hooks.synchronizationSettingsAvailable()?hooks.synchronizationSupportedModes(player.serverLevel(),external):0;
                if(selected==4&&(hooks==null||!hooks.jniNetplaySettingsAvailable()))implemented&=~16;
                if(!HomeSyncMenuPolicy.supported(implemented,selected))return hooks!=null
                        ?hooks.synchronizationUnavailableReason(player.serverLevel(),external,selected)
                        :"此机型尚未提供该 Netplay 运行方式。";
            }catch(RuntimeException|LinkageError failure){return "读取附属运行能力失败，请刷新或查看日志。";}
        }
        if(selected==0&&!cn.piq.fcarcade.cabinet.CabinetHostingConfig.playerAllowed())return HomeSyncPolicy.unavailable(CabinetSyncMode.MEDIA);
        if((selected==1||selected>=3)&&!cn.piq.fcarcade.cabinet.CabinetHostingConfig.localAllowed())return "服主已禁用本地输入同步及 Netplay；请在管理终端检查同步策略。";
        if(selected>=3)return "此机型尚未提供该 Netplay 运行方式。";
        return unavailable(player,console,CabinetSyncMode.checked(selected));
    }
    private static String unavailable(ServerPlayer player,HomeEndpointBlockEntity console,CabinetSyncMode mode){
        if(console instanceof HomeConsoleBlockEntity&&mode==CabinetSyncMode.SERVER_MEDIA&&!cn.piq.fcarcade.cabinet.CabinetHostingConfig.enabled())
            return "服务端托管未启用。";
        if(console instanceof HomeConsoleBlockEntity&&mode==CabinetSyncMode.SERVER_MEDIA&&cn.piq.fcarcade.cabinet.CabinetHostingConfig.enabled()){
            String platform=cn.piq.fcarcade.core.libretro.LibretroNesCore.unavailableReason();if(platform!=null)return platform;
        }
        if(console instanceof ExternalHomeConsoleBlockEntity external){var hooks=HomeSystems.applianceHooks(external.systemId());
            try{if(hooks!=null)return hooks.synchronizationUnavailableReason(player.serverLevel(),external,mode);}catch(RuntimeException|LinkageError ignored){}}
        return HomeSyncPolicy.unavailable(mode);
    }
    private static boolean busy(ServerPlayer player, HomeEndpointBlockEntity console) {
        if (console instanceof HomeConsoleBlockEntity fc) return ServerArcadeSessions.homeConfigurationBusy(player.serverLevel(),fc);
        if (console instanceof ExternalHomeConsoleBlockEntity external) {
            var hooks = HomeSystems.applianceHooks(external.systemId());
            try{return hooks == null || hooks.synchronizationSettingsBusy(player.serverLevel(),external);}catch(RuntimeException|LinkageError failure){return true;}
        }
        return true;
    }
    private static boolean basic(ServerPlayer player, Intent intent) {
        var server = player.getServer();var c = intent.console;var level = player.serverLevel();
        if (server == null || !server.isSameThread() || Integer.toUnsignedLong(server.getTickCount()) > intent.expires
                || server.getPlayerList().getPlayer(player.getUUID()) != player || !player.isAlive() || player.isSpectator()
                || player.hasDisconnected() || player.connection.getConnection() != intent.connection || !intent.connection.isConnected()
                || c.getLevel() != level || c.isRemoved() || !HomeHardware.mayUse(player,intent.clicked)
                || HomeHardware.loadedEndpoint(level,intent.clicked) != c || !c.hardwareId().equals(intent.hardware)
                || !Objects.equals(c.linkId(),intent.link) || !Objects.equals(c.peerPos(),intent.peer)
                || !level.getWorldBorder().isWithinBounds(c.getBlockPos()) || !level.mayInteract(player,c.getBlockPos()) || !available(c)) return false;
        if (c instanceof HomeConsoleBlockEntity && !SuborStructure.complete(level,c.getBlockPos())) return false;
        if (c instanceof ExternalHomeConsoleBlockEntity external && !HomeSystems.complete(external)) return false;
        if (intent.peer != null && (!(HomeHardware.loadedEndpoint(level,intent.peer) instanceof HomeTvBlockEntity tv)
                || HomeHardware.connectedEndpoint(level,intent.peer) != c || !level.mayInteract(player,tv.getBlockPos())
                || !level.getWorldBorder().isWithinBounds(tv.getBlockPos()))) return false;
        return (intent.tool==null||DeviceDebugService.current(player,intent.tool))&&identity(player,intent);
    }
    /** Last check has no provider/protection callbacks; a callback may not replace the clicked hardware. */
    private static boolean identity(ServerPlayer player,Intent i){
        var c=i.console;var level=player.serverLevel();
        return player.getServer()!=null&&player.getServer().isSameThread()&&!player.hasDisconnected()&&player.isAlive()&&!player.isSpectator()
                &&player.getServer().getPlayerList().getPlayer(player.getUUID())==player&&player.connection.getConnection()==i.connection&&i.connection.isConnected()
                &&c.getLevel()==level&&!c.isRemoved()&&level.hasChunkAt(c.getBlockPos())&&level.getBlockEntity(c.getBlockPos())==c
                &&level.hasChunkAt(i.clicked)&&HomeHardware.loadedEndpoint(level,i.clicked)==c&&c.hardwareId().equals(i.hardware)
                &&Objects.equals(c.linkId(),i.link)&&Objects.equals(c.peerPos(),i.peer)
                &&(i.tool==null||player.hasPermissions(2)&&player.getItemInHand(i.tool.hand())==i.tool.held()&&!i.tool.held().isEmpty()
                    &&i.tool.held().getItem() instanceof DeviceDebugItem&&ItemStack.matches(i.tool.held(),i.tool.original()));
    }
    private static boolean authorized(ServerPlayer player, Intent intent, BlockHitResult hit) {
        if (CHECKING.get() || !basic(player,intent)) return false;
        ItemStack main = player.getMainHandItem(),off = player.getOffhandItem();
        ItemStack mainCopy = main.copy(),offCopy = off.copy();
        CHECKING.set(true);
        try {
            var positions = new LinkedHashSet<BlockPos>();positions.add(intent.clicked);positions.add(intent.console.getBlockPos());
            if (intent.peer != null) positions.add(intent.peer);
            for (BlockPos pos : positions) {
                var protectedHit = new BlockHitResult(hit.getLocation(),hit.getDirection(),pos,hit.isInside());
                var event = NeoForge.EVENT_BUS.post(new net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock(
                        player,intent.tool==null?InteractionHand.MAIN_HAND:intent.tool.hand(),pos,protectedHit));
                if (event.isCanceled() || event.getUseBlock() == TriState.FALSE || event.getUseItem() == TriState.FALSE
                        || !basic(player,intent) || main != player.getMainHandItem() || off != player.getOffhandItem()
                        || !ItemStack.matches(main,mainCopy) || !ItemStack.matches(off,offCopy)) return false;
            }
            return true;
        } catch(RuntimeException|LinkageError rejected){return false;} finally { CHECKING.remove(); }
    }
    private static void feedback(ServerPlayer player,String text) { player.displayClientMessage(Component.literal(text),true); }
}
