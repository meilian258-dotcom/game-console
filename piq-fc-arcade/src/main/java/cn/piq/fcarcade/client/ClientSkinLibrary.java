package cn.piq.fcarcade.client;

import cn.piq.fcarcade.skin.SkinImageCodec;
import cn.piq.fcarcade.skin.SkinLayout;
import cn.piq.fcarcade.storage.FcStoragePaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

final class ClientSkinLibrary {
    private ClientSkinLibrary() {
    }

    static Path root() {
        return ClientFcDirectories.path(FcStoragePaths.Area.SKINS);
    }

    static Path cacheRoot() {
        return ClientFcDirectories.path(FcStoragePaths.Area.SKIN_CACHE);
    }

    static List<LocalSkin> list() throws IOException {
        Path root = ClientFcDirectories.prepare(FcStoragePaths.Area.SKINS);
        ensureTemplate();
        try (var paths = Files.list(root)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString()
                            .toLowerCase(Locale.ROOT)
                            .endsWith(".png"))
                    .sorted(Comparator.comparing(
                            path -> path.getFileName().toString(),
                            String.CASE_INSENSITIVE_ORDER))
                    .limit(128)
                    .map(ClientSkinLibrary::describe)
                    .toList();
        }
    }

    static SkinImageCodec.Prepared prepare(LocalSkin skin) throws IOException {
        return SkinImageCodec.prepare(SkinImageCodec.readBounded(skin.path()));
    }

    static Path cachedPath(String sha256) {
        return cacheRoot().resolve(sha256 + ".png");
    }

    static void storeCache(String sha256, byte[] png) throws IOException {
        SkinImageCodec.validateStored(png, sha256);
        Path root = ClientFcDirectories.prepare(FcStoragePaths.Area.SKIN_CACHE);
        Path temporary = Files.createTempFile(root, ".piq-skin-", ".tmp");
        try {
            Files.write(temporary, png);
            try {
                Files.move(
                        temporary,
                        cachedPath(sha256),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(
                        temporary,
                        cachedPath(sha256),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static void ensureTemplate() throws IOException {
        Path template = ClientFcDirectories.prepare(FcStoragePaths.Area.SKINS)
                .resolve("通用街机皮肤模板-v1-2048.png");
        if (Files.isRegularFile(template)) return;
        Files.createDirectories(root());
        try (var input = ClientSkinLibrary.class.getResourceAsStream(
                "/assets/piq_fc_arcade/textures/block/"
                        + "rocket_arcade_skin.png")) {
            if (input == null) throw new IOException("模组内缺少通用街机 2048 皮肤模板");
            Files.copy(input, template);
        }
    }

    private static String displayName(Path path) {
        String name = path.getFileName().toString();
        return name.substring(0, name.length() - 4);
    }

    private static LocalSkin describe(Path path) {
        SkinLayout layout;
        try { layout = SkinImageCodec.inspect(path); }
        catch (IOException ignored) { layout = null; }
        return new LocalSkin(path, displayName(path), layout);
    }

    record LocalSkin(Path path, String name, SkinLayout layout) {
        boolean compatible() { return layout != null && layout.compatible(); }
    }
}
