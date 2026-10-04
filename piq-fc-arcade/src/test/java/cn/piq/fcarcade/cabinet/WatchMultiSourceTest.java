package cn.piq.fcarcade.cabinet;

import io.netty.buffer.Unpooled;
import java.util.*;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Pure lease/lane/throttle behavior and real wire codec. Does not claim a live MC/native test. */
class WatchMultiSourceTest {
    private static UUID id(long n){return new UUID(0,n);}
    private static WatchLedger.Source source(long n){return new WatchLedger.Source(id(n),id(n+100));}
    private static WatchLedger.Candidate at(long n,double distance){return new WatchLedger.Candidate(source(n),distance);}
    private static List<WatchLedger.Candidate> candidates(){return List.of(at(1,1),at(2,4),at(3,9),at(4,16),at(5,25));}

    @Test void upToFourIndependentSourcesShareOneConnectionButNeverAToken(){
        var ledger=new WatchLedger();var connection=new Object();
        var leases=ledger.selectMany(id(99),connection,candidates(),4,0);
        assertEquals(4,leases.size());assertEquals(4,leases.stream().map(WatchLedger.Lease::token).distinct().count());
        assertEquals(List.of(source(1),source(2),source(3),source(4)),leases.stream().map(WatchLedger.Lease::source).toList());
        for(int i=1;i<leases.size();i++)assertTrue(leases.get(i).revision()>leases.get(i-1).revision());
        assertThrows(UnsupportedOperationException.class,()->leases.clear());
        assertThrows(IllegalArgumentException.class,()->ledger.selectMany(id(99),connection,candidates(),5,0));
        assertThrows(IllegalArgumentException.class,()->ledger.selectMany(id(99),connection,candidates(),-1,0));
    }
    @Test void duplicateCandidatesCannotConsumeMoreThanOneSlot(){
        var ledger=new WatchLedger();var c=new Object();
        assertEquals(1,ledger.selectMany(id(1),c,List.of(at(1,0),at(1,1),at(1,2)),4,0).size());
    }
    @Test void heartbeatsAndExpiryArePerLeaseNotPerPlayer(){
        var ledger=new WatchLedger();var c=new Object();var leases=ledger.selectMany(id(1),c,candidates(),2,0);
        var a=leases.get(0);var b=leases.get(1);
        assertTrue(ledger.heartbeat(id(1),c,a.token(),a.revision(),99));
        assertNotNull(ledger.authorized(id(1),c,a.token(),a.revision(),100));
        assertNull(ledger.authorized(id(1),c,b.token(),b.revision(),100));
        assertFalse(ledger.heartbeat(id(1),c,b.token(),b.revision(),100));
        var after=ledger.selectMany(id(1),c,candidates(),2,100);
        assertEquals(a.token(),after.getFirst().token());assertNotEquals(b.token(),after.get(1).token());
        assertEquals(199,after.getFirst().expires());
    }
    @Test void releasingOneSourceDoesNotRevokeOrRenewAnother(){
        var ledger=new WatchLedger();var c=new Object();var leases=ledger.selectMany(id(1),c,candidates(),3,0);
        var a=leases.getFirst();var b=leases.get(1);
        assertSame(a,ledger.release(id(1),c,a.token(),a.revision(),10));
        assertEquals(2,ledger.all(id(1)).size());assertSame(b,ledger.authorized(id(1),c,b.token(),b.revision(),10));
        assertNull(ledger.release(id(1),c,a.token(),a.revision(),11));assertEquals(100,b.expires());
    }
    @Test void oldHostAndOldConnectionCleanupCannotRevokeReplacementOrOtherSource(){
        var ledger=new WatchLedger();var c=new Object();var original=ledger.selectMany(id(1),c,List.of(at(1,0),at(2,1)),2,0);
        var replacementSource=new WatchLedger.Source(source(1).id(),id(999));
        var updated=ledger.selectMany(id(1),c,List.of(new WatchLedger.Candidate(replacementSource,0),at(2,1)),2,1);
        assertTrue(updated.contains(original.get(1)));assertTrue(ledger.removeSource(source(1)).isEmpty());
        assertNull(ledger.remove(original.getFirst()));assertEquals(2,ledger.all(id(1)).size());
        var replacement=ledger.selectMany(id(1),new Object(),List.of(new WatchLedger.Candidate(replacementSource,0),at(2,1)),2,2);
        for(var old:updated)assertNull(ledger.remove(old));
        assertEquals(replacement,ledger.all(id(1)));
    }
    @Test void capacityShrinkRetainsExistingLeasesAndDoesNotRefreshTtl(){
        var ledger=new WatchLedger();var c=new Object();var original=ledger.selectMany(id(1),c,candidates(),4,0);
        var reduced=ledger.selectMany(id(1),c,candidates(),2,10);
        assertEquals(original.subList(0,2),reduced);
        assertNull(ledger.authorized(id(1),c,original.get(2).token(),original.get(2).revision(),10));
        assertEquals(100,reduced.getFirst().expires());
        assertEquals(4,ledger.selectMany(id(1),c,candidates(),4,11).size());
        assertTrue(ledger.selectMany(id(1),c,candidates(),0,12).isEmpty());
    }
    @Test void onePlayersSourceRemovalPreservesOtherLeaseOnSamePlayer(){
        var ledger=new WatchLedger();var c=new Object();var leases=ledger.selectMany(id(1),c,candidates(),4,0);
        assertEquals(List.of(leases.get(1)),ledger.removeSource(source(2)));
        assertEquals(List.of(leases.get(0),leases.get(2),leases.get(3)),ledger.all(id(1)));
        assertEquals(3,ledger.removeAll(id(1)).size());assertTrue(ledger.all().isEmpty());
    }
    @Test void sameGlobalEightSourcesAndEightViewersCapsApplyToMultiSourceConnections(){
        var ledger=new WatchLedger();var first=new ArrayList<WatchLedger.Candidate>();var second=new ArrayList<WatchLedger.Candidate>();
        for(int n=1;n<=4;n++){first.add(at(n,n));second.add(at(n+4,n));}
        for(int p=1;p<=8;p++){
            assertEquals(4,ledger.selectMany(id(100+p),new Object(),first,4,0).size());
            assertEquals(4,ledger.selectMany(id(200+p),new Object(),second,4,0).size());
        }
        assertEquals(64,ledger.all().size());
        assertTrue(ledger.selectMany(id(999),new Object(),List.of(at(1,0),at(9,0)),4,0).isEmpty());
        assertEquals(8,ledger.count(source(1)));assertEquals(8,WatchLedger.MAX_SOURCES);assertEquals(8,WatchLedger.MAX_VIEWERS);
    }
    @Test void nearestMediaRemainsSingleEvenWhenNetplayCapacityIsFour(){
        var ledger=new WatchLedger();var c=new Object();var choices=List.of(at(1,0),at(2,1),at(3,2));
        var selection=WatchService.selectLane(ledger,id(1),c,choices,Set.of(source(2),source(3)),4,0);
        assertEquals(1,selection.capacity());assertEquals(List.of(at(1,0)),selection.candidates());
        assertEquals(1,ledger.selectMany(id(1),c,selection.candidates(),selection.capacity(),0).size());
    }
    @Test void activeNetplayLaneCannotAcquireMediaAsAnExtraOrSwitchToCloserMedia(){
        var ledger=new WatchLedger();var c=new Object();ledger.selectMany(id(1),c,List.of(at(2,10)),1,0);
        var selection=WatchService.selectLane(ledger,id(1),c,List.of(at(1,0),at(2,10),at(3,20)),Set.of(source(2),source(3)),4,1);
        assertEquals(4,selection.capacity());assertEquals(List.of(at(2,10),at(3,20)),selection.candidates());
        assertEquals(2,ledger.selectMany(id(1),c,selection.candidates(),selection.capacity(),1).size());
    }
    @Test void zeroNativeBudgetStillAllowsOldMediaButNeverNetplay(){
        var ledger=new WatchLedger();var c=new Object();
        var selection=WatchService.selectLane(ledger,id(1),c,List.of(at(1,0),at(2,1)),Set.of(source(1)),0,0);
        assertEquals(1,selection.capacity());assertEquals(List.of(at(2,1)),selection.candidates());
        assertEquals(0,WatchService.selectLane(ledger,id(1),c,List.of(at(1,0)),Set.of(source(1)),0,0).capacity());
    }
    @Test void throttleAndBackoffAreIndependentAndGenerationBound(){
        var control=new WatchService.Control(null);
        assertTrue(control.heartbeat(id(1),10));assertTrue(control.heartbeat(id(2),10));
        assertFalse(control.heartbeat(id(1),14));assertTrue(control.heartbeat(id(1),15));
        control.block(source(1),50);
        assertFalse(control.allows(source(1),49));assertTrue(control.allows(source(1),50));assertTrue(control.allows(source(2),11));
        assertTrue(control.allows(new WatchLedger.Source(source(1).id(),id(888)),11));
    }
    @Test void budgetReductionsAndDisableApplyImmediatelyWhileIncreasesAreThrottled(){
        var control=new WatchService.Control(null);
        assertTrue(control.available(new WatchNetwork.Available(true,4),0));
        assertTrue(control.available(new WatchNetwork.Available(true,2),1));assertEquals(2,control.capacity);
        assertFalse(control.available(new WatchNetwork.Available(true,4),2));assertEquals(2,control.capacity);
        assertTrue(control.available(new WatchNetwork.Available(false,4),2));assertEquals(0,control.capacity);assertFalse(control.enabled);
        assertFalse(control.available(new WatchNetwork.Available(true,4),3));
        assertTrue(control.available(new WatchNetwork.Available(true,0),7));assertTrue(control.enabled);assertEquals(0,control.capacity);
    }
    @Test void sourceSpecificHookDefaultsToConservativeLegacyParticipation(){
        WatchProvider provider=new WatchProvider(){
            public List<WatchSource> sources(MinecraftServer server){return List.of();}
            public boolean isCurrent(MinecraftServer server,WatchSource source){return false;}
            public boolean isParticipant(MinecraftServer server,UUID player){return player.equals(id(1));}
        };
        assertTrue(provider.isParticipant(null,null,id(1)));assertFalse(provider.isParticipant(null,null,id(2)));
    }
    @Test void trustedPerPlayerLaneChoiceDoesNotGloballyLockTheSource(){
        var ledger=new WatchLedger();var a=new Object();var b=new Object();var choices=List.of(at(1,0),at(2,1));
        var nativeSelection=WatchService.selectLane(ledger,id(1),a,choices,Set.of(source(1),source(2)),4,0);
        assertEquals(2,ledger.selectMany(id(1),a,nativeSelection.candidates(),nativeSelection.capacity(),0).size());
        var mediaSelection=WatchService.selectLane(ledger,id(2),b,choices,Set.of(),4,0);
        assertEquals(1,mediaSelection.capacity());assertEquals(1,ledger.selectMany(id(2),b,mediaSelection.candidates(),mediaSelection.capacity(),0).size());
        assertEquals(2,ledger.all(id(1)).size());assertEquals(2,ledger.count(source(1)));
    }
    @Test void availabilityCodecCarriesBoundedCapacityAndLegacyConstructorStillMeansOne(){
        assertEquals(new WatchNetwork.Available(true,1),new WatchNetwork.Available(true));
        assertEquals(new WatchNetwork.Available(false,0),new WatchNetwork.Available(false));
        for(boolean enabled:new boolean[]{false,true})for(int capacity=0;capacity<=4;capacity++){
            var value=new WatchNetwork.Available(enabled,capacity);var buffer=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
            try{WatchNetwork.Available.CODEC.encode(buffer,value);assertEquals(value,WatchNetwork.Available.CODEC.decode(buffer));assertEquals(0,buffer.readableBytes());}
            finally{buffer.release();}
        }
        for(int capacity:new int[]{-1,5,Integer.MAX_VALUE}){
            assertThrows(IllegalArgumentException.class,()->new WatchNetwork.Available(true,capacity));
            var buffer=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
            try{buffer.writeBoolean(true);buffer.writeVarInt(capacity);assertThrows(IllegalArgumentException.class,()->WatchNetwork.Available.CODEC.decode(buffer));}
            finally{buffer.release();}
        }
        var truncated=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
        try{truncated.writeBoolean(true);assertThrows(RuntimeException.class,()->WatchNetwork.Available.CODEC.decode(truncated));}finally{truncated.release();}
    }
}
