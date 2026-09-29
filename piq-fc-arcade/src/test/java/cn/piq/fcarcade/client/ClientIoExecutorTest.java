package cn.piq.fcarcade.client;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class ClientIoExecutorTest {
    @Test
    void saturatedFileQueueRejectsWithoutRunningWorkOnTheCaller() throws Exception {
        Thread caller = Thread.currentThread();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(33);
        AtomicBoolean ranOnCaller = new AtomicBoolean();
        ClientIoExecutor.execute(() -> {
            entered.countDown();
            try {
                if (Thread.currentThread() == caller) ranOnCaller.set(true);
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            } finally {
                finished.countDown();
            }
        });
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            for (int i = 0; i < 32; i++) {
                ClientIoExecutor.execute(() -> {
                    if (Thread.currentThread() == caller) ranOnCaller.set(true);
                    finished.countDown();
                });
            }
            assertThrows(RejectedExecutionException.class,
                    () -> ClientIoExecutor.execute(() -> ranOnCaller.set(true)));
        } finally {
            release.countDown();
        }
        assertTrue(finished.await(5, TimeUnit.SECONDS));
        assertFalse(ranOnCaller.get(), "Busy disk work must never fall back to the render thread");
    }
}
