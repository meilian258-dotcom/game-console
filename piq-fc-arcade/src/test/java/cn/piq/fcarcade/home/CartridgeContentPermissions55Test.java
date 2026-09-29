package cn.piq.fcarcade.home;

import cn.piq.fcarcade.access.PlayerContentPolicy;
import cn.piq.fcarcade.rom.RomCatalogEntry;
import cn.piq.fcarcade.rom.RomSaveMode;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Actual wire round trips plus source guards; not a Minecraft/claim-mod integration test. */
class CartridgeContentPermissions55Test {
    private String source(String path) throws Exception {
        return Files.readString(Path.of("src/main/java/cn/piq/fcarcade/" + path + ".java"));
    }
    private String method(String source, String signature) {
        int start = source.indexOf(signature), brace = source.indexOf('{', start), depth = 1, end = brace + 1;
        assertTrue(start >= 0, signature);
        while (depth > 0) { char c = source.charAt(end++); if (c == '{') depth++; else if (c == '}') depth--; }
        return source.substring(start, end);
    }
    @Test void browseOnlyGranteeMayEditOwnCardWithoutReceivingEitherUploadCapability() {
        UUID player = UUID.randomUUID();
        int caps = new PlayerContentPolicy(false, false, false, Set.of(player)).capabilities(player, false);
        assertEquals(PlayerContentPolicy.BROWSE|PlayerContentPolicy.SERVER_ROM_USE, caps);
        var computer = new CartridgeComputerBinding(UUID.randomUUID(), "overworld", 1, 2, 3);
        assertTrue(computer.permits(computer.computerId(), "overworld", true, true, true,
                PlayerContentPolicy.has(caps, PlayerContentPolicy.BROWSE), true, 25));
        assertFalse(computer.permits(computer.computerId(), "overworld", true, true, true, false, true, 0));
        assertFalse(computer.permits(computer.computerId(), "overworld", true, true, true, true, false, 0));
        assertFalse(computer.permits(computer.computerId(), "overworld", true, true, true, true, true, 25.01));
    }
    @Test void actualReplyWirePreservesEveryCapabilityAndCatalogSaveMetadata() {
        var target = new CartridgeEditBinding(UUID.randomUUID(), UUID.randomUUID(), 0, 2);
        var catalog = List.of(new RomCatalogEntry("fixture.nes", "a".repeat(64), 16400, 0, 2, RomSaveMode.PLAYER));
        for (int caps = 0; caps <= PlayerContentPolicy.ALL; caps++) {
            var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
            try {
                var reply = new CartridgeNetwork.Reply(CartridgeNetwork.OPEN, target, "", "a".repeat(64), "",
                        "fixture", "server authority", 0, 0, new byte[0], catalog, caps);
                CartridgeNetwork.Reply.CODEC.encode(buffer, reply);
                var decoded = CartridgeNetwork.Reply.CODEC.decode(buffer);
                assertEquals(caps, decoded.capabilities()); assertEquals(target, decoded.target());
                assertEquals(catalog, decoded.catalog()); assertEquals(reply.romSha(), decoded.romSha());
                assertEquals(reply.title(), decoded.title()); assertEquals(0, buffer.readableBytes());
            } finally { buffer.release(); }
        }
    }
    @Test void legacyReplyIsDeniedAndUnknownCapabilityBitsAreRejected() {
        var reply = new CartridgeNetwork.Reply(CartridgeNetwork.STATUS, CartridgeNetwork.NO_TARGET,
                "", "", "", "", "", 0, 0, new byte[0], List.of());
        assertEquals(0, reply.capabilities());
        for (int invalid : new int[] {-1, 64, Integer.MAX_VALUE})
            assertThrows(IllegalArgumentException.class, () -> new CartridgeNetwork.Reply(CartridgeNetwork.STATUS,
                    CartridgeNetwork.NO_TARGET, "", "", "", "", "", 0, 0, new byte[0], List.of(), invalid));
    }
    @Test void bothIdentityGatesUseBrowseAndGlobalMutationsRecheckOpAfterProtectionCallbacks() throws Exception {
        String service = source("server/ServerCartridgeService");
        for (String signature : List.of("private static boolean computerFacts(", "boolean cardMatches(")) {
            String gate = method(service, signature);
            assertTrue(gate.contains("PlayerContentAccess.canBrowse(player)"));
            assertFalse(gate.contains("player.hasPermissions(2)"));
        }
        for (String signature : List.of("void setPlayers(")) {
            String setter = method(service, signature);
            int first = setter.indexOf("player.hasPermissions(2)"), validation = setter.indexOf("valid(player,"), last = setter.lastIndexOf("player.hasPermissions(2)");
            assertTrue(first >= 0 && first < validation && validation < last, signature);
            assertTrue(last < setter.indexOf("library.set"), signature);
        }
        String save=method(service,"void setSaveMode(");
        assertFalse(save.contains("hasPermissions(2)"));
        assertTrue(save.indexOf("PlayerContentAccess.canBrowse(player)")<save.indexOf("valid(player,"));
        assertTrue(save.lastIndexOf("PlayerContentAccess.canBrowse(player)")>save.indexOf("valid(player,"));
        assertTrue(save.lastIndexOf("PlayerContentAccess.canBrowse(player)")<save.indexOf("FcCartridgeData.setSaveMode("));
        assertFalse(save.contains("library.setSaveMode"));
        String send = method(service, "void send(");
        assertTrue(send.contains("session.capabilities = PlayerContentAccess.capabilities(player)"));
        assertTrue(send.contains("catalog, session.capabilities"));
    }
    @Test void everyUploadStageUsesKindSpecificPermissionsBeforeAcceptingBytesOrWritingCard() throws Exception {
        String service = source("server/ServerCartridgeService");
        String start = method(service, "void start("), finish = method(service, "void finish("), handle = method(service, "public static void handle(");
        assertTrue(start.indexOf("requireUpload(player, cover)") < start.indexOf("BUDGET.reserve("));
        assertTrue(start.indexOf("requireUpload(player, cover)") < start.indexOf("library.find(request.hash())"));
        assertTrue(handle.indexOf("state.requireUpload(player, session.upload.cover)") < handle.indexOf("session.upload.buffer.append("));
        assertTrue(finish.indexOf("requireUpload(player, upload.cover)") < finish.indexOf("upload.buffer.finish()"));
        assertTrue(finish.contains("upload.revoked || !uploadAllowed(current, upload.cover)"));
        assertTrue(finish.contains("PlayerContentPolicy.COVER_UPLOAD")); assertTrue(finish.contains("PlayerContentPolicy.ROM_UPLOAD"));
        String commit = method(service, "void commit(");
        int recheck = commit.indexOf("PlayerContentAccess.capabilities(player)");
        assertTrue(recheck > commit.indexOf("valid(player, session, session.target)"));
        assertTrue(recheck < commit.indexOf("FcCartridgeData.write("));
        String allowed = method(service, "boolean uploadAllowed(");
        assertTrue(allowed.contains("cover ? PlayerContentAccess.canUploadCover(player) : PlayerContentAccess.canUploadRom(player)"));
    }
    @Test void revocationIsStickyAndDoesNotReleaseIoOwnedBudgetOrDeleteExistingFiles() throws Exception {
        String service = source("server/ServerCartridgeService"), tick = method(service, "void tick()");
        assertTrue(tick.contains("!session.upload.revoked && !uploadAllowed(player, session.upload.cover)"));
        assertTrue(tick.indexOf("session.upload.revoked = true") < tick.indexOf("abortUpload(session)"));
        assertTrue(tick.contains("session.capabilities != PlayerContentAccess.capabilities(player)"));
        assertFalse(service.contains("revoked = false"));
        String abort = method(service, "void abortUpload(");
        assertTrue(abort.indexOf("session.processing") < abort.indexOf("BUDGET.release("));
        assertTrue(method(service, "void finish(").contains("finally { BUDGET.release(upload.reservation); }"));
        assertFalse(service.contains("Files.delete")); assertFalse(service.contains("library.remove("));
    }
    @Test void ordinaryServerSelectionDoesNotNeedRomUploadAndClientCannotExposeGlobalSettingsToGrantees() throws Exception {
        String client = source("client/ClientCartridgeEditor"), select = method(client, "private void select(");
        assertTrue(select.indexOf("if (entry.server) { if(has(PlayerContentPolicy.SERVER_ROM_USE))write(entry.hash, coverSha); return; }") < select.indexOf("!has(PlayerContentPolicy.ROM_UPLOAD)"));
        assertTrue(method(client, "private void uploadCover(").contains("!has(PlayerContentPolicy.COVER_UPLOAD)"));
        assertTrue(method(client, "private boolean saveModeEditable(").contains("has(PlayerContentPolicy.BROWSE)"));
        assertTrue(method(client, "private void togglePlayers(").contains("!has(PlayerContentPolicy.ADMIN)"));
        assertTrue(client.contains("has(selected.server?PlayerContentPolicy.SERVER_ROM_USE:PlayerContentPolicy.ROM_UPLOAD)"));
        assertTrue(client.contains("editor.updateCapabilities(reply.capabilities())"));
        assertTrue(client.contains("仅本地")); assertTrue(client.contains("服务器游戏库 · "));assertTrue(client.contains("has(PlayerContentPolicy.SERVER_ROM_USE)?\"可直接写卡\":\"使用未授权\""));
    }
}
