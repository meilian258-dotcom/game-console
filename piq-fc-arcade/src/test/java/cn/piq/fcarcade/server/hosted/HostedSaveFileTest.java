package cn.piq.fcarcade.server.hosted;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.IOException;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class HostedSaveFileTest {
    @TempDir Path root;
    @Test void roundtripPreviousAndNoChangeDoNotRewrite()throws Exception{
        try(var store=new HostedSaveFile(root,"a".repeat(64),"fixture",1024)){
            assertArrayEquals(new byte[0],store.load());store.save(new byte[]{1,2,3});assertArrayEquals(new byte[]{1,2,3},store.load());
            Path file;try(var files=Files.walk(root)){file=files.filter(p->p.getFileName().toString().equals("state.bin")).findFirst().orElseThrow();}
            var stamp=Files.getLastModifiedTime(file);store.save(new byte[]{1,2,3});assertEquals(stamp,Files.getLastModifiedTime(file));
            store.save(new byte[]{4,5});assertArrayEquals(new byte[]{4,5},store.load());assertTrue(Files.isRegularFile(file.resolveSibling("state.previous.bin")));
            assertThrows(IOException.class,()->store.save(new byte[1025]));
        }
    }
    @Test void conflictingWriterRejectedAndLeaseReleasedAfterClose()throws Exception{
        try(var first=new HostedSaveFile(root,"b".repeat(64),"fixture",1024)){assertThrows(IOException.class,()->new HostedSaveFile(root,"b".repeat(64),"fixture",1024));}
        try(var second=new HostedSaveFile(root,"b".repeat(64),"fixture",1024)){assertArrayEquals(new byte[0],second.load());}
    }
    @Test void corruptedSaveIsPreservedAndNeverSilentlyReplaced()throws Exception{
        try(var store=new HostedSaveFile(root,"c".repeat(64),"fixture",1024)){
            store.save(new byte[]{1});Path file;try(var files=Files.walk(root)){file=files.filter(p->p.getFileName().toString().equals("state.bin")).findFirst().orElseThrow();}
            byte[] corrupt=Files.readAllBytes(file);corrupt[40]=2;Files.write(file,corrupt);
            assertThrows(IOException.class,store::load);assertThrows(IOException.class,()->store.save(new byte[]{3}));assertArrayEquals(corrupt,Files.readAllBytes(file));
        }
    }
    @Test void compatibilityRomOwnerAndStableDeviceAreIsolated()throws Exception{
        UUID owner=UUID.randomUUID(),device=UUID.randomUUID();var context=new ServerCoreContext(root,root.resolve("saves"),owner,device);
        Path saved=context.saveDirectory("nes");assertTrue(saved.endsWith(Path.of("server-hosted-v1","nes",owner.toString(),device.toString())));
        assertNotEquals(saved,new ServerCoreContext(root,root.resolve("saves"),UUID.randomUUID(),device).saveDirectory("nes"));
        assertThrows(IllegalArgumentException.class,()->context.saveDirectory("../old-saves"));
        try(var first=new HostedSaveFile(saved,"d".repeat(64),"legacy",1024);var second=new HostedSaveFile(saved,"d".repeat(64),"zapper",1024)){
            first.save(new byte[]{7});assertArrayEquals(new byte[0],second.load());
        }
    }
    @Test void boundedReadRejectsOversizeAndDirectory()throws Exception{
        Path file=root.resolve("file.rom");Files.write(file,new byte[17]);assertEquals(17,ServerCoreFiles.read(file,16,32).length);
        assertThrows(IOException.class,()->ServerCoreFiles.read(file,1,16));assertThrows(IOException.class,()->ServerCoreFiles.read(root,1,32));
    }
}
