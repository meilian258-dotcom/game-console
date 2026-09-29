package cn.piq.fcarcade.cabinet;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CabinetPowerSettingsTest {
    @Test void defaultsAndCorruptPersistentValuesAreBounded(){
        assertEquals(60,CabinetPowerSettings.DEFAULT_SECONDS);assertEquals(16,CabinetPowerSettings.DEFAULT_RENDER_DISTANCE);
        for(int value:new int[]{-1,0,Integer.MIN_VALUE,Integer.MAX_VALUE}){assertEquals(60,CabinetPowerSettings.seconds(value));assertEquals(16,CabinetPowerSettings.renderDistance(value));}
        assertTrue(CabinetPowerSettings.validSeconds(1));assertTrue(CabinetPowerSettings.validSeconds(3600));assertFalse(CabinetPowerSettings.validSeconds(3601));
        assertTrue(CabinetPowerSettings.validRenderDistance(128));assertFalse(CabinetPowerSettings.validRenderDistance(129));
    }
    @Test void visibilityBoundaryUsesSquared3dDistanceAndNeverPowerState(){
        assertTrue(CabinetPowerSettings.visible(0,16));assertTrue(CabinetPowerSettings.visible(256,16));assertFalse(CabinetPowerSettings.visible(256.0001,16));
        assertFalse(CabinetPowerSettings.visible(Double.NaN,16));assertFalse(CabinetPowerSettings.visible(Double.POSITIVE_INFINITY,16));assertFalse(CabinetPowerSettings.visible(-1,16));
        assertTrue(CabinetPowerSettings.visible(1024,32));assertFalse(CabinetPowerSettings.visible(1025,32));
    }
    @Test void allArcadeVideoPathsGateBeforeRenderingWithoutChangingHomeScreens()throws Exception{
        String base="src/main/java/cn/piq/fcarcade/";
        var display=Files.readString(Path.of(base+"client/cabinet/CabinetVideoDisplay.java"));
        assertTrue(display.indexOf("CabinetClientSettings.rules().visible")<display.indexOf("CabinetVideoGeometry.frame"));
        assertFalse(display.contains("cabinet.screenRenderDistance()"));assertFalse(display.contains("closeRoom"));
        var legacy=Files.readString(Path.of(base+"client/ArcadeBlockScreenRenderer.java"));
        assertTrue(legacy.contains("instanceof cn.piq.fcarcade.world.LegacyFcArcadeBlockEntity cabinet"));
        assertTrue(legacy.indexOf("CabinetClientSettings.rules().visible")<legacy.indexOf("session.textureAt"));
        assertFalse(Files.readString(Path.of(base+"client/HomeVideoDisplay.java")).contains("CabinetPowerSettings"));
        var network=Files.readString(Path.of(base+"cabinet/CabinetSyncNetwork.java"));assertTrue(network.contains("cabinet-sync-10"));
        var rooms=Files.readString(Path.of(base+"cabinet/CabinetRooms.java"));assertFalse(rooms.contains("screenRenderDistance"));
        var server=Files.readString(Path.of(base+"cabinet/CabinetSyncSettings.java"));assertFalse(server.contains(".setPowerSettings("));
    }
}
