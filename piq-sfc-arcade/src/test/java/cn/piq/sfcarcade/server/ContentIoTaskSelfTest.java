// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfcarcade.server;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Exercises the cancellation lifecycle used by real library requests without game/network stubs. */
public final class ContentIoTaskSelfTest {
    public static void verify() throws Exception {
        AtomicBoolean called = new AtomicBoolean();
        var queued = new ContentIoTask<>(() -> { called.set(true); return 1; });
        queued.cancel(); queued.run();
        require(queued.done() && queued.cancelled() && !called.get(), "cancel-before-run performed IO or leaked slot");

        var entered = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var running = new ContentIoTask<>(() -> {
            entered.countDown();
            try { release.await(); } catch (InterruptedException expected) {
                interrupted.countDown(); release.await();
            }
            return 7;
        });
        var thread = new Thread(running, "sfc-io-test"); thread.start();
        try {
            require(entered.await(5, TimeUnit.SECONDS), "worker did not start");
            running.cancel();
            require(interrupted.await(5, TimeUnit.SECONDS), "running cancellation did not interrupt IO");
            require(!running.done(), "running cancel released quota before IO exited");
        } finally { release.countDown(); thread.join(5000); }
        require(!thread.isAlive() && running.done() && running.cancelled(), "cancelled IO leaked slot");

        var failed = new ContentIoTask<>(() -> { throw new java.io.IOException("probe"); });
        failed.run();
        require(failed.done() && failed.error() instanceof java.io.IOException, "failure did not become an error result");
        var succeeded = new ContentIoTask<>(() -> 11); succeeded.run();
        require(succeeded.done() && succeeded.error() == null && succeeded.value() == 11, "normal result was lost");
        System.out.println("SFC content IO cancellation self-test passed");
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
