package cn.piq.fcarcade.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ArcadeSaveStoreTest {
    private static final String ROM_SHA = "b".repeat(64);

    @TempDir
    Path temporary;

    @Test
    void savesAndLoadsByMachineAndRom() {
        ArcadeSaveStore store = new ArcadeSaveStore(temporary);
        byte[] state = {1, 2, 3, 4, 5};

        store.save("minecraft:overworld|1,2,3|LOCKSTEP", ROM_SHA, state);

        assertArrayEquals(
                state,
                store.load("minecraft:overworld|1,2,3|LOCKSTEP", ROM_SHA));
        assertNull(store.load(
                "minecraft:overworld|4,5,6|LOCKSTEP",
                ROM_SHA));
    }

    @Test
    void atomicallyReplacesPreviousState() throws Exception {
        ArcadeSaveStore store = new ArcadeSaveStore(temporary);
        String key = "minecraft:overworld|1,2,3|LOCKSTEP";

        store.save(key, ROM_SHA, new byte[]{1});
        store.save(key, ROM_SHA, new byte[]{9, 8, 7});

        assertArrayEquals(new byte[]{9, 8, 7}, store.load(key, ROM_SHA));
        assertEquals(1, Files.list(temporary)
                .filter(path -> path.getFileName().toString().endsWith(".sav"))
                .count());
    }

    @Test
    void quarantinesCorruptSave() throws Exception {
        ArcadeSaveStore store = new ArcadeSaveStore(temporary);
        String key = "minecraft:overworld|1,2,3|LOCKSTEP";
        Path save = store.path(key, ROM_SHA);
        Files.createDirectories(save.getParent());
        Files.write(save, new byte[]{0, 1, 2, 3});

        assertNull(store.load(key, ROM_SHA));
        assertFalse(Files.exists(save));
        assertEquals(1, Files.list(temporary)
                .filter(path -> path.getFileName().toString().contains(".corrupt-"))
                .count());
    }

    @Test
    void rejectsOversizedOrInvalidKeys() {
        ArcadeSaveStore store = new ArcadeSaveStore(temporary);
        assertThrows(
                IllegalArgumentException.class,
                () -> store.save("", ROM_SHA, new byte[]{1}));
        assertThrows(
                IllegalArgumentException.class,
                () -> store.save("machine", "bad", new byte[]{1}));
    }

    @Test
    void detectsAndDeletesSaveSlots() {
        ArcadeSaveStore store = new ArcadeSaveStore(temporary);
        String key = "player|00000000-0000-0000-0000-000000000001";

        assertFalse(store.exists(key, ROM_SHA));
        store.save(key, ROM_SHA, new byte[]{4, 2});
        org.junit.jupiter.api.Assertions.assertTrue(store.exists(key, ROM_SHA));
        store.delete(key, ROM_SHA);
        assertFalse(store.exists(key, ROM_SHA));
    }

    @Test
    void cleansOnlyInactiveSaveFilesWhenRetentionIsEnabled() throws Exception {
        ArcadeSaveStore store = new ArcadeSaveStore(temporary);
        var oldPlayer=java.util.UUID.randomUUID();var recentPlayer=java.util.UUID.randomUUID();
        String oldKey = PlayerSaveSlots.key(oldPlayer,1);
        String recentKey = PlayerSaveSlots.key(recentPlayer,1);
        store.save(oldKey, ROM_SHA, new byte[]{1});
        store.save(recentKey, ROM_SHA, new byte[]{2});
        Path oldSave = store.path(oldKey, ROM_SHA);
        Path recentSave = store.path(recentKey, ROM_SHA);
        Instant now = Instant.parse("2026-07-28T00:00:00Z");
        store.played(oldPlayer,now.minusSeconds(31L*24*60*60));
        store.played(recentPlayer,now.minusSeconds(29L*24*60*60));
        Files.setLastModifiedTime(
                oldSave,
                FileTime.from(now.minusSeconds(31L * 24 * 60 * 60)));
        Files.setLastModifiedTime(
                recentSave,
                FileTime.from(now.minusSeconds(29L * 24 * 60 * 60)));
        Files.write(temporary.resolve("keep.corrupt-1"), new byte[]{3});

        assertEquals(0, store.cleanupOlderThanDays(0, now));
        assertEquals(1, store.cleanupOlderThanDays(30, now));
        assertFalse(Files.exists(oldSave));
        org.junit.jupiter.api.Assertions.assertTrue(Files.exists(recentSave));
        org.junit.jupiter.api.Assertions.assertTrue(
                Files.exists(temporary.resolve("keep.corrupt-1")));
    }

    @Test
    void loadingRefreshesTheSaveLastUsedTime() throws Exception {
        ArcadeSaveStore store = new ArcadeSaveStore(temporary);
        String key = "player|touch";
        store.save(key, ROM_SHA, new byte[]{7});
        Path save = store.path(key, ROM_SHA);
        Files.setLastModifiedTime(
                save,
                FileTime.from(Instant.parse("2020-01-01T00:00:00Z")));

        assertArrayEquals(new byte[]{7}, store.load(key, ROM_SHA));
        org.junit.jupiter.api.Assertions.assertTrue(
                Files.getLastModifiedTime(save).toInstant()
                        .isAfter(Instant.parse("2025-01-01T00:00:00Z")));
    }

    @Test
    void catalogsNewPlayerSavesWithTheirOwnerKey() {
        ArcadeSaveStore store = new ArcadeSaveStore(temporary);
        String playerKey =
                "player|00000000-0000-0000-0000-000000000001";
        store.save(playerKey, ROM_SHA, new byte[]{5, 4, 3});

        ArcadeSaveStore.SaveInfo save = store.list().getFirst();
        assertEquals(playerKey, save.saveKey());
        assertEquals(ROM_SHA, save.romSha256());
        assertFalse(save.legacy());
        org.junit.jupiter.api.Assertions.assertTrue(save.fileBytes() > 0);
    }

    @Test
    void persistsNamedTwoPlayerSlotMetadata() {
        ArcadeSaveStore store = new ArcadeSaveStore(temporary);
        String playerKey =
                "player|00000000-0000-0000-0000-000000000001|slot|2";
        store.save(
                playerKey,
                ROM_SHA,
                new byte[]{3, 1, 4},
                "周末双人档",
                2);

        ArcadeSaveStore.SaveInfo save = store.list().getFirst();
        assertEquals(playerKey, save.saveKey());
        assertEquals("周末双人档", save.slotName());
        assertEquals(2, save.players());
        assertArrayEquals(
                new byte[]{3, 1, 4},
                store.load(playerKey, ROM_SHA));
    }

    @Test
    void migratesVersionTwoOwnerSaveIntoFirstNamedSlot() throws Exception {
        ArcadeSaveStore store = new ArcadeSaveStore(temporary);
        String oldKey =
                "player|00000000-0000-0000-0000-000000000001";
        String newKey = oldKey + "|slot|1";
        byte[] state = {2, 7, 1, 8};
        Path savePath = store.path(oldKey, ROM_SHA);
        Files.createDirectories(savePath.getParent());
        try (DataOutputStream output = new DataOutputStream(
                Files.newOutputStream(savePath))) {
            output.writeInt(0x50465153);
            output.writeInt(2);
            output.writeUTF(oldKey);
            output.writeUTF(ROM_SHA);
            output.writeInt(state.length);
            output.write(MessageDigest.getInstance("SHA-256").digest(state));
            output.write(state);
        }

        org.junit.jupiter.api.Assertions.assertTrue(store.migrate(
                oldKey,
                newKey,
                ROM_SHA,
                "旧档升级",
                1));
        assertFalse(store.exists(oldKey, ROM_SHA));
        assertArrayEquals(state, store.load(newKey, ROM_SHA));
        ArcadeSaveStore.SaveInfo save = store.list().getFirst();
        assertEquals("旧档升级", save.slotName());
        assertEquals(1, save.players());
    }

    @Test
    void deletesCatalogEntryByValidatedStorageId() {
        ArcadeSaveStore store = new ArcadeSaveStore(temporary);
        store.save("player|owner|slot|3", ROM_SHA, new byte[]{9});
        String storageId = store.list().getFirst().storageId();

        org.junit.jupiter.api.Assertions.assertTrue(
                store.deleteByStorageId(storageId));
        assertEquals(0, store.list().size());
        assertThrows(
                IllegalArgumentException.class,
                () -> store.deleteByStorageId("../outside"));
    }

    @Test
    void loadsAndCatalogsLegacyVersionOneSaves() throws Exception {
        ArcadeSaveStore store = new ArcadeSaveStore(temporary);
        String key = "player|legacy";
        byte[] state = {8, 6, 7, 5};
        Path savePath = store.path(key, ROM_SHA);
        Files.createDirectories(savePath.getParent());
        try (DataOutputStream output = new DataOutputStream(
                Files.newOutputStream(savePath))) {
            output.writeInt(0x50465153);
            output.writeInt(1);
            output.writeUTF(ROM_SHA);
            output.writeInt(state.length);
            output.write(MessageDigest.getInstance("SHA-256").digest(state));
            output.write(state);
        }

        assertArrayEquals(state, store.load(key, ROM_SHA));
        ArcadeSaveStore.SaveInfo save = store.list().getFirst();
        assertEquals("", save.saveKey());
        org.junit.jupiter.api.Assertions.assertTrue(save.legacy());
    }
}
