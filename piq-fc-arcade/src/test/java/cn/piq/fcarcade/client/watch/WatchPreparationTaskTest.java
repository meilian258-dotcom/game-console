package cn.piq.fcarcade.client.watch;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WatchPreparationTaskTest {
    @Test void queuedCancellationNeverRunsLoader(){
        AtomicInteger calls=new AtomicInteger();var task=new WatchPreparationTask<>(()->calls.incrementAndGet());
        task.cancel();task.run();assertTrue(task.done());assertTrue(task.result().isCancelled());assertEquals(0,calls.get());
    }
    @Test void interruptionIsNotReleaseUntilRealWorkerReturns()throws Exception{
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var interrupted=new CountDownLatch(1);
        var task=new WatchPreparationTask<>(()->{entered.countDown();for(;;)try{release.await();break;}catch(InterruptedException ignored){interrupted.countDown();}return 7;});
        var worker=new Thread(task);worker.start();
        try{
            assertTrue(entered.await(3,TimeUnit.SECONDS));task.cancel();assertTrue(interrupted.await(3,TimeUnit.SECONDS));
            assertFalse(task.done());assertFalse(task.result().isDone(),"cancelled IO may still own resources");
        }finally{release.countDown();worker.join(3000);}
        assertFalse(worker.isAlive());assertTrue(task.done());assertTrue(task.result().isCancelled());
    }
    @Test void failedSourceDoesNotCancelIndependentTask()throws Exception{
        var bad=new WatchPreparationTask<>(()->{throw new IllegalStateException("one source");});
        var good=new WatchPreparationTask<>(()->42);bad.run();good.run();
        assertThrows(ExecutionException.class,()->bad.result().get());assertEquals(42,good.result().get());
        assertTrue(bad.done());assertTrue(good.done());
    }
    @Test void repeatedRunAndCancelDoNotReopenCompletedLoader()throws Exception{
        AtomicInteger calls=new AtomicInteger();var task=new WatchPreparationTask<>(calls::incrementAndGet);
        task.run();task.run();task.cancel();assertEquals(1,calls.get());assertEquals(1,task.result().get());
    }
}
