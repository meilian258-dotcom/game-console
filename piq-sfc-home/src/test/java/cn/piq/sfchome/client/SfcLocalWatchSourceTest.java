package cn.piq.sfchome.client;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Wiring guards supplement the executed bounded-transfer and actual-worker tests. */
class SfcLocalWatchSourceTest {
    String source(String file)throws Exception{return Files.readString(Path.of("src/main/java/cn/piq/sfchome/"+file+".java"));}
    @Test void observerNeverGetsControlsDownloadsOrSaves()throws Exception{
        String client=source("client/SfcLocalWatchClient"),server=source("server/SfcLocalWatchServer");
        for(String forbidden:new String[]{"new SfcHomeNetwork.Input","ControllerReady","RomRequest","InputOwnership.acquire","SfcClientFiles.snapshot","loadSram","saveSram"})assertFalse(client.contains(forbidden),forbidden);
        assertTrue(client.contains("Observers must not save"));assertTrue(client.contains("SfcClientFiles.cachedRom"));
        for(String forbidden:new String[]{"authorizedRom(","claim(","s.inputs","setControllerLeased","repairs.start","s.clock="})assertFalse(server.contains(forbidden),forbidden);
        assertTrue(server.contains("localWatchReplay"));assertTrue(server.contains("localWatchDigest"));
    }
    @Test void oldConnectionAndOldAssignmentAreRejected()throws Exception{
        String client=source("client/SfcLocalWatchClient"),network=source("net/SfcLocalWatchNetwork"),server=source("server/SfcLocalWatchServer");
        assertTrue(network.contains("p.connection.getConnection()==source"));assertTrue(network.contains("getPlayer(p.getUUID())==p"));
        assertTrue(client.contains("assignment==expected"));assertTrue(client.contains("p.revision()!=revision"));
        assertTrue(server.contains("pref.connection!=o.connection"));assertTrue(server.contains("source.source().equals(o.source.source())"));
    }
    @Test void modeChoiceNeverSilentlyFallsBackToMedia()throws Exception{
        String server=source("server/SfcLocalWatchServer"),client=source("client/SfcLocalWatchClient");
        assertTrue(server.contains("pref.mode==SfcLocalWatchNetwork.MEDIA)return true"));
        assertTrue(server.contains("Do not silently fall back"));assertTrue(client.contains("不会自动切换高流量音画"));
        assertTrue(client.contains("literal(\"local\")"));assertTrue(client.contains("literal(\"media\")"));assertTrue(client.contains("literal(\"off\")"));
    }
    @Test void boundedBootstrapAndSharedWindowPreventUnboundedStateQueues()throws Exception{
        String server=source("server/SfcLocalWatchServer"),client=source("client/SfcLocalWatchClient");
        assertTrue(server.contains("MAX_BOOTSTRAPS=4"));assertTrue(server.contains("MAX_OBSERVERS=128"));
        assertTrue(server.contains("CabinetMediaSender.sendPayload"));assertTrue(client.contains("int budget=2"));
        assertTrue(client.contains("UPLOADS.size()>=4"));assertTrue(client.contains("new ArrayBlockingQueue<>(2)"));
    }
    @Test void publicAndPrivatePlaybackCannotShareTheObserverCore()throws Exception{
        String client=source("client/SfcLocalWatchClient");assertTrue(client.contains("SfcHomeClient.currentSession()==null"));
        assertTrue(client.contains("!cn.piq.retro.input.InputOwnership.occupied()"));assertTrue(client.contains("!SfcCoreLease.occupied()"));
        assertTrue(source("client/SfcHomeClient").contains("SfcLocalWatchClient.controlStarting()"));
        assertFalse(source("client/SfcPrivateProvider").contains("SfcLocalWatchClient.busy()"));
        assertTrue(client.contains("PrivateHomeClient.registerBeforeStart"));assertTrue(client.contains("PrivateHomeClient.isActiveOrClosing()"));
        assertTrue(source("client/SfcPrivateEngine").contains("acquireAfterObserver(()->stopping)"));
        assertTrue(source("client/cabinet/SfcCabinetProvider").contains("SfcLocalWatchClient.yieldForControl()"));
    }
    @Test void snapshotPinnedBeforeSlowRomLoadingAndUsesAdminDisplayRanges()throws Exception{
        String server=source("server/SfcLocalWatchServer"),client=source("client/SfcLocalWatchClient");
        assertTrue(server.contains("Pin immediately"));assertTrue(server.contains("&&o.coreReady"));assertTrue(server.contains("o.coreReady=true"));
        assertTrue(client.contains("new SfcLocalWatchNetwork.CaptureFailed(k)"));
        assertTrue(server.contains("GameConsoleAdminSettings.watchRange(p.serverLevel(),d.origin().pos())"));
        assertTrue(server.contains("GameConsoleAdminSettings.exitRange(p.serverLevel(),d.origin().pos())"));
        assertTrue(client.contains("assignment.exitRange()"));
    }
    @Test void observerFailureDoesNotPauseOrStopTheAuthority()throws Exception{
        String client=source("client/SfcLocalWatchClient"),server=source("server/SfcLocalWatchServer");
        assertFalse(server.contains("SfcHomeServer.stop"));assertFalse(server.contains("SfcHomeServer.leave"));
        assertTrue(client.contains("owner.checkpoint(k.frame())"));assertFalse(client.contains("core.saveState()"));
        assertTrue(client.contains("previous.close()"));assertTrue(client.contains("failed=true"));
    }
    @Test void heartbeatAndHardwareRetirementStopOrphanRendering()throws Exception{
        String client=source("client/SfcLocalWatchClient"),server=source("server/SfcLocalWatchServer");
        assertTrue(client.contains("hardwareCurrent()"));assertTrue(client.contains("lastReceived>10_000_000_000L"));
        assertTrue(server.contains("st.tick-pref.heartbeat>120"));assertTrue(server.contains("sfc-local-watch")||source("net/SfcLocalWatchNetwork").contains("sfc-local-watch-1"));
        assertTrue(client.contains("ClientPlayerNetworkEvent.LoggingOut"));
    }
}
