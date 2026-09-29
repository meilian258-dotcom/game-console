package cn.piq.fcarcade.server;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SharedCabinetOccupancyWiringTest {
    private static String source(String path) throws Exception {
        return Files.readString(Path.of("src/main/java/cn/piq/fcarcade/" + path + ".java"));
    }

    @Test void sharedDisplayMaintenanceDoesNotDependOnFcStorageOrAClientsParticipation() throws Exception {
        String display = source("server/ArcadeOccupancyDisplay");
        assertTrue(display.contains("addListener(ArcadeOccupancyDisplay::onServerTick)"));
        assertTrue(display.contains("getTickCount() % 20 == 0"));
        assertTrue(display.contains("ServerCabinets.occupancy(server).entrySet()"));
        assertTrue(display.contains("level.hasChunkAt(anchor)"));
        assertFalse(display.contains("initializeStorage"));
        assertFalse(display.contains("PacketDistributor"));
        assertFalse(display.contains("displayClientMessage"));
        assertTrue(display.contains("level.addFreshEntity(display)")); // Vanilla tracking includes bystanders.
    }

    @Test void roomsAndLocalLeasesUseValidatedPlayerNamesAndOnePrimaryLabel() throws Exception {
        String rooms = source("cabinet/CabinetRooms");
        String method = rooms.substring(rooms.indexOf("static Map<CabinetTarget,String> occupancy("), rooms.indexOf("static boolean contains("));
        assertTrue(method.contains("state.ledger.occupantNames(room"));
        assertTrue(method.contains("validateLease(player,member.id,backend)"));
        assertTrue(method.contains("player.getGameProfile().getName()"));
        assertTrue(method.contains("result.put(room.target,names)"));
        assertFalse(method.contains("result.put(secondary,names)"));
        assertFalse(method.contains("room.ready")); // Starting/uploading still occupies real seats.
        assertFalse(method.contains("room.mode")); // All transports share the same authority.
        assertTrue(source("cabinet/ServerCabinets").contains("validateLease(player,lease.id(),ResourceLocation.parse(lease.backend()))"));
    }

    @Test void persistedOccupancyIsCleanedAndLeaderboardCannotEraseALiveAddonLabel() throws Exception {
        String display = source("server/ArcadeOccupancyDisplay");
        assertTrue(display.contains("display.addTag(OCCUPANCY_TAG)"));
        assertTrue(display.contains("display.removeTag(OCCUPANCY_TAG)"));
        assertTrue(display.contains("tracked.discardIf"));
        assertTrue(display.contains("!activeOwners.contains(owner)"));
        assertTrue(display.contains("!ServerArcadeSessions.hasCabinetSession"));
        String sessions = source("server/ServerArcadeSessions");
        String method = sessions.substring(sessions.indexOf("private void refreshIdleScoreDisplay("), sessions.indexOf("private void reconcileViewers("));
        assertTrue(method.indexOf("ServerCabinets.isCabinetBusy") < method.indexOf("ServerCabinets.blocksNes"));
    }

    @Test void occupancyAndLeaderboardUseDeviceFacingAndIdleFcDoesNotInventAUser() throws Exception {
        String display = source("server/ArcadeOccupancyDisplay");
        assertTrue(display.contains("facing, 0.0F, \"fixed\""));
        assertFalse(display.contains("facing, 0.0F, \"center\""));
        assertTrue(display.contains("tag.put(\"Rotation\", floatList(facing.toYRot(), 0.0F))"));
        assertTrue(display.contains("\"fixed\""));
        assertFalse(display.contains("? \"玩家\""));
        assertTrue(display.contains("if (playerNames == null || playerNames.isBlank())"));
        String sessions = source("server/ServerArcadeSessions");
        String names = sessions.substring(sessions.indexOf("private static String playerNames("), sessions.indexOf("private static void broadcastFrame("));
        assertTrue(names.contains("ArcadeRole.SPECTATOR"));
        assertTrue(names.contains("controller.connection() != player.connection.getConnection()"));
    }

    @Test void homePreferenceOnlyRemovesThatOwnerBeforeAnOccupancyLabelCanSpawn() throws Exception {
        String display = source("server/ArcadeOccupancyDisplay");
        String refresh = display.substring(display.indexOf("static void refresh("), display.indexOf("static void refreshLeaderboard("));
        int hidden = refresh.indexOf("!cn.piq.fcarcade.home.HomePresentationSettings.occupancyVisible(level, structure.anchor())");
        assertTrue(hidden > refresh.indexOf("level.hasChunkAt(anchor)"));
        int remove = refresh.indexOf("remove(server, dimension, structure.anchor());", hidden);
        assertTrue(remove > hidden && remove < refresh.indexOf("spawn(level"));
        assertTrue(refresh.substring(remove, refresh.indexOf("Vec3 position", remove)).contains("return;"));
        String leaderboard = display.substring(display.indexOf("static void refreshLeaderboard("), display.indexOf("static void remove("));
        assertFalse(leaderboard.contains("occupancyVisible"));
    }

    @Test void deletingAnOldLeaderboardRomCannotRemoveAnOccupiedAddonLabel() throws Exception {
        String sessions = source("server/ServerArcadeSessions");
        String stale = sessions.substring(sessions.indexOf("for (SessionKey stale : new HashSet<>(idleLeaderboardDisplays))"),
                sessions.indexOf("private void rotateIdleScoreDisplays("));
        int guard = stale.indexOf("if (occupied != null && ServerCabinets.isCabinetBusy(server, occupied)) continue;");
        assertTrue(guard > stale.indexOf("idleLeaderboardDisplays.remove(stale)"));
        assertTrue(guard < stale.indexOf("ArcadeOccupancyDisplay.remove("));
    }
}
