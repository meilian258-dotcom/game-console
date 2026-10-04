package cn.piq.fcarcade.client.watch;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WatchPresentationPolicyTest {
    @Test void fourObserversKeepStableTotalCapacityAsTheyReserveSlots(){
        for(int held=0;held<=4;held++)assertEquals(4,WatchPresentationPolicy.capacity(4-held,held,0));
    }
    @Test void existingControllerKeepsItsSlotAndPendingControllerHasPriority(){
        assertEquals(3,WatchPresentationPolicy.capacity(0,3,0,0));
        assertEquals(3,WatchPresentationPolicy.capacity(4,0,0,1));
        assertEquals(2,WatchPresentationPolicy.capacity(1,2,0,1));
        assertEquals(0,WatchPresentationPolicy.capacity(0,0,0,1));
    }
    @Test void fullNativeWatchSetIsNotEvictedByANewControlAttempt(){
        assertEquals(4,WatchPresentationPolicy.capacity(0,4,0,1));
        assertEquals(4,WatchPresentationPolicy.capacity(0,4,0,4));
        // Existing controller already owns the fourth slot: no pending claim for it, no double deduction.
        assertEquals(3,WatchPresentationPolicy.capacity(0,3,0,0));
        // One not-yet-reserved watcher may yield its claim, but three already running watches remain.
        assertEquals(3,WatchPresentationPolicy.capacity(1,3,0,1));
    }
    @Test void oneComponentMayHaveSeveralPendingControllersAndSumStaysBounded(){
        assertEquals(3,WatchPresentationPolicy.pendingControls(List.of(()->3)));
        assertEquals(1,WatchPresentationPolicy.capacity(4,0,0,WatchPresentationPolicy.pendingControls(List.of(()->3))));
        assertEquals(4,WatchPresentationPolicy.pendingControls(List.of(()->3,()->2)));
        assertEquals(0,WatchPresentationPolicy.pendingControls(List.of(()->-1)));
        assertEquals(4,WatchPresentationPolicy.pendingControls(List.of(()->100)));
    }
    @Test void BrokenControlQueryNeverAdvertisesThoseSlotsAsFree(){
        assertEquals(4,WatchPresentationPolicy.pendingControls(List.of(()->{throw new IllegalStateException("unavailable");})));
        assertEquals(4,WatchPresentationPolicy.pendingControls(List.of(()->{throw new NoClassDefFoundError("incompatible addon");})));
    }
    @Test void nativeClosingAndInterruptedPreparationCannotPretendToBeFree(){
        // One native closing slot is already absent from free, two live watches are held.
        assertEquals(3,WatchPresentationPolicy.capacity(1,2,0));
        // A cancelled loader has no native token yet but still retains its Java claim.
        assertEquals(3,WatchPresentationPolicy.capacity(2,2,1));
        assertEquals(2,WatchPresentationPolicy.capacity(2,2,1,1));
        assertEquals(4,WatchPresentationPolicy.capacity(2,2,0));
    }
    @Test void onlyNearestReadySourceSpeaksAndOperatingMutesAll(){
        var items=List.of(new WatchPresentationPolicy.Audible<>("preparing",1,false),
                new WatchPresentationPolicy.Audible<>("near",4,true),new WatchPresentationPolicy.Audible<>("far",9,true));
        assertEquals("near",WatchPresentationPolicy.primary(items,false));assertNull(WatchPresentationPolicy.primary(items,true));
    }
    @Test void invalidDistancesCannotBecomeAudioPrimary(){
        var items=List.of(new WatchPresentationPolicy.Audible<>("bad",Double.NaN,true),
                new WatchPresentationPolicy.Audible<>("absent",Double.POSITIVE_INFINITY,true),new WatchPresentationPolicy.Audible<>("negative",-1,true));
        assertNull(WatchPresentationPolicy.primary(items,false));
    }
}
