// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.retro.libretro;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.util.*;

/**
 * Opt-in native-memory store; never used implicitly by transient/netplay snapshots.
 * Caller supplies a private, already-created world/owner/slot directory, never a global ROM directory.
 * Single owner thread and exclusive writer. No automatic migration, deletion or corrupt-file recovery.
 */
public final class LibretroMemoryStore implements AutoCloseable {
    private static final int MAGIC = 0x504c4d31, HEADER = 80;
    private final Thread owner = Thread.currentThread();
    private final byte[] identity;
    private final Path directory, file, previous;
    private final FileChannel channel;
    private final FileLock lock;
    private boolean closed;

    public LibretroMemoryStore(Path ownerSlotDirectory, byte[] identity) throws IOException {
        if (identity == null || identity.length != 32) throw new IllegalArgumentException("Persistence identity");
        this.identity = identity.clone();
        Path root = ownerSlotDirectory.toAbsolutePath().normalize();
        directory(root);
        directory = root.resolve(HexFormat.of().formatHex(identity));
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(directory);
        directory(directory);
        file = directory.resolve("memory.bin"); previous = directory.resolve("memory.previous.bin");
        Path lease = directory.resolve("writer.lock");
        regularIfPresent(lease);
        channel = FileChannel.open(lease, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try {
            lock = channel.tryLock();
            if (lock == null) throw new IOException("Native save already in use");
        } catch (IOException | RuntimeException error) {
            channel.close(); throw new IOException("Native save already in use or unavailable", error);
        }
    }

    /** False means no save exists; corrupt/incompatible files throw and remain untouched. */
    public boolean restore(LibretroProcess process) throws IOException {
        return restore((LibretroRuntime) process);
    }

    /** Backend-neutral overload; the original process signature remains binary-compatible. */
    public boolean restore(LibretroRuntime process) throws IOException {
        compatible(process);
        Optional<LibretroSaveMemory> saved = read();
        if (saved.isEmpty()) return false;
        process.restoreSaveMemory(saved.get()); return true;
    }

    /** Capture before closing the core. Empty unsupported memory never overwrites an existing save. */
    public boolean save(LibretroProcess process) throws IOException {
        return save((LibretroRuntime) process);
    }

    /** Backend-neutral overload; identity and on-disk format remain unchanged. */
    public boolean save(LibretroRuntime process) throws IOException {
        compatible(process); return write(process.saveMemory());
    }

    private void compatible(LibretroRuntime process) throws IOException {
        check();
        if (!MessageDigest.isEqual(identity, process.persistenceIdentity()))
            throw new IOException("Native save belongs to different content/core/options");
    }

    Optional<LibretroSaveMemory> read() throws IOException {
        check(); directory(directory);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        return Optional.of(decode(readBounded(file)));
    }

    boolean write(LibretroSaveMemory data) throws IOException {
        check(); Objects.requireNonNull(data); directory(directory);
        byte[] old = null;
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) { old = readBounded(file); decode(old); }
        if (data.isEmpty()) {
            if (old != null) throw new IOException("Empty native memory cannot replace an existing save");
            return false;
        }
        byte[] next = encode(data);
        if (Arrays.equals(old, next)) return true;
        // Fail before changing either file if an existing backup is redirected/non-regular.
        regularIfPresent(previous);
        if (old != null) atomic(previous, old);
        atomic(file, next);
        return true;
    }

    private byte[] encode(LibretroSaveMemory data) {
        byte[] ram = data.ram(), rtc = data.rtc();
        ByteBuffer b = ByteBuffer.allocate(HEADER + ram.length + rtc.length);
        b.putInt(MAGIC).putInt(1).put(identity).putInt(ram.length).putInt(rtc.length).put(ram).put(rtc);
        b.put(hash(b.array(), b.position())); return b.array();
    }

    private LibretroSaveMemory decode(byte[] bytes) throws IOException {
        if (bytes.length < HEADER || bytes.length > HEADER + LibretroSaveMemory.MAX_BYTES)
            throw new IOException("Native save size; original preserved");
        ByteBuffer b = ByteBuffer.wrap(bytes);
        if (b.getInt() != MAGIC || b.getInt() != 1) throw new IOException("Native save format; original preserved");
        byte[] storedIdentity = new byte[32]; b.get(storedIdentity);
        int ram = b.getInt(), rtc = b.getInt();
        if (!MessageDigest.isEqual(identity, storedIdentity) || ram < 0 || rtc < 0
                || (long) ram + rtc < 1 || (long) ram + rtc != bytes.length - HEADER
                || !MessageDigest.isEqual(hash(bytes, bytes.length - 32), Arrays.copyOfRange(bytes, bytes.length - 32, bytes.length)))
            throw new IOException("Native save identity/length/checksum mismatch; original preserved");
        byte[] saveRam = new byte[ram], clock = new byte[rtc]; b.get(saveRam); b.get(clock);
        return new LibretroSaveMemory(saveRam, clock);
    }

    private static byte[] hash(byte[] bytes, int length) {
        try { var digest = MessageDigest.getInstance("SHA-256"); digest.update(bytes, 0, length); return digest.digest(); }
        catch (NoSuchAlgorithmException error) { throw new AssertionError(error); }
    }

    private static void directory(Path path) throws IOException {
        for (Path cursor = path; cursor != null; cursor = cursor.getParent()) {
            var a = Files.readAttributes(cursor, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!a.isDirectory() || a.isSymbolicLink() || a.isOther() || !cursor.toRealPath().equals(cursor))
                throw new IOException("Native save directory redirected or unavailable");
        }
    }

    private static void regularIfPresent(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        var a = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!a.isRegularFile() || a.isSymbolicLink() || a.isOther()) throw new IOException("Native save target redirected");
    }

    private static byte[] readBounded(Path path) throws IOException {
        regularIfPresent(path);
        var before = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        int max = HEADER + LibretroSaveMemory.MAX_BYTES;
        if (before.size() < HEADER || before.size() > max) throw new IOException("Native save file bounds");
        byte[] data;
        try (var in = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) { data = in.readNBytes(max + 1); }
        var after = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!after.isRegularFile() || after.isSymbolicLink() || after.isOther() || data.length != before.size()
                || after.size() != before.size() || !Objects.equals(before.fileKey(), after.fileKey())
                || !before.lastModifiedTime().equals(after.lastModifiedTime()))
            throw new IOException("Native save changed during read");
        return data;
    }

    private void atomic(Path target, byte[] data) throws IOException {
        directory(directory); regularIfPresent(target);
        Path pending = Files.createTempFile(directory, "pending-", ".tmp");
        try {
            try (var output = FileChannel.open(pending, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                var buffer = ByteBuffer.wrap(data); while (buffer.hasRemaining()) output.write(buffer); output.force(true);
            }
            Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(pending); }
    }

    private void check() {
        if (Thread.currentThread() != owner || closed) throw new IllegalStateException("Native save store owner/closed");
    }
    @Override public void close() throws IOException {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Native save store owner");
        if (!closed) { closed = true; try { lock.release(); } finally { channel.close(); } }
    }
}
