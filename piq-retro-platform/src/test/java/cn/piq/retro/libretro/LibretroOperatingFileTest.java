package cn.piq.retro.libretro;

import java.nio.file.*;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class LibretroOperatingFileTest {
    @TempDir Path root;
    @Test void stagesCloneAndExtractsOnlyAfterExplicitClosed()throws Exception {
        var captured=new AtomicReference<byte[]>();byte[] initial={1,2,3};
        var file=new LibretroOperatingFile("fbneo/kov.fs",100,initial,captured::set);initial[0]=9;
        file.prepare(root);assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(root.resolve("fbneo/kov.fs")));assertNull(captured.get());
        Files.write(root.resolve("fbneo/kov.fs"),new byte[]{4,5,6});file.closed(root);assertArrayEquals(new byte[]{4,5,6},captured.get());
    }
    @Test void missingNativeFileDoesNotErasePreviousCopy()throws Exception {
        var captured=new AtomicReference<byte[]>();var file=new LibretroOperatingFile("fbneo/kov.fs",100,null,captured::set);
        file.prepare(root);file.closed(root);assertNull(captured.get());
    }
    @Test void rejectsEmptyOversizeAndUnexpectedExistingFile()throws Exception {
        var file=new LibretroOperatingFile("fbneo/kov.fs",3,null,b->{fail("must not commit");});file.prepare(root);
        Files.write(root.resolve("fbneo/kov.fs"),new byte[0]);assertThrows(IOException.class,()->file.closed(root));
        Files.write(root.resolve("fbneo/kov.fs"),new byte[4]);assertThrows(IOException.class,()->file.closed(root));
        assertThrows(IOException.class,()->file.prepare(root));
    }
    @Test void arbitraryPathsAndWrongBudgetsNeverReachFilesystem(){
        for(String path:new String[]{"../kov.fs","fbneo/../kov.fs","fbneo/kov.dll","fbneo/CON.fs","C:/kov.fs","fbneo/kov.fs/more","fbneo/kov\\x.fs"})
            assertThrows(IllegalArgumentException.class,()->new LibretroOperatingFile(path,16,null,b->{}));
        assertThrows(IllegalArgumentException.class,()->new LibretroOperatingFile("fbneo/kov.fs",0,null,b->{}));
        assertThrows(IllegalArgumentException.class,()->new LibretroOperatingFile("fbneo/kov.fs",1024*1024+1,null,b->{}));
        assertThrows(IllegalArgumentException.class,()->new LibretroOperatingFile("fbneo/kov.fs",2,new byte[3],b->{}));
    }
    @Test void directoryInPlaceOfFileRejected()throws Exception {
        var file=new LibretroOperatingFile("fbneo/kov.fs",16,null,b->fail());file.prepare(root);
        Files.createDirectory(root.resolve("fbneo/kov.fs"));assertThrows(IOException.class,()->file.closed(root));
    }
    @Test void sinkFailureIsNotSilentlySuccessful()throws Exception {
        var file=new LibretroOperatingFile("fbneo/kov.fs",16,new byte[]{1},b->{throw new IOException("disk unavailable");});file.prepare(root);
        assertEquals("disk unavailable",assertThrows(IOException.class,()->file.closed(root)).getMessage());
    }
}
