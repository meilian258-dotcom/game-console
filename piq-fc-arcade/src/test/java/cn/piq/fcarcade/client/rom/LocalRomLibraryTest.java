package cn.piq.fcarcade.client.rom;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class LocalRomLibraryTest {
    @TempDir Path temp;

    @Test void gettersArePureAndUseFixedSubdirectories() {
        Path game = temp.resolve("not-created");
        assertEquals(game.resolve("game-console/piq-sfc-home/roms"), LocalRomLibrary.sfcDirectory(game));
        assertEquals(game.resolve("game-console/piq-native-arcade/roms"), LocalRomLibrary.arcadeDirectory(game));
        assertFalse(Files.exists(game));
    }

    @Test void prepareCreatesOnlyRequestedDirectoriesAndIsIdempotent() throws Exception {
        Path wanted = temp.resolve("实例 中文/piq-native-arcade/roms");
        assertEquals(wanted, LocalRomLibrary.prepare(wanted));
        Files.writeString(wanted.resolve("user-note.txt"), "preserved");
        assertEquals(wanted, LocalRomLibrary.prepare(wanted));
        assertEquals("preserved", Files.readString(wanted.resolve("user-note.txt")));
        assertFalse(Files.exists(temp.resolve("piq-sfc-home")));
    }

    @Test void prepareNeverReplacesExistingFiles() throws Exception {
        Path file = temp.resolve("occupied"); Files.writeString(file, "keep");
        assertThrows(IOException.class, () -> LocalRomLibrary.prepare(file.resolve("roms")));
        assertEquals("keep", Files.readString(file));
    }

    @Test void scanDoesNotCreateMissingDirectory() {
        Path missing = temp.resolve("missing");
        assertThrows(IOException.class, () -> LocalRomLibrary.scan(missing, Set.of(".sfc"), Set.of()));
        assertFalse(Files.exists(missing));
    }

    @Test void directMetadataOnlyUnicodeAndCaseInsensitiveFiltering() throws Exception {
        Files.write(temp.resolve("Z.SFC"), new byte[]{1,2,3});
        Files.write(temp.resolve("游戏.sMc"), new byte[]{4});
        Files.writeString(temp.resolve("a.sfc"), "not a ROM header");
        Files.writeString(temp.resolve("wrong.sfc.exe"), "ignore");
        Files.writeString(temp.resolve("note.txt"), "ignore");
        Files.createDirectory(temp.resolve("directory.sfc"));
        Files.write(temp.resolve("directory.sfc/nested.sfc"), new byte[]{9});
        var scan = LocalRomLibrary.scan(temp, Set.of("SFC", ".smc"), Set.of());
        assertEquals(java.util.List.of("a.sfc", "Z.SFC", "游戏.sMc"), scan.entries().stream().map(LocalRomLibrary.Entry::fileName).toList());
        assertEquals(3, scan.skipped()); assertFalse(scan.limited());
        assertEquals(3, scan.entries().get(1).bytes());
        assertEquals(temp.resolve("Z.SFC"), scan.entries().get(1).path());
        assertThrows(UnsupportedOperationException.class, () -> scan.entries().clear());
    }

    @Test void biosFilesNeverAppearAsGamesRegardlessOfCase() throws Exception {
        for (String name : java.util.List.of("kof97.zip", "NEOGEO.ZIP", "qsound_hle.zip", "dino.zip"))
            Files.writeString(temp.resolve(name), "opaque, never parsed");
        var scan = LocalRomLibrary.scan(temp, Set.of(".ZIP"), Set.of("neogeo.zip", "QSOUND_HLE.ZIP"));
        assertEquals(java.util.List.of("dino.zip", "kof97.zip"), scan.entries().stream().map(LocalRomLibrary.Entry::fileName).toList());
        assertEquals(2, scan.skipped());
    }

    @Test void resultLimitAndInspectedLimitAreBounded() throws Exception {
        // Isolate capacity limits from slow Windows metadata calls and the separate
        // two-second cooperative deadline. Other tests retain real host paths.
        Path archive = temp.resolve("capacity-fixture.zip");
        assertFalse(Files.exists(archive));
        try (var fs = FileSystems.newFileSystem(archive, Map.of("create", "true"))) {
            Path games = Files.createDirectory(fs.getPath("/games"));
            for (int i=0;i<520;i++) Files.createFile(games.resolve("game"+i+".zip"));
            var matches = LocalRomLibrary.scan(games, Set.of(".zip"), Set.of());
            assertTrue(matches.limited()); assertEquals(512, matches.entries().size());
            Path ignored = Files.createDirectory(fs.getPath("/ignored"));
            for (int i=0;i<2050;i++) Files.createFile(ignored.resolve("other"+i+".txt"));
            var nonmatches = LocalRomLibrary.scan(ignored, Set.of(".zip"), Set.of());
            assertTrue(nonmatches.limited()); assertEquals(2048, nonmatches.skipped());
            assertTrue(nonmatches.entries().isEmpty());
        }
    }

    @Test void invalidExtensionsAndExclusionsCannotBroadenDiscovery() {
        for (Set<String> extensions : java.util.List.of(Set.<String>of(), Set.of("*"), Set.of("../zip"), Set.of(".zip|.exe")))
            assertThrows(IOException.class, () -> LocalRomLibrary.scan(temp, extensions, Set.of()));
        assertThrows(IOException.class, () -> LocalRomLibrary.scan(temp, Set.of("zip"), Set.of("../neogeo.zip")));
    }

    @Test void validateSelectedFileRejectsRemovedAndDirectoryEntries() throws Exception {
        Path game = temp.resolve("sample.sfc"); Files.writeString(game, "opaque");
        LocalRomLibrary.validateFile(game);
        Files.delete(game);
        assertThrows(IOException.class, () -> LocalRomLibrary.validateFile(game));
        Files.createDirectory(game);
        assertThrows(IOException.class, () -> LocalRomLibrary.validateFile(game));
    }

    @Test void interruptedOperationsStopAndPreserveInterruptFlag() {
        try {
            Thread.currentThread().interrupt();
            assertThrows(InterruptedIOException.class, () -> LocalRomLibrary.scan(temp, Set.of("zip"), Set.of()));
            assertTrue(Thread.currentThread().isInterrupted());
            assertThrows(InterruptedIOException.class, () -> LocalRomLibrary.prepare(temp.resolve("cancelled")));
            assertFalse(Files.exists(temp.resolve("cancelled")));
        } finally { Thread.interrupted(); }
    }

    @Test void linksToFilesAreSkippedAndCannotBeSelected() throws Exception {
        Path target = temp.resolve("target.sfc"); Files.writeString(target, "real");
        Path link = temp.resolve("linked.sfc"); makeLinkOrSkip(link, target);
        var scan = LocalRomLibrary.scan(temp, Set.of("sfc"), Set.of());
        assertEquals(1, scan.entries().size()); assertEquals("target.sfc", scan.entries().getFirst().fileName());
        assertThrows(IOException.class, () -> LocalRomLibrary.validateFile(link));
    }

    @Test void linkedDirectoryAndLinkedParentAreRejectedWithoutCreatingChildren() throws Exception {
        Path target = Files.createDirectory(temp.resolve("target"));
        Files.writeString(target.resolve("game.zip"), "opaque");
        Path link = temp.resolve("redirect"); makeLinkOrSkip(link, target);
        assertThrows(IOException.class, () -> LocalRomLibrary.scan(link, Set.of("zip"), Set.of()));
        assertThrows(IOException.class, () -> LocalRomLibrary.validateFile(link.resolve("game.zip")));
        assertThrows(IOException.class, () -> LocalRomLibrary.prepare(link.resolve("must-not-create")));
        assertFalse(Files.exists(target.resolve("must-not-create")));
    }

    @Test void submitIsDaemonOffCallerAndQueueNeverRunsInline() throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1), done = new CountDownLatch(8);
        AtomicBoolean daemon = new AtomicBoolean(), rejectedRan = new AtomicBoolean();
        Thread caller = Thread.currentThread(); AtomicBoolean different = new AtomicBoolean();
        assertFalse(LocalRomLibrary.submit(null));
        assertTrue(LocalRomLibrary.submit(() -> {
            daemon.set(Thread.currentThread().isDaemon()); different.set(Thread.currentThread() != caller); started.countDown();
            try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
        }));
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS));
            for (int i=0;i<8;i++) assertTrue(LocalRomLibrary.submit(done::countDown));
            assertFalse(LocalRomLibrary.submit(() -> rejectedRan.set(true)));
            assertFalse(rejectedRan.get()); assertTrue(daemon.get()); assertTrue(different.get());
        } finally { release.countDown(); }
        assertTrue(done.await(2, TimeUnit.SECONDS)); assertFalse(rejectedRan.get());
    }

    private static void makeLinkOrSkip(Path link, Path target) throws Exception {
        try { Files.createSymbolicLink(link, target); }
        catch (IOException | UnsupportedOperationException | SecurityException denied) {
            assumeTrue(false, "Symbolic link privilege unavailable: "+denied.getClass().getSimpleName());
        }
    }
}
