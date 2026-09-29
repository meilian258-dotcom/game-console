package cn.piq.sfchome.client;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Architecture guard, separate from executed frame/demand tests and real worker probes. */
class SfcWatchSourceTest {
    private String source(String name)throws Exception{return Files.readString(Path.of("src/main/java/cn/piq/sfchome",name+".java"));}
    @Test void spectatorAdapterNeverStartsEmulatorOrRequestsCartridge()throws Exception{
        String display=source("client/SfcWatchClient"),provider=source("server/SfcWatchProvider");
        for(String forbidden:new String[]{"new SfcPlayback","new WasmSfcCore","RomRequest","ControllerInput","InputOwnership.acquire","setControllerLeased"}){
            assertFalse(display.contains(forbidden));assertFalse(provider.contains(forbidden));
        }
        assertTrue(display.contains("HomeVideoDisplay.render"));assertTrue(display.contains("HomeTvStructure.complete"));
    }
    @Test void sourceSnapshotsRequireReadyLiveEndpointsAndDontExposeRom()throws Exception{
        String server=source("server/SfcHomeServer");
        String snapshots=server.substring(server.indexOf("static java.util.List<cn.piq.fcarcade.cabinet.WatchSource> watchSources"),server.indexOf("static boolean watchParticipant"));
        assertTrue(snapshots.contains("!server.isSameThread()"));assertTrue(snapshots.contains("STATES.get(server)"));
        assertTrue(snapshots.contains("session.hostReady==null||session.clock==null"));assertTrue(snapshots.contains("hostValid(host,session)"));assertTrue(snapshots.contains("session.host.id"));assertFalse(snapshots.contains("session.ports["));
        assertTrue(snapshots.contains("c.televisionId()"));assertTrue(snapshots.contains("c.linkId()"));
        assertFalse(snapshots.contains("state(server)"));assertFalse(snapshots.contains("RomRequest"));assertFalse(snapshots.contains("SfcHomeNetwork.send"));
    }
    @Test void publisherOnlyCopiesOnWorkerAndSharesBoundedConnectionWindow()throws Exception{
        String source=source("client/SfcWatchPublisher");
        String frame=source.substring(source.indexOf("static void frame("),source.indexOf("static void tick()"));
        assertFalse(frame.contains("Minecraft"));assertFalse(frame.contains("watchServerbound"));
        assertTrue(frame.contains("!current.needed"));assertTrue(frame.contains("SfcWatchFrames.copy"));
        assertTrue(source.contains("CabinetMediaSender.watchServerbound"));assertTrue(source.contains("controllerLease().equals(descriptor.hostLease())"));
        String suspend=source.substring(source.indexOf("private static void suspend()"),source.indexOf("private static void close()"));
        assertTrue(suspend.contains("sending(false)"));assertFalse(suspend.contains("close()"));
    }
    @Test void optionalWatchFailureIsIsolatedButRequiredPlayerMediaStops()throws Exception{
        String source=source("client/SfcPlayback");
        assertTrue(source.contains("default void mediaFrame("));assertTrue(source.contains("session.executionHost()&&!mediaFailed"));
        int branch=source.indexOf("if(session.playerHosted())throw new IllegalStateException");
        int optional=source.indexOf("mediaFailed=true;LoggerFactory.getLogger");
        assertTrue(branch>=0&&optional>branch);
        assertTrue(source.contains("SFC spectator publication disabled; controller playback continues"));
        assertTrue(source.contains("host.observedFrame(nextFrame"));
        assertFalse(source.contains("SfcWatchPublisher.demand"));
    }
}
