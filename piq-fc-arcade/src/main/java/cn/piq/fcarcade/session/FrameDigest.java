package cn.piq.fcarcade.session;

public final class FrameDigest {
    private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private FrameDigest() {
    }

    public static long calculate(byte[] bytes) {
        long hash = FNV_OFFSET_BASIS;
        for (byte value : bytes) {
            hash ^= value & 0xFFL;
            hash *= FNV_PRIME;
        }
        return hash;
    }
}
