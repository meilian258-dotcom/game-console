package cn.piq.fcarcade.rom;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RomRepositoryTest {
    @TempDir
    Path tempDir;

    @Test
    void loadsRomAndCalculatesStableHash() throws IOException {
        byte[] rom = INesHeaderTest.nrom(1, 1, 0);
        Files.write(tempDir.resolve("test.nes"), rom);

        RomDescriptor descriptor = new RomRepository(tempDir).load("test.nes");

        assertEquals("test.nes", descriptor.fileName());
        assertEquals(64, descriptor.sha256().length());
        assertEquals(0, descriptor.header().mapper());
    }

    @Test
    void blocksTraversalAndWrongExtension() {
        RomRepository repository = new RomRepository(tempDir);
        assertThrows(IllegalArgumentException.class, () -> repository.load("../outside.nes"));
        assertThrows(IllegalArgumentException.class, () -> repository.load("test.zip"));
    }

    @Test
    void storesVerifiedRomWithSanitizedNameAndFindsItByHash() throws IOException {
        byte[] rom = INesHeaderTest.nrom(1, 1, 0);
        String hash = RomRepository.sha256(rom);
        RomRepository repository = new RomRepository(tempDir);

        RomDescriptor stored = repository.storeVerified("../我的:游戏.nes", hash, rom);
        RomDescriptor found = repository.findBySha256(hash);

        assertNotNull(found);
        assertEquals("_我的_游戏.nes", stored.fileName());
        assertEquals(tempDir.toAbsolutePath().normalize(), stored.path().getParent());
        assertArrayEquals(rom, found.bytes());
    }

    @Test
    void rejectsHashMismatchAndUnsupportedMapper() {
        byte[] rom = INesHeaderTest.nrom(1, 1, 0);
        RomRepository repository = new RomRepository(tempDir);
        assertThrows(
                IllegalArgumentException.class,
                () -> repository.storeVerified("test.nes", "0".repeat(64), rom));

        byte[] unsupported = rom.clone();
        unsupported[6] = 0x40;
        unsupported[7] = 0x10; // Mapper 20 has no iNES mapper factory (FDS is a separate format).
        assertThrows(
                IllegalArgumentException.class,
                () -> repository.storeVerified(
                        "unsupported.nes",
                        RomRepository.sha256(unsupported),
                        unsupported));
    }

    @Test
    void brokenRomDoesNotHideValidLibraryEntries() throws IOException {
        Files.write(tempDir.resolve("broken.nes"), new byte[]{1, 2, 3});
        byte[] rom = INesHeaderTest.nrom(1, 1, 0);
        Files.write(tempDir.resolve("valid.nes"), rom);

        assertEquals(1, new RomRepository(tempDir).list().size());
        assertEquals("valid.nes", new RomRepository(tempDir).list().getFirst().fileName());
    }

    @Test
    void deletesOnlyTheRomMatchingTheRequestedHash() throws IOException {
        byte[] first = INesHeaderTest.nrom(1, 1, 0);
        byte[] second = first.clone();
        second[16] = 42;
        Files.write(tempDir.resolve("first.nes"), first);
        Files.write(tempDir.resolve("second.nes"), second);
        RomRepository repository = new RomRepository(tempDir);

        assertTrue(repository.deleteBySha256(RomRepository.sha256(first)));
        assertFalse(Files.exists(tempDir.resolve("first.nes")));
        assertTrue(Files.exists(tempDir.resolve("second.nes")));
        assertFalse(repository.deleteBySha256("0".repeat(64)));
    }

    @Test
    void occupiedRequestedNameUsesFreshFallbackWithoutChangingOriginal() throws IOException {
        byte[] original = INesHeaderTest.nrom(1, 1, 0), incoming = original.clone();
        incoming[16] = 23;
        Path first = tempDir.resolve("game.nes");
        Files.write(first, original);
        String originalHash = RomRepository.sha256(Files.readAllBytes(first));
        String incomingHash = RomRepository.sha256(incoming);

        RomDescriptor stored = new RomRepository(tempDir).storeVerified("game.nes", incomingHash, incoming);

        assertEquals("game-" + incomingHash.substring(0, 12) + ".nes", stored.fileName());
        assertEquals(originalHash, RomRepository.sha256(Files.readAllBytes(first)));
        assertArrayEquals(incoming, Files.readAllBytes(stored.path()));
        assertEquals(2, new RomRepository(tempDir).list().size());
        assertNoTemporaryUploads();
    }

    @Test
    void occupiedFallbackIsRejectedAndBothExistingHashesArePreserved() throws IOException {
        byte[] original = INesHeaderTest.nrom(1, 1, 0), fallback = original.clone(), incoming = original.clone();
        fallback[16] = 41; incoming[16] = 42;
        String incomingHash = RomRepository.sha256(incoming);
        Path first = tempDir.resolve("game.nes");
        Path occupied = tempDir.resolve("game-" + incomingHash.substring(0, 12) + ".nes");
        Files.write(first, original); Files.write(occupied, fallback);
        String originalHash = RomRepository.sha256(Files.readAllBytes(first));
        String fallbackHash = RomRepository.sha256(Files.readAllBytes(occupied));

        assertThrows(java.nio.file.FileAlreadyExistsException.class,
                () -> new RomRepository(tempDir).storeVerified("game.nes", incomingHash, incoming));

        assertEquals(originalHash, RomRepository.sha256(Files.readAllBytes(first)));
        assertEquals(fallbackHash, RomRepository.sha256(Files.readAllBytes(occupied)));
        assertEquals(2, new RomRepository(tempDir).list().size());
        assertNoTemporaryUploads();
    }

    @Test
    void identicalHashReusesExistingFileBeforeConsideringConflictingName() throws IOException {
        byte[] existing = INesHeaderTest.nrom(1, 1, 0), other = existing.clone();
        other[16] = 7;
        Path existingPath = tempDir.resolve("already-owned.nes"), requestedPath = tempDir.resolve("game.nes");
        Files.write(existingPath, existing); Files.write(requestedPath, other);
        String hash = RomRepository.sha256(existing);

        RomDescriptor stored = new RomRepository(tempDir).storeVerified("game.nes", hash, existing);

        assertEquals(existingPath.toAbsolutePath().normalize(), stored.path());
        assertArrayEquals(existing, Files.readAllBytes(existingPath));
        assertArrayEquals(other, Files.readAllBytes(requestedPath));
        assertEquals(2, new RomRepository(tempDir).list().size());
        assertNoTemporaryUploads();
    }

    @Test
    void freshVerifiedUploadPublishesOnlyTheCompleteRequestedFile() throws IOException {
        byte[] rom = INesHeaderTest.nrom(1, 1, 0); rom[16] = 67;
        String hash = RomRepository.sha256(rom);
        RomRepository repository = new RomRepository(tempDir);

        RomDescriptor stored = repository.storeVerified("fresh.nes", hash, rom);

        assertEquals(tempDir.resolve("fresh.nes").toAbsolutePath().normalize(), stored.path());
        assertEquals(hash, RomRepository.sha256(Files.readAllBytes(stored.path())));
        assertArrayEquals(rom, repository.load("fresh.nes").bytes());
        assertEquals(1, repository.list().size());
        assertNoTemporaryUploads();
    }

    private void assertNoTemporaryUploads() throws IOException {
        try (var files = Files.list(tempDir)) {
            assertFalse(files.anyMatch(file -> file.getFileName().toString().startsWith(".piq-rom-")));
        }
    }
}
