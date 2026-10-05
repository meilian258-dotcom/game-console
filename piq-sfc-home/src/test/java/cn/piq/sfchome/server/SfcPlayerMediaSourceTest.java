package cn.piq.sfchome.server;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Wiring guards supplement actual codec and receive-worker tests; not a Minecraft integration test. */
class SfcPlayerMediaSourceTest {
    private String read(String name)throws Exception{return Files.readString(Path.of("src/main/java/cn/piq/sfchome/"+name+".java"));}
    private String part(String source,String start,String end){return source.substring(source.indexOf(start),source.indexOf(end,source.indexOf(start)));}
    @Test void serverCapturesModeAndHonorsPlayerHostingConfiguration()throws Exception{
        String s=read("server/SfcHomeServer");
        assertTrue(s.contains("CabinetHostingConfig.playerAllowed()?1:0"));
        assertTrue(s.contains("var mode=c.synchronizationMode();var checked=preflight(p,c,true)"));
        assertTrue(s.contains("c.synchronizationMode()!=mode||!modeSupported(c)"));
        assertTrue(s.contains("st.tick,mode"));assertTrue(s.contains("st.tick,old.mode"));
        assertTrue(s.contains("host,run==null?s.mode.ordinal():3,s.watchSource,s.host.id"));
        assertTrue(read("net/SfcHomeNetwork").contains("TrafficPayloadRegistrar.create(event,\"13\")"));
    }
    @Test void receiverBypassesRomAndCoreButHostKeepsOrderedInput()throws Exception{
        String c=read("client/SfcHomeClient"),worker=read("client/SfcPlayback"),s=read("server/SfcHomeServer");
        int bypass=c.indexOf("if(message.receivesMedia()){begin(message,new byte[0]);return;}");
        assertTrue(bypass>=0&&bypass<c.indexOf("SfcClientFiles.cachedRom"));
        assertTrue(c.contains("!waiting.receivesMedia()&&SfcCoreLease.occupied()"));
        // ROM chunks now also serve independent observers while this player receives MEDIA.
        // Only an explicitly queued, exact control reader may progress into control playback.
        assertTrue(bypass<c.indexOf("if(bytes==null)downloadRom(message,connection)"));
        String romChunks=part(c,"@Override public void romChunk(","@Override public void frames(");
        assertTrue(romChunks.contains("SfcNetplayWatchContent.chunk(chunk)"));
        assertTrue(romChunks.contains("if(romDownload!=null&&startup!=null&&romDownload.total()>0)"));
        for(String forbidden:new String[]{"begin(","new SfcPlayback", "cacheRom(","DOWNLOADS.request("})assertFalse(romChunks.contains(forbidden),forbidden);
        String loader=part(c,"private static void downloadRom(","private static boolean hardwareCurrent(");
        assertTrue(loader.contains("DOWNLOADS.request(expected.romSha(),transport)"));
        assertTrue(loader.contains("if(!waitingCurrent(expected,connection)||romDownload!=reader)return;"));
        assertTrue(loader.contains("SfcClientFiles.cacheRom(game,expected.romSha(),bytes)"));
        assertTrue(c.contains("!playback.session.receivesMedia()"));
        String legacy=worker.substring(worker.indexOf("if(session.receivesMedia())"));
        assertTrue(legacy.indexOf("lease=null;")<legacy.indexOf("lease=SfcCoreLease.acquire()"));
        assertTrue(s.contains("if(s.playerMedia()&&!host(player,s))continue"));
        assertTrue(s.contains("SfcPlaybackMode.permitsRom(s.mode.ordinal(),host(p,s))"));
    }
    @Test void mediaJoinCommitsAfterConsentAndPermissionWithoutSnapshotPause()throws Exception{
        String s=read("server/SfcHomeServer");
        String ready=part(s,"private static void joinReady(","private static boolean paused(");
        String media=part(ready,"if(s.hosted!=null||s.playerMedia()||NETPLAY.containsKey(s))","if(!joinValid(p.getServer(),s,true)||!ready.romSha()");
        assertTrue(media.contains("!joinValid(p.getServer(),s,true)"));
        assertTrue(media.contains("!endpointFacts(p,j.second,s.connection)"));
        assertTrue(media.contains("s.ports[j.port]=j.second"));assertTrue(media.contains("s.ready[j.port]=ready"));
        assertFalse(media.contains("Capture("));assertFalse(media.contains("s.frame="));
        assertFalse(media.contains("initialStateHash()"));
        assertTrue(s.contains("if(!j.gate.approve(p.getUUID(),packet.token(),packet.accepted(),st.tick))return"));
    }
    @Test void streamForwardingRequiresCurrentGenerationAndActiveRemoteLease()throws Exception{
        String s=read("server/SfcHomeServer"),p=read("server/SfcWatchProvider");
        String selection=part(s,"private static Session playerMediaSession(","public static void register()");
        for(String requirement:new String[]{"!server.isSameThread()","!watchSources(server).contains(source)","s.playerMedia()&&s.hosted==null","source.descriptor().hostLease()","s.ready[port]==null","lease.player.equals(s.host.player)","endpointFacts(player,lease,s.connection)","endpointFacts(target,lease,s.connection)"})assertTrue(selection.contains(requirement),requirement);
        assertTrue(selection.contains("SfcHostedNetwork.send(target.connection.getConnection(),s.id,s.epoch,lease.id,batch)"));
        assertTrue(p.contains("controlRecipients(MinecraftServer server,WatchSource source)"));
        assertTrue(p.contains("relayControls(MinecraftServer server,WatchSource source"));
        assertFalse(selection.contains("grant("));assertFalse(selection.contains("loadState("));
    }
    @Test void mediaParticipantsCannotStartStateRepairOrSnapshotTransfer()throws Exception{
        String s=read("server/SfcHomeServer"),w=read("client/SfcPlayback"),j=read("client/SfcJoinClient");
        assertTrue(s.contains("s.clock==null||s.hosted!=null||s.playerMedia())return null"));
        assertTrue(w.contains("session.checksState()&&host.consistencyChecks()"));
        assertTrue(read("client/SfcRepairClient").contains("p.session.checksState()"));
        assertTrue(j.contains("!playback.session.checksState()||!playback.session.executionHost()"));
        assertTrue(j.contains("!playback.session.checksState()||playback.session.executionHost()"));
    }
    @Test void disconnectStopsWholeRuntimeWithoutTransferringOrResettingProgress()throws Exception{
        String s=read("server/SfcHomeServer");
        assertTrue(s.contains("if(!valid){stop(st,s.connection.consoleId(),\"运行宿主、主机或交互权限已改变\");continue;}"));
        assertTrue(s.contains("s.host.connection==p.connection.getConnection()"));
        String stop=part(s,"private static void stop(","private static void detach(");
        assertTrue(stop.contains("st.sessions.remove(console)"));assertTrue(stop.contains("new SfcHomeNetwork.Stopped"));
        assertFalse(stop.contains("new Host("));assertFalse(stop.contains("new Session("));
        assertTrue(read("client/SfcPlayback").contains("if(session.executionHost() && nextFrame>0) backup(core,nextFrame)"));
        assertTrue(read("client/SfcClientFiles").contains("SfcRecoveryBackups.save(game,sha,backupSession,state,sram,frame)"));
        assertTrue(read("client/SfcRecoveryBackups").contains("not auto-loaded or a server save"));
        assertTrue(read("client/SfcPlayback").contains("backupSession=java.util.UUID.randomUUID()"));
    }
    @Test void controllerInputsStillCarryLeaseAndNeutralizeOnLoss()throws Exception{
        String s=read("server/SfcHomeServer"),c=read("client/SfcHomeClient");
        assertTrue(s.contains("!s.ports[port].id.equals(packet.lease())"));
        assertTrue(s.contains("SfcControllerAuthority.input(held(p,lease)"));
        assertTrue(s.contains("s.health.neutralizeStalePort(l.port,st.tick)"));
        assertTrue(s.contains("s.inputs[l.port].neutralizeStale()"));
        assertTrue(c.contains("new cn.piq.sfchome.net.SfcJoinNetwork.ControllerInput(controlLease,input)"));
        assertTrue(c.contains("if(force||!active){mask=0;INPUT_FOCUS.suspend()"));
    }
    @Test void currentModeReportedWithoutGuessingFromSettings()throws Exception{
        String s=part(read("client/SfcHomeClient"),"private static java.util.List<cn.piq.fcarcade.client.NetworkDiagnosticsView.Device> diagnosticDevices()","static boolean sessionCurrent(");
        assertTrue(s.contains("s.playerHosted()?cn.piq.fcarcade.client.NetworkDiagnosticsView.Mode.PLAYER_MEDIA"));
        assertFalse(s.contains("synchronizationMode()"));
    }
    @Test void requiredMediaFailureStopsHostButOptionalWatchFailureDoesNot()throws Exception{
        String worker=read("client/SfcPlayback"),publisher=read("client/SfcWatchPublisher");
        assertTrue(worker.contains("if(session.playerHosted())throw new IllegalStateException"));
        assertTrue(worker.contains("if(!running||!session.playerHosted()||!session.executionHost())return"));
        String fail=part(worker,"void playerMediaFailed()","ResourceLocation textureId()");
        assertTrue(fail.contains("running=false;thread.interrupt()"));assertTrue(fail.contains("error="));
        assertTrue(publisher.contains("if(current.stream.error()!=null){failed(current);return;}"));
        assertTrue(publisher.contains("current.owner.playerMediaFailed()"));
        assertTrue(read("client/SfcHomeClient").contains("if(playback.error()!=null){leave(playback.error());return;}"));
    }
}
