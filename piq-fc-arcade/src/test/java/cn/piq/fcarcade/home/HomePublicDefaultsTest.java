package cn.piq.fcarcade.home;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Pure migration policy plus source wiring: no server or client instance is started. */
class HomePublicDefaultsTest {
    @Test void onlyFcSuborAndSfcMissingSettingsSkipJoinApproval() {
        assertFalse(HomeEndpointBlockEntity.Defaults.joinApproval("piq_fc_arcade:nes", null));
        assertFalse(HomeEndpointBlockEntity.Defaults.joinApproval("piq_sfc_home:sfc", null));
        for (String other : new String[]{null, "", "other:sfc", "piq_sfc_home:other", "PIQ_SFC_HOME:sfc"})
            assertTrue(HomeEndpointBlockEntity.Defaults.joinApproval(other, null));
    }
    @Test void everyExplicitOldApprovalValueWinsIncludingFalseForOtherExtensions() {
        for (String system : new String[]{"piq_fc_arcade:nes", "piq_sfc_home:sfc", "other:console", ""})
            for (boolean explicit : new boolean[]{true, false})
                assertEquals(explicit, HomeEndpointBlockEntity.Defaults.joinApproval(system, explicit));
    }
    @Test void nbtAdapterDistinguishesMissingBooleanMalformedAndSerializesTheEffectiveValue() throws Exception {
        String endpoint = source("home/HomeEndpointBlockEntity");
        assertTrue(endpoint.contains("private Boolean joinApprovalRequired;"));
        assertTrue(endpoint.contains("this instanceof HomeConsoleBlockEntity"));
        assertTrue(endpoint.contains("this instanceof ExternalHomeConsoleBlockEntity external"));
        assertTrue(endpoint.contains("!tag.contains(\"HomeJoinApproval\") ? null"));
        assertTrue(endpoint.contains("!tag.contains(\"HomeJoinApproval\", net.minecraft.nbt.Tag.TAG_BYTE) || tag.getBoolean(\"HomeJoinApproval\")"));
        assertTrue(endpoint.contains("tag.putBoolean(\"HomeJoinApproval\", joinApprovalRequired())"));
        assertTrue(endpoint.contains("if (kind() == HomeLinkLedger.Kind.CONSOLE)"));
        assertTrue(source("home/ExternalHomeConsoleBlockEntity").contains("this.systemId = Objects.requireNonNull(systemId)"));
    }
    @Test void directJoinStillUsesPermissionLeaseAndSnapshotHandshake() throws Exception {
        String all = source("server/ServerArcadeSessions");
        String request = between(all, "private boolean requestHomeControl(", "private boolean homeSocketAvailable(");
        assertTrue(request.contains("if(!canTakeHome(p,s,port,gun))return false"));
        assertTrue(request.indexOf("if(!canTakeHome(p,s,port,gun))return false")
                < request.indexOf("return grantHome(p,s,port,gun)"));
        assertFalse(request.contains("joinApprovalRequired()"));
        String eligibility = between(all, "private boolean canTakeHome(", "private boolean grantHome(");
        assertTrue(eligibility.contains("if(!s.homeReady)return false"));
        assertTrue(eligibility.contains("!computeHost(s,p)&&!s.multiplayerEnabled"));
        assertTrue(eligibility.contains("HomeZapperService.permissionToControl(p,c,tv)"));
        assertTrue(eligibility.contains("HomeControllerService.canJoin(p,s.key.anchor(),s.id,port)"));
        String grant = between(all, "private boolean grantHome(", "private void decideHome(");
        assertTrue(grant.contains("if(!canTakeHome(p,s,port,gun)||p.connection.getConnection()!=expectedSource||!current(p))return false"));
        assertTrue(grant.contains("HomeControllerService.grant(p,s.key.anchor(),s.id,port)"));
        assertTrue(grant.contains("s.homeRuntime.take(p.getUUID(),expectedSource,lease,port)"));
        assertTrue(grant.contains("s.pendingControllerSync.add(p.getUUID());requestSessionSnapshot(p.getServer(),s)"));
        assertTrue(grant.contains("syncControllerState(p,s)"));
    }
    @Test void homeSlotDefaultsAndEditorUseHomeCountsWithoutResettingExistingChoices() throws Exception {
        String sessions = source("server/ServerArcadeSessions");
        String slots = between(sessions, "private void openHomeSaveSlots(", "private boolean validHomeSave(");
        assertTrue(slots.contains("slot,false,name,gun?2:library(p.getServer()).homeMaxPlayers(rom)"));
        assertTrue(slots.contains("found.players()"));
        String ui = source("client/ArcadeSaveSlotsScreen");
        assertTrue(ui.contains("players[index] = slot.players()"));
        assertTrue(ui.contains("players[index]=players[index]==1?2:1"));
        String editor = source("client/ClientCartridgeEditor");
        assertTrue(editor.contains("设置当前卡带的游戏人数。需 OP；同一游戏共用，下次开机生效。"));
        assertTrue(editor.contains("game.maxPlayers() == 2 ? 1 : 2"));
        String service = source("server/ServerCartridgeService");
        assertEquals(7, service.split("library\\.homeCatalog\\(\\)", -1).length - 1);
        assertTrue(service.contains("服务器内容权限已更新\", library.homeCatalog()"));
        assertFalse(service.contains("library.catalog()"));
        String library = source("server/ServerRomLibrary");
        assertTrue(library.contains("List<RomCatalogEntry> catalog() {\n        return catalog(false);"));
        assertTrue(library.contains("List<RomCatalogEntry> homeCatalog() {\n        return catalog(true);"));
    }
    @Test void newMachineSaveUsesEffectiveCountButCurrentAndCompatibleLegacyMetadataWin() throws Exception {
        String source = source("server/ServerArcadeSessions");
        String power = between(source, "public static boolean powerHomeConsole(", "public static void homeSaveAction(");
        assertTrue(power.contains("gun?2:m.library(player.getServer()).homeMaxPlayers(rom)"));
        String create = between(source, "private boolean createHome(", "private int homeMachineSavePlayers(");
        assertTrue(create.contains("if(mode==RomSaveMode.MACHINE&&resume)players=homeMachineSavePlayers("));
        assertTrue(create.contains("gun?2:library(p.getServer()).homeMaxPlayers(rom)"));
        String metadata = between(source, "private int homeMachineSavePlayers(", "private void openHomeSaveSlots(");
        assertTrue(metadata.contains("saveKey.equals(existing.saveKey())&&rom.equals(existing.romSha256())"));
        assertTrue(metadata.contains("legacy.equals(existing.saveKey())&&rom.equals(existing.romSha256())"));
        assertEquals(2, metadata.split("return existing.players\\(\\)", -1).length - 1);
        assertTrue(metadata.contains("return fallback;"));
        assertFalse(metadata.contains(".save("));
        assertFalse(metadata.contains(".delete("));
    }
    private static String source(String relative) throws Exception {
        return Files.readString(Path.of("src/main/java/cn/piq/fcarcade/" + relative + ".java")).replace("\r\n", "\n");
    }
    private static String between(String source, String start, String end) {
        int from = source.indexOf(start), to = source.indexOf(end, from + start.length());
        assertTrue(from >= 0 && to > from, start + " / " + end);
        return source.substring(from, to);
    }
}
