// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfchome.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Session-isolated recovery material only. No restore, migration, or cross-session pruning. */
final class SfcRecoveryBackups {
    static final int MAX_STATE = 128 * 1024 * 1024;
    static final int MAX_SRAM = 8 * 1024 * 1024;
    private static final int MAX_DIRECTORY_ENTRIES = 16;
    private static final long MAX_ARCHIVE = (long) MAX_STATE + MAX_SRAM + 8192;
    private static final Pattern ARCHIVE = Pattern.compile("backup-([0-9]{20})-([0-9a-f-]{36})\\.zip");
    private static final Set<String> MEMBERS = Set.of("state.bin", "sram.bin", "metadata.txt");
    private static final String FORMAT = "sfc-local-recovery-v2";

    record Result(Path path, String cleanupWarning) {}
    private record Archive(Path path, long sequence) {}
    private record Member(long size, long crc, String sha) {}

    interface Operations {
        default void commit(Path temporary, Path destination) throws IOException {
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
        }
        default void remove(Path archive) throws IOException { Files.delete(archive); }
    }
    private static final Operations FILES = new Operations() {};
    private SfcRecoveryBackups() {}
    private static Path storageRoot(Path game)throws IOException{try{return cn.piq.retro.storage.ConsoleStorage.root(game);}catch(java.io.UncheckedIOException e){throw e.getCause();}}

    static Result save(Path game, String sha, UUID session, byte[] state, byte[] sram, int frame) throws IOException {
        return save(game, sha, session, state, sram, frame, FILES);
    }

