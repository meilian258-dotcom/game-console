package cn.piq.fcarcade.home;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EmptyConsolePowerTest {
    @Test void onlyConnectedEmptyHardwareWithTvOnCanPowerUp() {
        var idle = new EmptyConsolePower();
        assertFalse(idle.turnOn(true,true,true));
        assertFalse(idle.turnOn(false,false,true));
        assertFalse(idle.turnOn(false,true,false));
        assertTrue(idle.turnOn(false,true,true));
        assertTrue(idle.reconcile(false,true,true));
        assertFalse(idle.turnOn(false,true,true));
    }
    @Test void insertionExitsStandbyAndCannotAutoRestartAfterEjection() {
        var idle = new EmptyConsolePower();
        assertTrue(idle.turnOn(false,true,true));
        assertFalse(idle.reconcile(true,true,true));
        assertFalse(idle.reconcile(false,true,true));
    }
    @Test void televisionOffClearsStandbyWithoutRearmingWhenItReturns() {
        var idle = new EmptyConsolePower();
        idle.turnOn(false,true,true);
        assertFalse(idle.reconcile(false,true,false));
        assertFalse(idle.reconcile(false,true,true));
    }
    @Test void disconnectOrUnloadClearsStandbyAndReconnectNeedsExplicitPower() {
        var idle = new EmptyConsolePower();
        idle.turnOn(false,true,true);
        assertFalse(idle.reconcile(false,false,true));
        assertFalse(idle.reconcile(false,true,true));
        assertTrue(idle.turnOn(false,true,true));
        idle.clear();
        assertFalse(idle.reconcile(false,true,true));
    }
    @Test void newWorldInstanceNeverRestoresPowerState() {
        var old = new EmptyConsolePower();
        old.turnOn(false,true,true);
        assertFalse(new EmptyConsolePower().reconcile(false,true,true));
    }
    @Test void clearingIsIdempotent() {
        var idle = new EmptyConsolePower();
        idle.clear(); idle.clear();
        assertFalse(idle.reconcile(false,true,true));
    }
}
