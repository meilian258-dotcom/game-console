package cn.piq.nativearcade.bridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class NativeRomStagingTest {
    @TempDir Path temp;
    private Path write(Path root,String name,int size)throws Exception{byte[] b=new byte[size];Arrays.fill(b,(byte)name.hashCode());return Files.write(root.resolve(name),b);}
    @Test void onlyDeclaredBiosNamesAreCopiedAsOpaqueBytes()throws Exception{
        Path source=Files.createDirectory(temp.resolve("roms")),owned=Files.createDirectory(temp.resolve("owned"));
        Path rom=write(source,"kof97.zip",30),neo=write(source,"neogeo.zip",40),qs=write(source,"qsound_hle.zip",50);
        Path pgm=write(source,"pgm.zip",45),qsOld=write(source,"qsound.zip",55);
        write(source,"secret.zip",60);write(source,"dino.zip",60);Files.createDirectory(source.resolve("nested"));
        assertEquals(owned.resolve("kof97.zip"),NativeRomStaging.stage(rom,"kof97",owned));
        try(var files=Files.list(owned)){assertEquals(5,files.count());}
        assertArrayEquals(Files.readAllBytes(rom),Files.readAllBytes(owned.resolve("kof97.zip")));
        assertArrayEquals(Files.readAllBytes(neo),Files.readAllBytes(owned.resolve("neogeo.zip")));
        assertArrayEquals(Files.readAllBytes(qs),Files.readAllBytes(owned.resolve("qsound_hle.zip")));
        assertArrayEquals(Files.readAllBytes(pgm),Files.readAllBytes(owned.resolve("pgm.zip")));
        assertArrayEquals(Files.readAllBytes(qsOld),Files.readAllBytes(owned.resolve("qsound.zip")));
        assertTrue(Files.exists(source.resolve("secret.zip")));
    }
    @Test void missingAuxiliaryFilesAreOptional()throws Exception{
        Path source=Files.createDirectory(temp.resolve("roms")),owned=Files.createDirectory(temp.resolve("owned"));
        NativeRomStaging.stage(write(source,"dino.zip",22),"dino",owned);
        try(var files=Files.list(owned)){assertEquals(1,files.count());}
    }
    @Test void tooSmallAndDirectoryAuxiliaryAreRejected()throws Exception{
        Path source=Files.createDirectory(temp.resolve("roms"));Path rom=write(source,"dino.zip",22);
        write(source,"neogeo.zip",21);
        assertThrows(IOException.class,()->NativeRomStaging.stage(rom,"dino",Files.createDirectory(temp.resolve("one"))));
        Files.delete(source.resolve("neogeo.zip"));Files.createDirectory(source.resolve("qsound_hle.zip"));
        assertThrows(IOException.class,()->NativeRomStaging.stage(rom,"dino",Files.createDirectory(temp.resolve("two"))));
    }
    @Test void refusesOutputCollisionRatherThanReplacingData()throws Exception{
        Path source=Files.createDirectory(temp.resolve("roms")),owned=Files.createDirectory(temp.resolve("owned"));
        Path rom=write(source,"dino.zip",22);write(owned,"dino.zip",33);
        assertThrows(FileAlreadyExistsException.class,()->NativeRomStaging.stage(rom,"dino",owned));
        assertEquals(33,Files.size(owned.resolve("dino.zip")));
    }
    @Test void driverTraversalAndAbsoluteNamesNeverReachCopy()throws Exception{
        Path rom=write(temp,"dino.zip",22);Path owned=Files.createDirectory(temp.resolve("owned"));
        for(String bad:new String[]{"../dino","C:/dino","dino.zip","UPPER","a/b",""})assertThrows(IOException.class,()->NativeRomStaging.stage(rom,bad,owned));
        try(var files=Files.list(owned)){assertEquals(0,files.count());}
    }
    @Test void perFileAndCombinedLimitsAreCheckedWithoutAllocatingHugeBuffers()throws Exception{
        long size=BridgeProtocol.MAX_ROM;
        assertEquals(size,NativeRomStaging.checkedTotal(0,size));assertEquals(2*size,NativeRomStaging.checkedTotal(size,size));
        assertThrows(IOException.class,()->NativeRomStaging.checkedTotal(0,size+1));
        assertThrows(IOException.class,()->NativeRomStaging.checkedTotal(0,21));
        assertThrows(IOException.class,()->NativeRomStaging.checkedTotal(2*size,22));
        assertThrows(IOException.class,()->NativeRomStaging.checkedTotal(Long.MAX_VALUE,22));
    }
}
