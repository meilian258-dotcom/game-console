package cn.piq.fcarcade.home;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Call-site regressions; pure animation tests cover behavior, Minecraft acceptance remains separate. */
class HomePresentationSourceContractTest {
    private static String source(String file) throws Exception {
        return Files.readString(Path.of("src/main/java/cn/piq/fcarcade", file));
    }
    @Test void allThreeCableTransactionsEmitOnlyAfterCommit() throws Exception {
        String av = source("home/HomeHardware.java");
        assertTrue(av.indexOf("wire.shrink(1)") < av.indexOf("Action.CABLE_CONNECT"));
        String gun = source("home/ZapperStandService.java");
        assertTrue(gun.indexOf("d.setDirty();b.link(link);") < gun.indexOf("Action.CABLE_CONNECT"));
        String cabinet = source("cabinet/CabinetLinks.java");
        assertTrue(cabinet.indexOf("连接未完成") < cabinet.indexOf("Action.CABLE_CONNECT"));
    }
    @Test void preferencesAreSavedAndNoSignalToneRespectsBothToggles() throws Exception {
        String tv = source("home/HomeTvBlockEntity.java");
        for (String key : new String[]{"TvPowerAnimation", "TvNoSignalTone", "TvMuted"}) {
            assertTrue(tv.contains("tag.getBoolean(\"" + key + "\")"));
            assertTrue(tv.contains("tag.putBoolean(\"" + key + "\","));
        }
        assertTrue(source("client/TelevisionTone.java").contains("tv.noSignalToneEnabled() && !tv.muted()"));
    }
    @Test void fcExternalAndIdleRenderersShareTheEnvelope() throws Exception {
        assertTrue(source("client/ArcadeBlockScreenRenderer.java").contains("HomeApplianceClient.powerAmount(tv)"));
        assertTrue(source("client/HomeVideoDisplay.java").contains("HomeApplianceClient.powerAmount(tv)"));
        assertTrue(source("client/HomeApplianceClient.java").contains("double power = powerAmount(tv)"));
        assertFalse(source("home/HomeTvBlockEntity.java").contains("HomeInteractionSounds.play"));
    }
    @Test void chunkSnapshotSnapsButLivePacketMayAnimate() throws Exception {
        String tv = source("home/HomeTvBlockEntity.java");
        String snapshot = tv.substring(tv.indexOf("void handleUpdateTag"), tv.indexOf("void onDataPacket"));
        assertTrue(snapshot.contains("powerTransition.loaded(powered())"));
        assertFalse(snapshot.contains("powerTransition.observe("));
        assertFalse(tv.substring(tv.indexOf("protected void loadAdditional")).contains("powerTransition.observe("));
    }
    @Test void emptyPowerReturnsBeforeAnyCoreStart() throws Exception {
        String service = source("home/HomeApplianceService.java");
        int start = service.indexOf("var idle = new EmptyConsolePower()");
        int end = service.indexOf("HomeConsoleRuntime.powerOn",start);
        assertTrue(start > 0 && end > start);
        assertTrue(service.substring(start,end).contains("return InteractionResult.CONSUME;"));
        assertFalse(service.substring(start,end).contains("HomeConsoleRuntime.takeController"));
    }
    @Test void emptyStateIsTransientAndClearedOnHardwareLifecycle() throws Exception {
        String tv = source("home/HomeTvBlockEntity.java");
        String save = tv.substring(tv.indexOf("protected void saveAdditional"),tv.indexOf("public CompoundTag getUpdateTag"));
        assertFalse(save.contains("TvEmptyConsole"));
        String hardware = source("home/HomeHardware.java");
        assertTrue(hardware.contains("HomeApplianceService.clearEmptyPower(endpoint)"));
        assertTrue(hardware.contains("HomeApplianceService.clearEmptyPowerFor(event.getServer())"));
        assertTrue(hardware.contains("HomeApplianceService.cartridgeChanged(player.serverLevel(),console.getBlockPos())"));
    }
    @Test void unknownAddonsKeepLegacyMediaBehavior() throws Exception {
        String external = source("home/ExternalHomeConsoleBlockEntity.java");
        assertTrue(external.contains("boolean hasInsertedCartridge() { return true; }"));
        assertTrue(external.contains("HomeApplianceService.cartridgeChanged(serverLevel, worldPosition)"));
    }
    @Test void emptyMessageIsLocalizedAndDoesNotBeepLikeNoSignal() throws Exception {
        assertTrue(source("client/HomeApplianceClient.java").contains("Component.translatable(\"screen.piq_fc_arcade.no_cartridge\")"));
        assertTrue(source("client/TelevisionTone.java").contains("!tv.emptyConsolePowered()"));
    }
}
