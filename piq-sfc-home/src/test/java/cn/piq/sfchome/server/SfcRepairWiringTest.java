package cn.piq.sfchome.server;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Source wiring supplements (never replaces) the actual two-WASM worker/codec tests. */
class SfcRepairWiringTest {
    String read(String name)throws Exception{return Files.readString(Path.of("src/main/java/cn/piq/sfchome/"+name+".java"));}
    String part(String s,String start,String end){return s.substring(s.indexOf(start),s.indexOf(end,s.indexOf(start)));}
    @Test void bothSnapshotDirectionsShareActualConnectionCompletionWindow()throws Exception{
        String join=read("client/SfcJoinClient"),repair=read("client/SfcRepairClient"),server=read("server/SfcHomeServer");
        for(String text:new String[]{join,repair}){String tick=part(text,"static void tick()","private static void clearTransaction()");assertTrue(tick.contains("CabinetMediaSender.sendPayload(listener.getConnection(),payload,end-at+2048,true))break;at=end"));assertTrue(tick.contains("budget<2"));}
        assertTrue(server.contains("part.length+2048,false))break;r.sentPart(part.length)"));
        assertTrue(server.contains("end-from+2048,false))break;j.sent=end"));
        assertTrue(server.contains("4096,false))break;r.replay+=replay.p1().length"));
    }
    @Test void newProtocolRejectsOldConnectionBeforeCallingServerAndClientHandlers()throws Exception{
        String s=read("net/SfcRepairNetwork");
        for(String check:new String[]{"Object source=c.connection()","h.acceptsConnection(source)","var source=c.connection()","s.connection.getConnection()==source","source.isConnected()","getPlayer(s.getUUID())==s","TrafficPayloadRegistrar.create(e,\"sfc-repair-2\")"})assertTrue(s.contains(check),check);
        for(String record:new String[]{"Digest","Fault","Upload","Restored","Done"})assertTrue(s.contains("r.playToServer("+record+".TYPE,"+record+".CODEC,(p,c)->server(c,"));
    }
    @Test void repairingPortStillConsumesInputSequenceButCannotChangeAuthoritativeButtons()throws Exception{
        String server=read("server/SfcHomeServer"),input=part(server,"private static void acceptInput(","public static void leave(");
        assertTrue(input.contains("endpointFacts(p,lease,s.connection)"));assertTrue(input.contains("held(p,lease)&&!s.repairs.isolated(p.getUUID())"));
        assertTrue(input.contains("s.inputs[port].offer(r.sequence(),input.mask(),input.release())"));
        assertTrue(read("client/SfcHomeClient").contains("!SfcRepairClient.suspended(playback)"));
        assertTrue(read("client/SfcRepairClient").contains("p.synchronizationPaused()"));
    }
    @Test void hostDoesNotPauseOrTakeSynchronousSnapshotWhenOtherEndpointFails()throws Exception{
        String server=read("server/SfcHomeServer"),repair=part(server,"private static void repairTick(","private static void tick(");
        assertFalse(repair.contains("s.clock="));assertFalse(repair.contains("s.frame="));assertFalse(repair.contains("stop("));
        String client=part(read("client/SfcRepairClient"),"@Override public void request(","@Override public void state(");
        assertTrue(client.contains("owner.checkpoint(p.key().frame())"));assertFalse(client.contains("saveState("));
        String worker=read("client/SfcPlayback");assertTrue(worker.contains("checkpoints.put(nextFrame,core.saveState())"));assertTrue(worker.contains("nextFrame%SfcRepairLedger.INTERVAL==0"));
    }
    @Test void resetAndDepartureRetireTransactionsAndKeepOtherPort()throws Exception{
        String server=read("server/SfcHomeServer"),reset=part(server,"private static void reset(","private static boolean hostValid(");
        assertTrue(reset.indexOf("finishRepair(old)")<reset.indexOf("old.epoch+1"));
        String detach=part(server,"private static void detach(","private static void release(MinecraftServer");assertTrue(detach.contains("s.repairs.isolated(lease.player))finishRepair(s)"));assertTrue(detach.contains("s.repairs.forget(lease.player)"));assertFalse(detach.contains("stop("));
        assertTrue(read("client/SfcRepairClient").contains("GUARD.finish(connection,owner,p.key().token())"));
    }
    @Test void sharedSfcExecutionOwnsStateAndFpsForBothModesWithoutDuplicatedCoreClock()throws Exception{
        String generic=read("client/cabinet/SfcCabinetSyncCore"),house=read("client/SfcPlayback"),core=read("client/SfcExecutionCore");
        assertTrue(generic.contains("opened=new SfcExecutionCore()"));assertTrue(house.contains("new SfcExecutionCore()"));assertFalse(generic.contains("Thread.ofPlatform"));
        assertTrue(core.contains("Thread.currentThread()!=owner"));assertTrue(core.contains("expected.equals(digest(saveState()))"));
        assertTrue(generic.contains("p3!=0||p4!=0"));assertTrue(generic.contains("Math.multiplyExact(count,2)"));assertTrue(generic.contains("finally{lease.close();}"));
    }
    @Test void completionIncludesDequeuedBatchAndServerUsesLiveHistoryHead()throws Exception{
        String worker=read("client/SfcPlayback"),ledger=read("server/SfcRepairLedger");
        assertTrue(worker.contains("finishRepairFrame(nextFrame,frames.p1Masks().length)"));
        assertTrue(worker.contains("queued+=batch.p1Masks().length"));
        assertTrue(ledger.contains("long lag=(long)ledger.next-frame"));
        assertTrue(ledger.contains("frame>=resume"));assertTrue(ledger.contains("lag>=0&&lag<=MAX_ACTIVATION_LAG"));
    }
}
