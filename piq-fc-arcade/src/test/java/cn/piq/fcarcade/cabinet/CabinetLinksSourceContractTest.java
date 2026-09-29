package cn.piq.fcarcade.cabinet;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Narrow wiring checks, not a simulated Minecraft protection-event integration test. */
class CabinetLinksSourceContractTest {
    private String source()throws Exception{return Files.readString(Path.of("src/main/java/cn/piq/fcarcade/cabinet/CabinetLinks.java"));}
    @Test void bothOriginalBindingsAreRecheckedAfterAllPermissionEvents()throws Exception{
        String s=source();assertTrue(s.contains("ServerCabinets.validatedTarget(player,first.clicked(),first.hit())"));
        assertTrue(s.contains("authorizedBoth(player,primaryBinding,clickedBinding)"));
        assertTrue(s.contains("authorizedBoth(player,clickedBinding,otherBinding)"));
        assertTrue(s.contains("ServerCabinets.valid(player,first,false)"));assertTrue(s.contains("ServerCabinets.valid(player,second,false)"));
        assertTrue(s.contains("first.cabinet(),first.clickedEntity()"));assertTrue(s.contains("USING.get()"));assertTrue(s.contains("finally{USING.remove();}"));
    }
    @Test void pendingAuthorityAndRuntimeTopologyCannotSurviveWrongSession()throws Exception{
        String s=source();assertTrue(s.contains("PENDING_TICKS=1200,MAX_PENDING=64"));
        assertTrue(s.contains("server.getPlayerList().getPlayer(value.player().getUUID())!=value.player()"));
        assertTrue(s.contains("value.player().serverLevel().dimension().location()"));
        assertTrue(s.contains("CabinetLinks::login"));assertTrue(s.contains("CabinetLinks::logout"));assertTrue(s.contains("CabinetLinks::stopped"));
        assertTrue(s.contains("player.hasPermissions(2)"));assertTrue(s.contains("ServerCabinets.isCabinetBusy(server,first)"));
        assertTrue(s.contains("player.displayClientMessage(Component.literal(text),true)"));
    }
    @Test void persistedSecondaryNeverBecomesPrimaryWhenPeerUnloads()throws Exception{
        String s=source();String master=s.substring(s.indexOf("public static CabinetTarget master"),s.indexOf("public static boolean hasLink"));
        assertFalse(master.contains("validPair"));assertTrue(master.contains("target(pair.primary()):supplied"));
        assertTrue(s.contains("public static boolean hasLink"));assertTrue(s.contains("pair!=null&&validPair(server,pair)"));
        assertTrue(s.contains("computeIfAbsent("));assertTrue(s.contains("server.overworld().getDataStorage()"));
        assertTrue(s.contains("Math.min(pairs.size(),512)"));assertFalse(s.contains("getChunk("));
        assertTrue(s.contains("!row.hasUUID(\"Id\")||!row.contains(\"Primary\",Tag.TAG_COMPOUND)||!row.contains(\"Secondary\",Tag.TAG_COMPOUND)"));
    }
}
