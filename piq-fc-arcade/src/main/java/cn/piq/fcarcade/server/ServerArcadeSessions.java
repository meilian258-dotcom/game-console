package cn.piq.fcarcade.server;

import cn.piq.fcarcade.ArcadeFramePayload;
import cn.piq.fcarcade.ArcadeDigestPayload;
import cn.piq.fcarcade.ArcadeExitDecisionPayload;
import cn.piq.fcarcade.ArcadeExitPromptPayload;
import cn.piq.fcarcade.ArcadeHistoryPayload;
import cn.piq.fcarcade.ArcadeInputPayload;
import cn.piq.fcarcade.ArcadeJoinApprovalPayload;
import cn.piq.fcarcade.ArcadeJoinDecisionPayload;
import cn.piq.fcarcade.ArcadeLibraryPayload;
import cn.piq.fcarcade.ArcadeLeaderboardTogglePayload;
import cn.piq.fcarcade.ArcadeMultiplayerOfferPayload;
import cn.piq.fcarcade.ArcadeMultiplayerResponsePayload;
import cn.piq.fcarcade.ArcadePersistentStatePayload;
import cn.piq.fcarcade.ArcadeResetPayload;
import cn.piq.fcarcade.ArcadeResumeDecisionPayload;
import cn.piq.fcarcade.ArcadeResumePromptPayload;
import cn.piq.fcarcade.ArcadeSessionPayload;
import cn.piq.fcarcade.ArcadeSettingsPayload;
import cn.piq.fcarcade.ArcadeSettingsUpdatePayload;
import cn.piq.fcarcade.ArcadeSaveCatalogEntry;
import cn.piq.fcarcade.ArcadeSaveCatalogPayload;
import cn.piq.fcarcade.ArcadeSaveDeletePayload;
import cn.piq.fcarcade.ArcadeSaveSlotActionPayload;
import cn.piq.fcarcade.ArcadeSaveSlotEntry;
import cn.piq.fcarcade.ArcadeSaveSlotsPayload;
import cn.piq.fcarcade.ArcadeScorePayload;
import cn.piq.fcarcade.ArcadeSnapshotPayload;
import cn.piq.fcarcade.ArcadeSnapshotRequestPayload;
import cn.piq.fcarcade.ArcadeSnapshotUploadPayload;
import cn.piq.fcarcade.FcNetwork;
import cn.piq.fcarcade.FcArcadeMod;
import cn.piq.fcarcade.LeaderboardPanelConfigUpdatePayload;
import cn.piq.fcarcade.RomDownloadChunkPayload;
import cn.piq.fcarcade.RomDownloadStartPayload;
import cn.piq.fcarcade.RomReadyPayload;
import cn.piq.fcarcade.RomPlayerModePayload;
import cn.piq.fcarcade.RomDeletePayload;
import cn.piq.fcarcade.RomRenamePayload;
import cn.piq.fcarcade.RomSaveModePayload;
import cn.piq.fcarcade.RomSelectRequestPayload;
import cn.piq.fcarcade.RomUploadChunkPayload;
import cn.piq.fcarcade.RomUploadStartPayload;
import cn.piq.fcarcade.rom.RomDescriptor;
import cn.piq.fcarcade.rom.RomRepository;
import cn.piq.fcarcade.rom.RomSaveMode;
import cn.piq.fcarcade.rom.RomTransferBuffer;
import cn.piq.fcarcade.rom.RomTransferLimits;
import cn.piq.fcarcade.score.RoadRaceScoreRule;
import cn.piq.fcarcade.config.ArcadeGlobalSettings;
import cn.piq.fcarcade.session.LockstepState;
import cn.piq.fcarcade.session.ControllerDepartureInputs;
import cn.piq.fcarcade.session.LockstepDigestTracker;
import cn.piq.fcarcade.session.LockstepTimeline;
import cn.piq.fcarcade.session.ArcadeRole;
import cn.piq.fcarcade.session.ArcadeMode;
import cn.piq.fcarcade.session.SessionRoster;
import cn.piq.fcarcade.world.ArcadeStructure;
import cn.piq.fcarcade.world.FcArcadeBlock;
import cn.piq.fcarcade.home.HomeHardware;
import cn.piq.fcarcade.home.HomeFeedback;
import cn.piq.fcarcade.home.flow.HomeLaunchServer;
import cn.piq.fcarcade.home.flow.HomeLaunchNetwork;
import java.util.Objects;
import cn.piq.fcarcade.cabinet.*;
import cn.piq.fcarcade.server.hosted.*;
import cn.piq.fcarcade.home.HomeControllerService;
import cn.piq.fcarcade.storage.FcStoragePaths;
import cn.piq.fcarcade.home.RetroTvBlock;
import cn.piq.fcarcade.world.LeaderboardPanelBlockEntity;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.time.Instant;
import java.util.List;

public final class ServerArcadeSessions {
    private static final double MAX_DISTANCE_SQUARED = 8.0D * 8.0D;
    private static final int MAX_INPUT_PACKETS_PER_TICK = 32;
    private static final int MAX_CONCURRENT_ROM_TRANSFERS = 8;
    private static final long MAX_IN_FLIGHT_UPLOAD_BYTES =
            2L * RomRepository.MAX_ROM_BYTES;
    private static final long MAX_CACHED_SNAPSHOT_AGE_FRAMES = 300;
    private static final long MAX_UPLOADED_SNAPSHOT_LAG_FRAMES = 600;
    private static final int SNAPSHOT_REFRESH_TICKS = 80;
    private static final Map<MinecraftServer, Manager> MANAGERS = new WeakHashMap<>();

    private ServerArcadeSessions() {
    }

    public static void register() {
        WatchProviders.register(JNI_WATCH_PROVIDER,new WatchProvider(){
            public List<WatchSource> sources(MinecraftServer server){var m=MANAGERS.get(server);return m==null?List.of():m.sessions.values().stream().filter(s->sharedJniWatch(s)&&s.homeReady&&m.validHome(server,s)).map(ServerArcadeSessions::jniWatchSource).toList();}
            public boolean isCurrent(MinecraftServer server,WatchSource source){return jniWatchSession(server,source)!=null;}
            public boolean isParticipant(MinecraftServer server,UUID player){var m=MANAGERS.get(server);return m!=null&&m.sessions.values().stream().anyMatch(s->sharedJniWatch(s)&&homeParticipant(server,s,player));}
            public boolean isParticipant(MinecraftServer server,WatchSource source,UUID player){var s=jniWatchSession(server,source);return s!=null&&homeParticipant(server,s,player);}
            public boolean acceptsUpload(){return false;}
            public WatchNetplay.Offer netplay(ServerPlayer player,WatchSource source){var s=jniWatchSession(player.getServer(),source);return s==null?null:new WatchNetplay.Offer(s.id,s.netplay,s.variant.isZapper()?JNI_GUN_BACKEND:JNI_PAD_BACKEND,s.romSha256,null);}
        });
        WatchProviders.register(PLAYER_WATCH_PROVIDER,new WatchProvider(){
            public List<WatchSource> sources(MinecraftServer server){var m=MANAGERS.get(server);if(m==null)return List.of();return m.sessions.values().stream().filter(s->s.playerMedia&&s.homeReady&&m.validHome(server,s)).map(ServerArcadeSessions::playerWatchSource).toList();}
            public boolean isCurrent(MinecraftServer server,WatchSource source){return playerMediaSession(server,source)!=null;}
            public boolean isParticipant(MinecraftServer server,UUID player){var m=MANAGERS.get(server);var p=server.getPlayerList().getPlayer(player);return m!=null&&Manager.current(p)&&m.sessions.values().stream().anyMatch(s->s.playerMedia&&(Manager.computeHost(s,p)||s.homeRuntime.player(player)!=null&&s.homeRuntime.player(player).connection()==p.connection.getConnection()));}
            public int controlRecipients(MinecraftServer server,WatchSource source){var s=playerMediaSession(server,source);return s==null?0:playerMediaRecipients(server,s).size();}
            public void relayControls(MinecraftServer server,WatchSource source,List<CabinetMediaPacket> batch){var s=playerMediaSession(server,source);if(s==null)return;for(var p:playerMediaRecipients(server,s))FcHomeHostedNetwork.send(p,s.id,s.lockstep.epoch(),batch);}
        });
        WatchProviders.register(HOSTED_WATCH_PROVIDER,new WatchProvider(){
            public List<WatchSource> sources(MinecraftServer server){var manager=MANAGERS.get(server);if(manager==null)return List.of();return manager.sessions.values().stream().filter(s->s.hosted!=null&&s.hosted.ready()&&manager.validHome(server,s)).map(ServerArcadeSessions::hostedWatchSource).toList();}
            public boolean isCurrent(MinecraftServer server,WatchSource source){return sources(server).contains(source);}
            public boolean isParticipant(MinecraftServer server,UUID player){var manager=MANAGERS.get(server);var p=server.getPlayerList().getPlayer(player);return manager!=null&&Manager.current(p)&&manager.sessions.values().stream().anyMatch(s->s.hosted!=null&&(Manager.computeHost(s,p)||s.homeRuntime.player(player)!=null&&s.homeRuntime.player(player).connection()==p.connection.getConnection()));}
            public boolean acceptsUpload(){return false;}
            public boolean serverHosted(MinecraftServer server,WatchSource source){return isCurrent(server,source);}
        });
        ArcadeOccupancyDisplay.register();
        NeoForge.EVENT_BUS.addListener(ServerArcadeSessions::onServerTick);
        NeoForge.EVENT_BUS.addListener(ServerArcadeSessions::onPlayerLoggedOut);
        NeoForge.EVENT_BUS.addListener(ServerArcadeSessions::onServerStopped);
        NeoForge.EVENT_BUS.addListener(ServerArcadeSessions::onRegisterCommands);
    }
    private static final net.minecraft.resources.ResourceLocation HOSTED_WATCH_PROVIDER=net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("piq_fc_arcade","home_server");
    private static final net.minecraft.resources.ResourceLocation PLAYER_WATCH_PROVIDER=net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("piq_fc_arcade","home_player");
    public static final ResourceLocation JNI_WATCH_PROVIDER=ResourceLocation.fromNamespaceAndPath("piq_fc_arcade","home_jni_netplay");
    public static final ResourceLocation JNI_PAD_BACKEND=ResourceLocation.fromNamespaceAndPath("piq_fc_arcade","fc_jni_pad");
    public static final ResourceLocation JNI_GUN_BACKEND=ResourceLocation.fromNamespaceAndPath("piq_fc_arcade","fc_jni_gun");
    private static boolean sharedJniWatch(Session s){return s.homeRuntime!=null&&s.netplay!=null&&s.netplayJniTrial;}
    private static WatchSource jniWatchSource(Session s){return new WatchSource(new WatchDescriptor(JNI_WATCH_PROVIDER,s.mediaSource,s.mediaToken,s.key.dimension().location(),new WatchAnchor(s.homeConsolePos,s.homeConsoleId),s.homeLinkId,List.of(new WatchAnchor(s.key.anchor(),s.homeTvId))),s.homeRuntime.host());}
    private static Session jniWatchSession(MinecraftServer server,WatchSource source){var m=MANAGERS.get(server);if(m==null||source==null||!server.isSameThread())return null;for(var s:m.sessions.values())if(sharedJniWatch(s)&&s.homeReady&&!s.netplay.closed()&&jniWatchSource(s).equals(source)&&m.validHome(server,s))return s;return null;}
    private static boolean homeParticipant(MinecraftServer server,Session s,UUID player){var p=server.getPlayerList().getPlayer(player);if(!Manager.current(p))return false;var control=s.homeRuntime.player(player);return Manager.computeHost(s,p)||control!=null&&control.connection()==p.connection.getConnection();}
    private static WatchSource hostedWatchSource(Session s){return new WatchSource(new WatchDescriptor(HOSTED_WATCH_PROVIDER,s.hosted.source,s.hosted.token,s.key.dimension().location(),new WatchAnchor(s.homeConsolePos,s.homeConsoleId),s.homeLinkId,List.of(new WatchAnchor(s.key.anchor(),s.homeTvId))),s.homeRuntime.host());}
    private static WatchSource playerWatchSource(Session s){return new WatchSource(new WatchDescriptor(PLAYER_WATCH_PROVIDER,s.mediaSource,s.mediaToken,s.key.dimension().location(),new WatchAnchor(s.homeConsolePos,s.homeConsoleId),s.homeLinkId,List.of(new WatchAnchor(s.key.anchor(),s.homeTvId))),s.homeRuntime.host());}
    private static Session playerMediaSession(MinecraftServer server,WatchSource source){
        var m=MANAGERS.get(server);if(m==null||source==null||!server.isSameThread())return null;
        for(var s:m.sessions.values())if(s.playerMedia&&s.homeReady&&playerWatchSource(s).equals(source)&&m.validHome(server,s))return s;return null;
    }
    /** Source authority and the exact current physical lease are rechecked for every recipient copy. */
    private static List<ServerPlayer> playerMediaRecipients(MinecraftServer server,Session s){
        var result=new java.util.ArrayList<ServerPlayer>(2);
        for(UUID id:s.roster.playerIds()){
            var p=server.getPlayerList().getPlayer(id);if(!Manager.current(p)||Manager.computeHost(s,p)||validStructure(p,s.key.anchor())==null)continue;
            var c=s.homeRuntime.player(id);if(c==null||c.connection()!=p.connection.getConnection())continue;
            boolean authorized=s.homeRuntime.gunMode()&&c.port()==1
                    ?s.zapperBinding!=null&&cn.piq.fcarcade.home.HomeZapperService.authorized(p,s.zapperBinding,false)
                    :HomeControllerService.mediaAuthorized(p,s.key.anchor(),s.id,c.port(),c.lease());
            if(authorized)result.add(p);
        }
        return List.copyOf(result);
    }

