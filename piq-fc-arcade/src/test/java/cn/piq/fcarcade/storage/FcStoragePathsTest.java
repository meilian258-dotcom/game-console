package cn.piq.fcarcade.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static cn.piq.fcarcade.storage.FcStoragePaths.Area.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class FcStoragePathsTest {
    @TempDir Path temporary;

    @Test void gettersArePureAndEveryAreaIsUnderOneRoot() {
        for (var area : FcStoragePaths.Area.values()) {
            assertTrue(FcStoragePaths.path(temporary, area).startsWith(temporary.resolve("game-console/piq-fc")));
        }
        assertFalse(Files.exists(temporary.resolve("game-console/piq-fc")));
        assertEquals(temporary.resolve("game-console/piq-fc/roms"), FcStoragePaths.path(temporary, ROMS));
        assertNotEquals(FcStoragePaths.path(temporary, COVERS), FcStoragePaths.path(temporary, SHARED_COVERS));
        assertNotEquals(FcStoragePaths.path(temporary, SKINS), FcStoragePaths.path(temporary, SHARED_SKINS));
        assertNotEquals(FcStoragePaths.path(temporary, COVER_CACHE), FcStoragePaths.path(temporary, SHARED_COVERS));
    }

    @Test void copiesRomBytesAndAllMetadataWithoutChangingOldFiles() throws Exception {
        Path old = Files.createDirectories(temporary.resolve("fc-roms"));
        for (String file : new String[]{"game.nes", "arcade-selections.properties", "rom-metadata.properties", "arcade-leaderboards.properties"}) {
            Files.writeString(old.resolve(file), "original:" + file);
        }
        Path nested = Files.createDirectories(old.resolve("retained-subfolder"));
        Files.writeString(nested.resolve("notes.txt"), "do not discard");
        Path target = FcStoragePaths.prepare(temporary, ROMS);
        for (String file : new String[]{"game.nes", "arcade-selections.properties", "rom-metadata.properties", "arcade-leaderboards.properties", "retained-subfolder/notes.txt"}) {
            assertEquals(-1, Files.mismatch(old.resolve(file), target.resolve(file)));
        }
        assertTrue(Files.exists(temporary.resolve("game-console/piq-fc/.migration/roms.done")));
    }

    @Test void sameBytesDoNotRewriteExistingDestination() throws Exception {
        Path old = write("fc-saves/slot.sav", "same");
        Path target = write("game-console/piq-fc/saves/slot.sav", "same");
        FileTime sentinel = FileTime.fromMillis(1_234_567_000L);
        Files.setLastModifiedTime(target, sentinel);
        FcStoragePaths.prepare(temporary, SAVES);
        assertEquals(sentinel, Files.getLastModifiedTime(target));
        assertEquals("same", Files.readString(old));
    }

    @Test void conflictKeepsNewAuthorityAndReportsPreservedOld() throws Exception {
        Path old = write("fc-saves/slot.sav", "old state");
        Path target = write("game-console/piq-fc/saves/slot.sav", "new state");
        FcStoragePaths.prepare(temporary, SAVES);
        assertEquals("old state", Files.readString(old));
        assertEquals("new state", Files.readString(target));
        String report = Files.readString(temporary.resolve("game-console/piq-fc/.migration/saves.report.txt"));
        assertTrue(report.contains("conflicts=1"));
        assertTrue(report.contains("slot.sav"));
    }

    @Test void completedMigrationNeverResurrectsDeletedSaveEvenAfterRestart() throws Exception {
        write("fc-saves/slot.sav", "old state");
        write("game-console/piq-fc/.migration/saves.done", "PIQ-FC copy migration v1\n");
        Path target = FcStoragePaths.prepare(temporary, SAVES);
        assertFalse(Files.exists(target.resolve("slot.sav")));
        assertEquals("old state", Files.readString(temporary.resolve("fc-saves/slot.sav")));
    }

    @Test void laterLegacyEditsAreNotReimported() throws Exception {
        Path target = FcStoragePaths.prepare(temporary, COVERS);
        write("fc-covers/late.png", "old directory is no longer authority");
        assertEquals(target, FcStoragePaths.prepare(temporary, COVERS));
        assertFalse(Files.exists(target.resolve("late.png")));
    }

    @Test void partialRetryComparesCopiedFilesAndPreservesOlderReports() throws Exception {
        write("fc-saves/a.sav", "a"); write("fc-saves/b.sav", "b");
        write("game-console/piq-fc/saves/a.sav", "a");
        Path oldReport = write("game-console/piq-fc/.migration/saves.report.txt", "previous attempt");
        FcStoragePaths.prepare(temporary, SAVES);
        assertEquals("b", Files.readString(temporary.resolve("game-console/piq-fc/saves/b.sav")));
        assertEquals("previous attempt", Files.readString(oldReport));
        try (var reports = Files.list(oldReport.getParent())) {
            assertTrue(reports.anyMatch(path -> path.getFileName().toString().startsWith("saves.report.txt.retry-")));
        }
    }

    @Test void onlyExplicitFcConfigFileIsCopiedAndRecoveryJournalStaysUntouched() throws Exception {
        write("config/piq-fc-arcade-client.properties", "maxSimulatedSpectators=0");
        Path other = write("config/another-mod.properties", "other");
        Path recovery = write("config/piq-fc-arcade-suppressed-keys.properties", "recover me");
        Path target = FcStoragePaths.prepare(temporary, CLIENT_CONFIG);
        assertEquals("maxSimulatedSpectators=0", Files.readString(target));
        assertFalse(Files.exists(target.getParent().resolve(other.getFileName())));
        assertFalse(Files.exists(target.getParent().resolve(recovery.getFileName())));
        assertEquals("recover me", Files.readString(recovery));
    }

    @Test void calibrationOnlyCopiesOwnExplicitFilenamePrefix() throws Exception {
        write("logs/piq-fc-scorecal-20260908-123456.txt", "calibration");
        write("logs/latest.log", "minecraft"); write("logs/other-scorecal.txt", "other mod");
        Path target = FcStoragePaths.prepare(temporary, CALIBRATION);
        assertEquals("calibration", Files.readString(target.resolve("piq-fc-scorecal-20260908-123456.txt")));
        try (var entries = Files.list(target)) { assertEquals(1, entries.count()); }
        assertEquals("minecraft", Files.readString(temporary.resolve("logs/latest.log")));
    }

    @Test void corruptedCompletionMarkerFailsClosed() throws Exception {
        write("fc-saves/slot.sav", "old");
        write("game-console/piq-fc/.migration/saves.done", "invalid");
        assertThrows(IOException.class, () -> FcStoragePaths.prepare(temporary, SAVES));
        assertFalse(Files.exists(temporary.resolve("game-console/piq-fc/saves/slot.sav")));
    }

    @Test void wrongTypeInLegacyRootDoesNotCreateAnEmptyAuthoritativeStore() throws Exception {
        write("fc-saves", "not a directory");
        assertThrows(UncheckedIOException.class, () -> FcStoragePaths.prepareUnchecked(temporary, SAVES));
        assertFalse(Files.exists(temporary.resolve("game-console/piq-fc/.migration/saves.done")));
        assertEquals("not a directory", Files.readString(temporary.resolve("fc-saves")));
    }

    @Test void blockingDestinationIsPreservedAndMigrationNotCommitted() throws Exception {
        write("fc-saves/slot.sav", "old");
        write("game-console/piq-fc/saves", "blocking destination");
        assertThrows(IOException.class, () -> FcStoragePaths.prepare(temporary, SAVES));
        assertEquals("blocking destination", Files.readString(temporary.resolve("game-console/piq-fc/saves")));
        assertFalse(Files.exists(temporary.resolve("game-console/piq-fc/.migration/saves.done")));
    }

    @Test void legacyFileSymlinkOutsideRootIsRejected() throws Exception {
        Path outside = write("outside/secret.sav", "preserve");
        Files.createDirectories(temporary.resolve("fc-saves"));
        symlink(temporary.resolve("fc-saves/linked.sav"), outside);
        assertThrows(IOException.class, () -> FcStoragePaths.prepare(temporary, SAVES));
        assertFalse(Files.exists(temporary.resolve("game-console/piq-fc/.migration/saves.done")));
        assertEquals("preserve", Files.readString(outside));
    }

    @Test void destinationDirectorySymlinkIsRejected() throws Exception {
        Path outside = Files.createDirectories(temporary.resolve("outside"));
        Files.createDirectories(temporary.resolve("game-console/piq-fc"));
        symlink(temporary.resolve("game-console/piq-fc/saves"), outside);
        write("fc-saves/slot.sav", "state");
        assertThrows(IOException.class, () -> FcStoragePaths.prepare(temporary, SAVES));
        assertFalse(Files.exists(outside.resolve("slot.sav")));
    }

    @Test void linkedLegacyAncestorIsRejected() throws Exception {
        Path outside = Files.createDirectories(temporary.resolve("outside"));
        Files.createDirectories(outside.resolve("skins"));
        symlink(temporary.resolve("piq_fc_arcade"), outside);
        assertThrows(IOException.class, () -> FcStoragePaths.prepare(temporary, SKINS));
    }

    @Test void concurrentAreasCanSafelyCreateTheirSharedParents() throws Exception {
        var executor = java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<Path>>();
            for (var area : FcStoragePaths.Area.values()) futures.add(executor.submit(() -> FcStoragePaths.prepare(temporary, area)));
            for (var future : futures) assertTrue(future.get().startsWith(temporary.resolve("game-console/piq-fc")));
        } finally { executor.shutdownNow(); }
    }

    @Test void windowsLegacyJunctionIsRejectedWithoutFollowingIt() throws Exception {
        Path outside = Files.createDirectories(temporary.resolve("outside"));
        Files.writeString(outside.resolve("slot.sav"), "preserve");
        junction(temporary.resolve("fc-saves"), outside);
        assertThrows(IOException.class, () -> FcStoragePaths.prepare(temporary, SAVES));
        assertEquals("preserve", Files.readString(outside.resolve("slot.sav")));
        assertFalse(Files.exists(temporary.resolve("game-console/piq-fc/saves/slot.sav")));
    }

    @Test void windowsDestinationJunctionIsRejectedWithoutWritingThroughIt() throws Exception {
        Path outside = Files.createDirectories(temporary.resolve("outside"));
        Files.createDirectories(temporary.resolve("game-console/piq-fc"));
        write("fc-saves/slot.sav", "preserve");
        junction(temporary.resolve("game-console/piq-fc/saves"), outside);
        assertThrows(IOException.class, () -> FcStoragePaths.prepare(temporary, SAVES));
        assertFalse(Files.exists(outside.resolve("slot.sav")));
    }

    private void junction(Path link, Path target) throws Exception {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"), "Windows junction check");
        Process process = new ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J", link.toString(), target.toString())
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.Charset.defaultCharset());
        assertEquals(0, process.waitFor(), output);
    }

    private Path write(String relative, String content) throws IOException {
        Path path = temporary.resolve(relative);
        Files.createDirectories(path.getParent());
        return Files.writeString(path, content);
    }

    private void symlink(Path link, Path target) throws IOException {
        try { Files.createSymbolicLink(link, target); }
        catch (UnsupportedOperationException | java.nio.file.FileSystemException unavailable) {
            assumeTrue(false, "Symbolic-link creation is unavailable: " + unavailable.getMessage());
        }
    }
}
