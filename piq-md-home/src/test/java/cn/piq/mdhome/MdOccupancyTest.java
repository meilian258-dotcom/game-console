package cn.piq.mdhome;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MdOccupancyTest {
    record Seat(UUID player, Object connection, UUID loan) {}
    static final class Fixture {
        final Seat[] ports=new Seat[2];
        final Map<UUID,Object> connected=new HashMap<>();
        final UUID[] loans=new UUID[2];
        boolean active=true;
        Seat grant(int port,UUID id) {
            var connection=new Object();var loan=UUID.randomUUID();
            var seat=new Seat(id,connection,loan);ports[port]=seat;loans[port]=loan;connected.put(id,connection);return seat;
        }
        List<UUID> players(){return MdOccupancy.players(active,ports,(port,s)->connected.get(s.player)==s.connection
                &&s.loan.equals(loans[port]),Seat::player);}
    }
    @Test void borrowedButNotRunningOrReadyDoesNotAdvertiseAUser(){
        var f=new Fixture();f.grant(0,UUID.randomUUID());f.active=false;assertEquals(List.of(),f.players());
        f.active=true;assertEquals(1,f.players().size());f.active=false;assertEquals(List.of(),f.players());
    }
    @Test void hostAndObserversWithoutGrantedPortsAreAbsent(){
        var f=new Fixture();f.connected.put(UUID.randomUUID(),new Object());f.connected.put(UUID.randomUUID(),new Object());
        assertEquals(List.of(),f.players());
    }
    @Test void twoPortsStayOrderedAndDepartureOnlyRemovesItsOwnSeat(){
        var f=new Fixture();var one=f.grant(0,UUID.randomUUID());var two=f.grant(1,UUID.randomUUID());
        assertEquals(List.of(one.player,two.player),f.players());f.ports[0]=null;
        assertEquals(List.of(two.player),f.players());f.loans[1]=UUID.randomUUID();assertEquals(List.of(),f.players());
    }
    @Test void replacedConnectionAndDisconnectCannotReuseOldSeat(){
        var f=new Fixture();var seat=f.grant(0,UUID.randomUUID());f.connected.put(seat.player,new Object());
        assertEquals(List.of(),f.players());f.connected.remove(seat.player);assertEquals(List.of(),f.players());
    }
    @Test void samePlayerIsNotRepeatedAndSnapshotDoesNotChangeAfterRelease(){
        var f=new Fixture();var seat=f.grant(0,UUID.randomUUID());f.ports[1]=seat;f.loans[1]=seat.loan;
        var snapshot=f.players();assertEquals(List.of(seat.player),snapshot);f.ports[0]=null;f.ports[1]=null;
        assertEquals(List.of(),f.players());assertEquals(List.of(seat.player),snapshot);
        assertThrows(UnsupportedOperationException.class,()->snapshot.add(UUID.randomUUID()));
    }
}
