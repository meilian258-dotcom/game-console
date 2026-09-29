package cn.piq.fcarcade.world;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class DualCabinetRemovalGateTest {
    @Test void anyUnloadedOrDeniedCellRejectsBeforeExtraEventsOrMutation() {
        for(int unavailable=0;unavailable<12;unavailable++) {
            int bad=unavailable;var events=new AtomicInteger();var commits=new AtomicInteger();
            assertEquals(DualCabinetRemovalGate.Result.UNLOADED,DualCabinetRemovalGate.attempt(DualCabinetFootprint.Facing.NORTH,11,
                    c->c.part()!=bad,c->true,c->true,c->{events.incrementAndGet();return true;},()->true,commits::incrementAndGet));
            assertEquals(DualCabinetRemovalGate.Result.DENIED,DualCabinetRemovalGate.attempt(DualCabinetFootprint.Facing.NORTH,11,
                    c->true,c->c.part()!=bad,c->true,c->{events.incrementAndGet();return true;},()->true,commits::incrementAndGet));
            assertEquals(0,events.get());assertEquals(0,commits.get());
        }
    }

    @Test void successFiresExactlyElevenOtherEventsThenOneCommit() {
        for(var facing:DualCabinetFootprint.Facing.values()) for(int clicked=0;clicked<12;clicked++) {
            var events=new ArrayList<Integer>();var commits=new AtomicInteger();
            assertEquals(DualCabinetRemovalGate.Result.REMOVED,DualCabinetRemovalGate.attempt(facing,clicked,
                    c->true,c->true,c->true,c->{events.add(c.part());return true;},()->true,commits::incrementAndGet));
            assertEquals(11,events.size());assertEquals(11,events.stream().distinct().count());
            assertFalse(events.contains(clicked));assertEquals(1,commits.get());
        }
    }

    @Test void canceledUpperFloorProtectionEventDoesNotCommit() {
        var commits=new AtomicInteger();
        assertEquals(DualCabinetRemovalGate.Result.DENIED,DualCabinetRemovalGate.attempt(DualCabinetFootprint.Facing.NORTH,0,
                c->true,c->true,c->true,c->c.part()!=11,()->true,commits::incrementAndGet));
        assertEquals(0,commits.get());
    }

    @Test void eventChangingClaimChunkOrIdentityFailsClosedBeforeCommit() {
        for(int scenario=0;scenario<3;scenario++) {
            int which=scenario;var stable=new AtomicBoolean(true);var commits=new AtomicInteger();
            var result=DualCabinetRemovalGate.attempt(DualCabinetFootprint.Facing.EAST,0,
                    c->which!=0||stable.get(),c->which!=1||stable.get(),c->true,
                    c->{stable.set(false);return true;},()->which!=2||stable.get(),commits::incrementAndGet);
            assertEquals(switch(which){case 0->DualCabinetRemovalGate.Result.UNLOADED;case 1->DualCabinetRemovalGate.Result.DENIED;
                default->DualCabinetRemovalGate.Result.REPLACED;},result);
            assertEquals(0,commits.get());
        }
    }

    @Test void foreignReplacementsReceiveNoBreakEventAndInvalidClickedPartsCannotCommit() {
        var events=new ArrayList<Integer>();var commits=new AtomicInteger();
        assertEquals(DualCabinetRemovalGate.Result.REMOVED,DualCabinetRemovalGate.attempt(DualCabinetFootprint.Facing.WEST,0,
                c->true,c->true,c->c.part()!=5,c->{events.add(c.part());return true;},()->true,commits::incrementAndGet));
        assertFalse(events.contains(5));assertEquals(1,commits.get());
        for(int part:new int[]{-1,12})
            assertEquals(DualCabinetRemovalGate.Result.REPLACED,DualCabinetRemovalGate.attempt(DualCabinetFootprint.Facing.NORTH,part,
                    c->true,c->true,c->true,c->true,()->true,()->fail("invalid part committed")));
    }
}

