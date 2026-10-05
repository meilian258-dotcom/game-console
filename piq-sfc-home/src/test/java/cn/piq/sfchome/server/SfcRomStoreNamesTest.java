package cn.piq.sfchome.server;

import cn.piq.sfcarcade.core.SfcRomImage;
import java.nio.file.*;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SfcRomStoreNamesTest {
    @Test void scanKeepsValidEntriesAndExplainsRejectedFiles()throws Exception{
        Path root=Files.createDirectory(temp.resolve("scan"));Files.write(root.resolve("good.sfc"),rom(0));Files.write(root.resolve("bad.sfc"),new byte[]{1});Files.writeString(root.resolve("note.txt"),"not a ROM");
        var report=new SfcRomStore(root).scan();assertEquals(1,report.entries().size());assertEquals(2,report.failures().size());assertTrue(report.summary("服务器").contains("2 项被拒绝"));assertTrue(Files.exists(root.resolve("bad.sfc")));
    }
    @TempDir Path temp;
    private byte[] rom(int value){byte[] bytes=new byte[32768];Arrays.fill(bytes,(byte)value);return bytes;}
    @Test void originalChineseNameSurvivesReopenWithoutRenamingPhysicalContent() throws Exception {
        Path root=temp.resolve("roms");byte[] bytes=rom(0);String hash=SfcRomImage.fromBytes(bytes).sha256();
        new SfcRomStore(root).store("超级 游戏.smc",hash,bytes);
        var reopened=new SfcRomStore(root);var row=reopened.list().getFirst();
        assertEquals("超级 游戏.smc",row.fileName());assertEquals(hash,row.sha256());
        assertArrayEquals(bytes,reopened.read(hash));assertTrue(Files.exists(root.resolve(hash+".sfc")));
        try(var files=Files.list(root)){assertEquals(1,files.count());}
    }
    @Test void laterUploadDoesNotRenameFirstAcceptedLibraryName() throws Exception {
        Path root=temp.resolve("roms");byte[] bytes=rom(0);String hash=SfcRomImage.fromBytes(bytes).sha256();
        var store=new SfcRomStore(root);store.store("原名.sfc",hash,bytes);store.store("另一个卡名.sfc",hash,bytes);
        assertEquals("原名.sfc",new SfcRomStore(root).list().getFirst().fileName());
    }
    @Test void equalNamesDoNotMergeDifferentContent() throws Exception {
        var store=new SfcRomStore(temp.resolve("roms"));
        for(int n=0;n<2;n++){byte[] bytes=rom(n);store.store("游戏.sfc",SfcRomImage.fromBytes(bytes).sha256(),bytes);}
        var rows=store.list();assertEquals(2,rows.size());assertNotEquals(rows.get(0).sha256(),rows.get(1).sha256());
        assertTrue(rows.stream().allMatch(row->row.fileName().equals("游戏.sfc")));
    }
    @Test void oldHashHasExplicitFallbackAndCorruptNameNeverHidesRom() throws Exception {
        Path root=Files.createDirectory(temp.resolve("roms"));byte[] bytes=rom(0);String hash=SfcRomImage.fromBytes(bytes).sha256();
        Files.write(root.resolve(hash+".sfc"),bytes);var store=new SfcRomStore(root);
        assertTrue(store.list().getFirst().fileName().contains("名称缺失"));
        Path names=Files.createDirectory(temp.resolve("roms-names"));Files.write(names.resolve(hash+".name"),new byte[]{(byte)0xff});
        assertEquals(1,store.list().size());assertArrayEquals(bytes,store.read(hash));
    }
    @Test void invalidNameAndHashDoNotPublishContent() throws Exception {
        Path root=temp.resolve("roms");byte[] bytes=rom(0);String hash=SfcRomImage.fromBytes(bytes).sha256();
        var store=new SfcRomStore(root);
        assertThrows(IllegalArgumentException.class,()->store.store("bad\nname.sfc",hash,bytes));
        assertThrows(IllegalArgumentException.class,()->store.store("ok.sfc","../invalid",bytes));
        assertFalse(Files.exists(root));
    }
    @Test void failedMetadataWriteKeepsRomAndRetryFinishesName() throws Exception {
        Path root=temp.resolve("roms"), names=temp.resolve("roms-names");
        byte[] bytes=rom(0);String hash=SfcRomImage.fromBytes(bytes).sha256();
        Files.writeString(names,"obstruction");
        var store=new SfcRomStore(root);
        assertThrows(java.io.IOException.class,()->store.store("恢复名称.sfc",hash,bytes));
        assertArrayEquals(bytes,store.read(hash));
        assertEquals("obstruction",Files.readString(names));
        Files.delete(names);
        store.store("恢复名称.sfc",hash,bytes);
        assertEquals("恢复名称.sfc",new SfcRomStore(root).list().getFirst().fileName());
    }
    @Test void separateLibrariesDoNotShareDisplayNames() throws Exception {
        byte[] bytes=rom(0);String hash=SfcRomImage.fromBytes(bytes).sha256();
        var first=new SfcRomStore(temp.resolve("first/roms"));
        var second=new SfcRomStore(temp.resolve("second/roms"));
        first.store("第一库.sfc",hash,bytes);second.store("第二库.sfc",hash,bytes);
        assertEquals("第一库.sfc",first.list().getFirst().fileName());
        assertEquals("第二库.sfc",second.list().getFirst().fileName());
    }
}
