package cn.piq.nativearcade.world;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class NativeCabinetAssemblyLedgerTest {
    @Test void eachOfSixPartsClaimsExactlyOneDropAcrossLateChunkCleanupAndReload() {
        for(var facing:NativeCabinetFootprint.Facing.values()) for(int first=0;first<6;first++) {
            var ledger=new NativeCabinetAssemblyLedger(); var id=UUID.randomUUID();
            ledger.restore(new NativeCabinetAssemblyLedger.Assembly(id,15,63,15,facing,false,0));
            var clicked=NativeCabinetFootprint.cell(facing,first);
            assertTrue(ledger.close(id,first,15+clicked.x(),63+clicked.y(),15+clicked.z()));
            for(var part:NativeCabinetFootprint.cells(facing)) {
                var reload=new NativeCabinetAssemblyLedger();
                for(var saved:ledger.snapshots())assertTrue(reload.restore(saved));
                ledger=reload;
                assertFalse(ledger.close(id,part.part(),15+part.x(),63+part.y(),15+part.z()));
                ledger.acknowledge(id,part.part());
                if(part.part()<5)assertNotNull(ledger.get(id));
            }
            assertNull(ledger.get(id));
        }
    }

    @Test void upperFloorsHaveDifferentOwnershipFromSameHorizontalCoordinates() {
        var ledger=new NativeCabinetAssemblyLedger(); var id=UUID.randomUUID();
        ledger.restore(new NativeCabinetAssemblyLedger.Assembly(id,0,50,0,NativeCabinetFootprint.Facing.NORTH,false,0));
        assertFalse(ledger.close(id,4,0,50,0));
        assertTrue(ledger.close(id,4,0,52,0));
        assertFalse(ledger.close(id,0,0,50,0));
    }

    @Test void canceledPlacementCannotClaimDropOrTouchAnotherIdentityAtSameAnchor() {
        var ledger=new NativeCabinetAssemblyLedger();var old=UUID.randomUUID();var fresh=UUID.randomUUID();
        ledger.restore(new NativeCabinetAssemblyLedger.Assembly(old,0,0,0,NativeCabinetFootprint.Facing.NORTH,false,0));
        ledger.restore(new NativeCabinetAssemblyLedger.Assembly(fresh,0,0,0,NativeCabinetFootprint.Facing.EAST,false,0));
        ledger.awaitPlacementEvent(old);ledger.awaitPlacementEvent(fresh);
        assertFalse(ledger.close(old,0,0,0,0));
        assertFalse(ledger.cancelPlacement(old,5,0,0,0));
        assertTrue(ledger.cancelPlacement(old,5,1,2,0));
        assertNull(ledger.get(old));assertNotNull(ledger.get(fresh));assertTrue(ledger.pending(fresh));
        ledger.confirmPlacement(fresh);assertTrue(ledger.close(fresh,0,0,0,0));
    }

    @Test void malformedMasksAndForeignCoordinatesNeverAuthorizeItems() {
        var ledger=new NativeCabinetAssemblyLedger();var id=UUID.randomUUID();var facing=NativeCabinetFootprint.Facing.NORTH;
        assertFalse(ledger.restore(new NativeCabinetAssemblyLedger.Assembly(id,0,0,0,facing,false,1)));
        assertFalse(ledger.restore(new NativeCabinetAssemblyLedger.Assembly(id,0,0,0,facing,true,64)));
        assertFalse(ledger.restore(new NativeCabinetAssemblyLedger.Assembly(id,0,0,0,facing,true,-1)));
        var original=new NativeCabinetAssemblyLedger.Assembly(id,0,0,0,facing,false,0);
        assertTrue(ledger.restore(original));assertFalse(ledger.restore(original));
        assertFalse(ledger.close(id,6,0,0,0));assertFalse(ledger.close(UUID.randomUUID(),0,0,0,0));
        assertFalse(ledger.close(id,1,99,0,0));assertFalse(ledger.get(id).closed());
    }

    @Test void fourAcknowledgedCellsStillRetainTwoUnloadedCells() {
        var ledger=new NativeCabinetAssemblyLedger();var id=UUID.randomUUID();
        ledger.restore(new NativeCabinetAssemblyLedger.Assembly(id,0,0,0,NativeCabinetFootprint.Facing.NORTH,false,0));
        assertTrue(ledger.close(id,0,0,0,0));
        for(int part=0;part<4;part++)ledger.acknowledge(id,part);
        assertEquals(15,ledger.get(id).cleared());
        for(int part=4;part<6;part++)ledger.acknowledge(id,part);
        assertNull(ledger.get(id));
    }
}

