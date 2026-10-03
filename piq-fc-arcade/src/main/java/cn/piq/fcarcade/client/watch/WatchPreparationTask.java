// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.fcarcade.client.watch;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

/** Cancellation is not completion: an interrupted loader may still own IO/resources.
 * Unlike Future.cancel, done() becomes true only when the real invocation has returned,
 * or when a queued invocation was cancelled before it could run. */
final class WatchPreparationTask<T> implements Runnable {
    private final Callable<T> loader;
    private final CompletableFuture<T> result=new CompletableFuture<>();
    private Thread runner;
    private boolean begun,cancelled,done;
    WatchPreparationTask(Callable<T> loader){this.loader=Objects.requireNonNull(loader);}
    public synchronized boolean done(){return done;}
    public CompletableFuture<T> result(){return result;}
    public void cancel(){
        synchronized(this){
            if(done)return;
            cancelled=true;
            if(!begun){done=true;result.cancel(false);}
            // Interrupt before releasing the task lock. Otherwise the executor could finish this
            // invocation and reuse the captured thread for an unrelated source before interrupt().
            else if(runner!=null)runner.interrupt();
        }
    }
    @Override public void run(){
        synchronized(this){if(done||begun)return;begun=true;runner=Thread.currentThread();}
        T value=null;Throwable failure=null;
        try{value=loader.call();}catch(Throwable error){failure=error;}
        synchronized(this){
            runner=null;done=true;
            if(cancelled)result.cancel(false);
            else if(failure!=null)result.completeExceptionally(failure);
            else result.complete(value);
        }
    }
}
