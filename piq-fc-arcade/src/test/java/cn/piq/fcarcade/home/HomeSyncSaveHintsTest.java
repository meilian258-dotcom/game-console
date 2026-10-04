package cn.piq.fcarcade.home;

import cn.piq.fcarcade.cabinet.CabinetSyncMode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HomeSyncSaveHintsTest {
    @Test void localAndPlayerSfcDescribeRecoveryBackupNotAutomaticCartridgeSave() {
        for (var mode : new CabinetSyncMode[] {CabinetSyncMode.LOCAL_SYNC, CabinetSyncMode.MEDIA}) {
            String saved=HomeSyncSaveHints.modeSaved("SFC",mode.ordinal());
            assertTrue(saved.contains("运行玩家本机的恢复备份"));
            assertTrue(saved.contains("不是卡带存档"));assertTrue(saved.contains("不会自动续玩"));
            assertFalse(saved.contains("不同同步模式的存档分开"));
            String footer=HomeSyncSaveHints.footer("SFC",mode.ordinal());
            assertTrue(footer.contains("本机恢复备份"));assertTrue(footer.contains("非卡带档"));
            assertTrue(footer.contains("不自动续玩"));assertFalse(footer.contains("卡带菜单"));
        }
    }
    @Test void hostedSfcDescribesIndependentServerSram() {
        int mode=CabinetSyncMode.SERVER_MEDIA.ordinal();
        assertTrue(HomeSyncSaveHints.modeSaved("SFC",mode).contains("独立服务器 SRAM"));
        assertTrue(HomeSyncSaveHints.modeSaved("SFC",mode).contains("与玩家本机恢复备份分开"));
        assertTrue(HomeSyncSaveHints.footer("SFC",mode).contains("独立服务器 SRAM"));
        assertFalse(HomeSyncSaveHints.footer("SFC",mode).contains("卡带菜单"));
    }
    @Test void fcAndSuborKeepExistingCartridgeSaveGuidance() {
        for (String system : new String[] {"FC", "小霸王学习机（SB-926）"})
            for (var mode : CabinetSyncMode.values()) {
                assertTrue(HomeSyncSaveHints.modeSaved(system,mode.ordinal()).contains("存档方式由卡带决定"));
                assertTrue(HomeSyncSaveHints.footer(system,mode.ordinal()).contains("存档仍在卡带菜单"));
            }
    }
    @Test void unknownAddonDoesNotInheritFcSavePromises() {
        for (String system : new String[] {null,"","NEW_SYSTEM","FC_PLUS","SFC_PLUS"})
            for (int mode=0;mode<3;mode++) {
                assertTrue(HomeSyncSaveHints.modeSaved(system,mode).contains("对应机型说明"));
                assertTrue(HomeSyncSaveHints.footer(system,mode).contains("对应主机说明"));
                assertFalse(HomeSyncSaveHints.footer(system,mode).contains("卡带菜单"));
            }
    }
    @Test void unknownModeStaysNeutralAndSystemMatchingIsCaseInsensitive() {
        for (String system : new String[] {"FC","SFC"})
            for (int mode : new int[] {-1,5,Integer.MAX_VALUE}) {
                assertTrue(HomeSyncSaveHints.modeSaved(system,mode).contains("对应机型说明"));
                assertTrue(HomeSyncSaveHints.footer(system,mode).contains("对应主机说明"));
            }
        assertEquals(HomeSyncSaveHints.footer("SFC",0),HomeSyncSaveHints.footer("sfc",0));
        assertTrue(HomeSyncSaveHints.modeSaved("FC",4).contains("JNI"));
        assertTrue(HomeSyncSaveHints.footer("FC",4).contains("按个人/卡带归属另存"));
        assertTrue(HomeSyncSaveHints.footer("FC",4).contains("旁观自动跟随"));
        assertTrue(HomeSyncSaveHints.modeSaved("FC",4).contains("旧档保留"));
        assertTrue(HomeSyncSaveHints.modeSaved("SFC",4).contains("对应机型说明"));
    }
    @Test void serverAndClientUseSameAcknowledgedSystemAndModeWithVersionedBounds() throws Exception {
        String settings=source("home/HomeSyncSettings");
        assertTrue(settings.contains("HomeSyncSaveHints.modeSaved(system(c),request.mode())"));
        assertTrue(settings.contains("String system = system(c)"));
        assertTrue(settings.contains("external.systemId().getPath().toUpperCase(java.util.Locale.ROOT)"));
        String screen=source("client/HomeSyncSettingsScreen");
        assertTrue(screen.contains("HomeSyncSaveHints.footer(setting.system(),setting.mode())"));
        assertFalse(screen.contains("存档仍在卡带菜单"));
        assertTrue(screen.contains("setting=value;pending=false"));
        assertTrue(source("home/HomeSyncNetwork").contains("TrafficPayloadRegistrar.create(event,\"home-sync-5\")"));
    }
    @Test void sharedTvRemoteDoesNotPromiseEveryMachineACartridgeMenu() throws Exception {
        String remote=source("home/TvRemoteService");
        assertFalse(remote.contains("存档仍在卡带菜单"));
        assertTrue(remote.contains("存档功能以对应主机说明为准"));
    }
    private static String source(String name) throws Exception {
        return Files.readString(Path.of("src/main/java/cn/piq/fcarcade/"+name+".java"));
    }
}
