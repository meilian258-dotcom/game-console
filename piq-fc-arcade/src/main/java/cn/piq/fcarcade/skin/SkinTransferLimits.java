package cn.piq.fcarcade.skin;

public final class SkinTransferLimits {
    public static final int TARGET_SIZE = 2048;
    public static final int MAX_SOURCE_DIMENSION = 2048;
    public static final int MAX_SOURCE_BYTES = 16 * 1024 * 1024;
    public static final int MAX_PNG_BYTES = 16 * 1024 * 1024;
    public static final int CHUNK_BYTES = 64 * 1024;
    public static final int CHUNKS_PER_TICK = 2;
    public static final int MAX_NAME_CHARS = 80;
    public static final int MAX_CATALOG_ENTRIES = 128;
    public static final int MAX_TEXTURES = 8;
    public static final int MAX_TRANSFERS = 4;
    public static final long MAX_IN_FLIGHT_BYTES = 32L * 1024 * 1024;
    public static final int GLOBAL_CHUNKS_PER_TICK = 4;
    public static final int TRANSFER_TIMEOUT_TICKS = 20 * 60;

    private SkinTransferLimits() {
    }
}
