package cn.piq.fcarcade.client;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** One bounded file/decode worker shared by the editor and cover cache. Never runs rendering calls. */
final class ClientCartridgeIo {
    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(8), task -> { Thread t = new Thread(task, "PIQ-FC-cartridge-client-IO"); t.setDaemon(true); return t; },
            new ThreadPoolExecutor.AbortPolicy());
    private ClientCartridgeIo() {}
    static boolean submit(Runnable task) {
        try { WORKER.execute(task); return true; }
        catch (java.util.concurrent.RejectedExecutionException busy) { return false; }
    }
}
