package cn.piq.fcarcade.config;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AdminTerminalPolicyTest {
    @Test void readAndTargetCannotCarryMutations() {
        for (int action : new int[]{AdminTerminalPolicy.READ, AdminTerminalPolicy.TARGET}) {
            assertTrue(AdminTerminalPolicy.valid(action, 0));
            for (int value : new int[]{-1, 1, Integer.MIN_VALUE, Integer.MAX_VALUE})
                assertFalse(AdminTerminalPolicy.valid(action, value));
        }
    }
    @Test void everyAllowedAccessCombinationIsBounded() {
        for (int value = 0; value <= 31; value++) assertTrue(AdminTerminalPolicy.valid(AdminTerminalPolicy.ACCESS, value));
        for (int value : new int[]{-1, 32, 63, 255, Integer.MIN_VALUE, Integer.MAX_VALUE})
            assertFalse(AdminTerminalPolicy.valid(AdminTerminalPolicy.ACCESS, value));
    }
    @Test void rangeAndModeUseTheSamePublicBoundsAsCommands() {
        for (int value = -100; value < 100; value++) {
            assertEquals(GameConsoleAdminPolicy.validRange(value), AdminTerminalPolicy.valid(AdminTerminalPolicy.RANGE, value));
            assertEquals(GameConsoleAdminPolicy.validMode(value), AdminTerminalPolicy.valid(AdminTerminalPolicy.MODE, value));
        }
    }
    @Test void trafficCannotSendArbitraryCommandsOrActions() {
        for (int value = 0; value <= 2; value++) assertTrue(AdminTerminalPolicy.valid(AdminTerminalPolicy.TRAFFIC, value));
        for (int value : new int[]{-1, 3, Integer.MAX_VALUE}) assertFalse(AdminTerminalPolicy.valid(AdminTerminalPolicy.TRAFFIC, value));
        for (int action : new int[]{-1, 7, Integer.MIN_VALUE, Integer.MAX_VALUE}) assertFalse(AdminTerminalPolicy.valid(action, 0));
    }
    @Test void staleClientDoesNotOverwriteAnotherAdminsUpdate() {
        assertTrue(AdminTerminalPolicy.same(7, 1, 16, 7, 1, 16));
        assertFalse(AdminTerminalPolicy.same(7, 1, 16, 15, 1, 16));
        assertFalse(AdminTerminalPolicy.same(7, 1, 16, 7, 2, 16));
        assertFalse(AdminTerminalPolicy.same(7, 1, 16, 7, 1, 32));
    }
}
