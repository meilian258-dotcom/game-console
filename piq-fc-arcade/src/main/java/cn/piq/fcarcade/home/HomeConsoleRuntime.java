package cn.piq.fcarcade.home;
import cn.piq.fcarcade.server.ServerArcadeSessions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
/** Physical permission checks are repeated within each session transaction. */
public final class HomeConsoleRuntime {
    private HomeConsoleRuntime(){}
    public static boolean powerOn(ServerPlayer player,HomeConsoleBlockEntity console){return ServerArcadeSessions.powerHomeConsole(player,console,ZapperStandService.connected(console));}
    public static void powerOff(ServerLevel level,HomeConsoleBlockEntity console){if(console!=null&&console.getLevel()==level)ServerArcadeSessions.stopHomeConsole(level.getServer(),level.dimension(),console.tvPos());}
    public static void reset(ServerPlayer player,HomeConsoleBlockEntity console){ServerArcadeSessions.resetHomeConsole(player,console);}
    public static void takeController(ServerPlayer player,HomeConsoleBlockEntity console,int port){ServerArcadeSessions.takeHomeController(player,console,port);}
    public static boolean running(ServerLevel level,HomeConsoleBlockEntity console){return ServerArcadeSessions.homeRunning(level,console);}
    public static boolean pendingSave(ServerPlayer player,HomeConsoleBlockEntity console){return ServerArcadeSessions.homeSavePending(player,console);}
}
