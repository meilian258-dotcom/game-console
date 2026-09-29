package cn.piq.nativearcade.world;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class NativeCabinetRemovalGateTest {
    @Test void anyUnloadedOrDeniedCellRejectsBeforeExtraEventsOrMutation() {
        for(int unavailable=0;unavailable<6;unavailable++) {
            int bad=unavailable;var events=new AtomicInteger();var commits=new AtomicInteger();
            assertEquals(NativeCabinetRemovalGate.Result.UNLOADED,NativeCabinetRemovalGate.attempt(NativeCabinetFootprint.Facing.NORTH,5,
                    c->c.part()!=bad,c->true,c->true,c->{events.incrementAndGet();return true;},()->true,commits::incrementAndGet));
            assertEquals(NativeCabinetRemovalGate.Result.DENIED,NativeCabinetRemovalGate.attempt(NativeCabinetFootprint.Facing.NORTH,5,
                    c->true,c->c.part()!=bad,c->true,c->{events.incrementAndGet();return true;},()->true,commits::incrementAndGet));
            assertEquals(0,events.get());assertEquals(0,commits.get());
        }
    }

    @Test void successFiresExactlyFiveOtherEventsThenOneCommit() {
        for(var facing:NativeCabinetFootprint.Facing.values()) for(int clicked=0;clicked<6;clicked++) {
            var events=new ArrayList<Integer>();var commits=new AtomicInteger();
            assertEquals(NativeCabinetRemovalGate.Result.REMOVED,NativeCabinetRemovalGate.attempt(facing,clicked,
                    c->true,c->true,c->true,c->{events.add(c.part());return true;},()->true,commits::incrementAndGet));
            assertEquals(5,events.size());assertEquals(5,events.stream().distinct().count());
            assertFalse(events.contains(clicked));assertEquals(1,commits.get());
        }
    }

    @Test void canceledUpperFloorProtectionEventDoesNotCommit() {
        var commits=new AtomicInteger();
        assertEquals(NativeCabinetRemovalGate.Result.DENIED,NativeCabinetRemovalGate.attempt(NativeCabinetFootprint.Facing.NORTH,0,
                c->true,c->true,c->true,c->c.part()!=5,()->true,commits::incrementAndGet));
        assertEquals(0,commits.get());
    }

    @Test void eventChangingClaimChunkOrIdentityFailsClosedBeforeCommit() {
        for(int scenario=0;scenario<3;scenario++) {
            int which=scenario;var stable=new AtomicBoolean(true);var commits=new AtomicInteger();
            var result=NativeCabinetRemovalGate.attempt(NativeCabinetFootprint.Facing.EAST,0,
                    c->which!=0||stable.get(),c->which!=1||stable.get(),c->true,
                    c->{stable.set(false);return true;},()->which!=2||stable.get(),commits::incrementAndGet);
            assertEquals(switch(which){case 0->NativeCabinetRemovalGate.Result.UNLOADED;case 1->NativeCabinetRemovalGate.Result.DENIED;
                default->NativeCabinetRemovalGate.Result.REPLACED;},result);
            assertEquals(0,commits.get());
        }
    }

    @Test void foreignReplacementsReceiveNoBreakEventAndInvalidClickedPartsCannotCommit() {
        var events=new ArrayList<Integer>();var commits=new AtomicInteger();
        assertEquals(NativeCabinetRemovalGate.Result.REMOVED,NativeCabinetRemovalGate.attempt(NativeCabinetFootprint.Facing.WEST,0,
                c->true,c->true,c->c.part()!=5,c->{events.add(c.part());return true;},()->true,commits::incrementAndGet));
        assertFalse(events.contains(5));assertEquals(1,commits.get());
        for(int part:new int[]{-1,6})
            assertEquals(NativeCabinetRemovalGate.Result.REPLACED,NativeCabinetRemovalGate.attempt(NativeCabinetFootprint.Facing.NORTH,part,
                    c->true,c->true,c->true,c->true,()->true,()->fail("invalid part committed")));
    }
}

