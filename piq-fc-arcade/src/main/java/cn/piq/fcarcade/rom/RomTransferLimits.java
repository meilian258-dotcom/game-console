package cn.piq.fcarcade.rom;

public final class RomTransferLimits {
    public static final int CHUNK_BYTES = 128 * 1024;
    public static final int MAX_CATALOG_ENTRIES = 256;
    public static final int MAX_FILE_NAME_CHARS = 128;
    public static final int CHUNKS_PER_TICK = 2;
    public static final int TRANSFER_TIMEOUT_TICKS = 20 * 60;

    private RomTransferLimits() {
    }
}
