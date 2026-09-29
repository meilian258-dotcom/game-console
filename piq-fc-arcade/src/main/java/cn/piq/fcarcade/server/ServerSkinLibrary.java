package cn.piq.fcarcade.server;

import cn.piq.fcarcade.FcArcadeMod;
import cn.piq.fcarcade.skin.SkinDescriptor;
import cn.piq.fcarcade.skin.SkinImageCodec;
import cn.piq.fcarcade.skin.SkinTransferLimits;
import cn.piq.fcarcade.skin.SkinLayout;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;

final class ServerSkinLibrary {
    private static final String METADATA_FILE = "skin-metadata.properties";

    private final Path root;
    private final Path metadataPath;
    private final Properties metadata = new Properties();

    ServerSkinLibrary(Path root) {
        this.root = root.toAbsolutePath().normalize();
        metadataPath = this.root.resolve(METADATA_FILE);
        try {
            Files.createDirectories(this.root);
            if (Files.isRegularFile(metadataPath)) {
                try (Reader reader = Files.newBufferedReader(
                        metadataPath,
                        StandardCharsets.UTF_8)) {
                    metadata.load(reader);
                }
            }
        } catch (IOException error) {
            FcArcadeMod.LOGGER.error("[PIQ FC] 读取服务器皮肤库失败", error);
        }
    }

    List<SkinDescriptor> catalog() {
        try (var paths = Files.list(root)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString()
                            .matches("[0-9a-f]{64}\\.png"))
                    .sorted(Comparator.comparing(path ->
                            displayName(hashFromPath(path)),
                            String.CASE_INSENSITIVE_ORDER))
                    .limit(SkinTransferLimits.MAX_CATALOG_ENTRIES)
                    .map(path -> descriptor(path, hashFromPath(path)))
                    .filter(java.util.Objects::nonNull)
                    .toList();
        } catch (IOException error) {
            throw new IllegalStateException("读取服务器皮肤目录失败", error);
        }
    }

    SkinData find(String sha256) {
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) return null;
        Path path = pathFor(sha256);
        if (!Files.isRegularFile(path)) return null;
        try {
            byte[] bytes = SkinImageCodec.readBounded(path);
            SkinLayout layout = SkinImageCodec.validateStored(bytes, sha256);
            return new SkinData(
                    new SkinDescriptor(
                            displayName(sha256),
                            sha256,
                            bytes.length,
                            layout),
                    bytes);
        } catch (IOException error) {
            FcArcadeMod.LOGGER.warn(
                    "[PIQ FC] 忽略损坏的服务器皮肤 {}",
                    path,
                    error);
            return null;
        }
    }

    SkinDescriptor store(String name, String sha256, byte[] png) {
        try {
            SkinImageCodec.validatePrepared(png, sha256);
            Path destination = pathFor(sha256);
            Path temporary = Files.createTempFile(root, ".piq-skin-", ".tmp");
            try {
                Files.write(temporary, png);
                try {
                    Files.move(
                            temporary,
                            destination,
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                    Files.move(
                            temporary,
                            destination,
                            StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
            String normalizedName = SkinDescriptor.normalizeName(name);
            metadata.setProperty(sha256 + ".name", normalizedName);
            saveMetadata();
            return new SkinDescriptor(normalizedName, sha256, png.length, SkinLayout.ROCKET_V1_2048);
        } catch (IOException error) {
            throw new IllegalStateException("保存服务器皮肤失败", error);
        }
    }

    private SkinDescriptor descriptor(Path path, String sha256) {
        try {
            long size = Files.size(path);
            if (size <= 0 || size > SkinTransferLimits.MAX_PNG_BYTES) {
                return null;
            }
            return new SkinDescriptor(
                    displayName(sha256),
                    sha256,
                    (int) size,
                    SkinImageCodec.inspect(path));
        } catch (IOException | IllegalArgumentException error) {
            return null;
        }
    }

    private String displayName(String sha256) {
        String configured = metadata.getProperty(sha256 + ".name", "").strip();
        return configured.isBlank() ? sha256.substring(0, 12) : configured;
    }

    private void saveMetadata() {
        try {
            Path temporary = Files.createTempFile(root, ".piq-skins-", ".tmp");
            try {
                try (Writer writer = Files.newBufferedWriter(
                        temporary,
                        StandardCharsets.UTF_8)) {
                    metadata.store(writer, "PIQ FC skin metadata");
                }
                try {
                    Files.move(
                            temporary,
                            metadataPath,
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                    Files.move(
                            temporary,
                            metadataPath,
                            StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException error) {
            throw new IllegalStateException("保存皮肤元数据失败", error);
        }
    }

    private Path pathFor(String sha256) {
        return root.resolve(sha256 + ".png");
    }

    private static String hashFromPath(Path path) {
        String fileName = path.getFileName().toString();
        return fileName.substring(0, 64);
    }

    record SkinData(SkinDescriptor descriptor, byte[] bytes) {
        SkinData {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }

        byte[] copyBytes(int offset, int end) {
            return java.util.Arrays.copyOfRange(bytes, offset, end);
        }
    }
}
