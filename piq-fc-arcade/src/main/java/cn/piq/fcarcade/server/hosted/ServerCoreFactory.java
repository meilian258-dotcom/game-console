package cn.piq.fcarcade.server.hosted;

import java.nio.file.Path;

/** Common/headless addon extension. Registration neither enables hosting nor starts a core. */
public interface ServerCoreFactory {
    int maxPlayers();
    /** Hard adapter ceiling, in addition to the server administrator's lower global budget. */
    default int maxConcurrentSessions(){return 32;}
    String unavailableReason(ServerCoreContext context);
    /** Called off the Minecraft server thread; the returned handle itself is nonblocking. */
    ServerCoreHandle open(ServerCoreContext context, Path rom) throws Exception;
}
