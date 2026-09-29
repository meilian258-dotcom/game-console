package cn.piq.fcarcade.config;

import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GameConsoleAdminSettingsTest {
    @Test void actualSavedDataRoundTripsDefaultsWithoutDevicesOrSessions() {
        for(int mode=-1;mode<=2;mode++)for(int range:new int[]{4,16,32,64}) {
            var input=new CompoundTag();input.putInt("Mode",mode);input.putInt("Range",range);
            var output=GameConsoleAdminSettings.load(input,null).save(new CompoundTag(),null);
            assertEquals(mode,output.getInt("Mode"));assertEquals(range,output.getInt("Range"));assertEquals(2,output.getAllKeys().size());
        }
    }
    @Test void missingMalformedAndOutOfRangeStoredDefaultsStayConservative() {
        for(int mode:new int[]{-2,3,Integer.MAX_VALUE})for(int range:new int[]{0,3,65,Integer.MAX_VALUE}) {
            var input=new CompoundTag();input.putInt("Mode",mode);input.putInt("Range",range);
            var output=GameConsoleAdminSettings.load(input,null).save(new CompoundTag(),null);
            assertEquals(-1,output.getInt("Mode"));assertEquals(16,output.getInt("Range"));
        }
        var malformed=new CompoundTag();malformed.putString("Mode","2");malformed.putString("Range","64");
        var output=GameConsoleAdminSettings.load(malformed,null).save(new CompoundTag(),null);
        assertEquals(-1,output.getInt("Mode"));assertEquals(16,output.getInt("Range"));
    }
    @Test void oldNbtCannotAdoptNewPlacementDefaultsAndNoDefaultIsReadByRangeLookup() throws Exception {
        for(String name:new String[]{"home/HomeEndpointBlockEntity","world/LegacyFcArcadeBlockEntity"}) {
            String code=source(name),load=code.substring(code.indexOf("protected void loadAdditional"),code.indexOf("protected void saveAdditional"));
            assertTrue(load.contains("adminDefaultsInitialized=true")||load.contains("adminDefaultsInitialized = true"));
            assertTrue(load.contains("persistedRange(tag.getInt(\"ConsoleWatchRange\"),0)")||load.contains("persistedRange(tag.getInt(\"ConsoleWatchRange\"), 0)"));
            assertFalse(load.contains("defaultMode("));assertFalse(load.contains("defaultRange("));
            assertTrue(code.contains("!adminDefaultsInitialized"));
        }
        String ranges=source("config/GameConsoleAdminSettings");
        ranges=ranges.substring(ranges.indexOf("public static int watchRange("),ranges.indexOf("static GameConsoleAdminSettings load("));
        assertFalse(ranges.contains("defaultMode("));assertFalse(ranges.contains("defaultRange("));assertFalse(ranges.contains("getChunk("));
    }
    @Test void commandMutationsKeepOpProtectionIdleAndExactHardwareChecks() throws Exception {
        String command=source("config/GameConsoleAdminCommands");
        assertTrue(command.contains("source.hasPermission(2)&&source.getServer().isSameThread()"));
        assertTrue(command.contains("ClickEvent.Action.SUGGEST_COMMAND"));assertFalse(command.contains("ClickEvent.Action.RUN_COMMAND"));
        String home=source("home/HomeSyncSettings");
        String body=home.substring(home.indexOf("public static boolean adminCommand("),home.indexOf("private static String unavailable("));
        for(String gate:new String[]{"!player.hasPermissions(2)","authorized(player,intent,hit)","busy(player,console)","basic(player,intent)","identity(player,intent)"})assertTrue(body.contains(gate),gate);
        String cabinet=source("cabinet/CabinetSyncSettings");
        body=cabinet.substring(cabinet.indexOf("public static boolean adminCommand("),cabinet.indexOf("private static void requestChecked("));
        for(String gate:new String[]{"!p.hasPermissions(2)","ServerCabinets.validatedTarget(","request(p,new CabinetSyncNetwork.Mode("})assertTrue(body.contains(gate),gate);
        assertTrue(body.contains("街机范围已改为全服统一设置"));assertFalse(body.contains(".setObservationRange("));
        assertFalse(body.contains("setCabinetBackend("));assertFalse(body.contains("startGame("));assertFalse(body.contains("stopHomeConsole("));
    }
    private static String source(String relative)throws Exception{return Files.readString(Path.of("src/main/java/cn/piq/fcarcade/"+relative+".java")).replace("\r\n","\n");}
}
