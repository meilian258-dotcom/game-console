package cn.piq.fcarcade.server;

import cn.piq.fcarcade.session.ArcadeRole;
import cn.piq.fcarcade.session.SessionRoster;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PersonalSaveLeaseTest {
    @TempDir Path directory;
    private final UUID owner = UUID.randomUUID();
    private final UUID successor = UUID.randomUUID();
    private final String rom = "a".repeat(64);

    @Test
    void handoffAndSuccessorExitCannotOverwriteOriginalSlotOrCreateSuccessorSlot() {
        ArcadeSaveStore store = new ArcadeSaveStore(directory);
        PersonalSaveLease lease = new PersonalSaveLease(owner);
        SessionRoster roster = new SessionRoster();
        roster.join(owner);
        roster.join(successor);
        String original = PlayerSaveSlots.key(owner, 1);
        lease.write(() -> store.save(original, rom, new byte[]{10}));
        assertTrue(lease.sealOnDeparture(owner));
        roster.remove(owner);
        assertEquals(ArcadeRole.PLAYER_ONE, roster.roleOf(successor));
        assertFalse(lease.write(() -> store.save(original, rom, new byte[]{99})));
        roster.remove(successor);
        assertFalse(lease.write(() -> store.save(original, rom, new byte[]{100})));
        assertArrayEquals(new byte[]{10}, store.load(original, rom));
        assertFalse(store.exists(PlayerSaveSlots.key(successor, 1), rom));
    }

    @Test
    void noSaveExitPreservesPreviousBytesAndDisconnectRejoinNeverUnsealsSharedRun() {
        ArcadeSaveStore store = new ArcadeSaveStore(directory);
        PersonalSaveLease lease = new PersonalSaveLease(owner);
        String original = PlayerSaveSlots.key(owner, 2);
        store.save(original, rom, new byte[]{1});
        SessionRoster roster = new SessionRoster();
        roster.join(owner);
        roster.join(successor);
        assertFalse(lease.sealOnDeparture(successor));
        assertTrue(lease.sealOnDeparture(owner));
        ArcadeRole previous = roster.roleOf(owner);
        roster.remove(owner);
        assertFalse(lease.write(() -> store.save(original, rom, new byte[]{2})));
        roster.rejoin(owner, previous);
        assertEquals(ArcadeRole.PLAYER_ONE, roster.roleOf(owner));
        assertFalse(lease.write(() -> store.save(original, rom, new byte[]{3})));
        assertArrayEquals(new byte[]{1}, store.load(original, rom));
        PersonalSaveLease newRun = new PersonalSaveLease(successor);
        assertTrue(newRun.write(() -> store.save(PlayerSaveSlots.key(successor, 2), rom, new byte[]{4})));
        assertArrayEquals(new byte[]{4}, store.load(PlayerSaveSlots.key(successor, 2), rom));
        assertArrayEquals(new byte[]{1}, store.load(original, rom));
    }

    @Test
    void persistenceFailureIsNotReportedAsSuccessAndCanBeRetried() {
        PersonalSaveLease lease = new PersonalSaveLease(owner);
        assertThrows(IllegalStateException.class, () -> lease.write(() -> {
            throw new IllegalStateException("disk full");
        }));
        assertTrue(lease.writable());
        ArcadeSaveStore store = new ArcadeSaveStore(directory);
        assertTrue(lease.write(() -> store.save(PlayerSaveSlots.key(owner, 1), rom, new byte[]{7})));
        assertArrayEquals(new byte[]{7}, store.load(PlayerSaveSlots.key(owner, 1), rom));
    }
}
