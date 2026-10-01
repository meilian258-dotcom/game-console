package cn.piq.fcarcade.home;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Content-addressed PNG storage. No caller-controlled path or URL is accepted. */
public final class CartridgeCoverRepository {
    // FC and content-card writers can own separate repository instances for the same shared directory.
    private static final Object WRITES = new Object();
    private final Path root;
    public CartridgeCoverRepository(Path root) { this.root = root.toAbsolutePath().normalize(); }
    public Path root() { return root; }
    private Path path(String hash) throws IOException {
        if (!CartridgeLimits.validHash(hash)) throw new IOException("封面哈希无效");
        if (Files.isSymbolicLink(root)) throw new IOException("封面目录不能为符号链接");
        return root.resolve(hash + ".png");
    }
    public boolean exists(String hash) {
        try { return Files.isRegularFile(path(hash), LinkOption.NOFOLLOW_LINKS); }
        catch (IOException ignored) { return false; }
    }
    /** Metadata only; selected images are fully size/hash/PNG checked by read(). */
    public java.util.List<String> list() throws IOException {
        if (Files.isSymbolicLink(root)) throw new IOException("封面目录不能为符号链接");
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return java.util.List.of();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw new IOException("封面目录无效");
        var result = new java.util.ArrayList<String>();
        try (var files = Files.newDirectoryStream(root)) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                if (!name.matches("[0-9a-f]{64}\\.png") || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue;
                long size = Files.size(file);
                if (size <= 0 || size > CartridgeLimits.MAX_COVER_BYTES) continue;
                if (result.size() >= 256) throw new IOException("封面目录超过 256 项");
                result.add(name.substring(0, 64));
            }
        }
        result.sort(String::compareTo);
        return java.util.List.copyOf(result);
    }
    /** Content-card catalog: isolate invalid images while retaining the immutable shared hash store. */
    public cn.piq.fcarcade.home.content.ContentCardStore.Scan scan() throws IOException {
        cn.piq.fcarcade.cabinet.CabinetGameStore.directory(root);
        var entries=new java.util.ArrayList<cn.piq.fcarcade.home.content.ContentCardStore.Entry>();
        var failures=new java.util.ArrayList<cn.piq.fcarcade.home.content.ContentCardStore.Failure>();
        try(var files=Files.list(root)){
            var paths=files.limit(257).toList();
            if(paths.size()>256)throw new IOException("封面目录超过 256 项");
            for(var file:paths.stream().sorted().toList()){
                String name=file.getFileName().toString();
                try{
                    cn.piq.fcarcade.cabinet.CabinetGameStore.regular(file);
                    if(!name.endsWith(".png"))throw new IOException("未扫描：扩展名不支持（支持 .png）");
                    if(!name.matches("[0-9a-f]{64}\\.png"))throw new IOException("服务器封面需要通过工作台上传，文件名不是内容哈希");
                    String hash=name.substring(0,64);byte[] png=read(hash);
                    entries.add(new cn.piq.fcarcade.home.content.ContentCardStore.Entry(hash,hash.substring(0,12)+".png",png.length));
                }catch(IOException|RuntimeException error){failures.add(new cn.piq.fcarcade.home.content.ContentCardStore.Failure(name,error.getMessage()));}
            }
        }
        return new cn.piq.fcarcade.home.content.ContentCardStore.Scan(entries,failures);
    }
    public byte[] read(String hash) throws IOException {
        Path file = path(hash);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("服务器没有此封面");
        long size = Files.size(file);
        if (size <= 0 || size > CartridgeLimits.MAX_COVER_BYTES) throw new IOException("封面文件大小无效");
        byte[] bytes;
        try (var input = Files.newInputStream(file)) { bytes = input.readNBytes(CartridgeLimits.MAX_COVER_BYTES + 1); }
        CartridgeCoverCodec.validate(bytes, hash);
        return bytes;
    }
    public void store(String hash, byte[] png) throws IOException {
        CartridgeCoverCodec.validate(png, hash);
        synchronized (WRITES) { storeValidated(hash, png); }
    }
    private void storeValidated(String hash, byte[] png) throws IOException {
        Path destination = path(hash);
        Files.createDirectories(root);
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) { read(hash); return; }
        long count = 0, total = 0;
        try (var files = Files.list(root)) {
            var iterator = files.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)
                    && p.getFileName().toString().matches("[0-9a-f]{64}\\.png")).iterator();
            while (iterator.hasNext()) {
                Path next = iterator.next();
                if (++count >= 256) throw new IOException("封面库已满（最多 256 张）");
                total += Files.size(next);
                if (total > 128L * 1024 * 1024 - png.length) throw new IOException("封面库超过 128 MiB");
            }
        }
        Path temporary = Files.createTempFile(root, ".cover-", ".tmp");
        try {
            Files.write(temporary, png);
            try { Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE); }
            catch (java.nio.file.AtomicMoveNotSupportedException ignored) { Files.move(temporary, destination); }
        } finally { Files.deleteIfExists(temporary); }
    }
}
