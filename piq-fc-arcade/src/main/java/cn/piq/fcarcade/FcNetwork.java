package cn.piq.fcarcade;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

public final class FcNetwork {
    // Persistent upload/initial restore can carry PFP1; reject older peers before joining.
    static final String PROTOCOL_VERSION = "45";

    public interface ZapperSink {
        boolean acceptsConnection(net.minecraft.network.Connection connection);
        void start(cn.piq.fcarcade.home.ZapperBinding binding);
        void stop(java.util.UUID lease);
    }
    private static volatile ZapperSink zapperSink;
    public static void setZapperSink(ZapperSink sink){zapperSink=java.util.Objects.requireNonNull(sink);}
    public static void sendZapperInput(ArcadeZapperInputPayload payload){PacketDistributor.sendToServer(payload);}
    public static void sendZapperSession(ServerPlayer player,ArcadeZapperSessionPayload payload){PacketDistributor.sendToPlayer(player,payload);}

    private FcNetwork() {
    }
    private static void clientWork(net.neoforged.neoforge.network.handling.IPayloadContext context,Runnable action){
        var source=context.connection();context.enqueueWork(()->{if(source!=null&&source.isConnected()&&ClientArcadeSupport.acceptsConnection(source))action.run();});
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = cn.piq.fcarcade.network.TrafficPayloadRegistrar.create(event,PROTOCOL_VERSION);
        registrar.playToServer(ArcadeHomeSaveActionPayload.TYPE,ArcadeHomeSaveActionPayload.STREAM_CODEC,(payload,context)->{
            var source=context.connection();context.enqueueWork(()->{if(context.player() instanceof ServerPlayer p&&source!=null&&source.isConnected()&&p.connection.getConnection()==source)
                cn.piq.fcarcade.server.ServerArcadeSessions.homeSaveAction(p,payload);});
        }).playToClient(ArcadeHomeSaveSlotsPayload.TYPE,ArcadeHomeSaveSlotsPayload.STREAM_CODEC,(payload,context)->{
            if(FMLEnvironment.dist.isClient())clientWork(context,()->ClientArcadeSupport.openHomeSaveSlots(payload));
        });
        registrar.playToServer(ArcadeHomeReadyPayload.TYPE,ArcadeHomeReadyPayload.STREAM_CODEC,(payload,context)->{
            var source=context.connection();context.enqueueWork(()->{if(context.player() instanceof ServerPlayer p&&source!=null&&source.isConnected()&&p.connection.getConnection()==source)
                cn.piq.fcarcade.server.ServerArcadeSessions.homeReady(p,payload);});
        });
        registrar.playToServer(ArcadeHomeInputPayload.TYPE,ArcadeHomeInputPayload.STREAM_CODEC,(payload,context)->{
            var source=context.connection();context.enqueueWork(()->{
                if(context.player() instanceof ServerPlayer player&&source!=null&&source.isConnected()
                        &&player.connection.getConnection()==source&&player.getServer()!=null&&player.getServer().getPlayerList().getPlayer(player.getUUID())==player)
                    cn.piq.fcarcade.server.ServerArcadeSessions.handleHomeInput(player,payload);
            });
        });
        registrar.playToServer(ArcadeZapperInputPayload.TYPE,ArcadeZapperInputPayload.STREAM_CODEC,(payload,context)->{
            var source=context.connection();
            context.enqueueWork(()->{if(context.player() instanceof ServerPlayer player&&source!=null&&source.isConnected()
                    &&player.connection.getConnection()==source&&player.getServer()!=null&&player.getServer().getPlayerList().getPlayer(player.getUUID())==player)
                cn.piq.fcarcade.server.ServerArcadeSessions.handleZapperInput(player,payload);});
        }).playToClient(ArcadeZapperSessionPayload.TYPE,ArcadeZapperSessionPayload.STREAM_CODEC,(payload,context)->{
            var source=context.connection();
            context.enqueueWork(()->{var sink=zapperSink;if(source!=null&&source.isConnected()&&sink!=null&&sink.acceptsConnection(source)){
                if(payload.active())sink.start(payload.binding());else sink.stop(payload.binding().lease());
            }});
        });
        registrar
                .playToClient(
                        SkinLibraryPayload.TYPE,
                        SkinLibraryPayload.STREAM_CODEC,
                        (payload, context) -> {
                            if (FMLEnvironment.dist.isClient()) {
                                context.enqueueWork(
                                        () -> ClientArcadeSupport
                                                .openSkinLibrary(payload));
                            }
                        })
                .playToClient(
                        SkinDownloadStartPayload.TYPE,
                        SkinDownloadStartPayload.STREAM_CODEC,
                        (payload, context) -> {
                            if (FMLEnvironment.dist.isClient()) {
                                context.enqueueWork(
                                        () -> ClientArcadeSupport
                                                .startSkinDownload(payload));
                            }
                        })
                .playToClient(
                        SkinDownloadChunkPayload.TYPE,
                        SkinDownloadChunkPayload.STREAM_CODEC,
                        (payload, context) -> {
                            if (FMLEnvironment.dist.isClient()) {
                                context.enqueueWork(
                                        () -> ClientArcadeSupport
                                                .acceptSkinDownload(payload));
                            }
                        })
                .playToClient(ArcadeLibraryPayload.TYPE, ArcadeLibraryPayload.STREAM_CODEC,
                        (payload, context) -> {
                            if (FMLEnvironment.dist.isClient()) {
                                context.enqueueWork(
                                        () -> ClientArcadeSupport.openLibrary(payload));
                            }
                        })
                .playToClient(
                        ArcadeSettingsPayload.TYPE,
                        ArcadeSettingsPayload.STREAM_CODEC,
                        (payload, context) -> {
                            if (FMLEnvironment.dist.isClient()) {
                                context.enqueueWork(
                                        () -> ClientArcadeSupport.openSettings(payload));
                            }
                        })
                .playToClient(
                        LeaderboardPanelConfigPayload.TYPE,
                        LeaderboardPanelConfigPayload.STREAM_CODEC,
                        (payload, context) -> {
                            if (FMLEnvironment.dist.isClient()) {
                                context.enqueueWork(() -> ClientArcadeSupport
                                        .openLeaderboardPanelConfig(payload));
                            }
                        })
                .playToClient(
                        ArcadeSaveCatalogPayload.TYPE,
                        ArcadeSaveCatalogPayload.STREAM_CODEC,
                        (payload, context) -> {
                            if (FMLEnvironment.dist.isClient()) {
                                context.enqueueWork(
                                        () -> ClientArcadeSupport
                                                .openSaveCatalog(payload));
                            }
                        })
                .playToClient(
                        ArcadeSaveSlotsPayload.TYPE,
                        ArcadeSaveSlotsPayload.STREAM_CODEC,
                        (payload, context) -> {
                            if (FMLEnvironment.dist.isClient()) {
                                context.enqueueWork(
                                        () -> ClientArcadeSupport
                                                .openSaveSlots(payload));
                            }
                        })
                .playToClient(
                        ArcadeMultiplayerOfferPayload.TYPE,
                        ArcadeMultiplayerOfferPayload.STREAM_CODEC,
                        (payload, context) -> {
                            if (FMLEnvironment.dist.isClient()) {
                                context.enqueueWork(
                                        () -> ClientArcadeSupport.offerMultiplayer(payload));
                            }
                        })
                .playToClient(
                        ArcadeJoinApprovalPayload.TYPE,
                        ArcadeJoinApprovalPayload.STREAM_CODEC,
                        (payload, context) -> {
                            if (FMLEnvironment.dist.isClient()) {
                                clientWork(context,() -> ClientArcadeSupport.requestJoinApproval(payload));
                            }
                        })
                .playToClient(ArcadeJoinPromptPayload.TYPE, ArcadeJoinPromptPayload.STREAM_CODEC,
                        (payload, context) -> {
                            if (FMLEnvironment.dist.isClient()) {
                                context.enqueueWork(
                                        () -> ClientArcadeSupport.requestJoin(payload));
                            }
                        })
                .playToClient(
                        ArcadeExitPromptPayload.TYPE,
                        ArcadeExitPromptPayload.STREAM_CODEC,
                        (payload, context) -> {
                            if (FMLEnvironment.dist.isClient()) {
                                context.enqueueWork(
                                        () -> ClientArcadeSupport.promptExit(payload));
                            }
                        })
                .playToClient(
                        ArcadeResumePromptPayload.TYPE,
                        ArcadeResumePromptPayload.STREAM_CODEC,
                        (payload, context) -> {
                            if (FMLEnvironment.dist.isClient()) {
                                context.enqueueWork(
                                        () -> ClientArcadeSupport.promptResume(payload));
                            }
                        })
                .playToClient(ArcadeSessionPayload.TYPE, ArcadeSessionPayload.STREAM_CODEC,
                        (payload, context) -> {
                            if (FMLEnvironment.dist.isClient()) {
                                clientWork(context,() -> ClientArcadeSupport.updateSession(payload));
                            }
                        })
                .playToClient(ArcadeFramePayload.TYPE, ArcadeFramePayload.STREAM_CODEC,
                        (payload, context) -> {
                            if (FMLEnvironment.dist.isClient()) {
                                clientWork(context,() -> ClientArcadeSupport.advanceFrame(payload));
                            }
                        })
                .playToClient(ArcadeHistoryPayload.TYPE, ArcadeHistoryPayload.STREAM_CODEC,
                        (payload, context) -> {
                            if (FMLEnvironment.dist.isClient()) {
                                clientWork(context,() -> ClientArcadeSupport.applyHistory(payload));
                            }
                        })
                .playToClient(
                        ArcadeSnapshotRequestPayload.TYPE,
                        ArcadeSnapshotRequestPayload.STREAM_CODEC,
                        (payload, context) -> {
                            if (FMLEnvironment.dist.isClient()) {
                                clientWork(context,() -> ClientArcadeSupport.requestSnapshot(payload));
                            }
                        })
                .playToClient(ArcadeSnapshotPayload.TYPE, ArcadeSnapshotPayload.STREAM_CODEC,
                        (payload, context) -> {
                            if (FMLEnvironment.dist.isClient()) {
                                clientWork(context,() -> ClientArcadeSupport.applySnapshot(payload));
                            }
                        })
                .playToClient(
                        ArcadePersistentStatePayload.TYPE,
                        ArcadePersistentStatePayload.STREAM_CODEC,
                        (payload, context) -> {
                            if (FMLEnvironment.dist.isClient()) {
                                clientWork(context,() -> ClientArcadeSupport.applyPersistentState(payload));
                            }
                        })
                .playToClient(
                        RomDownloadStartPayload.TYPE,
                        RomDownloadStartPayload.STREAM_CODEC,
                        (payload, context) -> {
                            if (FMLEnvironment.dist.isClient()) {
                                context.enqueueWork(
                                        () -> ClientArcadeSupport.startRomDownload(payload));
                            }
                        })
                .playToClient(
                        RomDownloadChunkPayload.TYPE,
                        RomDownloadChunkPayload.STREAM_CODEC,
                        (payload, context) -> {
                            if (FMLEnvironment.dist.isClient()) {
                                context.enqueueWork(
                                        () -> ClientArcadeSupport.acceptRomDownload(payload));
                            }
                        })
                .playToServer(
                        SkinLibraryRequestPayload.TYPE,
                        SkinLibraryRequestPayload.STREAM_CODEC,
                        (payload, context) -> context.enqueueWork(() -> {
                            if (context.player() instanceof ServerPlayer player) {
                                cn.piq.fcarcade.server.ServerSkinService
                                        .openLibrary(player, payload.blockPos());
                            }
                        }))
                .playToServer(
                        SkinUploadStartPayload.TYPE,
                        SkinUploadStartPayload.STREAM_CODEC,
                        (payload, context) -> context.enqueueWork(() -> {
                            if (context.player() instanceof ServerPlayer player) {
                                cn.piq.fcarcade.server.ServerSkinService
                                        .beginUpload(player, payload);
                            }
                        }))
                .playToServer(
                        SkinUploadChunkPayload.TYPE,
                        SkinUploadChunkPayload.STREAM_CODEC,
                        (payload, context) -> context.enqueueWork(() -> {
                            if (context.player() instanceof ServerPlayer player) {
                                cn.piq.fcarcade.server.ServerSkinService
                                        .acceptUploadChunk(player, payload);
                            }
                        }))
                .playToServer(
                        SkinSelectPayload.TYPE,
                        SkinSelectPayload.STREAM_CODEC,
                        (payload, context) -> context.enqueueWork(() -> {
                            if (context.player() instanceof ServerPlayer player) {
                                cn.piq.fcarcade.server.ServerSkinService
                                        .select(player, payload);
                            }
                        }))
                .playToServer(
                        SkinDownloadRequestPayload.TYPE,
                        SkinDownloadRequestPayload.STREAM_CODEC,
                        (payload, context) -> context.enqueueWork(() -> {
                            if (context.player() instanceof ServerPlayer player) {
                                cn.piq.fcarcade.server.ServerSkinService
                                        .requestDownload(
                                                player,
                                                payload.sha256());
                            }
                        }))
                .playToServer(
                        ArcadeLibraryRequestPayload.TYPE,
                        ArcadeLibraryRequestPayload.STREAM_CODEC,
                        (payload, context) -> context.enqueueWork(() -> {
                            if (context.player() instanceof ServerPlayer player) {
                                cn.piq.fcarcade.server.ServerArcadeSessions.openLibrary(
                                        player,
                                        payload.blockPos());
                            }
                        }))
                .playToServer(ArcadeJoinRequestPayload.TYPE, ArcadeJoinRequestPayload.STREAM_CODEC,
                        (payload, context) -> context.enqueueWork(() -> {
                            if (context.player() instanceof ServerPlayer player) {
                                cn.piq.fcarcade.server.ServerArcadeSessions.joinArcade(
                                        player,
                                        payload.blockPos(),
                                        payload.romSha256());
                            }
                        }))
                .playToServer(
                        ArcadeExitDecisionPayload.TYPE,
                        ArcadeExitDecisionPayload.STREAM_CODEC,
                        (payload, context) -> context.enqueueWork(() -> {
                            if (context.player() instanceof ServerPlayer player) {
                                cn.piq.fcarcade.server.ServerArcadeSessions.decideExit(
                                        player,
                                        payload);
                            }
                        }))
                .playToServer(
                        ArcadeResumeDecisionPayload.TYPE,
                        ArcadeResumeDecisionPayload.STREAM_CODEC,
                        (payload, context) -> context.enqueueWork(() -> {
                            if (context.player() instanceof ServerPlayer player) {
                                cn.piq.fcarcade.server.ServerArcadeSessions.decideResume(
                                        player,
                                        payload);
                            }
                        }))
                .playToServer(
                        RomSelectRequestPayload.TYPE,
                        RomSelectRequestPayload.STREAM_CODEC,
                        (payload, context) -> context.enqueueWork(() -> {
                            if (context.player() instanceof ServerPlayer player) {
                                cn.piq.fcarcade.server.ServerArcadeSessions.selectRom(
                                        player,
                                        payload);
                            }
                        }))
                .playToServer(
                        RomPlayerModePayload.TYPE,
                        RomPlayerModePayload.STREAM_CODEC,
                        (payload, context) -> context.enqueueWork(() -> {
                            if (context.player() instanceof ServerPlayer player) {
                                cn.piq.fcarcade.server.ServerArcadeSessions.setRomPlayerMode(
                                        player,
                                        payload);
                            }
                        }))
                .playToServer(
                        RomSaveModePayload.TYPE,
                        RomSaveModePayload.STREAM_CODEC,
                        (payload, context) -> context.enqueueWork(() -> {
                            if (context.player() instanceof ServerPlayer player) {
                                cn.piq.fcarcade.server.ServerArcadeSessions.setRomSaveMode(
                                        player,
                                        payload);
                            }
                        }))
                .playToServer(
                        RomDeletePayload.TYPE,
                        RomDeletePayload.STREAM_CODEC,
                        (payload, context) -> context.enqueueWork(() -> {
                            if (context.player() instanceof ServerPlayer player) {
                                cn.piq.fcarcade.server.ServerArcadeSessions.deleteRom(
                                        player,
                                        payload);
                            }
                        }))
                .playToServer(
                        ArcadeSettingsRequestPayload.TYPE,
                        ArcadeSettingsRequestPayload.STREAM_CODEC,
                        (payload, context) -> context.enqueueWork(() -> {
                            if (context.player() instanceof ServerPlayer player) {
                                cn.piq.fcarcade.server.ServerArcadeSessions.openSettings(
                                        player,
                                        payload.blockPos());
                            }
                        }))
                .playToServer(
                        ArcadeSaveCatalogRequestPayload.TYPE,
                        ArcadeSaveCatalogRequestPayload.STREAM_CODEC,
                        (payload, context) -> context.enqueueWork(() -> {
                            if (context.player() instanceof ServerPlayer player) {
                                cn.piq.fcarcade.server.ServerArcadeSessions
                                        .openSaveCatalog(
                                                player,
                                                payload.blockPos());
                            }
                        }))
                .playToServer(
                        ArcadeSaveSlotActionPayload.TYPE,
                        ArcadeSaveSlotActionPayload.STREAM_CODEC,
                        (payload, context) -> context.enqueueWork(() -> {
                            if (context.player() instanceof ServerPlayer player) {
                                cn.piq.fcarcade.server.ServerArcadeSessions
                                        .handleSaveSlotAction(
                                                player,
                                                payload);
                            }
                        }))
                .playToServer(
                        ArcadeSaveDeletePayload.TYPE,
                        ArcadeSaveDeletePayload.STREAM_CODEC,
                        (payload, context) -> context.enqueueWork(() -> {
                            if (context.player() instanceof ServerPlayer player) {
                                cn.piq.fcarcade.server.ServerArcadeSessions
                                        .deleteSave(player, payload);
                            }
                        }))
                .playToServer(
                        RomRenamePayload.TYPE,
                        RomRenamePayload.STREAM_CODEC,
                        (payload, context) -> context.enqueueWork(() -> {
                            if (context.player() instanceof ServerPlayer player) {
                                cn.piq.fcarcade.server.ServerArcadeSessions
                                        .renameRom(player, payload);
                            }
                        }))
                .playToServer(
                        ArcadeSettingsUpdatePayload.TYPE,
                        ArcadeSettingsUpdatePayload.STREAM_CODEC,
                        (payload, context) -> context.enqueueWork(() -> {
                            if (context.player() instanceof ServerPlayer player) {
                                cn.piq.fcarcade.server.ServerArcadeSessions.updateSettings(
                                        player,
                                        payload);
                            }
                        }))
                .playToServer(
                        ArcadeLeaderboardTogglePayload.TYPE,
                        ArcadeLeaderboardTogglePayload.STREAM_CODEC,
                        (payload, context) -> context.enqueueWork(() -> {
                            if (context.player() instanceof ServerPlayer player) {
                                cn.piq.fcarcade.server.ServerArcadeSessions
                                        .setArcadeLeaderboardEnabled(player, payload);
                            }
                        }))
                .playToServer(
                        LeaderboardPanelConfigUpdatePayload.TYPE,
                        LeaderboardPanelConfigUpdatePayload.STREAM_CODEC,
                        (payload, context) -> context.enqueueWork(() -> {
                            if (context.player() instanceof ServerPlayer player) {
                                cn.piq.fcarcade.server.ServerArcadeSessions
                                        .updateLeaderboardPanel(player, payload);
                            }
                        }))
                .playToServer(
                        RomUploadStartPayload.TYPE,
                        RomUploadStartPayload.STREAM_CODEC,
                        (payload, context) -> context.enqueueWork(() -> {
                            if (context.player() instanceof ServerPlayer player) {
                                cn.piq.fcarcade.server.ServerArcadeSessions.beginRomUpload(
                                        player,
                                        payload);
                            }
                        }))
                .playToServer(
                        RomUploadChunkPayload.TYPE,
                        RomUploadChunkPayload.STREAM_CODEC,
                        (payload, context) -> context.enqueueWork(() -> {
                            if (context.player() instanceof ServerPlayer player) {
                                cn.piq.fcarcade.server.ServerArcadeSessions
                                        .acceptRomUploadChunk(player, payload);
                            }
                        }))
                .playToServer(
                        RomDownloadRequestPayload.TYPE,
                        RomDownloadRequestPayload.STREAM_CODEC,
                        (payload, context) -> context.enqueueWork(() -> {
                            if (context.player() instanceof ServerPlayer player) {
                                cn.piq.fcarcade.server.ServerArcadeSessions
                                        .requestRomDownload(player, payload.sha256());
                            }
                        }))
                .playToServer(
                        RomReadyPayload.TYPE,
                        RomReadyPayload.STREAM_CODEC,
                        (payload, context) -> context.enqueueWork(() -> {
                            if (context.player() instanceof ServerPlayer player) {
                                cn.piq.fcarcade.server.ServerArcadeSessions.handleRomReady(
                                        player,
                                        payload);
                            }
                        }))
                .playToServer(ArcadeInputPayload.TYPE, ArcadeInputPayload.STREAM_CODEC,
                        (payload, context) -> context.enqueueWork(() -> {
                            if (context.player() instanceof ServerPlayer player) {
                                cn.piq.fcarcade.server.ServerArcadeSessions.handleInput(
                                        player,
                                payload);
                            }
                        }))
                .playToServer(ArcadeDigestPayload.TYPE, ArcadeDigestPayload.STREAM_CODEC,
                        (payload, context) -> context.enqueueWork(() -> {
                            if (context.player() instanceof ServerPlayer player) {
                                cn.piq.fcarcade.server.ServerArcadeSessions.handleDigest(
                                        player,
                                payload);
                            }
                        }))
                .playToServer(
                        ArcadeSnapshotUploadPayload.TYPE,
                        ArcadeSnapshotUploadPayload.STREAM_CODEC,
                        (payload, context) -> context.enqueueWork(() -> {
                            if (context.player() instanceof ServerPlayer player) {
                                cn.piq.fcarcade.server.ServerArcadeSessions.handleSnapshot(
                                        player,
                                        payload);
                            }
                        }))
                .playToServer(ArcadeResetPayload.TYPE, ArcadeResetPayload.STREAM_CODEC,
                        (payload, context) -> context.enqueueWork(() -> {
                            if (context.player() instanceof ServerPlayer player) {
                                cn.piq.fcarcade.server.ServerArcadeSessions.requestReset(
                                        player,
                                payload);
                            }
                        }))
                .playToServer(
                        ArcadeMultiplayerResponsePayload.TYPE,
                        ArcadeMultiplayerResponsePayload.STREAM_CODEC,
                        (payload, context) -> context.enqueueWork(() -> {
                            if (context.player() instanceof ServerPlayer player) {
                                cn.piq.fcarcade.server.ServerArcadeSessions
                                        .setMultiplayerEnabled(player, payload);
                            }
                        }))
                .playToServer(
                        ArcadeJoinDecisionPayload.TYPE,
                        ArcadeJoinDecisionPayload.STREAM_CODEC,
                        (payload, context) -> context.enqueueWork(() -> {
                            if (context.player() instanceof ServerPlayer player) {
                                cn.piq.fcarcade.server.ServerArcadeSessions
                                        .decideJoin(player, payload);
                            }
                        }))
                .playToServer(
                        ArcadeScorePayload.TYPE,
                        ArcadeScorePayload.STREAM_CODEC,
                        (payload, context) -> context.enqueueWork(() -> {
                            if (context.player() instanceof ServerPlayer player) {
                                cn.piq.fcarcade.server.ServerArcadeSessions
                                        .handleScore(player, payload);
                            }
                        }));
    }

