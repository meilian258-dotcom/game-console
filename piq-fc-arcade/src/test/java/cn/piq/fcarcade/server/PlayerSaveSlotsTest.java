package cn.piq.fcarcade.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerSaveSlotsTest {
    private static final UUID PLAYER =
            UUID.fromString("00000000-0000-0000-0000-000000000001");

    @TempDir
    Path temporary;

    @Test
    void migratesOnlyThreeMostRecentCrossRomSavesIntoGlobalSlots()
            throws Exception {
        ArcadeSaveStore store = new ArcadeSaveStore(temporary);
        String owner = "player|" + PLAYER;
        String[] keys = {
                owner,
                owner + "|slot|1",
                owner + "|slot|2",
                owner + "|slot|3"
        };
        String[] roms = {
                "a".repeat(64),
                "b".repeat(64),
                "c".repeat(64),
                "d".repeat(64)
        };
        for (int index = 0; index < keys.length; index++) {
            store.save(
                    keys[index],
                    roms[index],
                    new byte[]{(byte) index},
                    "旧存档 " + index,
                    index == 2 ? 2 : 1);
            Files.setLastModifiedTime(
                    store.path(keys[index], roms[index]),
                    FileTime.from(Instant.parse(
                            "2026-07-2" + (index + 1)
                                    + "T00:00:00Z")));
        }

        PlayerSaveSlots.migrateLegacy(
                store,
                PLAYER,
                slot -> "存档 " + slot,
                ignored -> false,
                key -> false);

        assertArrayEquals(
                new byte[]{3},
                store.load(PlayerSaveSlots.key(PLAYER, 1), roms[3]));
        assertArrayEquals(
                new byte[]{2},
                store.load(PlayerSaveSlots.key(PLAYER, 2), roms[2]));
        assertArrayEquals(
                new byte[]{1},
                store.load(PlayerSaveSlots.key(PLAYER, 3), roms[1]));
        assertTrue(store.exists(keys[0], roms[0]));
        assertFalse(store.exists(keys[1], roms[1]));
        assertFalse(store.exists(keys[2], roms[2]));
        assertFalse(store.exists(keys[3], roms[3]));
        assertEquals(4, store.list().size());
    }

    @Test
    void keepsExistingGlobalSlotAndSkipsActiveLegacySave() {
        ArcadeSaveStore store = new ArcadeSaveStore(temporary);
        String globalTwo = PlayerSaveSlots.key(PLAYER, 2);
        String oldOne = "player|" + PLAYER + "|slot|1";
        String oldTwo = "player|" + PLAYER + "|slot|2";
        String globalRom = "e".repeat(64);
        String activeRom = "f".repeat(64);
        String migratableRom = "1".repeat(64);
        store.save(globalTwo, globalRom, new byte[]{8});
        store.save(oldOne, activeRom, new byte[]{9});
        store.save(oldTwo, migratableRom, new byte[]{7});

        PlayerSaveSlots.migrateLegacy(
                store,
                PLAYER,
                slot -> "存档 " + slot,
                save -> activeRom.equals(save.romSha256()),
                key -> false);

        assertArrayEquals(
                new byte[]{7},
                store.load(PlayerSaveSlots.key(PLAYER, 1), migratableRom));
        assertArrayEquals(new byte[]{8}, store.load(globalTwo, globalRom));
        assertTrue(store.exists(oldOne, activeRom));
        assertFalse(store.exists(
                PlayerSaveSlots.key(PLAYER, 3),
                activeRom));
    }
}
