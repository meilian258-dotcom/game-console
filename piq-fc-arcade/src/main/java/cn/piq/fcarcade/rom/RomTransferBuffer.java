package cn.piq.fcarcade.rom;

import java.util.Arrays;

public final class RomTransferBuffer {
    private final String fileName;
    private final String sha256;
    private final byte[] bytes;
    private int received;

    public RomTransferBuffer(String fileName, String sha256, int totalBytes) {
        if (fileName == null || fileName.isBlank()
                || fileName.length() > RomTransferLimits.MAX_FILE_NAME_CHARS) {
            throw new IllegalArgumentException("ROM 文件名无效");
        }
        if (sha256 == null || !sha256.matches(RomRepository.SHA256_PATTERN)) {
            throw new IllegalArgumentException("ROM SHA-256 无效");
        }
        if (totalBytes <= 0 || totalBytes > RomRepository.MAX_ROM_BYTES) {
            throw new IllegalArgumentException("ROM 传输大小无效");
        }
        this.fileName = fileName;
        this.sha256 = sha256;
        this.bytes = new byte[totalBytes];
    }

    public String fileName() {
        return fileName;
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
                    "ROM 分块偏移不连续：预期 " + received + "，实际 " + offset);
        }
        if (data == null || data.length == 0
                || data.length > RomTransferLimits.CHUNK_BYTES
                || received + data.length > bytes.length) {
            throw new IllegalArgumentException("ROM 分块大小无效");
        }
        System.arraycopy(data, 0, bytes, received, data.length);
        received += data.length;
    }

    public byte[] completedBytes() {
        if (!complete()) throw new IllegalStateException("ROM 传输尚未完成");
        return Arrays.copyOf(bytes, bytes.length);
    }
}
