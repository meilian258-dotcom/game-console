package cn.piq.fcarcade.client;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Source wiring checks, not a claim that Minecraft UI rendering was exercised. */
class DeviceDebugClientSourceTest {
    private static String source(String name) throws Exception {
        return Files.readString(Path.of("src/main/java/cn/piq/fcarcade/client/"+name+".java"));
    }
    @Test void homeSettingsUseAcknowledgedRevisionsAndExposeRealSupport() throws Exception {
        String code=source("HomeSyncSettingsScreen");
        assertTrue(code.contains("HomeSyncNetwork.request(setting.token(),setting.revision(),mode,occupancy,approval)"));
        assertTrue(code.contains("if(value.revision()<setting.revision())return"));
        assertTrue(code.contains("ready()&&setting.editable()&&setting.occupancySupported()"));
        assertTrue(code.contains("pending=false;timedOut=true"));
        assertTrue(code.contains("!value.debugTool()&&mc.screen instanceof ChatScreen"));
        assertTrue(code.contains("setting.console().getX()"));
        assertTrue(code.contains("setting.console().getY()"));
        assertTrue(code.contains("setting.console().getZ()"));
        assertTrue(code.contains("设备设置 · "));
        assertTrue(code.contains("已借手柄无需归还"));
        assertFalse(code.contains("归还控制器后调整"));
        assertFalse(code.contains("HomeControllerReturn"));
        assertFalse(code.contains("homeSaveAction("));
    }
    @Test void cabinetDebugReplyIsExplicitAndCannotLaunchOrReplaceAnotherDialog() throws Exception {
        String code=source("cabinet/CabinetClientBackends");
        String method=code.substring(code.indexOf("@Override public void openMenu("),code.indexOf("@Override public void openBackend("));
        String debug=method.substring(method.indexOf("if(menu.debugTool())"),method.indexOf("if(mc.screen==null||mc.screen instanceof CabinetMenuScreen)"));
        assertTrue(debug.contains("if(mc.screen!=null||mc.getConnection()==null"));
        assertTrue(debug.contains("new CabinetSyncSettingsScreen(null,menu.target(),menu.selected(),menu.token())"));
        assertFalse(debug.contains("entries().isEmpty()"));
        assertFalse(debug.contains("configure("));
        assertFalse(debug.contains("openBackend("));
        assertFalse(debug.contains("startGame("));
        assertTrue(method.contains("new CabinetMenuScreen(menu)"),"Legacy backend-menu route remains intact");
    }
    @Test void privatePrototypeLinksToRealLocalSettingsWithoutPretendingToEnableNetwork() throws Exception {
        String code=source("HomeSyncSettingsScreen");
        assertTrue(code.contains("if(privateOnly&&selected==4){if(ready()&&current())openLocalSettings();}"));
        assertTrue(code.contains("私人单人 · 本机设置…"));
        assertTrue(code.contains("（尚未接入）"));
        assertTrue(code.contains("不是权限不足"));
        assertTrue(code.contains("new HomeRuntimeSettingsScreen(this,deviceSystem(),setting.system())"));
        assertTrue(code.contains("mode>=0&&(setting.supported()&(1<<mode))==0"),"Server capability guard retained");
    }
    @Test void cabinetHeaderIdentifiesPhysicalTargetWithoutSubstitutingLinkedAnchor() throws Exception {
        String code=source("cabinet/CabinetSyncSettingsScreen");
        assertTrue(code.contains("target.dual()?\"双人街机\":\"单人街机\""));
        assertTrue(code.contains("target.anchor().getX()"));
        assertTrue(code.contains("target.anchor().getY()"));
        assertTrue(code.contains("target.anchor().getZ()"));
        assertTrue(code.contains("value.target().equals(target)"));
        assertTrue(code.contains("value.backend().equals(backend)"));
        assertTrue(code.contains("Objects.equals(value.debugToken(),debugToken)"));
        assertTrue(code.contains("CabinetSyncNetwork.requestMode(target,backend,mode,debugToken)"));
        assertTrue(code.contains("CabinetSyncNetwork.requestMode(target,backend,-1,debugToken)"));
        assertTrue(code.contains("this(parent,target,backend,null)"));
        assertTrue(code.contains("cooldown=4"));
        assertTrue(code.contains("刷新状态"));
    }
}
