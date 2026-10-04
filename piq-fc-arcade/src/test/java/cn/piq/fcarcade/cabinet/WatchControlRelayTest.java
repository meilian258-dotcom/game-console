package cn.piq.fcarcade.cabinet;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Behavioral budget/callback tests plus source guards; not a Minecraft world/permission test. */
class WatchControlRelayTest {
    private static UUID id(long n){return new UUID(0,n);}
    private static final WatchSource SOURCE=new WatchSource(new WatchDescriptor(
            ResourceLocation.fromNamespaceAndPath("test","home"),id(1),id(2),
            ResourceLocation.fromNamespaceAndPath("minecraft","overworld"),
            new WatchAnchor(BlockPos.ZERO,id(3)),id(4),List.of(new WatchAnchor(new BlockPos(1,0,0),id(5)))),id(6));
    private static CabinetMediaPacket pcm(UUID source,UUID host,long sequence){
        return new CabinetMediaPacket(source,host,sequence,1,0,1,0,0,1,0,4,new byte[]{1,2,3,4});
    }
    private static List<CabinetMediaPacket> batch(){return List.of(pcm(id(1),id(2),0));}
    private static class Provider implements WatchProvider {
        int count,calls;boolean countFails,countLinkageFails,relayFails,relayLinkageFails;
        List<CabinetMediaPacket> delivered;
        Provider(int count){this.count=count;}
        @Override public List<WatchSource> sources(MinecraftServer ignored){return List.of(SOURCE);}
        @Override public boolean isCurrent(MinecraftServer ignored,WatchSource source){return source==SOURCE;}
        @Override public boolean isParticipant(MinecraftServer ignored,UUID player){return false;}
        @Override public int controlRecipients(MinecraftServer ignored,WatchSource source){
            assertSame(SOURCE,source);
            if(countFails)throw new IllegalStateException("provider failure");
            if(countLinkageFails)throw new LinkageError("optional addon mismatch");
            return count;
        }
        @Override public void relayControls(MinecraftServer ignored,WatchSource source,List<CabinetMediaPacket> packets){
            assertSame(SOURCE,source);calls++;delivered=packets;
            if(relayFails)throw new IllegalStateException("uncertain send");
            if(relayLinkageFails)throw new LinkageError("optional addon mismatch");
        }
    }
    private static boolean relay(Provider provider,WatchBudget budget,List<CabinetMediaPacket> packets){
        return WatchService.relayControlBatch(null,provider,SOURCE,packets,budget,0);
    }
    @Test void legacyProviderDefaultsCannotCreateControlDemandOrCallback(){
        WatchProvider legacy=new WatchProvider(){
            @Override public List<WatchSource> sources(MinecraftServer ignored){return List.of(SOURCE);}
            @Override public boolean isCurrent(MinecraftServer ignored,WatchSource source){return true;}
            @Override public boolean isParticipant(MinecraftServer ignored,UUID player){return false;}
        };
        assertEquals(0,legacy.controlRecipients(null,SOURCE));
        var budget=new WatchBudget();
        assertFalse(WatchService.relayControlBatch(null,legacy,SOURCE,batch(),budget,0));
        assertTrue(budget.tryReserve(id(1),0,WatchBudget.SOURCE_BYTES));
    }
    @Test void onlyOneOrTwoExistingControllersAreAdmitted(){
        for(int count:new int[]{Integer.MIN_VALUE,-1,0,3,8,Integer.MAX_VALUE}){
            var provider=new Provider(count);var budget=new WatchBudget();
            assertEquals(0,WatchService.controlRecipients(null,provider,SOURCE));
            assertFalse(relay(provider,budget,batch()));assertEquals(0,provider.calls);
            assertTrue(budget.tryReserve(id(1),0,WatchBudget.SOURCE_BYTES));
        }
        for(int count:new int[]{1,2})assertEquals(count,WatchService.controlRecipients(null,new Provider(count),SOURCE));
    }
    @Test void countCallbackExceptionsAndLinkageErrorsFailClosedWithoutCharge(){
        for(boolean linkage:new boolean[]{false,true}){
            var provider=new Provider(1);provider.countFails=!linkage;provider.countLinkageFails=linkage;
            var budget=new WatchBudget();assertFalse(relay(provider,budget,batch()));assertEquals(0,provider.calls);
            assertTrue(budget.tryReserve(id(1),0,WatchBudget.SOURCE_BYTES));
        }
    }
    @Test void noObserversStillDrivesHostDemandButLegacyWireCountRemainsBounded(){
        assertEquals(0,WatchService.mediaRecipients(0,0));
        assertEquals(1,WatchService.mediaRecipients(0,1));assertEquals(2,WatchService.mediaRecipients(0,2));
        assertEquals(7,WatchService.mediaRecipients(5,2));
        assertEquals(WatchLedger.MAX_VIEWERS,WatchService.mediaRecipients(8,2));
        assertEquals(WatchLedger.MAX_VIEWERS,WatchService.mediaRecipients(Integer.MAX_VALUE,2));
        assertEquals(0,WatchService.mediaRecipients(Integer.MIN_VALUE,Integer.MAX_VALUE));
        assertEquals(3,WatchService.mediaRecipients(3,-1));assertEquals(3,WatchService.mediaRecipients(3,3));
    }
    @Test void everyControlCopyIncludesPayloadHeadersAndUsesTheSameSourceBudget(){
        var provider=new Provider(2);var budget=new WatchBudget();assertTrue(relay(provider,budget,batch()));
        assertEquals(1,provider.calls);assertEquals(1,provider.delivered.size());
        assertArrayEquals(new byte[]{1,2,3,4},provider.delivered.getFirst().data());
        assertTrue(budget.tryReserve(id(1),0,WatchBudget.SOURCE_BYTES-2*(4+256)));
        assertFalse(budget.tryReserve(id(1),0,1));
    }
    @Test void insufficientBudgetCannotPartiallyChargeOrCallTheProvider(){
        var provider=new Provider(2);var budget=new WatchBudget();
        assertTrue(budget.tryReserve(id(1),0,WatchBudget.SOURCE_BYTES-519));
        assertFalse(relay(provider,budget,batch()));assertEquals(0,provider.calls);
        assertTrue(budget.tryReserve(id(1),0,519));assertFalse(budget.tryReserve(id(1),0,1));
    }
    @Test void controlsAndObservationShareTheSameGlobalAndSourceCaps(){
        var provider=new Provider(1);var budget=new WatchBudget();
        for(int source=10;source<13;source++)assertTrue(budget.tryReserve(id(source),0,WatchBudget.SOURCE_BYTES));
        assertTrue(budget.tryReserve(id(1),0,WatchBudget.SOURCE_BYTES-260));
        assertTrue(relay(provider,budget,batch()));
        assertFalse(budget.tryReserve(id(1),0,260)); // Optional observer copy must now be dropped.
        assertFalse(budget.tryReserve(id(99),0,1)); // Controls also consume the shared global cap.
    }
    @Test void failedOrUncertainProviderSendKeepsItsChargeAndIsNotRetried(){
        for(boolean linkage:new boolean[]{false,true}){
            var provider=new Provider(1);provider.relayFails=!linkage;provider.relayLinkageFails=linkage;
            var budget=new WatchBudget();assertFalse(relay(provider,budget,batch()));assertEquals(1,provider.calls);
            assertTrue(budget.tryReserve(id(1),0,WatchBudget.SOURCE_BYTES-260));
            assertFalse(budget.tryReserve(id(1),0,1));
        }
    }
    @Test void wrongGenerationEmptyAndIncompleteBatchesNeverReachControlHook(){
        var first=new CabinetMediaPacket(id(1),id(2),0,0,0,2,128,128,1,0,32768,new byte[24576]);
        var last=new CabinetMediaPacket(id(1),id(2),0,0,1,2,128,128,1,0,32768,new byte[4]);
        var different=new CabinetMediaPacket(id(1),id(2),1,0,1,2,128,128,1,0,32768,new byte[4]);
        var badBatches=new ArrayList<List<CabinetMediaPacket>>();badBatches.add(null);badBatches.add(List.of());
        badBatches.add(List.of(pcm(id(99),id(2),0)));badBatches.add(List.of(pcm(id(1),id(99),0)));
        badBatches.add(List.of(first));badBatches.add(List.of(last,first));badBatches.add(List.of(first,different));
        var nullEntry=new ArrayList<CabinetMediaPacket>();nullEntry.add(null);badBatches.add(nullEntry);
        for(var packets:badBatches){var provider=new Provider(2);var budget=new WatchBudget();
            assertFalse(relay(provider,budget,packets));assertEquals(0,provider.calls);
            assertTrue(budget.tryReserve(id(1),0,WatchBudget.SOURCE_BYTES));}
    }
    @Test void completeMultipartVideoIsChargedAtomicallyAndCallbackListIsImmutable(){
        var packets=new ArrayList<CabinetMediaPacket>();
        packets.add(new CabinetMediaPacket(id(1),id(2),0,0,0,2,128,128,1,0,32768,new byte[24576]));
        packets.add(new CabinetMediaPacket(id(1),id(2),0,0,1,2,128,128,1,0,32768,new byte[4]));
        var provider=new Provider(2);var budget=new WatchBudget();assertTrue(relay(provider,budget,packets));
        packets.clear();assertEquals(2,provider.delivered.size());
        assertThrows(UnsupportedOperationException.class,()->provider.delivered.clear());
        byte[] copy=provider.delivered.getFirst().data();copy[0]=99;assertEquals(0,provider.delivered.getFirst().data()[0]);
        assertTrue(budget.tryReserve(id(1),0,WatchBudget.SOURCE_BYTES-2*(24576+4+2*256)));
        assertFalse(budget.tryReserve(id(1),0,1));
    }
    @Test void uploadAuthenticationAndObserverExclusionRemainAheadOfControlDelivery()throws Exception{
        String source=Files.readString(Path.of("src/main/java/cn/piq/fcarcade/cabinet/WatchService.java"));
        String ingress=source.substring(source.indexOf("static void media("),source.indexOf("static int controlRecipients("));
        int callback=ingress.indexOf("relayControlBatch(");assertTrue(callback>0);
        for(String guard:new String[]{"!current(player)","serverHosted(server,live)","!uploads(live)",
                "!live.source.hostPlayer().equals(player.getUUID())","live.hostConnection!=player.connection.getConnection()",
                "!live.source.descriptor().hostLease().equals(packet.hostMember())","!validSource(server,live)",
                "live.ingress.accept(packet.part(),now(server))","if(complete.isEmpty())return"})
            assertTrue(ingress.indexOf(guard)>=0&&ingress.indexOf(guard)<callback,guard);
        String genericRelay=source.substring(source.indexOf("public static void relay("),source.indexOf("private static int batchBytes("));
        assertFalse(genericRelay.contains("relayControls"));assertFalse(genericRelay.contains("relayControlBatch"));
        assertTrue(source.contains("if(provider.isParticipant(server,player.getUUID()))return false"));
        assertTrue(source.contains("if(state.budget.tryReserve(source,now,bytes))CabinetMediaSender.watchClientbound"));
        String watchClient=Files.readString(Path.of("src/main/java/cn/piq/fcarcade/client/watch/WatchClient.java"));
        assertTrue(watchClient.contains("InputOwnership.occupied()||ClientArcadeEvents.isControlling()"));
        assertTrue(watchClient.contains("r.grant!=null?adapter.blocksNetplay(d):adapter.isParticipant(d)"));
    }
}
