package cn.piq.fcarcade.cabinet;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CabinetLeaseLedgerTest {
    @Test void oneOwnerAndOneTargetCannotRunTwoCores() {
        var ledger=new CabinetLeaseLedger<String>();var owner=UUID.randomUUID();
        var first=ledger.acquire(owner,"a","mame",10);
        assertNotNull(first);
        assertNull(ledger.acquire(owner,"b","sfc",11));
        assertNull(ledger.acquire(UUID.randomUUID(),"a","sfc",11));
        assertNotNull(ledger.acquire(UUID.randomUUID(),"b","sfc",11));
        assertEquals(first,ledger.owner(owner));assertEquals(first,ledger.target("a"));
    }
    @Test void guessedTokensAndForeignPlayersCannotHeartbeatOrRelease() {
        var ledger=new CabinetLeaseLedger<String>();var owner=UUID.randomUUID();var foreign=UUID.randomUUID();
        var lease=ledger.acquire(owner,"a","sfc",0);
        assertFalse(ledger.heartbeat(foreign,lease.id(),20));
        assertFalse(ledger.heartbeat(owner,UUID.randomUUID(),20));
        assertNull(ledger.release(foreign,lease.id()));
        assertNull(ledger.release(owner,UUID.randomUUID()));
        assertEquals(80,ledger.get(lease.id()).expires());
    }
    @Test void heartbeatExtendsOnlyALiveLeaseAndExpiryBoundaryIsClosed() {
        var ledger=new CabinetLeaseLedger<String>();var owner=UUID.randomUUID();
        var lease=ledger.acquire(owner,"a","sfc",100);
        assertTrue(ledger.heartbeat(owner,lease.id(),179));assertEquals(259,ledger.get(lease.id()).expires());
        assertFalse(ledger.heartbeat(owner,lease.id(),259));assertFalse(ledger.heartbeat(owner,lease.id(),260));
    }
    @Test void oldTokenCannotCloseNewSessionAtSamePosition() {
        var ledger=new CabinetLeaseLedger<String>();var owner=UUID.randomUUID();
        var first=ledger.acquire(owner,"same-position","mame",0);
        assertEquals(first,ledger.release(owner,first.id()));
        var fresh=ledger.acquire(owner,"same-position","sfc",10);
        assertNotEquals(first.id(),fresh.id());
        assertNull(ledger.release(owner,first.id()));assertFalse(ledger.heartbeat(owner,first.id(),20));
        assertEquals(fresh,ledger.get(fresh.id()));
    }
    @Test void identityAndDimensionArePartOfCallerProvidedKey() {
        record Key(String dimension,int x,UUID identity) {}
        var ledger=new CabinetLeaseLedger<Key>();var id=UUID.randomUUID();
        assertNotNull(ledger.acquire(UUID.randomUUID(),new Key("overworld",1,id),"sfc",0));
        assertNotNull(ledger.acquire(UUID.randomUUID(),new Key("nether",1,id),"sfc",0));
        assertNotNull(ledger.acquire(UUID.randomUUID(),new Key("overworld",1,UUID.randomUUID()),"sfc",0));
    }
    @Test void capacityIsBoundedAndReleasedSlotsCanBeReused() {
        var ledger=new CabinetLeaseLedger<Integer>();
        for(int i=0;i<CabinetLeaseLedger.MAX_LEASES;i++)assertNotNull(ledger.acquire(UUID.randomUUID(),i,"sfc",0));
        assertNull(ledger.acquire(UUID.randomUUID(),100,"sfc",0));
        var first=ledger.all().getFirst();ledger.release(first.owner(),first.id());
        assertNotNull(ledger.acquire(UUID.randomUUID(),100,"sfc",1));
        assertEquals(CabinetLeaseLedger.MAX_LEASES,ledger.all().size());
    }
    @Test void snapshotsAreImmutableAndSafeDuringRelease() {
        var ledger=new CabinetLeaseLedger<String>();var owner=UUID.randomUUID();ledger.acquire(owner,"a","sfc",0);
        var snapshot=ledger.all();assertThrows(UnsupportedOperationException.class,snapshot::clear);
        for(var lease:snapshot)ledger.release(lease.owner(),lease.id());
        assertTrue(ledger.all().isEmpty());assertEquals(1,snapshot.size());
    }
}
