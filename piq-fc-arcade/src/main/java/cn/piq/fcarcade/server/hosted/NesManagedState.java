package cn.piq.fcarcade.server.hosted;
import java.util.Objects;
import java.util.function.Consumer;
/** Existing FC session owns save permissions; the core only publishes bounded snapshots off-thread. */
public final class NesManagedState {
    private final String romSha;private final byte[] initial;private final Consumer<byte[]> sink;private final Runnable resetComplete;
    public NesManagedState(String romSha,byte[] initial,Consumer<byte[]> sink){
        this(romSha,initial,sink,()->{});
    }
    public NesManagedState(String romSha,byte[] initial,Consumer<byte[]> sink,Runnable resetComplete){
        if(romSha==null||!romSha.matches("[a-fA-F0-9]{64}")||initial!=null&&initial.length>2*1024*1024)throw new IllegalArgumentException("Managed NES identity/state bounds");
        this.romSha=romSha;this.initial=initial==null?new byte[0]:initial.clone();this.sink=Objects.requireNonNull(sink);this.resetComplete=Objects.requireNonNull(resetComplete);
    }
    public String romSha(){return romSha;}
    public byte[] initial(){return initial.clone();}
    public void publish(byte[] state){if(state==null||state.length<1||state.length>2*1024*1024)throw new IllegalArgumentException("Managed NES snapshot bounds");sink.accept(state.clone());}
    void resetComplete(){resetComplete.run();}
}
