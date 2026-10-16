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
    byte[] state(boolean gun,boolean jni,long frame){return NetplaySaveState.encode(new NetplaySaveState.Parts(FcNetplaySaves.identity(gun,jni,rom),frame,new byte[]{1,2,3},new byte[]{90},new byte[0]));}
    private record Domain(boolean gun,boolean jni,String variant){
        String key(String owner){return FcNetplaySaves.key(gun,jni,variant,owner);}
        byte[] state(String rom,long frame){return NetplaySaveState.encode(new NetplaySaveState.Parts(FcNetplaySaves.identity(gun,jni,variant,rom),frame,new byte[]{1,2,3},new byte[]{90},new byte[0]));}
    }
    private static final List<Domain> DOMAINS=List.of(new Domain(false,false,""),new Domain(true,false,""),
            new Domain(false,true,""),new Domain(true,true,""),new Domain(false,false,FcNetplayBackend.OFFICIAL_PAD));
    // Complete PFQS v3 files produced by the frozen FC76.57 ArcadeSaveStore,
    // including its PNS1 payload. Kept independent of today's encoder.
    private static final String[] FROZEN_JNI_FILES={
        "UEZRUwAAAAMAUWNvcmV8bmVzLWpuaS1uZXRwbGF5LXYyfHBsYXllcnwxMjM0NTY3OC0xMjM0LTU2NzgtOTBhYi0xMjM0NTY3ODlhYmN8Z2xvYmFsLXNsb3R8MQBAYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYQAIZnJvemVuNTcCAAAAgGSGc24Zif16Bv1s8e6L4QQuP76PJqUsuecksQHLSJ8dUE5TMQAAAAFXKf8S7ylJSivr7UiDfwsnk0ZHhPYTf+4XnJA0trJEbETCM2/tq4/2qFx0wrlBZTd7CYH1Jq25SHiVymMUFl6GAAAAAAAAAAcAAAADAAAAAQAAAAABAgNalgd0VZE2ZP+dozgJXxA8c8xXuccoFCEZYrhp/OFxpTk=",
        "UEZRUwAAAAMAWGNvcmV8bmVzLWpuaS1uZXRwbGF5LXphcHBlci12MXxwbGF5ZXJ8MTIzNDU2NzgtMTIzNC01Njc4LTkwYWItMTIzNDU2Nzg5YWJjfGdsb2JhbC1zbG90fDEAQGFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWEACGZyb3plbjU3AgAAAICV3rry9oEJMnGlDaaGQ69XSVCImQnwVeEfcWLA/WiYN1BOUzEAAAABprdaeZO74jGi1/WB7RiiBKxfQ3Ss66ZFSDsu+WiM4AhEwjNv7auP9qhcdMK5QWU3ewmB9SatuUh4lcpjFBZehgAAAAAAAAAHAAAAAwAAAAEAAAAAAQIDWlMA+dsuYrCuLXCk0xwR/vC5PnJVAU6lDz5eeKg/3Fz7"
    };

    @Test void filesWrittenByFrozen57JniPadAndGunRemainReadableAndWritable()throws Exception{
        var store=new ArcadeSaveStore(root);var owner=UUID.fromString("12345678-1234-5678-90ab-123456789abc");
        for(int index=0;index<2;index++){
            boolean gun=index==1;String key=FcNetplaySaves.key(gun,true,PlayerSaveSlots.key(owner,1));
            byte[] frozen=Base64.getDecoder().decode(FROZEN_JNI_FILES[index]);Path file=store.path(key,rom);Files.write(file,frozen);
            byte[] payload=store.loadReadOnly(key,rom);assertArrayEquals(state(gun,true,7),payload);
            assertTrue(FcNetplaySaves.accepts(gun,true,rom,payload));
            var row=store.list().stream().filter(save->save.saveKey().equals(key)).findFirst().orElseThrow();
            assertEquals("frozen57",row.slotName());assertEquals(2,row.players());
            store.save(key,rom,state(gun,true,8),row.slotName(),row.players());
            assertArrayEquals(state(gun,true,8),store.loadReadOnly(key,rom));
            assertArrayEquals(frozen,Files.readAllBytes(file.resolveSibling(file.getFileName()+".previous")));
        }
    }
    @Test void playerAndCartridgeOwnershipSurviveTheRealExistingEnvelope()throws Exception{
        var store=new ArcadeSaveStore(root);UUID player=UUID.randomUUID(),other=UUID.randomUUID(),card=UUID.randomUUID();
        for(var domain:DOMAINS){
            String personal=domain.key(PlayerSaveSlots.key(player,1));
            String cart=domain.key(CartridgeSaveIdentity.key(card));
            store.save(personal,rom,domain.state(rom,1),"个人进度",2);store.save(cart,rom,domain.state(rom,2),"卡带进度",1);
            assertArrayEquals(domain.state(rom,1),store.loadReadOnly(personal,rom));assertEquals(PlayerSaveSlots.key(player,1),PlayerSaveCatalogKey.normalize(personal));
            assertTrue(CartridgeSaveIdentity.owns(cart,card));assertFalse(CartridgeSaveIdentity.owns(personal,card));
            assertFalse(CartridgeSaveIdentity.owns(cart,UUID.randomUUID()));
            var manager=new FcSaveManagementStore(root);var row=manager.current(store.path(personal,rom).getFileName().toString().replace(".sav",""));
            assertTrue(FcSaveManagementStore.allowed(row,player,false));assertFalse(FcSaveManagementStore.allowed(row,other,false));
            var cardRow=manager.current(store.path(cart,rom).getFileName().toString().replace(".sav",""));
            assertTrue(FcSaveManagementStore.allowed(cardRow,other,false,card));
            assertFalse(FcSaveManagementStore.allowed(cardRow,player,false,UUID.randomUUID()));
            manager.rename(row.id(),row.version(),"改名");assertArrayEquals(domain.state(rom,1),store.loadReadOnly(personal,rom));
        }
        assertEquals(DOMAINS.size()*2,store.list().size());
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

    @Test void everyJniAndRetroarchDomainPreservesGoodFileAndPreviousOnBadCheckpoint()throws Exception{
        var store=new ArcadeSaveStore(root);UUID owner=UUID.randomUUID();
        for(var domain:DOMAINS){
            String key=domain.key(PlayerSaveSlots.key(owner,1));Path file=store.path(key,rom),previous=file.resolveSibling(file.getFileName()+".previous");
            store.save(key,rom,domain.state(rom,1));byte[] first=Files.readAllBytes(file);
            store.save(key,rom,domain.state(rom,2));byte[] second=Files.readAllBytes(file);
            assertArrayEquals(first,Files.readAllBytes(previous));
            byte[] bad=domain.state(rom,3);bad[bad.length-1]^=1;
            assertThrows(IllegalArgumentException.class,()->store.save(key,rom,bad));
            assertArrayEquals(second,Files.readAllBytes(file));assertArrayEquals(first,Files.readAllBytes(previous));
            Files.write(file,new byte[]{4});
            assertThrows(IllegalStateException.class,()->store.save(key,rom,domain.state(rom,4)));
            assertArrayEquals(new byte[]{4},Files.readAllBytes(file));assertArrayEquals(first,Files.readAllBytes(previous));
        }
        try(var paths=Files.list(root)){assertFalse(paths.anyMatch(path->path.getFileName().toString().endsWith(".tmp")));}
    }

    @Test void nonRegularTargetsAreNotReplacedByAnyKnownNetplayDomain()throws Exception{
        var store=new ArcadeSaveStore(root);
        for(var domain:DOMAINS){
            String key=domain.key("owner");Path destination=store.path(key,rom);Files.createDirectory(destination);
            Path marker=destination.resolve("keep");Files.write(marker,new byte[]{17});
            assertThrows(IllegalStateException.class,()->store.save(key,rom,domain.state(rom,1)));
            assertTrue(Files.isDirectory(destination));assertArrayEquals(new byte[]{17},Files.readAllBytes(marker));
        }
    }

    @Test void redirectedSaveDirectoriesAndFilesAreRejectedWithoutChangingTheirTargets()throws Exception{
        Path target=Files.createDirectory(root.resolve("target")),redirect=root.resolve("redirect");
        try{Files.createSymbolicLink(redirect,target);}
        catch(java.io.IOException|UnsupportedOperationException|SecurityException unsupported){
            org.junit.jupiter.api.Assumptions.assumeTrue(false,"Symbolic links unavailable: "+unsupported.getClass().getSimpleName());
        }
        for(var domain:DOMAINS){
            String key=domain.key("owner");
            assertThrows(IllegalStateException.class,()->new ArcadeSaveStore(redirect).save(key,rom,domain.state(rom,1)));
            var safe=new ArcadeSaveStore(target);safe.save(key,rom,domain.state(rom,1));
            Path real=safe.path(key,rom);byte[] original=Files.readAllBytes(real);
            var linked=new ArcadeSaveStore(root);Path file=linked.path(key,rom);Files.createSymbolicLink(file,real);
            assertThrows(IllegalStateException.class,()->linked.save(key,rom,domain.state(rom,2)));
            assertTrue(Files.isSymbolicLink(file));assertArrayEquals(original,Files.readAllBytes(real));
        }
    }
}
