package cn.piq.fcarcade.home;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Integration guardrails supplement the pure bounded-settings tests. */
class TvRemoteSecuritySourceTest {
    private static String source(String name) throws Exception {
        return Files.readString(Path.of("src/main/java/cn/piq/fcarcade/home/" + name + ".java"));
    }
    @Test void rateLimitPrecedesExpensivePermissionChecks() throws Exception {
        String code=source("TvRemoteService");
        String request=code.substring(code.indexOf("static void request("),code.indexOf("private static boolean current("));
        assertTrue(request.indexOf("now - intent.lastRequest < 3") < request.indexOf("!permitted(player,"));
        assertTrue(request.indexOf("now - intent.lastRequest < 3") < request.indexOf("!consolePermitted(player,"));
        assertTrue(request.contains("player.connection.getConnection() != intent.connection"));
    }
    @Test void intentIsReleasedOnLogoutStopAndExpiry() throws Exception {
        String code=source("TvRemoteService");
        assertTrue(code.contains("PlayerEvent.PlayerLoggedOutEvent"));
        assertTrue(code.contains("INTENTS.remove(player)"));
        assertTrue(code.contains("ServerStoppedEvent"));
        assertTrue(code.contains("entry.getValue().target.level().getServer() == event.getServer()"));
        assertTrue(code.contains("entry.getValue().expires < event.getServer().getTickCount()"));
    }
    @Test void requestsRetainPhysicalIdentityAndFailClosedRevalidation() throws Exception {
        String code=source("TvRemoteService");
        assertTrue(code.contains("current.tv() == expected.tv()"));
        assertTrue(code.contains("current.identity().equals(expected.identity())"));
        assertTrue(code.contains("player.getItemInHand(hand) == held"));
        assertTrue(code.contains("java.util.Objects.equals(i.link, i.target.tv().linkId())"));
        assertTrue(code.contains("packet.revision() != intent.revision"));
        assertTrue(code.contains("!consolePermitted(player, intent) || !current(player, intent)"));
        assertTrue(code.contains("player.hasPermissions(2)"));
    }
    @Test void remoteDoesNotBecomeASaveOrRomEditor() throws Exception {
        String code=source("TvRemoteService");
        for(String forbidden:new String[]{"ServerCartridgeService", "homeSaveAction(", "loadSnapshot(", "selectedRom("})
            assertFalse(code.contains(forbidden));
    }
    @Test void remoteCannotMutateLegacyConsolePresentationActions() throws Exception {
        String code=source("TvRemoteService");
        String request=code.substring(code.indexOf("static void request("),code.indexOf("private static boolean current("));
        assertTrue(request.contains("!TvRemoteSettingsPolicy.mayApply("));
        assertFalse(request.contains("occupancyVisible("));
        assertFalse(request.contains("joinApprovalRequired("));
        assertTrue(request.contains("主机高级设置请使用调试螺丝刀"));
    }
    @Test void remoteScreenOnlyExposesTvSettingsAndPersonalInput() throws Exception {
        String code=Files.readString(Path.of("src/main/java/cn/piq/fcarcade/client/TvRemoteSettingsScreen.java"));
        assertFalse(code.contains("TvRemoteSettingsPolicy.OCCUPANCY"));
        assertFalse(code.contains("TvRemoteSettingsPolicy.APPROVAL"));
        assertTrue(code.contains("ControlSettingsScreen(this)"));
        assertTrue(code.contains("遥控器 · 电视设置"));
    }
}
