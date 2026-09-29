package cn.piq.retro.worker;

/** Private local transport, not a public network protocol or RetroArch Netplay. */
public final class WorkerProtocol {
    public static final int MAGIC = 0x504c5232, VERSION = 3, ACK = 0x4f4b4159;
    public static final int LOAD = 1, RUN = 2, SAVE = 3, RESTORE = 4, RESET = 5, CLOSE = 6, MEMORY = 7;
    public static final int RESTORE_SAVE_MEMORY = 8;
    public static final int VIDEO = 1, AUDIO = 2;
    public static final int MAX_ROM = 64 * 1024 * 1024, MAX_STATE = 16 * 1024 * 1024;
    public static final int MAX_MEMORY = 16 * 1024 * 1024, MAX_VIDEO_BYTES = 32 * 1024 * 1024;
    public static final int MAX_DIMENSION = 4096, MAX_AUDIO_FRAMES = 262144, MAX_RUN_FRAMES = 120;
    public static final int MAX_PORTS = 4, MAX_OPTIONS = 256;
    private WorkerProtocol() {}
}
