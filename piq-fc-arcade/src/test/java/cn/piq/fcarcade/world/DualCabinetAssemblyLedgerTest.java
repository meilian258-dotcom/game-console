package cn.piq.fcarcade.world;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class DualCabinetAssemblyLedgerTest {
    @Test void eachOfTwelvePartsClaimsExactlyOneDropAcrossLateChunkCleanupAndReload() {
        for(var facing:DualCabinetFootprint.Facing.values()) for(int first=0;first<12;first++) {
            var ledger=new DualCabinetAssemblyLedger(); var id=UUID.randomUUID();
            ledger.restore(new DualCabinetAssemblyLedger.Assembly(id,15,63,15,facing,false,0));
            var clicked=DualCabinetFootprint.cell(facing,first);
            assertTrue(ledger.close(id,first,15+clicked.x(),63+clicked.y(),15+clicked.z()));
            for(var part:DualCabinetFootprint.cells(facing)) {
                var reload=new DualCabinetAssemblyLedger();
                for(var saved:ledger.snapshots())assertTrue(reload.restore(saved));
                ledger=reload;
                assertFalse(ledger.close(id,part.part(),15+part.x(),63+part.y(),15+part.z()));
                ledger.acknowledge(id,part.part());
                if(part.part()<11)assertNotNull(ledger.get(id));
            }
            assertNull(ledger.get(id));
        }
    }

    @Test void upperFloorsHaveDifferentOwnershipFromSameHorizontalCoordinates() {
        var ledger=new DualCabinetAssemblyLedger(); var id=UUID.randomUUID();
        ledger.restore(new DualCabinetAssemblyLedger.Assembly(id,0,50,0,DualCabinetFootprint.Facing.NORTH,false,0));
        assertFalse(ledger.close(id,4,0,50,0));
        assertTrue(ledger.close(id,4,0,52,0));
        assertFalse(ledger.close(id,0,0,50,0));
    }

    @Test void canceledPlacementCannotClaimDropOrTouchAnotherIdentityAtSameAnchor() {
        var ledger=new DualCabinetAssemblyLedger();var old=UUID.randomUUID();var fresh=UUID.randomUUID();
        ledger.restore(new DualCabinetAssemblyLedger.Assembly(old,0,0,0,DualCabinetFootprint.Facing.NORTH,false,0));
        ledger.restore(new DualCabinetAssemblyLedger.Assembly(fresh,0,0,0,DualCabinetFootprint.Facing.EAST,false,0));
        ledger.awaitPlacementEvent(old);ledger.awaitPlacementEvent(fresh);
        assertFalse(ledger.close(old,0,0,0,0));
        assertFalse(ledger.cancelPlacement(old,11,0,0,0));
        assertTrue(ledger.cancelPlacement(old,11,1,2,1));
        assertNull(ledger.get(old));assertNotNull(ledger.get(fresh));assertTrue(ledger.pending(fresh));
        ledger.confirmPlacement(fresh);assertTrue(ledger.close(fresh,0,0,0,0));
    }

    @Test void malformedMasksAndForeignCoordinatesNeverAuthorizeItems() {
        var ledger=new DualCabinetAssemblyLedger();var id=UUID.randomUUID();var facing=DualCabinetFootprint.Facing.NORTH;
        assertFalse(ledger.restore(new DualCabinetAssemblyLedger.Assembly(id,0,0,0,facing,false,1)));
        assertFalse(ledger.restore(new DualCabinetAssemblyLedger.Assembly(id,0,0,0,facing,true,4096)));
        assertFalse(ledger.restore(new DualCabinetAssemblyLedger.Assembly(id,0,0,0,facing,true,-1)));
        var original=new DualCabinetAssemblyLedger.Assembly(id,0,0,0,facing,false,0);
        assertTrue(ledger.restore(original));assertFalse(ledger.restore(original));
        assertFalse(ledger.close(id,12,0,0,0));assertFalse(ledger.close(UUID.randomUUID(),0,0,0,0));
        assertFalse(ledger.close(id,1,99,0,0));assertFalse(ledger.get(id).closed());
    }

    @Test void eightAcknowledgedCellsStillRetainFourUnloadedCells() {
        var ledger=new DualCabinetAssemblyLedger();var id=UUID.randomUUID();
        ledger.restore(new DualCabinetAssemblyLedger.Assembly(id,0,0,0,DualCabinetFootprint.Facing.NORTH,false,0));
        assertTrue(ledger.close(id,0,0,0,0));
        for(int part=0;part<8;part++)ledger.acknowledge(id,part);
        assertEquals(255,ledger.get(id).cleared());
        for(int part=8;part<12;part++)ledger.acknowledge(id,part);
        assertNull(ledger.get(id));
    }
}

