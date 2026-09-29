package cn.piq.fcarcade.cabinet;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class PgmServicePolicyTest {
    private static CabinetGameManifest manifest(String backend,String game,boolean bios){
        var main=new CabinetGameManifest.Entry(game,"a".repeat(64),100);
        return new CabinetGameManifest(backend,bios?List.of(main,new CabinetGameManifest.Entry("pgm.zip","b".repeat(64),100)):List.of(main));
    }
    @Test void onlyDeclaredBackendNotGameNameWhitelist(){
        PgmServicePolicy.registerDiagnosticBackend(net.minecraft.resources.ResourceLocation.parse("piq_native_arcade:mame"));
        assertTrue(PgmServicePolicy.supports(manifest("piq_native_arcade:mame","kov.zip",true)));
        assertTrue(PgmServicePolicy.supports(manifest("piq_native_arcade:mame","kov115.zip",true)));
        assertTrue(PgmServicePolicy.supports(manifest("piq_native_arcade:mame","kov.zip",false)));
        assertTrue(PgmServicePolicy.supports(manifest("piq_native_arcade:mame","dino.zip",true)));
        assertFalse(PgmServicePolicy.supports(manifest("piq_sfc_home:sfc","kov.zip",true)));
        assertFalse(PgmServicePolicy.supports(null));
    }
    @Test void reservedComboRequiresGrant(){
        for(int mask=0;mask<65536;mask++){
            assertEquals(mask,PgmServicePolicy.filterInput(mask,true));
            int clean=PgmServicePolicy.filterInput(mask,false);
            assertNotEquals(PgmServicePolicy.DIAGNOSTIC_MASK,clean&PgmServicePolicy.DIAGNOSTIC_MASK);
            assertEquals(mask&~8,clean&~8);
        }
        assertEquals(8,PgmServicePolicy.filterInput(8,false));
    }
    @Test void serverAndClientFenceTheExactHostAndLease() throws Exception {
        String server=java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/cn/piq/fcarcade/cabinet/CabinetRooms.java"));
        String action=server.substring(server.indexOf("public static String pgmService("),server.indexOf("static void ready("));
        for(String check:List.of("current(player)","player.hasPermissions(2)","member.port!=0","member!=room.host()","authorized(player,room.id,member.id)!=member","CabinetNetplay.active(room.id)","validatedManifest","PgmServicePolicy.supports","tick+300"))assertTrue(action.contains(check),check);
        String client=java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/cn/piq/fcarcade/client/cabinet/CabinetClientBackends.java"));
        assertTrue(client.contains("!netplay()||room.port()!=0||!room.room().equals(value.room())||!room.member().equals(value.member())||!current()||!running()"));
    }
}
