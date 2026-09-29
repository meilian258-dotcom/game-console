package cn.piq.fcarcade.skin;

import java.util.Arrays;

public final class SkinTransferBuffer {
    private final String name;
    private final String sha256;
    private final byte[] bytes;
    private int received;

    public SkinTransferBuffer(String name, String sha256, int totalBytes) {
        this.name = SkinDescriptor.normalizeName(name);
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("皮肤 SHA-256 无效");
        }
        if (totalBytes <= 0 || totalBytes > SkinTransferLimits.MAX_PNG_BYTES) {
            throw new IllegalArgumentException("皮肤传输大小无效");
        }
        this.sha256 = sha256;
        this.bytes = new byte[totalBytes];
    }

    public String name() {
        return name;
    }

    public String sha256() {
        return sha256;
    }

    public int totalBytes() {
        return bytes.length;
    }

    public int receivedBytes() {
        return received;
    }

    public boolean complete() {
        return received == bytes.length;
    }

    public void append(int offset, byte[] data) {
        if (offset != received) {
            throw new IllegalArgumentException(
                    "皮肤分块偏移不连续：预期 " + received + "，实际 " + offset);
        }
        if (data == null || data.length == 0
                || data.length > SkinTransferLimits.CHUNK_BYTES
                || received + data.length > bytes.length) {
            throw new IllegalArgumentException("皮肤分块大小无效");
        }
        System.arraycopy(data, 0, bytes, received, data.length);
        received += data.length;
    }

    public byte[] completedBytes() {
        if (!complete()) throw new IllegalStateException("皮肤传输尚未完成");
        return Arrays.copyOf(bytes, bytes.length);
    }
}
