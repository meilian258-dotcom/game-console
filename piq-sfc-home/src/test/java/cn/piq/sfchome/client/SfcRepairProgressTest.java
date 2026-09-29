package cn.piq.sfchome.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class SfcRepairProgressTest {
    @Test void oldGoalDoesNotCompleteWithBacklog(){for(int queued=7;queued<=512;queued++)assertFalse(SfcRepairProgress.ready(720,720,queued));}
    @Test void passesOldGoalWhileCatchingUpAndCompletesInSmallWindow(){assertFalse(SfcRepairProgress.ready(716,720,0));assertFalse(SfcRepairProgress.ready(900,720,20));assertTrue(SfcRepairProgress.ready(914,720,6));assertTrue(SfcRepairProgress.ready(920,720,0));}
    @Test void noResumeOrInvalidCountsNeverComplete(){assertFalse(SfcRepairProgress.ready(920,-1,0));assertFalse(SfcRepairProgress.ready(920,720,-1));}
}
