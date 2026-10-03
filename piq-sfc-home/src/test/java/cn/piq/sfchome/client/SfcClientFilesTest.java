package cn.piq.sfchome.client;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SfcClientFilesTest {
    @TempDir Path temp;
    private byte[] rom(){byte[] data=new byte[32768];for(int i=0;i<data.length;i++)data[i]=(byte)(i*13);return data;}
    @Test void storesOnlyByValidatedContentHash()throws Exception{
        byte[] data=rom();String sha=SfcClientFiles.hash(data);
        SfcClientFiles.cacheRom(temp,sha,data);assertArrayEquals(data,SfcClientFiles.cachedRom(temp,sha));
        assertThrows(java.io.IOException.class,()->SfcClientFiles.cacheRom(temp,"0".repeat(64),data));
        assertThrows(java.io.IOException.class,()->SfcClientFiles.cachedRom(temp,"../escape"));
    }
    @Test void corruptCacheNeverExecutes()throws Exception{
        byte[] data=rom();String sha=SfcClientFiles.hash(data);SfcClientFiles.cacheRom(temp,sha,data);
        Path p=temp.resolve("game-console/piq-sfc-home/cache/roms/"+sha+".sfc");Files.write(p,new byte[32768]);
        assertThrows(java.io.IOException.class,()->SfcClientFiles.cachedRom(temp,sha));
    }
    @Test void copierHeaderIsNormalizedBeforeUpload()throws Exception{
        byte[] data=rom(),withHeader=new byte[33280];System.arraycopy(data,0,withHeader,512,data.length);
        Path p=temp.resolve("owned.smc");Files.write(p,withHeader);
        assertArrayEquals(data,SfcClientFiles.importRom(p));
    }
    @Test void localImportNeedsCorrectExtensionAndBoundedFile()throws Exception{
        Path txt=temp.resolve("wrong.txt");Files.write(txt,rom());assertThrows(java.io.IOException.class,()->SfcClientFiles.importRom(txt));
        Path empty=temp.resolve("empty.sfc");Files.write(empty,new byte[0]);assertThrows(java.io.IOException.class,()->SfcClientFiles.importRom(empty));
        assertThrows(java.io.IOException.class,()->SfcClientFiles.readBounded(txt,32));
    }
    @Test void backupsAreClearlyNotAutoResumedServerSaves()throws Exception{
        String sha=SfcClientFiles.hash(rom());
        Path backup=SfcClientFiles.snapshot(temp,sha,java.util.UUID.randomUUID(),new byte[]{1,2},new byte[]{3},99);
        try(var zip=new java.util.zip.ZipFile(backup.toFile())){
            assertArrayEquals(new byte[]{1,2},zip.getInputStream(zip.getEntry("state.bin")).readAllBytes());
            assertTrue(new String(zip.getInputStream(zip.getEntry("metadata.txt")).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).contains("not auto-loaded or a server save"));
        }
    }
    @Test void nonDirectoryCacheComponentFailsClosed()throws Exception{
        Files.writeString(temp.resolve("piq-sfc-home"),"occupied");byte[]data=rom();
        assertThrows(java.io.IOException.class,()->SfcClientFiles.cacheRom(temp,SfcClientFiles.hash(data),data));
    }
    @Test void simultaneousControlAndObserverCachePublicationKeepsVerifiedBytes()throws Exception{
        byte[] data=rom();String sha=SfcClientFiles.hash(data);
        try(var workers=java.util.concurrent.Executors.newFixedThreadPool(4)){
            for(int iteration=0;iteration<8;iteration++){
                Path game=temp.resolve("parallel-"+iteration);var barrier=new java.util.concurrent.CyclicBarrier(4);
                var writes=new java.util.ArrayList<java.util.concurrent.Future<?>>();
                for(int i=0;i<4;i++)writes.add(workers.submit(()->{barrier.await();SfcClientFiles.cacheRom(game,sha,data);return null;}));
                for(var write:writes)write.get(10,java.util.concurrent.TimeUnit.SECONDS);
                assertArrayEquals(data,SfcClientFiles.cachedRom(game,sha));
                try(var files=Files.list(game.resolve("game-console/piq-sfc-home/cache/roms"))){assertEquals(1,files.count());}
            }
        }
    }
}
