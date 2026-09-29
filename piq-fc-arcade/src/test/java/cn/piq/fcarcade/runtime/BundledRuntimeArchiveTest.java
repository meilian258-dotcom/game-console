package cn.piq.fcarcade.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static cn.piq.fcarcade.runtime.RuntimeCatalog.RuntimeId.MAME;
import static cn.piq.fcarcade.runtime.RuntimeInstaller.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real tiny ZIPs and inert data only. No global provider registration, native loads, or game files. */
class BundledRuntimeArchiveTest {
    @TempDir Path root;
    private static final String PREFIX = "native-runtime/win-x64-v1/";
    private static final String FIRST = "piq-native-arcade/runtime/mame_libretro.dll";
    private static final String SECOND = "piq-native-arcade/runtime/piq-native-helper.jar";
    private static final byte[] FIRST_BYTES = "only inert archive fixture one".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] SECOND_BYTES = "only inert archive fixture two".getBytes(StandardCharsets.US_ASCII);

    private static RuntimeCatalog.Artifact artifact(String path, byte[] bytes) throws Exception {
        return new RuntimeCatalog.Artifact(path, bytes.length,
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
    }
    private RuntimeCatalog.Artifact first() throws Exception { return artifact(FIRST, FIRST_BYTES); }
    private Path archive(Map<String, byte[]> entries) throws Exception {
        Path path = root.resolve("inert-arcade-fixture.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey())); zip.write(entry.getValue()); zip.closeEntry();
            }
        }
        return path;
    }
    private Map<String, byte[]> validEntries() {
        var entries = new LinkedHashMap<String, byte[]>();
        entries.put(PREFIX + FIRST, FIRST_BYTES); entries.put(PREFIX + SECOND, SECOND_BYTES); return entries;
    }
    private RuntimeInstaller installer(Path archive) throws Exception {
        Path gameRoot = root.resolve("isolated-game-fixture"); Files.createDirectories(gameRoot);
        var component = new RuntimeCatalog.Component(MAME, "inert fixture", List.of(first(), artifact(SECOND, SECOND_BYTES)));
        return new RuntimeInstaller(gameRoot, List.of(component), true, Map.of(MAME, bundledArchive(archive)));
    }
    private void noTargetsOrStages() throws Exception {
        Path gameRoot = root.resolve("isolated-game-fixture");
        assertFalse(Files.exists(gameRoot.resolve(FIRST))); assertFalse(Files.exists(gameRoot.resolve(SECOND)));
        Path staging = gameRoot.resolve("piq-runtime-packs");
        if (Files.isDirectory(staging)) try (var entries = Files.list(staging)) {
            assertFalse(entries.anyMatch(p -> p.getFileName().toString().startsWith(".install-")));
        }
    }
    private void moveProvesZipClosed(Path archive) throws Exception {
        Path moved = root.resolve("closed-archive-fixture.jar");
        Files.move(archive, moved); assertTrue(Files.isRegularFile(moved)); assertFalse(Files.exists(archive));
    }

    @Test void directZipStreamsAreFreshReadableAndCloseTheirArchive() throws Exception {
        Path archive = archive(validEntries()); var source = bundledArchive(archive);
        try (InputStream first = source.open(first())) { assertArrayEquals(FIRST_BYTES, first.readAllBytes()); }
        InputStream reopened = source.open(first());
        assertArrayEquals(FIRST_BYTES, reopened.readAllBytes()); reopened.close(); reopened.close();
        moveProvesZipClosed(archive);
    }

    @Test void validEmbeddedZipInstallsAndHashesEveryInertFileWithoutOfflinePack() throws Exception {
        Path archive = archive(validEntries());
        var result = installer(archive).install(Set.of(MAME), () -> false, p -> {});
        assertEquals(Outcome.INSTALLED, result.outcome(), result.details().toString()); assertEquals(2, result.installed());
        Path gameRoot = root.resolve("isolated-game-fixture");
        assertArrayEquals(FIRST_BYTES, Files.readAllBytes(gameRoot.resolve(FIRST)));
        assertArrayEquals(SECOND_BYTES, Files.readAllBytes(gameRoot.resolve(SECOND)));
        assertFalse(Files.exists(gameRoot.resolve("piq-runtime-packs/piq-runtime-pack-v1.zip")));
        moveProvesZipClosed(archive);
    }

    @Test void missingResourceRejectsAndClosesZip() throws Exception {
        Path archive = archive(Map.of(PREFIX + SECOND, SECOND_BYTES)); var source = bundledArchive(archive);
        assertThrows(IOException.class, () -> source.open(first())); moveProvesZipClosed(archive);
    }

    @Test void declaredSizeMismatchRejectsAndClosesZip() throws Exception {
        Path archive = archive(Map.of(PREFIX + FIRST, new byte[]{1})); var source = bundledArchive(archive);
        assertThrows(IOException.class, () -> source.open(first())); moveProvesZipClosed(archive);
    }

    @Test void directoryEntryCannotBeUsedAsAResource() throws Exception {
        Path archive = archive(Map.of(PREFIX + FIRST + "/", new byte[0])); var source = bundledArchive(archive);
        assertThrows(IOException.class, () -> source.open(first())); moveProvesZipClosed(archive);
    }

    @Test void corruptZipCannotSupplyAResourceAndIsNotKeptOpen() throws Exception {
        Path archive = root.resolve("inert-arcade-fixture.jar"); Files.writeString(archive, "not a ZIP");
        var source = bundledArchive(archive); assertThrows(IOException.class, () -> source.open(first()));
        moveProvesZipClosed(archive);
    }

    @Test void directoryArchiveSourceIsRefusedWithoutModification() throws Exception {
        Path directory = Files.createDirectory(root.resolve("directory-not-jar")); var source = bundledArchive(directory);
        assertThrows(IOException.class, () -> source.open(first()));
        try (var entries = Files.list(directory)) { assertEquals(0, entries.count()); }
    }

    @Test void nonDefaultZipFilesystemSourceIsRejectedBeforeOpening() throws Exception {
        Path archive = archive(validEntries());
        try (var zipfs = FileSystems.newFileSystem(archive, Map.of())) {
            assertNotSame(FileSystems.getDefault(), zipfs);
            assertThrows(IllegalArgumentException.class, () -> bundledArchive(zipfs.getPath("/inert-inner.jar")));
        }
        moveProvesZipClosed(archive);
    }

    @Test void sameLengthWrongShaInLastResourcePreventsWholeBatchPublication() throws Exception {
        var entries = validEntries(); byte[] altered = SECOND_BYTES.clone(); altered[0] ^= 1; entries.put(PREFIX + SECOND, altered);
        Path archive = archive(entries); var result = installer(archive).install(Set.of(MAME), () -> false, p -> {});
        assertEquals(Outcome.FAILED, result.outcome()); assertEquals(0, result.installed());
        assertTrue(result.details().stream().anyMatch(s -> s.contains("SHA-256")));
        noTargetsOrStages(); moveProvesZipClosed(archive);
    }

    @Test void duplicateResourceEntriesRejectEvenWhenZipFileCanReadThem() throws Exception {
        String wanted = PREFIX + FIRST;
        String alias = wanted.replace("mame_libretro.dll", "same_libretro.dll");
        var entries = new LinkedHashMap<String, byte[]>(); entries.put(wanted, FIRST_BYTES); entries.put(alias, FIRST_BYTES);
        Path archive = archive(entries); byte[] raw = Files.readAllBytes(archive);
        byte[] oldName = alias.getBytes(StandardCharsets.US_ASCII), newName = wanted.getBytes(StandardCharsets.US_ASCII);
        assertEquals(oldName.length, newName.length); int replacements = 0;
        // Same-length rename in the two local/central filename fields leaves CRCs and
        // lengths intact, creating a tiny real ZIP duplicate without a custom ZIP writer.
        for (int i = 0; i <= raw.length - oldName.length; i++) {
            boolean match = true;
            for (int j = 0; j < oldName.length; j++) if (raw[i + j] != oldName[j]) { match = false; break; }
            if (match) { System.arraycopy(newName, 0, raw, i, newName.length); replacements++; i += newName.length - 1; }
        }
        assertEquals(2, replacements); Files.write(archive, raw);
        try (var zip = new java.util.zip.ZipFile(archive.toFile())) {
            assertEquals(2, zip.stream().filter(e -> e.getName().equals(wanted)).count());
        }
        var source = bundledArchive(archive); assertThrows(IOException.class, () -> source.open(first()));
        moveProvesZipClosed(archive);
    }

    @Test void archiveMetadataChangeWhileStreamOpenIsDetectedOnClose() throws Exception {
        Path archive = archive(validEntries()); InputStream input = bundledArchive(archive).open(first());
        assertEquals(FIRST_BYTES[0], input.read());
        Files.setLastModifiedTime(archive, FileTime.fromMillis(1234567000L));
        assertThrows(IOException.class, input::close); input.close(); moveProvesZipClosed(archive);
    }

    @Test void cancellingStagingClosesZipAndLeavesNoPublishedFiles() throws Exception {
        Path archive = archive(validEntries()); AtomicBoolean cancelled = new AtomicBoolean();
        var result = installer(archive).install(Set.of(MAME), cancelled::get,
                p -> { if (p.phase() == Phase.STAGING) cancelled.set(true); });
        assertEquals(Outcome.CANCELLED, result.outcome()); noTargetsOrStages(); moveProvesZipClosed(archive);
    }
}
