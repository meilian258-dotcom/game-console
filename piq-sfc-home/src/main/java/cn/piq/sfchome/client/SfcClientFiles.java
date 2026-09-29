// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfchome.client;

import cn.piq.sfcarcade.core.SfcRomImage;
import java.io.IOException;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Only explicit user-selected imports and fixed hash-addressed local cache paths. */
final class SfcClientFiles {
    static final int MAX_ROM = 32 * 1024 * 1024;
    private SfcClientFiles() {}
    static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    static byte[] importRom(Path explicitUserPath) throws IOException {
        String name = explicitUserPath.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (!name.endsWith(".sfc") && !name.endsWith(".smc")) throw new IOException("请选择 .sfc 或 .smc 文件");
        return SfcRomImage.fromBytes(readBounded(explicitUserPath, MAX_ROM + 512)).copyPayload();
    }
    static byte[] cachedRom(Path game, String sha) throws IOException {
        Path path = cache(game).resolve(validHash(sha) + ".sfc");
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
        byte[] bytes = readBounded(path, MAX_ROM);
        if (!sha.equals(hash(bytes))) throw new IOException("SFC 缓存校验失败，请移走该损坏缓存再重试");
        return bytes;
    }
    static void cacheRom(Path game, String sha, byte[] bytes) throws IOException {
        if (bytes.length > MAX_ROM || !validHash(sha).equals(hash(bytes))) throw new IOException("SFC ROM 摘要不符");
        Path root=cache(game); safeDirectories(root); atomic(root.resolve(sha+".sfc"), bytes);
    }
    static Path snapshot(Path game, String sha, java.util.UUID backupSession, byte[] state, byte[] sram, int frame) throws IOException {
        var saved=SfcRecoveryBackups.save(game,sha,backupSession,state,sram,frame);
        if(!saved.cleanupWarning().isEmpty())System.getLogger("PIQ SFC Home").log(System.Logger.Level.WARNING,saved.cleanupWarning());
        return saved.path();
    }
    private static Path cache(Path game) throws IOException { try{return cn.piq.retro.storage.ConsoleStorage.root(game).resolve("piq-sfc-home").resolve("cache").resolve("roms");}catch(java.io.UncheckedIOException e){throw e.getCause();} }
    private static String validHash(String sha) throws IOException {
        if (sha == null || !sha.matches("[0-9a-f]{64}")) throw new IOException("非法 SFC 摘要");
        return sha;
    }
    static byte[] readBounded(Path path, int max) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("不是普通文件");
        try (FileChannel input=FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            long length=input.size();
            if (length<1 || length>max) throw new IOException("文件大小不符合限制");
            byte[] result=new byte[(int)length]; ByteBuffer target=ByteBuffer.wrap(result);
            while (target.hasRemaining()) if (input.read(target)<0) throw new IOException("文件读取中发生变化");
            if (input.size()!=length) throw new IOException("文件读取中发生变化");
            return result;
        }
    }
    private static void safeDirectories(Path path) throws IOException {
        Path absolute=path.toAbsolutePath().normalize(), cursor=absolute.getRoot();
        for (Path part:absolute) {
            cursor=cursor.resolve(part);
            if (Files.exists(cursor,LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(cursor) || !Files.isDirectory(cursor,LinkOption.NOFOLLOW_LINKS)) throw new IOException("缓存目录不安全");
            } else Files.createDirectory(cursor);
        }
    }
    private static void atomic(Path destination, byte[] bytes) throws IOException {
        if (Files.exists(destination,LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(destination,LinkOption.NOFOLLOW_LINKS)) throw new IOException("缓存目标不安全");
        Path temp=Files.createTempFile(destination.getParent(), "sfc-", ".part");
        try {
            try (FileChannel channel=FileChannel.open(temp,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer=ByteBuffer.wrap(bytes); while(buffer.hasRemaining()) channel.write(buffer); channel.force(true);
            }
            try { Files.move(temp,destination,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp,destination,StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
}
