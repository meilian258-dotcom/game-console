// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfcarcade.server;

import java.util.concurrent.Callable;

/** Cancellation keeps its quota until the actual IO has left run(), not merely Future.cancel(). */
final class ContentIoTask<T> implements Runnable {
    private final Callable<T> operation;
    private Thread runner;
    private volatile boolean cancelled, done;
    private T value;
    private Throwable error;
    ContentIoTask(Callable<T> operation) { this.operation = operation; }
    @Override public void run() {
        synchronized (this) {
            if (done) return;
            runner = Thread.currentThread();
        }
        try { if (!cancelled) value = operation.call(); }
        catch (Exception | LinkageError failure) { error = failure; }
        finally {
            synchronized (this) { runner = null; done = true; }
            Thread.interrupted(); // Do not leak an interrupted request into the next queued operation.
        }
    }
    synchronized void cancel() {
        cancelled = true;
        if (runner == null) done = true;
        else runner.interrupt();
    }
    boolean done() { return done; }
    boolean cancelled() { return cancelled; }
    T value() { if (!done) throw new IllegalStateException("IO is still running"); return value; }
    Throwable error() { if (!done) throw new IllegalStateException("IO is still running"); return error; }
}
