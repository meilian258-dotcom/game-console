package cn.piq.fcarcade.server;

import cn.piq.fcarcade.rom.RomRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class ServerRomLibraryTest {
    @TempDir Path directory;

    @Test
    void idleLeaderboardDefaultsOffAndRetainsExplicitChoices() {
        assertFalse(ServerRomLibrary.leaderboardEnabledFromSetting(null));
        assertFalse(ServerRomLibrary.leaderboardEnabledFromSetting(""));
        assertFalse(ServerRomLibrary.leaderboardEnabledFromSetting("false"));
        assertTrue(ServerRomLibrary.leaderboardEnabledFromSetting("true"));
        assertTrue(ServerRomLibrary.leaderboardEnabledFromSetting("TRUE"));
    }

    @Test
    void catalogKeepsModernAndUnsupportedBoardsButRefusesUnsupportedSelectionAndReuse() throws Exception {
        int[] mappers={25,69,66,85,4095};
        for(int mapper:mappers){
            byte[] bytes=rom(mapper);
            bytes[6]=(byte)((mapper&15)<<4);
            bytes[7]=(byte)((mapper&240)|8);
            bytes[8]=(byte)(mapper>>>8);
            Files.write(directory.resolve("mapper-"+mapper+".nes"),bytes);
        }
        Files.write(directory.resolve("broken.nes"),new byte[20]);
        ServerRomLibrary library=new ServerRomLibrary(directory,Runnable::run,()->{},warning->fail(warning));
        library.refreshCatalogAsync().join();
        assertEquals(5,library.catalog().size());
        assertEquals(5,library.homeCatalog().size());
        for(var entry:library.homeCatalog())assertNotNull(library.find(entry.sha256()));
        var unknown=library.homeCatalog().stream().filter(e->e.mapper()==4095).findFirst().orElseThrow();
        var descriptor=library.find(unknown.sha256());
        var failure=assertThrows(IllegalArgumentException.class,()->library.select(null,null,null,unknown.sha256()));
        assertTrue(failure.getMessage().contains("Mapper 4095"));
        assertThrows(IllegalArgumentException.class,()->library.store("unknown.nes",unknown.sha256(),descriptor.bytes()));
        assertTrue(Files.exists(descriptor.path()));
    }

    @Test
    void uploadBetweenInitialDiskScanAndPublishKeepsOldAndNewGamesImmediately() throws Exception {
        byte[] original = rom(11);
        byte[] uploaded = rom(12);
        String originalHash = RomRepository.sha256(original);
        String uploadedHash = RomRepository.sha256(uploaded);
        Files.write(directory.resolve("original.nes"), original);
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        AtomicReference<ServerRomLibrary> reference = new AtomicReference<>();
        ServerRomLibrary library = new ServerRomLibrary(directory, queue::addLast,
                () -> reference.get().store("uploaded.nes", uploadedHash, uploaded),
                warning -> fail(warning));
        reference.set(library);
        var initialScan = library.refreshCatalogAsync();
        assertEquals(1, queue.size());
        queue.removeFirst().run();
        initialScan.join();

        // No second catalog request or scheduled retry is needed to recover the original game.
        assertNotNull(library.find(originalHash));
        assertNotNull(library.find(uploadedHash));
        assertTrue(queue.isEmpty());
    }

    @Test
    void deleteAfterDiskScanWinsWithoutDiscardingOtherOriginalGames() throws Exception {
        byte[] kept = rom(21);
        byte[] removed = rom(22);
        byte[] added = rom(23);
        String keptHash = RomRepository.sha256(kept);
        String removedHash = RomRepository.sha256(removed);
        String addedHash = RomRepository.sha256(added);
        Files.write(directory.resolve("kept.nes"), kept);
        Files.write(directory.resolve("removed.nes"), removed);
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        AtomicReference<ServerRomLibrary> reference = new AtomicReference<>();
        ServerRomLibrary library = new ServerRomLibrary(directory, queue::addLast, () -> {
            ServerRomLibrary current = reference.get();
            // Register an existing on-disk ROM, then delete it while its old descriptor is in the scan.
            current.store("removed.nes", removedHash, removed);
            assertTrue(current.delete(removedHash));
            current.store("added.nes", addedHash, added);
        }, warning -> fail(warning));
        reference.set(library);
        var initialScan = library.refreshCatalogAsync();
        queue.removeFirst().run();
        initialScan.join();

        assertNotNull(library.find(keptHash));
        assertNotNull(library.find(addedHash));
        assertNull(library.find(removedHash));
        assertFalse(Files.exists(directory.resolve("removed.nes")));
        assertTrue(queue.isEmpty());
    }

    @Test
    void capacityTrimmingWarnsOnceAndNeverDeletesHiddenFiles() throws Exception {
        for (int index = 0; index <= 256; index++) {
            Files.write(directory.resolve(String.format("game-%03d.nes", index)), rom(index));
        }
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        ArrayList<String> warnings = new ArrayList<>();
        ServerRomLibrary library = new ServerRomLibrary(
                directory, queue::addLast, () -> {}, warnings::add);
        var initialScan = library.refreshCatalogAsync();
        queue.removeFirst().run();
        initialScan.join();
        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().contains("256"));
        assertTrue(warnings.getFirst().contains("128 MiB"));
        int available = 0;
        for (int index = 0; index <= 256; index++) {
            if (library.find(RomRepository.sha256(rom(index))) != null) available++;
        }
        assertEquals(256, available);
        try (var files = Files.list(directory)) {
            assertEquals(257, files.filter(path -> path.toString().endsWith(".nes")).count());
        }
        for (int index = 500; index < 502; index++) {
            byte[] attempted = rom(index);
            assertThrows(IllegalStateException.class,
                    () -> library.store("extra.nes", RomRepository.sha256(attempted), attempted));
        }
        assertEquals(1, warnings.size());
    }

    @Test
    void negativeLookupDoesNotDiscoverFilesAddedAfterCompletedIndex() throws Exception {
        byte[] first = rom(1);
        Files.write(directory.resolve("first.nes"), first);
        ServerRomLibrary library = new ServerRomLibrary(directory);
        library.refreshCatalogAsync().join();
        assertNotNull(library.find(RomRepository.sha256(first)));
        byte[] later = rom(2);
        String laterHash = RomRepository.sha256(later);
        Files.write(directory.resolve("later.nes"), later);
        // A full-library lookup would now find later.nes: repeated packet lookups must not.
        for (int request = 0; request < 1000; request++) assertNull(library.find(laterHash));
        Files.delete(directory.resolve("first.nes"));
        assertNotNull(library.find(RomRepository.sha256(first)));
        assertNull(library.find("f".repeat(64)));
    }

    @Test
    void uploadAndDeleteUpdateTheSameIndexWithoutWaitingForAnotherScan() {
        ServerRomLibrary library = new ServerRomLibrary(directory);
        library.refreshCatalogAsync().join();
        byte[] bytes = rom(3);
        String hash = RomRepository.sha256(bytes);
        library.store("new.nes", hash, bytes);
        assertNotNull(library.find(hash));
        assertTrue(library.delete(hash));
        assertNull(library.find(hash));
        assertFalse(library.delete(hash));
    }

    private static byte[] rom(int marker) {
        byte[] bytes = new byte[16 + 16384 + 8192];
        bytes[0] = 'N'; bytes[1] = 'E'; bytes[2] = 'S'; bytes[3] = 0x1a;
        bytes[4] = 1; bytes[5] = 1; bytes[16] = (byte) marker; bytes[17] = (byte) (marker >>> 8);
        return bytes;
    }
}
