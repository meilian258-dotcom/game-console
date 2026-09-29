package cn.piq.fcarcade.home;

import cn.piq.fcarcade.rom.RomRepository;

/** Single-owner, ordered, bounded buffer. finish transfers ownership, avoiding a second large copy. */
public final class CartridgeTransfer {
    private byte[] bytes;
    private int received;
    private final long started;
    public CartridgeTransfer(int total, long now) {
        if (total <= 0 || total > RomRepository.MAX_ROM_BYTES) throw new IllegalArgumentException("传输大小无效");
        bytes = new byte[total];
        started = now;
    }
    public int total() { return bytes == null ? 0 : bytes.length; }
    public int received() { return received; }
    public boolean expired(long now) { return now - started > CartridgeLimits.TRANSFER_TIMEOUT_NANOS; }
    public void append(int offset, byte[] part) {
        if (bytes == null || offset != received || part == null || part.length == 0
                || part.length > CartridgeLimits.CHUNK_BYTES || part.length > bytes.length - received)
            throw new IllegalArgumentException("分块必须连续且不能超过声明长度");
        System.arraycopy(part, 0, bytes, received, part.length);
        received += part.length;
    }
    public byte[] finish() {
        if (bytes == null || received != bytes.length) throw new IllegalStateException("传输未完成");
        byte[] result = bytes;
        bytes = null;
        return result;
    }
}
