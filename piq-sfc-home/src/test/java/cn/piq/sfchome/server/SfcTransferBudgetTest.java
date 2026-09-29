package cn.piq.sfchome.server;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SfcTransferBudgetTest {
    @Test void onlyTwoMaxBuffersFit(){var b=new SfcTransferBudget();assertTrue(b.reserve(32L*1024*1024));assertTrue(b.reserve(32L*1024*1024));assertFalse(b.reserve(1));}
    @Test void finishDoesNotReleaseUntilCompletion(){var b=new SfcTransferBudget();assertTrue(b.reserve(SfcTransferBudget.LIMIT));assertFalse(b.reserve(1));b.release(SfcTransferBudget.LIMIT);assertTrue(b.reserve(SfcTransferBudget.LIMIT));}
    @Test void arithmeticDoesNotOverflow(){var b=new SfcTransferBudget();assertFalse(b.reserve(Long.MAX_VALUE));assertFalse(b.reserve(-1));assertFalse(b.reserve(0));assertEquals(0,b.reserved());}
    @Test void doubleReleaseRejected(){var b=new SfcTransferBudget();b.reserve(1);b.release(1);assertThrows(IllegalStateException.class,()->b.release(1));}
}
