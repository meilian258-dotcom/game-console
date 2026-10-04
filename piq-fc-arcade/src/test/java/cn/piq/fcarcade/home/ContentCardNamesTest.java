package cn.piq.fcarcade.home;

import cn.piq.fcarcade.home.content.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ContentCardNamesTest {
    @TempDir Path root;
    private Path roms(){return root.resolve("roms");}
    private Path metadata(){return root.resolve("metadata");}
    private ContentCardStore store(){return new ContentCardStore(roms(),Set.of("md","bin"),b->{if(b.length!=1024)throw new IOException("not MD");},8*1024*1024,metadata());}
    private byte[] rom(int id){var data=new byte[1024];data[512]=(byte)id;return data;}
    private Path nameFile(String hash){return metadata().resolve(hash+".name");}

    @Test void uploadedOriginalNameSurvivesFreshStoreSearchAndRead()throws Exception{
        var bytes=rom(1);var entry=store().store("中文游戏 Zero Wing.MD",ContentCardStore.hash(bytes),bytes);
        assertEquals(entry.hash()+".md",entry.name());assertEquals("中文游戏 Zero Wing.MD",entry.displayName());
        var reopened=store().scan();assertEquals(1,reopened.entries().size());assertTrue(reopened.failures().isEmpty());assertTrue(reopened.warnings().isEmpty());
        var listed=reopened.entries().getFirst();assertEquals(entry.displayName(),listed.displayName());
        assertEquals(1,ContentCardWorkbench.page(reopened.entries(),"中文游戏",0).total());
        assertEquals(1,ContentCardWorkbench.page(reopened.entries(),"zero wing",0).total());
        assertArrayEquals(bytes,store().read(listed));
        assertEquals("中文游戏 Zero Wing.MD",Files.readString(nameFile(entry.hash()),StandardCharsets.UTF_8));
        try(var paths=Files.list(roms())){assertEquals(1,paths.count());}
    }
    @Test void repeatedUploadCannotRenameTheSharedLibrary()throws Exception{
        var bytes=rom(1);String hash=ContentCardStore.hash(bytes);
        var first=store().store("First name.md",hash,bytes);
        var second=store().store("另一个人的卡名.md",hash,bytes);
        assertEquals(first,second);assertEquals("First name.md",second.displayName());
        assertEquals("First name.md",store().list().getFirst().displayName());
        assertArrayEquals(bytes,store().read(first));
    }
    @Test void displayMetadataDoesNotChangeOldCardIdentityOrFileLookup()throws Exception{
        var bytes=rom(2);var fresh=store().store("上传时的名字.md",ContentCardStore.hash(bytes),bytes);
        var oldNbtEntry=new ContentCardStore.Entry(fresh.hash(),fresh.name(),fresh.size());
        assertNotEquals(fresh.displayName(),oldNbtEntry.displayName());assertEquals(fresh,oldNbtEntry);assertEquals(fresh.hashCode(),oldNbtEntry.hashCode());
        assertArrayEquals(bytes,store().read(oldNbtEntry));
        var newLabel=new ContentCardStore.Entry(fresh.hash(),fresh.name(),fresh.size(),"另一显示名");
        assertEquals(fresh,newLabel);assertArrayEquals(bytes,store().read(newLabel));
        assertNotEquals(fresh,new ContentCardStore.Entry(fresh.hash(),"different.md",fresh.size(),fresh.displayName()));
    }
    @Test void legacyHashFilesRemainReadableWithoutInventingAName()throws Exception{
        Files.createDirectories(roms());var bytes=rom(3);String hash=ContentCardStore.hash(bytes);
        Path old=roms().resolve(hash+".md");Files.write(old,bytes);
        var entry=store().list().getFirst();assertEquals(hash+".md",entry.name());
        assertTrue(entry.displayName().contains("名称缺失"));assertTrue(entry.displayName().contains(hash.substring(0,12)));
        assertArrayEquals(bytes,store().read(entry));assertFalse(Files.exists(metadata()));
        // Explicitly uploading a real name repairs missing metadata, not the old file or card identity.
        var upload=store().store("原来游戏.md",hash,bytes);assertEquals(entry,upload);assertEquals("原来游戏.md",upload.displayName());
        assertArrayEquals(bytes,Files.readAllBytes(old));
    }
    @Test void corruptNameWarnsButNeverRejectsOrOverwritesTheRom()throws Exception{
        var bytes=rom(4);var entry=store().store("原名.md",ContentCardStore.hash(bytes),bytes);
        byte[] corrupt={(byte)0xff};Files.write(nameFile(entry.hash()),corrupt);
        var scan=store().scan();assertEquals(1,scan.entries().size());assertTrue(scan.failures().isEmpty());assertEquals(1,scan.warnings().size());
        assertTrue(scan.summary("服务器").contains("游戏仍可用"));assertTrue(scan.entries().getFirst().displayName().contains("名称缺失"));
        assertThrows(IOException.class,()->store().store("修复不能静默覆盖.md",entry.hash(),bytes));
        assertArrayEquals(corrupt,Files.readAllBytes(nameFile(entry.hash())));assertArrayEquals(bytes,store().read(entry));
    }
    @Test void oversizedNameMetadataStaysNonDestructive()throws Exception{
        var bytes=rom(4);var entry=store().store("原名.md",ContentCardStore.hash(bytes),bytes);
        byte[] oversized=new byte[513];Files.write(nameFile(entry.hash()),oversized);
        assertEquals(1,store().scan().warnings().size());assertThrows(IOException.class,()->store().store("new.md",entry.hash(),bytes));
        assertArrayEquals(oversized,Files.readAllBytes(nameFile(entry.hash())));assertArrayEquals(bytes,store().read(entry));
    }
    @Test void directoryInPlaceOfNameMetadataStaysNonDestructive()throws Exception{
        Files.createDirectories(roms());Files.createDirectories(metadata());var bytes=rom(11);String hash=ContentCardStore.hash(bytes);
        Files.write(roms().resolve(hash+".md"),bytes);Files.createDirectory(nameFile(hash));
        var scan=store().scan();assertEquals(1,scan.entries().size());assertTrue(scan.failures().isEmpty());assertEquals(1,scan.warnings().size());
        assertThrows(IOException.class,()->store().store("new.md",hash,bytes));
        assertTrue(Files.isDirectory(nameFile(hash)));assertArrayEquals(bytes,store().read(scan.entries().getFirst()));
    }
    @Test void nameFailureDoesNotClaimSuccessOrRequireDeletingAnExistingContent()throws Exception{
        Files.write(metadata(),new byte[]{1});var bytes=rom(5);String hash=ContentCardStore.hash(bytes);
        assertThrows(IOException.class,()->store().store("文件.md",hash,bytes));
        assertArrayEquals(bytes,Files.readAllBytes(roms().resolve(hash+".md")));assertArrayEquals(new byte[]{1},Files.readAllBytes(metadata()));
    }
    @Test void metadataWriteLockCannotBeBypassed()throws Exception{
        Files.createDirectories(metadata());var bytes=rom(6);String hash=ContentCardStore.hash(bytes);
        try(var channel=FileChannel.open(metadata().resolve("catalog.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=channel.lock()){
            assertThrows(IOException.class,()->store().store("locked.md",hash,bytes));assertFalse(Files.exists(nameFile(hash)));
        }
        assertEquals("unlocked.md",store().store("unlocked.md",hash,bytes).displayName());
    }
    @Test void concurrentStoreInstancesKeepOneDurableNameAndExactBytes()throws Exception{
        var bytes=rom(7);String hash=ContentCardStore.hash(bytes);var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(4)){
            var jobs=new ArrayList<Future<ContentCardStore.Entry>>();
            for(int i=0;i<4;i++){int n=i;jobs.add(executor.submit(()->{start.await();return store().store("同时上传"+n+".md",hash,bytes);}));}
            start.countDown();String winner=jobs.getFirst().get(10,TimeUnit.SECONDS).displayName();
            for(var job:jobs)assertEquals(winner,job.get(10,TimeUnit.SECONDS).displayName());
            assertEquals(winner,store().list().getFirst().displayName());assertArrayEquals(bytes,store().read(jobs.getFirst().get()));
        }
        try(var files=Files.list(metadata())){assertEquals(2,files.count());}
    }
    @Test void metadataCapacityAndNamespaceAreBounded()throws Exception{
        Files.createDirectories(metadata());
        for(int i=0;i<ContentCardStore.MAX_FILES;i++)Files.writeString(metadata().resolve(String.format("%064x.name",i)),"old.md");
        var bytes=rom(8);assertThrows(IOException.class,()->store().store("new.md",ContentCardStore.hash(bytes),bytes));
        assertThrows(IllegalArgumentException.class,()->new ContentCardStore(roms(),Set.of("md"),b->{},1024,roms().resolve("metadata")));
        for(String bad:List.of(""," ","a\nb","a".repeat(129),"\ud800"))
            assertThrows(IllegalArgumentException.class,()->new ContentCardStore.Entry("a".repeat(64),"a.md",1024,bad));
    }
    @Test void knownNameRemainsAvailableAtMetadataCapacity()throws Exception{
        var bytes=rom(9);var first=store().store("known.md",ContentCardStore.hash(bytes),bytes);
        for(int i=0;i<ContentCardStore.MAX_FILES-1;i++)Files.writeString(metadata().resolve(String.format("%064x.name",i)),"old.md");
        assertEquals("known.md",store().store("rename.md",first.hash(),bytes).displayName());
    }
    @Test void localAndCacheConstructorsDoNotCreateMetadata()throws Exception{
        var cache=new ContentCardStore(roms(),Set.of("md"),b->{});var bytes=rom(10);
        var entry=cache.store("cache.md",ContentCardStore.hash(bytes),bytes);
        assertArrayEquals(bytes,cache.read(entry));assertFalse(Files.exists(metadata()));
        try(var files=Files.list(root)){assertEquals(1,files.count());}
    }
}
