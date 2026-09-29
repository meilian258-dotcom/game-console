package cn.piq.fcarcade.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class PlayerSaveMigrationSafetyTest {
    @TempDir Path directory;
    private static final UUID PLAYER = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final String ROM = "a".repeat(64), OTHER = "b".repeat(64), LEGACY = "player|" + PLAYER;
    private String slot(int n) {return PlayerSaveSlots.key(PLAYER,n);}
    private ArcadeSaveStore fixture() {
        var store = new ArcadeSaveStore(directory);
        store.save(LEGACY,ROM,new byte[]{17},"old",1);
        store.save(slot(2),OTHER,new byte[]{2});store.save(slot(3),OTHER,new byte[]{3});return store;
    }
    @Test void activeFilelessTargetKeepsLegacySourceByteForByte() throws Exception {
        var store = fixture(); Path old = store.path(LEGACY,ROM);byte[] before=Files.readAllBytes(old);var time=Files.getLastModifiedTime(old);
        PlayerSaveSlots.migrateLegacy(store,PLAYER,n->"Slot "+n,i->false,k->k.equals(slot(1)));
        assertArrayEquals(before,Files.readAllBytes(old));assertEquals(time,Files.getLastModifiedTime(old));assertFalse(store.exists(slot(1),ROM));
        store.save(slot(1),ROM,new byte[]{99});assertArrayEquals(new byte[]{17},store.load(LEGACY,ROM));
    }
    @Test void allowedFreeTargetStillMigratesAndOnlyThenDeletesSource() {
        var store = fixture();PlayerSaveSlots.migrateLegacy(store,PLAYER,n->"Slot "+n,i->false,k->false);
        assertFalse(store.exists(LEGACY,ROM));assertArrayEquals(new byte[]{17},store.load(slot(1),ROM));
    }
    @Test void targetBecomingActiveDuringNameCallbackIsRechecked() {
        var store = fixture();store.save(LEGACY,ROM,new byte[]{17});var active=new AtomicBoolean();
        PlayerSaveSlots.migrateLegacy(store,PLAYER,n->{active.set(true);return "name";},i->false,k->active.get());
        assertTrue(store.exists(LEGACY,ROM));assertFalse(store.exists(slot(1),ROM));
    }
    @Test void activeSourceIsNeverMovedEvenWhenTargetIsFree() {
        var store = fixture();PlayerSaveSlots.migrateLegacy(store,PLAYER,n->"Slot",i->true,k->false);
        assertTrue(store.exists(LEGACY,ROM));assertFalse(store.exists(slot(1),ROM));
    }
    @Test void diskFileCreatedByCallbackAlsoPreventsCrossRomCollision() {
        var store = fixture();store.save(LEGACY,ROM,new byte[]{17});
        PlayerSaveSlots.migrateLegacy(store,PLAYER,n->{store.save(slot(1),OTHER,new byte[]{8});return "name";},i->false,k->false);
        assertTrue(store.exists(LEGACY,ROM));assertFalse(store.exists(slot(1),ROM));assertArrayEquals(new byte[]{8},store.load(slot(1),OTHER));
    }
    @Test void reservedFirstSlotDoesNotBlockAnotherFreeSlot() {
        var store = new ArcadeSaveStore(directory);store.save(LEGACY,ROM,new byte[]{17});
        PlayerSaveSlots.migrateLegacy(store,PLAYER,n->"Slot",i->false,k->k.equals(slot(1)));
        assertFalse(store.exists(slot(1),ROM));assertArrayEquals(new byte[]{17},store.load(slot(2),ROM));
    }
    @Test void exactFourLegacyScenarioCannotConsumeFourthSaveAfterRestart() throws Exception {
        var store=new ArcadeSaveStore(directory);String[] old={LEGACY,LEGACY+"|slot|1",LEGACY+"|slot|2",LEGACY+"|slot|3"};
        String[] roms={ROM,OTHER,"c".repeat(64),"d".repeat(64)};
        for(int i=0;i<4;i++){store.save(old[i],roms[i],new byte[]{(byte)(10+i)});Files.setLastModifiedTime(store.path(old[i],roms[i]),FileTime.fromMillis(1000L*(i+1)));}
        PlayerSaveSlots.migrateLegacy(store,PLAYER,n->"Slot",i->false,k->false);
        assertTrue(store.exists(LEGACY,ROM));store.delete(slot(1),roms[3]);
        PlayerSaveSlots.migrateLegacy(store,PLAYER,n->"Slot",i->i.saveKey().equals(slot(1)),k->k.equals(slot(1)));
        store.save(slot(1),ROM,new byte[]{99});assertArrayEquals(new byte[]{10},store.load(LEGACY,ROM));assertEquals(4,store.list().size());
    }
}
