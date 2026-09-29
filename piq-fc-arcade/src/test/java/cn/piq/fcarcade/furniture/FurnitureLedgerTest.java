package cn.piq.fcarcade.furniture;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FurnitureLedgerTest {
    private FurnitureLedger.Entry entry(UUID id){return new FurnitureLedger.Entry(id,15,64,31,0,"oak",false,0);}
    @Test void breakAnyHalfAndRepeatedCallbacksClaimExactlyOnce() {
        for(int first=0;first<2;first++) {var ledger=new FurnitureLedger();var id=UUID.randomUUID();assertTrue(ledger.add(entry(id)));
            assertTrue(ledger.claim(id));assertFalse(ledger.claim(id));ledger.acknowledge(id,first);
            assertTrue(ledger.get(id).closed());assertFalse(ledger.claim(id));ledger.acknowledge(id,1-first);
            assertNull(ledger.get(id));assertFalse(ledger.claim(id));}
    }
    @Test void unloadedSecondHalfKeepsClosedTombstoneUntilItsOwnAcknowledgement() {
        var ledger=new FurnitureLedger();var id=UUID.randomUUID();ledger.add(entry(id));ledger.claim(id);ledger.acknowledge(id,0);
        var saved=ledger.entries();var reloaded=new FurnitureLedger();saved.forEach(reloaded::add);
        assertTrue(reloaded.get(id).closed());assertFalse(reloaded.claim(id));reloaded.acknowledge(id,1);assertNull(reloaded.get(id));
    }
    @Test void sameLocationNewGenerationSurvivesStaleOldHalf() {
        var ledger=new FurnitureLedger();var old=UUID.randomUUID();var fresh=UUID.randomUUID();ledger.add(entry(old));ledger.claim(old);ledger.acknowledge(old,0);ledger.add(entry(fresh));
        ledger.acknowledge(old,1);assertNotNull(ledger.get(fresh));assertFalse(ledger.get(fresh).closed());assertTrue(ledger.claim(fresh));
    }
    @Test void placementRollbackHasNoEntitlementAndCannotCloseNeighbour() {
        var ledger=new FurnitureLedger();var id=UUID.randomUUID();var other=UUID.randomUUID();ledger.add(entry(id));ledger.add(entry(other));ledger.cancel(id);
        assertFalse(ledger.claim(id));assertTrue(ledger.claim(other));assertFalse(ledger.add(entry(other)));
    }
    @Test void invalidPartsAndEntryValuesAreRejected() {
        var ledger=new FurnitureLedger();var id=UUID.randomUUID();assertThrows(IllegalArgumentException.class,()->ledger.acknowledge(id,-1));
        assertThrows(IllegalArgumentException.class,()->ledger.acknowledge(id,2));assertThrows(IllegalArgumentException.class,()->new FurnitureLedger.Entry(id,0,0,0,4,"oak",false,0));
    }
    @Test void activeRecordCannotBeAcknowledgedAway() {
        var ledger=new FurnitureLedger();var id=UUID.randomUUID();ledger.add(entry(id));ledger.acknowledge(id,0);ledger.acknowledge(id,1);assertNotNull(ledger.get(id));assertTrue(ledger.claim(id));
    }
}
