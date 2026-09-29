package cn.piq.sfcarcade.server;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/** One session/actual connection's admission; unknown hashes never invoke authority callbacks or IO. */
final class SfcDownloadGate {
    static final long RETRY_TICKS = 100;
    private final String romHash;
    private final Object connection;
    private long retryAt;
    private boolean busy;
    SfcDownloadGate(String romHash, Object connection) {
        this.romHash=Objects.requireNonNull(romHash);this.connection=Objects.requireNonNull(connection);
    }
    boolean admit(String requestedHash,Object actualConnection,long tick,BooleanSupplier authorized) {
        if(actualConnection!=connection||!romHash.equals(requestedHash)||tick<0||tick<retryAt||busy)return false;
        retryAt=tick>Long.MAX_VALUE-RETRY_TICKS?Long.MAX_VALUE:tick+RETRY_TICKS;
        busy=true; // Protection events may synchronously reenter this gate.
        try { if(authorized.getAsBoolean())return true; }
        catch(RuntimeException failure){busy=false;throw failure;}
        busy=false;return false;
    }
    void complete(){busy=false;}
}
