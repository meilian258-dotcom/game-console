package cn.piq.fcarcade.cabinet;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Wiring checks supplement the executed policy/state/gate tests, not network-play evidence. */
class CabinetSnapshotSyncSourceTest {
    private static String read(String name)throws Exception{return Files.readString(Path.of("src/main/java/cn/piq/fcarcade/cabinet/"+name+".java"));}
    @Test void protocolUpgradeRetainsExactConnectionGuardAndWireBounds()throws Exception{
        var s=read("CabinetSyncNetwork");assertTrue(s.contains("TrafficPayloadRegistrar.create(event,\"cabinet-sync-10\")"));assertFalse(s.contains("\"cabinet-sync-7\""));
        assertTrue(s.contains("source.isConnected()&&s.connection.getConnection()==source"));assertTrue(s.contains("s.getServer().getPlayerList().getPlayer(s.getUUID())==s"));
        assertTrue(s.contains("total>CabinetSyncCore.MAX_STATE_BYTES"));assertTrue(s.contains("b.readByteArray(CabinetSyncState.CHUNK)"));
    }
    @Test void pinnedHostIsCheckedBeforeCreatingAuthoritativeTimeline()throws Exception{
        var s=read("CabinetSynchronizer");assertTrue(s.indexOf("!s.policy.acceptsHost(p.identity())")<s.indexOf("s.identity=p;s.timeline="));
        assertTrue(s.contains("CabinetSharedGameService.validatedGameHash(player,m.id,backend)"));assertTrue(s.contains("CabinetSharedGameService.validatedContentId(player,m.id,backend)"));
        assertTrue(s.contains("s.policy.acceptsGuest(expected.identity(),p.identity())"));assertTrue(s.contains("repair(server,s,m,false)"));
    }
    @Test void snapshotAdmissionDoesNotActivateGuestBeforeOriginalAckGate()throws Exception{
        var s=read("CabinetSynchronizer");var hello=s.substring(s.indexOf("static void hello("),s.indexOf("static void input("));
        assertFalse(hello.contains("activateHost"));assertFalse(hello.contains("new CabinetSyncNetwork.Active"));
        assertTrue(s.contains("peer.gate.acknowledge(p.token(),p.frame(),peer.cursor,t.offset==t.state.length"));
        assertTrue(s.contains("s.snapshotFrame<0&&(p.frame()!=0||!p.hash().equals(s.identity.initialHash()))"));
    }
    @Test void capacityIsCheckedBeforeOpeningRoomAndRecheckedForEveryLease()throws Exception{
        var s=read("CabinetRooms");assertTrue(s.indexOf("supported=netplay?CabinetNetplay.maxPlayers(backend):CabinetBackends.syncMaxPlayers(backend)")<s.indexOf("state.ledger.open("));
        assertTrue(s.indexOf("if(capacity==0)")<s.indexOf("state.ledger.open("));
        assertTrue(s.contains("room.mode=mode"));assertTrue(s.contains("room.capacity==CabinetSeats.capacity(CabinetNetplay.active(room.id)?"));
        assertTrue(s.contains("ServerCabinets.valid(host,hostBinding,true)"));assertTrue(s.contains("ServerCabinets.valid(applicant,binding,true)"));
    }
    @Test void settingsRejectOverCapacityWithoutAffectingMediaChoice()throws Exception{
        var s=read("CabinetSyncSettings");assertTrue(s.contains("CabinetSeats.capacity(syncPlayers,secondary!=null"));
        assertTrue(s.contains("packet.mode()==1&&!supported"));assertTrue(s.contains("!ServerCabinets.isCabinetBusy(server,primary)"));
        assertTrue(s.contains("!Objects.equals(secondary,CabinetLinks.peer(server,primary))"));
    }
    @Test void staleCacheRejectsOnlyGuestAndKeepsHostRefreshRoom()throws Exception{
        var s=read("CabinetSynchronizer");var repair=s.substring(s.indexOf("private static void repair("),s.indexOf("static void release("));
        assertTrue(repair.contains("if(m.port==0||"));assertTrue(repair.contains("CabinetSyncRecoveryWindow.available(s.timeline.frame(),s.snapshotFrame)"));
        assertTrue(repair.contains("CabinetRooms.syncClose(server,m,"));assertFalse(repair.contains("s.room.host()"));
        assertTrue(s.contains("if(m.port!=0&&!CabinetSyncRecoveryWindow.available(s.timeline.frame(),transfer.frame))"));
        var ack=s.substring(s.indexOf("static void ack("),s.indexOf("static void digest("));
        assertTrue(ack.indexOf("CabinetSyncRecoveryWindow.available(s.timeline.frame(),t.frame)")<ack.indexOf("peer.gate.acknowledge("));
        assertTrue(s.contains("s.peers.values().stream().anyMatch(v->v.transfer!=null)"));
    }
    @Test void farOrFutureAckIsolatedAfterExactTokenBeforeActivation()throws Exception{
        var s=read("CabinetSynchronizer");var ack=s.substring(s.indexOf("static void ack("),s.indexOf("static void digest("));
        int token=ack.indexOf("!peer.transfer.token.equals(p.token())"),near=ack.indexOf("CabinetSyncRecoveryWindow.canActivate(s.timeline.frame(),p.frame())"),activate=ack.indexOf("peer.gate.acknowledge(");
        assertTrue(token>=0&&token<near&&near<activate);
        assertTrue(ack.substring(near,activate).contains("CabinetRooms.syncClose(player.getServer(),m,"));
        assertFalse(ack.contains("s.room.host()"));
        assertTrue(ack.indexOf("if(m==null||m.port==0)return")<near);
    }
}
