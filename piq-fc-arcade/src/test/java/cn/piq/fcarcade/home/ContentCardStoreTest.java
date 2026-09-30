package cn.piq.fcarcade.home;

import cn.piq.fcarcade.home.content.ContentCardStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.IOException;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class ContentCardStoreTest {
    @TempDir Path root;
    private ContentCardStore store(){return new ContentCardStore(root,Set.of("md","bin","gen"),b->{if(b.length<512||b[256]!='S')throw new IOException("not MD");});}
    private byte[] rom(int marker){byte[] b=new byte[1024];b[256]='S';b[700]=(byte)marker;return b;}
    @Test void immutableUploadAndReopen()throws Exception{
        var s=store();var bytes=rom(1);var entry=s.store("中文名称.MD",ContentCardStore.hash(bytes),bytes);
        assertArrayEquals(bytes,s.read(entry));assertEquals(entry,s.store("another.md",entry.hash(),bytes));
        assertEquals(1,s.list().size());assertEquals(entry,s.list().getFirst());
    }
    @Test void badHashNeverReplacesGoodRom()throws Exception{
        var s=store();var good=rom(1);var entry=s.store("good.md",ContentCardStore.hash(good),good);
        assertThrows(IOException.class,()->s.store("bad.md",entry.hash(),rom(2)));
        assertArrayEquals(good,s.read(entry));
    }
    @Test void invalidFormatNeverCommits()throws Exception{
        byte[] invalid=new byte[1024];assertThrows(IOException.class,()->store().store("bad.md",ContentCardStore.hash(invalid),invalid));
        assertEquals(0,store().list().size());
    }
    @Test void staleCatalogContentRejected()throws Exception{
        var s=store();var b=rom(1);var entry=s.store("good.md",ContentCardStore.hash(b),b);Files.write(root.resolve(entry.name()),rom(2));
        assertThrows(IOException.class,()->s.read(entry));
    }
    @Test void traversalAndOversizeRejected(){
        String hash=ContentCardStore.hash(rom(1));
        for(String name:new String[]{"../a.md","x\\a.md","bad\n.md",""})assertThrows(IllegalArgumentException.class,()->new ContentCardStore.Entry(hash,name,512));
        assertThrows(IllegalArgumentException.class,()->new ContentCardStore.Entry(hash,"a.md",ContentCardStore.MAX_BYTES+1));
    }
    @Test void manualServerFilesHaveRealNames()throws Exception{
        Files.write(root.resolve("Test Game.gen"),rom(3));var entry=store().list().getFirst();
        assertEquals("Test Game.gen",entry.name());assertArrayEquals(rom(3),store().read(entry));
    }
    @Test void oversizeDirectoryFailsRatherThanPretendingEmpty()throws Exception{
        for(int i=0;i<=ContentCardStore.MAX_FILES;i++)Files.write(root.resolve(i+".txt"),new byte[]{1});
        assertThrows(IOException.class,()->store().list());
    }
    @Test void nonRomAndDirectoryCannotBeExecuted()throws Exception{
        Files.createDirectory(root.resolve("fake.md"));assertThrows(IOException.class,()->store().list());
        assertThrows(IOException.class,()->store().readPath(root.resolve("other.exe")));
    }
    @Test void legacyStoreKeepsEightMiBLimit()throws Exception{
        byte[] large=new byte[ContentCardStore.DEFAULT_MAX_BYTES+1];large[256]='S';
        var file=root.resolve("large.md");Files.write(file,large);
        assertThrows(IOException.class,()->store().readPath(file));
        assertThrows(IOException.class,()->store().store("large.md",ContentCardStore.hash(large),large));
        var adapter=new cn.piq.fcarcade.home.content.ContentCards.Adapter(()->null,"MD",Set.of("md"),b->{});
        assertEquals(8*1024*1024,adapter.maxBytes());
    }
    @Test void explicitlyRegistered32MiBStoreRoundTrips()throws Exception{
        var gba=new ContentCardStore(root,Set.of("gba"),b->{if(b[0xb2]!=(byte)0x96)throw new IOException("GBA header");},32*1024*1024);
        byte[] bytes=new byte[32*1024*1024];bytes[0xb2]=(byte)0x96;bytes[bytes.length-1]=42;
        var entry=gba.store("full.gba",ContentCardStore.hash(bytes),bytes);
        assertEquals(bytes.length,entry.size());assertArrayEquals(bytes,gba.read(entry));
        assertThrows(IllegalArgumentException.class,()->new ContentCardStore(root,Set.of("gba"),b->{},ContentCardStore.MAX_BYTES+1));
        assertThrows(IllegalArgumentException.class,()->new ContentCardStore(root,Set.of("gba"),b->{},0));
        assertThrows(IllegalArgumentException.class,()->new cn.piq.fcarcade.home.content.ContentCards.Adapter(()->null,"bad",Set.of("gba"),b->{},ContentCardStore.MAX_BYTES+1));
    }
}