    static synchronized Result save(Path game, String sha, UUID session, byte[] state, byte[] sram,
                                    int frame, Operations operations) throws IOException {
        if (game == null || sha == null || !sha.matches("[0-9a-f]{64}") || session == null
                || state == null || state.length < 1 || state.length > MAX_STATE
                || sram == null || sram.length > MAX_SRAM || frame < 0)
            throw new IOException("SFC 恢复备份参数或大小不符合限制");
        Path root = storageRoot(game).resolve("piq-sfc-home/local-backups")
                .resolve(sha).resolve("sessions").resolve(session.toString());
        directories(root, true);
        List<Archive> previous = archives(root);
        // A prior cleanup failure may leave three complete files; never grow without bound.
        if (previous.size() > 2) throw new IOException("本会话已有未完成轮转的 SFC 备份；旧文件均已保留，请先检查本机备份目录");
        long sequence = previous.stream().mapToLong(Archive::sequence).max().orElse(0);
        if (sequence == Long.MAX_VALUE) throw new IOException("SFC 备份序号达到上限");
        sequence++;
        String filename = String.format(Locale.ROOT, "backup-%020d-%s.zip", sequence, UUID.randomUUID());
        Path destination = root.resolve(filename);
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) throw new IOException("SFC 新备份目标已被占用");
        String metadata = "format=" + FORMAT + "\nromSha=" + sha + "\nsession=" + session
                + "\nsequence=" + sequence + "\nframe=" + frame + "\nstateSha=" + hash(state)
                + "\nsramSha=" + hash(sram) + "\nstateBytes=" + state.length + "\nsramBytes=" + sram.length
                + "\nnotice=Local recovery material only; not auto-loaded or a server save.\n";
        Path temporary = Files.createTempFile(root, ".pending-sfc-", ".part");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                 ZipOutputStream zip = new ZipOutputStream(Channels.newOutputStream(channel), StandardCharsets.UTF_8)) {
                put(zip, "state.bin", state);
                put(zip, "sram.bin", sram);
                put(zip, "metadata.txt", metadata.getBytes(StandardCharsets.UTF_8));
                zip.finish(); zip.flush(); channel.force(true);
            }
            directories(root, false);
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) throw new IOException("SFC 新备份目标已被占用");
            operations.commit(temporary, destination); // No non-atomic fallback.
            temporary = null;
            verify(destination, sha, session, sequence);
            String warning = "";
            if (previous.size() == 2) {
                Archive oldest = previous.stream().min(Comparator.comparingLong(Archive::sequence)).orElseThrow();
                try {
                    directories(root, false);
                    verify(oldest.path(), sha, session, oldest.sequence());
                    operations.remove(oldest.path());
                } catch (IOException | RuntimeException failure) {
                    warning = "SFC 新备份已提交，但旧备份轮转失败；旧文件已保留：" + failure.getMessage();
                }
            }
            return new Result(destination, warning);
        } finally {
            if (temporary != null) {
                // This exact temporary file is the only uncommitted data eligible for cleanup.
                try { directories(root, false); Files.deleteIfExists(temporary); }
                catch (IOException | RuntimeException ignored) { /* Preserve rather than follow an unsafe path. */ }
            }
        }
    }

    private static List<Archive> archives(Path root) throws IOException {
        List<Archive> result = new ArrayList<>();
        try (DirectoryStream<Path> children = Files.newDirectoryStream(root)) {
            int count = 0;
            for (Path child : children) {
                if (++count > MAX_DIRECTORY_ENTRIES) throw new IOException("SFC 本会话备份目录条目过多；未清理未知文件");
                var match = ARCHIVE.matcher(child.getFileName().toString());
                if (!match.matches()) continue; // Unknown files and crashed staging files are never deleted.
                try {
                    if (!UUID.fromString(match.group(2)).toString().equals(match.group(2))) continue;
                    long sequence = Long.parseLong(match.group(1));
                    if (sequence < 1) continue;
                    file(child, MAX_ARCHIVE);
                    result.add(new Archive(child, sequence));
                } catch (IllegalArgumentException invalid) { /* Unknown name is not ours to delete. */ }
            }
        }
        return result;
    }

    private static void verify(Path path, String sha, UUID session, long sequence) throws IOException {
        file(path, MAX_ARCHIVE);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            Map<String, Member> central = centralDirectory(channel);
            Map<String, Member> actual = new HashMap<>();
            byte[] text = null;
            channel.position(0);
            try (ZipInputStream zip = new ZipInputStream(Channels.newInputStream(channel), StandardCharsets.UTF_8)) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    String name = entry.getName();
                    if (entry.isDirectory() || !MEMBERS.contains(name) || actual.containsKey(name)
                            || entry.getMethod() != ZipEntry.STORED) throw new IOException("SFC 旧备份包含未知或重复成员，已保留");
                    int maximum = name.equals("state.bin") ? MAX_STATE : name.equals("sram.bin") ? MAX_SRAM : 2048;
                    int minimum = name.equals("sram.bin") ? 0 : 1;
                    if (entry.getSize() < minimum || entry.getSize() > maximum) throw new IOException("SFC 备份成员大小超限");
                    var digest = digest(); CRC32 crc = new CRC32(); long total = 0; int count;
                    byte[] buffer = new byte[32768];
                    var metadata = name.equals("metadata.txt") ? new java.io.ByteArrayOutputStream() : null;
                    while ((count = zip.read(buffer)) != -1) {
                        total += count;
                        if (total > maximum || total > entry.getSize()) throw new IOException("SFC 备份成员解压超限");
                        digest.update(buffer, 0, count); crc.update(buffer, 0, count);
                        if (metadata != null) metadata.write(buffer, 0, count);
                    }
                    zip.closeEntry();
                    Member member = new Member(total, crc.getValue(), HexFormat.of().formatHex(digest.digest()));
                    Member declared = central.get(name);
                    if (declared == null || total != entry.getSize() || total != declared.size() || member.crc() != declared.crc())
                        throw new IOException("SFC 备份成员大小或 CRC 不一致");
                    actual.put(name, member);
                    if (metadata != null) text = metadata.toByteArray();
                }
            }
            if (!actual.keySet().equals(MEMBERS) || text == null) throw new IOException("SFC 旧备份成员不完整，已保留");
            Map<String, String> fields = new HashMap<>();
            for (String line : new String(text, StandardCharsets.UTF_8).split("\n")) {
                int separator = line.indexOf('=');
                if (separator < 1 || fields.put(line.substring(0, separator), line.substring(separator + 1)) != null)
                    throw new IOException("SFC 备份元数据格式不符");
            }
            if (!fields.keySet().equals(Set.of("format", "romSha", "session", "sequence", "frame", "stateSha", "sramSha", "stateBytes", "sramBytes", "notice"))
                    || !FORMAT.equals(fields.get("format")) || !sha.equals(fields.get("romSha"))
                    || !session.toString().equals(fields.get("session")) || !Long.toString(sequence).equals(fields.get("sequence")))
                throw new IOException("SFC 旧备份身份不符，已保留");
            try {
                if (Integer.parseInt(fields.get("frame")) < 0) throw new NumberFormatException();
                verifyMember(actual.get("state.bin"), fields.get("stateBytes"), fields.get("stateSha"));
                verifyMember(actual.get("sram.bin"), fields.get("sramBytes"), fields.get("sramSha"));
            } catch (NumberFormatException invalid) { throw new IOException("SFC 备份帧号不符", invalid); }
        }
    }

    private static void verifyMember(Member member, String expectedBytes, String expectedHash) throws IOException {
        if (!Long.toString(member.size()).equals(expectedBytes) || !member.sha().equals(expectedHash))
            throw new IOException("SFC 备份成员大小或摘要不符");
    }

    private static Map<String, Member> centralDirectory(FileChannel channel) throws IOException {
        long size = channel.size();
        if (size < 22 || size > MAX_ARCHIVE) throw new IOException("SFC 备份 ZIP 大小不符");
        ByteBuffer end = read(channel, size - 22, 22);
        if (end.getInt() != 0x06054b50 || end.getShort() != 0 || end.getShort() != 0
                || Short.toUnsignedInt(end.getShort()) != 3 || Short.toUnsignedInt(end.getShort()) != 3)
            throw new IOException("SFC 备份 ZIP 目录不完整");
        long bytes = Integer.toUnsignedLong(end.getInt()), offset = Integer.toUnsignedLong(end.getInt());
        if (end.getShort() != 0 || bytes < 1 || bytes > 4096 || offset + bytes != size - 22)
            throw new IOException("SFC 备份 ZIP 目录边界不符");
        ByteBuffer directory = read(channel, offset, (int) bytes);
        Map<String, Member> entries = new HashMap<>();
        long expectedLocal = 0;
        for (int i = 0; i < 3; i++) {
            if (directory.remaining() < 46 || directory.getInt() != 0x02014b50) throw new IOException("SFC 备份 ZIP 目录缺项");
            directory.getShort(); int version = Short.toUnsignedInt(directory.getShort());
            int flags = Short.toUnsignedInt(directory.getShort()), method = Short.toUnsignedInt(directory.getShort());
            int time = Short.toUnsignedInt(directory.getShort()), date = Short.toUnsignedInt(directory.getShort());
            long crc = Integer.toUnsignedLong(directory.getInt());
            long compressed = Integer.toUnsignedLong(directory.getInt()), length = Integer.toUnsignedLong(directory.getInt());
            int nameBytes = Short.toUnsignedInt(directory.getShort()), extra = Short.toUnsignedInt(directory.getShort()), comment = Short.toUnsignedInt(directory.getShort());
            int disk = Short.toUnsignedInt(directory.getShort()); directory.getShort(); directory.getInt();
            long local = Integer.toUnsignedLong(directory.getInt());
            if ((flags & ~0x0800) != 0 || method != ZipEntry.STORED || compressed != length || disk != 0
                    || local != expectedLocal || offset - local < 30 || nameBytes < 1
                    || nameBytes + extra + comment > directory.remaining())
                throw new IOException("SFC 备份 ZIP 目录字段不符");
            byte[] name = new byte[nameBytes]; directory.get(name); directory.position(directory.position() + extra + comment);
            String member = new String(name, StandardCharsets.UTF_8);
            if (!MEMBERS.contains(member) || entries.put(member, new Member(length, crc, "")) != null)
                throw new IOException("SFC 备份 ZIP 目录含未知或重复项");
            ByteBuffer header = read(channel, local, 30);
            if (header.getInt() != 0x04034b50 || Short.toUnsignedInt(header.getShort()) != version
                    || Short.toUnsignedInt(header.getShort()) != flags || Short.toUnsignedInt(header.getShort()) != method
                    || Short.toUnsignedInt(header.getShort()) != time || Short.toUnsignedInt(header.getShort()) != date
                    || Integer.toUnsignedLong(header.getInt()) != crc || Integer.toUnsignedLong(header.getInt()) != compressed
                    || Integer.toUnsignedLong(header.getInt()) != length)
                throw new IOException("SFC 备份 ZIP 本地头与中央目录不一致");
            int localNameBytes = Short.toUnsignedInt(header.getShort()), localExtra = Short.toUnsignedInt(header.getShort());
            long dataStart = local + 30L + localNameBytes + localExtra;
            if (localNameBytes != nameBytes || dataStart > offset || length > offset - dataStart
                    || !Arrays.equals(name, read(channel, local + 30, localNameBytes).array()))
                throw new IOException("SFC 备份 ZIP 本地名称或数据边界不一致");
            expectedLocal = dataStart + length;
        }
        if (directory.hasRemaining() || expectedLocal != offset) throw new IOException("SFC 备份 ZIP 目录含额外内容或数据缺口");
        return entries;
    }

    private static ByteBuffer read(FileChannel channel, long position, int size) throws IOException {
        ByteBuffer result = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        while (result.hasRemaining()) {
            int count = channel.read(result, position + result.position());
            if (count < 1) throw new IOException("读取 SFC 备份时文件发生变化");
        }
        return result.flip();
    }

    private static void put(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        CRC32 crc = new CRC32(); crc.update(bytes);
        ZipEntry entry = new ZipEntry(name); entry.setMethod(ZipEntry.STORED);
        entry.setSize(bytes.length); entry.setCompressedSize(bytes.length); entry.setCrc(crc.getValue());
        zip.putNextEntry(entry); zip.write(bytes); zip.closeEntry();
    }

    private static void directories(Path path, boolean create) throws IOException {
        Path absolute = path.toAbsolutePath().normalize(), cursor = absolute.getRoot();
        for (Path part : absolute) {
            cursor = cursor.resolve(part);
            if (create && !Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
                try { Files.createDirectory(cursor); } catch (FileAlreadyExistsException ignored) {}
            }
            BasicFileAttributes attributes = Files.readAttributes(cursor, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther())
                throw new IOException("SFC 备份目录不是安全的普通目录");
        }
    }

    private static void file(Path path, long maximum) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.isOther()
                || attributes.size() < 1 || attributes.size() > maximum)
            throw new IOException("SFC 备份不是安全的普通文件或大小超限");
    }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static String hash(byte[] bytes) { return HexFormat.of().formatHex(digest().digest(bytes)); }
}
