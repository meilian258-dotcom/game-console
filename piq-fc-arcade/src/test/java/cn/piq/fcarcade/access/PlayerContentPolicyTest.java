package cn.piq.fcarcade.access;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlayerContentPolicyTest {
    final UUID alice=UUID.fromString("a81215f0-0464-46f5-b009-c2a14765aa13"), bob=UUID.fromString("a81215f0-0464-46f5-b009-c2a14765aa14");
    @Test void defaultsDenyOrdinaryPlayersButNotOperators() {
        var p=new PlayerContentPolicy(false,false,false,Set.of());
        assertEquals(0,p.capabilities(alice,false));assertEquals(63,p.capabilities(alice,true));assertEquals(0,p.capabilities(null,true));
    }
    @Test void everyIndependentToggleCombination() {
        for(boolean all:new boolean[]{true,false})for(boolean rom:new boolean[]{true,false})for(boolean cover:new boolean[]{true,false})for(boolean useRom:new boolean[]{true,false})for(boolean useCover:new boolean[]{true,false}) {
            var p=new PlayerContentPolicy(all,rom,cover,Set.of(alice),useRom,useCover);
            int caps=1+(rom?2:0)+(cover?4:0)+(useRom?16:0)+(useCover?32:0);
            assertEquals(caps,p.capabilities(alice,false));assertEquals(all?caps:0,p.capabilities(bob,false));
            assertFalse(PlayerContentPolicy.has(caps,PlayerContentPolicy.ADMIN));
        }
    }
    @Test void browseDoesNotGrantUploadOrAdministration() {
        int c=new PlayerContentPolicy(true,false,false,Set.of()).capabilities(alice,false);
        assertTrue(PlayerContentPolicy.has(c,1));assertFalse(PlayerContentPolicy.has(c,2));assertFalse(PlayerContentPolicy.has(c,4));assertFalse(PlayerContentPolicy.has(c,8));
        assertFalse(PlayerContentPolicy.has(c,0));
    }
    @Test void revokeDoesNotCarryPreviousPermissions() {
        var old=new PlayerContentPolicy(false,true,true,Set.of(alice));
        assertEquals(23,old.capabilities(alice,false));assertEquals(0,new PlayerContentPolicy(false,true,true,Set.of()).capabilities(alice,false));
    }
    @Test void defensiveCopyAndStrictPlayerIdentity() {
        var set=new java.util.HashSet<>(Set.of(alice));var p=new PlayerContentPolicy(false,false,false,set);set.clear();assertEquals(17,p.capabilities(alice,false));
        assertEquals(alice,PlayerContentPolicy.parsePlayer(alice.toString().toUpperCase()));
        for(String bad:new String[]{"1-1-1-1-1","../alice","00000000-0000-0000-0000-000000000000"," alice",""})
            assertThrows(IllegalArgumentException.class,()->PlayerContentPolicy.parsePlayer(bad));
    }
    @Test void commandAndConfigStayServerOwned() throws Exception {
        var root=java.nio.file.Path.of("src/main/java/cn/piq/fcarcade/access");
        String commands=java.nio.file.Files.readString(root.resolve("PlayerContentCommands.java"));
        assertTrue(commands.contains("source.hasPermission(2)"));assertTrue(commands.contains("source.getServer().isSameThread()"));
        String access=java.nio.file.Files.readString(root.resolve("PlayerContentAccess.java"));
        assertTrue(access.contains("ModConfig.Type.SERVER"));assertTrue(access.contains("SPEC.save()"));assertTrue(access.contains("set(old)"));
        assertFalse(access.contains("Files.delete"));
    }
}
