package cn.piq.fcarcade.cabinet;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Integration contracts supplement actual pure state tests; no Minecraft world is claimed. */
class CabinetSingleSeatSourceTest {
    private static String source(String name)throws Exception{return Files.readString(Path.of("src/main/java/cn/piq/fcarcade/"+name+".java"));}
    private static String method(String source,String start,String end){return source.substring(source.indexOf(start),source.indexOf(end,source.indexOf(start)));}
    @Test void capabilityOneDoesNotWeakenCommonRegistrationOrOldNesReservation()throws Exception{
        var s=source("cabinet/CabinetBackends");assertTrue(s.contains("maxPlayers < 1 || maxPlayers > 4"));assertTrue(s.contains("entry.localOnly() || id.equals(NES)"));assertTrue(s.contains("NETWORK_PLAYERS.containsKey(id)"));
    }
    @Test void noConsentGateOrApplicantTransactionCanBeCreatedForSingleSeat()throws Exception{
        var s=source("cabinet/CabinetRooms");assertTrue(s.contains("if(room.capacity>1)state.joins.put"));var join=method(s,"private static void requestJoin(","private static boolean validPending(");
        assertTrue(join.indexOf("if(room.capacity==1)")<join.indexOf("state.joins.get"));assertTrue(join.contains("附近旁观"));
        assertTrue(s.contains("CabinetSeats.capacity(supported,secondary!=null"));assertTrue(s.contains("room.capacity==CabinetSeats.capacity(CabinetNetplay.active(room.id)?CabinetNetplay.maxPlayers"));
        assertTrue(s.contains(":room.mode==CabinetSyncMode.LOCAL_SYNC?CabinetBackends.syncMaxPlayers"));
        assertTrue(s.contains(":CabinetBackends.maxPlayers(ResourceLocation.parse(room.backend))"));
    }
    @Test void hostOnlyAndWatchPathsRemainSharedWithoutGivingObserversASeat()throws Exception{
        var s=source("cabinet/CabinetRooms");assertTrue(s.contains("for(var room:state.ledger.all())if(room.ready)"));assertTrue(s.contains("WatchService.relay(server,room.id,room.host().id,batch)"));assertTrue(s.contains("WatchService.closed(server,room.id,room.host().id)"));
        var media=method(s,"static void media(","static boolean heartbeat(");assertTrue(media.contains("member==null||member.port!=0"));assertTrue(media.contains("if(!room.ready)return"));
        assertTrue(s.contains("!ServerCabinets.valid(player,binding,false)"));assertTrue(s.contains("now(server)>=member.expires"));assertTrue(s.contains("player.connection.getConnection().isConnected()"));
    }
    @Test void clientRejectsOutOfCapacitySeatAndButtonsBeforeCoreCallsAndSuppressesSingleSeatConsent()throws Exception{
        var s=source("client/cabinet/CabinetClientBackends");var seat=method(s,"@Override public void seat(","public static void watchDemand(");var input=method(s,"@Override public void buttons(","@Override public void media(");
        assertTrue(seat.indexOf("message.port()>=room.capacity()")<seat.indexOf("peers.seat("));assertTrue(input.indexOf("message.port()>=room.capacity()")<input.indexOf("peers.input("));
        assertTrue(s.contains("room!=null&&room.capacity()>1&&(hosted()?moderator&&room.member().equals(hostMember):room.port()==0&&room.hostMember().equals(hostMember))"));assertTrue(s.contains("room.secondary()==null?room.capacity()"));assertTrue(s.contains("ready.maxPlayers()<room.capacity()"));
    }
    @Test void changedLegalCapacityRequiresNewMandatoryWireVersionAndKeepsExactIdentityChecks()throws Exception{
        var s=source("cabinet/CabinetRoomNetwork");assertTrue(s.contains("TrafficPayloadRegistrar.create(event,\"cabinet-room-10\")"));assertFalse(s.contains("\"cabinet-room-9\""));
        assertTrue(s.contains("!CabinetSeats.validCapacity("));assertTrue(s.contains("port>=capacity"));assertTrue(s.contains("(port==0)!=member.equals(hostMember)"));assertTrue(s.contains("sink.acceptsConnection(source)"));
    }
}
