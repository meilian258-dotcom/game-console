package cn.piq.replayquick;

import com.replaymod.replaystudio.protocol.Packet;
import java.io.IOException;

/** Synchronous provenance markers: only a generated RAR location inside its own seek. */
public final class QuickSableScope {
    public record Seek(QuickSableState state, int targetTime) {}
    private static final ThreadLocal<Seek> SEEK = new ThreadLocal<>();
    private static final ThreadLocal<Packet> GENERATED = new ThreadLocal<>();
    @FunctionalInterface public interface IOAction { void run() throws IOException; }

    public static void seek(QuickSableState state, int targetTime, IOAction body) throws IOException {
        Seek previous = SEEK.get();
        SEEK.set(new Seek(state, targetTime));
        try { body.run(); }
        finally { if (previous == null) SEEK.remove(); else SEEK.set(previous); }
    }

    public static void generated(Packet packet, IOAction body) throws IOException {
        Packet previous = GENERATED.get();
        GENERATED.set(packet);
        try { body.run(); }
        finally { if (previous == null) GENERATED.remove(); else GENERATED.set(previous); }
    }

    public static Seek forPacket(Packet packet) {
        return GENERATED.get() == packet ? SEEK.get() : null;
    }

    private QuickSableScope() {}
}
