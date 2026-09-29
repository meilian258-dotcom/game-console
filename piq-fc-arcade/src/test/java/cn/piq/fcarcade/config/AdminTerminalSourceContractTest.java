package cn.piq.fcarcade.config;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Source guards supplement pure policy tests; they are not a Minecraft permission integration test. */
class AdminTerminalSourceContractTest {
    private static String source(String path) throws Exception {
        return Files.readString(Path.of("src/main/java/cn/piq/fcarcade/" + path + ".java"));
    }
    @Test void serverAuthorityUsesOpAndCurrentHeldTerminalNotCreative() throws Exception {
        String s = source("config/AdminTerminalService");
        assertTrue(s.contains("p.hasPermissions(2)"));
        assertTrue(s.contains("p.getItemInHand(hand).is(ModItems.ADMIN_TERMINAL.get())"));
        assertTrue(s.contains("server.getPlayerList().getPlayer(p.getUUID())==p"));
        assertTrue(s.contains("server.isSameThread()"));
        assertFalse(s.contains("isCreative()"));
        assertFalse(s.contains("instabuild"));
    }
    @Test void packetsDoNotTrustLateConnectionsOrOpenScreenAsAuthority() throws Exception {
        String s = source("config/AdminTerminalNetwork");
        assertTrue(s.contains("player.connection.getConnection()==source"));
        assertTrue(s.contains("current.accepts(source)"));
        assertTrue(s.contains("admin-terminal-2"));
        String client = source("client/AdminTerminalScreen");
        assertTrue(client.contains("value.nonce().equals(nonce)"));
        assertTrue(client.contains("getConnection().getConnection()==connection"));
        assertTrue(client.contains("getItemInHand(hand).is(ModItems.ADMIN_TERMINAL.get())"));
        assertTrue(client.contains("extends DeviceScreen"));
    }
    @Test void persistenceKeepsAllowedPlayersAndUsesCompareAndSet() throws Exception {
        String s = source("config/AdminTerminalService");
        assertTrue(s.contains("AdminTerminalPolicy.same("));
        assertTrue(s.contains("PlayerContentAccess.updateOptions(player,before,options(request.value()))"));
        assertFalse(s.contains("setAllowedPlayers"));
        assertTrue(s.contains("GameConsoleAdminSettings.setDefaults"));
        assertFalse(s.contains("setBlock"));
    }
    @Test void terminalIsRegisteredButDoesNotGrantAuthorityViaRecipe() throws Exception {
        assertTrue(source("registry/ModItems").contains("ITEMS.register(\"admin_terminal\""));
        assertTrue(source("registry/CreativeTabCatalog").contains("\"admin_terminal\""));
        assertTrue(source("FcArcadeMod").contains("event.accept(ModItems.ADMIN_TERMINAL.get())"));
        assertTrue(Files.isRegularFile(Path.of("src/main/resources/assets/piq_fc_arcade/models/item/admin_terminal.json")));
    }
    @Test void deviceMenuUsesExplicitChatCommandsNotAnUnacceptedScreenPacket() throws Exception {
        String service=source("config/AdminTerminalService"),client=source("client/AdminTerminalScreen");
        assertTrue(service.contains("player.createCommandSourceStack(),\"gameconsole settings\""));
        assertFalse(service.contains("HomeSyncSettings.adminCommand"));
        assertFalse(service.contains("CabinetSyncSettings.adminCommand"));
        assertFalse(service.contains("已打开目标设备设置"));
        assertTrue(client.contains("单机设置 / 指令菜单"));
    }
}
