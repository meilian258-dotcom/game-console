// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.fcarcade.client.privateplay;

import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/**
 * Worker-only private save transactions. The caller supplies its local identity-scoped root.
 * No public save migration, ROM copies, network access or repair-by-overwrite is performed.
 */
public final class PrivateSaveStore {
    public static final int MAX_STATE_BYTES = 128 * 1024 * 1024;
    public static final int MAX_SRAM_BYTES = 8 * 1024 * 1024;
    private static final long MAX_ARCHIVE = (long) MAX_STATE_BYTES + MAX_SRAM_BYTES + 8192;
    private static final Object IO_LOCK = new Object();
    private static final Set<String> MEMBERS = Set.of("metadata.bin", "state.bin", "sram.bin");
    private final Path root;
    private final Operations operations;

    public record Key(String system, String coreNamespace, String romSha256) {
        public Key {
            if (system == null || !system.matches("[a-z0-9-]{1,24}")
                    || coreNamespace == null || !coreNamespace.matches("[a-zA-Z0-9._/\\-]{1,512}")
                    || romSha256 == null || !romSha256.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Invalid private save identity");
        }
    }
    public record Snapshot(byte[] state, byte[] sram) {
        public Snapshot {
            if (state == null || state.length == 0 || state.length > MAX_STATE_BYTES
                    || sram == null || sram.length > MAX_SRAM_BYTES)
                throw new IllegalArgumentException("Private save exceeds size limits");
            state = state.clone(); sram = sram.clone();
        }
        @Override public byte[] state() { return state.clone(); }
        @Override public byte[] sram() { return sram.clone(); }
    }
    interface Operations {
        default void replace(Path temporary, Path destination) throws IOException {
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
    }
    public PrivateSaveStore(Path saveRoot) { this(saveRoot, new Operations() {}); }
    PrivateSaveStore(Path saveRoot, Operations operations) {
        root = Objects.requireNonNull(saveRoot).toAbsolutePath().normalize().resolve("private-saves-v1");
        this.operations = Objects.requireNonNull(operations);
    }
    Path directory(Key key) {
        return root.resolve(key.system()).resolve(hash(key.coreNamespace().getBytes(StandardCharsets.UTF_8)))
                .resolve(key.romSha256());
    }

    /** Missing means new game; damaged or missing-current-with-backup means stop and preserve. */
    public Optional<Snapshot> load(Key key) throws IOException {
        synchronized (IO_LOCK) {
            Path directory = directory(key);
            if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
                safeExistingParents(directory);
                return Optional.empty();
            }
            directories(directory, false);
            Path latest = directory.resolve("latest.zip"), previous = directory.resolve("previous.zip");
            if (!Files.exists(latest, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.exists(previous, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("私人存档当前文件缺失，旧备份已保留；请先检查，未自动新建");
                return Optional.empty();
            }
            // A known-bad previous generation would also reject save: fail before the player can lose a new run.
            if (Files.exists(previous, LinkOption.NOFOLLOW_LINKS)) {
                try { readArchive(previous, key); }
                catch (IOException invalid) {
                    throw new IOException("私人存档旧备份无法校验，未启动游戏；当前存档与旧备份均已保留", invalid);
                }
            }
            return Optional.of(readArchive(latest, key));
        }
    }

    /** All existing owned archives must verify before either can be replaced. No non-atomic fallback. */
    public void save(Key key, byte[] state, byte[] sram) throws IOException {
        Snapshot snapshot;
        try { snapshot = new Snapshot(state, sram); }
        catch (IllegalArgumentException invalid) { throw new IOException(invalid.getMessage(), invalid); }
        synchronized (IO_LOCK) {
            Path directory = directory(key); directories(directory, true);
            int count = 0;
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
                for (Path ignored : entries) if (++count > 16)
                    throw new IOException("私人存档目录残留过多；原文件保留，请先检查");
            }
            Path latest = directory.resolve("latest.zip"), previous = directory.resolve("previous.zip");
            boolean hasLatest = Files.exists(latest, LinkOption.NOFOLLOW_LINKS);
            boolean hasPrevious = Files.exists(previous, LinkOption.NOFOLLOW_LINKS);
            if (hasLatest) readArchive(latest, key);
            if (hasPrevious) readArchive(previous, key);
            if (!hasLatest && hasPrevious) throw new IOException("私人当前存档缺失，拒绝覆盖保留的旧备份");
            Path temporary = Files.createTempFile(directory, ".private-pending-", ".part");
            Path backup = null;
            try {
                writeArchive(temporary, key, snapshot);
                readArchive(temporary, key);
                if (hasLatest) {
                    backup = Files.createTempFile(directory, ".private-backup-", ".part");
                    copyForced(latest, backup);
                    readArchive(backup, key);
                    directories(directory, false);
                    operations.replace(backup, previous);
                    backup = null;
                }
                directories(directory, false);
                operations.replace(temporary, latest);
                temporary = null;
                readArchive(latest, key);
            } finally {
                cleanup(directory, temporary); cleanup(directory, backup);
            }
        }
    }

