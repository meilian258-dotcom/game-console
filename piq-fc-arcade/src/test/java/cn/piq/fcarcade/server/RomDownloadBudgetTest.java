package cn.piq.fcarcade.server;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class RomDownloadBudgetTest {
    @Test
    void unknownRequestsAreLimitedBeforeLookupButSequentialValidGamesContinue() {
        RomDownloadBudget budget = new RomDownloadBudget();
        UUID unknown = UUID.randomUUID();
        UUID normal = UUID.randomUUID();
        assertTrue(budget.allowLookup(unknown, 0));
        budget.missing(unknown, 0);
        for (int tick = 1; tick < 40; tick++) {
            assertFalse(budget.allowLookup(unknown, tick));
            assertTrue(budget.allowLookup(normal, tick));
        }
        assertTrue(budget.allowLookup(unknown, 40));
        budget.missing(unknown, 40);
        budget.forget(unknown);
        assertTrue(budget.allowLookup(unknown, 41));
    }

    @Test
    void globalAdmissionIsBoundedAcrossDistinctPlayersAndRecoversNextTick() {
        RomDownloadBudget budget = new RomDownloadBudget();
        for (int n = 0; n < 32; n++) assertTrue(budget.allowLookup(UUID.randomUUID(), 20));
        assertFalse(budget.allowLookup(UUID.randomUUID(), 20));
        assertTrue(budget.allowLookup(UUID.randomUUID(), 21));
        assertTrue(RomDownloadBudget.canStart(7, 63L * 1024 * 1024, 1024 * 1024));
        assertFalse(RomDownloadBudget.canStart(8, 0, 1));
        assertFalse(RomDownloadBudget.canStart(1, 64L * 1024 * 1024, 1));
    }
}
