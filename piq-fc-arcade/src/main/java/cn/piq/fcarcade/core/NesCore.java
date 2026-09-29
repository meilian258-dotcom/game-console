package cn.piq.fcarcade.core;

/** A core belongs to its construction thread, including state copies and close. */
public interface NesCore extends AutoCloseable {
    int WIDTH = 256;
    int HEIGHT = 240;
    int RGBA_BYTES = WIDTH * HEIGHT * 4;
    int CPU_RAM_BYTES = 0x800;

    void loadRom(byte[] rom);

    void reset();

    void setControllerState(int player, int buttonMask);

    /** Optional port-two light gun; old cores and sessions remain controller-only. */
    default boolean supportsZapper() { return false; }

    /** Native 256x240 aim. Offscreen is dark; trigger is independent of the photosensor. */
    default void setZapperState(int x, int y, boolean offscreen, boolean trigger) {
        throw new UnsupportedOperationException("This NES core does not support a Zapper");
    }

    /** Hosts must segregate states when opting into a different core module. */
    default String stateNamespace() { return "nes-legacy-v1"; }

    /** Nonblocking diagnostics from any thread; must not invoke the core or await its owner. */
    default String diagnosticError() { return ""; }

    void runFrame();

    void copyFrameRgba(byte[] destination);

    int copyAudioSamples(float[] destination);

    void copyCpuRam(byte[] destination);

    byte[] saveTransientState();

    void loadTransientState(byte[] state);

    /** Explicit owner-thread persistence, separate from live replay snapshots. */
    default byte[] savePersistentState() { return saveTransientState(); }
    /** Legacy cores keep their original format; libretro adds a native-memory bundle. */
    default void loadPersistentState(byte[] state) { loadTransientState(state); }

    @Override
    void close();
}
