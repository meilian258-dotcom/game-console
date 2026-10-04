package cn.piq.retro.libretro;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class LibretroContentFilesTest {
    @TempDir Path temp;
    private static final LibretroContentFiles.Check OK=()->{};
    private Path file(String name,int bytes)throws IOException {
        Path p=temp.resolve(name);try(var f=new RandomAccessFile(p.toFile(),"rw")){f.setLength(bytes);}return p;
    }
    private LibretroContentFiles inspect(Map<String,Path> sources)throws IOException {
        return LibretroContentFiles.inspect("game.zip",sources,LibretroContentFiles.MAX_MAIN,16*1024*1024,OK);
    }
    @Test void allFiveNamedFilesStageWithoutByteArrayPayloadAndKeepPreviousIdentityFormat()throws Exception {
        var paths=new TreeMap<String,Path>();
        for(String name:List.of("game.zip","neogeo.zip","pgm.zip","qsound.zip","qsound_hle.zip")){
            Path p=file(name,32);Files.writeString(p,name);paths.put(name,p);
        }
        var bundle=inspect(paths);paths.clear();assertEquals(5,bundle.files().size());assertEquals(4,bundle.auxiliaryHashes().size());
        assertThrows(UnsupportedOperationException.class,()->bundle.files().clear());
        Path destination=Files.createDirectory(temp.resolve("stage"));byte[] actual=bundle.stage(destination,OK);
        var expected=new ByteArrayOutputStream();try(var data=new DataOutputStream(expected)){
            for(var e:bundle.files().entrySet()){
                data.writeUTF(e.getKey());data.write(HexFormat.of().parseHex(e.getValue().sha256()));
                assertEquals(-1,Files.mismatch(e.getValue().source(),destination.resolve(e.getKey())));
                assertTrue(Files.exists(e.getValue().source()),"Never consumes the user source");
            }
        }
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(expected.toByteArray()),actual);
    }
    @Test void seventyNineMiBMainIsAcceptedAndStagedWithSmallFixedBuffers()throws Exception {
        Path source=file("large",83_164_356);var bundle=inspect(Map.of("game.zip",source));
        assertEquals(83_164_356,bundle.main().size());assertEquals(83_164_356,bundle.size());
        Path target=Files.createDirectory(temp.resolve("large-stage"));assertEquals(32,bundle.stage(target,OK).length);
        assertEquals(-1,Files.mismatch(source,target.resolve("game.zip")));
    }
    @Test void roleAndAggregateLimitsRemainBounded()throws Exception {
        assertThrows(IOException.class,()->inspect(Map.of("game.zip",file("over-main",LibretroContentFiles.MAX_MAIN+1))));
        assertThrows(IOException.class,()->inspect(Map.of("game.zip",file("small",22),"neogeo.zip",file("over-bios",16*1024*1024+1))));
        var large=new LinkedHashMap<String,Path>();large.put("game.zip",file("main",96*1024*1024));
        large.put("pgm.zip",file("bios1",16*1024*1024));large.put("neogeo.zip",file("bios2",16*1024*1024));
        assertEquals(LibretroContentFiles.MAX_TOTAL,inspect(large).size());
        large.put("qsound.zip",file("bios3",1));assertThrows(IOException.class,()->inspect(large));
        var six=new HashMap<String,Path>();for(String n:List.of("game.zip","a.zip","b.zip","c.zip","d.zip","e.zip"))six.put(n,temp.resolve("small"));
        assertThrows(IllegalArgumentException.class,()->inspect(six));
    }
    @Test void selectionChangesAreRejectedEvenWhenLengthAndTimestampArePreserved()throws Exception {
        Path p=file("original",32);var bundle=inspect(Map.of("game.zip",p));var timestamp=Files.getLastModifiedTime(p);
        byte[] changed=new byte[32];changed[0]=7;Files.write(p,changed);Files.setLastModifiedTime(p,timestamp);
        assertThrows(IOException.class,()->bundle.stage(Files.createDirectory(temp.resolve("changed")),OK));
        assertArrayEquals(changed,Files.readAllBytes(p));
    }
    @Test void cancellationAndExistingTargetFailClosedWithoutTouchingSources()throws Exception {
        Path p=file("source",200_000);AtomicInteger reads=new AtomicInteger();
        assertThrows(IOException.class,()->LibretroContentFiles.inspect("game.zip",Map.of("game.zip",p),LibretroContentFiles.MAX_MAIN,16*1024*1024,
                ()->{if(reads.incrementAndGet()>2)throw new IOException("cancelled");}));
        var bundle=inspect(Map.of("game.zip",p));Path target=Files.createDirectory(temp.resolve("cancelled"));reads.set(0);
        assertThrows(IOException.class,()->bundle.stage(target,()->{if(reads.incrementAndGet()>2)throw new IOException("cancelled");}));
        assertEquals(200_000,Files.size(p));Path existing=Files.createDirectory(temp.resolve("existing"));Files.writeString(existing.resolve("game.zip"),"keep");
        assertThrows(IOException.class,()->bundle.stage(existing,OK));assertEquals("keep",Files.readString(existing.resolve("game.zip")));
    }
    @Test void unsafeNamesDirectoriesAndMissingFilesAreRejected()throws Exception {
        Path p=file("source",22);
        for(String name:List.of("../game.zip","CON.zip","a/b.zip","a\\b.zip"))
            assertThrows(IllegalArgumentException.class,()->LibretroContentFiles.inspect(name,Map.of(name,p),1024,1024,OK));
        assertThrows(IOException.class,()->inspect(Map.of("game.zip",temp)));
        assertThrows(IOException.class,()->inspect(Map.of("game.zip",temp.resolve("missing"))));
        assertThrows(IOException.class,()->inspect(Map.of("game.zip",p,"GAME.ZIP",p)));
    }
    @Test void symlinksAtSourceOrParentAreRejectedWhenSupported()throws Exception {
        Path p=file("source",22),link=temp.resolve("link");
        try{Files.createSymbolicLink(link,p);}catch(IOException|UnsupportedOperationException e){org.junit.jupiter.api.Assumptions.abort("Symlinks unavailable");}
        assertThrows(IOException.class,()->inspect(Map.of("game.zip",link)));
        Path parent=temp.resolve("parent-link");Files.createSymbolicLink(parent,temp);
        assertThrows(IOException.class,()->inspect(Map.of("game.zip",parent.resolve("source"))));
    }
    @Test void windowsJunctionParentsAreRejectedForBothSourceAndStaging()throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        Path source=file("source",22),link=temp.resolve("junction");
        var builder=new ProcessBuilder("powershell.exe","-NoProfile","-Command",
                "New-Item -ItemType Junction -Path $env:PIQ_QA_LINK -Target $env:PIQ_QA_TARGET -ErrorAction Stop | Out-Null");
        builder.environment().put("PIQ_QA_LINK",link.toString());builder.environment().put("PIQ_QA_TARGET",temp.toString());
        var process=builder.redirectErrorStream(true).start();String result=new String(process.getInputStream().readAllBytes());
        assertEquals(0,process.waitFor(),result);
        try{
            assertThrows(IOException.class,()->inspect(Map.of("game.zip",link.resolve("source"))));
            var bundle=inspect(Map.of("game.zip",source));assertThrows(IOException.class,()->bundle.stage(link,OK));
            assertEquals(22,Files.size(source));
        }finally{Files.delete(link);}
    }
}
