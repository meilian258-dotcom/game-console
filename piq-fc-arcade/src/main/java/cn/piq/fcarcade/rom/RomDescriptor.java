package cn.piq.fcarcade.rom;

import java.nio.file.Path;
import java.util.Arrays;

public record RomDescriptor(
        Path path,
        String fileName,
        String sha256,
        INesHeader header,
        byte[] bytes
) {
    public RomDescriptor {
        bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }

    public int size() {
        return bytes.length;
    }

    public byte[] copyBytes(int from, int to) {
        if (from < 0 || to < from || to > bytes.length) {
            throw new IndexOutOfBoundsException("ROM 字节范围无效");
        }
        return Arrays.copyOfRange(bytes, from, to);
    }
}
