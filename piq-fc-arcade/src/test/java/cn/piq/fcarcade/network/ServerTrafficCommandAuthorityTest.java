package cn.piq.fcarcade.network;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Guards the shared Brigadier root merge: another feature may register /gameconsole first. */
class ServerTrafficCommandAuthorityTest {
    @Test void trafficNodeHasItsOwnPermissionAndEachMutationRechecksBeforeUsingPlayer() throws Exception {
        String code=Files.readString(Path.of("src/main/java/cn/piq/fcarcade/network/ServerTrafficDiagnostics.java"));
        assertTrue(code.contains("Commands.literal(\"traffic\").requires(s->s.hasPermission(2))"));
        for(String name:new String[]{"watch","off"}) {
            int start=code.indexOf("Commands.literal(\""+name+"\")");
            int player=code.indexOf("var p=c.getSource().getPlayerOrException()",start);
            int guard=code.indexOf("if(!authorized(c.getSource()))return 0;",start);
            assertTrue(start>=0&&guard>start&&guard<player,name);
        }
        assertTrue(code.contains("if(!authorized(source)||meter==null)return 0"));
        assertTrue(code.contains("source.hasPermission(2)&&source.getServer()!=null&&source.getServer()==server&&source.getServer().isSameThread()"));
    }
}
