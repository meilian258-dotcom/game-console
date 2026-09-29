package cn.piq.fcarcade.runtime;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Small, bounded central-directory audit before ZipFile sees a pack; no extraction by entry name. */
final class RuntimePack {
    private RuntimePack() {}

    static void validate(Path path, List<RuntimeCatalog.Artifact> artifacts) throws IOException {
        Map<String, RuntimeCatalog.Artifact> allowed = new HashMap<>();
        Set<String> directories = new HashSet<>();
        long total = 0;
        for (var artifact : artifacts) {
            allowed.put(artifact.relativePath(), artifact); total += artifact.bytes();
            String name = artifact.relativePath();
            for (int slash = name.indexOf('/'); slash >= 0; slash = name.indexOf('/', slash + 1))
                directories.add(name.substring(0, slash + 1));
        }
        try (FileChannel file = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            long length = file.size();
            if (length < 22 || length > total + 1024 * 1024) throw invalid("安装包大小越界");
            int tailLength = (int) Math.min(length, 65557);
            ByteBuffer tail = read(file, length - tailLength, tailLength);
            int end = -1;
            for (int i = tailLength - 22; i >= 0; i--)
                if (tail.getInt(i) == 0x06054b50 && i + 22 + u16(tail, i + 20) == tailLength) { end = i; break; }
            if (end < 0) throw invalid("缺少 ZIP 目录");
            if (u16(tail, end + 4) != 0 || u16(tail, end + 6) != 0
                    || u16(tail, end + 8) != u16(tail, end + 10)) throw invalid("不支持分卷 ZIP");
            int count = u16(tail, end + 10);
            long centralSize = u32(tail, end + 12), centralOffset = u32(tail, end + 16);
            if (count == 65535 || centralSize == 0xffffffffL || centralOffset == 0xffffffffL
                    || count < artifacts.size() || count > artifacts.size() + directories.size()
                    || centralSize > 65536 || centralOffset + centralSize != length - tailLength + end)
                throw invalid("安装包目录边界或条目数错误（不支持 ZIP64）");
            ByteBuffer central = read(file, centralOffset, (int) centralSize);
            Set<String> seen = new HashSet<>();
            int cursor = 0;
            for (int index = 0; index < count; index++) {
                if (cursor + 46 > central.limit() || central.getInt(cursor) != 0x02014b50) throw invalid("ZIP 目录损坏");
                int flags = u16(central, cursor + 8), method = u16(central, cursor + 10);
                int nameLength = u16(central, cursor + 28), extra = u16(central, cursor + 30), comment = u16(central, cursor + 32);
                long compressed = u32(central, cursor + 20), size = u32(central, cursor + 24);
                long attrs = u32(central, cursor + 38), offset = u32(central, cursor + 42);
                if (cursor + 46L + nameLength + extra + comment > central.limit() || nameLength > 200
                        || (flags & 1) != 0 || (flags & 64) != 0 || (method != 0 && method != 8)
                        || u16(central, cursor + 34) != 0 || offset >= centralOffset || compressed > total + 65536
                        || offset + 30 + compressed > centralOffset) throw invalid("ZIP 条目边界/格式不安全");
                byte[] nameBytes = new byte[nameLength];
                central.get(cursor + 46, nameBytes);
                for (byte value : nameBytes) if (value < 32 || value > 126) throw invalid("仅允许固定 ASCII 文件名");
                String name = new String(nameBytes, StandardCharsets.US_ASCII);
                if (!seen.add(name)) throw invalid("安装包有重复文件");
                int unixType = (int) ((attrs >>> 16) & 0170000);
                boolean directory = name.endsWith("/");
                if ((attrs & 0x400) != 0 || (unixType != 0 && unixType != (directory ? 0040000 : 0100000)))
                    throw invalid("安装包含链接/特殊文件");
                if (directory) {
                    if (!directories.contains(name) || size != 0) throw invalid("安装包含未知目录");
                } else {
                    var expected = allowed.get(name);
                    if (expected == null || expected.bytes() != size || compressed == 0)
                        throw invalid("安装包文件不在固定清单内或大小不匹配：" + name);
                }
                cursor += 46 + nameLength + extra + comment;
            }
            if (cursor != central.limit() || !seen.containsAll(allowed.keySet())) throw invalid("安装包文件不完整");
        }
    }

    private static ByteBuffer read(FileChannel file, long offset, int length) throws IOException {
        if (offset < 0 || length < 0) throw invalid("ZIP 越界");
        ByteBuffer data = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
        while (data.hasRemaining()) { int read = file.read(data, offset + data.position()); if (read <= 0) throw invalid("ZIP 截断"); }
        return data.flip();
    }
    private static int u16(ByteBuffer b, int at) { return Short.toUnsignedInt(b.getShort(at)); }
    private static long u32(ByteBuffer b, int at) { return Integer.toUnsignedLong(b.getInt(at)); }
    private static IOException invalid(String message) { return new IOException(message); }
}
