// PIQ-authored code: additional permission in piq-fc-arcade/MD-ARCADE-LINKING-EXCEPTION.txt.
// PIQ-authored code: additional permission in piq-fc-arcade/SNES9X-LINKING-EXCEPTION.txt.
package cn.piq.fcarcade.cabinet;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LegacyLocalSyncPolicyTest {
    @Test void oldConfigurationStillDecodesButCannotStartANewOrdinaryRoom() {
        assertEquals(CabinetSyncMode.LOCAL_SYNC,CabinetSyncMode.checked(1));
        assertFalse(LegacyLocalSyncPolicy.mayStart(CabinetSyncMode.checked(1),false,false));
        for(boolean netplay:new boolean[]{false,true})for(boolean originalFc:new boolean[]{false,true})
            assertFalse(LegacyLocalSyncPolicy.mayStart(null,netplay,originalFc));
        assertEquals(0,CabinetSyncMode.MEDIA.ordinal());
        assertEquals(2,CabinetSyncMode.SERVER_MEDIA.ordinal());
    }

    @Test void sharedNetplayMarkerAndTheOriginalFcCabinetRouteArePreserved() {
        for(boolean netplay:new boolean[]{false,true})for(boolean originalFc:new boolean[]{false,true})
            assertEquals(netplay||originalFc,LegacyLocalSyncPolicy.mayStart(CabinetSyncMode.LOCAL_SYNC,netplay,originalFc));
        for(var mode:new CabinetSyncMode[]{CabinetSyncMode.MEDIA,CabinetSyncMode.SERVER_MEDIA})
            assertTrue(LegacyLocalSyncPolicy.mayStart(mode,false,false));
    }

    @Test void onlyTheRetiredSelectionIsRemovedNotNetplayOrOtherCommands() {
        for(int mode=-1;mode<=6;mode++)assertEquals(mode==1,LegacyLocalSyncPolicy.retiredSelection(mode));
        for(int mask=0;mask<32;mask++){
            int selected=LegacyLocalSyncPolicy.selectableMask(mask);
            assertEquals(0,selected&2);
            assertEquals(mask&~2,selected);
            assertEquals(mask&24,selected&24);
        }
    }

    @Test void newAutomaticAndMediaDefaultsNeverFallBackToOldSync() {
        for(int preferred:new int[]{-1,0,2})for(boolean player:new boolean[]{false,true})for(boolean hosted:new boolean[]{false,true}){
            var mode=CabinetPlacementDefaults.mediaMode(preferred,player,hosted);
            assertNotEquals(CabinetSyncMode.LOCAL_SYNC,mode);
            assertEquals(preferred==0&&player?CabinetSyncMode.MEDIA:hosted?CabinetSyncMode.SERVER_MEDIA:CabinetSyncMode.MEDIA,mode);
        }
        // JNI selection remains the existing opt-in/default rule, checked before the media fallback.
        assertTrue(CabinetPlacementDefaults.jni(true,-1,true,true,true));
        assertFalse(CabinetPlacementDefaults.jni(true,1,true,true,true));
        assertThrows(IllegalArgumentException.class,()->CabinetPlacementDefaults.mediaMode(3,true,true));
        assertThrows(IllegalArgumentException.class,()->CabinetPlacementDefaults.mediaMode(-2,true,true));
    }

    @Test void explicitOldGlobalDefaultRemainsDisabledAndRequiresReselection() {
        for(boolean player:new boolean[]{false,true})for(boolean hosted:new boolean[]{false,true}) {
            var mode=CabinetPlacementDefaults.mediaMode(1,player,hosted);
            assertEquals(CabinetSyncMode.LOCAL_SYNC,mode,"an explicit old preference must not silently choose another runtime/save identity");
            assertFalse(LegacyLocalSyncPolicy.mayStart(mode,false,false));
            assertTrue(LegacyLocalSyncPolicy.retiredSelection(mode.ordinal()));
            assertEquals(0,LegacyLocalSyncPolicy.selectableMask(31)&(1<<mode.ordinal()));
            assertFalse(CabinetPlacementDefaults.jni(true,1,true,true,true));
        }
    }

    @Test void actualRoomGateRunsBeforeAllocationAndOnlyForANewRoom() throws Exception {
        // Wiring guard complements the executed policy tests, not a graphical/server integration claim.
        String source=Files.readString(Path.of("src/main/java/cn/piq/fcarcade/cabinet/CabinetRooms.java"));
        int gate=source.indexOf("if(!LegacyLocalSyncPolicy.mayStart(mode,netplay,backend.equals(CabinetBackends.NES)))");
        assertTrue(gate>0);
        int newRoom=source.lastIndexOf("if(room==null){",gate);
        assertTrue(newRoom>=0&&newRoom<gate);
        assertTrue(gate<source.indexOf("room=state.ledger.open("));
        String settings=Files.readString(Path.of("src/main/java/cn/piq/fcarcade/cabinet/CabinetSyncSettings.java"));
        assertTrue(settings.contains("LegacyLocalSyncPolicy.retiredSelection(packet.mode())"));
        assertTrue(settings.contains("boolean supported=false"));
        assertTrue(settings.contains("boolean netplaySupported=CabinetHostingConfig.localAllowed()"));
        int newDefault=settings.indexOf("return data(s).rememberDefault(key(t,b),selected,false,null)");
        int oldImplicit=settings.indexOf("if(b.toString().equals(\"piq_sfc_home:sfc\")&&CabinetHostingConfig.localAllowed()&&CabinetBackends.supportsSync(b))return CabinetSyncMode.LOCAL_SYNC");
        assertTrue(newDefault>=0&&oldImplicit>newDefault,"new placements resolve first; only old unconfigured SFC hardware retains its implicit identity");
    }

    @Test void adminGuiAndCommandWritesRejectLegacySelectionBeforeMutation() throws Exception {
        String service=Files.readString(Path.of("src/main/java/cn/piq/fcarcade/config/AdminTerminalService.java"));
        String modeBranch=service.substring(service.indexOf("case AdminTerminalPolicy.MODE ->"),service.indexOf("case AdminTerminalPolicy.RANGE ->"));
        int serviceGate=modeBranch.indexOf("LegacyLocalSyncPolicy.retiredSelection(request.value())");
        assertTrue(serviceGate>=0&&serviceGate<modeBranch.indexOf("GameConsoleAdminSettings.setDefaults("));
        String commands=Files.readString(Path.of("src/main/java/cn/piq/fcarcade/config/GameConsoleAdminCommands.java"));
        String global=commands.substring(commands.indexOf("private static int update("),commands.indexOf("private static int target("));
        int globalGate=global.indexOf("LegacyLocalSyncPolicy.retiredSelection(mode)");
        assertTrue(globalGate>=0&&globalGate<global.indexOf("GameConsoleAdminSettings.setDefaults("));
        String target=commands.substring(commands.indexOf("private static int target("),commands.indexOf("private static int show("));
        int targetGate=target.indexOf("LegacyLocalSyncPolicy.retiredSelection(mode)");
        assertTrue(targetGate>=0&&targetGate<target.indexOf("HomeSyncSettings.adminCommand("));
    }
}
