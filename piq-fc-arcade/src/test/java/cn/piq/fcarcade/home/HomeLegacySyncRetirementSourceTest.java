package cn.piq.fcarcade.home;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Wiring supplements pure policy tests; not a Minecraft launch acceptance test. */
class HomeLegacySyncRetirementSourceTest {
    private String source(String path)throws Exception {
        return Files.readString(Path.of("src/main/java/cn/piq/fcarcade/"+path+".java"));
    }
    private String part(String source,String start,String end) {
        int first=source.indexOf(start);assertTrue(first>=0,start);
        int last=source.indexOf(end,first);assertTrue(last>first,end);
        return source.substring(first,last);
    }
    @Test void powerPrepareAndFinalCreationRejectOldHomeModeBeforeStorageOrRuntime()throws Exception {
        String server=source("server/ServerArcadeSessions");
        String power=part(server,"public static boolean powerHomeConsole(","public static int personalRetentionDays(");
        assertTrue(power.indexOf("HomeSyncPolicy.publicRunnable(console.synchronizationMode(),console.netplayExperimental())")
                <power.indexOf("if(!storageReady(player)"));
        assertTrue(power.contains("HomeSyncPolicy.legacyUnavailable()"));
        String prepare=part(server,"private void prepareHome(","private boolean createHome(");
        int first=prepare.indexOf("HomeSyncPolicy.publicRunnable(c.synchronizationMode(),c.netplayExperimental())");
        assertTrue(first>=0&&first<prepare.indexOf("HomeLaunchServer.start("));
        assertTrue(prepare.indexOf("HomeSyncPolicy.publicRunnable(c.synchronizationMode(),c.netplayExperimental())",first+1)>prepare.indexOf("public boolean valid()"));
        String create=server.substring(server.indexOf("private boolean createHome("));
        int finalGate=create.indexOf("HomeSyncPolicy.publicRunnable(c.synchronizationMode(),c.netplayExperimental())");
        assertTrue(finalGate>=0&&finalGate<create.indexOf("saves(p.getServer()).loadReadOnly"));
        assertTrue(finalGate<create.indexOf("new Session("));
    }
    @Test void settingsRejectLegacyRequestWithoutChangingPersistenceAndKeepAnExplanationRow()throws Exception {
        String settings=source("home/HomeSyncSettings");
        int deny=settings.indexOf("else if (request.mode() == 1) reason = HomeSyncPolicy.legacyUnavailable()");
        assertTrue(deny>settings.indexOf("else if (busy)"));
        assertTrue(deny<settings.indexOf("if(request.mode()>=0){"));
        assertTrue(settings.contains("if(selected==1)return HomeSyncPolicy.legacyUnavailable()"));
        String screen=source("client/HomeSyncSettingsScreen");
        assertTrue(screen.contains("!HomeSyncMenuPolicy.showMode(mode,external,privateOnly,setting.supported())&&setting.mode()!=mode"));
        assertTrue(screen.contains("HomeSyncMenuPolicy.supported(setting.supported(),mode)"));
        assertTrue(screen.contains("else if(mode==1)label+=\"（已退役）\""));
        assertTrue(screen.contains("PrivateHomeClient.open()"),"Private mode keeps its separate client-only entry");
        String privateClient=source("client/PrivateHomeClient");
        assertFalse(privateClient.contains("HomeSyncPolicy.publicRunnable"));
        assertFalse(privateClient.contains("powerHomeConsole("));
    }
    @Test void placementResolutionNeverRunsForLoadedNbtAndKeepsExistingJniDefaultFirst()throws Exception {
        String endpoint=source("home/HomeEndpointBlockEntity");
        String load=part(endpoint,"protected void loadAdditional(","protected void saveAdditional(");
        assertTrue(load.contains("adminDefaultsInitialized = true"));
        assertTrue(load.contains("HomeSyncPolicy.persisted(tag.getString(\"HomeSyncMode\"))"));
        assertFalse(load.contains("HomeSyncSettings.placementMode("));
        String placement=part(source("home/HomeSyncSettings"),"static CabinetSyncMode placementMode(","/** OP command convenience");
        assertTrue(placement.indexOf("GameConsoleAdminPolicy.defaultHomeJni(desired,modes)")
                <placement.indexOf("return HomeSyncPolicy.placementMode(desired,modes,fallback)"));
    }
}
