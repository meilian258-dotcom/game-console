package cn.piq.fcarcade.cabinet;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Narrow wiring checks supplement executable Gate/Ledger and real codec tests; not a world simulation. */
class CabinetJoinSourceContractTest {
    @Test void fcUsesOneSessionConsentAndOtherBackendsRetainApproval()throws Exception {
        String text=source("CabinetRooms");
        assertTrue(text.contains("CabinetBackends.NES.toString().equals(room.backend)"));
        int direct=text.indexOf("if(directJoin(room)){");
        int approval=text.indexOf("new CabinetJoinNetwork.Approval(",direct);
        assertTrue(direct>=0&&approval>direct);
        String bypass=text.substring(direct,approval);
        assertTrue(bypass.contains("decide(host,new CabinetJoinNetwork.Decision("));
        assertTrue(bypass.contains("return;"));
    }
    static String source(String name)throws Exception {
        return Files.readString(Path.of("src/main/java/cn/piq/fcarcade/cabinet/"+name+".java"));
    }
    @Test void clickCannotGrantSeatBeforeConsent()throws Exception {
        String text=source("CabinetRooms");
        String click=text.substring(text.indexOf("static void interact("),text.indexOf("static CabinetTarget validateLease("));
        assertTrue(click.contains("requestJoin(player,state,room,binding)"));assertFalse(click.contains("ledger.join("));
        String decision=text.substring(text.indexOf("static void decide("),text.indexOf("private static void finishPending("));
        assertTrue(decision.indexOf("gate.decide(")<decision.indexOf("validPending("));
        assertTrue(decision.indexOf("validPending(")<decision.indexOf("state.ledger.join("));
        assertTrue(decision.contains("pending.port(),pending.port()+1"));
    }
    @Test void bothPermissionCallbacksAreFollowedByOriginalTransactionRevalidation()throws Exception {
        String text=source("CabinetRooms");
        String valid=text.substring(text.indexOf("private static boolean validPending("),text.indexOf("static void decide("));
        assertTrue(valid.contains("request.pending()!=pending||state.pending.get(room.id)!=request"));
        assertTrue(valid.contains("applicant.connection.getConnection()!=request.connection()"));
        assertTrue(valid.contains("!current(applicant)"));
        assertTrue(valid.contains("validateLease(host,room.host().id"));
        String current=text.substring(text.indexOf("private static boolean current("),text.indexOf("private static void notice("));
        assertTrue(current.contains("!player.hasDisconnected()&&player.connection.getConnection().isConnected()"));
        String lease=text.substring(text.indexOf("static CabinetTarget validateLease("),text.indexOf("private static boolean topology("));
        assertTrue(lease.contains("if(!current(player)"));
        assertTrue(valid.contains("ServerCabinets.valid(host,hostBinding,true)"));
        assertTrue(valid.contains("ServerCabinets.valid(applicant,binding,true)"));
        assertTrue(valid.indexOf("ServerCabinets.valid(applicant,binding,true)")<valid.indexOf("return validPending(server,state,room,pending,request,false)"));
    }
    @Test void cancellationAndExceptionCleanupCannotEraseReplacementRequest()throws Exception {
        String text=source("CabinetRooms");
        String finish=text.substring(text.indexOf("private static void finishPending("),text.indexOf("static void input("));
        assertTrue(finish.contains("if(pending==null)return"));
        assertTrue(finish.contains("request.pending()!=pending||!state.pending.remove(room.id,request)"));
        assertFalse(finish.contains("state.pending.remove(room.id);"));
        assertTrue(text.contains("gate.pending()!=null||state.pending.containsKey(room.id)"));
        assertTrue(text.contains("if(request!=null&&state.pending.get(room.id)==request)finishPending("));
        assertTrue(text.contains("gate.cancel(player.getUUID());finishPending(server,state,pendingRoom,entry.getValue().pending()"));
        assertTrue(text.contains("if(gate.pending()==pending)gate.clear();finishPending(server,state,room,pending,\"申请条件"));
        assertTrue(text.contains("if(gate.pending()==pending)gate.clear();finishPending(server,state,room,pending,\"加入申请条件"));
    }
    @Test void newPacketsAreAdditiveAndQueueActualConnectionIdentity()throws Exception {
        String network=source("CabinetJoinNetwork");
        assertTrue(network.contains("TrafficPayloadRegistrar.create(event,\"cabinet-join-2\")"));
        assertTrue(network.contains("player.connection.getConnection() == source"));
        String server=network.substring(network.indexOf("private static void server("),network.indexOf("private static void client("));
        assertTrue(server.indexOf("context.enqueueWork(")<server.indexOf("source.isConnected()"));
        assertTrue(server.contains("source != null && source.isConnected() && player.connection.getConnection() == source"));
        assertTrue(network.contains("sink.acceptsConnection(source)"));
        assertFalse(network.contains("readBlockPos"));assertFalse(network.contains("net.minecraft.client"));
        assertTrue(source("CabinetNetwork").contains("CabinetJoinNetwork.register(event)"));
        // Mixed physical seat ranges require the new mandatory room cohort; join consent stays additive.
        assertTrue(source("CabinetRoomNetwork").contains("TrafficPayloadRegistrar.create(event,\"cabinet-room-10\")"));
    }
}
