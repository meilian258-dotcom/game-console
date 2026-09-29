package cn.piq.fcarcade;

import cn.piq.fcarcade.client.ClientArcadeEvents;

final class ClientArcadeSupport {
    static boolean acceptsConnection(net.minecraft.network.Connection source){var connection=net.minecraft.client.Minecraft.getInstance().getConnection();return connection!=null&&connection.getConnection()==source&&source.isConnected();}
    private ClientArcadeSupport() {
    }

    static void openLibrary(ArcadeLibraryPayload payload) {
        ClientArcadeEvents.openLibrary(payload);
    }

    static void openSkinLibrary(SkinLibraryPayload payload) {
        ClientArcadeEvents.openSkinLibrary(payload);
    }

    static void startSkinDownload(SkinDownloadStartPayload payload) {
        ClientArcadeEvents.startSkinDownload(payload);
    }

    static void acceptSkinDownload(SkinDownloadChunkPayload payload) {
        ClientArcadeEvents.acceptSkinDownload(payload);
    }

    static void openSettings(ArcadeSettingsPayload payload) {
        ClientArcadeEvents.openSettings(payload);
    }

    static void openLeaderboardPanelConfig(
            LeaderboardPanelConfigPayload payload
    ) {
        ClientArcadeEvents.openLeaderboardPanelConfig(payload);
    }

    static void openSaveCatalog(ArcadeSaveCatalogPayload payload) {
        ClientArcadeEvents.openSaveCatalog(payload);
    }

    static void openSaveSlots(ArcadeSaveSlotsPayload payload) {
        ClientArcadeEvents.openSaveSlots(payload);
    }
    static void openHomeSaveSlots(ArcadeHomeSaveSlotsPayload payload){ClientArcadeEvents.openHomeSaveSlots(payload);}

    static void requestJoin(ArcadeJoinPromptPayload payload) {
        ClientArcadeEvents.requestJoin(payload);
    }

    static void promptExit(ArcadeExitPromptPayload payload) {
        ClientArcadeEvents.promptExit(payload);
    }

    static void promptResume(ArcadeResumePromptPayload payload) {
        ClientArcadeEvents.promptResume(payload);
    }

    static void offerMultiplayer(ArcadeMultiplayerOfferPayload payload) {
        ClientArcadeEvents.offerMultiplayer(payload);
    }

    static void requestJoinApproval(ArcadeJoinApprovalPayload payload) {
        ClientArcadeEvents.requestJoinApproval(payload);
    }

    static void updateSession(ArcadeSessionPayload payload) {
        ClientArcadeEvents.applySession(payload);
    }

    static void advanceFrame(ArcadeFramePayload payload) {
        ClientArcadeEvents.advanceFrame(payload);
    }

    static void applyHistory(ArcadeHistoryPayload payload) {
        ClientArcadeEvents.applyHistory(payload);
    }

    static void requestSnapshot(ArcadeSnapshotRequestPayload payload) {
        ClientArcadeEvents.requestSnapshot(payload);
    }

    static void applySnapshot(ArcadeSnapshotPayload payload) {
        ClientArcadeEvents.applySnapshot(payload);
    }

    static void applyPersistentState(ArcadePersistentStatePayload payload) {
        ClientArcadeEvents.applyPersistentState(payload);
    }

    static void startRomDownload(RomDownloadStartPayload payload) {
        ClientArcadeEvents.startRomDownload(payload);
    }

    static void acceptRomDownload(RomDownloadChunkPayload payload) {
        ClientArcadeEvents.acceptRomDownload(payload);
    }
}
