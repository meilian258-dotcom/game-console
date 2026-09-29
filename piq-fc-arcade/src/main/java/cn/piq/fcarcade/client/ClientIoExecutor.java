package cn.piq.fcarcade.client;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Bounded file work; never falls back to doing IO on the submitting UI thread. */
final class ClientIoExecutor {
    private static final ThreadPoolExecutor EXECUTOR = new ThreadPoolExecutor(
            1, 1, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(32), task -> {
                Thread thread = new Thread(task, "PIQ FC ROM IO");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    static {
        EXECUTOR.allowCoreThreadTimeOut(true);
    }

    private ClientIoExecutor() {}

    static void execute(Runnable action) {
        EXECUTOR.execute(action);
    }
}
