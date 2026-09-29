package cn.piq.fcarcade.server;

import cn.piq.fcarcade.session.NesCoreVariant;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class NamcoSaveIsolation43Test {
    @TempDir Path root;
    private static final UUID OWNER=new UUID(12,34);
    @Test void savingAndDeletingNewCoreSlotCannotMutateLegacyOrGunFiles()throws Exception{
        var store=new ArcadeSaveStore(root);String key=PlayerSaveSlots.key(OWNER,1),rom="a".repeat(64);
        String legacy=NesCoreVariant.LEGACY.saveKey(key),gun=NesCoreVariant.ZAPPER_V1.saveKey(key),namco=NesCoreVariant.MAPPER19_V1.saveKey(key);
        store.save(legacy,rom,new byte[]{1},"Old",1);store.save(gun,rom,new byte[]{2},"Gun",1);
        byte[] old=Files.readAllBytes(store.path(legacy,rom)),oldGun=Files.readAllBytes(store.path(gun,rom));
        var oldTime=Files.getLastModifiedTime(store.path(legacy,rom));var gunTime=Files.getLastModifiedTime(store.path(gun,rom));
        store.save(namco,rom,new byte[]{3},"Namco",2);assertArrayEquals(new byte[]{3},store.load(namco,rom));store.delete(namco,rom);
        assertFalse(store.exists(namco,rom));assertArrayEquals(old,Files.readAllBytes(store.path(legacy,rom)));assertArrayEquals(oldGun,Files.readAllBytes(store.path(gun,rom)));
        assertEquals(oldTime,Files.getLastModifiedTime(store.path(legacy,rom)));assertEquals(gunTime,Files.getLastModifiedTime(store.path(gun,rom)));
    }
    @Test void legacyMigrationCannotConsumeNewMapperNamespace(){
        var store=new ArcadeSaveStore(root);String namco=NesCoreVariant.MAPPER19_V1.saveKey("player|"+OWNER),rom="b".repeat(64);
        store.save(namco,rom,new byte[]{3});PlayerSaveSlots.migrateLegacy(store,OWNER,i->"Slot "+i,s->false,s->false);
        assertTrue(store.exists(namco,rom));for(int slot=1;slot<=3;slot++)assertFalse(store.exists(PlayerSaveSlots.key(OWNER,slot),rom));
    }
}
