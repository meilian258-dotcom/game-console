package cn.piq.sfchome.client;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SfcStartupProgressTest {
    @Test void perStageAndTotalUseActualMonotonicElapsedTime(){var now=new AtomicLong();var p=new SfcStartupProgress(now::get);now.set(500_000_000);p.enter(SfcStartupProgress.Stage.CORE_CREATE);now.set(1_400_000_000);assertEquals(500_000_000L,p.timings().get(SfcStartupProgress.Stage.CACHE));assertEquals(900_000_000L,p.timings().get(SfcStartupProgress.Stage.CORE_CREATE));assertTrue(p.message().contains("0.9 秒 / 总 1.4 秒"));}
    @Test void duplicateStagePollingDoesNotResetTiming(){var now=new AtomicLong();var p=new SfcStartupProgress(now::get);p.enter(SfcStartupProgress.Stage.PREVIOUS_CORE);now.set(1_000_000_000);p.enter(SfcStartupProgress.Stage.PREVIOUS_CORE);now.set(2_000_000_000);assertEquals(2_000_000_000L,p.timings().get(SfcStartupProgress.Stage.PREVIOUS_CORE));}
    @Test void downloadPercentDoesNotClaimTwoControllerWait(){var now=new AtomicLong();var p=new SfcStartupProgress(now::get);p.enter(SfcStartupProgress.Stage.DOWNLOAD);p.download(75,100);assertTrue(p.message().contains("75%"));assertFalse(p.message().contains("双手柄"));p.enter(SfcStartupProgress.Stage.READY);assertFalse(p.message().contains("75%"));assertTrue(p.message().contains("等待服务器开局"));}
    @Test void timingsSnapshotIsImmutableAndRunningDoesNotRegress(){var now=new AtomicLong();var p=new SfcStartupProgress(now::get);p.enter(SfcStartupProgress.Stage.RUNNING);p.enter(SfcStartupProgress.Stage.ROM_LOAD);assertEquals(SfcStartupProgress.Stage.RUNNING,p.stage());assertThrows(UnsupportedOperationException.class,()->p.timings().clear());}
}
