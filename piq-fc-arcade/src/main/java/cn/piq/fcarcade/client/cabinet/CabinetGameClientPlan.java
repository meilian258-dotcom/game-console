package cn.piq.fcarcade.client.cabinet;

import cn.piq.fcarcade.cabinet.CabinetGameManifest;
import cn.piq.fcarcade.cabinet.CabinetGameStore;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Pure, worker-only transfer selection and verified local cache preparation. Not a game grant. */
public record CabinetGameClientPlan(CabinetGameManifest manifest, int missingMask) {
    public CabinetGameClientPlan {
        Objects.requireNonNull(manifest);
        if (missingMask < 0 || (missingMask & ~((1 << manifest.files().size()) - 1)) != 0)
            throw new IllegalArgumentException("Invalid missing game files");
    }

    @FunctionalInterface public interface FileAction {
        void accept(int index, CabinetGameManifest.Entry entry) throws Exception;
    }
    @FunctionalInterface public interface Check { void check() throws Exception; }
    @FunctionalInterface public interface Quota { void check(long additional) throws IOException; }

    /** A server mask is meaningful only on the successful reply to this exact upload OPEN. */
    public static boolean validReply(boolean success, boolean uploadOpen, CabinetGameManifest offered,
                                     CabinetGameManifest returned, int mask) {
        if (!success || !uploadOpen) return mask == 0;
        return offered != null && offered.equals(returned) && mask >= 0
                && (mask & ~((1 << offered.files().size()) - 1)) == 0;
    }

    public long missingBytes() {
        long bytes = 0;
        for (int i = 0; i < manifest.files().size(); i++) if (missing(i)) bytes += manifest.files().get(i).size();
        return bytes;
    }

    public boolean missing(int index) {
        if (index < 0 || index >= manifest.files().size()) throw new IllegalArgumentException("Invalid game file index");
        return (missingMask & (1 << index)) != 0;
    }

    /** Both PUT and GET use this exact ordered selector; cache hits never invoke the transport. */
    public void transferMissing(FileAction action) throws Exception {
        for (int i = 0; i < manifest.files().size(); i++) if (missing(i)) action.accept(i, manifest.files().get(i));
    }

    public static Path cacheDirectory(Path root, String context, CabinetGameManifest manifest) {
        if (context == null || context.isBlank()) throw new IllegalArgumentException("Missing game context");
        return root.toAbsolutePath().normalize()
                .resolve(CabinetGameManifest.digest(context.getBytes(StandardCharsets.UTF_8)))
                .resolve(manifest.contentId());
    }

    /** Existing files must be complete, same-size, same-SHA ordinary files, including every BIOS. */
    public static CabinetGameClientPlan inspectCache(Path directory, CabinetGameManifest manifest, Check check) throws Exception {
        CabinetGameStore.directory(directory);
        int mask = 0;
        for (int i = 0; i < manifest.files().size(); i++) {
            check.check();
            var entry = manifest.files().get(i);
            Path target = directory.resolve(entry.name());
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) CabinetGameStore.verify(target, entry);
            else mask |= 1 << i;
        }
        check.check();
        return new CabinetGameClientPlan(manifest, mask);
    }

    public static void verifySelected(List<Path> sources, CabinetGameManifest manifest, Check check) throws Exception {
        if (sources.size() != manifest.files().size()) throw new IOException("本地游戏文件列表与共享清单不一致");
        for (int i = 0; i < sources.size(); i++) {
            check.check();
            var entry = manifest.files().get(i);
            if (!sources.get(i).getFileName().toString().equals(entry.name())) throw new IOException("本地游戏文件名与共享清单不一致");
            CabinetGameStore.verify(sources.get(i), entry);
        }
        check.check();
    }

    /** Prepare selected data before OPEN. Failure/cancellation cannot commit a server binding.
     * Only missing cache files are copied; never overwrite a source, existing cache file or old content ID.
     */
    public static void cacheSelected(List<Path> sources, Path directory, CabinetGameManifest manifest,
                                     UUID transaction, Check check, Quota quota) throws Exception {
        verifySelected(sources, manifest, check);
        var plan = inspectCache(directory, manifest, check);
        if (plan.missingBytes() > 0) quota.check(plan.missingBytes());
        plan.transferMissing((index, entry) -> {
            check.check();
            Path source = sources.get(index), target = directory.resolve(entry.name());
            Path temporary = directory.resolve("selected-" + transaction + "-" + index + ".part");
            boolean created = false;
            try {
                CabinetGameStore.directory(directory);
                try (var in = Files.newByteChannel(source, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
                     var out = Files.newByteChannel(temporary, Set.of(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW, LinkOption.NOFOLLOW_LINKS))) {
                    created = true;
                    ByteBuffer buffer = ByteBuffer.allocate(32768);
                    int copied = 0;
                    while (true) {
                        check.check();
                        int count = in.read(buffer);
                        if (count < 0) break;
                        if (count == 0) continue;
                        copied += count;
                        if (copied > entry.size()) throw new IOException("游戏文件在缓存时变大");
                        buffer.flip();
                        while (buffer.hasRemaining()) { check.check(); out.write(buffer); }
                        buffer.clear();
                    }
                    if (copied != entry.size()) throw new IOException("游戏文件在缓存时被截断");
                }
                check.check();
                CabinetGameStore.verify(source, entry);
                CabinetGameStore.verify(temporary, entry);
                check.check();
                CabinetGameStore.directory(directory);
                Files.move(temporary, target);
                CabinetGameStore.verify(target, entry);
            } finally {
                if (created && Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) {
                    CabinetGameStore.regular(temporary);
                    Files.delete(temporary);
                }
            }
        });
        verifySelected(sources, manifest, check);
        if (inspectCache(directory, manifest, check).missingMask() != 0) throw new IOException("本地共享游戏缓存不完整");
    }
}