    /** Removes only text owned by this exact anchor; sessions and saved configuration are untouched. */
    public static void removeMachineDisplays(
            MinecraftServer server, ResourceKey<Level> dimension, BlockPos anchor
    ) {
        ArcadeOccupancyDisplay.remove(server, dimension, anchor);
    }

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("fc-score")
                        .executes(context -> showLeaderboard(context.getSource()))
                        .then(Commands.literal("top")
                                .executes(context -> showLeaderboard(
                                        context.getSource())))
                        .then(Commands.literal("me")
                                .executes(context -> showPersonalBest(
                                        context.getSource())))
                        .then(Commands.literal("interval")
                                .requires(source -> source.hasPermission(2))
                                .executes(context -> showLeaderboardInterval(
                                        context.getSource()))
                                .then(Commands.argument(
                                                "seconds",
                                                IntegerArgumentType.integer(1, 60))
                                        .executes(context -> setLeaderboardInterval(
                                                context.getSource(),
                                                IntegerArgumentType.getInteger(
                                                        context,
                                                        "seconds"))))));
    }

    private static int showLeaderboard(CommandSourceStack source) {
        if (!storageReady(source)) return 0;
        List<ArcadeScoreStore.ScoreEntry> entries = manager(source.getServer())
                .scores(source.getServer())
                .top(RoadRaceScoreRule.ROM_SHA256, 10);
        source.sendSuccess(
                () -> Component.literal("=== 公路赛车排行榜 ==="),
                false);
        if (entries.isEmpty()) {
            source.sendSuccess(() -> Component.literal("暂时还没有成绩。"), false);
            return 1;
        }
        for (int index = 0; index < entries.size(); index++) {
            ArcadeScoreStore.ScoreEntry entry = entries.get(index);
            String line = String.format(
                    java.util.Locale.ROOT,
                    "%d. %s  %06d",
                    index + 1,
                    entry.playerName(),
                    entry.score());
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return entries.size();
    }

    public static List<ArcadeScoreStore.ScoreEntry> topRoadRaceScores(
            MinecraftServer server
    ) {
        if (!manager(server).initializeStorage(server)) return List.of(); // No backing store is created on failure.
        return manager(server).scores(server).top(
                RoadRaceScoreRule.ROM_SHA256,
                ArcadeLeaderboardText.LEADERBOARD_LIMIT);
    }

    private static int showPersonalBest(CommandSourceStack source)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        if (!storageReady(source)) return 0;
        ServerPlayer player = source.getPlayerOrException();
        int score = manager(source.getServer())
                .scores(source.getServer())
                .personalBest(RoadRaceScoreRule.ROM_SHA256, player.getUUID());
        String text = score <= 0
                ? "你还没有公路赛车成绩。"
                : String.format(
                        java.util.Locale.ROOT,
                        "你的公路赛车最高分：%06d",
                        score);
        source.sendSuccess(() -> Component.literal(text), false);
        return score;
    }

    private static int showLeaderboardInterval(CommandSourceStack source) {
        if (!storageReady(source)) return 0;
        int seconds = manager(source.getServer())
                .leaderboardPageSeconds(source.getServer());
        source.sendSuccess(
                () -> Component.literal(
                        "\u8857\u673a\u5c4f\u5e55\u6392\u884c\u699c\u6bcf\u9875\u663e\u793a "
                                + seconds + " \u79d2\u3002"),
                false);
        return seconds;
    }

    private static int setLeaderboardInterval(
            CommandSourceStack source,
            int seconds
    ) {
        if (!storageReady(source)) return 0;
        manager(source.getServer()).setLeaderboardPageSeconds(
                source.getServer(),
                seconds);
        source.sendSuccess(
                () -> Component.literal(
                        "\u8857\u673a\u5c4f\u5e55\u6392\u884c\u699c\u6bcf\u9875\u663e\u793a\u65f6\u95f4\u5df2\u8bbe\u7f6e\u4e3a "
                                + seconds + " \u79d2\u3002"),
                true);
        return seconds;
    }

    /** Shared, indexed ROM store for cartridge uploads; no second mutable cache. */
    static ServerRomLibrary cartridgeLibrary(MinecraftServer server) {
        if (!manager(server).initializeStorage(server)) throw new java.io.UncheckedIOException(
                "FC storage initialization failed; see server log and restart after repair",
                new java.io.IOException("FC storage initialization failed", manager(server).storageFailure));
        return manager(server).library(server);
    }

    /** Legacy TV entry is informational; physical appliance buttons own start and lending. */
    public static void startHomeConsole(ServerPlayer player, BlockPos tvPos, String romSha) {
        player.displayClientMessage(Component.literal("请按主机电源开机，再点击主机上的 P1/P2 手柄领取。"),true);
    }

    public static boolean powerHomeConsole(ServerPlayer player,cn.piq.fcarcade.home.HomeConsoleBlockEntity console,boolean gun){
        if(console!=null&&(!cn.piq.fcarcade.home.HomeSyncPolicy.runnable(console.synchronizationMode())||console.synchronizationMode()==CabinetSyncMode.LOCAL_SYNC&&!CabinetHostingConfig.localAllowed()||console.synchronizationMode()==CabinetSyncMode.MEDIA&&!CabinetHostingConfig.playerAllowed())){
            player.displayClientMessage(Component.literal(cn.piq.fcarcade.home.HomeSyncPolicy.unavailable(console.synchronizationMode())),true);return false;
        }
        if(!storageReady(player)||console==null||console.tvPos()==null||!player.serverLevel().hasChunkAt(console.tvPos())||!(player.serverLevel().getBlockEntity(console.tvPos()) instanceof cn.piq.fcarcade.home.HomeTvBlockEntity tv)
                ||!tv.powered()||!cn.piq.fcarcade.home.HomeZapperService.facts(player,console,tv)||validStructure(player,tv.getBlockPos())==null)return false;
        Manager m=manager(player.getServer());if(m.homeSaveBusy.contains(player.getUUID()))return false;SessionKey key=new SessionKey(player.level().dimension(),tv.getBlockPos(),ArcadeMode.LOCKSTEP);
        if(HomeLaunchServer.busy(player.getServer(),launchKey(player.serverLevel(),console)))return false;
        Session existing=m.sessions.get(key);if(existing!=null)return existing.homeRuntime!=null&&m.validHome(player.getServer(),existing);
        if(m.sessions.values().stream().anyMatch(s->s.homeRuntime!=null&&s.homeRuntime.host().equals(player.getUUID())))return false;
        String rom=HomeHardware.selectedRom(player.serverLevel(),tv.getBlockPos());if(m.library(player.getServer()).find(rom)==null)return false;
        try{m.coreVariant(player.getServer(),rom,gun);}catch(IllegalArgumentException incompatible){player.displayClientMessage(Component.literal(incompatible.getMessage()),false);return false;}
        RomSaveMode mode=console.cartridgeSaveMode(m.library(player.getServer()).saveMode(rom));
        if(console.netplayExperimental()&&console.synchronizationMode()!=CabinetSyncMode.LOCAL_SYNC){
            player.displayClientMessage(Component.literal("请重新选择 Netplay 模式。"),false);return false;
        }
        if(mode==RomSaveMode.PLAYER){m.openHomeSaveSlots(player,console,tv,rom,gun);return false;}
        String cardKey=m.cartridgeSaveKey(player.getServer(),console,rom,gun);
        if(mode==RomSaveMode.MACHINE&&m.saves(player.getServer()).exists(cardKey,rom)){m.openHomeSaveSlots(player,console,tv,rom,gun);return false;}
        m.homeSaveRequests.remove(player.getUUID());
        m.prepareHome(player,console,tv,rom,gun,mode,mode==RomSaveMode.NONE?"":cardKey,"卡带进度",gun?2:m.library(player.getServer()).homeMaxPlayers(rom),false);
        return false; // A prepared launch is not yet a powered/ready machine.
    }
    public static int personalRetentionDays(MinecraftServer server){return manager(server).settings(server).saveRetentionDays();}
    public static boolean setPersonalRetentionDays(ServerPlayer player,int expected,int days){
        if(!Manager.current(player)||!player.hasPermissions(2)||days<0||days>3650)return false;
        var m=manager(player.getServer());var old=m.settings(player.getServer());if(old.saveRetentionDays()!=expected)return false;
        m.settingsStore(player.getServer()).set(new ArcadeGlobalSettings(old.viewDistance(),old.audioDistance(),old.audioVolumePercent(),days));
        return true;
    }
    public static void refreshCartridgeProgress(MinecraftServer server,net.minecraft.world.item.ItemStack stack){
        if(server==null||!server.isSameThread()||!cn.piq.fcarcade.home.FcCartridgeData.isPlayable(stack)||!cn.piq.fcarcade.home.FcCartridgeData.supportsAssembly(stack))return;
        var m=manager(server);if(!m.initializeStorage(server))return;
        String rom=cn.piq.fcarcade.home.FcCartridgeData.romSha(stack);
        if(cn.piq.fcarcade.home.FcCartridgeData.storedSaveMode(stack)<0)cn.piq.fcarcade.home.FcCartridgeData.setSaveMode(stack,rom.isEmpty()?RomSaveMode.NONE:m.library(server).saveMode(rom));
        boolean saved=m.cardProgressExists(server,cn.piq.fcarcade.home.FcCartridgeData.id(stack),rom);
        if(saved!=cn.piq.fcarcade.home.FcCartridgeData.hasSavedProgress(stack))cn.piq.fcarcade.home.FcCartridgeData.setSavedProgress(stack,saved);
    }
    public static void homeSaveAction(ServerPlayer player,cn.piq.fcarcade.ArcadeHomeSaveActionPayload payload){
        Manager m=MANAGERS.get(player.getServer());if(m!=null&&Manager.current(player))m.homeSaveAction(player,payload);
    }
    public static boolean homeSavePending(ServerPlayer p,cn.piq.fcarcade.home.HomeConsoleBlockEntity c){
        if(p!=null&&c!=null&&HomeLaunchServer.pending(p.getServer(),launchKey(p.serverLevel(),c)))return true;
        Manager m=p==null?null:MANAGERS.get(p.getServer());HomeSaveRequest r=m==null?null:m.homeSaveRequests.get(p.getUUID());
        return r!=null&&c!=null&&c.hardwareId().equals(r.consoleId())&&c.getBlockPos().equals(r.consolePos())&&m.validHomeSave(p,r,r.intent().token());
    }
    public static boolean homeRunning(ServerLevel level,cn.piq.fcarcade.home.HomeConsoleBlockEntity console){
        Manager m=MANAGERS.get(level.getServer());Session s=m==null||console==null||console.tvPos()==null?null:m.sessions.get(new SessionKey(level.dimension(),console.tvPos(),ArcadeMode.LOCKSTEP));
        return s!=null&&s.homeRuntime!=null&&console.hardwareId().equals(s.homeConsoleId)&&m.validHome(level.getServer(),s);
    }
    /** Configuration cannot race an active/closing runtime or any player's pending save-slot start. */
    public static boolean homeConfigurationBusy(ServerLevel level,cn.piq.fcarcade.home.HomeConsoleBlockEntity console){
        if(level==null||console==null||console.getLevel()!=level||!level.getServer().isSameThread())return true;
        if(HomeLaunchServer.busy(level.getServer(),launchKey(level,console)))return true;
        Manager m=MANAGERS.get(level.getServer());if(m==null)return false;
        if(m.sessions.values().stream().anyMatch(s->s.homeRuntime!=null&&console.hardwareId().equals(s.homeConsoleId)))return true;
        // The core can be terminated while its final snapshot still awaits the
        // server-thread reap. Keep this exact hardware busy until that completes.
        if(m.closingHosted.stream().anyMatch(s->s.hosted!=null&&console.hardwareId().equals(s.homeConsoleId)))return true;
        for(var entry:m.homeSaveRequests.entrySet()){
            HomeSaveRequest r=entry.getValue();ServerPlayer p=level.getServer().getPlayerList().getPlayer(entry.getKey());
            if(console.hardwareId().equals(r.consoleId())&&p!=null&&r.intent().valid(r.intent().token(),p.connection.getConnection(),level.getServer().getTickCount()))return true;
        }
        return false;
    }
    public static boolean isPoweredHomeSession(MinecraftServer server,long id){Manager m=MANAGERS.get(server);return m!=null&&m.sessions.values().stream().anyMatch(s->s.id==id&&s.homeRuntime!=null);}
    private static HomeLaunchServer.Key launchKey(ServerLevel level,cn.piq.fcarcade.home.HomeConsoleBlockEntity c){
        return new HomeLaunchServer.Key(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("piq_fc_arcade","nes"),level.dimension().location(),c.getBlockPos(),c.hardwareId());
    }
    public static void resetHomeConsole(ServerPlayer player,cn.piq.fcarcade.home.HomeConsoleBlockEntity console){
        Manager m=MANAGERS.get(player.getServer());Session s=m==null||console==null||console.tvPos()==null?null:m.sessions.get(new SessionKey(player.level().dimension(),console.tvPos(),ArcadeMode.LOCKSTEP));
        if(s==null||s.homeRuntime==null||!m.validHome(player.getServer(),s)||validStructure(player,s.key.anchor())==null||!console.hardwareId().equals(s.homeConsoleId))return;
        if(s.netplay!=null){player.displayClientMessage(Component.literal("Netplay 实验请关机后重新开机。"),true);return;}
        if(s.hosted!=null){if(!s.hosted.ready())return;s.hosted.reset();}
        m.restart(s);s.homeStartTick=player.getServer().getTickCount();s.homeRequests.clear();m.sync(player.getServer(),s,true);
        cn.piq.fcarcade.home.HomeInteractionSounds.play(player.serverLevel(),console.getBlockPos(),cn.piq.fcarcade.home.HomeInteractionSounds.Action.RESET);
    }
    public static void takeHomeController(ServerPlayer player,cn.piq.fcarcade.home.HomeConsoleBlockEntity console,int port){
        if(port<0||port>1)return;
        Manager m=MANAGERS.get(player.getServer());Session s=m==null||console==null||console.tvPos()==null?null:m.sessions.get(new SessionKey(player.level().dimension(),console.tvPos(),ArcadeMode.LOCKSTEP));
        if(s==null){HomeControllerService.borrowIdle(player,console,port);return;}
        if(s.homeRuntime==null||!m.validHome(player.getServer(),s)||!console.hardwareId().equals(s.homeConsoleId))return;
        HomeControllerService.borrowIdle(player,console,port);
        if(port>=s.maxPlayers){player.displayClientMessage(Component.literal("手柄可取下，但当前游戏不支持这个玩家端口。"),true);return;}
        if(s.homeRuntime.gunMode()&&port==1){player.displayClientMessage(Component.literal("光枪局的第二口留给光枪，请领取 P1 手柄；普通 P2 手柄不能同时连接。"),true);return;}
        m.requestHomeControl(player,s,port,null);
    }
    public static boolean takeHomeZapper(ServerPlayer player,cn.piq.fcarcade.home.HomeConsoleBlockEntity console,net.minecraft.world.item.ItemStack stack){
        Manager m=MANAGERS.get(player.getServer());Session s=m==null||console==null||console.tvPos()==null?null:m.sessions.get(new SessionKey(player.level().dimension(),console.tvPos(),ArcadeMode.LOCKSTEP));
        if(s==null||s.homeRuntime==null||!m.validHome(player.getServer(),s)||!console.hardwareId().equals(s.homeConsoleId)||!s.variant.isZapper())return false;
        return m.requestHomeControl(player,s,1,stack);
    }
    public static void handleHomeInput(ServerPlayer player,cn.piq.fcarcade.ArcadeHomeInputPayload payload){
        Manager m=MANAGERS.get(player.getServer());if(m!=null)m.handleHomeInput(player,payload);
    }
    public static void cancelHomeRequest(ServerPlayer player){Manager m=MANAGERS.get(player.getServer());if(m!=null)for(Session s:m.sessions.values()){
        var request=s.homeRequests.get(player.getUUID());
        if(request!=null&&request.gun()!=null&&request.connection()==player.connection.getConnection())s.homeRequests.remove(player.getUUID(),request);
    }}
    public static void homeReady(ServerPlayer player,cn.piq.fcarcade.ArcadeHomeReadyPayload payload){
        Manager m=MANAGERS.get(player.getServer());Session s=m==null?null:m.sessionFor(player,payload.sessionId());
        if(s!=null&&s.hosted==null&&s.homeRuntime!=null&&Manager.computeHost(s,player)&&m.validHome(player.getServer(),s)&&s.lockstep.epoch()==payload.epoch()){
            if(!s.homeReady&&s.homeLaunch!=null&&s.homeLaunch.stage()!=cn.piq.retro.flow.DeviceSessionFlow.Stage.READY&&!s.homeLaunch.ready())return;
            if(!s.homeReady&&s.netplay!=null&&s.saveMode!=RomSaveMode.NONE&&!cn.piq.fcarcade.netplay.NetplaySaveServer.activate(player.getServer(),s.id,player.connection.getConnection())){m.close(player.getServer(),s,false);return;}
            boolean first=!s.homeReady;s.homeReady=true;
            if(first){var c=HomeHardware.connectedConsole(player.serverLevel(),s.key.anchor());HomeControllerService.attachHeld(player,c);
                if(s.homeRuntime.gunMode()&&cn.piq.fcarcade.home.ZapperStandService.loanPlayer(c)==player)takeHomeZapper(player,c,player.getMainHandItem());}
        }
    }

    /** Legacy gun entry now only requests a socket on an already powered gun appliance. */
    public static boolean startHomeZapper(ServerPlayer player,cn.piq.fcarcade.home.HomeConsoleBlockEntity console,
            cn.piq.fcarcade.home.HomeTvBlockEntity tv,net.minecraft.world.item.ItemStack stack){
        return takeHomeZapper(player,console,stack);
    }
    public static void pauseHomeZapper(ServerPlayer player,long sessionId){
        Manager m=MANAGERS.get(player.getServer());Session s=m==null?null:m.sessionFor(player,sessionId);
        if(s!=null&&s.zapperBinding!=null){s.lockstep.clearZapper();if(s.hosted!=null)s.hosted.release(1);if(s.homeRuntime==null||s.homeRuntime.clearGunButtons(s.zapperBinding.lease())){s.lockstep.clearController(0);if(s.hosted!=null)s.hosted.release(0);}}
    }
    public static void handleZapperInput(ServerPlayer player,cn.piq.fcarcade.ArcadeZapperInputPayload payload){
        Manager m=MANAGERS.get(player.getServer());if(m!=null)m.handleZapper(player,payload);
    }

    /** Powered appliances release only the exact lender's socket, never the computation host. */
    public static void releaseHomeController(ServerPlayer player, long sessionId) {
        Manager manager = MANAGERS.get(player.getServer());
        Session session = manager == null ? null : manager.sessionFor(player, sessionId);
        // The physical controller service emits one final return/transfer result,
        // not the generic chat message with machine coordinates.
        if(session!=null&&session.homeRuntime!=null){
            var control=session.homeRuntime.player(player.getUUID());
            if(control!=null&&(!session.homeRuntime.gunMode()||control.port()==0))manager.detachHomeDevice(player,session,control.port(),control.lease());
            return;
        }
        if (session != null && session.homeConsole) manager.leave(player, false);
    }

    /** A gun is an independent port-two lease; returning it must never revoke a P1 controller. */
    public static void releaseHomeZapper(ServerPlayer player,long sessionId,UUID lease){
        Manager m=MANAGERS.get(player.getServer());Session s=m==null?null:m.sessionFor(player,sessionId);
        if(s!=null&&s.homeRuntime!=null&&s.homeRuntime.gunMode())m.detachHomeDevice(player,s,1,lease);
    }

    public static void pauseHomeController(ServerPlayer player, long sessionId, int port) {
        Manager manager = MANAGERS.get(player.getServer());
        Session session = manager == null ? null : manager.sessionFor(player, sessionId);
        if (session != null && session.homeConsole && session.lockstep != null
                && session.roster.roleOf(player.getUUID()) != null
                && session.roster.roleOf(player.getUUID()).controllerIndex() == port) {
            // A stowed formal P1 can still receive its owner's authorized gun keyboard.
            UUID gun=port==0&&session.homeRuntime!=null&&session.homeRuntime.gunMode()
                    ?cn.piq.fcarcade.home.HomeZapperService.keyboardLease(player,session.id,session.lockstep.epoch()):null;
            if(gun!=null&&session.homeRuntime.authorized(player.getUUID(),player.connection.getConnection(),gun,1))return;
            session.lockstep.clearController(port);if(port==0&&session.homeRuntime!=null)session.homeRuntime.clearButtonSource();
            if(session.hosted!=null)session.hosted.release(port);
        }
    }

    public static void stopHomeControllerSession(MinecraftServer server, long sessionId) {
        Manager manager = MANAGERS.get(server);
        if (manager == null) return;
        for (Session session : List.copyOf(manager.sessions.values()))
            if (session.homeConsole && session.id == sessionId) { manager.close(server, session); return; }
    }

    /** Hard unplug/physical removal: preserve the latest confirmed snapshot and stop only this TV. */
    public static void stopHomeConsole(MinecraftServer server, ResourceKey<Level> dimension, BlockPos tvPos) {
        if (server == null || tvPos == null) return;
        var level=server.getLevel(dimension);
        var console=level==null?null:HomeHardware.connectedConsole(level,tvPos);
        if(console!=null)HomeLaunchServer.cancel(server,launchKey(level,console),"实体电源取消开局，原进度保留");
        Manager home = MANAGERS.get(server);
        if (home != null) {
            SessionKey key = new SessionKey(dimension, tvPos, ArcadeMode.LOCKSTEP);
            home.homeSaveRequests.entrySet().removeIf(e->e.getValue().key().equals(key));
            Session current = home.sessions.get(key);
            if (current != null) home.close(server, current);
            home.idleLeaderboardDisplays.remove(key);
        }
        ArcadeOccupancyDisplay.remove(server, dimension, tvPos);
    }

    public static void interact(ServerPlayer player, BlockPos clickedPos) {
        if (cn.piq.fcarcade.cabinet.ServerCabinets.blocksNes(player.serverLevel(), clickedPos)) return;
        if (!storageReady(player)) return;
        ServerLevel level = player.serverLevel();
        if (!level.hasChunkAt(clickedPos) || !level.mayInteract(player, clickedPos)) return;
        if (!(level.getBlockState(clickedPos).getBlock() instanceof FcArcadeBlock)) return;
        if (level.getBlockState(clickedPos).getBlock() instanceof cn.piq.fcarcade.world.DualCabinetBlock
                && validStructure(player, clickedPos) == null) return;
        if (level.getBlockState(clickedPos).getBlock() instanceof RetroTvBlock) {
            startHomeConsole(player, clickedPos, HomeHardware.selectedRom(level, clickedPos));
            return;
        }
        ArcadeStructure structure = ArcadeStructure.resolve(level, clickedPos);
        SessionKey key = new SessionKey(
                level.dimension(),
                structure.anchor(),
                structure.mode());
        manager(player.getServer()).interact(player, key);
    }

    public static void openLibrary(ServerPlayer player, BlockPos clickedPos) {
        if (!storageReady(player)) return;
        if (player.serverLevel().hasChunkAt(clickedPos)
                && player.serverLevel().getBlockState(clickedPos).getBlock() instanceof RetroTvBlock) {
            HomeFeedback.show(player, "home_edit_cartridge");
            return;
        }
        if (!cn.piq.fcarcade.access.PlayerContentAccess.canBrowse(player)) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                    "message.piq_fc_arcade.library_op_only"));
            return;
        }
        ArcadeStructure structure = validStructure(player, clickedPos);
        if (structure == null) return;
        manager(player.getServer()).openLibrary(
                player,
                new SessionKey(
                        player.level().dimension(),
                        structure.anchor(),
                        structure.mode()));
    }

    public static void joinArcade(
            ServerPlayer player,
            BlockPos clickedPos,
            String romSha256
    ) {
        if (!storageReady(player)) return;
        if (romSha256 == null
                || !romSha256.matches(RomRepository.SHA256_PATTERN)) return;
        ArcadeStructure structure = validStructure(player, clickedPos);
        if (structure == null) return;
        manager(player.getServer()).join(
                player,
                new SessionKey(
                        player.level().dimension(),
                        structure.anchor(),
                        structure.mode()),
                romSha256,
                clickedPos);
    }

    public static void selectRom(ServerPlayer player, RomSelectRequestPayload payload) {
        if (!storageReady(player)) return;
        manager(player.getServer()).selectRom(
                player,
                payload.blockPos(),
                payload.romSha256());
    }

    public static void setRomPlayerMode(
            ServerPlayer player,
            RomPlayerModePayload payload
    ) {
        if (!storageReady(player)) return;
        manager(player.getServer()).setRomPlayerMode(player, payload);
    }

    public static void setRomSaveMode(
            ServerPlayer player,
            RomSaveModePayload payload
    ) {
        if (!storageReady(player)) return;
        manager(player.getServer()).setRomSaveMode(player, payload);
    }

    public static void deleteRom(
            ServerPlayer player,
            RomDeletePayload payload
    ) {
        if (!storageReady(player)) return;
        manager(player.getServer()).deleteRom(player, payload);
    }

    public static void openSettings(ServerPlayer player, BlockPos clickedPos) {
        if (!storageReady(player)) return;
        manager(player.getServer()).openSettings(player, clickedPos);
    }

    /** Save-manager read-only guard includes asynchronous hosted shutdown, for every save mode. */
    static boolean cartridgeSaveActive(MinecraftServer server,String key,String rom) {
        if(server==null||!server.isSameThread())return true;
        var m=manager(server);
        return m.sessions.values().stream().anyMatch(s->s.saveKey.equals(key)&&s.romSha256.equals(rom))
                ||m.closingHosted.stream().anyMatch(s->s.saveKey.equals(key)&&s.romSha256.equals(rom))
                ||cn.piq.fcarcade.netplay.NetplaySaveServer.busy(server,key);
    }
    public static void openSaveCatalog(
            ServerPlayer player,
            BlockPos clickedPos
    ) {
        if (!storageReady(player)) return;
        manager(player.getServer()).openSaveCatalog(player, clickedPos);
    }

    public static void handleSaveSlotAction(
            ServerPlayer player,
            ArcadeSaveSlotActionPayload payload
    ) {
        if (!storageReady(player)) return;
        manager(player.getServer()).handleSaveSlotAction(player, payload);
    }

    public static void deleteSave(
            ServerPlayer player,
            ArcadeSaveDeletePayload payload
    ) {
        if (!storageReady(player)) return;
        manager(player.getServer()).deleteSave(player, payload);
    }

    public static void renameRom(
            ServerPlayer player,
            RomRenamePayload payload
    ) {
        if (!storageReady(player)) return;
        manager(player.getServer()).renameRom(player, payload);
    }

    public static void updateSettings(
            ServerPlayer player,
            ArcadeSettingsUpdatePayload payload
    ) {
        if (!storageReady(player)) return;
        manager(player.getServer()).updateSettings(player, payload);
    }

    public static void setArcadeLeaderboardEnabled(
            ServerPlayer player,
            ArcadeLeaderboardTogglePayload payload
    ) {
        if (!storageReady(player)) return;
        manager(player.getServer()).setArcadeLeaderboardEnabled(
                player,
                payload);
    }

    public static void updateLeaderboardPanel(
            ServerPlayer player,
            LeaderboardPanelConfigUpdatePayload payload
    ) {
        if (!player.hasPermissions(2)) return;
        if (player.level().getBlockEntity(payload.blockPos())
                instanceof LeaderboardPanelBlockEntity panel) {
            panel.apply(payload);
            player.sendSystemMessage(Component.translatable(
                    "message.piq_fc_arcade.leaderboard_panel_saved"));
        }
    }

    public static void beginRomUpload(
            ServerPlayer player,
            RomUploadStartPayload payload
    ) {
        if (!storageReady(player)) return;
        manager(player.getServer()).beginUpload(player, payload);
    }

    public static void acceptRomUploadChunk(
            ServerPlayer player,
            RomUploadChunkPayload payload
    ) {
        if (!storageReady(player)) return;
        manager(player.getServer()).acceptUploadChunk(player, payload);
    }

    public static void requestRomDownload(ServerPlayer player, String sha256) {
        if (!storageReady(player)) return;
        manager(player.getServer()).requestDownload(player, sha256);
    }

    public static void handleRomReady(ServerPlayer player, RomReadyPayload payload) {
        if (!storageReady(player)) return;
        manager(player.getServer()).handleRomReady(player, payload);
    }

    public static void handleInput(ServerPlayer player, ArcadeInputPayload payload) {
        manager(player.getServer()).handleInput(player, payload);
    }

    public static void handleScore(ServerPlayer player, ArcadeScorePayload payload) {
        manager(player.getServer()).handleScore(player, payload);
    }

    public static void handleDigest(ServerPlayer player, ArcadeDigestPayload payload) {
        manager(player.getServer()).handleDigest(player, payload);
    }

    public static void handleSnapshot(
            ServerPlayer player,
            ArcadeSnapshotUploadPayload payload
    ) {
        manager(player.getServer()).handleSnapshot(player, payload);
    }

    public static void requestReset(ServerPlayer player, ArcadeResetPayload payload) {
        manager(player.getServer()).requestReset(player, payload);
    }

    public static void decideExit(
            ServerPlayer player,
            ArcadeExitDecisionPayload payload
    ) {
        manager(player.getServer()).decideExit(player, payload);
    }

    public static void decideResume(
            ServerPlayer player,
            ArcadeResumeDecisionPayload payload
    ) {
        if (!storageReady(player)) return;
        manager(player.getServer()).decideResume(player, payload);
    }

    public static void setMultiplayerEnabled(
            ServerPlayer player,
            ArcadeMultiplayerResponsePayload payload
    ) {
        manager(player.getServer()).setMultiplayerEnabled(player, payload);
    }

    public static void decideJoin(
            ServerPlayer player,
            ArcadeJoinDecisionPayload payload
    ) {
        manager(player.getServer()).decideJoin(player, payload);
    }

    private static ArcadeStructure validStructure(
            ServerPlayer player,
            BlockPos clickedPos
    ) {
        if (!Manager.current(player) || !player.isAlive() || player.isSpectator() || clickedPos == null) return null;
        ServerLevel level = player.serverLevel();
        if (cn.piq.fcarcade.cabinet.ServerCabinets.blocksNes(level, clickedPos)) return null;
        if (!level.hasChunkAt(clickedPos) || !level.mayInteract(player, clickedPos)
                || !(level.getBlockState(clickedPos).getBlock() instanceof FcArcadeBlock)) {
            return null;
        }
        double distance = player.distanceToSqr(
                clickedPos.getX() + 0.5D,
                clickedPos.getY() + 0.5D,
                clickedPos.getZ() + 0.5D);
        if (level.getBlockState(clickedPos).getBlock() instanceof RetroTvBlock) {
            var console = HomeHardware.connectedConsole(level, clickedPos);
            if (console == null || !HomeHardware.validPlayback(level, clickedPos)
                    || !level.mayInteract(player, console.getBlockPos())) return null;
            BlockPos consolePos = console.getBlockPos();
            distance = Math.min(distance, player.distanceToSqr(consolePos.getX() + 0.5D,
                    consolePos.getY() + 0.5D, consolePos.getZ() + 0.5D));
        }
        if (distance > MAX_DISTANCE_SQUARED) {
            return null;
        }
        if (level.getBlockState(clickedPos).getBlock() instanceof cn.piq.fcarcade.world.DualCabinetBlock
                && !cn.piq.fcarcade.world.DualCabinetStructure.complete(level, clickedPos)) return null;
        return ArcadeStructure.resolve(level, clickedPos);
    }

    /** Read-only cabinet query: no storage initialization, roster changes, or game interruption. */
    public static boolean hasCabinetSession(MinecraftServer server, ResourceKey<Level> dimension, BlockPos anchor) {
        Manager manager = MANAGERS.get(server);
        return manager != null && manager.sessions.keySet().stream()
                .anyMatch(key -> key.dimension().equals(dimension) && key.anchor().equals(anchor));
    }

    public static boolean hasPlayerCabinetSession(ServerPlayer player) {
        Manager manager = MANAGERS.get(player.getServer());
        return manager != null && manager.memberships.containsKey(player.getUUID());
    }

    private static double controllerDistanceSquared(ServerPlayer player, BlockPos displayPos) {
        double distance = player.distanceToSqr(displayPos.getX() + 0.5D,
                displayPos.getY() + 0.5D, displayPos.getZ() + 0.5D);
        ServerLevel level = player.serverLevel();
        if (level.hasChunkAt(displayPos)
                && level.getBlockState(displayPos).getBlock() instanceof RetroTvBlock) {
            var console = HomeHardware.connectedConsole(level, displayPos);
            if (console != null) {
                BlockPos control = console.getBlockPos();
                distance = Math.min(distance, player.distanceToSqr(control.getX() + 0.5D,
                        control.getY() + 0.5D, control.getZ() + 0.5D));
            }
        }
        return distance;
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        Manager manager = manager(event.getServer());
        if (manager.initializeStorage(event.getServer())) manager.tick(event.getServer());
    }

    private static boolean storageReady(ServerPlayer player) {
        Manager manager = manager(player.getServer());
        if (manager.initializeStorage(player.getServer())) return true;
        long now = player.getServer().getTickCount();
        if (now >= manager.storageNoticeAfter.getOrDefault(player.getUUID(), 0L)) {
            manager.storageNoticeAfter.put(player.getUUID(), now + 100);
            player.sendSystemMessage(Component.translatable("message.piq_fc_arcade.storage_unavailable"));
        }
        return false;
    }

    private static boolean storageReady(CommandSourceStack source) {
        if (manager(source.getServer()).initializeStorage(source.getServer())) return true;
        source.sendFailure(Component.translatable("message.piq_fc_arcade.storage_unavailable"));
        return false;
    }

    private static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            manager(player.getServer()).disconnect(player);
        }
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        Manager manager = MANAGERS.remove(event.getServer());
        try { if (manager != null) manager.saveAll(event.getServer()); }
        finally {
            HomeControllerService.stopped(event.getServer());
            cn.piq.fcarcade.home.HomeZapperService.stopped(event.getServer());
            ArcadeOccupancyDisplay.stopped(event.getServer());
        }
    }

    private static Manager manager(MinecraftServer server) {
        return MANAGERS.computeIfAbsent(server, ignored -> new Manager());
    }

    private record SessionKey(
            ResourceKey<Level> dimension,
            BlockPos anchor,
            ArcadeMode mode
    ) {
        private SessionKey {
            anchor = anchor.immutable();
        }
    }

    private static final class Session {
        private final long id;
        private boolean homeConsole;
        private boolean playerMedia;
        private cn.piq.fcarcade.netplay.NetplayRelay<net.minecraft.network.Connection> netplay;
        private boolean netplayJniTrial;
        private UUID mediaSource;
        private UUID mediaToken=UUID.randomUUID();
        private cn.piq.fcarcade.home.HomeRuntimeAuthority<net.minecraft.network.Connection> homeRuntime;
        private BlockPos homeConsolePos;
        private UUID homeConsoleId,homeTvId,homeLinkId,homeCardId;
        private final Map<UUID,HomeRequest> homeRequests=new HashMap<>();
        private boolean homeDecisionBusy;
        private boolean homeReady;
        private HomeLaunchServer.Handle homeLaunch;
        private net.minecraft.network.Connection homeLaunchHost;
        private int homeStartTick;
        private cn.piq.fcarcade.session.NesCoreVariant variant=cn.piq.fcarcade.session.NesCoreVariant.LIBRETRO_V1;
        private cn.piq.fcarcade.home.ZapperBinding zapperBinding;
        private final SessionKey key;
        private final SessionRoster roster = new SessionRoster();
        private final Set<UUID> viewers = new HashSet<>();
        private final Set<UUID> pendingViewers = new HashSet<>();
        private final Set<UUID> waitingRomViewers = new HashSet<>();
        private final String romSha256;
        private final int maxPlayers;
        private final RomSaveMode saveMode;
        private final PersonalSaveLease personalSave;
        private boolean lastSaveFailed;
        private final String saveKey;
        private final String saveSlotName;
        private final int savePlayers;
        private final Set<UUID> pendingApplicants = new HashSet<>();
        private final Map<UUID, ArcadeRole> disconnectedRoles = new HashMap<>();
        private final Map<UUID, Integer> disconnectedUntil = new HashMap<>();
        private final Set<UUID> pendingControllerSync = new HashSet<>();
        private final Map<UUID, Integer> resyncTicks = new HashMap<>();
        private final RoadRaceRoundState scoreRound = new RoadRaceRoundState();
        private boolean multiplayerEnabled;
        private boolean multiplayerChosen;
        private final LockstepState lockstep;
        private final LockstepDigestTracker digests;
        private final LockstepTimeline timeline;
        private byte[] snapshot;
        private FcHomeHostedRun hosted;
        private boolean hostedFinalPersist;
        private final int[] hostedRecipientCursor=new int[2];
        private int snapshotEpoch;
        private long snapshotFrame;
        private boolean snapshotRequested;
        private int snapshotRequestTick;
        private int lastSnapshotReceivedTick;

        private Session(
                long id,
                SessionKey key,
                String romSha256,
                int maxPlayers,
                RomSaveMode saveMode,
                UUID saveOwner,
                String saveKey,
                String saveSlotName,
                int savePlayers
        ) {
            this.id = id;
            this.key = key;
            this.romSha256 = romSha256;
            this.maxPlayers = maxPlayers;
            this.saveMode = saveMode;
            this.personalSave = saveMode == RomSaveMode.PLAYER
                    ? new PersonalSaveLease(saveOwner) : null;
            this.saveKey = saveKey;
            this.saveSlotName = saveSlotName;
            this.savePlayers = savePlayers;
            this.lockstep = key.mode() == ArcadeMode.LOCKSTEP
                    ? new LockstepState()
                    : null;
            this.digests = key.mode() == ArcadeMode.LOCKSTEP
                    ? new LockstepDigestTracker()
                    : null;
            this.timeline = key.mode() == ArcadeMode.LOCKSTEP
                    ? new LockstepTimeline()
                    : null;
        }

        private Set<UUID> allPlayers() {
            Set<UUID> players = new LinkedHashSet<>(roster.playerIds());
            if(homeRuntime!=null)players.add(homeRuntime.host());
            players.addAll(viewers);
            return players;
        }
    }

    private record HomeRequest(UUID token,UUID player,net.minecraft.network.Connection connection,int port,int deadline,net.minecraft.world.item.ItemStack gun){}
    private record HomeSaveRequest(cn.piq.fcarcade.home.HomeSaveIntent<net.minecraft.network.Connection> intent,SessionKey key,BlockPos consolePos,
                                   UUID consoleId,UUID tvId,UUID linkId,String rom,boolean gun,Map<Integer,ArcadeSaveStore.SaveInfo> slots,UUID cardId,RomSaveMode mode){}

    private static final class Manager {
        private final InteractionTransaction legacyInteractions = new InteractionTransaction();

        /** Permission belongs to this live actor and these endpoints, not to a previous GUI prompt. */
        private void legacyTransaction(ServerPlayer player, BlockPos clicked, Runnable commit) {
            if (legacyInteractions.active()) return;
            ArcadeStructure structure = validStructure(player, clicked);
            if (structure == null) return;
            ServerLevel level = player.serverLevel();
            var source = player.connection.getConnection();
            BlockPos anchor = structure.anchor();
            var clickedState = level.getBlockState(clicked);
            var anchorState = level.getBlockState(anchor);
            var clickedEntity = level.getBlockEntity(clicked);
            var anchorEntity = level.getBlockEntity(anchor);
            var clickedIdentity = legacyIdentity(clickedEntity);
            var anchorIdentity = legacyIdentity(anchorEntity);
            SessionKey key = new SessionKey(level.dimension(), anchor, structure.mode());
            String rom = selectedRom(player.getServer(), key);
            Session before = sessions.get(key);
            int epoch = before == null || before.lockstep == null ? 0 : before.lockstep.epoch();
            java.util.function.BooleanSupplier facts = () -> current(player) && player.isAlive() && !player.isSpectator()
                    && player.serverLevel() == level && player.connection.getConnection() == source
                    && level.hasChunkAt(clicked) && level.hasChunkAt(anchor)
                    && level.getBlockState(clicked) == clickedState && level.getBlockState(anchor) == anchorState
                    && level.getBlockEntity(clicked) == clickedEntity && level.getBlockEntity(anchor) == anchorEntity
                    && (clickedEntity == null || !clickedEntity.isRemoved()) && (anchorEntity == null || !anchorEntity.isRemoved())
                    && java.util.Objects.equals(clickedIdentity, legacyIdentity(clickedEntity))
                    && java.util.Objects.equals(anchorIdentity, legacyIdentity(anchorEntity))
                    && structure.equals(validStructure(player, clicked)) && level.mayInteract(player, anchor)
                    && rom.equals(selectedRom(player.getServer(), key)) && sessions.get(key) == before
                    && (before == null || before.lockstep == null || before.lockstep.epoch() == epoch);
            legacyInteractions.run(facts, () -> legacyPermission(player, clicked) && facts.getAsBoolean()
                    && (clicked.equals(anchor) || legacyPermission(player, anchor)), commit);
        }

        private static UUID legacyIdentity(net.minecraft.world.level.block.entity.BlockEntity entity) {
            if (entity instanceof cn.piq.fcarcade.world.LegacyFcArcadeBlockEntity cabinet) return cabinet.cabinetId();
            if (entity instanceof cn.piq.fcarcade.home.HomeConsoleBlockEntity console) return console.hardwareId();
            if (entity instanceof cn.piq.fcarcade.home.HomeTvBlockEntity television) return television.hardwareId();
            return null; // Original non-BE blocks have no persistent hardware identifier.
        }

        private boolean legacyPermission(ServerPlayer player, BlockPos pos) {
            try {
                var hit = new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(pos),
                        net.minecraft.core.Direction.UP, pos, false);
                var event = NeoForge.EVENT_BUS.post(new net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock(
                        player, net.minecraft.world.InteractionHand.MAIN_HAND, pos, hit));
                return !event.isCanceled() && event.getUseBlock() != net.neoforged.neoforge.common.util.TriState.FALSE
                        && event.getUseItem() != net.neoforged.neoforge.common.util.TriState.FALSE;
            } catch (RuntimeException | LinkageError failure) {
                FcArcadeMod.LOGGER.warn("[PIQ FC] Legacy arcade permission check failed closed", failure);
                return false;
            }
        }

        private final Map<UUID,HomeSaveRequest> homeSaveRequests=new HashMap<>();
        private final List<Session> closingHosted=new java.util.ArrayList<>();
        private static boolean hostedSelected(cn.piq.fcarcade.home.HomeConsoleBlockEntity c){return c.synchronizationMode()==CabinetSyncMode.SERVER_MEDIA;}
        private final Set<UUID> homeSaveBusy=new HashSet<>();
        private static String homeSaveKey(boolean gun,String key){return gun?"core|"+cn.piq.fcarcade.session.NesCoreVariant.ZAPPER_V1.stateNamespace()+"|"+key:key;}
        private cn.piq.fcarcade.session.NesCoreVariant coreVariant(MinecraftServer server,String rom,boolean gun){
            RomDescriptor descriptor=library(server).find(rom);
            if(descriptor==null)throw new IllegalArgumentException("会话指定的 ROM 已不可用。");
            return cn.piq.fcarcade.session.NesCoreVariant.forRom(descriptor.header(),gun);
        }
        private String homeSaveKey(MinecraftServer server,cn.piq.fcarcade.home.HomeConsoleBlockEntity console,String rom,boolean gun,String key){
            if(console.netplayExperimental())return cn.piq.fcarcade.netplay.FcNetplaySaves.key(gun,console.netplayJniTrial(),key);
            return coreVariant(server,rom,gun).saveKey(key);
        }
        private String cartridgeSaveKey(MinecraftServer server,cn.piq.fcarcade.home.HomeConsoleBlockEntity console,String rom,boolean gun){
            // All three public FC runners serialize the same selected core; only core ABI separates card progress.
            return homeSaveKey(server,console,rom,gun,cn.piq.fcarcade.home.CartridgeSaveIdentity.key(cn.piq.fcarcade.home.FcCartridgeData.id(console.insertedCartridge())));
        }
        private RomSaveMode cartridgeMode(cn.piq.fcarcade.home.HomeConsoleBlockEntity c){return cn.piq.fcarcade.home.FcCartridgeData.saveMode(c.insertedCartridge());}
        private boolean cardActive(UUID id){
            if(id!=null&&cn.piq.fcarcade.netplay.NetplaySaveServer.busy(cn.piq.fcarcade.netplay.FcNetplaySaves.key(false,true,cn.piq.fcarcade.home.CartridgeSaveIdentity.key(id))))return true;
            if(id!=null)for(boolean gun:new boolean[]{false,true})if(cn.piq.fcarcade.netplay.NetplaySaveServer.busy(cn.piq.fcarcade.netplay.FcNetplaySaves.key(gun,cn.piq.fcarcade.home.CartridgeSaveIdentity.key(id))))return true;
            return java.util.stream.Stream.concat(sessions.values().stream(),closingHosted.stream()).anyMatch(s->id!=null&&id.equals(s.homeCardId));
        }
        private boolean cardProgressExists(MinecraftServer server,UUID id,String rom){
            if(id==null||rom==null||rom.isEmpty())return false;
            if(saves(server).exists(cn.piq.fcarcade.netplay.FcNetplaySaves.key(false,true,cn.piq.fcarcade.home.CartridgeSaveIdentity.key(id)),rom))return true;
            for(var variant:cn.piq.fcarcade.session.NesCoreVariant.values())if(saves(server).exists(variant.saveKey(cn.piq.fcarcade.home.CartridgeSaveIdentity.key(id)),rom))return true;
            for(boolean gun:new boolean[]{false,true})if(saves(server).exists(cn.piq.fcarcade.netplay.FcNetplaySaves.key(gun,cn.piq.fcarcade.home.CartridgeSaveIdentity.key(id)),rom))return true;
            return false;
        }
        private boolean homeAccepts(MinecraftServer server,cn.piq.fcarcade.home.HomeConsoleBlockEntity c,String rom,boolean gun,byte[] bytes){
            return c.netplayExperimental()?cn.piq.fcarcade.netplay.FcNetplaySaves.accepts(gun,c.netplayJniTrial(),rom,bytes):coreVariant(server,rom,gun).acceptsPersistentStateHeader(bytes,rom);
        }
        /** Legacy FC slot editor feeds the same pre-core join/ready coordinator as addons.
         * Slot editing/storage formats remain FC-owned; merely accepting a selection never writes. */
        private void prepareHome(ServerPlayer p,cn.piq.fcarcade.home.HomeConsoleBlockEntity c,cn.piq.fcarcade.home.HomeTvBlockEntity tv,String rom,boolean gun,
                                 RomSaveMode mode,String saveKey,String name,int players,boolean resume){
            var server=p.getServer();var card=c.insertedCartridge().copy();var consoleId=c.hardwareId();var tvId=tv.hardwareId();var link=c.linkId();
            var syncMode=c.synchronizationMode();boolean netplay=c.netplayExperimental(),jni=c.netplayJniTrial();
            int max=gun?2:library(server).homeMaxPlayers(rom);
            var expected=mode==RomSaveMode.NONE?null:saves(server).list().stream().filter(i->i.saveKey().equals(saveKey)).findFirst().orElse(null);
            String cardTitle=cn.piq.fcarcade.home.FcCartridgeData.title(card);
            String label=cardTitle.isBlank()?library(server).displayName(rom):cardTitle;
            final String title=label.length()>128?label.substring(0,128):label;
            HomeLaunchServer.start(p,launchKey(p.serverLevel(),c),new HomeLaunchServer.Adapter<Boolean>(){
                Session created;
                public HomeLaunchServer.Definition definition(){return new HomeLaunchServer.Definition("FC",title,max,0,false,"");}
                public boolean valid(){
                    if(!current(p)||!tv.powered()||!cn.piq.fcarcade.home.HomeZapperService.facts(p,c,tv)
                            ||!consoleId.equals(c.hardwareId())||!tvId.equals(tv.hardwareId())||!Objects.equals(link,c.linkId())
                            ||!net.minecraft.world.item.ItemStack.isSameItemSameComponents(card,c.insertedCartridge())||c.insertedCartridge().getCount()!=1
                            ||cartridgeMode(c)!=mode||c.synchronizationMode()!=syncMode||c.netplayExperimental()!=netplay||c.netplayJniTrial()!=jni
                            ||!rom.equals(HomeHardware.selectedRom(p.serverLevel(),tv.getBlockPos()))||library(server).find(rom)==null
                            ||(gun?2:library(server).homeMaxPlayers(rom))!=max)return false;
                    var actual=sessions.get(new SessionKey(p.level().dimension(),tv.getBlockPos(),ArcadeMode.LOCKSTEP));
                    if(created!=null)return actual==created;
                    if(actual!=null||cardActive(cn.piq.fcarcade.home.FcCartridgeData.id(card)))return false;
                    return mode==RomSaveMode.NONE||Objects.equals(expected,saves(server).list().stream().filter(i->i.saveKey().equals(saveKey)).findFirst().orElse(null));
                }
                public void list(java.util.function.Consumer<List<HomeLaunchNetwork.Row>> success,java.util.function.Consumer<String> failure){failure.accept("FC 存档已在前一步选择");}
                public void select(HomeLaunchNetwork.Choice choice,java.util.function.Consumer<Boolean> success,java.util.function.Consumer<String> failure){success.accept(Boolean.TRUE);}
                public void load(HomeLaunchServer.Launch<Boolean> launch,HomeLaunchServer.Handle handle){
                    if(!createHome(p,c,tv,rom,gun,mode,saveKey,name,players,resume)){handle.fail("FC 开机条件已变化，原进度保留");return;}
                    created=sessions.get(new SessionKey(p.level().dimension(),tv.getBlockPos(),ArcadeMode.LOCKSTEP));
                    created.homeLaunch=handle;created.homeLaunchHost=p.connection.getConnection();created.multiplayerEnabled=launch.allowSecondPort();created.multiplayerChosen=true;
                }
                public void cancelled(String reason){if(created!=null&&sessions.get(created.key)==created)close(server,created,created.homeReady);}
            });
        }
        private boolean createHome(ServerPlayer p,cn.piq.fcarcade.home.HomeConsoleBlockEntity c,cn.piq.fcarcade.home.HomeTvBlockEntity tv,String rom,boolean gun,
                                   RomSaveMode mode,String saveKey,String name,int players,boolean resume){
            if(c.netplayExperimental()&&c.synchronizationMode()!=CabinetSyncMode.LOCAL_SYNC)return false;
            if(mode!=RomSaveMode.NONE&&cn.piq.fcarcade.netplay.NetplaySaveServer.busy(p.getServer(),saveKey)){p.displayClientMessage(Component.literal("上局仍在保存，请稍后开机。"),true);return false;}
            if(!cn.piq.fcarcade.home.HomeSyncPolicy.runnable(c.synchronizationMode())||c.synchronizationMode()==CabinetSyncMode.LOCAL_SYNC&&!CabinetHostingConfig.localAllowed()||c.synchronizationMode()==CabinetSyncMode.MEDIA&&!CabinetHostingConfig.playerAllowed())return false;
            if(hostedSelected(c)&&(!CabinetHostingConfig.SPEC.isLoaded()||!CabinetHostingConfig.ENABLED.get())){p.displayClientMessage(Component.literal("服主未启用服务端托管（piq-sync-server.toml）。"),false);return false;}
            SessionKey key=new SessionKey(p.level().dimension(),tv.getBlockPos(),ArcadeMode.LOCKSTEP);
            if(!current(p)||!tv.powered()||!cn.piq.fcarcade.home.HomeZapperService.facts(p,c,tv)||!rom.equals(HomeHardware.selectedRom(p.serverLevel(),tv.getBlockPos()))
                    ||sessions.containsKey(key)||mode!=RomSaveMode.NONE&&closingHosted.stream().anyMatch(s->s.saveKey.equals(saveKey))||mode==RomSaveMode.PLAYER&&personalSaveKeyActive(saveKey)||sessions.values().stream().anyMatch(s->s.homeRuntime!=null&&s.homeRuntime.host().equals(p.getUUID()))||cartridgeMode(c)!=mode)return false;
            UUID cardId=cn.piq.fcarcade.home.FcCartridgeData.id(c.insertedCartridge());
            if(cardId==null||cardActive(cardId))return false;
            if(mode==RomSaveMode.MACHINE&&!saveKey.equals(cartridgeSaveKey(p.getServer(),c,rom,gun)))return false;
            if(resume&&mode!=RomSaveMode.NONE&&!homeAccepts(p.getServer(),c,rom,gun,saves(p.getServer()).loadReadOnly(saveKey,rom))){
                p.displayClientMessage(Component.literal("存档无法读取，未开机或覆盖进度。"),false);return false;
            }
            if(mode==RomSaveMode.MACHINE&&resume)players=homeMachineSavePlayers(p,c,rom,gun,saveKey,key,players);
            Session s=new Session(nextSessionId++,key,rom,gun?2:library(p.getServer()).homeMaxPlayers(rom),mode,p.getUUID(),saveKey,name,players);
            s.homeConsole=true;s.playerMedia=c.synchronizationMode()==CabinetSyncMode.MEDIA;s.variant=coreVariant(p.getServer(),rom,gun);
            s.homeRuntime=new cn.piq.fcarcade.home.HomeRuntimeAuthority<>(p.getUUID(),p.connection.getConnection(),gun);
            if(c.netplayExperimental()){
                s.netplayJniTrial=c.netplayJniTrial();
                s.netplay=cn.piq.fcarcade.netplay.NetplayNetwork.room(s.id,p.connection.getConnection());
                var identity=cn.piq.fcarcade.netplay.FcNetplaySaves.identity(gun,s.netplayJniTrial,rom);
                var store=saves(p.getServer());var server=p.getServer();
                try{cn.piq.fcarcade.netplay.NetplaySaveServer.openPrepared(server,s.id,p.connection.getConnection(),s.netplay.grant(p.connection.getConnection(),true).id(),identity,saveKey,mode==RomSaveMode.NONE?null:()->new cn.piq.fcarcade.netplay.NetplaySaveServer.Storage(){
                    public byte[] read(){if(!resume)return null;byte[] state=store.loadReadOnly(saveKey,rom);if(state==null)throw new IllegalStateException("无法读取已有 Netplay 存档");return state;}
                    public void write(byte[] state){
                        store.save(saveKey,rom,state,s.saveSlotName,s.savePlayers);
                        // A personal new-game selection only retires its old ROM after the new save is durable.
                        if(mode==RomSaveMode.PLAYER){for(var old:store.list())if(old.saveKey().equals(saveKey)&&!old.romSha256().equals(rom))store.delete(saveKey,old.romSha256());store.played(p.getUUID(),Instant.now());}
                        server.execute(()->{if(mode==RomSaveMode.MACHINE)c.cartridgeSaved(cardId,rom,true);});
                    }
                    public void close(){}
                });}catch(RuntimeException failure){cn.piq.fcarcade.netplay.NetplayNetwork.retire(s.netplay);p.displayClientMessage(Component.literal("Netplay 保存未就绪："+failure.getMessage()),false);return false;}
            }
            s.homeConsolePos=c.getBlockPos().immutable();s.homeConsoleId=c.hardwareId();s.homeTvId=tv.hardwareId();s.homeLinkId=c.linkId();s.homeCardId=cardId;
            removeViewer(p.getServer(),p.getUUID(),key,false);sessions.put(key,s);restart(s);s.homeStartTick=p.getServer().getTickCount();
            byte[] saved=s.netplay==null&&resume&&mode!=RomSaveMode.NONE?loadState(p.getServer(),s):null;
            if(!hostedSelected(c)&&!s.playerMedia&&saved==null&&resume&&gun&&mode==RomSaveMode.MACHINE&&s.homeCardId==null&&!saves(p.getServer()).exists(saveKey,rom)){
                // Read-only compatibility with alpha28's Host-qualified gun-machine key. Never delete or overwrite it.
                byte[] old=saves(p.getServer()).loadReadOnly(homeSaveKey(true,"player|"+p.getUUID()+"|"+machineKey(key)),rom);
                if(s.variant.acceptsPersistentStateHeader(old,rom)){saved=old;s.snapshot=old.clone();s.snapshotEpoch=s.lockstep.epoch();s.snapshotFrame=0;s.lastSnapshotReceivedTick=p.getServer().getTickCount();}
            }
            if(hostedSelected(c)){
                var descriptor=library(p.getServer()).find(rom);
                HostedServerLimits.Lease admission=null;
                try{
                    if(descriptor==null)throw new IllegalStateException("服务端游戏已不可用");
                    var context=new ServerCoreContext(p.getServer().getServerDirectory(),p.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("piq-fc-home/hosted-saves"),p.getUUID(),c.hardwareId(),s.variant);
                    var factory=ServerCoreRegistry.find(CabinetBackends.NES);String unavailable=factory==null?"未注册 FC 服务器核心":factory.unavailableReason(context);
                    if(unavailable!=null)throw new IllegalStateException(unavailable);
                    admission=HostedServerLimits.tryAcquire(p.getServer());if(admission==null)throw new IllegalStateException("服务器托管名额已满或上一局仍在关闭");
                    s.hosted=new FcHomeHostedRun(context,descriptor.path(),rom,saved,admission);
                }catch(RuntimeException failure){if(admission!=null)admission.close();sessions.remove(key,s);s.homeRuntime.close();p.displayClientMessage(Component.literal("服务器托管未开机："+failure.getMessage()),false);return false;}
            }
            sync(p.getServer(),s,true);if(saved!=null&&s.hosted==null)FcNetwork.sendPersistentState(p,new ArcadePersistentStatePayload(s.id,s.lockstep.epoch(),saved));
            // The common coordinator already asked before any core or input grant.
            recordPlay(p);
            return true;
        }
        private int homeMachineSavePlayers(ServerPlayer p,cn.piq.fcarcade.home.HomeConsoleBlockEntity c,String rom,boolean gun,String saveKey,SessionKey key,int fallback){
            var all=saves(p.getServer()).list();
            for(var existing:all)if(saveKey.equals(existing.saveKey())&&rom.equals(existing.romSha256()))return existing.players();
            // Preserve the metadata of the same legacy gun-machine source that createHome may read.
            if(!cn.piq.fcarcade.home.CartridgeSaveIdentity.owns(saveKey,cn.piq.fcarcade.home.FcCartridgeData.id(c.insertedCartridge()))&&!hostedSelected(c)&&c.synchronizationMode()!=CabinetSyncMode.MEDIA&&gun&&!saves(p.getServer()).exists(saveKey,rom)){
                String legacy=homeSaveKey(true,"player|"+p.getUUID()+"|"+machineKey(key));
                for(var existing:all)if(legacy.equals(existing.saveKey())&&rom.equals(existing.romSha256()))return existing.players();
            }
            return fallback;
        }
        private void openHomeSaveSlots(ServerPlayer p,cn.piq.fcarcade.home.HomeConsoleBlockEntity c,cn.piq.fcarcade.home.HomeTvBlockEntity tv,String rom,boolean gun){
            RomSaveMode mode=cartridgeMode(c);boolean card=mode==RomSaveMode.MACHINE;
            UUID cardId=cn.piq.fcarcade.home.FcCartridgeData.id(c.insertedCartridge());
            if(mode==RomSaveMode.NONE||cardId==null||cardActive(cardId))return;
            if(!card&&!c.netplayExperimental()){
                if(coreVariant(p.getServer(),rom,gun)==cn.piq.fcarcade.session.NesCoreVariant.LEGACY)migratePlayerSavesToGlobalSlots(p.getServer(),p.getUUID());
                HomePersonalSaveMigration.copy(saves(p.getServer()),p.getUUID(),coreVariant(p.getServer(),rom,gun),this::personalSaveKeyActive);
            }
            var all=saves(p.getServer()).list();Map<Integer,ArcadeSaveStore.SaveInfo> shown=new HashMap<>();var rows=new java.util.ArrayList<ArcadeSaveSlotEntry>();
            for(int slot=1;slot<=3;slot++){String key=card?cartridgeSaveKey(p.getServer(),c,rom,gun):homeSaveKey(p.getServer(),c,rom,gun,playerSlotKey(p.getUUID(),slot));var found=card&&slot>1?null:all.stream().filter(i->key.equals(i.saveKey())&&(!card||rom.equals(i.romSha256()))).findFirst().orElse(null);
                String name=Component.translatable("screen.piq_fc_arcade.default_slot_name",slot).getString();if(found!=null)shown.put(slot,found);
                rows.add(found==null?new ArcadeSaveSlotEntry(slot,false,name,gun?2:library(p.getServer()).homeMaxPlayers(rom),0,"",""):new ArcadeSaveSlotEntry(slot,true,found.slotName().isBlank()?name:found.slotName(),found.players(),found.modifiedEpochMillis(),found.romSha256(),saveRomName(p.getServer(),found)));
            }
            var intent=new cn.piq.fcarcade.home.HomeSaveIntent<>(UUID.randomUUID(),p.connection.getConnection(),(long)p.getServer().getTickCount()+2400);
            var r=new HomeSaveRequest(intent,new SessionKey(p.level().dimension(),tv.getBlockPos(),ArcadeMode.LOCKSTEP),c.getBlockPos().immutable(),c.hardwareId(),tv.hardwareId(),c.linkId(),rom,gun,Map.copyOf(shown),cardId,mode);
            homeSaveRequests.put(p.getUUID(),r);String title=library(p.getServer()).displayName(rom);if(title.length()>140)title=title.substring(0,140);
            FcNetwork.sendHomeSaveSlots(p,new cn.piq.fcarcade.ArcadeHomeSaveSlotsPayload(intent.token(),gun,new ArcadeSaveSlotsPayload(tv.getBlockPos(),rom,title,rows),card));
        }
        private boolean validHomeSave(ServerPlayer p,HomeSaveRequest r,UUID token){
            if(!current(p)||homeSaveRequests.get(p.getUUID())!=r||!r.intent().valid(token,p.connection.getConnection(),p.getServer().getTickCount())
                    ||p.level().dimension()!=r.key().dimension()||sessions.containsKey(r.key())||cardActive(r.cardId())
                    ||!p.serverLevel().hasChunkAt(r.consolePos())||!p.serverLevel().hasChunkAt(r.key().anchor())
                    ||!(p.serverLevel().getBlockEntity(r.consolePos()) instanceof cn.piq.fcarcade.home.HomeConsoleBlockEntity c)
                    ||!(p.serverLevel().getBlockEntity(r.key().anchor()) instanceof cn.piq.fcarcade.home.HomeTvBlockEntity tv))return false;
            return c.hardwareId().equals(r.consoleId())&&tv.hardwareId().equals(r.tvId())&&java.util.Objects.equals(c.linkId(),r.linkId())&&tv.powered()
                    &&r.mode()==cartridgeMode(c)&&r.cardId().equals(cn.piq.fcarcade.home.FcCartridgeData.id(c.insertedCartridge()))
                    &&r.rom().equals(HomeHardware.selectedRom(p.serverLevel(),r.key().anchor()))&&cn.piq.fcarcade.home.ZapperStandService.connected(c)==r.gun()
                    &&cn.piq.fcarcade.home.HomeZapperService.facts(p,c,tv)
                    &&sessions.values().stream().noneMatch(s->s.homeRuntime!=null&&s.homeRuntime.host().equals(p.getUUID()));
        }
        private void homeSaveAction(ServerPlayer p,cn.piq.fcarcade.ArcadeHomeSaveActionPayload packet){
            HomeSaveRequest r=homeSaveRequests.get(p.getUUID());if(r==null||!r.intent().valid(packet.token(),p.connection.getConnection(),p.getServer().getTickCount())||!homeSaveBusy.add(p.getUUID()))return;
            try{
                if(packet.action()==null){r.intent().consume(packet.token(),p.connection.getConnection(),p.getServer().getTickCount());homeSaveRequests.remove(p.getUUID(),r);return;}
                var a=packet.action();if(!a.blockPos().equals(r.key().anchor())||!a.romSha256().equals(r.rom())||!validHomeSave(p,r,packet.token()))return;
                var c=HomeHardware.connectedConsole(p.serverLevel(),r.key().anchor());var tv=(cn.piq.fcarcade.home.HomeTvBlockEntity)p.serverLevel().getBlockEntity(r.key().anchor());
                if(validStructure(p,r.key().anchor())==null||!cn.piq.fcarcade.home.HomeZapperService.permissionToControl(p,c,tv)||!validHomeSave(p,r,packet.token()))return;
                boolean card=r.mode()==RomSaveMode.MACHINE;if(card&&a.slot()!=1)return;
                var store=saves(p.getServer());String slotKey=card?cartridgeSaveKey(p.getServer(),c,r.rom(),r.gun()):homeSaveKey(p.getServer(),c,r.rom(),r.gun(),playerSlotKey(p.getUUID(),a.slot()));
                var existing=card?store.list().stream().filter(i->i.saveKey().equals(slotKey)&&i.romSha256().equals(r.rom())).findFirst().orElse(null):findPlayerSlotSave(store,slotKey);
                if(!java.util.Objects.equals(existing,r.slots().get(a.slot()))||personalSaveKeyActive(slotKey)||existing!=null&&isSaveActive(existing)){
                    openHomeSaveSlots(p,c,tv,r.rom(),r.gun());return;
                }
                int max=r.gun()?2:library(p.getServer()).homeMaxPlayers(r.rom());if(a.players()>max){p.displayClientMessage(Component.literal("当前游戏不支持双人存档，请选择单人后再开机。"),false);openHomeSaveSlots(p,c,tv,r.rom(),r.gun());return;}
                boolean same=existing!=null&&existing.romSha256().equals(r.rom());
                if(a.resume()&&!same&&a.action()!=ArcadeSaveSlotActionPayload.DELETE)return;
                String name=ArcadeSaveStore.normalizeSlotName(a.name());if(name.isBlank())name=Component.translatable("screen.piq_fc_arcade.default_slot_name",a.slot()).getString();
                if(!r.intent().consume(packet.token(),p.connection.getConnection(),p.getServer().getTickCount())||!homeSaveRequests.remove(p.getUUID(),r))return;
                if(a.action()==ArcadeSaveSlotActionPayload.DELETE){if(existing!=null)store.delete(slotKey,existing.romSha256());if(card)c.cartridgeSaved(r.cardId(),r.rom(),cardProgressExists(p.getServer(),r.cardId(),r.rom()));openHomeSaveSlots(p,c,tv,r.rom(),r.gun());return;}
                if(a.action()==ArcadeSaveSlotActionPayload.RENAME){if(same){byte[] state=store.load(slotKey,r.rom());if(state!=null&&homeAccepts(p.getServer(),c,r.rom(),r.gun(),state))store.save(slotKey,r.rom(),state,name,a.players());}openHomeSaveSlots(p,c,tv,r.rom(),r.gun());return;}
                if(a.resume()&&same){byte[] state=store.load(slotKey,r.rom());if(!homeAccepts(p.getServer(),c,r.rom(),r.gun(),state)){p.displayClientMessage(Component.literal("存档核心不匹配，未开机、未覆盖原存档。"),false);openHomeSaveSlots(p,c,tv,r.rom(),r.gun());return;}}
                // Starting a replacement is not a durable save. Keep the old slot until saveState
                // has committed the new state; cancellation/crash must not destroy good progress.
                prepareHome(p,c,tv,r.rom(),r.gun(),r.mode(),slotKey,name,a.players(),a.resume());
            }catch(RuntimeException error){FcArcadeMod.LOGGER.warn("[PIQ FC] Home save selection failed without a fallback save mode",error);p.displayClientMessage(Component.literal("存档操作失败，请重新按电源选择。"),false);}
            finally{homeSaveBusy.remove(p.getUUID());}
        }
        private static boolean current(ServerPlayer p){return p!=null&&p.getServer()!=null&&p.getServer().isSameThread()&&!p.hasDisconnected()
                &&p.connection.getConnection().isConnected()&&p.getServer().getPlayerList().getPlayer(p.getUUID())==p;}
        private static boolean computeHost(Session s,ServerPlayer p){return current(p)&&(s.homeRuntime!=null?s.homeRuntime.host(p.getUUID(),p.connection.getConnection()):s.roster.roleOf(p.getUUID())==ArcadeRole.PLAYER_ONE);}
        private boolean validHome(MinecraftServer server,Session s){
            if(s.homeRuntime==null||!s.homeRuntime.running()||sessions.get(s.key)!=s)return false;
            ServerPlayer host=server.getPlayerList().getPlayer(s.homeRuntime.host());ServerLevel l=server.getLevel(s.key.dimension());
            if((s.hosted==null&&(!computeHost(s,host)||host.level().dimension()!=s.key.dimension()))||l==null||!l.hasChunkAt(s.key.anchor())||!l.hasChunkAt(s.homeConsolePos))return false;
            if(!(l.getBlockEntity(s.homeConsolePos) instanceof cn.piq.fcarcade.home.HomeConsoleBlockEntity c)||!(l.getBlockEntity(s.key.anchor()) instanceof cn.piq.fcarcade.home.HomeTvBlockEntity tv))return false;
            return tv.powered()&&!c.isRemoved()&&!tv.isRemoved()&&s.homeConsoleId.equals(c.hardwareId())&&s.homeTvId.equals(tv.hardwareId())&&s.homeLinkId.equals(c.linkId())
                    &&HomeHardware.connectedConsole(l,s.key.anchor())==c&&s.romSha256.equals(HomeHardware.selectedRom(l,s.key.anchor()))
                    &&s.homeCardId!=null&&s.homeCardId.equals(cn.piq.fcarcade.home.FcCartridgeData.id(c.insertedCartridge()))&&s.saveMode==cartridgeMode(c)
                    &&(s.hosted!=null||l.mayInteract(host,c.getBlockPos())&&l.mayInteract(host,tv.getBlockPos()));
        }
        private boolean requestHomeControl(ServerPlayer p,Session s,int port,net.minecraft.world.item.ItemStack gun){
            if(!current(p)||!validHome(p.getServer(),s)||s.homeDecisionBusy||validStructure(p,s.key.anchor())==null)return false;
            s.homeDecisionBusy=true;
            try{
            var owned=s.homeRuntime.player(p.getUUID());
            if(owned!=null&&owned.port()==port){if(gun==null)HomeControllerService.reclaim(p,s.id);return false;}
            if(!homeSocketAvailable(p,s,port,gun))return false;
            if(!canTakeHome(p,s,port,gun))return false;
            return grantHome(p,s,port,gun);
            }finally{s.homeDecisionBusy=false;}
        }
        private boolean homeSocketAvailable(ServerPlayer p,Session s,int port,net.minecraft.world.item.ItemStack gun){
            if(s.netplay!=null&&!s.homeRuntime.gunMode()&&(gun!=null||computeHost(s,p)!=(port==0))){p.displayClientMessage(Component.literal("Netplay 实验：开机玩家使用 1P，其他玩家使用 2P。"),true);return false;}
            if(port<0||port>=s.maxPlayers||s.homeRuntime.port(port)!=null)return false;
            if(gun!=null?(!s.homeRuntime.gunMode()||port!=1):(s.homeRuntime.gunMode()&&port!=0))return false;
            var owned=s.homeRuntime.player(p.getUUID());var key=memberships.get(p.getUUID());
            boolean paired=s.homeRuntime.gunMode()&&owned!=null&&owned.port()!=port&&owned.connection()==p.connection.getConnection();
            return (owned==null||paired)&&(key==null||paired&&key.equals(s.key));
        }
        private boolean canTakeHome(ServerPlayer p,Session s,int port,net.minecraft.world.item.ItemStack gun){
            if(!s.homeReady)return false;
            if(!computeHost(s,p)&&!s.multiplayerEnabled){p.displayClientMessage(Component.literal("本局不允许其他玩家加入。"),true);return false;}
            if(!current(p)||!validHome(p.getServer(),s)||validStructure(p,s.key.anchor())==null||!homeSocketAvailable(p,s,port,gun))return false;
            var c=HomeHardware.connectedConsole(p.serverLevel(),s.key.anchor());var tv=(cn.piq.fcarcade.home.HomeTvBlockEntity)p.serverLevel().getBlockEntity(s.key.anchor());
            if(!cn.piq.fcarcade.home.HomeZapperService.permissionToControl(p,c,tv)||!validHome(p.getServer(),s)||!homeSocketAvailable(p,s,port,gun)||!computeHost(s,p)&&!s.multiplayerEnabled)return false;
            if(gun==null)return HomeControllerService.canJoin(p,s.key.anchor(),s.id,port);
            return s.variant.isZapper()&&cn.piq.fcarcade.home.HomeZapperService.canBind(p,gun,c,tv);
        }
        private boolean grantHome(ServerPlayer p,Session s,int port,net.minecraft.world.item.ItemStack gun){
            var expectedSource=p.connection.getConnection();
            if(!canTakeHome(p,s,port,gun)||p.connection.getConnection()!=expectedSource||!current(p))return false;
            boolean alreadyParticipant=s.roster.contains(p.getUUID());
            UUID lease;
            if(gun==null){if(!HomeControllerService.grant(p,s.key.anchor(),s.id,port))return false;lease=HomeControllerService.leaseId(p,s.id,port);}
            else {var b=new cn.piq.fcarcade.home.ZapperBinding(s.id,s.lockstep.epoch(),UUID.randomUUID(),s.key.dimension().location(),s.homeConsolePos,s.homeConsoleId,s.key.anchor(),s.homeTvId,s.homeLinkId);
                if(!cn.piq.fcarcade.home.HomeZapperService.bind(p,gun,b))return false;s.zapperBinding=b;lease=b.lease();}
            boolean granted=gun==null?s.homeRuntime.take(p.getUUID(),expectedSource,lease,port):s.homeRuntime.takeGun(p.getUUID(),expectedSource,lease);
            if(!granted)throw new IllegalStateException("Physical grant transaction lost its reserved socket");
            refreshHomeMember(s,p.getUUID());
            removeViewer(p.getServer(),p.getUUID(),s.key,false);s.lockstep.clearController(port);s.lockstep.forgetPlayer(lease);if(gun!=null)s.lockstep.clearZapper();
            if(s.hosted!=null)s.hosted.release(port);
            if(port==0)s.homeRuntime.clearButtonSource();
            sync(p.getServer(),s,false);
            if(gun!=null)FcNetwork.sendZapperSession(p,new cn.piq.fcarcade.ArcadeZapperSessionPayload(s.zapperBinding,true));
            if(s.netplay==null&&s.hosted==null&&!s.playerMedia&&!alreadyParticipant&&!computeHost(s,p)){if(hasFreshSnapshot(s))syncControllerState(p,s);else{s.pendingControllerSync.add(p.getUUID());requestSessionSnapshot(p.getServer(),s);}}
            recordPlay(p);
            return true;
        }
        private void decideHome(ServerPlayer host,Session s,ArcadeJoinDecisionPayload payload){
            if(!computeHost(s,host)||!validHome(host.getServer(),s)||s.homeDecisionBusy)return;
            HomeRequest r=s.homeRequests.get(payload.applicantId());
            if(r==null||!r.token().equals(payload.requestToken()))return;
            s.homeRequests.remove(r.player(),r);if(!payload.accepted()||host.getServer().getTickCount()>=r.deadline())return;
            ServerPlayer p=host.getServer().getPlayerList().getPlayer(r.player());
            if(!current(p)||p.connection.getConnection()!=r.connection())return;
            s.homeDecisionBusy=true;
            try{if(canTakeHome(p,s,r.port(),r.gun())&&p.connection.getConnection()==r.connection()&&computeHost(s,host))grantHome(p,s,r.port(),r.gun());}finally{s.homeDecisionBusy=false;}
        }
        private void refreshHomeMember(Session s,UUID id){
            s.roster.removeFixed(id);var remaining=s.homeRuntime.player(id);
            if(remaining==null)OwnedMemberships.removeIfOwnedBy(memberships,id,s.key);
            else {if(!s.roster.joinAt(id,remaining.port()))throw new IllegalStateException("Runtime socket and primary role disagree");memberships.put(id,s.key);}
        }
        private boolean detachHomeSocket(MinecraftServer server,Session s,UUID id,net.minecraft.network.Connection source,int port,UUID lease){
            var old=s.homeRuntime.release(id,source,lease,port);if(old==null)return false;
            s.lockstep.clearController(port);s.lockstep.forgetPlayer(old.lease());
            if(s.hosted!=null)s.hosted.release(port);
            if(port==0)s.homeRuntime.clearButtonSource();
            boolean gun=s.homeRuntime.gunMode()&&port==1;
            if(gun){s.lockstep.clearZapper();if(s.homeRuntime.clearGunButtons(old.lease())){s.lockstep.clearController(0);if(s.hosted!=null)s.hosted.release(0);}if(s.zapperBinding!=null&&s.zapperBinding.lease().equals(old.lease())){cn.piq.fcarcade.home.HomeZapperService.closeSession(server,s.id);s.zapperBinding=null;}}
            else HomeControllerService.memberLeft(server,id,s.id);
            refreshHomeMember(s,id);
            if(s.homeRuntime.player(id)==null){s.pendingControllerSync.remove(id);s.resyncTicks.remove(id);inputTicks.remove(id);inputCounts.remove(id);}
            return true;
        }
        private void detachHomeDevice(ServerPlayer p,Session s,int port,UUID lease){
            if(!detachHomeSocket(p.getServer(),s,p.getUUID(),p.connection.getConnection(),port,lease))return;
            sync(p.getServer(),s,false);
            if(current(p)&&s.homeRuntime.player(p.getUUID())==null&&!computeHost(s,p)){if(s.hosted!=null||s.playerMedia||sharedJniWatch(s))sendInactive(p,s);else{s.viewers.add(p.getUUID());addTracking(viewerships,p.getUUID(),s.key);sendViewerSession(p,s);}}
        }
        private void detachHome(ServerPlayer p,Session s){
            for(var c:s.homeRuntime.controls(p.getUUID()))detachHomeDevice(p,s,c.port(),c.lease());
        }
        /** Server-only stale-socket cleanup uses the stored source, never a replacement connection. */
        private void detachStaleHome(MinecraftServer server,Session s,UUID id){
            for(var old:s.homeRuntime.controls(id))detachHomeSocket(server,s,id,old.connection(),old.port(),old.lease());
            s.roster.removeFixed(id);OwnedMemberships.removeIfOwnedBy(memberships,id,s.key);
            HomeControllerService.memberLeft(server,id,s.id);s.pendingControllerSync.remove(id);s.resyncTicks.remove(id);inputTicks.remove(id);inputCounts.remove(id);
            sync(server,s,false);
        }
        private boolean storageInitialized;
        private boolean storageFailed;
        private RuntimeException storageFailure;
        private final Map<UUID, Long> storageNoticeAfter = new HashMap<>();

        /** Fail closed once: never build an empty fallback store or retry every tick. */
        private boolean initializeStorage(MinecraftServer server) {
            if (storageInitialized) return true;
            if (storageFailed) return false;
            try {
                library(server); saves(server); scores(server); settingsStore(server);
                storageInitialized = true;
                return true;
            } catch (RuntimeException error) {
                storageFailed = true;
                storageFailure = error;
                FcArcadeMod.LOGGER.error("[PIQ FC] Storage initialization failed; FC requests are disabled until restart. No legacy/empty fallback was used.", error);
                return false;
            }
        }
        private final Map<SessionKey, Session> sessions = new HashMap<>();
        private final Map<UUID, SessionKey> memberships = new HashMap<>();
        private final Map<UUID, Set<SessionKey>> viewerships = new HashMap<>();
        private final Map<UUID, Set<SessionKey>> pendingViewerships = new HashMap<>();
        private final Map<UUID, Set<SessionKey>> waitingRomViewerships =
                new HashMap<>();
        private final Map<UUID, Integer> inputTicks = new HashMap<>();
        private final Map<UUID, Integer> inputCounts = new HashMap<>();
        private final Map<UUID, IncomingUpload> uploads = new HashMap<>();
        private final Map<UUID, OutgoingDownload> downloads = new java.util.LinkedHashMap<>();
        private final RomDownloadBudget downloadBudget = new RomDownloadBudget();
        private final Map<UUID, Integer> uploadTicks = new HashMap<>();
        private final Map<UUID, Integer> uploadCounts = new HashMap<>();
        private final Map<UUID, ResumeChoice> resumeChoices = new HashMap<>();
        private final Map<UUID, SaveSlotChoice> saveSlotChoices =
                new HashMap<>();
        private final Map<UUID, PendingExit> pendingExits = new HashMap<>();
        private ServerRomLibrary romLibrary;
        private ArcadeSaveStore saveStore;
        private ArcadeScoreStore scoreStore;
        private final Set<SessionKey> idleLeaderboardDisplays = new HashSet<>();
        private boolean idleDisplaysInitialized;
        private ServerArcadeSettings settingsStore;
        private long nextSessionId = 1;
        private int cleanupTicks;
        private int nextSaveCleanupTick;

        private record ResumeChoice(
                SessionKey key,
                String romSha256,
                boolean resume
        ) {
        }

        private record PendingExit(long sessionId, int deadlineTick) {
        }

        private record SaveSlotChoice(
                SessionKey key,
                String romSha256,
                int slot,
                String name,
                int players,
                boolean resume,
                String replacedRomSha256
        ) {
        }

        private ServerRomLibrary library(MinecraftServer server) {
            if (romLibrary == null) romLibrary = new ServerRomLibrary(
                    FcStoragePaths.prepareUnchecked(server.getServerDirectory(), FcStoragePaths.Area.ROMS));
            return romLibrary;
        }

        private ArcadeSaveStore saves(MinecraftServer server) {
            if (saveStore == null) {
                saveStore = new ArcadeSaveStore(
                        FcStoragePaths.prepareUnchecked(server.getServerDirectory(), FcStoragePaths.Area.SAVES));
            }
            return saveStore;
        }

        private ArcadeScoreStore scores(MinecraftServer server) {
            if (scoreStore == null) {
                scoreStore = new ArcadeScoreStore(
                        FcStoragePaths.prepareUnchecked(server.getServerDirectory(), FcStoragePaths.Area.SCORES)
                                .resolve("leaderboard.properties"));
            }
            return scoreStore;
        }

        private ServerArcadeSettings settingsStore(MinecraftServer server) {
            if (settingsStore == null) {
                settingsStore = new ServerArcadeSettings(
                        FcStoragePaths.prepareUnchecked(server.getServerDirectory(), FcStoragePaths.Area.SERVER_CONFIG));
            }
            return settingsStore;
        }

        private ArcadeGlobalSettings settings(MinecraftServer server) {
            return settingsStore(server).get();
        }

        private int leaderboardPageSeconds(MinecraftServer server) {
            return settingsStore(server).leaderboardPageSeconds();
        }

        private String selectedRom(MinecraftServer server, SessionKey key) {
            ServerLevel level = server.getLevel(key.dimension());
            if (level == null || !level.hasChunkAt(key.anchor())) return "";
            if (level.getBlockState(key.anchor()).getBlock() instanceof RetroTvBlock) {
                return HomeHardware.validPlayback(level, key.anchor())
                        ? HomeHardware.selectedRom(level, key.anchor()) : "";
            }
            return library(server).selected(key.dimension(), key.anchor(), key.mode());
        }

        private void setLeaderboardPageSeconds(
                MinecraftServer server,
                int seconds
        ) {
            settingsStore(server).setLeaderboardPageSeconds(seconds);
            refreshIdleScoreDisplays(server);
        }

        private void interact(ServerPlayer player, SessionKey requestedKey) {
            SessionKey currentKey = memberships.get(player.getUUID());
            if (requestedKey.equals(currentKey)) {
                Session current = sessions.get(currentKey);
                if (current != null
                        && current.lockstep != null
                        && current.saveMode != RomSaveMode.NONE
                        && (current.personalSave == null || current.personalSave.writable())
                        && current.roster.roleOf(player.getUUID())
                        == ArcadeRole.PLAYER_ONE) {
                    FcNetwork.promptExit(
                            player,
                            new ArcadeExitPromptPayload(current.id));
                } else {
                    leave(player, true);
                }
                return;
            }
            String selected = selectedRom(player.getServer(), requestedKey);
            if (selected.isEmpty()) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                        player.hasPermissions(2)
                                ? "message.piq_fc_arcade.arcade_unconfigured_op"
                                : "message.piq_fc_arcade.arcade_unconfigured"));
                return;
            }
            Session existing = sessions.get(requestedKey);
            if (existing != null) {
                if (existing.disconnectedRoles.containsKey(player.getUUID())) {
                    FcNetwork.promptResume(
                            player,
                            new ArcadeResumePromptPayload(
                                    requestedKey.anchor(),
                                    existing.romSha256));
                    return;
                }
                if (existing.maxPlayers == 1) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                            "message.piq_fc_arcade.single_player_occupied"));
                    return;
                }
                if (existing.roster.size()
                        + existing.disconnectedRoles.size()
                        >= existing.maxPlayers) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                            "message.piq_fc_arcade.arcade_full"));
                    return;
                }
                if (!existing.multiplayerEnabled) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                            "message.piq_fc_arcade.multiplayer_disabled"));
                    return;
                }
                FcNetwork.promptJoin(player, requestedKey.anchor(), selected);
                return;
            }
            RomSaveMode saveMode = effectiveSaveMode(
                    requestedKey,
                    library(player.getServer()).saveMode(selected));
            if (saveMode == RomSaveMode.PLAYER) {
                openPlayerSaveSlots(player, requestedKey, selected);
                return;
            }
            if (hasSave(
                    player.getServer(),
                    requestedKey,
                    selected,
                    saveMode,
                    player.getUUID())) {
                FcNetwork.promptResume(
                        player,
                        new ArcadeResumePromptPayload(
                                requestedKey.anchor(),
                                selected));
            } else {
                FcNetwork.promptJoin(player, requestedKey.anchor(), selected);
            }
        }

        private void openLibrary(ServerPlayer player, SessionKey requestedKey) {
            if (!cn.piq.fcarcade.access.PlayerContentAccess.canBrowse(player)) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                        "message.piq_fc_arcade.library_op_only"));
                return;
            }
            FcNetwork.sendLibrary(player, new ArcadeLibraryPayload(
                    requestedKey.anchor(),
                    selectedRom(player.getServer(), requestedKey),
                    library(player.getServer()).leaderboardEnabled(
                            requestedKey.dimension(),
                            requestedKey.anchor()),
                    cn.piq.fcarcade.access.PlayerContentAccess.canUseServerRom(player)?library(player.getServer()).catalog():List.of(),
                    cn.piq.fcarcade.access.PlayerContentAccess.capabilities(player)));
        }

        private void setArcadeLeaderboardEnabled(
                ServerPlayer player,
                ArcadeLeaderboardTogglePayload payload
        ) {
            if (!player.hasPermissions(2)) return;
            ArcadeStructure structure = validStructure(
                    player,
                    payload.blockPos());
            if (structure == null) return;
            SessionKey key = new SessionKey(
                    player.level().dimension(),
                    structure.anchor(),
                    structure.mode());
            library(player.getServer()).setLeaderboardEnabled(
                    key.dimension(),
                    key.anchor(),
                    payload.enabled());
            refreshIdleScoreDisplay(player.getServer(), key);
            openLibrary(player, key);
        }

        private void join(
                ServerPlayer player,
                SessionKey requestedKey,
                String romSha256
        ) {
            join(player, requestedKey, romSha256, requestedKey.anchor());
        }

        private void join(ServerPlayer player, SessionKey requestedKey, String romSha256, BlockPos clicked) {
            legacyTransaction(player, clicked, () -> joinPermitted(player, requestedKey, romSha256));
        }

        private void joinPermitted(
                ServerPlayer player,
                SessionKey requestedKey,
                String romSha256
        ) {
            if (cn.piq.fcarcade.cabinet.ServerCabinets.hasExternalLease(player)) return;
            if (validStructure(player, requestedKey.anchor()) == null) return;
            String selected = selectedRom(player.getServer(), requestedKey);
            if (!romSha256.equals(selected)
                    || library(player.getServer()).find(romSha256) == null) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                        "message.piq_fc_arcade.rom_unavailable"));
                return;
            }
            Session existing = sessions.get(requestedKey);
            if (existing != null && !existing.romSha256.equals(romSha256)) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                        "message.piq_fc_arcade.rom_mismatch"));
                return;
            }
            if (existing != null) {
                resumeChoices.remove(player.getUUID());
                requestSecondPlayer(player, existing);
                return;
            }

            boolean isHome = player.serverLevel().getBlockState(requestedKey.anchor()).getBlock() instanceof RetroTvBlock;
            if(isHome)return; // Only the physical power button creates appliance sessions.
            if (isHome && !HomeControllerService.canJoin(player, requestedKey.anchor(), nextSessionId, 0)) return;

            RomSaveMode saveMode = effectiveSaveMode(
                    requestedKey,
                    library(player.getServer()).saveMode(romSha256));
            boolean resume;
            String sessionSaveKey;
            String saveSlotName = "";
            int savePlayers = 1;
            if (saveMode == RomSaveMode.PLAYER) {
                SaveSlotChoice slotChoice =
                        saveSlotChoices.remove(player.getUUID());
                if (slotChoice == null
                        || !slotChoice.key.equals(requestedKey)
                        || !slotChoice.romSha256.equals(romSha256)) {
                    openPlayerSaveSlots(player, requestedKey, romSha256);
                    return;
                }
                sessionSaveKey = coreVariant(player.getServer(),romSha256,false).saveKey(playerSlotKey(
                        player.getUUID(),
                        slotChoice.slot));
                if(personalSaveKeyActive(sessionSaveKey)){player.displayClientMessage(Component.literal("这个个人槽正在被另一局使用，请先关闭那一局。"),false);openPlayerSaveSlots(player,requestedKey,romSha256);return;}
                ArcadeSaveStore store = saves(player.getServer());
                ArcadeSaveStore.SaveInfo existingSlot =
                        findPlayerSlotSave(store, sessionSaveKey);
                if (!slotChoice.replacedRomSha256.isEmpty()) {
                    if (existingSlot == null
                            || !slotChoice.replacedRomSha256.equals(
                            existingSlot.romSha256())
                            || isSaveActive(existingSlot)) {
                        if (existingSlot != null
                                && isSaveActive(existingSlot)) {
                            player.sendSystemMessage(
                                    net.minecraft.network.chat.Component.translatable(
                                            "message.piq_fc_arcade.save_delete_in_use"));
                        }
                        openPlayerSaveSlots(player, requestedKey, romSha256);
                        return;
                    }
                    store.delete(sessionSaveKey, existingSlot.romSha256());
                    existingSlot = null;
                } else if (existingSlot != null
                        && !romSha256.equals(existingSlot.romSha256())) {
                    openPlayerSaveSlots(player, requestedKey, romSha256);
                    return;
                }
                boolean occupied = existingSlot != null;
                resume = occupied && slotChoice.resume;
                if (occupied && !resume) {
                    store.delete(
                            sessionSaveKey,
                            romSha256);
                }
                saveSlotName = slotChoice.name;
                savePlayers = slotChoice.players;
            } else {
                boolean hasSave = hasSave(
                        player.getServer(),
                        requestedKey,
                        romSha256,
                        saveMode,
                        player.getUUID());
                ResumeChoice choice = resumeChoices.remove(player.getUUID());
                if (hasSave && (choice == null
                        || !choice.key.equals(requestedKey)
                        || !choice.romSha256.equals(romSha256))) {
                    FcNetwork.promptResume(
                            player,
                            new ArcadeResumePromptPayload(
                                    requestedKey.anchor(),
                                    romSha256));
                    return;
                }
                resume = hasSave && choice.resume;
                sessionSaveKey = saveMode == RomSaveMode.NONE
                        ? ""
                        : coreVariant(player.getServer(),romSha256,false).saveKey(saveKey(
                                requestedKey,
                                saveMode,
                                player.getUUID()));
                if (hasSave && !resume) {
                    saves(player.getServer()).delete(
                            sessionSaveKey,
                            romSha256);
                }
            }

            SessionKey currentKey = memberships.get(player.getUUID());
            if (currentKey != null) leave(player, true);
            removeViewer(
                    player.getServer(),
                    player.getUUID(),
                    requestedKey,
                    false);
            Session session = new Session(
                    nextSessionId++,
                    requestedKey,
                    romSha256,
                    player.serverLevel().getBlockEntity(requestedKey.anchor()) instanceof cn.piq.fcarcade.world.PortraitCabinetBlockEntity ? 1 : library(player.getServer()).maxPlayers(romSha256),
                    saveMode,
                    player.getUUID(),
                    sessionSaveKey,
                    saveSlotName,
                    savePlayers);
            session.homeConsole = isHome;
            session.variant=coreVariant(player.getServer(),romSha256,false);
            if (isHome && !HomeControllerService.grant(player, requestedKey.anchor(), session.id, 0)) return;
            sessions.put(requestedKey, session);
            session.roster.join(player.getUUID());
            memberships.put(player.getUUID(), requestedKey);
            boolean reset = session.lockstep != null;
            if (reset) restart(session);
            byte[] savedState = reset && resume
                    ? loadState(player.getServer(), session)
                    : null;
            sync(player.getServer(), session, reset);
            if (savedState != null) {
                FcNetwork.sendPersistentState(
                        player,
                        new ArcadePersistentStatePayload(
                                session.id,
                                session.lockstep.epoch(),
                                savedState));
            }
            if (session.maxPlayers == 2) {
                FcNetwork.offerMultiplayer(
                        player,
                        new ArcadeMultiplayerOfferPayload(session.id));
            }
            recordPlay(player);
        }

        private void recordPlay(ServerPlayer player){
            try{saves(player.getServer()).played(player.getUUID(),Instant.now());}
            catch(RuntimeException error){FcArcadeMod.LOGGER.warn("[PIQ FC] Play activity write failed; this process retains the activity in memory",error);}
        }

        private void requestSecondPlayer(ServerPlayer applicant, Session session) {
            if(session.homeRuntime!=null)return; // Home devices use physical controller sockets.
            if (session.roster.contains(applicant.getUUID())) {
                if (session.homeConsole) HomeControllerService.reclaim(applicant, session.id);
                return;
            }
            if (session.maxPlayers == 1) {
                applicant.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                        "message.piq_fc_arcade.single_player_occupied"));
                return;
            }
            if (!session.multiplayerEnabled) {
                applicant.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                        "message.piq_fc_arcade.multiplayer_disabled"));
                return;
            }
            if (session.roster.size()
                    + session.disconnectedRoles.size()
                    >= session.maxPlayers) {
                applicant.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                        "message.piq_fc_arcade.arcade_full"));
                return;
            }
            joinSecondPlayer(applicant,session);
        }
        private void joinSecondPlayer(ServerPlayer applicant,Session session){
            if(!current(applicant)||sessions.get(session.key)!=session||!session.multiplayerEnabled||session.maxPlayers!=2
                    ||session.roster.size()+session.disconnectedRoles.size()>=2||playerOne(applicant.getServer(),session)==null
                    ||applicant.level().dimension()!=session.key.dimension()||controllerDistanceSquared(applicant,session.key.anchor())>MAX_DISTANCE_SQUARED)return;
            var previous=memberships.get(applicant.getUUID());
            if(previous!=null)leave(applicant,true);
            if(!current(applicant)||sessions.get(session.key)!=session||!session.multiplayerEnabled
                    ||session.roster.size()+session.disconnectedRoles.size()>=2||playerOne(applicant.getServer(),session)==null)return;
            removeViewer(applicant.getServer(),applicant.getUUID(),session.key,false);
            if(!session.roster.joinAt(applicant.getUUID(),1))return;
            memberships.put(applicant.getUUID(),session.key);
            if(session.personalSave!=null&&!session.personalSave.writable())notifySealedSave(applicant,session);
            sync(applicant.getServer(),session,false);
            if(session.lockstep!=null){if(hasFreshSnapshot(session))syncControllerState(applicant,session);else{session.pendingControllerSync.add(applicant.getUUID());requestSessionSnapshot(applicant.getServer(),session);}}
            applicant.displayClientMessage(Component.literal("已加入，当前为 2P。"),true);
            recordPlay(applicant);
        }

        private void selectRom(
                ServerPlayer player,
                BlockPos clickedPos,
                String sha256
        ) {
            selectRom(player,clickedPos,sha256,false);
        }

        private void selectRom(ServerPlayer player,BlockPos clickedPos,String sha256,boolean uploaded) {
            if (!(uploaded?cn.piq.fcarcade.access.PlayerContentAccess.canUploadRom(player):cn.piq.fcarcade.access.PlayerContentAccess.canUseServerRom(player))) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                        "message.piq_fc_arcade.library_op_only"));
                return;
            }
            ArcadeStructure structure = validStructure(player, clickedPos);
            if (structure == null
                    || sha256 == null
                    || !sha256.matches(RomRepository.SHA256_PATTERN)
                    || library(player.getServer()).find(sha256) == null) {
                return;
            }
            if (player.serverLevel().getBlockState(structure.anchor()).getBlock() instanceof RetroTvBlock) {
                HomeFeedback.show(player, "home_swap_cartridge");
                return;
            }
            SessionKey key = new SessionKey(
                    player.level().dimension(),
                    structure.anchor(),
                    structure.mode());
            Session existing = sessions.get(key);
            if (existing != null) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                        "message.piq_fc_arcade.only_player_one_changes_rom"));
                return;
            }

            if (!(uploaded?cn.piq.fcarcade.access.PlayerContentAccess.canUploadRom(player):cn.piq.fcarcade.access.PlayerContentAccess.canUseServerRom(player))) return;
            var selectedRom = library(player.getServer()).find(sha256);
            if (selectedRom == null) return;
            if (!cn.piq.fcarcade.rom.NesCompatibility.isSupported(selectedRom.header())) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        cn.piq.fcarcade.rom.NesCompatibility.unsupportedReason(selectedRom.header().mapper())));
                return;
            }
            library(player.getServer()).select(
                    key.dimension(),
                    key.anchor(),
                    key.mode(),
                    sha256);
            if (existing != null) close(player.getServer(), existing);
            join(player, key, sha256);
        }

        private void setRomPlayerMode(
                ServerPlayer player,
                RomPlayerModePayload payload
        ) {
            if (!player.hasPermissions(2)) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                        "message.piq_fc_arcade.library_op_only"));
                return;
            }
            ArcadeStructure structure = validStructure(player, payload.blockPos());
            if (structure == null) return;
            library(player.getServer()).setMaxPlayers(
                    payload.romSha256(),
                    payload.maxPlayers());
            openLibrary(
                    player,
                    new SessionKey(
                            player.level().dimension(),
                            structure.anchor(),
                            structure.mode()));
        }

        private void setRomSaveMode(
                ServerPlayer player,
                RomSaveModePayload payload
        ) {
            if (!player.hasPermissions(2)) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                        "message.piq_fc_arcade.library_op_only"));
                return;
            }
            ArcadeStructure structure = validStructure(player, payload.blockPos());
            if (structure == null) return;
            library(player.getServer()).setSaveMode(
                    payload.romSha256(),
                    payload.saveMode());
            openLibrary(
                    player,
                    new SessionKey(
                            player.level().dimension(),
                            structure.anchor(),
                            structure.mode()));
        }

        private void deleteRom(
                ServerPlayer player,
                RomDeletePayload payload
        ) {
            if (!player.hasPermissions(2)) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                        "message.piq_fc_arcade.library_op_only"));
                return;
            }
            ArcadeStructure structure = validStructure(player, payload.blockPos());
            if (structure == null) return;
            if (sessions.values().stream().anyMatch(
                    session -> session.romSha256.equals(payload.romSha256()))) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                        "message.piq_fc_arcade.rom_delete_in_use"));
                openLibrary(
                        player,
                        new SessionKey(
                                player.level().dimension(),
                                structure.anchor(),
                                structure.mode()));
                return;
            }
            boolean deleted = library(player.getServer()).delete(
                    payload.romSha256());
            player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                    deleted
                            ? "message.piq_fc_arcade.rom_deleted"
                            : "message.piq_fc_arcade.rom_delete_missing"));
            openLibrary(
                    player,
                    new SessionKey(
                            player.level().dimension(),
                            structure.anchor(),
                    structure.mode()));
        }

        private void renameRom(
                ServerPlayer player,
                RomRenamePayload payload
        ) {
            if (!player.hasPermissions(2)) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                        "message.piq_fc_arcade.library_op_only"));
                return;
            }
            ArcadeStructure structure = validStructure(player, payload.blockPos());
            if (structure == null) return;
            library(player.getServer()).setDisplayName(
                    payload.romSha256(),
                    payload.displayName());
            player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                    "message.piq_fc_arcade.rom_renamed",
                    payload.displayName()));
            openLibrary(
                    player,
                    new SessionKey(
                            player.level().dimension(),
                            structure.anchor(),
                            structure.mode()));
        }

        private void openSettings(ServerPlayer player, BlockPos clickedPos) {
            if (!player.hasPermissions(2)) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                        "message.piq_fc_arcade.library_op_only"));
                return;
            }
            ArcadeStructure structure = validStructure(player, clickedPos);
            if (structure == null) return;
            FcNetwork.sendSettings(
                    player,
                    new ArcadeSettingsPayload(
                            structure.anchor(),
                            settings(player.getServer())));
        }

        private void openSaveCatalog(
                ServerPlayer player,
                BlockPos clickedPos
        ) {
            if (!player.hasPermissions(2)) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                        "message.piq_fc_arcade.library_op_only"));
                return;
            }
            ArcadeStructure structure = validStructure(player, clickedPos);
            if (structure == null) return;
            MinecraftServer server = player.getServer();
            var entries = saves(server).list().stream()
                    .filter(save -> save.legacy()
                            || catalogPlayerKey(save.saveKey()).startsWith("player|"))
                    .limit(ArcadeSaveCatalogPayload.MAX_ENTRIES)
                    .map(save -> {
                        RomDescriptor rom = library(server).find(
                                save.romSha256());
                        return new ArcadeSaveCatalogEntry(
                                save.storageId(),
                                saveOwner(server, save),
                                rom == null
                                        ? save.romSha256().substring(0, 12)
                                        : library(server).displayName(
                                                save.romSha256()),
                                save.romSha256(),
                                save.slotName(),
                                save.players(),
                                save.modifiedEpochMillis(),
                                save.fileBytes(),
                                save.legacy());
                    })
                    .toList();
            FcNetwork.sendSaveCatalog(
                    player,
                    new ArcadeSaveCatalogPayload(
                            structure.anchor(),
                            entries));
        }

        private static String saveOwner(
                MinecraftServer server,
                ArcadeSaveStore.SaveInfo save
        ) {
            if (save.legacy()) return "unknown";
            String remainder =
                    catalogPlayerKey(save.saveKey()).substring("player|".length());
            int separator = remainder.indexOf('|');
            String uuidText = separator < 0
                    ? remainder
                    : remainder.substring(0, separator);
            try {
                UUID uuid = UUID.fromString(uuidText);
                ServerPlayer online = server.getPlayerList().getPlayer(uuid);
                String name = online == null
                        ? server.getProfileCache()
                        .get(uuid)
                        .map(profile -> profile.getName())
                        .orElse(uuidText)
                        : online.getGameProfile().getName();
                return name + " (" + uuidText + ")";
            } catch (IllegalArgumentException invalid) {
                return uuidText;
            }
        }

        private void openPlayerSaveSlots(
                ServerPlayer player,
                SessionKey key,
                String romSha256
        ) {
            MinecraftServer server = player.getServer();
            var selectedVariant=coreVariant(server,romSha256,false);
            if(selectedVariant==cn.piq.fcarcade.session.NesCoreVariant.LEGACY)migratePlayerSavesToGlobalSlots(server, player.getUUID());
            List<ArcadeSaveStore.SaveInfo> all = saves(server).list();
            List<ArcadeSaveSlotEntry> slots =
                    java.util.stream.IntStream.rangeClosed(1, 3)
                            .mapToObj(slot -> {
                                String slotKey =
                                        selectedVariant.saveKey(playerSlotKey(player.getUUID(), slot));
                                ArcadeSaveStore.SaveInfo info = all.stream()
                                        .filter(save -> slotKey.equals(
                                                save.saveKey()))
                                        .findFirst()
                                        .orElse(null);
                                String defaultName =
                                        net.minecraft.network.chat.Component.translatable(
                                                "screen.piq_fc_arcade.default_slot_name",
                                                slot).getString();
                                return info == null
                                        ? new ArcadeSaveSlotEntry(
                                                slot,
                                                false,
                                                defaultName,
                                                1,
                                                0,
                                                "",
                                                "")
                                        : new ArcadeSaveSlotEntry(
                                                slot,
                                                true,
                                                info.slotName().isBlank()
                                                        ? defaultName
                                                        : info.slotName(),
                                                info.players(),
                                                info.modifiedEpochMillis(),
                                                info.romSha256(),
                                                saveRomName(server, info));
                            })
                            .toList();
            FcNetwork.sendSaveSlots(
                    player,
                    new ArcadeSaveSlotsPayload(
                            key.anchor(),
                            romSha256,
                            library(server).displayName(romSha256),
                            slots));
        }

        private static String catalogPlayerKey(String key) {
            return PlayerSaveCatalogKey.normalize(key);
        }

        private void handleSaveSlotAction(
                ServerPlayer player,
                ArcadeSaveSlotActionPayload payload
        ) {
            legacyTransaction(player, payload.blockPos(), () -> handleSaveSlotActionPermitted(player, payload));
        }

        private void handleSaveSlotActionPermitted(
                ServerPlayer player,
                ArcadeSaveSlotActionPayload payload
        ) {
            ArcadeStructure structure = validStructure(
                    player,
                    payload.blockPos());
            if (structure == null) return;
            // Home slots require the independently issued, connection-bound power intent.
            if(player.serverLevel().getBlockEntity(structure.anchor()) instanceof cn.piq.fcarcade.home.HomeTvBlockEntity)return;
            SessionKey key = new SessionKey(
                    player.level().dimension(),
                    structure.anchor(),
                    structure.mode());
            String selected = selectedRom(player.getServer(), key);
            if (!selected.equals(payload.romSha256())
                    || effectiveSaveMode(
                    key,
                    library(player.getServer()).saveMode(selected))
                    != RomSaveMode.PLAYER) {
                return;
            }
            int maxPlayers =
                    library(player.getServer()).maxPlayers(selected);
            ArcadeSaveStore store = saves(player.getServer());
            var selectedVariant=coreVariant(player.getServer(),selected,false);
            String slotKey = selectedVariant.saveKey(playerSlotKey(
                    player.getUUID(),
                    payload.slot()));
            if(personalSaveKeyActive(slotKey)){player.displayClientMessage(Component.literal("这个个人槽正在使用，不能同时修改或开启另一局。"),false);return;}
            ArcadeSaveStore.SaveInfo existing = store.list().stream()
                    .filter(save -> slotKey.equals(save.saveKey()))
                    .findFirst()
                    .orElse(null);
            if (payload.action() == ArcadeSaveSlotActionPayload.DELETE) {
                if (existing != null && isSaveActive(existing)) {
                    player.sendSystemMessage(
                            net.minecraft.network.chat.Component.translatable(
                                    "message.piq_fc_arcade.save_delete_in_use"));
                    openPlayerSaveSlots(player, key, selected);
                    return;
                }
                if (existing != null) {
                    store.delete(slotKey, existing.romSha256());
                }
                player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                        "message.piq_fc_arcade.save_deleted"));
                openPlayerSaveSlots(player, key, selected);
                return;
            }
            boolean selectedRomOccupied = existing != null
                    && selected.equals(existing.romSha256());
            if (payload.players() > maxPlayers) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                        "message.piq_fc_arcade.slot_players_unsupported"));
                openPlayerSaveSlots(player, key, selected);
                return;
            }
            if (payload.action() == ArcadeSaveSlotActionPayload.RENAME) {
                if (selectedRomOccupied) {
                    byte[] state = store.load(slotKey, selected);
                    if (state != null && selectedVariant.acceptsPersistentStateHeader(state,selected)) {
                        store.save(
                                slotKey,
                                selected,
                                state,
                                payload.name(),
                                payload.players());
                    }
                }
                openPlayerSaveSlots(player, key, selected);
                return;
            }
            String name =
                    ArcadeSaveStore.normalizeSlotName(payload.name());
            if (name.isBlank()) {
                name = net.minecraft.network.chat.Component.translatable(
                        "screen.piq_fc_arcade.default_slot_name",
                        payload.slot()).getString();
            }
            int players = payload.players();
            if (selectedRomOccupied
                    && payload.resume()
                    && (!name.equals(existing.slotName())
                    || players != existing.players())) {
                byte[] state = store.load(slotKey, selected);
                if (state == null || !selectedVariant.acceptsPersistentStateHeader(state,selected)) {
                    openPlayerSaveSlots(player, key, selected);
                    return;
                }
                store.save(
                        slotKey,
                        selected,
                        state,
                        name,
                        players);
            }
            saveSlotChoices.put(
                    player.getUUID(),
                    new SaveSlotChoice(
                            key,
                            selected,
                            payload.slot(),
                            name,
                            players,
                            selectedRomOccupied && payload.resume(),
                            existing != null && !selectedRomOccupied
                                    ? existing.romSha256()
                                    : ""));
            FcNetwork.promptJoin(player, key.anchor(), selected);
        }

        private void migratePlayerSavesToGlobalSlots(
                MinecraftServer server,
                UUID playerId
        ) {
            PlayerSaveSlots.migrateLegacy(
                    saves(server),
                    playerId,
                    slot -> net.minecraft.network.chat.Component.translatable(
                            "screen.piq_fc_arcade.default_slot_name",
                            slot).getString(),
                    this::isSaveActive,
                    this::personalSaveKeyActive);
        }

        private ArcadeSaveStore.SaveInfo findPlayerSlotSave(
                ArcadeSaveStore store,
                String slotKey
        ) {
            return store.list().stream()
                    .filter(save -> slotKey.equals(save.saveKey()))
                    .findFirst()
                    .orElse(null);
        }

        private String saveRomName(
                MinecraftServer server,
                ArcadeSaveStore.SaveInfo save
        ) {
            RomDescriptor rom = library(server).find(save.romSha256());
            return rom == null
                    ? save.romSha256().substring(0, 12)
                    : library(server).displayName(save.romSha256());
        }

        private boolean isSaveActive(ArcadeSaveStore.SaveInfo save) {
            if(personalSaveKeyActive(save.saveKey()))return true;
            return sessions.values().stream().anyMatch(
                    session -> session.romSha256.equals(save.romSha256())
                            && session.saveKey.equals(save.saveKey()));
        }
        private boolean personalSaveKeyActive(String key){return cn.piq.fcarcade.netplay.NetplaySaveServer.busy(key)||sessions.values().stream().anyMatch(s->s.saveMode==RomSaveMode.PLAYER&&s.saveKey.equals(key))||closingHosted.stream().anyMatch(s->s.saveMode==RomSaveMode.PLAYER&&s.saveKey.equals(key));}

        private void deleteSave(
                ServerPlayer player,
                ArcadeSaveDeletePayload payload
        ) {
            legacyTransaction(player, payload.blockPos(), () -> deleteSavePermitted(player, payload));
        }

        private void deleteSavePermitted(
                ServerPlayer player,
                ArcadeSaveDeletePayload payload
        ) {
            if (!player.hasPermissions(2)) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                        "message.piq_fc_arcade.library_op_only"));
                return;
            }
            ArcadeStructure structure = validStructure(player, payload.blockPos());
            if (structure == null) return;
            ArcadeSaveStore.SaveInfo target = saves(player.getServer()).list()
                    .stream()
                    .filter(save -> payload.storageId().equals(
                            save.storageId()))
                    .findFirst()
                    .orElse(null);
            if (target == null) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                        "message.piq_fc_arcade.save_delete_missing"));
            } else if (sessions.values().stream().anyMatch(
                    session -> session.romSha256.equals(target.romSha256())
                            && session.saveKey.equals(target.saveKey()))) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                        "message.piq_fc_arcade.save_delete_in_use"));
            } else {
                saves(player.getServer()).deleteByStorageId(
                        payload.storageId());
                player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                        "message.piq_fc_arcade.save_deleted"));
            }
            openSaveCatalog(player, structure.anchor());
        }

        private void updateSettings(
                ServerPlayer player,
                ArcadeSettingsUpdatePayload payload
        ) {
            if (!player.hasPermissions(2)) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                        "message.piq_fc_arcade.library_op_only"));
                return;
            }
            ArcadeStructure structure = validStructure(player, payload.blockPos());
            if (structure == null) return;
            if (settingsStore == null) settings(player.getServer());
            settingsStore.set(payload.settings());
            cleanupExpiredSaves(player.getServer(), true);
            for (Session session : sessions.values()) {
                sync(player.getServer(), session, false);
            }
            reconcileViewers(player.getServer());
            player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                    "message.piq_fc_arcade.settings_saved"));
        }

        private void decideResume(
                ServerPlayer player,
                ArcadeResumeDecisionPayload payload
        ) {
            legacyTransaction(player, payload.blockPos(), () -> decideResumePermitted(player, payload));
        }

        private void decideResumePermitted(
                ServerPlayer player,
                ArcadeResumeDecisionPayload payload
        ) {
            ArcadeStructure structure = validStructure(player, payload.blockPos());
            if (structure == null) return;
            SessionKey key = new SessionKey(
                    player.level().dimension(),
                    structure.anchor(),
                    structure.mode());
            String selected = selectedRom(player.getServer(), key);
            if (!payload.romSha256().equals(selected)) {
                return;
            }
            Session existing = sessions.get(key);
            if (existing != null) {
                ArcadeRole previousRole =
                        existing.disconnectedRoles.get(player.getUUID());
                if (previousRole == null
                        || player.getServer().getTickCount() >= existing.disconnectedUntil
                        .getOrDefault(player.getUUID(), 0)
                        || !existing.romSha256.equals(selected)
                        || existing.roster.size() >= existing.maxPlayers) {
                    return;
                }
                OwnedMemberships.leaveOtherBeforeTransfer(
                        memberships, player.getUUID(), key, () -> leave(player, true));
                existing.disconnectedRoles.remove(player.getUUID());
                existing.disconnectedUntil.remove(player.getUUID());
                removeViewer(
                        player.getServer(),
                        player.getUUID(),
                        key,
                        false);
                existing.roster.rejoin(player.getUUID(), previousRole);
                memberships.put(player.getUUID(), key);
                if (existing.lockstep != null) {
                    existing.lockstep.forgetPlayer(player.getUUID());
                    existing.lockstep.clearInputs();
                }
                if (existing.personalSave != null && !existing.personalSave.writable()) {
                    notifySealedSave(player, existing);
                }
                if (!payload.resume() && previousRole == ArcadeRole.PLAYER_ONE) {
                    if (existing.saveMode != RomSaveMode.NONE
                            && (existing.personalSave == null || existing.personalSave.writable())) {
                        saves(player.getServer()).delete(
                                existing.saveKey,
                                existing.romSha256);
                    }
                    restart(existing);
                    sync(player.getServer(), existing, true);
                } else {
                    sync(player.getServer(), existing, false);
                    if (hasRecoverableSnapshot(existing)) {
                        syncControllerState(player, existing);
                    } else {
                        existing.pendingControllerSync.add(player.getUUID());
                        requestSessionSnapshot(player.getServer(), existing);
                    }
                }
                return;
            }
            RomSaveMode saveMode = effectiveSaveMode(
                    key,
                    library(player.getServer()).saveMode(selected));
            if (!hasSave(
                    player.getServer(),
                    key,
                    selected,
                    saveMode,
                    player.getUUID())) {
                FcNetwork.promptJoin(player, key.anchor(), selected);
                return;
            }
            resumeChoices.put(
                    player.getUUID(),
                    new ResumeChoice(key, selected, payload.resume()));
            FcNetwork.promptJoin(player, key.anchor(), selected);
        }

        private boolean validUpload(ServerPlayer player, IncomingUpload upload) {
            if(player==null||player!=upload.player||player.serverLevel()!=upload.level
                    ||player.connection.getConnection()!=upload.connection||!cn.piq.fcarcade.access.PlayerContentAccess.canUploadRom(player))return false;
            ArcadeStructure structure=validStructure(player,upload.blockPos);
            if(structure==null||player.serverLevel().getBlockState(structure.anchor()).getBlock() instanceof RetroTvBlock)return false;
            return !sessions.containsKey(new SessionKey(player.level().dimension(),structure.anchor(),structure.mode()))
                    && cn.piq.fcarcade.access.PlayerContentAccess.canUploadRom(player);
        }

        private void beginUpload(ServerPlayer player, RomUploadStartPayload payload) {
            if (!cn.piq.fcarcade.access.PlayerContentAccess.canUploadRom(player)) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                        "message.piq_fc_arcade.library_op_only"));
                return;
            }
            ArcadeStructure structure = validStructure(player, payload.blockPos());
            if (structure == null) return;
            if(player.serverLevel().getBlockState(structure.anchor()).getBlock() instanceof RetroTvBlock)return;
            SessionKey key = new SessionKey(
                    player.level().dimension(),
                    structure.anchor(),
                    structure.mode());
            Session existing = sessions.get(key);
            if (existing != null) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                        "message.piq_fc_arcade.only_player_one_changes_rom"));
                return;
            }

            if (library(player.getServer()).find(payload.sha256()) != null) {
                selectRom(player, payload.blockPos(), payload.sha256(), true);
                return;
            }
            long currentBytes = uploads.entrySet().stream()
                    .filter(entry -> !entry.getKey().equals(player.getUUID()))
                    .mapToLong(entry -> entry.getValue().buffer.totalBytes())
                    .sum();
            if ((!uploads.containsKey(player.getUUID())
                    && uploads.size() >= MAX_CONCURRENT_ROM_TRANSFERS)
                    || currentBytes + payload.totalBytes()
                    > MAX_IN_FLIGHT_UPLOAD_BYTES) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                        "message.piq_fc_arcade.rom_transfer_busy"));
                return;
            }
            uploads.put(player.getUUID(), new IncomingUpload(
                    new RomTransferBuffer(
                            payload.fileName(),
                            payload.sha256(),
                            payload.totalBytes()),
                    payload.blockPos().immutable(),
                    player.getServer().getTickCount(), player));
        }

        private void acceptUploadChunk(
                ServerPlayer player,
                RomUploadChunkPayload payload
        ) {
            if (!cn.piq.fcarcade.access.PlayerContentAccess.canUploadRom(player)) {
                uploads.remove(player.getUUID());
                return;
            }
            int serverTick = player.getServer().getTickCount();
            int lastTick = uploadTicks.getOrDefault(player.getUUID(), -1);
            int count = lastTick == serverTick
                    ? uploadCounts.getOrDefault(player.getUUID(), 0) + 1
                    : 1;
            uploadTicks.put(player.getUUID(), serverTick);
            uploadCounts.put(player.getUUID(), count);
            if (count > RomTransferLimits.CHUNKS_PER_TICK * 2) return;

            IncomingUpload upload = uploads.get(player.getUUID());
            if (upload == null || !upload.buffer.sha256().equals(payload.sha256())) return;
            if (!validUpload(player,upload)) {uploads.remove(player.getUUID());return;}
            try {
                upload.buffer.append(payload.offset(), payload.data());
                upload.lastActivityTick = serverTick;
                if (!upload.buffer.complete()) return;
                RomDescriptor descriptor = library(player.getServer()).store(
                        upload.buffer.fileName(),
                        upload.buffer.sha256(),
                        upload.buffer.completedBytes());
                uploads.remove(player.getUUID());
                if(validUpload(player,upload))selectRom(player, upload.blockPos, descriptor.sha256(), true);
            } catch (RuntimeException error) {
                uploads.remove(player.getUUID());
                player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                        "message.piq_fc_arcade.rom_transfer_failed",
                        safeMessage(error)));
            }
        }

        private void setMultiplayerEnabled(
                ServerPlayer player,
                ArcadeMultiplayerResponsePayload payload
        ) {
            Session session = sessionFor(player, payload.sessionId());
            if(session==null)session=sessions.values().stream().filter(s->s.id==payload.sessionId()&&s.homeRuntime!=null&&computeHost(s,player)).findFirst().orElse(null);
            if (session == null || session.maxPlayers != 2
                    || session.multiplayerChosen || (session.homeRuntime!=null?!computeHost(session,player)||!validHome(player.getServer(),session):session.roster.roleOf(player.getUUID())!=ArcadeRole.PLAYER_ONE)) {
                return;
            }
            session.multiplayerEnabled = payload.enabled();
            session.multiplayerChosen = true;
            if (!payload.enabled()) {
                expireApplicants(player.getServer(), session);
            }
            player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                    payload.enabled()
                            ? "message.piq_fc_arcade.multiplayer_enabled"
                            : "message.piq_fc_arcade.multiplayer_kept_private"));
        }

        private void decideJoin(
                ServerPlayer playerOne,
                ArcadeJoinDecisionPayload payload
        ) {
            Session session = sessionFor(playerOne, payload.sessionId());
            if(session!=null&&session.homeRuntime!=null){decideHome(playerOne,session,payload);return;}
            if (session == null
                    || session.roster.roleOf(playerOne.getUUID())
                    != ArcadeRole.PLAYER_ONE
                    || !session.pendingApplicants.remove(payload.applicantId())) {
                return;
            }
            ServerPlayer applicant = playerOne.getServer()
                    .getPlayerList()
                    .getPlayer(payload.applicantId());
            if (applicant == null) return;
            if (!payload.accepted()) {
                applicant.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                        "message.piq_fc_arcade.join_request_denied"));
                return;
            }
            if (!session.multiplayerEnabled
                    || session.maxPlayers != 2
                    || session.roster.size()
                    + session.disconnectedRoles.size()
                    >= session.maxPlayers
                    || applicant.level().dimension() != session.key.dimension()
                    || controllerDistanceSquared(applicant, session.key.anchor()) > MAX_DISTANCE_SQUARED) {
                applicant.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                        "message.piq_fc_arcade.join_request_expired"));
                return;
            }

            SessionKey previous = memberships.get(applicant.getUUID());
            if (session.homeConsole && !HomeControllerService.canJoin(applicant, session.key.anchor(), session.id, 1)) return;
            if (previous != null) leave(applicant, true);
            removeViewer(
                    playerOne.getServer(),
                    applicant.getUUID(),
                    session.key,
                    false);
            if (session.homeConsole && !HomeControllerService.grant(applicant, session.key.anchor(), session.id, 1)) return;
            session.roster.join(applicant.getUUID());
            memberships.put(applicant.getUUID(), session.key);
            if (session.personalSave != null && !session.personalSave.writable()) {
                notifySealedSave(applicant, session);
            }
            sync(playerOne.getServer(), session, false);
            if (session.lockstep != null) {
                if (hasFreshSnapshot(session)) {
                    syncControllerState(applicant, session);
                } else {
                    session.pendingControllerSync.add(applicant.getUUID());
                    requestSessionSnapshot(playerOne.getServer(), session);
                }
            }
            applicant.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                    "message.piq_fc_arcade.join_request_accepted"));
        }

        private static ServerPlayer playerOne(
                MinecraftServer server,
                Session session
        ) {
            for (UUID playerId : session.roster.playerIds()) {
                if (session.roster.roleOf(playerId) == ArcadeRole.PLAYER_ONE) {
                    return server.getPlayerList().getPlayer(playerId);
                }
            }
            return null;
        }

        private static void expireApplicants(
                MinecraftServer server,
                Session session
        ) {
            for (UUID applicantId : Set.copyOf(session.pendingApplicants)) {
                ServerPlayer applicant = server.getPlayerList().getPlayer(applicantId);
                if (applicant != null) {
                    applicant.sendSystemMessage(
                            net.minecraft.network.chat.Component.translatable(
                                    "message.piq_fc_arcade.join_request_expired"));
                }
            }
            session.pendingApplicants.clear();
        }

        private void requestDownload(ServerPlayer player, String sha256) {
            if (sha256 == null || !sha256.matches(RomRepository.SHA256_PATTERN)) return;
            int tick = player.getServer().getTickCount();
            if (downloads.containsKey(player.getUUID())
                    || !downloadBudget.allowLookup(player.getUUID(), tick)) return;
            RomDescriptor descriptor = library(player.getServer()).find(sha256);
            if (descriptor == null) {
                downloadBudget.missing(player.getUUID(), tick);
                return;
            }
            long outstanding = downloads.values().stream()
                    .mapToLong(download -> download.descriptor.size() - download.offset).sum();
            if (!RomDownloadBudget.canStart(downloads.size(), outstanding, descriptor.size())) return;
            downloads.put(player.getUUID(), new OutgoingDownload(descriptor));
            FcNetwork.startRomDownload(player, new RomDownloadStartPayload(
                    descriptor.fileName(),
                    descriptor.sha256(),
                    descriptor.size()));
        }

        private void decideExit(
                ServerPlayer player,
                ArcadeExitDecisionPayload payload
        ) {
            Session session = sessionFor(player, payload.sessionId());
            if(session!=null&&session.homeRuntime!=null)return;
            if (session == null
                    || session.roster.roleOf(player.getUUID())
                    != ArcadeRole.PLAYER_ONE) {
                return;
            }
            if (!payload.save()
                    || session.saveMode == RomSaveMode.NONE
                    || (session.personalSave != null && !session.personalSave.writable())
                    || session.lockstep == null) {
                if (payload.save() && session.personalSave != null
                        && !session.personalSave.writable()) notifySealedSave(player, session);
                leave(player, true, false);
                return;
            }
            pendingExits.put(
                    player.getUUID(),
                    new PendingExit(
                            session.id,
                            player.getServer().getTickCount() + 100));
            session.snapshotRequested = false;
            requestSessionSnapshot(player.getServer(), session);
            player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                    "message.piq_fc_arcade.saving_before_exit"));
        }

        private void leave(ServerPlayer player, boolean notifyPlayer) {
            leave(player, notifyPlayer, true);
        }

        private void leave(
                ServerPlayer player,
                boolean notifyPlayer,
                boolean persist
        ) {
            SessionKey key = memberships.get(player.getUUID());
            if (key == null) return;
            Session before = sessions.get(key);
            if(before!=null&&before.homeRuntime!=null){detachHome(player,before);return;}
            if (before != null && before.homeConsole && before.roster.roleOf(player.getUUID()) == ArcadeRole.PLAYER_ONE) {
                close(player.getServer(), before, persist);
                return;
            }
            if (!OwnedMemberships.removeIfOwnedBy(memberships, player.getUUID(), key)) return;
            Session session = sessions.get(key);
            if (session == null) return;
            if (session.homeConsole) HomeControllerService.memberLeft(player, session.id);
            pendingExits.remove(player.getUUID());
            session.pendingControllerSync.remove(player.getUUID());
            session.resyncTicks.remove(player.getUUID());
            if (session.lockstep != null) session.lockstep.forgetPlayer(player.getUUID());
            if (notifyPlayer) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                        "message.piq_fc_arcade.machine_left",
                        session.key.anchor().getX(),
                        session.key.anchor().getY(),
                        session.key.anchor().getZ()));
            }
            ArcadeRole removedRole = session.roster.roleOf(player.getUUID());
            if (persist && removedRole == ArcadeRole.PLAYER_ONE) {
                saveState(player.getServer(), session);
            }
            sealPersonalSave(player.getServer(), session, player.getUUID());
            if (removedRole == ArcadeRole.PLAYER_ONE) {
                finishScoreRound(player.getServer(), session);
            }
            session.roster.remove(player.getUUID());
            if (removedRole == ArcadeRole.PLAYER_ONE) {
                expireApplicants(player.getServer(), session);
            }
            inputTicks.remove(player.getUUID());
            inputCounts.remove(player.getUUID());
            if (session.roster.isEmpty()) {
                if (notifyPlayer) sendInactive(player, session);
                close(player.getServer(), session, persist);
            } else {
                if (session.lockstep != null
                        && removedRole != ArcadeRole.SPECTATOR) {
                    ControllerDepartureInputs.clear(session.lockstep, session.homeConsole, removedRole.controllerIndex());
                }
                sync(player.getServer(), session, false);
                if (notifyPlayer) {
                    session.viewers.add(player.getUUID());
                    addTracking(viewerships, player.getUUID(), session.key);
                    sendViewerSession(player, session);
                }
            }
        }

        private void disconnect(ServerPlayer player) {
            for(Session powered:List.copyOf(sessions.values()))if(powered.homeRuntime!=null&&powered.homeRuntime.host(player.getUUID(),player.connection.getConnection())){
                if(powered.hosted==null)close(player.getServer(),powered);else{collectHostedSnapshot(player.getServer(),powered);saveState(player.getServer(),powered);sealPersonalSave(player.getServer(),powered,player.getUUID());}
            }
            storageNoticeAfter.remove(player.getUUID());
            uploads.remove(player.getUUID());
            downloads.remove(player.getUUID());
            downloadBudget.forget(player.getUUID());
            uploadTicks.remove(player.getUUID());
            uploadCounts.remove(player.getUUID());
            resumeChoices.remove(player.getUUID());
            saveSlotChoices.remove(player.getUUID());
            homeSaveRequests.remove(player.getUUID());
            pendingExits.remove(player.getUUID());
            sessions.values().forEach(
                    session -> {
                        session.pendingApplicants.remove(player.getUUID());
                        HomeRequest request=session.homeRequests.get(player.getUUID());
                        if(request!=null&&request.connection()==player.connection.getConnection())session.homeRequests.remove(player.getUUID(),request);
                    });
            removeViewer(player.getServer(), player.getUUID(), false);
            SessionKey key = memberships.get(player.getUUID());
            Session session = key == null ? null : sessions.get(key);
            if (session != null && session.homeConsole) { leave(player, false); return; }
            if (session != null && session.roster.size() > 1) {
                ArcadeRole previousRole =
                        session.roster.roleOf(player.getUUID());
                if (previousRole == ArcadeRole.PLAYER_ONE) {
                    saveState(player.getServer(), session);
                    finishScoreRound(player.getServer(), session);
                }
                sealPersonalSave(player.getServer(), session, player.getUUID());
                OwnedMemberships.removeIfOwnedBy(memberships, player.getUUID(), session.key);
                inputTicks.remove(player.getUUID());
                inputCounts.remove(player.getUUID());
                session.pendingControllerSync.remove(player.getUUID());
                session.resyncTicks.remove(player.getUUID());
                if (session.lockstep != null) session.lockstep.forgetPlayer(player.getUUID());
                session.roster.remove(player.getUUID());
                session.disconnectedRoles.put(player.getUUID(), previousRole);
                session.disconnectedUntil.put(
                        player.getUUID(),
                        player.getServer().getTickCount() + 1_200);
                if (session.lockstep != null) session.lockstep.clearInputs();
                sync(player.getServer(), session, false);
                return;
            }
            leave(player, false);
        }

        private void tick(MinecraftServer server) {
            for(var stopped:List.copyOf(closingHosted))if(stopped.hosted.terminated()){
                collectHostedSnapshot(server,stopped);boolean clean=stopped.hosted.error()==null;
                boolean saved=clean&&stopped.saveMode==RomSaveMode.NONE;
                // A previous periodic snapshot is not evidence that a failed final capture succeeded.
                if(stopped.hostedFinalPersist&&clean)saved=saveState(server,stopped)||saved;
                if(stopped.homeLaunch!=null)stopped.homeLaunch.finished(saved,saved?"":"FC 最后进度未确认保存；原档保留。");
                closingHosted.remove(stopped);
            }
            HomeControllerService.tick(server);
            cn.piq.fcarcade.home.HomeZapperService.tick(server);
            if (scoreStore != null && server.getTickCount() % 200 == 0) {
                try {
                    scoreStore.flush();
                } catch (RuntimeException error) {
                    FcArcadeMod.LOGGER.error(
                            "[PIQ FC] Failed to save leaderboard",
                            error);
                }
            }
            if(server.getTickCount()%1200==0){
                Set<UUID> playing=new HashSet<>();
                for(var session:sessions.values())if(session.lockstep!=null&&session.snapshot!=null){playing.addAll(session.roster.playerIds());if(session.homeRuntime!=null)playing.add(session.homeRuntime.host());}
                for(var id:playing)try{saves(server).played(id,Instant.now());}catch(RuntimeException error){FcArcadeMod.LOGGER.warn("FC play activity was not persisted; retention will remain conservative",error);}
            }
            if (server.getTickCount() >= nextSaveCleanupTick) {
                cleanupExpiredSaves(server, false);
                nextSaveCleanupTick = server.getTickCount() + 432_000;
            }
            tickTransfers(server);
            tickPendingExits(server);
            homeSaveRequests.entrySet().removeIf(e->e.getValue().intent().expired(server.getTickCount())||!current(server.getPlayerList().getPlayer(e.getKey())));
            if (IdleDisplaySchedule.shouldReconcile(
                    server.getTickCount(),
                    idleDisplaysInitialized)) {
                refreshIdleScoreDisplays(server);
                idleDisplaysInitialized = true;
            } else if (IdleDisplaySchedule.shouldRotate(
                    server.getTickCount(),
                    idleDisplaysInitialized,
                    leaderboardPageSeconds(server) * 20)) {
                rotateIdleScoreDisplays(server);
            }
            for (Session session : sessions.values().toArray(Session[]::new)) {
                if (session.lockstep == null || session.lockstep.epoch() == 0) continue;
                if(session.homeRuntime!=null&&!validHome(server,session)){close(server,session);continue;}
                if(session.netplay!=null){if(session.netplay.closed()){close(server,session);continue;}renewNetplay(server,session);if(session.variant.isZapper())sendNetplayGun(server,session);if(!session.homeReady&&server.getTickCount()-session.homeStartTick>1200)close(server,session);continue;}
                if(session.hosted!=null&&!tickHosted(server,session))continue;
                if(session.homeRuntime!=null&&!session.homeReady){if(server.getTickCount()-session.homeStartTick>1200)close(server,session);continue;}
                session.homeRequests.values().removeIf(r->server.getTickCount()>=r.deadline()||!current(server.getPlayerList().getPlayer(r.player()))
                        ||server.getPlayerList().getPlayer(r.player()).connection.getConnection()!=r.connection());
                if (session.hosted==null&&!session.timeline.canRecordFrames(LockstepState.FRAMES_PER_SERVER_TICK)) {
                    FcArcadeMod.LOGGER.warn("[PIQ FC] Closing session {}: no accepted snapshot within bounded input history", session.id);
                    if(session.playerMedia)for(UUID id:session.allPlayers()){
                        var participant=server.getPlayerList().getPlayer(id);
                        if(current(participant))participant.sendSystemMessage(Component.literal("[FC] 主持运行端未返回有效进度，已安全停机；仅保留最近确认的快照，未确认的操作可能丢失。"));
                    }
                    close(server, session);
                    continue;
                }
                for (int frame = 0; frame < LockstepState.FRAMES_PER_SERVER_TICK; frame++) {
                    LockstepState.FrameStep step = session.lockstep.advanceFrame();
                    if(session.hosted!=null)session.hosted.input(step);else{session.timeline.record(step);broadcastFrame(server, session, step);}
                }
            }

            cleanupTicks++;
            if (cleanupTicks < 20) return;
            cleanupTicks = 0;

            for (Session session : sessions.values().toArray(Session[]::new)) {
                ServerLevel machineLevel = server.getLevel(session.key.dimension());
                if (machineLevel != null && machineLevel.hasChunkAt(session.key.anchor())
                        && machineLevel.getBlockState(session.key.anchor()).getBlock()
                        instanceof cn.piq.fcarcade.world.DualCabinetBlock
                        && !cn.piq.fcarcade.world.DualCabinetStructure.complete(machineLevel, session.key.anchor())) {
                    close(server, session);
                    continue;
                }
                if (machineLevel != null && machineLevel.hasChunkAt(session.key.anchor())
                        && machineLevel.getBlockState(session.key.anchor()).getBlock() instanceof RetroTvBlock
                        && !session.romSha256.equals(selectedRom(server, session.key))) {
                    close(server, session);
                    continue;
                }
                if (machineLevel != null && machineLevel.hasChunkAt(session.key.anchor())
                        && !(machineLevel.getBlockState(session.key.anchor()).getBlock()
                        instanceof FcArcadeBlock)) {
                    close(server, session);
                    continue;
                }
                ArcadeOccupancyDisplay.refresh(
                        server,
                        session.key.dimension(),
                        session.key.anchor(),
                        playerNames(server, session));
                for (Map.Entry<UUID, Integer> disconnected
                        : new HashMap<>(session.disconnectedUntil).entrySet()) {
                    if (server.getTickCount() < disconnected.getValue()) continue;
                    session.disconnectedRoles.remove(disconnected.getKey());
                    session.disconnectedUntil.remove(disconnected.getKey());
                }
                ServerLevel level = server.getLevel(session.key.dimension());
                if (level == null
                        || !(level.getBlockState(session.key.anchor()).getBlock()
                        instanceof FcArcadeBlock arcadeBlock)
                        || arcadeBlock.mode() != session.key.mode()) {
                    close(server, session);
                    continue;
                }

                boolean changed = false;
                if(session.homeRuntime!=null){
                    for(UUID id:session.roster.playerIds()){
                        ServerPlayer p=server.getPlayerList().getPlayer(id);
                        var c=session.homeRuntime.player(id);
                        if(!current(p)||c==null||c.connection()!=p.connection.getConnection())detachStaleHome(server,session,id);
                        else if(validStructure(p,session.key.anchor())==null)detachHome(p,session);
                    }
                    if(server.getTickCount()-session.lastSnapshotReceivedTick>=SNAPSHOT_REFRESH_TICKS)requestSessionSnapshot(server,session);
                    continue;
                }
                boolean controllerChanged = false;
                boolean playerOneChanged = false;
                for (UUID playerId : session.roster.playerIds()) {
                    ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                    if (player == null) {
                        ArcadeRole removedRole = session.roster.roleOf(playerId);
                        if (removedRole == ArcadeRole.PLAYER_ONE) {
                            saveState(server, session);
                            finishScoreRound(server, session);
                        }
                        sealPersonalSave(server, session, playerId);
                        if (OwnedMemberships.removeIfOwnedBy(memberships, playerId, session.key)) {
                            inputTicks.remove(playerId);
                            inputCounts.remove(playerId);
                        }
                        session.pendingControllerSync.remove(playerId);
                        session.resyncTicks.remove(playerId);
                        if (session.lockstep != null) session.lockstep.forgetPlayer(playerId);
                        session.roster.remove(playerId);
                        controllerChanged |= removedRole != ArcadeRole.SPECTATOR;
                        playerOneChanged |= removedRole == ArcadeRole.PLAYER_ONE;
                        changed = true;
                        continue;
                    }
                    boolean valid = session.key.equals(memberships.get(playerId))
                            && player.level().dimension() == session.key.dimension()
                            && controllerDistanceSquared(player, session.key.anchor()) <= MAX_DISTANCE_SQUARED;
                    if (!valid) {
                        ArcadeRole removedRole = session.roster.roleOf(playerId);
                        if (removedRole == ArcadeRole.PLAYER_ONE) {
                            saveState(server, session);
                            finishScoreRound(server, session);
                        }
                        sealPersonalSave(server, session, playerId);
                        if (OwnedMemberships.removeIfOwnedBy(memberships, playerId, session.key)) {
                            inputTicks.remove(playerId);
                            inputCounts.remove(playerId);
                        }
                        session.pendingControllerSync.remove(playerId);
                        session.resyncTicks.remove(playerId);
                        if (session.lockstep != null) session.lockstep.forgetPlayer(playerId);
                        session.roster.remove(playerId);
                        sendInactive(player, session);
                        controllerChanged |= removedRole != ArcadeRole.SPECTATOR;
                        playerOneChanged |= removedRole == ArcadeRole.PLAYER_ONE;
                        changed = true;
                    }
                }
                if (playerOneChanged) expireApplicants(server, session);
                if (session.roster.isEmpty()) {
                    close(server, session);
                } else if (changed) {
                    if (session.lockstep != null && controllerChanged) {
                        session.lockstep.clearInputs();
                    }
                    sync(server, session, false);
                }
                if (sessions.containsKey(session.key)
                        && session.lockstep != null
                        && session.lockstep.epoch() > 0
                        && server.getTickCount() - session.lastSnapshotReceivedTick
                        >= SNAPSHOT_REFRESH_TICKS) {
                    requestSessionSnapshot(server, session);
                }
            }
            reconcileViewers(server);
        }

        private void collectHostedSnapshot(MinecraftServer server,Session session){
            byte[] state=session.hosted.snapshot();if(state==null)return;
            if(!session.variant.acceptsPersistentStateHeader(state,session.romSha256)){FcArcadeMod.LOGGER.warn("[PIQ FC] Rejected hosted snapshot with wrong core identity");return;}
            session.snapshot=state;session.snapshotEpoch=session.lockstep.epoch();session.snapshotFrame=session.lockstep.targetFrame();session.lastSnapshotReceivedTick=server.getTickCount();
        }
        private boolean tickHosted(MinecraftServer server,Session session){
            var run=session.hosted;collectHostedSnapshot(server,session);
            if(run.error()!=null||run.terminated()||!CabinetHostingConfig.SPEC.isLoaded()||!CabinetHostingConfig.ENABLED.get()){
                if(run.error()!=null)for(UUID id:session.allPlayers()){var p=server.getPlayerList().getPlayer(id);if(current(p))p.displayClientMessage(Component.literal("FC 服务器托管已停止："+run.error()),false);}
                close(server,session);return false;
            }
            if(!session.homeReady&&run.ready()){
                if(session.homeLaunch!=null&&session.homeLaunch.stage()!=cn.piq.retro.flow.DeviceSessionFlow.Stage.READY&&!session.homeLaunch.ready()){close(server,session,false);return false;}
                session.homeReady=true;var level=server.getLevel(session.key.dimension());if(level!=null)cn.piq.fcarcade.home.HomeApplianceService.refresh(level,session.key.anchor());
                var host=server.getPlayerList().getPlayer(session.homeRuntime.host());if(computeHost(session,host)&&host.level().dimension()==session.key.dimension()){
                    var console=HomeHardware.connectedConsole(host.serverLevel(),session.key.anchor());HomeControllerService.attachHeld(host,console);
                    if(session.homeRuntime.gunMode()&&cn.piq.fcarcade.home.ZapperStandService.loanPlayer(console)==host)takeHomeZapper(host,console,host.getMainHandItem());
                }
            }
            if(!computeHost(session,server.getPlayerList().getPlayer(session.homeRuntime.host()))&&session.roster.size()==0){close(server,session);return false;}
            List<CabinetMediaPacket> batch;int batches=0;
            while(batches++<8&&(batch=run.poll())!=null){
                var recipients=List.copyOf(session.allPlayers());int start=recipients.isEmpty()?0:Math.floorMod(session.hostedRecipientCursor[batch.getFirst().kind()]++,recipients.size());
                for(int offset=0;offset<recipients.size();offset++){
                    UUID id=recipients.get((start+offset)%recipients.size());
                    var p=server.getPlayerList().getPlayer(id);if(!current(p)||p.level().dimension()!=session.key.dimension())continue;
                    var control=session.homeRuntime.player(id);if(!computeHost(session,p)&&(control==null||control.connection()!=p.connection.getConnection()))continue;
                    int bytes=batch.stream().mapToInt(part->part.data().length+320).sum();
                    if(run.budget(server,bytes))FcHomeHostedNetwork.send(p,session.id,session.lockstep.epoch(),batch);
                }
                WatchService.relay(server,run.source,run.token,batch);
            }
            return true;
        }

        private void cleanupExpiredSaves(
                MinecraftServer server,
                boolean notifyOperators
        ) {
            int retentionDays = settings(server).saveRetentionDays();
            if (retentionDays == 0) return;
            int deleted;
            try { deleted = saves(server).cleanupOlderThanDays(
                    retentionDays,
                    Instant.now(),info->isSaveActive(info)||sessions.values().stream().anyMatch(s->{
                        String owner=FcSaveManagementStore.owner(info.saveKey());
                        return !owner.isEmpty()&&(s.roster.playerIds().stream().anyMatch(id->id.toString().equals(owner))||s.homeRuntime!=null&&s.homeRuntime.host().toString().equals(owner));
                    }));
            } catch (RuntimeException error) {
                FcArcadeMod.LOGGER.warn("FC personal-save cleanup stopped; remaining saves kept", error);
                return;
            }
            if (deleted <= 0) return;
            FcArcadeMod.LOGGER.info(
                    "[PIQ FC] 已将 {} 个所属玩家超过 {} 天未游玩的个人存档移入回收目录",
                    deleted,
                    retentionDays);
            if (notifyOperators) {
                server.getPlayerList().getPlayers().stream()
                        .filter(player -> player.hasPermissions(2))
                        .forEach(player -> player.sendSystemMessage(
                                net.minecraft.network.chat.Component.translatable(
                                        "message.piq_fc_arcade.saves_cleaned",
                                        deleted,
                                        retentionDays)));
            }
        }

        private void tickPendingExits(MinecraftServer server) {
            int serverTick = server.getTickCount();
            for (Map.Entry<UUID, PendingExit> entry
                    : new HashMap<>(pendingExits).entrySet()) {
                if (serverTick < entry.getValue().deadlineTick()) continue;
                pendingExits.remove(entry.getKey());
                ServerPlayer player =
                        server.getPlayerList().getPlayer(entry.getKey());
                if (player == null) continue;
                Session session = sessionFor(
                        player,
                        entry.getValue().sessionId());
                if (session == null) continue;
                if (saveState(server, session)) {
                    player.sendSystemMessage(Component.literal(
                            "[FC] 未收到新的退出快照，已保存最近确认的快照；最后一小段进度可能未保存。"));
                    leave(player, true, false);
                }
            }
        }

        private void tickTransfers(MinecraftServer server) {
            int serverTick = server.getTickCount();
            uploads.entrySet().removeIf(entry -> {
                if(!validUpload(server.getPlayerList().getPlayer(entry.getKey()),entry.getValue()))return true;
                if (serverTick - entry.getValue().lastActivityTick
                        <= RomTransferLimits.TRANSFER_TIMEOUT_TICKS) {
                    return false;
                }
                ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
                if (player != null) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                            "message.piq_fc_arcade.rom_transfer_timeout"));
                }
                return true;
            });

            int chunksRemaining = RomDownloadBudget.GLOBAL_CHUNKS_PER_TICK;
            // Move served transfers to the tail so every player progresses under the global cap.
            for (UUID playerId : List.copyOf(downloads.keySet())) {
                if (chunksRemaining == 0) break;
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (player == null) {
                    downloads.remove(playerId);
                    downloadBudget.forget(playerId);
                    continue;
                }
                OutgoingDownload download = downloads.remove(playerId);
                for (int chunk = 0;
                     chunk < RomTransferLimits.CHUNKS_PER_TICK
                             && chunksRemaining > 0
                             && download.offset < download.descriptor.size();
                     chunk++) {
                    int end = Math.min(
                            download.offset + RomTransferLimits.CHUNK_BYTES,
                            download.descriptor.size());
                    byte[] data = download.descriptor.copyBytes(download.offset, end);
                    FcNetwork.sendRomDownloadChunk(player, new RomDownloadChunkPayload(
                            download.sha256,
                            download.offset,
                            data));
                    download.offset = end;
                    chunksRemaining--;
                }
                if (download.offset < download.descriptor.size()) downloads.put(playerId, download);
            }
        }

        private void handleInput(ServerPlayer player, ArcadeInputPayload payload) {
            handleInput(player,payload,null);
        }
        private void handleHomeInput(ServerPlayer player,cn.piq.fcarcade.ArcadeHomeInputPayload payload){handleInput(player,payload.input(),payload.lease());}
        private void handleInput(ServerPlayer player,ArcadeInputPayload payload,UUID physicalLease){
            Session session = sessionFor(player, payload.sessionId());
            if(session!=null&&session.netplay!=null&&!session.variant.isZapper())return;
            if (session == null || session.lockstep == null || payload.epoch()!=session.lockstep.epoch()) return;
            ArcadeRole role = session.roster.roleOf(player.getUUID());
            if (role == null || role.controllerIndex() < 0) return;
            int inputPort=role.controllerIndex();UUID assistedGun=null;
            if(session.homeRuntime!=null&&(!validHome(player.getServer(),session)||physicalLease==null
                    ||(inputPort=session.homeRuntime.buttonPort(player.getUUID(),player.connection.getConnection(),physicalLease))<0))return;
            if(session.homeRuntime==null&&physicalLease!=null)return;
            if(session.homeRuntime!=null&&session.homeRuntime.gunMode()){
                boolean heldPad=session.homeRuntime.authorized(player.getUUID(),player.connection.getConnection(),physicalLease,0)
                        &&HomeControllerService.authorized(player,session.key.anchor(),session.id,0);
                if(!heldPad){
                    assistedGun=cn.piq.fcarcade.home.HomeZapperService.keyboardLease(player,session.id,session.lockstep.epoch());
                    if(assistedGun==null||!session.homeRuntime.authorized(player.getUUID(),player.connection.getConnection(),assistedGun,1))return;
                }
            } else if (session.homeConsole && !(session.homeRuntime==null&&session.zapperBinding!=null
                    ?cn.piq.fcarcade.home.HomeZapperService.authorized(player,session.zapperBinding,true)
                    :HomeControllerService.authorized(player, session.key.anchor(), session.id, role.controllerIndex()))) {
                session.lockstep.clearController(role.controllerIndex());
                if(session.hosted!=null)session.hosted.release(role.controllerIndex());
                return;
            }

            int serverTick = player.getServer().getTickCount();
            int lastTick = inputTicks.getOrDefault(player.getUUID(), -1);
            int count = lastTick == serverTick
                    ? inputCounts.getOrDefault(player.getUUID(), 0) + 1
                    : 1;
            inputTicks.put(player.getUUID(), serverTick);
            inputCounts.put(player.getUUID(), count);
            boolean accepted=session.lockstep.acceptInput(
                    session.homeRuntime==null?player.getUUID():physicalLease,
                    payload.epoch(),
                    inputPort,
                    payload.sequence(),
                    payload.buttonMask(),
                    payload.forceRelease(),
                    count > MAX_INPUT_PACKETS_PER_TICK);
            if(accepted&&session.homeRuntime!=null&&inputPort==0)
                session.homeRuntime.recordButtons(player.getUUID(),player.connection.getConnection(),physicalLease,assistedGun);
        }

        private void handleZapper(ServerPlayer player,cn.piq.fcarcade.ArcadeZapperInputPayload payload){
            Session s=sessionFor(player,payload.sessionId());
            if(s==null||s.lockstep==null||!s.variant.isZapper()||s.zapperBinding==null
                    ||s.homeRuntime==null&&s.roster.roleOf(player.getUUID())!=ArcadeRole.PLAYER_ONE||payload.epoch()!=s.lockstep.epoch()
                    ||!s.zapperBinding.lease().equals(payload.lease()))return;
            if(s.homeRuntime!=null&&(!validHome(player.getServer(),s)||!s.homeRuntime.authorized(player.getUUID(),player.connection.getConnection(),payload.lease(),1)))return;
            UUID sequenceOwner=s.homeRuntime==null?player.getUUID():payload.lease();
            if(!s.lockstep.canAcceptZapper(sequenceOwner,payload.epoch(),payload.sequence(),payload.packed(),payload.forceRelease()))return;
            if(!cn.piq.fcarcade.home.HomeZapperService.authorized(player,s.zapperBinding,true)){s.lockstep.clearZapper();if(s.hosted!=null)s.hosted.release(1);if(s.homeRuntime==null||s.homeRuntime.clearGunButtons(payload.lease())){s.lockstep.clearController(0);if(s.hosted!=null)s.hosted.release(0);}return;}
            int tick=player.getServer().getTickCount();int count=inputTicks.getOrDefault(player.getUUID(),-1)==tick?inputCounts.getOrDefault(player.getUUID(),0)+1:1;
            inputTicks.put(player.getUUID(),tick);inputCounts.put(player.getUUID(),count);
            if(count>MAX_INPUT_PACKETS_PER_TICK&&!payload.forceRelease()){
                if(s.hosted!=null)s.hosted.release(1);
                s.lockstep.acceptZapper(sequenceOwner,payload.epoch(),payload.sequence(),cn.piq.fcarcade.session.ZapperInput.NEUTRAL,false,true);return;
            }
            // The sender's x/y are hints only. The server traces its own current eye/look and world collision.
            var hit=payload.offscreen()||payload.forceRelease()?java.util.Optional.<cn.piq.fcarcade.layout.ScreenRayMapping.Pixel>empty()
                    :cn.piq.fcarcade.home.HomeZapperAim.sample(player,s.zapperBinding);
            int state=hit.isPresent()?cn.piq.fcarcade.session.ZapperInput.pack(hit.get().x(),hit.get().y(),false,payload.trigger())
                    :cn.piq.fcarcade.session.ZapperInput.pack(0,0,true,payload.trigger());
            if(s.lockstep.acceptZapper(sequenceOwner,payload.epoch(),payload.sequence(),state,payload.forceRelease(),count>MAX_INPUT_PACKETS_PER_TICK)){
                cn.piq.fcarcade.home.HomeZapperService.observedAim(player,s.zapperBinding);
                if(payload.forceRelease()){if(s.hosted!=null)s.hosted.release(1);if(s.homeRuntime!=null&&s.homeRuntime.clearGunButtons(payload.lease())){s.lockstep.clearController(0);if(s.hosted!=null)s.hosted.release(0);}}
            }
        }

        private void handleScore(ServerPlayer player, ArcadeScorePayload payload) {
            Session session = sessionFor(player, payload.sessionId());
            if (session == null||session.hosted!=null
                    || session.roster.roleOf(player.getUUID()) != ArcadeRole.PLAYER_ONE
                    || !RoadRaceScoreRule.ROM_SHA256.equalsIgnoreCase(
                            session.romSha256)
                    || payload.score() < 0
                    || payload.score() > RoadRaceScoreRule.MAX_SCORE) {
                return;
            }
            if (payload.score() == 0) {
                finishScoreRound(player.getServer(), session);
                return;
            }
            if (session.scoreRound.startsNewRound(payload.score())) {
                finishScoreRound(player.getServer(), session);
            }
            session.scoreRound.observe(payload.score());

            ArcadeScoreStore store = scores(player.getServer());
            int previousPersonal = store.personalBest(
                    session.romSha256,
                    player.getUUID());
            List<ArcadeScoreStore.ScoreEntry> previousTop = store.top(
                    session.romSha256,
                    1);
            int previousGlobal = previousTop.isEmpty()
                    ? 0
                    : previousTop.getFirst().score();
            boolean updated = store.submit(
                    session.romSha256,
                    player.getUUID(),
                    player.getName().getString(),
                    payload.score());
            if (!updated) return;

            session.scoreRound.recordImprovement(
                    player.getUUID(),
                    player.getName().getString(),
                    previousPersonal,
                    previousGlobal,
                    payload.score()).ifPresent(breakthrough ->
                            announceScoreBreakthrough(
                                    player.getServer(),
                                    breakthrough));
            refreshIdleScoreDisplays(player.getServer());
        }

        private static void announceScoreBreakthrough(
                MinecraftServer server,
                RoadRaceRoundState.Breakthrough breakthrough
        ) {
            boolean globalBest = breakthrough.globalBest();
            Component announcement = Component.literal(
                            globalBest
                                    ? "[FC \u5168\u670d\u65b0\u7eaa\u5f55] "
                                    : "[FC \u4e2a\u4eba\u65b0\u7eaa\u5f55] ")
                    .withStyle(globalBest
                            ? ChatFormatting.GOLD
                            : ChatFormatting.AQUA)
                    .append(Component.literal(breakthrough.playerName())
                            .withStyle(ChatFormatting.YELLOW))
                    .append(Component.literal(String.format(
                                    java.util.Locale.ROOT,
                                    " \u516c\u8def\u8d5b\u8f66: %06d -> %06d",
                                    breakthrough.previousPersonal(),
                                    breakthrough.score()))
                            .withStyle(ChatFormatting.WHITE));
            server.getPlayerList().broadcastSystemMessage(announcement, false);
        }

        private void finishScoreRound(MinecraftServer server, Session session) {
            session.scoreRound.finish().ifPresent(settlement -> {
                int bestOther = scores(server).top(session.romSha256, 2).stream()
                        .filter(entry -> !entry.playerId().equals(
                                settlement.playerId()))
                        .mapToInt(ArcadeScoreStore.ScoreEntry::score)
                        .max()
                        .orElse(0);
                boolean globalBest = settlement.finalScore() > bestOther;
                Component announcement = Component.literal(
                                "[FC \u672c\u5c40\u7ed3\u7b97] ")
                        .withStyle(globalBest
                                ? ChatFormatting.GOLD
                                : ChatFormatting.AQUA)
                        .append(Component.literal(settlement.playerName())
                                .withStyle(ChatFormatting.YELLOW))
                        .append(Component.literal(String.format(
                                        java.util.Locale.ROOT,
                                        " \u516c\u8def\u8d5b\u8f66\u6700\u7ec8\u6210\u7ee9: %06d (%s)",
                                        settlement.finalScore(),
                                        globalBest
                                                ? "\u5168\u670d\u65b0\u7eaa\u5f55"
                                                : "\u4e2a\u4eba\u65b0\u7eaa\u5f55"))
                                .withStyle(ChatFormatting.WHITE));
                server.getPlayerList().broadcastSystemMessage(
                        announcement,
                        false);
            });
        }

        private void requestReset(ServerPlayer player, ArcadeResetPayload payload) {
            Session session = sessionFor(player, payload.sessionId());
            // Appliance reset is a physical button transaction, never a stale keyboard packet.
            if(session!=null&&session.homeRuntime!=null)return;
            if (session == null || session.lockstep == null
                    || payload.epoch() != session.lockstep.epoch()
                    || session.roster.roleOf(player.getUUID()) != ArcadeRole.PLAYER_ONE) {
                return;
            }
            finishScoreRound(player.getServer(), session);
            restart(session);
            sync(player.getServer(), session, true);
        }

        private void handleDigest(ServerPlayer player, ArcadeDigestPayload payload) {
            Session session = sessionForParticipant(player, payload.sessionId());
            if (session == null || session.hosted!=null || session.playerMedia || session.lockstep == null || session.digests == null) return;

            ServerPlayer reference = session.homeRuntime==null?playerOne(player.getServer(),session):player.getServer().getPlayerList().getPlayer(session.homeRuntime.host());
            if (reference == null) return;
            LockstepDigestTracker.Comparison comparison = session.digests.compareToReference(
                    player.getUUID(),
                    reference.getUUID(),
                    payload.epoch(),
                    payload.frame(),
                    session.lockstep.targetFrame(),
                    payload.digest(),
                    session.allPlayers());
            for (UUID playerId : comparison.mismatches()) {
                ServerPlayer member = player.getServer().getPlayerList().getPlayer(playerId);
                if (member != null && allowResync(member, session)) {
                    member.sendSystemMessage(Component.literal(
                            "[FC] 本地模拟状态与 1P 不一致，正在仅恢复你的画面；本局不会重开。"));
                    resyncParticipant(member, session);
                }
            }
        }

        private static void restart(Session session) {
            session.mediaToken=UUID.randomUUID();
            session.lockstep.restart();
            // A host can reject delayed demands from an earlier core epoch without
            // trusting a block position alone. This ID is not upload authority;
            // the random token and exact host Connection remain mandatory.
            session.mediaSource=new UUID(session.id,session.lockstep.epoch());
            if(session.homeRuntime!=null)session.homeRuntime.reset();
            if(session.homeRuntime!=null)session.homeReady=false;
            if(session.zapperBinding!=null)session.zapperBinding=session.zapperBinding.withEpoch(session.lockstep.epoch());
            session.digests.reset(session.lockstep.epoch());
            session.timeline.reset(session.lockstep.epoch());
            session.snapshot = null;
            session.snapshotEpoch = 0;
            session.snapshotFrame = 0;
            session.snapshotRequested = false;
            session.snapshotRequestTick = 0;
            session.lastSnapshotReceivedTick = 0;
        }

        private Session sessionFor(ServerPlayer player, long sessionId) {
            if(!current(player))return null;
            for(Session s:sessions.values())if(s.id==sessionId&&s.homeRuntime!=null){
                var c=s.homeRuntime.player(player.getUUID());
                return computeHost(s,player)||c!=null&&c.connection()==player.connection.getConnection()?s:null;
            }
            SessionKey key = memberships.get(player.getUUID());
            if (key == null) return null;
            Session session = sessions.get(key);
            return session != null && session.id == sessionId ? session : null;
        }

        private Session sessionForParticipant(ServerPlayer player, long sessionId) {
            if(!current(player))return null;
            Session powered=sessionFor(player,sessionId);if(powered!=null&&powered.homeRuntime!=null)return powered;
            SessionKey key = memberships.get(player.getUUID());
            if (key != null) {
                Session memberSession = sessions.get(key);
                if (memberSession != null && memberSession.homeRuntime==null && memberSession.id == sessionId) {
                    return memberSession;
                }
            }
            for (SessionKey viewerKey : trackedKeys(
                    viewerships,
                    player.getUUID())) {
                Session viewerSession = sessions.get(viewerKey);
                if (viewerSession != null && viewerSession.id == sessionId) {
                    return viewerSession;
                }
            }
            return null;
        }

        private void close(MinecraftServer server, Session session) {
            close(server, session, true);
        }

        private void close(
                MinecraftServer server,
                Session session,
                boolean persist
        ) {
            finishScoreRound(server, session);
            boolean coordinated=session.homeLaunch!=null&&session.homeLaunch.beginStopping();
            boolean saveConfirmed=session.saveMode==RomSaveMode.NONE;
            if(coordinated&&session.netplay!=null&&session.saveMode!=RomSaveMode.NONE){
                boolean waiting=cn.piq.fcarcade.netplay.NetplaySaveServer.awaitFinish(server,session.id,session.homeLaunchHost,
                    result->session.homeLaunch.finished(result.clean(),result.clean()?"FC 已正常保存关机。":"FC 保存未完成："+result.reason()));
                if(!waiting)session.homeLaunch.finished(false,"FC 保存会话已失效，最后进度未确认；原档保留。");
            }
            if(session.netplay!=null){
                if(persist&&session.homeReady)cn.piq.fcarcade.netplay.NetplaySaveServer.retire(server,session.id);
                else cn.piq.fcarcade.netplay.NetplaySaveServer.abort(server,session.id,session.homeLaunchHost,"开局取消或未就绪，未授权覆盖原档");
                cn.piq.fcarcade.netplay.NetplayNetwork.retire(session.netplay);
            }
            if(session.playerMedia)WatchService.closed(server,session.mediaSource,session.mediaToken);
            if(session.hosted!=null){collectHostedSnapshot(server,session);session.hostedFinalPersist=persist;session.hosted.close();closingHosted.add(session);}
            if (persist) saveConfirmed=saveState(server, session)||saveConfirmed;
            if (session.homeConsole) {if(session.homeRuntime!=null)HomeControllerService.deactivateSession(server,session.id);else HomeControllerService.closeSession(server, session.id);}
            if(session.zapperBinding!=null)cn.piq.fcarcade.home.HomeZapperService.closeSession(server,session.id);
            expireApplicants(server, session);
            for (UUID playerId : session.allPlayers()) {
                // allPlayers() also contains nearby spectators. A spectator may be
                // controlling a different cabinet, so only clear the membership
                // when it still belongs to the session being closed.
                boolean removedMembership = OwnedMemberships.removeIfOwnedBy(
                        memberships,
                        playerId,
                        session.key);
                removeTracking(viewerships, playerId, session.key);
                if (removedMembership) {
                    inputTicks.remove(playerId);
                    inputCounts.remove(playerId);
                }
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (player != null) sendInactive(player, session);
            }
            for (UUID playerId : session.pendingViewers) {
                removeTracking(pendingViewerships, playerId, session.key);
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (player != null) sendInactive(player, session);
            }
            for (UUID playerId : session.waitingRomViewers) {
                removeTracking(waitingRomViewerships, playerId, session.key);
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (player != null) sendInactive(player, session);
            }
            ArcadeOccupancyDisplay.remove(
                    server,
                    session.key.dimension(),
                    session.key.anchor());
            sessions.remove(session.key);
            if(coordinated&&session.hosted==null&&(session.netplay==null||session.saveMode==RomSaveMode.NONE))
                session.homeLaunch.finished(saveConfirmed,saveConfirmed?"":"FC 最近进度未确认保存；原档保留。");
            if(session.homeRuntime!=null){session.homeRuntime.close();session.homeRequests.clear();var level=server.getLevel(session.key.dimension());if(level!=null)cn.piq.fcarcade.home.HomeApplianceService.refresh(level,session.key.anchor());}
            refreshIdleScoreDisplay(server, session.key);
        }

        private void refreshIdleScoreDisplays(MinecraftServer server) {
            Set<SessionKey> configuredRoadRace = new HashSet<>();
            for (ServerRomLibrary.ArcadeSelection selection
                    : library(server).configuredSelections()) {
                SessionKey key = new SessionKey(
                        selection.dimension(),
                        selection.anchor(),
                        selection.mode());
                if (sessions.containsKey(key)) continue;
                refreshIdleScoreDisplay(server, key);
                if (RoadRaceScoreRule.ROM_SHA256.equalsIgnoreCase(
                        selection.sha256())
                        && library(server).leaderboardEnabled(
                                selection.dimension(),
                                selection.anchor())) {
                    configuredRoadRace.add(key);
                }
            }
            for (SessionKey stale : new HashSet<>(idleLeaderboardDisplays)) {
                if (configuredRoadRace.contains(stale)
                        || sessions.containsKey(stale)) {
                    continue;
                }
                idleLeaderboardDisplays.remove(stale);
                var occupied = CabinetTarget.resolve(server.getLevel(stale.dimension()), stale.anchor());
                if (occupied != null && ServerCabinets.isCabinetBusy(server, occupied)) continue;
                ArcadeOccupancyDisplay.remove(
                        server,
                        stale.dimension(),
                        stale.anchor());
            }
        }

        private void rotateIdleScoreDisplays(MinecraftServer server) {
            for (SessionKey key : new HashSet<>(idleLeaderboardDisplays)) {
                if (sessions.containsKey(key)) continue;
                refreshIdleScoreDisplay(server, key);
            }
        }

        private void refreshIdleScoreDisplay(
                MinecraftServer server,
                SessionKey key
        ) {
            ServerLevel displayLevel = server.getLevel(key.dimension());
            var occupiedCabinet = CabinetTarget.resolve(displayLevel, key.anchor());
            if (occupiedCabinet != null && ServerCabinets.isCabinetBusy(server, occupiedCabinet)) {
                // An add-on room owns this label now, even if old NES leaderboard settings remain.
                idleLeaderboardDisplays.remove(key);
                return;
            }
            if (displayLevel != null && cn.piq.fcarcade.cabinet.ServerCabinets.blocksNes(displayLevel, key.anchor())) {
                idleLeaderboardDisplays.remove(key);
                ArcadeOccupancyDisplay.remove(server, key.dimension(), key.anchor());
                return;
            }
            if (displayLevel != null && displayLevel.hasChunkAt(key.anchor())
                    && displayLevel.getBlockState(key.anchor()).getBlock() instanceof RetroTvBlock) {
                // A home TV is off when its session ends; never reuse old cabinet leaderboard settings.
                idleLeaderboardDisplays.remove(key);
                ArcadeOccupancyDisplay.remove(server, key.dimension(), key.anchor());
                return;
            }
            String selected = library(server).selected(
                    key.dimension(),
                    key.anchor(),
                    key.mode());
            if (!RoadRaceScoreRule.ROM_SHA256.equalsIgnoreCase(selected)
                    || !library(server).leaderboardEnabled(
                            key.dimension(),
                            key.anchor())) {
                idleLeaderboardDisplays.remove(key);
                ArcadeOccupancyDisplay.remove(
                        server,
                        key.dimension(),
                        key.anchor());
                return;
            }
            idleLeaderboardDisplays.add(key);
            List<ArcadeScoreStore.ScoreEntry> entries = scores(server).top(
                    RoadRaceScoreRule.ROM_SHA256,
                    ArcadeLeaderboardText.LEADERBOARD_LIMIT);
            ArcadeOccupancyDisplay.refreshLeaderboard(
                    server,
                    key.dimension(),
                    key.anchor(),
                    entries,
                    IdleDisplaySchedule.windowStart(
                            server.getTickCount(),
                            entries.size(),
                            ArcadeLeaderboardText.DISPLAY_LIMIT,
                            leaderboardPageSeconds(server) * 20));
        }

        private void reconcileViewers(MinecraftServer server) {
            Map<UUID, Set<SessionKey>> desired = new HashMap<>();
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                SessionKey membership = memberships.get(player.getUUID());
                for (Session candidate : sessions.values()) {
                    if (candidate.hosted!=null || candidate.playerMedia || sharedJniWatch(candidate) || candidate.lockstep == null || candidate.lockstep.epoch() == 0
                            || candidate.homeRuntime!=null&&candidate.homeRuntime.host().equals(player.getUUID())
                            || candidate.key.equals(membership)
                            || player.level().dimension()
                            != candidate.key.dimension()) {
                        continue;
                    }
                    double distance = player.distanceToSqr(
                            candidate.key.anchor().getX() + 0.5D,
                            candidate.key.anchor().getY() + 0.5D,
                            candidate.key.anchor().getZ() + 0.5D);
                    if (distance <= viewerDistanceSquared(server,candidate)) {
                        desired.computeIfAbsent(
                                player.getUUID(),
                                ignored -> new HashSet<>()).add(candidate.key);
                    }
                }
            }

            for (Session session : sessions.values()) {
                Set<UUID> tracked = new HashSet<>(session.viewers);
                tracked.addAll(session.pendingViewers);
                tracked.addAll(session.waitingRomViewers);
                for (UUID playerId : tracked) {
                    if (!desired.getOrDefault(playerId, Set.of())
                            .contains(session.key)) {
                        removeViewer(server, playerId, session.key, true);
                    }
                }
            }
            for (Map.Entry<UUID, Set<SessionKey>> entry : desired.entrySet()) {
                for (SessionKey desiredKey : entry.getValue()) {
                    if (isTracked(viewerships, entry.getKey(), desiredKey)
                            || isTracked(
                                    pendingViewerships,
                                    entry.getKey(),
                                    desiredKey)
                            || isTracked(
                                    waitingRomViewerships,
                                    entry.getKey(),
                                    desiredKey)) {
                        continue;
                    }
                    Session session = sessions.get(desiredKey);
                    ServerPlayer player =
                            server.getPlayerList().getPlayer(entry.getKey());
                    if (session == null || player == null) continue;
                    sendViewerSession(player, session);
                    session.waitingRomViewers.add(entry.getKey());
                    addTracking(
                            waitingRomViewerships,
                            entry.getKey(),
                            session.key);
                }
            }
            for (Session session : sessions.values()) {
                if (!session.pendingViewers.isEmpty()) {
                    requestSessionSnapshot(server, session);
                }
            }
        }

        private void removeViewer(
                MinecraftServer server,
                UUID playerId,
                boolean notifyPlayer
        ) {
            Set<SessionKey> keys = new HashSet<>(trackedKeys(
                    viewerships,
                    playerId));
            keys.addAll(trackedKeys(pendingViewerships, playerId));
            keys.addAll(trackedKeys(waitingRomViewerships, playerId));
            for (SessionKey key : keys) {
                removeViewer(server, playerId, key, notifyPlayer);
            }
        }

        private void removeViewer(
                MinecraftServer server,
                UUID playerId,
                SessionKey key,
                boolean notifyPlayer
        ) {
            removeTracking(viewerships, playerId, key);
            removeTracking(pendingViewerships, playerId, key);
            removeTracking(waitingRomViewerships, playerId, key);
            Session session = sessions.get(key);
            if (session == null) return;
            session.viewers.remove(playerId);
            session.pendingViewers.remove(playerId);
            session.waitingRomViewers.remove(playerId);
            session.resyncTicks.remove(playerId);
            if (notifyPlayer) {
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (player != null) sendInactive(player, session);
            }
        }

        private void handleSnapshot(
                ServerPlayer player,
                ArcadeSnapshotUploadPayload payload
        ) {
            Session session = sessionFor(player, payload.sessionId());
            if(session!=null&&session.netplay!=null)return;
            if (session == null || session.hosted!=null || session.lockstep == null
                    || !computeHost(session,player)
                    || !session.snapshotRequested
                    || payload.epoch() != session.lockstep.epoch()
                    || payload.frame() < session.timeline.baseFrame()
                    || payload.frame() % LockstepState.FRAMES_PER_SERVER_TICK != 0
                    || payload.frame() > session.lockstep.targetFrame()
                    || session.lockstep.targetFrame() - payload.frame()
                    > MAX_UPLOADED_SNAPSHOT_LAG_FRAMES) {
                return;
            }
            if(!session.variant.acceptsPersistentStateHeader(payload.state(),session.romSha256))return;
            session.snapshot = payload.state().clone();
            session.snapshotEpoch = payload.epoch();
            session.snapshotFrame = payload.frame();
            session.timeline.discardThrough(payload.frame());
            session.snapshotRequested = false;
            session.snapshotRequestTick = 0;
            session.lastSnapshotReceivedTick = player.getServer().getTickCount();
            PendingExit pendingExit = pendingExits.get(player.getUUID());
            if (pendingExit != null && pendingExit.sessionId() == session.id) {
                pendingExits.remove(player.getUUID());
                if (saveState(player.getServer(), session)) {
                    leave(player, true, false);
                }
                return;
            }

            if (!hasFreshSnapshot(session)) {
                requestSessionSnapshot(player.getServer(), session);
                return;
            }

            for (UUID controllerId : Set.copyOf(
                    session.pendingControllerSync)) {
                ServerPlayer controller =
                        player.getServer().getPlayerList().getPlayer(controllerId);
                if (controller != null && sessionFor(controller, session.id) == session) {
                    syncControllerState(controller, session);
                }
                session.pendingControllerSync.remove(controllerId);
            }

            for (UUID viewerId : Set.copyOf(session.pendingViewers)) {
                ServerPlayer viewer = player.getServer().getPlayerList().getPlayer(viewerId);
                if (viewer == null) {
                    session.pendingViewers.remove(viewerId);
                    removeTracking(
                            pendingViewerships,
                            viewerId,
                            session.key);
                } else {
                    activateViewer(viewer, session);
                }
            }
        }

        private void handleRomReady(ServerPlayer player, RomReadyPayload payload) {
            Session session = sessions.values().stream()
                    .filter(candidate -> candidate.id == payload.sessionId())
                    .findFirst()
                    .orElse(null);
            if (session == null || session.hosted!=null || session.lockstep == null
                    || !session.romSha256.equals(payload.sha256())
                    || (!session.waitingRomViewers.contains(player.getUUID())
                    && !session.pendingViewers.contains(player.getUUID())
                    && !session.viewers.contains(player.getUUID())
                    && sessionFor(player, session.id) != session)
                    || !allowResync(player, session)) {
                return;
            }
            resyncParticipant(player, session);
        }

        private double viewerDistanceSquared(MinecraftServer server, Session session) {
            var old = settings(server);
            int legacyRange = Math.max(old.viewDistance(),old.audioDistance());
            var level = server.getLevel(session.key.dimension());
            var source = session.homeConsolePos != null ? session.homeConsolePos : session.key.anchor();
            int range = cn.piq.fcarcade.config.GameConsoleAdminSettings.watchRange(level,source,legacyRange);
            return (double)range*range;
        }

        private boolean allowResync(ServerPlayer player, Session session) {
            if(session.playerMedia&&!computeHost(session,player))return false;
            if(session.homeRuntime!=null&&computeHost(session,player))return validHome(player.getServer(),session);
            boolean controller = sessionFor(player, session.id) == session
                    && session.roster.contains(player.getUUID());
            double limit = controller ? MAX_DISTANCE_SQUARED
                    : viewerDistanceSquared(player.getServer(),session);
            if (player.level().dimension() != session.key.dimension()
                    || (controller ? controllerDistanceSquared(player, session.key.anchor())
                    : player.distanceToSqr(session.key.anchor().getX() + 0.5D,
                    session.key.anchor().getY() + 0.5D,
                    session.key.anchor().getZ() + 0.5D)) > limit) return false;
            int tick = player.getServer().getTickCount();
            Integer previous = session.resyncTicks.get(player.getUUID());
            if (previous != null && tick - previous < 40) return false;
            session.resyncTicks.put(player.getUUID(), tick);
            return true;
        }

        private void resyncParticipant(ServerPlayer player, Session session) {
            if(session.netplay!=null){
                if(computeHost(session,player))return;
                session.netplay.revoke(player.connection.getConnection());sendHomeSession(player,session,false);return;
            }
            if(session.homeRuntime!=null&&computeHost(session,player)){
                if(hasRecoverableSnapshot(session))syncControllerState(player,session);
                else close(player.getServer(),session); // Never restart a failed host from frame zero silently.
                return;
            }
            if (sessionFor(player, session.id) == session
                    && session.roster.contains(player.getUUID())) {
                // A recovering 1P cannot supply a new snapshot until its core is restored.
                if (hasRecoverableSnapshot(session)) {
                    syncControllerState(player, session);
                } else {
                    session.pendingControllerSync.add(player.getUUID());
                    requestSessionSnapshot(player.getServer(), session);
                }
                return;
            }
            session.waitingRomViewers.remove(player.getUUID());
            removeTracking(
                    waitingRomViewerships,
                    player.getUUID(),
                    session.key);
            session.pendingViewers.add(player.getUUID());
            addTracking(
                    pendingViewerships,
                    player.getUUID(),
                    session.key);
            if (hasFreshSnapshot(session)) {
                activateViewer(player, session);
            } else {
                requestSessionSnapshot(player.getServer(), session);
            }
        }

        private void activateViewer(ServerPlayer player, Session session) {
            if (!hasFreshSnapshot(session)) return;
            sendSnapshotAndHistory(player, session);
            session.pendingViewers.remove(player.getUUID());
            removeTracking(
                    pendingViewerships,
                    player.getUUID(),
                    session.key);
            session.viewers.add(player.getUUID());
            addTracking(viewerships, player.getUUID(), session.key);
        }

        private static void syncControllerState(
                ServerPlayer player,
                Session session
        ) {
            if (!hasRecoverableSnapshot(session)) return;
            sendSnapshotAndHistory(player, session);
        }

        private static void sendSnapshotAndHistory(
                ServerPlayer player,
                Session session
        ) {
            if(session.hosted!=null)return;
            FcNetwork.sendSnapshot(player, new ArcadeSnapshotPayload(
                    session.id,
                    session.snapshotEpoch,
                    session.snapshotFrame,
                    cn.piq.fcarcade.session.NesPersistentState.snapshot(session.snapshot)));
            FcNetwork.sendHistory(player, new ArcadeHistoryPayload(
                    session.id,
                    session.timeline.epoch(),
                    session.snapshotFrame,
                    session.timeline.snapshotAfter(session.snapshotFrame)));
        }

        private static boolean isTracked(
                Map<UUID, Set<SessionKey>> tracking,
                UUID playerId,
                SessionKey key
        ) {
            return tracking.getOrDefault(playerId, Set.of()).contains(key);
        }

        private static Set<SessionKey> trackedKeys(
                Map<UUID, Set<SessionKey>> tracking,
                UUID playerId
        ) {
            return tracking.getOrDefault(playerId, Set.of());
        }

        private static void addTracking(
                Map<UUID, Set<SessionKey>> tracking,
                UUID playerId,
                SessionKey key
        ) {
            tracking.computeIfAbsent(playerId, ignored -> new HashSet<>()).add(key);
        }

        private static void removeTracking(
                Map<UUID, Set<SessionKey>> tracking,
                UUID playerId,
                SessionKey key
        ) {
            Set<SessionKey> keys = tracking.get(playerId);
            if (keys == null) return;
            keys.remove(key);
            if (keys.isEmpty()) tracking.remove(playerId);
        }

        private static boolean hasFreshSnapshot(Session session) {
            return hasRecoverableSnapshot(session)
                    && session.lockstep.targetFrame() - session.snapshotFrame
                    <= MAX_CACHED_SNAPSHOT_AGE_FRAMES;
        }

        private static boolean hasRecoverableSnapshot(Session session) {
            return session.snapshot != null
                    && session.snapshotEpoch == session.lockstep.epoch()
                    && session.snapshotFrame >= session.timeline.baseFrame()
                    && session.snapshotFrame <= session.lockstep.targetFrame()
                    && session.snapshotFrame >= 0;
        }

        private static void requestSessionSnapshot(
                MinecraftServer server,
                Session session
        ) {
            if(session.hosted!=null||session.netplay!=null)return;
            int serverTick = server.getTickCount();
            if (session.snapshotRequested
                    && serverTick - session.snapshotRequestTick < 60) {
                return;
            }
            session.snapshotRequested = false;
            if(session.homeRuntime!=null){
                ServerPlayer host=server.getPlayerList().getPlayer(session.homeRuntime.host());
                if(!computeHost(session,host))return;
                FcNetwork.requestSnapshot(host,new ArcadeSnapshotRequestPayload(session.id,session.lockstep.epoch()));
                session.snapshotRequested=true;session.snapshotRequestTick=serverTick;return;
            }
            for (UUID playerId : session.roster.playerIds()) {
                if (session.roster.roleOf(playerId) != ArcadeRole.PLAYER_ONE) continue;
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (player == null) return;
                FcNetwork.requestSnapshot(player, new ArcadeSnapshotRequestPayload(
                        session.id,
                        session.lockstep.epoch()));
                session.snapshotRequested = true;
                session.snapshotRequestTick = serverTick;
                return;
            }
        }

        private byte[] loadState(MinecraftServer server, Session session) {
            byte[] state = saves(server).load(
                    session.saveKey,
                    session.romSha256);
            if (state == null) return null;
            if(!session.variant.acceptsPersistentStateHeader(state,session.romSha256))return null;
            session.snapshot = state.clone();
            session.snapshotEpoch = session.lockstep.epoch();
            session.snapshotFrame = 0;
            session.lastSnapshotReceivedTick = server.getTickCount();
            return state;
        }

        private boolean saveState(
                MinecraftServer server,
                Session session
        ) {
            if (session.netplay != null || session.lockstep == null
                    || session.saveMode == RomSaveMode.NONE
                    || (session.personalSave != null && !session.personalSave.writable())) {
                return false;
            }
            if (session.snapshot == null
                    || session.snapshotEpoch != session.lockstep.epoch()
                    || session.snapshotFrame < 0) {
                reportSaveFailure(server, session, "没有可用快照");
                return false;
            }
            try {
                Runnable write = () -> saves(server).save(
                        session.saveKey,
                        session.romSha256,
                        session.snapshot,
                        session.saveSlotName,
                        session.savePlayers);
                if (session.personalSave != null) session.personalSave.write(write);
                else write.run();
                if(session.homeConsolePos!=null&&session.saveMode==RomSaveMode.PLAYER){
                    // A different ROM in the same personal slot is retired only after commit.
                    // Cleanup failure does not turn an already durable save into a false failure.
                    try{for(var old:saves(server).list())if(old.saveKey().equals(session.saveKey)
                            &&!old.romSha256().equals(session.romSha256))saves(server).delete(session.saveKey,old.romSha256());}
                    catch(RuntimeException cleanup){FcArcadeMod.LOGGER.warn("New home save committed; previous slot cleanup deferred",cleanup);}
                }
                String personalOwner=FcSaveManagementStore.owner(session.saveKey);
                if(!personalOwner.isEmpty())try{saves(server).played(UUID.fromString(personalOwner),Instant.now());}
                catch(RuntimeException activityFailure){FcArcadeMod.LOGGER.warn("Saved FC progress but could not record play activity",activityFailure);}
                if(session.homeCardId!=null&&session.saveMode==RomSaveMode.MACHINE){
                    var level=server.getLevel(session.key.dimension());
                    if(level!=null&&level.hasChunkAt(session.homeConsolePos)&&level.getBlockEntity(session.homeConsolePos) instanceof cn.piq.fcarcade.home.HomeConsoleBlockEntity c)
                        c.cartridgeSaved(session.homeCardId,session.romSha256,true);
                }
                session.lastSaveFailed = false;
                return true;
            } catch (RuntimeException error) {
                cn.piq.fcarcade.FcArcadeMod.LOGGER.error(
                        "[PIQ FC] 保存街机进度失败",
                        error);
                reportSaveFailure(server, session, "服务器写入失败");
                return false;
            }
        }

        private static void reportSaveFailure(MinecraftServer server, Session session, String reason) {
            session.lastSaveFailed = true;
            for (UUID id : session.roster.playerIds()) {
                ServerPlayer player = server.getPlayerList().getPlayer(id);
                if (player != null) player.sendSystemMessage(Component.literal(
                        "[FC] 进度未保存：" + reason + "。保存退出未成功时会保留会话，请重试或选择不保存退出。"));
            }
        }

        private static void sealPersonalSave(MinecraftServer server, Session session, UUID departing) {
            if (session.personalSave == null || !session.personalSave.sealOnDeparture(departing)) return;
            if (session.roster.size() <= 1) return;
            for (UUID id : session.roster.playerIds()) {
                ServerPlayer player = server.getPlayerList().getPlayer(id);
                if (player != null) notifySealedSave(player, session);
            }
        }

        private static void notifySealedSave(ServerPlayer player, Session session) {
            player.sendSystemMessage(Component.literal(
                    "[FC] 原玩家个人存档槽已封存；接手、断线保留期及重连后继续本局均不再写入该槽，"
                            + "也不会自动保存到接手者的槽。需要继续保存，请结束本局后选择自己的存档槽。"
                            + (session.lastSaveFailed ? " 注意：封存前的最后一次保存失败。" : "")));
        }

        private void saveAll(MinecraftServer server) {
            sessions.values().forEach(session->{if(session.netplay!=null){cn.piq.fcarcade.netplay.NetplaySaveServer.retire(server,session.id);cn.piq.fcarcade.netplay.NetplayNetwork.retire(session.netplay);}});
            sessions.values().forEach(session -> {if(session.hosted!=null){collectHostedSnapshot(server,session);session.hosted.close();}saveState(server,session);});
            closingHosted.forEach(session->session.hosted.close());
            if (scoreStore != null) scoreStore.flush();
        }

        private static String machineKey(SessionKey key) {
            return key.dimension().location() + "|"
                    + key.anchor().getX() + ","
                    + key.anchor().getY() + ","
                    + key.anchor().getZ() + "|"
                    + key.mode().name();
        }

        private static RomSaveMode effectiveSaveMode(
                SessionKey key,
                RomSaveMode configured
        ) {
            return key.mode() == ArcadeMode.LOCKSTEP
                    ? configured
                    : RomSaveMode.NONE;
        }

        private boolean hasSave(
                MinecraftServer server,
                SessionKey key,
                String romSha256,
                RomSaveMode saveMode,
                UUID playerId
        ) {
            return saveMode != RomSaveMode.NONE
                    && saves(server).exists(
                    coreVariant(server,romSha256,false).saveKey(saveKey(key, saveMode, playerId)),
                    romSha256);
        }

        private static String saveKey(
                SessionKey key,
                RomSaveMode saveMode,
                UUID playerId
        ) {
            return switch (saveMode) {
                case PLAYER -> "player|" + playerId;
                case MACHINE -> machineKey(key);
                case NONE -> throw new IllegalArgumentException(
                        "不存档模式没有存档键");
            };
        }

        private static String playerSlotKey(UUID playerId, int slot) {
            return PlayerSaveSlots.key(playerId, slot);
        }

        private void sync(
                MinecraftServer server,
                Session session,
                boolean reset
        ) {
            ArcadeGlobalSettings globalSettings = settings(server);
            String playerNames = playerNames(server, session);
            if(session.homeRuntime!=null){
                if(session.zapperBinding!=null)cn.piq.fcarcade.home.HomeZapperService.updateEpoch(server,session.id,session.lockstep.epoch());
                Set<UUID> recipients=new LinkedHashSet<>(session.allPlayers());recipients.addAll(session.pendingViewers);recipients.addAll(session.waitingRomViewers);
                for(UUID id:recipients){var p=server.getPlayerList().getPlayer(id);if(p!=null)sendHomeSession(p,session,reset);}
                ArcadeOccupancyDisplay.refresh(server,session.key.dimension(),session.key.anchor(),playerNames);return;
            }
            if(session.zapperBinding!=null)cn.piq.fcarcade.home.HomeZapperService.updateEpoch(server,session.id,session.lockstep.epoch());
            ArcadeOccupancyDisplay.refresh(
                    server,
                    session.key.dimension(),
                    session.key.anchor(),
                    playerNames);
            for (UUID playerId : session.roster.playerIds()) {
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                ArcadeRole role = session.roster.roleOf(playerId);
                if (player != null && role != null) {
                    FcNetwork.sendSession(player, new ArcadeSessionPayload(
                            session.key.anchor(),
                            session.id,
                            session.key.mode(),
                            role,
                            session.roster.size(),
                            playerNames,
                            globalSettings.viewDistance(),
                            globalSettings.audioDistance(),
                            globalSettings.audioVolumePercent(),
                            session.romSha256,
                            session.lockstep == null ? 0 : session.lockstep.epoch(),
                            reset,
                            true,session.variant));
                }
            }
            for (UUID playerId : session.viewers) {
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (player != null) sendViewerSession(player, session);
            }
            if (!session.pendingViewers.isEmpty()) {
                for (UUID playerId : session.pendingViewers) {
                    ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                    if (player != null) sendViewerSession(player, session);
                }
                requestSessionSnapshot(server, session);
            }
            for (UUID playerId : session.waitingRomViewers) {
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (player != null) sendViewerSession(player, session);
            }
        }

        private void sendViewerSession(ServerPlayer player, Session session) {
            if(session.homeRuntime!=null){sendHomeSession(player,session,false);return;}
            ArcadeGlobalSettings globalSettings = settings(player.getServer());
            int viewRange=cn.piq.fcarcade.config.GameConsoleAdminSettings.watchRange(player.serverLevel(),session.key.anchor(),globalSettings.viewDistance());
            FcNetwork.sendSession(player, new ArcadeSessionPayload(
                    session.key.anchor(),
                    session.id,
                    session.key.mode(),
                    ArcadeRole.SPECTATOR,
                    session.roster.size(),
                    playerNames(player.getServer(), session),
                    viewRange,
                    globalSettings.audioDistance(),
                    globalSettings.audioVolumePercent(),
                    session.romSha256,
                    session.lockstep == null ? 0 : session.lockstep.epoch(),
                    false,
                    true,session.variant));
        }

        private static void sendInactive(ServerPlayer player, Session session) {
            FcNetwork.sendSession(player, new ArcadeSessionPayload(
                    session.key.anchor(),
                    session.id,
                    session.key.mode(),
                    ArcadeRole.SPECTATOR,
                    session.roster.size(),
                    "",
                    ArcadeGlobalSettings.DEFAULT_VIEW_DISTANCE,
                    ArcadeGlobalSettings.DEFAULT_AUDIO_DISTANCE,
                    ArcadeGlobalSettings.DEFAULT_AUDIO_VOLUME_PERCENT,
                    "",
                    session.lockstep == null ? 0 : session.lockstep.epoch(),
                    false,
                    false,session.variant));
        }

        private void sendHomeSession(ServerPlayer p,Session s,boolean reset){
            if(!current(p))return;
            var config=settings(p.getServer());var control=s.homeRuntime.player(p.getUUID());
            if(control!=null&&control.connection()!=p.connection.getConnection())control=null;
            if((s.hosted!=null||s.playerMedia||sharedJniWatch(s))&&control==null&&!computeHost(s,p))return;
            ArcadeRole role=control==null?ArcadeRole.SPECTATOR:control.port()==0?ArcadeRole.PLAYER_ONE:ArcadeRole.PLAYER_TWO;
            int viewRange=role==ArcadeRole.SPECTATOR?cn.piq.fcarcade.config.GameConsoleAdminSettings.watchRange(p.serverLevel(),s.homeConsolePos,config.viewDistance()):config.viewDistance();
            var payload=new ArcadeSessionPayload(s.key.anchor(),s.id,s.key.mode(),role,s.roster.size(),playerNames(p.getServer(),s),viewRange,config.audioDistance(),config.audioVolumePercent(),s.romSha256,s.lockstep.epoch(),reset,true,s.variant,
                    true,s.hosted==null&&computeHost(s,p),control==null?null:control.lease(),s.homeRuntime.revision(),s.playerMedia);
            if(s.netplay!=null){
                var ticket=s.netplay.grant(p.connection.getConnection(),computeHost(s,p)||!s.variant.isZapper()&&control!=null);if(ticket==null)return;
                cn.piq.fcarcade.netplay.NetplayNetwork.authorize(s.id,p.connection.getConnection(),s.netplay);
                cn.piq.fcarcade.netplay.NetplayNetwork.state(p,new cn.piq.fcarcade.netplay.NetplayNetwork.State(payload,ticket.id(),ticket.player(),s.netplayJniTrial));return;
            }
            if(s.hosted!=null)FcHomeHostedNetwork.sendState(p,new FcHomeHostedNetwork.State(payload,s.hosted.source,s.hosted.token,computeHost(s,p)));
            else if(s.playerMedia&&!computeHost(s,p))FcHomeHostedNetwork.sendState(p,new FcHomeHostedNetwork.State(payload,s.mediaSource,s.mediaToken,false,true));
            else FcNetwork.sendSession(p,payload);
        }

        private void renewNetplay(MinecraftServer server,Session s){
            var allowed=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<net.minecraft.network.Connection,Boolean>());
            var participants=new HashSet<>(s.allPlayers());participants.addAll(s.waitingRomViewers);participants.addAll(s.pendingViewers);
            for(UUID id:participants){
                ServerPlayer p=server.getPlayerList().getPlayer(id);var control=s.homeRuntime.player(id);
                if(!current(p)||p.level().dimension()!=s.key.dimension()){if(control!=null)detachStaleHome(server,s,id);continue;}
                for(var socket:s.homeRuntime.controls(id)){
                    boolean gun=s.homeRuntime.gunMode()&&socket.port()==1;
                    boolean valid=gun?s.zapperBinding!=null&&s.zapperBinding.lease().equals(socket.lease())&&cn.piq.fcarcade.home.HomeZapperService.authorized(p,s.zapperBinding,false)
                            :s.homeRuntime.gunMode()?HomeControllerService.mediaAuthorized(p,s.key.anchor(),s.id,socket.port(),socket.lease()):HomeControllerService.authorized(p,s.key.anchor(),s.id,socket.port());
                    // A stowed device is still owned: its service pauses input, not the other socket.
                    if(socket.connection()!=p.connection.getConnection()){detachStaleHome(server,s,id);break;}
                    if(!valid||validStructure(p,s.key.anchor())==null)
                        detachHomeDevice(p,s,socket.port(),socket.lease());
                }
                control=s.homeRuntime.player(id);
                if(computeHost(s,p)||control!=null||p.distanceToSqr(s.key.anchor().getX()+0.5,s.key.anchor().getY()+0.5,s.key.anchor().getZ()+0.5)<=viewerDistanceSquared(server,s))allowed.add(p.connection.getConnection());
            }
            if(sharedJniWatch(s))allowed.addAll(WatchNetplay.connections(server,s.mediaSource));
            s.netplay.renew(allowed);
            cn.piq.fcarcade.netplay.NetplayNetwork.prune(s.netplay,allowed);
        }

        /** Server-validated gun and optional P1 keys are injected only by the native host. */
        private void sendNetplayGun(MinecraftServer server,Session s){
            if(s.netplay==null||s.netplay.closed()||s.homeRuntime==null||!s.variant.isZapper())return;
            ServerPlayer host=server.getPlayerList().getPlayer(s.homeRuntime.host());if(!current(host)||!computeHost(s,host))return;
            var ticket=s.netplay.grant(host.connection.getConnection(),true);
            for(int i=0;i<LockstepState.FRAMES_PER_SERVER_TICK;i++){
                var input=s.lockstep.advanceFrame();
                cn.piq.fcarcade.netplay.NetplayNetwork.gun(host,new cn.piq.fcarcade.netplay.NetplayNetwork.GunFrame(s.id,ticket.id(),input.epoch(),s.lockstep.releaseRevision(),input.targetFrame(),input.playerOneMask(),input.zapperState()));
            }
        }

        private static String playerNames(
                MinecraftServer server,
                Session session
        ) {
            List<String> names = new java.util.ArrayList<>(2);
            for (UUID playerId : session.roster.playerIds()) {
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (!current(player) || session.roster.roleOf(playerId) == ArcadeRole.SPECTATOR) continue;
                if (session.homeRuntime != null) {
                    var controller = session.homeRuntime.player(playerId);
                    if (controller == null || controller.connection() != player.connection.getConnection()) continue;
                }
                names.add(player.getGameProfile().getName());
            }
            return String.join("、", names);
        }

        private static void broadcastFrame(
                MinecraftServer server,
                Session session,
                LockstepState.FrameStep step
        ) {
            ArcadeFramePayload payload = new ArcadeFramePayload(
                    session.id,
                    step.epoch(),
                    step.targetFrame(),
                    step.playerOneMask(),
                    step.playerTwoMask(),step.zapperState());
            for (UUID playerId : session.allPlayers()) {
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (player != null&&(!session.playerMedia||computeHost(session,player))) FcNetwork.sendFrame(player, payload);
            }
        }

        private static String safeMessage(Throwable error) {
            String message = error.getMessage();
            return message == null || message.isBlank()
                    ? error.getClass().getSimpleName()
                    : message;
        }

        private static final class IncomingUpload {
            private final RomTransferBuffer buffer;
            private final BlockPos blockPos;
            private int lastActivityTick;
            private final ServerPlayer player;
            private final net.minecraft.network.Connection connection;
            private final net.minecraft.server.level.ServerLevel level;

            private IncomingUpload(
                    RomTransferBuffer buffer,
                    BlockPos blockPos,
                    int lastActivityTick, ServerPlayer player
            ) {
                this.buffer = buffer;
                this.blockPos = blockPos;
                this.lastActivityTick = lastActivityTick;
                this.player=player;this.connection=player.connection.getConnection();this.level=player.serverLevel();
            }
        }

        private static final class OutgoingDownload {
            private final String sha256;
            private final RomDescriptor descriptor;
            private int offset;

            private OutgoingDownload(RomDescriptor descriptor) {
                sha256 = descriptor.sha256();
                this.descriptor = descriptor;
            }
        }
    }
}
