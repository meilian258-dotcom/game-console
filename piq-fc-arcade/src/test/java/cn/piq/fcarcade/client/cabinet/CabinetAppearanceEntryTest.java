package cn.piq.fcarcade.client.cabinet;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** First-level routing supplements, but does not replace, server permission checks. */
class CabinetAppearanceEntryTest {
    private String source(String relative)throws Exception{
        return Files.readString(Path.of("src/main/java/cn/piq/fcarcade",relative+".java"));
    }
    @Test void appearanceIsOnTheFirstMenuAndDoesNotNeedAnySelectedBackend()throws Exception{
        var s=source("client/cabinet/CabinetMenuScreen");
        assertTrue(s.contains("DeviceUi.button(font,menu.target().dual()?\"专属模型外观\":\"机柜外观\""));
        assertTrue(s.contains("this::openAppearance"));
        String action=s.substring(s.indexOf("private void openAppearance()"),s.indexOf("@Override public void onClose()"));
        assertTrue(action.contains("FcNetwork.requestSkinLibrary(anchor)"));
        assertTrue(action.indexOf("onClose();")<action.indexOf("FcNetwork.requestSkinLibrary(anchor)"));
        for(String forbidden:List.of("focusedEntry(","unavailable(","configure(","Choose(","setCabinetBackend(","Release(","setSkin("))
            assertFalse(action.contains(forbidden),forbidden);
    }
    @Test void newDualAppearanceIsDisabledBeforeAnyRequestWhileSingleEntryRemains()throws Exception{
        var s=source("client/cabinet/CabinetMenuScreen");
        assertTrue(s.contains("!menu.target().dual()&&!sent&&minecraft.player!=null&&minecraft.player.hasPermissions(2)"));
        assertTrue(s.contains("此机型仅支持配套外观；旧皮肤已保留。"));
        String action=s.substring(s.indexOf("private void openAppearance()"),s.indexOf("@Override public void onClose()"));
        int dualGuard=action.indexOf("if(menu.target().dual())return;");
        assertTrue(dualGuard>=0&&dualGuard<action.indexOf("FcNetwork.requestSkinLibrary(anchor)"));
        var library=source("client/RomLibraryScreen");
        assertTrue(library.contains("instanceof cn.piq.fcarcade.world.DualCabinetBlockEntity"));
        assertTrue(library.contains("button(dedicatedAppearance?\"配套外观\":\"外观\""));
        assertTrue(library.contains("!dedicatedAppearance&&has(PlayerContentPolicy.ADMIN),DeviceUi.Tone.QUIET"));
        assertTrue(library.contains("FcNetwork.requestSkinLibrary(blockPos)"));
    }
    @Test void staleWrongWorldAndNonAdminClicksDoNotIssueSkinRequests()throws Exception{
        var s=source("client/cabinet/CabinetMenuScreen");
        String action=s.substring(s.indexOf("private void openAppearance()"),s.indexOf("@Override public void onClose()"));
        for(String guard:List.of("sent||age>=600","minecraft.screen!=this","minecraft.getConnection()!=connection",
                "!menu.target().matches(minecraft.level)","!minecraft.player.isAlive()","minecraft.player.isSpectator()",
                "!minecraft.player.hasPermissions(2)","distanceToSqr(",">64"))assertTrue(action.contains(guard),guard);
        assertTrue(action.contains("var anchor=menu.target().anchor()"));
        assertTrue(s.contains("onClose(){CabinetClientBackends.cancelConfigure();super.onClose();}"));
        assertTrue(s.contains("Math.min(3,Math.max(0,(b.primary().y()-4-(d.y()+52))/10))"));
        assertTrue(s.contains("if(lines>0)CabinetUi.paragraph("));
    }
    @Test void reusedSkinWorkflowStillChecksServerPermissionDistanceChunkAndFullDualStructure()throws Exception{
        String network=source("FcNetwork"),server=source("server/ServerSkinService"),client=source("client/ClientArcadeEvents");
        assertTrue(network.contains("ServerSkinService\n                                        .openLibrary(player, payload.blockPos())")
                ||network.contains("ServerSkinService\r\n                                        .openLibrary(player, payload.blockPos())"));
        assertTrue(client.contains("ClientSkinManager.openLibrary(payload)"));
        for(String guard:List.of("if (!player.hasPermissions(2))","hasChunkAt(pos)","mayInteract(player, pos)",
                "MAX_DISTANCE_SQUARED","DualCabinetStructure.complete(player.serverLevel(), pos)","validMachine(player, payload.blockPos())"))
            assertTrue(server.contains(guard),guard);
        assertFalse(server.contains("setCabinetBackend("));
    }
}
