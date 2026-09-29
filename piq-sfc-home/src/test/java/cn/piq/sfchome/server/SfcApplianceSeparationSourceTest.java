package cn.piq.sfchome.server;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Executed wiring guards, not a replacement for a real Minecraft session test. */
class SfcApplianceSeparationSourceTest {
    String source(String name)throws Exception{return Files.readString(Path.of("src/main/java/cn/piq/sfchome/"+name+".java"));}
    String between(String s,String a,String b){return s.substring(s.indexOf(a),s.indexOf(b,s.indexOf(a)));}
    @Test void hostCapabilityCanReadyAndStopButCannotSupplyPortInput()throws Exception{
        String s=source("server/SfcHomeServer");
        String ready=between(s,"public static void controllerReady(","public static void controllerLeave(");
        assertTrue(ready.contains("host(p,s)&&s.host.id.equals(packet.lease())"));
        String stop=between(s,"public static void controllerLeave(","private static void acceptInput(");
        assertTrue(stop.contains("host(p,s)&&s.host.id.equals(packet.lease())"));
        assertTrue(stop.contains("public static void input(ServerPlayer p,SfcHomeNetwork.Input r){}"));
        assertTrue(stop.contains("port<0||!s.ports[port].id.equals(packet.lease())"));
        assertFalse(stop.contains("s.host.id.equals(packet.lease()))acceptInput"));
    }
    @Test void hostLivenessHasExactPlayerConnectionAndDimensionButNoControllerDistance()throws Exception{
        String s=source("server/SfcHomeServer"),host=between(s,"private static boolean hostValid(","private static boolean host(");
        for(String token:new String[]{"getPlayer(p.getUUID())==p","s.host.connection==p.connection.getConnection()","s.host.connection.isConnected()","p.serverLevel()==s.connection.level()","HomeSystems.isCurrent(s.connection)","s.rom.equals","getWorldBorder().isWithinBounds"})assertTrue(host.contains(token),token);
        assertFalse(host.contains("distance"));assertFalse(host.contains("held("));assertFalse(host.contains("validLease"));
        assertTrue(s.contains("l.connection!=p.connection.getConnection()"));
    }
    @Test void returningControllerCannotRevokeHostOrTheOtherPort()throws Exception{
        String s=source("server/SfcHomeServer"),detach=between(s,"private static void detach(","private static void release(MinecraftServer");
        assertTrue(detach.contains("s.inputs[lease.port]=new SfcInputTimeline()"));assertTrue(detach.contains("s.ports[lease.port]=null"));
        assertFalse(detach.contains("s.inputs[0]"));assertFalse(detach.contains("s.inputs[1]"));assertFalse(detach.contains("host="));
        assertTrue(detach.contains("if(!s.host.player.equals(lease.player))SfcHomeNetwork.send(departed,new SfcHomeNetwork.Stopped"));
    }
    @Test void eachRuntimeGetsOneFrameCopyAndOnlyOccupiedPortsNeedHeartbeat()throws Exception{
        String s=source("server/SfcHomeServer"),recipients=between(s,"private static List<ServerPlayer> recipients(","private static Session member(");
        assertTrue(recipients.contains("new LinkedHashSet<UUID>()"));assertTrue(recipients.contains("ids.add(s.host.player)"));assertTrue(recipients.contains("if(l!=null)ids.add(l.player)"));
        String tick=between(s,"private static void tick(","private static void stop(");assertTrue(tick.contains("for(var player:recipients(server,s)){"));assertTrue(tick.contains("repair.player.equals(player.getUUID())&&repair.phase!=SfcRepairLedger.Phase.DONE)continue;SfcHomeNetwork.send(player,packet)"));
        String repair=between(s,"private static void startRepair(","public static void repairUpload(");assertTrue(repair.contains("mismatch.player().equals(s.host.player))return"));
        assertTrue(tick.contains("if(l!=null&&s.health.expiredPort(l.port,st.tick))release"));
    }
    @Test void eitherVacantPortCanJoinButOnlyHostMayApproveAndCapture()throws Exception{
        String s=source("server/SfcHomeServer");
        assertTrue(s.contains("requestedPort>=SfcCartridgeData.maxPlayers"));assertTrue(s.contains("s.ports[requestedPort]!=null"));
        String decide=between(s,"public static void decideJoin(","private static void joinReady(");assertTrue(decide.contains("if(!host(p,s)||s.join==null)return"));
        assertTrue(decide.contains("j.second=grant(other,(SfcHomeConsoleBlockEntity)s.connection.console(),j.port,st)"));
        String runtime=between(s,"private static void sendRuntime(","private static void reset(");assertTrue(runtime.contains("host?-1:lease.port"));assertTrue(runtime.contains("host?s.host.id:lease.id,host"));
    }
    @Test void backgroundMediaDoesNotRequireLocallyLoadedClientEndpoints()throws Exception{
        String publisher=source("client/SfcWatchPublisher");assertTrue(publisher.contains("owner.session.executionHost()"));assertTrue(publisher.contains("SfcHomeClient.isCurrent(owner)"));
        assertTrue(publisher.contains("client.getConnection()==connection&&connection.isConnected()"));assertTrue(publisher.contains("controllerLease().equals(descriptor.hostLease())"));
        assertFalse(publisher.contains("SfcWatchClient.hardwareCurrent("));
        assertTrue(source("client/SfcWatchClient").contains("hardwareCurrent(")); // Receivers still validate actual loaded TV.
    }
    @Test void televisionVolumeIsAppliedToBothLocalAndSpectatorAudio()throws Exception{
        assertTrue(source("client/SfcHomeClient").contains("HomeApplianceService.audioGain(mc.level,playback.session.tvPos())"));
        assertTrue(source("client/SfcWatchClient").contains("HomeApplianceService.audioGain("));
    }
    @Test void physicalButtonsPrecedeShiftEjectionButPreserveCardsAndWiringTools()throws Exception{
        String s=source("server/SfcHomeServer"),direct=between(s,"public static void interactDirect(","private static void interact(");
        assertTrue(direct.indexOf("HomeApplianceService.tryButton(")<direct.indexOf("p.isShiftKeyDown()"));
        assertTrue(direct.indexOf("if(SfcCartridgeData.isCartridge(held))")<direct.indexOf("HomeApplianceService.tryButton("));
        String block=source("world/SfcHomeConsoleBlock"),use=between(block,"@Override protected ItemInteractionResult useItemOn(","@Override protected void onRemove(");
        assertTrue(use.contains("!connectionTool(item)&&player instanceof ServerPlayer server"));
        for(String tool:new String[]{"AvCableItem","CabinetLinkCableItem","ZapperStandCableItem","HomeZapperItem"})assertTrue(use.contains(tool));
        assertTrue(use.contains("ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION"));
    }
}