    public static void promptJoin(ServerPlayer player, BlockPos pos, String romSha256) {
        PacketDistributor.sendToPlayer(
                player,
                new ArcadeJoinPromptPayload(pos, romSha256));
    }

    public static void sendLibrary(ServerPlayer player, ArcadeLibraryPayload payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    public static void sendSkinLibrary(
            ServerPlayer player,
            SkinLibraryPayload payload
    ) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    public static void startSkinDownload(
            ServerPlayer player,
            SkinDownloadStartPayload payload
    ) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    public static void sendSkinChunk(
            ServerPlayer player,
            SkinDownloadChunkPayload payload
    ) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    public static void sendSettings(ServerPlayer player, ArcadeSettingsPayload payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    public static void sendLeaderboardPanelConfig(
            ServerPlayer player,
            LeaderboardPanelConfigPayload payload
    ) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    public static void sendSaveCatalog(
            ServerPlayer player,
            ArcadeSaveCatalogPayload payload
    ) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    public static void sendSaveSlots(
            ServerPlayer player,
            ArcadeSaveSlotsPayload payload
    ) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    public static void offerMultiplayer(
            ServerPlayer player,
            ArcadeMultiplayerOfferPayload payload
    ) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    public static void requestJoinApproval(
            ServerPlayer player,
            ArcadeJoinApprovalPayload payload
    ) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    public static void startRomDownload(
            ServerPlayer player,
            RomDownloadStartPayload payload
    ) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    public static void sendRomDownloadChunk(
            ServerPlayer player,
            RomDownloadChunkPayload payload
    ) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    public static void sendSession(ServerPlayer player, ArcadeSessionPayload payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    public static void promptExit(
            ServerPlayer player,
            ArcadeExitPromptPayload payload
    ) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    public static void promptResume(
            ServerPlayer player,
            ArcadeResumePromptPayload payload
    ) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    public static void sendFrame(ServerPlayer player, ArcadeFramePayload payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    public static void sendHistory(ServerPlayer player, ArcadeHistoryPayload payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    public static void requestSnapshot(
            ServerPlayer player,
            ArcadeSnapshotRequestPayload payload
    ) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    public static void sendSnapshot(ServerPlayer player, ArcadeSnapshotPayload payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    public static void sendPersistentState(
            ServerPlayer player,
            ArcadePersistentStatePayload payload
    ) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    public static void requestJoin(BlockPos pos, String romSha256) {
        PacketDistributor.sendToServer(new ArcadeJoinRequestPayload(pos, romSha256));
    }

    public static void decideExit(long sessionId, boolean save) {
        PacketDistributor.sendToServer(new ArcadeExitDecisionPayload(
                sessionId,
                save));
    }

    public static void decideResume(
            BlockPos pos,
            String romSha256,
            boolean resume
    ) {
        PacketDistributor.sendToServer(new ArcadeResumeDecisionPayload(
                pos,
                romSha256,
                resume));
    }

    public static void requestLibrary(BlockPos pos) {
        PacketDistributor.sendToServer(new ArcadeLibraryRequestPayload(pos));
    }

    public static void requestSkinLibrary(BlockPos pos) {
        PacketDistributor.sendToServer(new SkinLibraryRequestPayload(pos));
    }

    public static void beginSkinUpload(SkinUploadStartPayload payload) {
        PacketDistributor.sendToServer(payload);
    }

    public static void uploadSkinChunk(SkinUploadChunkPayload payload) {
        PacketDistributor.sendToServer(payload);
    }

    public static void selectSkin(BlockPos pos, String sha256) {
        PacketDistributor.sendToServer(new SkinSelectPayload(pos, sha256));
    }

    public static void requestSkinDownload(String sha256) {
        PacketDistributor.sendToServer(new SkinDownloadRequestPayload(sha256));
    }

    public static void selectRom(BlockPos pos, String sha256) {
        PacketDistributor.sendToServer(new RomSelectRequestPayload(pos, sha256));
    }

    public static void setRomPlayerMode(BlockPos pos, String sha256, int maxPlayers) {
        PacketDistributor.sendToServer(new RomPlayerModePayload(
                pos,
                sha256,
                maxPlayers));
    }

    public static void setRomSaveMode(
            BlockPos pos,
            String sha256,
            cn.piq.fcarcade.rom.RomSaveMode saveMode
    ) {
        PacketDistributor.sendToServer(new RomSaveModePayload(
                pos,
                sha256,
                saveMode));
    }

    public static void deleteRom(BlockPos pos, String sha256) {
        PacketDistributor.sendToServer(new RomDeletePayload(pos, sha256));
    }

    public static void requestSettings(BlockPos pos) {
        PacketDistributor.sendToServer(new ArcadeSettingsRequestPayload(pos));
    }

    public static void requestSaveCatalog(BlockPos pos) {
        PacketDistributor.sendToServer(
                new ArcadeSaveCatalogRequestPayload(pos));
    }

    public static void actOnSaveSlot(ArcadeSaveSlotActionPayload payload) {
        PacketDistributor.sendToServer(payload);
    }
    public static void actOnHomeSaveSlot(ArcadeHomeSaveActionPayload payload){PacketDistributor.sendToServer(payload);}
    public static void sendHomeSaveSlots(ServerPlayer p,ArcadeHomeSaveSlotsPayload payload){PacketDistributor.sendToPlayer(p,payload);}

    public static void deleteSave(BlockPos pos, String storageId) {
        PacketDistributor.sendToServer(
                new ArcadeSaveDeletePayload(pos, storageId));
    }

    public static void renameRom(
            BlockPos pos,
            String sha256,
            String displayName
    ) {
        PacketDistributor.sendToServer(
                new RomRenamePayload(pos, sha256, displayName));
    }

    public static void updateSettings(
            BlockPos pos,
            cn.piq.fcarcade.config.ArcadeGlobalSettings settings
    ) {
        PacketDistributor.sendToServer(new ArcadeSettingsUpdatePayload(
                pos,
                settings));
    }

    public static void setArcadeLeaderboardEnabled(
            BlockPos pos,
            boolean enabled
    ) {
        PacketDistributor.sendToServer(new ArcadeLeaderboardTogglePayload(
                pos,
                enabled));
    }

    public static void updateLeaderboardPanel(
            LeaderboardPanelConfigUpdatePayload payload
    ) {
        PacketDistributor.sendToServer(payload);
    }

    public static void beginRomUpload(RomUploadStartPayload payload) {
        PacketDistributor.sendToServer(payload);
    }

    public static void uploadRomChunk(RomUploadChunkPayload payload) {
        PacketDistributor.sendToServer(payload);
    }

    public static void requestRomDownload(String sha256) {
        PacketDistributor.sendToServer(new RomDownloadRequestPayload(sha256));
    }

    public static void sendRomReady(long sessionId, String sha256) {
        PacketDistributor.sendToServer(new RomReadyPayload(sessionId, sha256));
    }

    public static void sendInput(ArcadeInputPayload payload) {
        if (net.minecraft.client.Minecraft.getInstance().getConnection() != null) {
            PacketDistributor.sendToServer(payload);
        }
    }
    public static void sendHomeInput(ArcadeHomeInputPayload payload){PacketDistributor.sendToServer(payload);}
    public static void homeReady(long id,int epoch){PacketDistributor.sendToServer(new ArcadeHomeReadyPayload(id,epoch));}
    public static void decideJoin(long sessionId,java.util.UUID applicantId,boolean accepted,java.util.UUID requestToken){
        PacketDistributor.sendToServer(new ArcadeJoinDecisionPayload(sessionId,applicantId,accepted,requestToken));
    }

    public static void requestReset(ArcadeResetPayload payload) {
        PacketDistributor.sendToServer(payload);
    }

    public static void sendDigest(ArcadeDigestPayload payload) {
        PacketDistributor.sendToServer(payload);
    }

    public static void sendScore(ArcadeScorePayload payload) {
        PacketDistributor.sendToServer(payload);
    }

    public static void uploadSnapshot(ArcadeSnapshotUploadPayload payload) {
        PacketDistributor.sendToServer(payload);
    }

    public static void setMultiplayerEnabled(long sessionId, boolean enabled) {
        PacketDistributor.sendToServer(new ArcadeMultiplayerResponsePayload(
                sessionId,
                enabled));
    }

    public static void decideJoin(
            long sessionId,
            java.util.UUID applicantId,
            boolean accepted
    ) {
        PacketDistributor.sendToServer(new ArcadeJoinDecisionPayload(
                sessionId,
                applicantId,
                accepted));
    }
}
