package cn.piq.fcarcade.cabinet;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CabinetContentStorageTest {
    @TempDir Path temp;
    private CabinetGameManifest.Entry entry(String name,byte[] data){return new CabinetGameManifest.Entry(name,CabinetGameManifest.digest(data),data.length);}
    @Test void fiveFileUploadAndDownloadUseRealLedgersAndHashVerifiedStores()throws Exception {
        var entries=new ArrayList<CabinetGameManifest.Entry>();var bytes=new ArrayList<byte[]>();
        var names=new ArrayList<String>();names.add("samsho5.zip");names.addAll(CabinetGameManifest.BIOS.stream().sorted().toList());
        for(int i=0;i<5;i++){byte[] data=new byte[CabinetGameManifest.CHUNK+i+1];Arrays.fill(data,(byte)(i+1));bytes.add(data);entries.add(entry(names.get(i),data));}
        var manifest=new CabinetGameManifest("test:arcade",entries);var upload=new CabinetGameTransfer(entries.stream().mapToInt(CabinetGameManifest.Entry::size).toArray());
        var plan=CabinetGameUploadPlan.fromVerifiedMissing(manifest,31);var server=new CabinetGameStore(temp.resolve("server-objects"));
        upload.delivered(0);int sequence=1;
        for(int i=0;i<5;i++){
            Path part=server.temporary(UUID.randomUUID(),i);var e=entries.get(i);
            for(int offset=0;offset<e.size();){
                byte[] chunk=Arrays.copyOfRange(bytes.get(i),offset,Math.min(e.size(),offset+CabinetGameManifest.CHUNK));
                assertTrue(upload.reserve(sequence,false));plan.validate(i,offset,chunk.length);server.append(part,offset,chunk);
                if(offset+chunk.length==e.size())server.commit(part,e);
                plan.accepted(i,offset,chunk.length);upload.delivered(sequence++);offset+=chunk.length;
            }
        }
        assertTrue(upload.reserve(sequence,true));var authorized=new java.util.concurrent.atomic.AtomicBoolean();
        plan.finish(()->{for(var e:entries)try{assertTrue(server.contains(e));}catch(IOException ex){throw new java.io.UncheckedIOException(ex);}authorized.set(true);});
        upload.delivered(sequence);assertTrue(authorized.get());
        var client=new CabinetGameStore(temp.resolve("client-cache"));var download=new CabinetGameTransfer(entries.stream().mapToInt(CabinetGameManifest.Entry::size).toArray());
        download.delivered(0);sequence=1;
        for(int i=0;i<5;i++){
            var e=entries.get(i);Path part=client.temporary(UUID.randomUUID(),i);
            for(int offset=0;offset<e.size();){
                assertTrue(download.reserve(sequence,false));int count=download.download(i,offset);byte[] chunk=server.chunk(e,offset);assertEquals(count,chunk.length);
                client.append(part,offset,chunk);download.delivered(sequence++);offset+=count;
            }
            client.commit(part,e);assertEquals(-1,Files.mismatch(server.verifiedPath(e),client.verifiedPath(e)));
        }
        assertTrue(download.reserve(sequence,true));download.delivered(sequence);assertEquals(0,download.pending());
    }
    @Test void everyDeclaredCompanionCanUploadDownloadAndDeduplicate()throws Exception {
        var store=new CabinetGameStore(temp.resolve("objects"));var files=new ArrayList<CabinetGameManifest.Entry>();
        var names=new ArrayList<String>();names.add("samsho.zip");names.addAll(CabinetGameManifest.BIOS.stream().sorted().toList());
        for(int i=0;i<CabinetGameManifest.MAX_FILES;i++){
            byte[] data={(byte)i,22,44};var e=entry(names.get(i),data);files.add(e);
            Path p=store.temporary(UUID.randomUUID(),i);store.append(p,0,data);store.commit(p,e);
            assertTrue(store.contains(e));assertArrayEquals(data,store.chunk(e,0));assertFalse(Files.exists(p));
            Path repeated=store.temporary(UUID.randomUUID(),i);store.append(repeated,0,data);store.commit(repeated,e);assertFalse(Files.exists(repeated));
        }
        assertEquals(5,new CabinetGameManifest("test:arcade",files).files().size());
        try(var paths=Files.list(temp.resolve("objects"))){assertEquals(5,paths.count());}
        assertThrows(IOException.class,()->store.temporary(UUID.randomUUID(),-1));assertThrows(IOException.class,()->store.temporary(UUID.randomUUID(),5));
        assertThrows(IOException.class,()->store.append(temp.resolve("objects/upload-"+UUID.randomUUID()+"-00.part"),0,new byte[]{1}));
    }
    @Test void legacyContentCopiesOnlyRequestedHashesAndKeepsWorldOriginals()throws Exception {
        Path old=Files.createDirectories(temp.resolve("world/game-console/piq-cabinet/shared-games/objects"));
        Path root=temp.resolve("game-console/piq-cabinet/shared-games/objects");
        byte[] data={1,7,9};var e=entry("samsho.zip",data);Files.write(old.resolve(e.sha256()+".data"),data);
        Files.writeString(old.resolve("unreferenced.data"),"do not migrate this");
        var store=new CabinetGameStore(root,old);assertTrue(store.contains(e));assertEquals(root.resolve(e.sha256()+".data"),store.verifiedPath(e));
        assertArrayEquals(data,store.chunk(e,0));assertArrayEquals(data,Files.readAllBytes(old.resolve(e.sha256()+".data")));
        assertFalse(Files.exists(root.resolve("unreferenced.data")));assertTrue(Files.exists(old.resolve("unreferenced.data")));
        assertTrue(store.contains(e));try(var files=Files.list(root)){assertEquals(1,files.count());}
    }
    @Test void missingLegacyDoesNotCreateWorldDirectoriesOrExposeOtherWorlds()throws Exception {
        Path root=temp.resolve("game-console/objects"),world=temp.resolve("world1/objects");
        byte[] data={9,8};var e=entry("neogeo.zip",data);
        Path other=Files.createDirectories(temp.resolve("world2/objects"));Files.write(other.resolve(e.sha256()+".data"),data);
        assertFalse(new CabinetGameStore(root,world).contains(e));assertFalse(Files.exists(world));
        assertFalse(Files.exists(root.resolve(e.sha256()+".data")));
    }
    @Test void corruptNewOrOldObjectNeverOverwritesGoodBytes()throws Exception {
        Path root=Files.createDirectory(temp.resolve("root")),old=Files.createDirectory(temp.resolve("old"));
        byte[] data={8,2};var e=entry("neogeo.zip",data);Path source=old.resolve(e.sha256()+".data"),target=root.resolve(e.sha256()+".data");
        Files.write(source,data);Files.write(target,new byte[]{1,1});var store=new CabinetGameStore(root,old);
        assertThrows(IOException.class,()->store.contains(e));assertArrayEquals(new byte[]{1,1},Files.readAllBytes(target));assertArrayEquals(data,Files.readAllBytes(source));
        var corrupt=entry("pgm.zip",new byte[]{7,7});Files.write(old.resolve(corrupt.sha256()+".data"),new byte[]{3,3});
        assertThrows(IOException.class,()->store.contains(corrupt));assertFalse(Files.exists(root.resolve(corrupt.sha256()+".data")));
    }
    @Test void differentVersionsOfOneBiosRemainIndependentAndSearchable()throws Exception {
        Path shared=temp.resolve("shared");var game=entry("侍魂-samsho.zip",new byte[]{1,2});
        var first=new CabinetGameManifest("piq_native_arcade:native",List.of(game,entry("neogeo.zip",new byte[]{3,4})));
        var second=new CabinetGameManifest(first.backend(),List.of(game,entry("neogeo.zip",new byte[]{4,5})));
        Path a=CabinetContentIndex.write(shared,first),b=CabinetContentIndex.write(shared,second);assertNotEquals(a,b);
        assertTrue(a.getFileName().toString().contains("侍魂-samsho.zip"));assertEquals(a,CabinetContentIndex.write(shared,first));
        var json=JsonParser.parseString(Files.readString(a)).getAsJsonObject();assertEquals(first.contentId(),json.get("contentId").getAsString());
        var files=json.getAsJsonArray("files");assertEquals("ROM",files.get(0).getAsJsonObject().get("role").getAsString());
        assertEquals("BIOS",files.get(1).getAsJsonObject().get("role").getAsString());
        assertEquals("neogeo.zip",files.get(1).getAsJsonObject().get("name").getAsString());
        assertEquals("../objects/"+first.files().get(1).sha256()+".data",files.get(1).getAsJsonObject().get("object").getAsString());
        assertFalse(Files.exists(shared.resolve("objects")),"Index itself neither grants nor invents ROM objects");
    }
    @Test void indexConflictsAndUnsafeDirectoriesAreNotOverwritten()throws Exception {
        var manifest=new CabinetGameManifest("test:../arcade",List.of(entry("game.zip",new byte[]{1})));
        Path shared=temp.resolve("shared"),receipt=CabinetContentIndex.write(shared,manifest);
        assertEquals(shared.resolve("indexes"),receipt.getParent());Files.writeString(receipt,"manual notes");
        assertThrows(IOException.class,()->CabinetContentIndex.write(shared,manifest));assertEquals("manual notes",Files.readString(receipt));
        Path bad=Files.createDirectory(temp.resolve("bad"));Files.writeString(bad.resolve("indexes"),"keep");
        assertThrows(IOException.class,()->CabinetContentIndex.write(bad,manifest));assertEquals("keep",Files.readString(bad.resolve("indexes")));
    }
    @Test void unicodeIndexNameFitsOrdinaryFilesystemLimitWithoutSplittingCodepoints()throws Exception {
        String name="\uD83C\uDFAE".repeat(48)+".zip";
        var manifest=new CabinetGameManifest("test:arcade",List.of(entry(name,new byte[]{1})));
        Path receipt=CabinetContentIndex.write(temp,manifest);
        assertTrue(receipt.getFileName().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=255);
        assertEquals(name,JsonParser.parseString(Files.readString(receipt)).getAsJsonObject().get("game").getAsString());
    }
    @Test void concurrentStoresCannotExceedObjectCountDuringUploadAndLegacyCopy()throws Exception {
        Path root=Files.createDirectory(temp.resolve("objects")),old=Files.createDirectory(temp.resolve("legacy"));
        for(int i=0;i<CabinetGameStore.MAX_OBJECTS-1;i++)Files.write(root.resolve("fixture-"+i+".data"),new byte[0]);
        var legacyEntry=entry("old.zip",new byte[]{3,4});Files.write(old.resolve(legacyEntry.sha256()+".data"),new byte[]{3,4});
        var pool=java.util.concurrent.Executors.newFixedThreadPool(8);var start=new java.util.concurrent.CountDownLatch(1);
        try{
            var jobs=new ArrayList<java.util.concurrent.Future<Boolean>>();
            for(int i=0;i<8;i++){final int n=i;jobs.add(pool.submit(()->{
                start.await();var store=new CabinetGameStore(root,old);Path p=null;
                try{if(n==0)return store.contains(legacyEntry);p=store.temporary(UUID.randomUUID(),0);byte[] bytes={(byte)n};store.append(p,0,bytes);store.commit(p,entry("game.zip",bytes));return true;}
                catch(IOException expectedQuota){return false;}finally{store.discard(p);}
            }));}
            start.countDown();int succeeded=0;for(var job:jobs)if(job.get(10,java.util.concurrent.TimeUnit.SECONDS))succeeded++;
            assertEquals(1,succeeded);try(var paths=Files.list(root)){assertEquals(CabinetGameStore.MAX_OBJECTS,paths.count());}
            assertArrayEquals(new byte[]{3,4},Files.readAllBytes(old.resolve(legacyEntry.sha256()+".data")));
        }finally{pool.shutdownNow();}
    }
}
