package cn.piq.fcarcade.cabinet;

import io.netty.buffer.Unpooled;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WatchManagementPreferenceTest {
    private static UUID id(long n){return new UUID(0,n);}
    private static WatchPreferenceState.Source key(long n){return new WatchPreferenceState.Source(ResourceLocation.parse("test:machine"),id(n),id(n+100));}
    private static WatchLedger.Source source(long n){return new WatchLedger.Source(id(n),id(n+100));}
    private static RegistryFriendlyByteBuf buffer(){return new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);}
    @Test void manualPauseDoesNotExpireWithTheFortyTickReleaseBackoff(){
        var prefs=new WatchPreferenceState();var connection=new Object();prefs.connection(connection);
        var ledger=new WatchLedger();var choices=List.of(new WatchLedger.Candidate(source(1),0),new WatchLedger.Candidate(source(2),1));
        var leases=ledger.selectMany(id(9),connection,choices,2,0);var a=leases.getFirst();var b=leases.get(1);
        boolean authorized=ledger.authorized(id(9),connection,a.token(),a.revision(),1)!=null;
        assertEquals(WatchPreferenceState.Result.APPLIED,prefs.change(connection,1,key(1),true,authorized));ledger.remove(a);
        for(int tick:new int[]{40,41,80,1000}){
            var filtered=choices.stream().filter(c->!prefs.paused(key(c.source().id().getLeastSignificantBits()))).toList();
            var after=ledger.selectMany(id(9),connection,filtered,2,tick);
            assertEquals(1,after.size());assertEquals(b.source(),after.getFirst().source());
        }
        assertEquals(WatchPreferenceState.Result.APPLIED,prefs.change(connection,2,key(1),false,false));
        assertEquals(2,ledger.selectMany(id(9),connection,choices,2,1001).size());
    }
    @Test void wrongPlayerOrConnectionCannotCreateAClientSelectedPause(){
        var c=new Object();var wrong=new Object();var ledger=new WatchLedger();var lease=ledger.select(id(1),c,List.of(new WatchLedger.Candidate(source(1),0)),true,0);
        var prefs=new WatchPreferenceState();prefs.connection(c);
        assertEquals(WatchPreferenceState.Result.UNAUTHORIZED,prefs.change(c,1,key(1),true,ledger.authorized(id(2),c,lease.token(),lease.revision(),0)!=null));
        assertEquals(WatchPreferenceState.Result.STALE,prefs.change(wrong,2,key(1),true,true));assertTrue(prefs.paused().isEmpty());
        assertEquals(WatchPreferenceState.Result.APPLIED,prefs.change(c,2,key(1),true,true));
        assertEquals(WatchPreferenceState.Result.APPLIED,prefs.change(c,3,key(1),true,false)); // Repeat one's own negative preference only.
    }
    @Test void sequenceAndCompleteSourcePreventLateOrCrossProviderMutations(){
        var p=new WatchPreferenceState();var c=new Object();p.connection(c);p.change(c,1,key(1),true,true);p.change(c,2,key(1),false,false);
        assertEquals(WatchPreferenceState.Result.STALE,p.change(c,1,key(1),true,true));assertFalse(p.paused(key(1)));
        p.change(c,3,key(1),true,true);
        var otherProvider=new WatchPreferenceState.Source(ResourceLocation.parse("test:other"),key(1).source(),key(1).hostLease());
        var otherHost=new WatchPreferenceState.Source(key(1).provider(),key(1).source(),id(999));
        p.change(c,4,otherProvider,false,false);p.change(c,5,otherHost,false,false);assertTrue(p.paused(key(1)));
        assertFalse(p.paused(otherProvider));assertFalse(p.paused(otherHost));
    }
    @Test void sixtyFourKnownSourcesAreBoundedAndUnknownSpamCannotFillTheSet(){
        var p=new WatchPreferenceState();var c=new Object();p.connection(c);long seq=0;
        for(int i=0;i<100;i++)assertEquals(WatchPreferenceState.Result.UNAUTHORIZED,p.change(c,++seq,key(i),true,false));
        assertTrue(p.paused().isEmpty());
        for(int i=0;i<64;i++)assertEquals(WatchPreferenceState.Result.APPLIED,p.change(c,++seq,key(i),true,true));
        assertEquals(WatchPreferenceState.Result.FULL,p.change(c,++seq,key(64),true,true));assertEquals(64,p.paused().size());
        p.change(c,++seq,key(0),false,false);assertEquals(WatchPreferenceState.Result.APPLIED,p.change(c,++seq,key(64),true,true));
        p.connection(new Object());assertTrue(p.paused().isEmpty());assertEquals(0,p.sequence());
    }
    @Test void preferenceAndAcknowledgementCodecCarryFullIdentityAndIntent(){
        for(boolean paused:new boolean[]{true,false}){var b=buffer();try{
            var request=new WatchNetwork.Preference(12,key(4),88,id(99),paused);WatchNetwork.Preference.CODEC.encode(b,request);assertEquals(request,WatchNetwork.Preference.CODEC.decode(b));
            var response=new WatchNetwork.PreferenceResult(12,key(4),paused,true,"本连接/本局");WatchNetwork.PreferenceResult.CODEC.encode(b,response);assertEquals(response,WatchNetwork.PreferenceResult.CODEC.decode(b));
            assertEquals(0,b.readableBytes());
        }finally{b.release();}}
    }
    @Test void malformedSequencesAndOversizedReasonsFailBeforeServiceMutation(){
        assertThrows(IllegalArgumentException.class,()->new WatchNetwork.Preference(0,key(1),1,id(1),true));
        assertThrows(IllegalArgumentException.class,()->new WatchNetwork.Preference(1,key(1),0,id(1),true));
        assertThrows(IllegalArgumentException.class,()->new WatchNetwork.PreferenceResult(1,key(1),true,false,"x".repeat(129)));
        var b=buffer();try{b.writeVarLong(-1);b.writeUtf("test:machine");b.writeUUID(id(1));b.writeUUID(id(2));b.writeVarLong(1);b.writeUUID(id(3));b.writeBoolean(true);
            assertThrows(IllegalArgumentException.class,()->WatchNetwork.Preference.CODEC.decode(b));}finally{b.release();}
    }
    @Test void productionHooksUseAuthenticatedServerRelationshipAndRealClosingOwners()throws Exception{
        var dir=Path.of("src/main/java/cn/piq/fcarcade");var network=Files.readString(dir.resolve("cabinet/WatchNetwork.java"));var server=Files.readString(dir.resolve("cabinet/WatchService.java"));
        var client=Files.readString(dir.resolve("client/watch/WatchClient.java"));var screen=Files.readString(dir.resolve("client/watch/WatchManagementScreen.java"));
        assertTrue(network.contains("create(event,\"watch-4\")"));assertFalse(network.contains("create(event,\"watch-3\")"));
        assertTrue(network.contains(".playToServer(Preference.TYPE,Preference.CODEC"));assertTrue(network.contains(".playToClient(PreferenceResult.TYPE,PreferenceResult.CODEC"));
        assertTrue(server.contains("state.ledger.authorized(player.getUUID(),c.connection,packet.lease(),packet.revision(),now)"));
        assertTrue(server.contains("WatchPreferenceState.Source.of(live.source.descriptor()).equals(packet.source())"));
        assertTrue(server.contains("!c.preferences.paused(WatchPreferenceState.Source.of(live.source.descriptor()))"));
        assertTrue(client.contains("WatchManagementState.released(task==null||task.done(),netplay==null||netplay.terminated().isDone(),netplay!=null&&netplay.nativeSlotHeld())"));
        assertTrue(client.contains("if(MANAGEMENT.paused(WatchPreferenceState.Source.of(start.descriptor())))"));
        assertTrue(screen.contains("getConnection().getConnection()==connection"));assertFalse(screen.contains("NetplaySave"));assertFalse(screen.contains("InputOwnership"));
    }
}
