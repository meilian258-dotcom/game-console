package cn.piq.sfchome.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SfcRecoveryBackupsTest {
    private static final String ROM = "a".repeat(64);
    private static final UUID SESSION = UUID.fromString("12345678-1234-5678-90ab-123456789abc");
    @TempDir Path game;

    @Test void completeGroupBecomesVisibleOnlyAtAtomicCommit() throws Exception {
        var operations = new SfcRecoveryBackups.Operations() {
            @Override public void commit(Path temporary, Path destination) throws IOException {
                assertFalse(Files.exists(destination));
                try (ZipFile zip = new ZipFile(temporary.toFile())) {
                    assertEquals(3, zip.size());
                    assertArrayEquals(new byte[]{1,2}, read(zip, "state.bin"));
                    assertArrayEquals(new byte[0], read(zip, "sram.bin"));
                    String metadata = new String(read(zip, "metadata.txt"), StandardCharsets.UTF_8);
                    assertTrue(metadata.contains("session=" + SESSION));
                    assertTrue(metadata.contains("frame=99"));
                    assertTrue(metadata.contains("not auto-loaded or a server save"));
                }
                SfcRecoveryBackups.Operations.super.commit(temporary, destination);
            }
        };
        var result = SfcRecoveryBackups.save(game, ROM, SESSION, new byte[]{1,2}, new byte[0], 99, operations);
        assertEquals("", result.cleanupWarning());
        assertTrue(Files.isRegularFile(result.path()));
        assertEquals(List.of(result.path()), children(root(SESSION)));
    }

    @Test void newSessionCannotOverwriteOldSessionOrLegacyLatestFiles() throws Exception {
        Path legacy = game.resolve("game-console/piq-sfc-home/local-backups/" + ROM);
        Files.createDirectories(legacy);
        for (String name : List.of("latest.state", "latest.sram", "latest.txt")) Files.writeString(legacy.resolve(name), "old-" + name);
        var first = save(SESSION, 1, 9000);
        byte[] original = Files.readAllBytes(first.path());
        UUID next = UUID.randomUUID();
        var second = save(next, 2, 5);
        assertNotEquals(first.path().getParent(), second.path().getParent());
        assertArrayEquals(original, Files.readAllBytes(first.path()));
        for (String name : List.of("latest.state", "latest.sram", "latest.txt")) assertEquals("old-" + name, Files.readString(legacy.resolve(name)));
    }

    @Test void sameSessionKeepsTwoLatestGenerationsEvenWhenFinalFrameRepeats() throws Exception {
        Path first = save(SESSION, 1, 1800).path();
        Path second = save(SESSION, 2, 1800).path();
        Path third = save(SESSION, 3, 1800).path();
        assertFalse(Files.exists(first));
        assertEquals(Set.of(second, third), new HashSet<>(children(root(SESSION))));
        try (ZipFile zip = new ZipFile(third.toFile())) {
            assertArrayEquals(new byte[]{3}, read(zip, "state.bin"));
            assertTrue(new String(read(zip, "metadata.txt"), StandardCharsets.UTF_8).contains("sequence=3"));
        }
    }

    @Test void commitFailureLeavesPriorCompleteBackupBytesUnchanged() throws Exception {
        Path first = save(SESSION, 1, 100).path();
        byte[] original = Files.readAllBytes(first);
        var failure = new SfcRecoveryBackups.Operations() {
            @Override public void commit(Path temporary, Path destination) throws IOException {
                throw new AtomicMoveNotSupportedException(temporary.toString(), destination.toString(), "injected");
            }
        };
        assertThrows(AtomicMoveNotSupportedException.class, () -> SfcRecoveryBackups.save(game, ROM, SESSION, new byte[]{2}, new byte[]{3}, 200, failure));
        assertArrayEquals(original, Files.readAllBytes(first));
        assertEquals(List.of(first), children(root(SESSION)));
    }

    @Test void cleanupFailureReportsCommittedBackupAndCapsFurtherGrowth() throws Exception {
        Path first = save(SESSION, 1, 1).path(), second = save(SESSION, 2, 2).path();
        var deniedDelete = new SfcRecoveryBackups.Operations() {
            @Override public void remove(Path archive) throws IOException { throw new AccessDeniedException(archive.toString()); }
        };
        var third = SfcRecoveryBackups.save(game, ROM, SESSION, new byte[]{3}, new byte[0], 3, deniedDelete);
        assertTrue(Files.exists(third.path()));
        assertTrue(third.cleanupWarning().contains("新备份已提交"));
        assertEquals(Set.of(first, second, third.path()), new HashSet<>(children(root(SESSION))));
        assertThrows(IOException.class, () -> save(SESSION, 4, 4));
        assertEquals(3, children(root(SESSION)).size());
    }

    @Test void damagedOldArchiveIsNeverDeletedToMakeRoom() throws Exception {
        Path first = save(SESSION, 1, 1).path();
        save(SESSION, 2, 2);
        byte[] damaged = new byte[]{7,8,9}; Files.write(first, damaged);
        var third = save(SESSION, 3, 3);
        assertFalse(third.cleanupWarning().isEmpty());
        assertArrayEquals(damaged, Files.readAllBytes(first));
        assertTrue(Files.exists(third.path()));
        assertEquals(3, children(root(SESSION)).size());
    }

    @Test void unknownFilesAndPriorSessionRemainUntouchedDuringRotation() throws Exception {
        var other = save(UUID.randomUUID(), 42, 999);
        byte[] prior = Files.readAllBytes(other.path());
        save(SESSION, 1, 1);
        Path note = root(SESSION).resolve("my-notes.txt"); Files.writeString(note, "keep");
        Path unknown = root(SESSION).resolve("backup-future.zip"); Files.write(unknown, new byte[]{9});
        save(SESSION, 2, 2); save(SESSION, 3, 3);
        assertEquals("keep", Files.readString(note));
        assertArrayEquals(new byte[]{9}, Files.readAllBytes(unknown));
        assertArrayEquals(prior, Files.readAllBytes(other.path()));
        assertEquals(4, children(root(SESSION)).size());
    }

    @Test void validZipWithWrongSessionIdentityIsPreservedInsteadOfPruned() throws Exception {
        Path first = save(SESSION, 1, 1).path();
        save(SESSION, 2, 2);
        Path foreign = save(UUID.randomUUID(), 8, 1).path();
        byte[] original = Files.readAllBytes(foreign);
        Files.write(first, original);
        var third = save(SESSION, 3, 3);
        assertFalse(third.cleanupWarning().isEmpty());
        assertArrayEquals(original, Files.readAllBytes(first));
        assertTrue(Files.exists(third.path()));
    }

    @Test void archiveWithCorruptedStateCrcIsPreservedInsteadOfPruned() throws Exception {
        Path first = save(SESSION, 1, 1).path();
        save(SESSION, 2, 2);
        byte[] damaged = Files.readAllBytes(first);
        var header = java.nio.ByteBuffer.wrap(damaged).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int data = 30 + Short.toUnsignedInt(header.getShort(26)) + Short.toUnsignedInt(header.getShort(28));
        damaged[data] ^= 1;
        Files.write(first, damaged);
        var third = save(SESSION, 3, 3);
        assertFalse(third.cleanupWarning().isEmpty());
        assertArrayEquals(damaged, Files.readAllBytes(first));
        assertTrue(Files.exists(third.path()));
    }

    @Test void truncatedCentralDirectoryNeverCountsAsCompleteRecoveryMaterial() throws Exception {
        Path first = save(SESSION, 1, 1).path();
        save(SESSION, 2, 2);
        byte[] archive = Files.readAllBytes(first), truncated = Arrays.copyOf(archive, archive.length - 8);
        Files.write(first, truncated);
        var third = save(SESSION, 3, 3);
        assertFalse(third.cleanupWarning().isEmpty());
        assertArrayEquals(truncated, Files.readAllBytes(first));
        assertTrue(Files.exists(third.path()));
    }

    @Test void centralDirectoryPointingSramAtStateHeaderNeverDeletesDamagedOldArchive() throws Exception {
        Path first = save(SESSION, 1, 1).path();
        save(SESSION, 2, 2);
        byte[] damaged = Files.readAllBytes(first);
        var bytes = java.nio.ByteBuffer.wrap(damaged).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int entry = bytes.getInt(damaged.length - 6);
        boolean changed = false;
        for (int i = 0; i < 3; i++) {
            assertEquals(0x02014b50, bytes.getInt(entry));
            int nameBytes = Short.toUnsignedInt(bytes.getShort(entry + 28));
            int extra = Short.toUnsignedInt(bytes.getShort(entry + 30));
            int comment = Short.toUnsignedInt(bytes.getShort(entry + 32));
            String name = new String(damaged, entry + 46, nameBytes, StandardCharsets.UTF_8);
            if (name.equals("sram.bin")) {
                assertTrue(bytes.getInt(entry + 42) > 0);
                bytes.putInt(entry + 42, 0);
                changed = true;
            }
            entry += 46 + nameBytes + extra + comment;
        }
        assertTrue(changed);
        Files.write(first, damaged);
        var third = save(SESSION, 3, 3);
        assertFalse(third.cleanupWarning().isEmpty());
        assertArrayEquals(damaged, Files.readAllBytes(first));
        assertTrue(Files.exists(third.path()));
    }

    @Test void fileInsteadOfParentDirectoryFailsWithoutReplacingIt() throws Exception {
        Path occupied = game.resolve("piq-sfc-home"); Files.writeString(occupied, "user file");
        assertThrows(IOException.class, () -> save(SESSION, 1, 1));
        assertEquals("user file", Files.readString(occupied));
    }

    @Test void archiveNamedDirectoryIsNotFollowedOrDeleted() throws Exception {
        save(SESSION, 1, 1);
        Path occupied = root(SESSION).resolve("backup-00000000000000000002-" + UUID.randomUUID() + ".zip");
        Files.createDirectory(occupied); Files.writeString(occupied.resolve("owned.txt"), "keep");
        assertThrows(IOException.class, () -> save(SESSION, 2, 2));
        assertEquals("keep", Files.readString(occupied.resolve("owned.txt")));
    }

    @Test void invalidArgumentsAreRejectedBeforeDirectoryCreation() throws Exception {
        assertThrows(IOException.class, () -> SfcRecoveryBackups.save(game, "../escape", SESSION, new byte[]{1}, new byte[0], 1));
        assertThrows(IOException.class, () -> SfcRecoveryBackups.save(game, ROM, null, new byte[]{1}, new byte[0], 1));
        assertThrows(IOException.class, () -> SfcRecoveryBackups.save(game, ROM, SESSION, new byte[0], new byte[0], 1));
        assertThrows(IOException.class, () -> SfcRecoveryBackups.save(game, ROM, SESSION, new byte[]{1}, new byte[0], -1));
        assertThrows(IOException.class, () -> SfcRecoveryBackups.save(game, ROM, SESSION, new byte[]{1}, new byte[SfcRecoveryBackups.MAX_SRAM + 1], 1));
        assertFalse(Files.exists(game.resolve("piq-sfc-home")));
    }

    @Test void overfullUnknownDirectoryFailsClosedAndPreservesEveryUnknownFile() throws Exception {
        save(SESSION, 1, 1);
        for (int i = 0; i < 17; i++) Files.writeString(root(SESSION).resolve("user-" + i), "keep-" + i);
        assertThrows(IOException.class, () -> save(SESSION, 2, 2));
        for (int i = 0; i < 17; i++) assertEquals("keep-" + i, Files.readString(root(SESSION).resolve("user-" + i)));
        assertEquals(18, children(root(SESSION)).size());
    }

    private SfcRecoveryBackups.Result save(UUID session, int marker, int frame) throws IOException {
        return SfcRecoveryBackups.save(game, ROM, session, new byte[]{(byte) marker}, new byte[]{(byte) (marker + 1)}, frame);
    }
    private Path root(UUID session) { return game.resolve("game-console/piq-sfc-home/local-backups/" + ROM + "/sessions/" + session); }
    private static List<Path> children(Path root) throws IOException { try (var paths = Files.list(root)) { return paths.sorted().toList(); } }
    private static byte[] read(ZipFile zip, String name) throws IOException { try (var input = zip.getInputStream(zip.getEntry(name))) { return input.readAllBytes(); } }
}