    private static void cleanup(Path directory, Path temporary) {
        if (temporary == null) return;
        try { directories(directory, false); Files.deleteIfExists(temporary); }
        catch (IOException | RuntimeException ignored) { /* Preserve unsafe/inaccessible paths. */ }
    }
    private static void copyForced(Path from, Path to) throws IOException {
        safeFile(from);
        try (FileChannel in = FileChannel.open(from, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
             FileChannel out = FileChannel.open(to, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.allocate(32768); long total = 0;
            while (in.read(buffer) != -1) {
                buffer.flip(); total += buffer.remaining();
                if (total > MAX_ARCHIVE) throw new IOException("Private archive changed size");
                while (buffer.hasRemaining()) out.write(buffer);
                buffer.clear();
            }
            out.force(true);
        }
    }
    private static byte[] metadata(Key key, byte[] state, byte[] sram) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            out.writeInt(0x50525631); out.writeInt(1);
            out.writeUTF(key.system()); out.writeUTF(key.coreNamespace()); out.writeUTF(key.romSha256());
            out.writeInt(state.length); out.writeInt(sram.length);
            out.writeUTF(hash(state)); out.writeUTF(hash(sram));
        }
        return buffer.toByteArray();
    }
    private static void writeArchive(Path path, Key key, Snapshot snapshot) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
             ZipOutputStream zip = new ZipOutputStream(Channels.newOutputStream(channel), StandardCharsets.UTF_8)) {
            put(zip, "metadata.bin", metadata(key, snapshot.state, snapshot.sram));
            put(zip, "state.bin", snapshot.state); put(zip, "sram.bin", snapshot.sram);
            zip.finish(); zip.flush(); channel.force(true);
        }
    }
    private static void put(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        CRC32 crc = new CRC32(); crc.update(bytes);
        ZipEntry entry = new ZipEntry(name); entry.setMethod(ZipEntry.STORED);
        entry.setSize(bytes.length); entry.setCompressedSize(bytes.length); entry.setCrc(crc.getValue());
        zip.putNextEntry(entry); zip.write(bytes); zip.closeEntry();
    }
    private record Member(long size, long crc) {}
    private static Snapshot readArchive(Path path, Key key) throws IOException {
        safeFile(path);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            Map<String, Member> central = centralDirectory(channel);
            Map<String, byte[]> members = new HashMap<>();
            channel.position(0);
            try (ZipInputStream zip = new ZipInputStream(Channels.newInputStream(channel), StandardCharsets.UTF_8)) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    String name = entry.getName();
                    int maximum = name.equals("state.bin") ? MAX_STATE_BYTES : name.equals("sram.bin") ? MAX_SRAM_BYTES : 2048;
                    if (!MEMBERS.contains(name) || members.containsKey(name) || entry.isDirectory()
                            || entry.getMethod() != ZipEntry.STORED || entry.getSize() < 0 || entry.getSize() > maximum)
                        throw new IOException("私人存档包含未知/重复成员或大小超限；原件保留");
                    byte[] bytes = zip.readNBytes((int) entry.getSize());
                    if (zip.read() != -1 || bytes.length != entry.getSize()) throw new IOException("私人存档成员被截断或超长");
                    CRC32 crc = new CRC32(); crc.update(bytes); zip.closeEntry();
                    Member declared = central.get(name);
                    if (declared == null || declared.size() != bytes.length || declared.crc() != crc.getValue())
                        throw new IOException("私人存档CRC或长度不符");
                    members.put(name, bytes);
                }
            }
            if (!members.keySet().equals(MEMBERS)) throw new IOException("私人存档成员缺失");
            byte[] state = members.get("state.bin"), sram = members.get("sram.bin");
            if (state.length == 0 || !Arrays.equals(members.get("metadata.bin"), metadata(key, state, sram)))
                throw new IOException("私人存档身份或SHA不符，拒绝载入/覆盖；原件保留");
            return new Snapshot(state, sram);
        }
    }
    private static Map<String, Member> centralDirectory(FileChannel channel) throws IOException {
        long size = channel.size();
        if (size < 22 || size > MAX_ARCHIVE) throw new IOException("Private ZIP size");
        ByteBuffer end = read(channel, size - 22, 22);
        if (end.getInt() != 0x06054b50 || end.getShort() != 0 || end.getShort() != 0
                || Short.toUnsignedInt(end.getShort()) != 3 || Short.toUnsignedInt(end.getShort()) != 3)
            throw new IOException("Private ZIP directory incomplete");
        long bytes = Integer.toUnsignedLong(end.getInt()), offset = Integer.toUnsignedLong(end.getInt());
        if (end.getShort() != 0 || bytes < 1 || bytes > 4096 || offset + bytes != size - 22)
            throw new IOException("Private ZIP directory bounds");
        ByteBuffer directory = read(channel, offset, (int) bytes);
        Map<String, Member> entries = new HashMap<>(); long expectedLocal = 0;
        for (int i = 0; i < 3; i++) {
            if (directory.remaining() < 46 || directory.getInt() != 0x02014b50) throw new IOException("Private ZIP directory entry");
            directory.getShort(); int version = Short.toUnsignedInt(directory.getShort());
            int flags = Short.toUnsignedInt(directory.getShort()), method = Short.toUnsignedInt(directory.getShort());
            int time = Short.toUnsignedInt(directory.getShort()), date = Short.toUnsignedInt(directory.getShort());
            long crc = Integer.toUnsignedLong(directory.getInt());
            long compressed = Integer.toUnsignedLong(directory.getInt()), length = Integer.toUnsignedLong(directory.getInt());
            int names = Short.toUnsignedInt(directory.getShort()), extra = Short.toUnsignedInt(directory.getShort()), comment = Short.toUnsignedInt(directory.getShort());
            int disk = Short.toUnsignedInt(directory.getShort()); directory.getShort(); directory.getInt();
            long local = Integer.toUnsignedLong(directory.getInt());
            if ((flags & ~0x0800) != 0 || method != ZipEntry.STORED || compressed != length || disk != 0
                    || local != expectedLocal || offset - local < 30 || names < 1 || names + extra + comment > directory.remaining())
                throw new IOException("Private ZIP directory fields");
            byte[] name = new byte[names]; directory.get(name); directory.position(directory.position() + extra + comment);
            String member = new String(name, StandardCharsets.UTF_8);
            if (!MEMBERS.contains(member) || entries.put(member, new Member(length, crc)) != null)
                throw new IOException("Private ZIP unexpected/duplicate entry");
            ByteBuffer header = read(channel, local, 30);
            if (header.getInt() != 0x04034b50 || Short.toUnsignedInt(header.getShort()) != version
                    || Short.toUnsignedInt(header.getShort()) != flags || Short.toUnsignedInt(header.getShort()) != method
                    || Short.toUnsignedInt(header.getShort()) != time || Short.toUnsignedInt(header.getShort()) != date
                    || Integer.toUnsignedLong(header.getInt()) != crc || Integer.toUnsignedLong(header.getInt()) != compressed
                    || Integer.toUnsignedLong(header.getInt()) != length)
                throw new IOException("Private ZIP local/central mismatch");
            int localNames = Short.toUnsignedInt(header.getShort()), localExtra = Short.toUnsignedInt(header.getShort());
            long start = local + 30L + localNames + localExtra;
            if (localNames != names || start > offset || length > offset - start
                    || !Arrays.equals(name, read(channel, local + 30, localNames).array()))
                throw new IOException("Private ZIP local name/data bounds");
            expectedLocal = start + length;
        }
        if (directory.hasRemaining() || expectedLocal != offset) throw new IOException("Private ZIP data gap/extra directory");
        return entries;
    }
    private static ByteBuffer read(FileChannel channel, long position, int size) throws IOException {
        ByteBuffer result = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        while (result.hasRemaining()) if (channel.read(result, position + result.position()) < 1)
            throw new IOException("Private archive changed during read");
        return result.flip();
    }
    private static void safeExistingParents(Path path) throws IOException {
        Path cursor = path.getRoot();
        for (Path part : path) {
            cursor = cursor.resolve(part);
            if (!Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) return;
            directories(cursor, false);
        }
    }
    private static void directories(Path path, boolean create) throws IOException {
        Path cursor = path.getRoot();
        for (Path part : path) {
            cursor = cursor.resolve(part);
            if (create && !Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
                try { Files.createDirectory(cursor); } catch (FileAlreadyExistsException ignored) {}
            }
            BasicFileAttributes a = Files.readAttributes(cursor, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!a.isDirectory() || a.isSymbolicLink() || a.isOther()) throw new IOException("私人目录不安全，拒绝跟随链接");
        }
    }
    private static void safeFile(Path path) throws IOException {
        BasicFileAttributes a = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!a.isRegularFile() || a.isSymbolicLink() || a.isOther() || a.size() < 1 || a.size() > MAX_ARCHIVE)
            throw new IOException("私人存档文件不安全或大小超限");
    }
    public static String sha256(byte[] bytes) { return hash(Objects.requireNonNull(bytes)); }
    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
