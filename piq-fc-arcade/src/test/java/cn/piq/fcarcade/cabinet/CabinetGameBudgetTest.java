package cn.piq.fcarcade.cabinet;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CabinetGameBudgetTest {
    @Test void exactBurstAndNoCreditWithoutTime() {
        var b=new CabinetGameBudget(512*1024,128*1024,0);
        for(int i=0;i<4;i++)b.charge(32768,0);
        assertFalse(b.permits(1,0));assertFalse(b.permits(1,-1));
        assertThrows(IllegalStateException.class,()->b.charge(1,0));
    }
    @Test void refillIsRateBoundedNotCallCount() {
        var b=new CabinetGameBudget(1000,1000,0);b.charge(1000,0);
        for(int i=0;i<10000;i++)assertFalse(b.permits(1,0));
        assertTrue(b.permits(500,500_000_000L));b.charge(500,500_000_000L);
        assertFalse(b.permits(1,500_000_000L));assertTrue(b.permits(500,1_000_000_000L));
    }
    @Test void failedAdmissionDoesNotConsumeAndClockCannotGoBackwards() {
        var b=new CabinetGameBudget(1000,1000,0);
        for(int i=0;i<100;i++)assertTrue(b.permits(1000,0));
        b.charge(1000,0);assertFalse(b.permits(1,-1_000_000_000L));
        assertTrue(b.permits(1000,1_000_000_000L));b.charge(1000,1_000_000_000L);
        assertFalse(b.permits(1,0));assertFalse(b.permits(1,1_000_000_000L));
    }
    @Test void invalidAndOversizedCostsRejected() {
        assertThrows(IllegalArgumentException.class,()->new CabinetGameBudget(0,1,0));
        assertThrows(IllegalArgumentException.class,()->new CabinetGameBudget(1,0,0));
        var b=new CabinetGameBudget(1,10,0);
        assertFalse(b.permits(0,0));assertFalse(b.permits(-1,0));assertFalse(b.permits(11,Long.MAX_VALUE));
    }
    @Test void independentConnectionAndSharedGlobalBudgetsBothApply() {
        var a=new CabinetGameBudget(100,100,0);var b=new CabinetGameBudget(100,100,0);var total=new CabinetGameBudget(150,150,0);
        a.charge(100,0);total.charge(100,0);
        assertTrue(b.permits(100,0));assertFalse(total.permits(100,0));
        b.charge(50,0);total.charge(50,0);assertFalse(total.permits(1,0));
    }
    @Test void longPauseCannotAccumulateUnlimitedTokens() {
        var b=new CabinetGameBudget(1000,1000,0);b.charge(1000,0);
        b.charge(1000,Long.MAX_VALUE);assertFalse(b.permits(1,Long.MAX_VALUE));
    }
}
