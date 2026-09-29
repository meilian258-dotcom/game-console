package cn.piq.fcarcade.server;

import cn.piq.fcarcade.netplay.*;
import cn.piq.fcarcade.home.CartridgeSaveIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FcNetplaySaveIntegrationTest {
    @TempDir Path root;
    final String rom="a".repeat(64);
    byte[] state(boolean gun,long frame){return NetplaySaveState.encode(new NetplaySaveState.Parts(FcNetplaySaves.identity(gun,rom),frame,new byte[]{1,2,3},new byte[]{90},new byte[0]));}
    @Test void playerAndCartridgeOwnershipSurviveTheRealExistingEnvelope()throws Exception{
        var store=new ArcadeSaveStore(root);UUID player=UUID.randomUUID(),other=UUID.randomUUID(),card=UUID.randomUUID();
        for(boolean gun:new boolean[]{false,true}){
            String personal=FcNetplaySaves.key(gun,PlayerSaveSlots.key(player,1));
            String cart=FcNetplaySaves.key(gun,CartridgeSaveIdentity.key(card));
            store.save(personal,rom,state(gun,1),"个人进度",2);store.save(cart,rom,state(gun,2),"卡带进度",1);
            assertArrayEquals(state(gun,1),store.loadReadOnly(personal,rom));assertEquals(PlayerSaveSlots.key(player,1),PlayerSaveCatalogKey.normalize(personal));
            assertTrue(CartridgeSaveIdentity.owns(cart,card));assertFalse(CartridgeSaveIdentity.owns(personal,card));
            var manager=new FcSaveManagementStore(root);var row=manager.current(store.path(personal,rom).getFileName().toString().replace(".sav",""));
            assertTrue(FcSaveManagementStore.allowed(row,player,false));assertFalse(FcSaveManagementStore.allowed(row,other,false));
            manager.rename(row.id(),row.version(),"改名");assertArrayEquals(state(gun,1),store.loadReadOnly(personal,rom));
        }
        assertEquals(4,store.list().size());
    }
    @Test void legacyIsUnchangedAndBackupRetainsPreviousGoodNetplayFile()throws Exception{
        var store=new ArcadeSaveStore(root);String legacy=PlayerSaveSlots.key(UUID.randomUUID(),1),key=FcNetplaySaves.key(false,legacy);
        store.save(legacy,rom,new byte[]{9,8,7});byte[] old=Files.readAllBytes(store.path(legacy,rom));
        store.save(key,rom,state(false,1));byte[] one=Files.readAllBytes(store.path(key,rom));store.save(key,rom,state(false,2));
        assertArrayEquals(one,Files.readAllBytes(store.path(key,rom).resolveSibling(store.path(key,rom).getFileName()+".previous")));
        assertArrayEquals(old,Files.readAllBytes(store.path(legacy,rom)));
        byte[] current=Files.readAllBytes(store.path(key,rom)),bad=state(false,3);bad[bad.length-1]^=1;
        assertThrows(IllegalArgumentException.class,()->store.save(key,rom,bad));assertArrayEquals(current,Files.readAllBytes(store.path(key,rom)));
        Files.write(store.path(key,rom),new byte[]{4});assertThrows(IllegalStateException.class,()->store.save(key,rom,state(false,4)));
        assertArrayEquals(new byte[]{4},Files.readAllBytes(store.path(key,rom)));assertArrayEquals(old,Files.readAllBytes(store.path(legacy,rom)));
    }
}
