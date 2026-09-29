package cn.piq.fcarcade.config;

import cn.piq.fcarcade.home.DeviceDebugPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GameConsoleCommandSelectionTest {
    @Test void commandUsesLoadedOnlyRayAndRetainsFinalConnectionIdentity() throws Exception {
        String command=Files.readString(Path.of("src/main/java/cn/piq/fcarcade/config/GameConsoleAdminCommands.java"));
        assertTrue(command.contains("DeviceDebugService.loadedTarget(player)"));assertFalse(command.contains("player.pick("));
        String service=Files.readString(Path.of("src/main/java/cn/piq/fcarcade/home/DeviceDebugService.java"));
        assertTrue(service.contains("Math.min(DeviceDebugPolicy.RANGE,player.blockInteractionRange())"));
        assertTrue(service.contains("level.hasChunkAt(pos) ? level.getBlockState(pos) : Blocks.BARRIER.defaultBlockState()"));
        assertTrue(service.contains("level.hasChunkAt(pos) ? level.getBlockEntity(pos) : null"));
        String wrapper=service.substring(service.indexOf("public static BlockHitResult loadedTarget("),service.indexOf("private static boolean finite("));
        for(String gate:new String[]{"CHECKING.get()","!player.getServer().isSameThread()","player.serverLevel()==level","player.connection.getConnection()==connection","connection.isConnected()","getPlayer(player.getUUID())==player"})assertTrue(wrapper.contains(gate),gate);
    }
    @Test void finiteRayRangeHonorsShortReachAndCapsExtendedReachAtSix() {
        for(double reach:new double[]{1,1.5,3,4.5,6,8,64}) {
            double allowed=Math.min(6,reach);
            assertTrue(DeviceDebugPolicy.inRange(allowed*allowed,reach));
            assertFalse(DeviceDebugPolicy.inRange(Math.nextUp(allowed*allowed),reach));
        }
        for(double bad:new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,-1,0})assertFalse(DeviceDebugPolicy.inRange(0,bad));
        assertFalse(DeviceDebugPolicy.inRange(-1,6));assertFalse(DeviceDebugPolicy.inRange(Double.NaN,6));
    }
}
