package cn.piq.fcarcade.server;

import cn.piq.fcarcade.rom.RomRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ServerRomPlayersPolicyTest {
    @TempDir Path directory;
    private static byte[] rom() {
        byte[] bytes = new byte[16 + 16384 + 8192];
        bytes[0] = 'N'; bytes[1] = 'E'; bytes[2] = 'S'; bytes[3] = 0x1a;
        bytes[4] = 1; bytes[5] = 1;
        return bytes;
    }
    private ServerRomLibrary library() {
        var library = new ServerRomLibrary(directory, Runnable::run, () -> {}, warning -> fail(warning));
        library.store("players-policy.nes", RomRepository.sha256(rom()), rom());
        return library;
    }
    @Test void existingRomUploadDoesNotResetSharedTwoPlayerMetadata() {
        var library = library(); String hash = RomRepository.sha256(rom());
        assertEquals(1, library.maxPlayers(hash));
        library.setMaxPlayers(hash, 2);
        library.store("same-rom.nes", hash, rom());
        assertEquals(2, library.maxPlayers(hash));
        assertEquals(2, library().maxPlayers(hash));
    }
    @Test void unknownRomAndInvalidCountsDoNotCreateMetadata() {
        var library = library(); String hash = RomRepository.sha256(rom());
        assertThrows(IllegalArgumentException.class, () -> library.setMaxPlayers("a".repeat(64), 2));
        assertThrows(IllegalArgumentException.class, () -> library.setMaxPlayers(hash, 0));
        assertThrows(IllegalArgumentException.class, () -> library.setMaxPlayers(hash, 3));
        assertEquals(1, library.maxPlayers(hash));
        assertFalse(Files.exists(directory.resolve("rom-metadata.properties")));
    }
    @Test void failedFirstSaveRestoresDefaultInsteadOfLeavingTwoPlayersInMemory() throws Exception {
        var library = library(); String hash = RomRepository.sha256(rom());
        Path obstruction = Files.createDirectory(directory.resolve("rom-metadata.properties"));
        Files.writeString(obstruction.resolve("keep.txt"), "test-owned obstruction");
        assertThrows(IllegalStateException.class, () -> library.setMaxPlayers(hash, 2));
        assertEquals(1, library.maxPlayers(hash));
        assertEquals("test-owned obstruction", Files.readString(obstruction.resolve("keep.txt")));
    }
    @Test void failedChangeRestoresExplicitPreviousCountAndDoesNotTouchSavedCopy() throws Exception {
        var library = library(); String hash = RomRepository.sha256(rom());
        library.setMaxPlayers(hash, 2);
        Path metadata = directory.resolve("rom-metadata.properties");
        Path savedCopy = directory.resolve("previous-metadata.properties");
        Files.move(metadata, savedCopy);
        String before = Files.readString(savedCopy);
        Files.createDirectory(metadata);
        Files.writeString(metadata.resolve("keep.txt"), "test-owned obstruction");
        assertThrows(IllegalStateException.class, () -> library.setMaxPlayers(hash, 1));
        assertEquals(2, library.maxPlayers(hash));
        assertEquals(before, Files.readString(savedCopy));
    }
    @Test void missingHomeCountDefaultsToTwoWithoutChangingLegacyCatalogOrWritingMetadata() {
        var library = library(); String hash = RomRepository.sha256(rom());
        assertEquals(2, library.homeMaxPlayers(hash));
        assertEquals(1, library.maxPlayers(hash));
        assertEquals(2, library.homeCatalog().getFirst().maxPlayers());
        assertEquals(1, library.catalog().getFirst().maxPlayers());
        assertFalse(Files.exists(directory.resolve("rom-metadata.properties")));
    }
    @Test void explicitSingleAndDoublePlayerSettingsWinInBothContextsAndSurviveReload() {
        String hash = RomRepository.sha256(rom());
        for (int players : new int[]{1, 2}) {
            var library = library();
            library.setMaxPlayers(hash, players);
            var reloaded = library();
            assertEquals(players, reloaded.homeMaxPlayers(hash));
            assertEquals(players, reloaded.maxPlayers(hash));
            assertEquals(players, reloaded.homeCatalog().getFirst().maxPlayers());
            assertEquals(players, reloaded.catalog().getFirst().maxPlayers());
        }
    }
    @Test void legacySingleAndDoublePlayerKeysArePreservedAndModernKeysTakePrecedence() throws Exception {
        String hash = RomRepository.sha256(rom());
        for (int old : new int[]{1, 2}) {
            String metadata = hash + "=" + old + "\n";
            Files.writeString(directory.resolve("rom-metadata.properties"), metadata);
            var library = library();
            assertEquals(old, library.homeMaxPlayers(hash));
            assertEquals(metadata, Files.readString(directory.resolve("rom-metadata.properties")));
            int explicit = old == 1 ? 2 : 1;
            Files.writeString(directory.resolve("rom-metadata.properties"), metadata + hash + ".players=" + explicit + "\n");
            assertEquals(explicit, library().homeMaxPlayers(hash));
        }
    }
    @Test void malformedPresentCountsKeepTheConservativeLegacySinglePlayerMeaning() throws Exception {
        String hash = RomRepository.sha256(rom());
        for (String invalid : new String[]{"", "0", "3", "true", "two"}) {
            String metadata = hash + ".players=" + invalid + "\n";
            Files.writeString(directory.resolve("rom-metadata.properties"), metadata);
            assertEquals(1, library().homeMaxPlayers(hash));
            assertEquals(metadata, Files.readString(directory.resolve("rom-metadata.properties")));
        }
    }
}
