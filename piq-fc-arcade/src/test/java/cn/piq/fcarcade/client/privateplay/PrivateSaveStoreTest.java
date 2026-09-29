package cn.piq.fcarcade.client.privateplay;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class PrivateSaveStoreTest {
    @TempDir Path temporary;
    private static final PrivateSaveStore.Key KEY = new PrivateSaveStore.Key("fc", "nes-legacy-v1/" + "a".repeat(64), "b".repeat(64));
    private PrivateSaveStore store() { return new PrivateSaveStore(temporary); }
    private Path latest(PrivateSaveStore store) { return store.directory(KEY).resolve("latest.zip"); }
    private static String sha(Path path) throws IOException { return PrivateSaveStore.sha256(Files.readAllBytes(path)); }
    private static long children(Path directory) throws IOException { try (var files = Files.list(directory)) { return files.count(); } }

    @Test void missingDoesNotCreateDirectoriesOrTouchPublicSaves() throws Exception {
        Path publicSave = temporary.resolve("public-save.bin"); Files.write(publicSave, new byte[]{7, 8});
        String original = sha(publicSave);
        assertTrue(store().load(KEY).isEmpty());
        assertFalse(Files.exists(temporary.resolve("private-saves-v1")));
        assertEquals(original, sha(publicSave));
    }
    @Test void storesAndRestoresStateAndSramTogether() throws Exception {
        var store = store(); store.save(KEY, new byte[]{1, 2, 3}, new byte[]{4, 5});
        var loaded = store.load(KEY).orElseThrow();
        assertArrayEquals(new byte[]{1, 2, 3}, loaded.state()); assertArrayEquals(new byte[]{4, 5}, loaded.sram());
        assertEquals(1, children(store.directory(KEY)));
        assertEquals(Set.of("metadata.bin", "state.bin", "sram.bin"), members(latest(store)).keySet());
    }
    @Test void keepsExactlyOneVerifiedPreviousGeneration() throws Exception {
        var store = store(); store.save(KEY, new byte[]{1}, new byte[0]);
        String one = sha(latest(store));
        store.save(KEY, new byte[]{2}, new byte[]{22});
        assertEquals(one, sha(store.directory(KEY).resolve("previous.zip")));
        String two = sha(latest(store));
        store.save(KEY, new byte[]{3}, new byte[]{33});
        assertEquals(two, sha(store.directory(KEY).resolve("previous.zip")));
        assertArrayEquals(new byte[]{3}, store.load(KEY).orElseThrow().state());
        assertEquals(2, children(store.directory(KEY)));
    }
    @Test void failedCurrentCommitKeepsOriginalAndVerifiedBackup() throws Exception {
        var store = store(); store.save(KEY, new byte[]{1}, new byte[]{9}); String before = sha(latest(store));
        var failing = new PrivateSaveStore(temporary, new PrivateSaveStore.Operations() {
            @Override public void replace(Path from, Path to) throws IOException {
                if (to.getFileName().toString().equals("latest.zip")) throw new IOException("injected latest failure");
                PrivateSaveStore.Operations.super.replace(from, to);
            }
        });
        assertThrows(IOException.class, () -> failing.save(KEY, new byte[]{2}, new byte[]{8}));
        assertEquals(before, sha(latest(store)));
        assertEquals(before, sha(store.directory(KEY).resolve("previous.zip")));
        assertArrayEquals(new byte[]{1}, store.load(KEY).orElseThrow().state());
        assertEquals(2, children(store.directory(KEY)));
    }
    @Test void failedBackupCommitPreservesBothOriginalFiles() throws Exception {
        var store = store(); store.save(KEY, new byte[]{1}, new byte[0]); store.save(KEY, new byte[]{2}, new byte[0]);
        Path previous = store.directory(KEY).resolve("previous.zip");
        String currentHash = sha(latest(store)), previousHash = sha(previous);
        var failing = new PrivateSaveStore(temporary, new PrivateSaveStore.Operations() {
            @Override public void replace(Path from, Path to) throws IOException { throw new IOException("injected backup failure"); }
        });
        assertThrows(IOException.class, () -> failing.save(KEY, new byte[]{3}, new byte[0]));
        assertEquals(currentHash, sha(latest(store))); assertEquals(previousHash, sha(previous));
        assertEquals(2, children(store.directory(KEY)));
    }
    @Test void atomicUnsupportedHasNoFallbackOrFalseSuccess() throws Exception {
        var failing = new PrivateSaveStore(temporary, new PrivateSaveStore.Operations() {
            @Override public void replace(Path from, Path to) throws IOException {
                throw new AtomicMoveNotSupportedException(from.toString(), to.toString(), "injected");
            }
        });
        assertThrows(AtomicMoveNotSupportedException.class, () -> failing.save(KEY, new byte[]{1}, new byte[0]));
        assertFalse(Files.exists(latest(failing))); assertEquals(0, children(failing.directory(KEY)));
    }
    @Test void corruptLatestIsPreservedAndNeverOverwritten() throws Exception {
        var store = store(); store.save(KEY, new byte[]{1}, new byte[0]);
        Files.write(latest(store), new byte[]{'b', 'a', 'd'}); String damaged = sha(latest(store));
        assertThrows(IOException.class, () -> store.load(KEY));
        assertThrows(IOException.class, () -> store.save(KEY, new byte[]{2}, new byte[0]));
        assertEquals(damaged, sha(latest(store))); assertEquals(1, children(store.directory(KEY)));
    }
    @Test void corruptPreviousIsNotDeletedToPermitNewSave() throws Exception {
        var store = store(); store.save(KEY, new byte[]{1}, new byte[0]); store.save(KEY, new byte[]{2}, new byte[0]);
        Path previous = store.directory(KEY).resolve("previous.zip"); Files.write(previous, new byte[]{3});
        String before = sha(latest(store)), damaged = sha(previous);
        assertThrows(IOException.class, () -> store.save(KEY, new byte[]{4}, new byte[0]));
        assertEquals(before, sha(latest(store))); assertEquals(damaged, sha(previous));
    }
    @Test void validLatestAndCorruptPreviousBlockLoadBeforePlayingAndPreserveBoth() throws Exception {
        var store = store(); store.save(KEY, new byte[]{1}, new byte[0]); store.save(KEY, new byte[]{2}, new byte[]{8});
        Path previous = store.directory(KEY).resolve("previous.zip"); Files.write(previous, new byte[]{3});
        byte[] currentBytes = Files.readAllBytes(latest(store)), previousBytes = Files.readAllBytes(previous);
        IOException failure = assertThrows(IOException.class, () -> store.load(KEY));
        assertTrue(failure.getMessage().contains("旧备份"));
        assertArrayEquals(currentBytes, Files.readAllBytes(latest(store)));
        assertArrayEquals(previousBytes, Files.readAllBytes(previous));
        assertEquals(2, children(store.directory(KEY)));
    }
    @Test void validZipFromAnotherIdentityCannotServeAsPreviousOnLoad() throws Exception {
        var store = store(); store.save(KEY, new byte[]{1}, new byte[0]); store.save(KEY, new byte[]{2}, new byte[0]);
        var other = new PrivateSaveStore.Key("fc", KEY.coreNamespace(), "c".repeat(64));
        store.save(other, new byte[]{3}, new byte[0]);
        Path previous = store.directory(KEY).resolve("previous.zip");
        Files.copy(store.directory(other).resolve("latest.zip"), previous, StandardCopyOption.REPLACE_EXISTING);
        byte[] currentBytes = Files.readAllBytes(latest(store)), previousBytes = Files.readAllBytes(previous);
        assertThrows(IOException.class, () -> store.load(KEY));
        assertArrayEquals(currentBytes, Files.readAllBytes(latest(store)));
        assertArrayEquals(previousBytes, Files.readAllBytes(previous));
        assertEquals(2, children(store.directory(KEY)));
    }
    @Test void missingLatestWithBackupIsNotTreatedAsNewGame() throws Exception {
        var store = store(); store.save(KEY, new byte[]{1}, new byte[0]); store.save(KEY, new byte[]{2}, new byte[0]);
        Files.delete(latest(store));
        String before = sha(store.directory(KEY).resolve("previous.zip"));
        assertThrows(IOException.class, () -> store.load(KEY));
        assertThrows(IOException.class, () -> store.save(KEY, new byte[]{3}, new byte[0]));
        assertEquals(before, sha(store.directory(KEY).resolve("previous.zip")));
    }
    @Test void fullRomHashSystemAndCoreEachSeparateKeys() throws Exception {
        var store = store(); store.save(KEY, new byte[]{1}, new byte[0]);
        var differentRom = new PrivateSaveStore.Key("fc", KEY.coreNamespace(), "b".repeat(63) + "c");
        var differentCore = new PrivateSaveStore.Key("fc", "nes-other-v1/" + "a".repeat(64), KEY.romSha256());
        var differentSystem = new PrivateSaveStore.Key("sfc", KEY.coreNamespace(), KEY.romSha256());
        for (var key : List.of(differentRom, differentCore, differentSystem)) {
            assertNotEquals(store.directory(KEY), store.directory(key)); assertTrue(store.load(key).isEmpty());
            store.save(key, new byte[]{2}, new byte[0]);
        }
        assertArrayEquals(new byte[]{1}, store.load(KEY).orElseThrow().state());
    }
    @Test void copiedArchiveUnderWrongIdentityIsRefused() throws Exception {
        var store = store(); store.save(KEY, new byte[]{1}, new byte[0]);
        var wrong = new PrivateSaveStore.Key("fc", KEY.coreNamespace(), "c".repeat(64));
        Files.createDirectories(store.directory(wrong));
        Path wrongArchive = store.directory(wrong).resolve("latest.zip");
        Files.copy(latest(store), wrongArchive); String before = sha(wrongArchive);
        assertThrows(IOException.class, () -> store.load(wrong));
        assertThrows(IOException.class, () -> store.save(wrong, new byte[]{2}, new byte[0]));
        assertEquals(before, sha(wrongArchive));
    }
    @Test void validCrcWithWrongPayloadShaIsRefused() throws Exception {
        var store = store(); store.save(KEY, new byte[]{1}, new byte[]{2});
        var members = members(latest(store)); members.put("state.bin", new byte[]{9});
        writeZip(latest(store), members); String before = sha(latest(store));
        assertThrows(IOException.class, () -> store.load(KEY));
        assertThrows(IOException.class, () -> store.save(KEY, new byte[]{8}, new byte[0]));
        assertEquals(before, sha(latest(store)));
    }
    @Test void corruptedCentralLocalOffsetIsRejectedAndPreserved() throws Exception {
        var store = store(); store.save(KEY, new byte[]{1}, new byte[0]);
        byte[] bytes = Files.readAllBytes(latest(store)); ByteBuffer data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int offset = -1; for (int i = 0; i < bytes.length - 46; i++) if (data.getInt(i) == 0x02014b50) { offset = i; break; }
        assertTrue(offset >= 0); data.putInt(offset + 42, 1); Files.write(latest(store), bytes); String before = sha(latest(store));
        assertThrows(IOException.class, () -> store.load(KEY));
        assertThrows(IOException.class, () -> store.save(KEY, new byte[]{2}, new byte[0]));
        assertEquals(before, sha(latest(store)));
    }
    @Test void truncatedCentralDirectoryFailsDespiteIntactLocalEntries() throws Exception {
        var store = store(); store.save(KEY, new byte[]{1}, new byte[0]); byte[] bytes = Files.readAllBytes(latest(store));
        Files.write(latest(store), Arrays.copyOf(bytes, bytes.length - 12));
        assertThrows(IOException.class, () -> store.load(KEY));
    }
    @Test void directoryAtArchiveTargetCannotBeReplaced() throws Exception {
        var store = store(); Files.createDirectories(latest(store));
        Path sentinel = latest(store).resolve("keep"); Files.write(sentinel, new byte[]{7});
        assertThrows(IOException.class, () -> store.save(KEY, new byte[]{1}, new byte[0]));
        assertArrayEquals(new byte[]{7}, Files.readAllBytes(sentinel));
    }
    @Test void identityAndPayloadBoundsRejectBeforeWrite() {
        assertThrows(IllegalArgumentException.class, () -> new PrivateSaveStore.Key("../public", "core", KEY.romSha256()));
        assertThrows(IllegalArgumentException.class, () -> new PrivateSaveStore.Key("fc", "core\n", KEY.romSha256()));
        assertThrows(IllegalArgumentException.class, () -> new PrivateSaveStore.Key("fc", "core", "b".repeat(12)));
        assertThrows(IOException.class, () -> store().save(KEY, new byte[0], new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new PrivateSaveStore.Snapshot(new byte[]{1}, null));
        assertEquals(128 * 1024 * 1024, PrivateSaveStore.MAX_STATE_BYTES);
        assertEquals(8 * 1024 * 1024, PrivateSaveStore.MAX_SRAM_BYTES);
    }
    @Test void snapshotArraysCannotBeMutatedByCaller() {
        byte[] state = {1}, sram = {2}; var snapshot = new PrivateSaveStore.Snapshot(state, sram);
        state[0] = 3; sram[0] = 4; snapshot.state()[0] = 5; snapshot.sram()[0] = 6;
        assertArrayEquals(new byte[]{1}, snapshot.state()); assertArrayEquals(new byte[]{2}, snapshot.sram());
    }
    @Test void excessiveUnknownLeftoversArePreservedAndBoundNewWrites() throws Exception {
        var store = store(); Files.createDirectories(store.directory(KEY));
        for (int i = 0; i < 17; i++) Files.write(store.directory(KEY).resolve("unknown-" + i), new byte[]{1});
        assertThrows(IOException.class, () -> store.save(KEY, new byte[]{1}, new byte[0]));
        assertEquals(17, children(store.directory(KEY))); assertFalse(Files.exists(latest(store)));
    }
    private static Map<String, byte[]> members(Path archive) throws IOException {
        Map<String, byte[]> result = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry; while ((entry = zip.getNextEntry()) != null) result.put(entry.getName(), zip.readAllBytes());
        }
        return result;
    }
    private static void writeZip(Path archive, Map<String, byte[]> members) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            for (var member : members.entrySet()) {
                CRC32 crc = new CRC32(); crc.update(member.getValue()); ZipEntry entry = new ZipEntry(member.getKey());
                entry.setMethod(ZipEntry.STORED); entry.setSize(member.getValue().length); entry.setCompressedSize(member.getValue().length); entry.setCrc(crc.getValue());
                zip.putNextEntry(entry); zip.write(member.getValue()); zip.closeEntry();
            }
        }
    }
}
