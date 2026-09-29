package cn.piq.flashbox.runtime;

/** A cancelled background launch must be cleaned up even when the game event loop has ended. */
public final class StartTicket {
    private boolean cancelled;
    private AutoCloseable resource;
    public synchronized boolean cancelled() { return cancelled; }
    public synchronized boolean register(AutoCloseable value) {
        if(cancelled){close(value);return false;}
        if(resource!=null)throw new IllegalStateException("Already registered");
        resource=value;return true;
    }
    public synchronized void cancel() { cancelled=true;close(resource);resource=null; }
    private static void close(AutoCloseable value) { if(value!=null)try{value.close();}catch(Exception ignored){} }
}
