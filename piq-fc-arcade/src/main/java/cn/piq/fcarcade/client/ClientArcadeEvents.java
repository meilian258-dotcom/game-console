package cn.piq.fcarcade.client;

import cn.piq.fcarcade.ArcadeFramePayload;
import cn.piq.fcarcade.ArcadeExitPromptPayload;
import cn.piq.fcarcade.ArcadeHistoryPayload;
import cn.piq.fcarcade.ArcadeJoinPromptPayload;
import cn.piq.fcarcade.ArcadeJoinApprovalPayload;
import cn.piq.fcarcade.ArcadeLibraryPayload;
import cn.piq.fcarcade.ArcadeMultiplayerOfferPayload;
import cn.piq.fcarcade.ArcadePersistentStatePayload;
import cn.piq.fcarcade.ArcadeResumePromptPayload;
import cn.piq.fcarcade.ArcadeSessionPayload;
import cn.piq.fcarcade.ArcadeSettingsPayload;
import cn.piq.fcarcade.ArcadeSaveCatalogPayload;
import cn.piq.fcarcade.ArcadeSaveSlotsPayload;
import cn.piq.fcarcade.ArcadeSnapshotPayload;
import cn.piq.fcarcade.ArcadeSnapshotRequestPayload;
import cn.piq.fcarcade.FcArcadeMod;
import cn.piq.fcarcade.FcNetwork;
import cn.piq.fcarcade.LeaderboardPanelConfigPayload;
import cn.piq.fcarcade.RomDownloadChunkPayload;
import cn.piq.fcarcade.RomDownloadStartPayload;
import cn.piq.fcarcade.SkinDownloadChunkPayload;
import cn.piq.fcarcade.SkinDownloadStartPayload;
import cn.piq.fcarcade.SkinLibraryPayload;
import cn.piq.fcarcade.registry.ModBlockEntities;
import cn.piq.fcarcade.world.ArcadeStructure;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.client.Minecraft;
import cn.piq.fcarcade.client.ui.DeviceConfirmScreen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ClientArcadeEvents {
    private static final Map<Long, ClientArcadeSession> SESSIONS = new HashMap<>();
    private static final Map<Long, BlockPos> SESSION_POSITIONS = new HashMap<>();
    private static final Map<Long, ArcadePersistentStatePayload>
            PENDING_PERSISTENT_STATES = new HashMap<>();
    private static final Map<Long, ArcadeSnapshotPayload> PENDING_SNAPSHOTS =
            new HashMap<>();
    private static final Map<Long, ArcadeHistoryPayload> PENDING_HISTORIES =
            new HashMap<>();
    private static final long SELECTION_INTERVAL_NANOS = 500_000_000L;
    private static Set<Long> selectedSimulations = Set.of();
    private static long nextSelectionNanos;
    private static LocalArcadePreferences localPreferences;
    private static final Map<java.util.UUID,Long> HOME_APPROVALS=new HashMap<>();

    private ClientArcadeEvents() {
    }

    public static void register(IEventBus ignoredModBus) {
        FcHomeWatchDisplay.register();
        NetworkDiagnosticsClient.registerDevices("fc-sessions",()->SESSIONS.entrySet().stream()
                .filter(e->selectedSimulations.contains(e.getKey())||e.getValue().hasController()||e.getValue().isComputeHost())
                .map(e->e.getValue().diagnosticDevice()).filter(java.util.Objects::nonNull).toList());
        cn.piq.fcarcade.server.FcHomeHostedNetwork.clientSink(new cn.piq.fcarcade.server.FcHomeHostedNetwork.ClientSink(){
            public boolean accepts(net.minecraft.network.Connection connection){var mc=Minecraft.getInstance();return mc.getConnection()!=null&&mc.getConnection().getConnection()==connection;}
            public void state(cn.piq.fcarcade.server.FcHomeHostedNetwork.State state){applySessionReady(state.session(),state.source(),state.token(),state.operator());}
            public void stream(cn.piq.fcarcade.server.FcHomeHostedNetwork.Stream stream){var mc=Minecraft.getInstance();if(mc.player==null||!mc.player.getUUID().equals(stream.recipient()))return;var session=SESSIONS.get(stream.session());if(session!=null)session.hostedMedia(stream);}
        });
        cn.piq.retro.client.KeyboardInput.install();
        ControllerCapture.install();
        PrivateHomeClient.install();
        ControllerCapture.registerRuntime(()->{for(var session:List.copyOf(SESSIONS.values()))session.refreshKeyboardCapture();});
        cn.piq.fcarcade.client.zapper.ZapperClient.register(ignoredModBus);
        cn.piq.retro.client.KeyboardInput.registerSettings(() -> Minecraft.getInstance().setScreen(new cn.piq.retro.client.ControlSettingsScreen(null)));
        ignoredModBus.addListener(ClientArcadeEvents::registerKeyMappings);
        ignoredModBus.addListener(ClientArcadeEvents::registerRenderers);
        NeoForge.EVENT_BUS.addListener(ClientArcadeEvents::onRenderFrame);
        NeoForge.EVENT_BUS.addListener(ClientArcadeEvents::onRenderLevel);
        NeoForge.EVENT_BUS.addListener(ClientArcadeEvents::onKeyInput);
        NeoForge.EVENT_BUS.addListener(ClientArcadeEvents::onMouseInput);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, ClientArcadeEvents::onScreenOpening);
        NeoForge.EVENT_BUS.addListener(ClientArcadeEvents::onLoggingOut);
        NeoForge.EVENT_BUS.addListener(
                ClientArcadeEvents::registerClientCommands);
    }

    public static void applySession(ArcadeSessionPayload payload) {
        ClientRomTransfers.applySession(payload);
    }

    static void applySessionReady(ArcadeSessionPayload payload) {
        applySessionReady(payload,null,null,false);
    }
    private static void applySessionReady(ArcadeSessionPayload payload,java.util.UUID hostedSource,java.util.UUID hostedToken,boolean hostedOperator) {
        if (!payload.active()) {
            NetplayClient.forget(payload.sessionId());
            ClientArcadeSession session = SESSIONS.remove(payload.sessionId());
            clearSessionMetadata(payload.sessionId());
            try {
                if (session != null) safelyLeave(payload.sessionId(), session);
                refreshSimulationSelection(true);
            } finally {
                ArcadeKeyMappings.syncOtherMappings();
            }
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null
                || minecraft.getConnection() == null) return;
        BlockPos controlPos = minecraft.level == null
                ? payload.blockPos()
                : ArcadeStructure.resolve(minecraft.level, payload.blockPos()).anchor();
        ClientArcadeSession session = SESSIONS.computeIfAbsent(
                payload.sessionId(),
                ignored -> {
                    ClientArcadeSession created = new ClientArcadeSession();
                    // Admission follows metadata: never briefly initialize all
                    // nearby spectator cores before applying the local limit.
                    created.setSimulationEnabled(false);
                    return created;
                });
        if(payload.homeRuntime()&&!session.acceptsApplianceRevision(payload.controlRevision()))return;
        session.hostedAuthority(hostedSource,hostedToken,hostedOperator);
        session.netplayAuthority(NetplayClient.state(payload.sessionId()));
        session.applianceAuthority(payload.homeRuntime(),payload.computeHost(),payload.controllerLease(),payload.controlRevision(),payload.playerMedia());
        if(payload.homeRuntime()&&(payload.computeHost()||hostedOperator||payload.role().controllerIndex()>=0))session.showStartupControls();
        session.join(
                controlPos,
                payload.mode(),
                payload.role(),
                payload.sessionId(),
                payload.viewDistance(),
                payload.audioDistance(),
                payload.audioVolumePercent(),
                payload.memberCount(),
                payload.playerNames(),
                payload.romSha256(),
                payload.epoch(),
                payload.reset(), payload.variant());
        if (!session.isActive()) {
            SESSION_POSITIONS.remove(payload.sessionId());
        } else {
            SESSION_POSITIONS.put(payload.sessionId(), controlPos.immutable());
        }
        ArcadePersistentStatePayload pending =
                PENDING_PERSISTENT_STATES.remove(payload.sessionId());
        if (pending != null) {
            session.applyPersistentState(pending);
        }
        ArcadeSnapshotPayload pendingSnapshot =
                PENDING_SNAPSHOTS.remove(payload.sessionId());
        if (pendingSnapshot != null) session.applySnapshot(pendingSnapshot);
        ArcadeHistoryPayload pendingHistory =
                PENDING_HISTORIES.remove(payload.sessionId());
        if (pendingHistory != null) session.applyHistory(pendingHistory);
        refreshSimulationSelection(true);
        ArcadeKeyMappings.syncOtherMappings();
    }

    public static void requestJoin(ArcadeJoinPromptPayload payload) {
        ClientRomTransfers.join(payload);
    }

    public static void promptExit(ArcadeExitPromptPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        CartridgeScreenCompat.prepare();
        minecraft.setScreen(new DeviceConfirmScreen(
                save -> {
                    minecraft.setScreen(null);
                    FcNetwork.decideExit(payload.sessionId(), save);
                },
                Component.translatable("screen.piq_fc_arcade.exit_save_title"),
                Component.translatable("screen.piq_fc_arcade.exit_save_message"),
                Component.translatable("screen.piq_fc_arcade.save_and_exit"),
                Component.translatable("screen.piq_fc_arcade.exit_without_saving"),
                () -> minecraft.setScreen(null)));
    }

    public static void promptResume(ArcadeResumePromptPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        CartridgeScreenCompat.prepare();
        minecraft.setScreen(new DeviceConfirmScreen(
                resume -> {
                    minecraft.setScreen(null);
                    FcNetwork.decideResume(
                            payload.blockPos(),
                            payload.romSha256(),
                            resume);
                },
                Component.translatable("screen.piq_fc_arcade.resume_title"),
                Component.translatable("screen.piq_fc_arcade.resume_message"),
                Component.translatable("screen.piq_fc_arcade.resume"),
                Component.translatable("screen.piq_fc_arcade.start_over"),
                () -> minecraft.setScreen(null)));
    }

    public static void offerMultiplayer(ArcadeMultiplayerOfferPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        var source=minecraft.getConnection();
        if(source==null||!source.getConnection().isConnected())return;
        CartridgeScreenCompat.prepare();
        minecraft.setScreen(new DeviceConfirmScreen(
                enabled -> {
                    minecraft.setScreen(null);
                    if(minecraft.getConnection()!=source||!source.getConnection().isConnected())return;
                    FcNetwork.setMultiplayerEnabled(payload.sessionId(), enabled);
                },
                Component.translatable(
                        "screen.piq_fc_arcade.multiplayer_offer_title"),
                Component.translatable(
                        "screen.piq_fc_arcade.multiplayer_offer_message"),
                Component.literal("允许 2P"),Component.literal("不允许 2P")));
    }

    public static void requestJoinApproval(ArcadeJoinApprovalPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if(payload.requestToken()!=null){requestHomeApproval(payload);return;}
        var source=minecraft.getConnection();
        if(payload.requestToken()!=null&&(source==null||minecraft.screen!=null||SESSIONS.get(payload.sessionId())==null||!SESSIONS.get(payload.sessionId()).isHomeOperator()))return;
        CartridgeScreenCompat.prepare();
        minecraft.setScreen(new DeviceConfirmScreen(
                accepted -> {
                    if(payload.requestToken()!=null&&(minecraft.getConnection()!=source||SESSIONS.get(payload.sessionId())==null||!SESSIONS.get(payload.sessionId()).isHomeOperator()))return;
                    minecraft.setScreen(null);
                    FcNetwork.decideJoin(
                            payload.sessionId(),
                            payload.applicantId(),
                            accepted,payload.requestToken());
                },
                Component.translatable(
                        "screen.piq_fc_arcade.join_approval_title"),
                Component.translatable(
                        "screen.piq_fc_arcade.join_approval_message",
                        payload.applicantName())));
    }

    private static void requestHomeApproval(ArcadeJoinApprovalPayload payload){
        Minecraft mc=Minecraft.getInstance();var source=mc.getConnection();var session=SESSIONS.get(payload.sessionId());long now=System.nanoTime();
        HOME_APPROVALS.values().removeIf(until->until<now);
        if(source==null||!source.getConnection().isConnected()||session==null||!session.isHomeOperator()||mc.screen!=null||HOME_APPROVALS.containsKey(payload.requestToken())||HOME_APPROVALS.size()>=32)return;
        long deadline=now+30_000_000_000L;HOME_APPROVALS.put(payload.requestToken(),deadline);
        final net.minecraft.client.gui.screens.Screen[] own=new net.minecraft.client.gui.screens.Screen[1];
        CartridgeScreenCompat.prepare();
        own[0]=new DeviceConfirmScreen(accepted->{
            if(mc.screen!=own[0])return;
            boolean stale=mc.getConnection()!=source||!source.getConnection().isConnected()||SESSIONS.get(payload.sessionId())!=session||!session.isHomeOperator()||System.nanoTime()>deadline;
            mc.setScreen(null);if(stale)return;
            FcNetwork.decideJoin(payload.sessionId(),payload.applicantId(),accepted,payload.requestToken());
        },Component.literal("FC 主机 · 手柄申请"),Component.literal(payload.applicantName()+" 请求操作这台主机。"));
        mc.setScreen(own[0]);
    }

    public static void openLibrary(ArcadeLibraryPayload payload) {
        ClientRomTransfers.openLibrary(payload);
    }

    public static void openSkinLibrary(SkinLibraryPayload payload) {
        ClientSkinManager.openLibrary(payload);
    }

    public static void startSkinDownload(SkinDownloadStartPayload payload) {
        ClientSkinManager.startDownload(payload);
    }

    public static void acceptSkinDownload(SkinDownloadChunkPayload payload) {
        ClientSkinManager.acceptDownload(payload);
    }

    public static void openSettings(ArcadeSettingsPayload payload) {
        Minecraft.getInstance().setScreen(new ArcadeSettingsScreen(payload));
    }

    public static void openLeaderboardPanelConfig(
            LeaderboardPanelConfigPayload payload
    ) {
        Minecraft.getInstance().setScreen(new LeaderboardPanelScreen(payload));
    }

    public static void openSaveCatalog(ArcadeSaveCatalogPayload payload) {
        Minecraft.getInstance().setScreen(new ArcadeSaveCatalogScreen(payload));
    }

    public static void openSaveSlots(ArcadeSaveSlotsPayload payload) {
        Minecraft.getInstance().setScreen(new ArcadeSaveSlotsScreen(payload));
    }
    public static void openHomeSaveSlots(cn.piq.fcarcade.ArcadeHomeSaveSlotsPayload payload){
        Minecraft.getInstance().setScreen(new ArcadeSaveSlotsScreen(payload.slots(),payload.token(),payload.gun(),payload.cartridge()));
    }

    public static void startRomDownload(RomDownloadStartPayload payload) {
        ClientRomTransfers.startDownload(payload);
    }

    public static void acceptRomDownload(RomDownloadChunkPayload payload) {
        ClientRomTransfers.acceptDownload(payload);
    }

    public static void advanceFrame(ArcadeFramePayload payload) {
        ClientArcadeSession session = SESSIONS.get(payload.sessionId());
        if (session != null) session.enqueueFrame(payload);
    }

    public static void applyHistory(ArcadeHistoryPayload payload) {
        ClientArcadeSession session = SESSIONS.get(payload.sessionId());
        if (session != null) {
            session.applyHistory(payload);
        } else {
            PENDING_HISTORIES.put(payload.sessionId(), payload);
        }
    }

    public static void requestSnapshot(ArcadeSnapshotRequestPayload payload) {
        ClientArcadeSession session = SESSIONS.get(payload.sessionId());
        if (session != null) session.requestSnapshot(payload);
    }

    public static void applySnapshot(ArcadeSnapshotPayload payload) {
        ClientArcadeSession session = SESSIONS.get(payload.sessionId());
        if (session != null) {
            session.applySnapshot(payload);
        } else {
            PENDING_SNAPSHOTS.put(payload.sessionId(), payload);
        }
    }

    public static void applyPersistentState(ArcadePersistentStatePayload payload) {
        ClientArcadeSession session = SESSIONS.get(payload.sessionId());
        if (session != null) {
            session.applyPersistentState(payload);
        } else {
            PENDING_PERSISTENT_STATES.put(payload.sessionId(), payload);
        }
    }

    public static boolean isControlling() {
        return SESSIONS.values().stream()
                .anyMatch(ClientArcadeSession::hasController);
    }
    /** Prepared public JNI control claims, excluding slots already counted by the native bridge. */
    public static int pendingNativeControlClaims(){return ClientRomTransfers.pendingNativeControlClaims();}

    /** The gun renderer and input facade share the existing admitted FC session. */
    public static boolean authorizedZapper(cn.piq.fcarcade.home.ZapperBinding binding) {
        if(binding==null)return false;
        ClientArcadeSession session=SESSIONS.get(binding.sessionId());
        return session!=null&&session.authorizedZapper(binding);
    }

    public static boolean visualZapperTrigger(cn.piq.fcarcade.home.ZapperBinding binding) {
        if(binding==null)return false;
        ClientArcadeSession session=SESSIONS.get(binding.sessionId());
        return session!=null&&session.visualZapperTrigger(binding);
    }
    static int controllerAnimationMask(long id,int port) {
        ClientArcadeSession session=SESSIONS.get(id);
        return session==null?-1:session.controllerAnimationMask(id,port);
    }

    /** Read-only NES button state for the physical cabinet currently rendered in this world. */
    public static int[] cabinetVisualInputs(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        if (pos == null || mc.level == null || mc.getConnection() == null || mc.player == null
                || !mc.player.isAlive() || mc.player.isSpectator() || mc.screen != null
                || !mc.isWindowActive() || mc.isPaused()) return new int[2];
        for (var entry : SESSION_POSITIONS.entrySet()) {
            if (!pos.equals(entry.getValue())) continue;
            ClientArcadeSession session = SESSIONS.get(entry.getKey());
            // textureAt also validates the session position and current dimension.
            if (session != null && session.textureAt(pos) != null)
                return new int[]{Math.max(0, session.controllerAnimationMask(entry.getKey(), 0)),
                        Math.max(0, session.controllerAnimationMask(entry.getKey(), 1))};
        }
        return new int[2];
    }

    private static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        ArcadeKeyMappings.register(event);
    }

    private static void registerRenderers(
            EntityRenderersEvent.RegisterRenderers event
    ) {
        event.registerBlockEntityRenderer(
                ModBlockEntities.LEGACY_FC_ARCADE.get(),
                LegacyArcadeSkinRenderer::new);
    }

    static java.util.List<FcPerformanceView.Entry> performanceEntries(boolean enabled) {
        var entries = new java.util.ArrayList<FcPerformanceView.Entry>();
        for (var session : SESSIONS.values()) {
            var entry = session.performance(enabled);
            if (entry != null) entries.add(entry);
        }
        return java.util.List.copyOf(entries);
    }

    private static void registerClientCommands(
            RegisterClientCommandsEvent event
    ) {
        // Local settings are available to every player; this never sends an OP/server request.
        FcPerformanceClient.registerCommands(event);
        event.getDispatcher().register(Commands.literal("fc-controls").executes(context -> {
            Minecraft.getInstance().setScreen(new cn.piq.retro.client.ControlSettingsScreen(null));return 1;
        }));
        event.getDispatcher().register(Commands.literal("fc-gamepad").executes(context -> {
            Minecraft minecraft = Minecraft.getInstance();
            minecraft.setScreen(cn.piq.retro.client.GamepadInput.settings(null));
            return 1;
        }));
        event.getDispatcher().register(
                Commands.literal("fc-keys")
                        .executes(context -> {
                            Minecraft minecraft = Minecraft.getInstance();
                            minecraft.setScreen(new KeyBindsScreen(
                                    minecraft.screen,
                                    minecraft.options));
                            return 1;
                        }));
        event.getDispatcher().register(
                Commands.literal("fc-client")
                        .executes(context -> reportClientStatus())
                        .then(Commands.literal("status")
                                .executes(context -> reportClientStatus()))
                        .then(Commands.literal("hand-size")
                                .executes(context -> { clientMessage("FC 手部大小："+controllerHandSize()+"（默认 1.18，范围 1.0~1.3）"); return 1; })
                                .then(Commands.argument("scale",com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(1,1.30))
                                        .executes(context -> setControllerHandSize(com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(context,"scale")))))
                        .then(Commands.literal("aspect")
                                .executes(context -> reportScreenAspect())
                                .then(Commands.literal("4:3").executes(context -> setScreenAspect("4:3")))
                                .then(Commands.literal("1:1").executes(context -> setScreenAspect("1:1")))
                                .then(Commands.literal("16:9").executes(context -> setScreenAspect("16:9"))))
                        .then(Commands.literal("spectators")
                                .executes(context -> reportSpectatorLimit())
                                .then(Commands.argument("count", IntegerArgumentType.integer(0, 8))
                                        .executes(context -> setSpectatorLimit(
                                                IntegerArgumentType.getInteger(context, "count"))))));
        event.getDispatcher().register(
                Commands.literal("fc-scorecal")
                        .requires(ignored -> isLocalOperator())
                        .then(Commands.literal("start")
                                .executes(context -> runScoreCalibration(
                                        ClientArcadeSession::startScoreCalibration)))
                        .then(Commands.literal("mark")
                                .then(Commands.argument(
                                                "score",
                                                IntegerArgumentType.integer(0, 999_999_999))
                                        .executes(context -> runScoreCalibration(
                                                session -> session.markScoreCalibration(
                                                        IntegerArgumentType.getInteger(
                                                                context,
                                                                "score"))))))
                        .then(Commands.literal("finish")
                                .executes(context -> runScoreCalibration(
                                        ClientArcadeSession::finishScoreCalibration)))
                        .then(Commands.literal("cancel")
                                .executes(context -> runScoreCalibration(
                                        ClientArcadeSession::cancelScoreCalibration))));
    }

    private static boolean isLocalOperator() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.player != null && minecraft.player.hasPermissions(2);
    }

    private static LocalArcadePreferences preferences() {
        if (localPreferences == null) {
            localPreferences = LocalArcadePreferences.forGameDirectory(
                    Minecraft.getInstance().gameDirectory.toPath());
            try {
                localPreferences.loadOnce();
            } catch (IOException error) {
                FcArcadeMod.LOGGER.warn("[PIQ FC] 读取本地旁观设置失败，使用默认 2 台", error);
            }
        }
        return localPreferences;
    }

    private static int reportSpectatorLimit() {
        clientMessage("FC 本地自动旁观上限：" + preferences().maximumSpectators()
                + " 台（0 关闭自动旁观，自己控制的机器不占限额）");
        return 1;
    }

    static cn.piq.fcarcade.layout.ScreenAspectFit.Aspect dualScreenAspect() { return preferences().dualScreenAspect(); }
    static double controllerHandSize() { return preferences().controllerHandSize(); }
    private static int setControllerHandSize(double value) {
        var prefs=preferences();prefs.setControllerHandSize(value);
        try { prefs.save();clientMessage("FC 手部大小已设为 "+value+"，手柄握点和低位置不变（仅本机第一人称）"); }
        catch(IOException error) { clientMessage("FC 手部大小已生效，但保存失败，重启后可能恢复原设置"); }
        return 1;
    }
    private static int reportScreenAspect() {
        clientMessage("FC 双人宽屏画面比例：" + dualScreenAspect().label() + "（仅本机显示，黑边居中；默认4:3）");
        return 1;
    }
    private static int setScreenAspect(String label) {
        var prefs = preferences();
        prefs.setDualScreenAspect(cn.piq.fcarcade.layout.ScreenAspectFit.Aspect.parse(label));
        try {
            prefs.save();
            clientMessage("FC 双人宽屏画面已设为 " + label + " 并保存；不影响其他玩家、单人街机或电视");
        } catch (IOException error) {
            FcArcadeMod.LOGGER.warn("[PIQ FC] 保存本地画面比例失败", error);
            clientMessage("FC 画面已设为 " + label + "，但保存失败，重启后可能恢复原设置");
        }
        return 1;
    }

    private static int setSpectatorLimit(int maximum) {
        LocalArcadePreferences preferences = preferences();
        preferences.setMaximumSpectators(maximum);
        refreshSimulationSelection(true);
        try {
            preferences.save();
            clientMessage("FC 本地自动旁观上限已立即设为 " + maximum
                    + " 台并保存；自己控制的机器不受影响");
        } catch (IOException error) {
            FcArcadeMod.LOGGER.warn("[PIQ FC] 保存本地旁观设置失败", error);
            clientMessage("FC 本地自动旁观上限已立即设为 " + maximum
                    + " 台，但保存失败；重启后不会保留本次设置");
        }
        return 1;
    }

    private static int reportClientStatus() {
        long active = SESSIONS.values().stream()
                .filter(ClientArcadeSession::isActive).count();
        long running = SESSIONS.values().stream()
                .filter(ClientArcadeSession::isSimulationRunning).count();
        long controllers = SESSIONS.values().stream()
                .filter(ClientArcadeSession::hasController).count();
        clientMessage("FC 本地：会话 " + active + "，实际模拟 " + running
                + "，控制中 " + controllers + "，自动旁观上限 "
                + preferences().maximumSpectators());
        clientMessage("待路由同步包：存档 " + PENDING_PERSISTENT_STATES.size()
                + "，快照 " + PENDING_SNAPSHOTS.size() + "，历史 "
                + PENDING_HISTORIES.size());
        SESSIONS.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> clientMessage("#" + entry.getKey() + " "
                        + entry.getValue().diagnosticSummary()));
        return 1;
    }

    private static void clientMessage(String message) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.literal(message), false);
        }
    }

    private static void refreshSimulationSelection(boolean force) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null
                || minecraft.getConnection() == null) return;
        long now = System.nanoTime();
        if (!force && now < nextSelectionNanos) return;
        nextSelectionNanos = now + SELECTION_INTERVAL_NANOS;
        List<SpectatorSelection.Candidate> candidates = new ArrayList<>();
        for (Map.Entry<Long, ClientArcadeSession> entry : SESSIONS.entrySet()) {
            ClientArcadeSession session = entry.getValue();
            if (!session.isActive()) continue;
            BlockPos pos = SESSION_POSITIONS.get(entry.getKey());
            if (session.hasController()||session.isComputeHost()) {
                candidates.add(new SpectatorSelection.Candidate(entry.getKey(), true, 0.0D));
            } else if (pos != null && session.isVisibleAt(pos)) {
                double distanceSquared = minecraft.player.distanceToSqr(
                        pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
                candidates.add(new SpectatorSelection.Candidate(
                        entry.getKey(), false, distanceSquared));
            }
        }
        Set<Long> jniSessions = new java.util.HashSet<>();
        SESSIONS.forEach((id,session)->{if(session.usesJniNetplay())jniSessions.add(id);});
        Set<Long> selected = SpectatorSelection.selectWithJni(
                candidates, selectedSimulations, preferences().maximumSpectators(),jniSessions);
        selectedSimulations = selected;
        // Retire first so changing the limit never transiently exceeds it.
        for (Map.Entry<Long, ClientArcadeSession> entry : SESSIONS.entrySet()) {
            if (!selected.contains(entry.getKey())) {
                safelySetSimulation(entry.getKey(), entry.getValue(), false);
            }
        }
        for (Long sessionId : selected) {
            ClientArcadeSession session = SESSIONS.get(sessionId);
            if (session != null) safelySetSimulation(sessionId, session, true);
        }
    }

    private static void safelySetSimulation(
            long sessionId, ClientArcadeSession session, boolean enabled
    ) {
        try {
            session.setSimulationEnabled(enabled);
        } catch (Throwable error) {
            FcArcadeMod.LOGGER.error("[PIQ FC] 隔离客户端模拟启停失败 #{}", sessionId, error);
            safelyLeave(sessionId, session);
        }
    }

    private static int runScoreCalibration(ScoreCalibrationCommand command) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientArcadeSession session = SESSIONS.values().stream()
                .filter(ClientArcadeSession::hasController)
                .findFirst()
                .orElse(null);
        String message = session == null
                ? "当前没有正在操作的 FC 街机"
                : command.run(session);
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.literal(message), false);
        }
        return session == null ? 0 : 1;
    }

    @FunctionalInterface
    private interface ScoreCalibrationCommand {
        String run(ClientArcadeSession session);
    }

    private static void onRenderFrame(RenderFrameEvent.Pre event) {
        Minecraft minecraft = Minecraft.getInstance();
        try {
            if (minecraft.level == null || minecraft.player == null
                    || minecraft.getConnection() == null) {
                clearOnDisconnect();
                return;
            }
            ArcadeKeyMappings.syncOtherMappings();
            updateTransfers();
            refreshSimulationSelection(false);
            Iterator<Map.Entry<Long, ClientArcadeSession>> iterator =
                    SESSIONS.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<Long, ClientArcadeSession> entry = iterator.next();
                ClientArcadeSession session = entry.getValue();
                boolean failed = false;
                try {
                    session.update();
                } catch (Throwable error) {
                    failed = true;
                    FcArcadeMod.LOGGER.error("[PIQ FC] 隔离客户端会话更新失败 #{}",
                            entry.getKey(), error);
                    safelyLeave(entry.getKey(), session);
                }
                if (failed || !session.isActive()) {
                    clearSessionMetadata(entry.getKey());
                    iterator.remove();
                }
            }
        } finally {
            // Never strand bindings when an update fails while an arcade owns
            // them: either restore all on disconnect or sync remaining owners.
            if (minecraft.level == null || minecraft.player == null
                    || minecraft.getConnection() == null) {
                ArcadeKeyMappings.restoreAll();
            } else {
                ArcadeKeyMappings.syncOtherMappings();
            }
            ClientControllerAnimation.frame();
        }
    }

    private static void updateTransfers() {
        try {
            ClientRomTransfers.update();
        } catch (RuntimeException error) {
            FcArcadeMod.LOGGER.error("[PIQ FC] 隔离 ROM 传输更新失败", error);
            ClientRomTransfers.clearOnDisconnect();
        }
        try {
            ClientSkinManager.update();
        } catch (RuntimeException error) {
            FcArcadeMod.LOGGER.error("[PIQ FC] 隔离皮肤传输更新失败", error);
            ClientSkinManager.clearOnDisconnect();
        }
    }

    static void playerMediaDemand(cn.piq.fcarcade.cabinet.WatchNetwork.HostDemand demand) {
        for(var session:List.copyOf(SESSIONS.values()))session.mediaDemand(demand);
    }
    static boolean hasHomeParticipant(){return SESSIONS.values().stream().anyMatch(s->s.hasController()||s.isComputeHost());}
    static boolean hasHomeParticipant(cn.piq.fcarcade.cabinet.WatchDescriptor source){
        return SESSION_POSITIONS.entrySet().stream().anyMatch(e->source.screens().stream().anyMatch(a->a.pos().equals(e.getValue()))
                &&SESSIONS.containsKey(e.getKey())&&(SESSIONS.get(e.getKey()).hasController()||SESSIONS.get(e.getKey()).isComputeHost()));
    }

    private static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut ignored) {
        clearOnDisconnect();
    }

    private static void clearOnDisconnect() {
        ControllerCapture.clear();
        ClientControllerAnimation.clear();
        try {
            for (Map.Entry<Long, ClientArcadeSession> entry : SESSIONS.entrySet()) {
                safelyLeave(entry.getKey(), entry.getValue());
            }
        } finally {
            SESSIONS.clear();
            HOME_APPROVALS.clear();
            SESSION_POSITIONS.clear();
            PENDING_PERSISTENT_STATES.clear();
            PENDING_SNAPSHOTS.clear();
            PENDING_HISTORIES.clear();
            selectedSimulations = Set.of();
            nextSelectionNanos = 0L;
            try {
                ClientRomTransfers.clearOnDisconnect();
            } finally {
                try {
                    ClientSkinManager.clearOnDisconnect();
                } finally {
                    ArcadeKeyMappings.restoreAll();
                }
            }
        }
    }

    private static void clearSessionMetadata(long sessionId) {
        SESSION_POSITIONS.remove(sessionId);
        PENDING_PERSISTENT_STATES.remove(sessionId);
        PENDING_SNAPSHOTS.remove(sessionId);
        PENDING_HISTORIES.remove(sessionId);
    }

    private static void safelyLeave(long sessionId, ClientArcadeSession session) {
        try {
            session.leave();
        } catch (Throwable error) {
            FcArcadeMod.LOGGER.error("[PIQ FC] 清理客户端会话失败 #{}", sessionId, error);
        }
    }

    private static void onRenderLevel(RenderLevelStageEvent event) {
        for (Map.Entry<Long, BlockPos> entry : SESSION_POSITIONS.entrySet()) {
            ClientArcadeSession session = SESSIONS.get(entry.getKey());
            if (session != null) {
                ArcadeBlockScreenRenderer.render(event, session, entry.getValue());
            }
        }
    }

    private static void onKeyInput(InputEvent.Key event) {
        ArcadeKeyMappings.syncOtherMappings();
        captureInputEdges();
    }

    private static void onMouseInput(InputEvent.MouseButton.Post event) {
        ArcadeKeyMappings.syncOtherMappings();
        captureInputEdges();
    }

    private static void onScreenOpening(ScreenEvent.Opening event) {
        if (event.getNewScreen() != null)
            for (ClientArcadeSession session : List.copyOf(SESSIONS.values())) session.suspendInput();
    }

    private static void captureInputEdges() {
        // Key and MouseButton.Post fire after KeyMapping.set. Sampling here
        // retains two edges delivered between renders, including mouse remaps;
        // identical masks from OS REPEAT or overlapping aliases are deduplicated.
        for (ClientArcadeSession session : List.copyOf(SESSIONS.values())) {
            session.captureInputEvent();
        }
    }
}
