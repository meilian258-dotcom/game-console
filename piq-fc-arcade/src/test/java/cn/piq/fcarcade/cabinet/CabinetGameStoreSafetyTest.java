package cn.piq.fcarcade.cabinet;

import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CabinetGameStoreSafetyTest {
    @TempDir Path temp;
    private CabinetGameManifest.Entry entry(byte[] data)throws Exception{return new CabinetGameManifest.Entry("test.gba",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)),data.length);}
    @Test void sourceValidationNeverCreatesMissingParent() {
        Path source=temp.resolve("missing/source/game.gba");
        assertThrows(IOException.class,()->CabinetGameStore.regular(source));assertFalse(Files.exists(temp.resolve("missing")));
        assertThrows(IOException.class,()->CabinetGameStore.requireDirectory(source.getParent()));assertFalse(Files.exists(temp.resolve("missing")));
    }
    @Test void successfulUploadCommitAndBoundedDownloadRoundTrip()throws Exception {
        byte[] bytes=new byte[CabinetGameManifest.CHUNK+37];new Random(8).nextBytes(bytes);var e=entry(bytes);var store=new CabinetGameStore(temp.resolve("objects"));
        Path p=store.temporary(UUID.randomUUID(),0);store.append(p,0,Arrays.copyOf(bytes,CabinetGameManifest.CHUNK));
        store.append(p,CabinetGameManifest.CHUNK,Arrays.copyOfRange(bytes,CabinetGameManifest.CHUNK,bytes.length));store.commit(p,e);
        assertFalse(Files.exists(p));assertTrue(store.contains(e));assertArrayEquals(Arrays.copyOf(bytes,CabinetGameManifest.CHUNK),store.chunk(e,0));
        assertArrayEquals(Arrays.copyOfRange(bytes,CabinetGameManifest.CHUNK,bytes.length),store.chunk(e,CabinetGameManifest.CHUNK));
    }
    @Test void appendRejectsRepeatGapsEmptyAndOversize()throws Exception {
        var store=new CabinetGameStore(temp);Path p=store.temporary(UUID.randomUUID(),0);store.append(p,0,new byte[]{1,2});
        assertThrows(IOException.class,()->store.append(p,0,new byte[]{1}));assertThrows(IOException.class,()->store.append(p,1,new byte[]{1}));
        assertThrows(IOException.class,()->store.append(p,3,new byte[]{1}));assertThrows(IOException.class,()->store.append(p,2,new byte[0]));
        assertThrows(IOException.class,()->store.append(p,2,new byte[CabinetGameManifest.CHUNK+1]));assertEquals(2,Files.size(p));
    }
    @Test void foreignStagingCannotBeCommittedDeletedOrWritten()throws Exception {
        var store=new CabinetGameStore(temp.resolve("owned"));byte[] bytes={1,2,3};var e=entry(bytes);
        Path outside=temp.resolve("upload-"+UUID.randomUUID()+"-0.part");Files.write(outside,bytes);
        assertThrows(IOException.class,()->store.commit(outside,e));assertThrows(IOException.class,()->store.append(outside,3,new byte[]{4}));
        store.discard(outside);assertArrayEquals(bytes,Files.readAllBytes(outside));assertFalse(Files.exists(temp.resolve("owned")));
    }
    @Test void wrongHashCannotBecomeContentObject()throws Exception {
        var store=new CabinetGameStore(temp);Path p=store.temporary(UUID.randomUUID(),1);store.append(p,0,new byte[]{9,9});
        assertThrows(IOException.class,()->store.commit(p,entry(new byte[]{1,2})));assertTrue(Files.exists(p));
        store.discard(p);assertFalse(Files.exists(p));
    }
    @Test void existingObjectIsVerifiedAndNeverOverwritten()throws Exception {
        var store=new CabinetGameStore(temp);byte[] data={2,3};var e=entry(data);Path a=store.temporary(UUID.randomUUID(),0);store.append(a,0,data);store.commit(a,e);
        Path object=temp.resolve(e.sha256()+".data");Files.write(object,new byte[]{9,9});
        Path b=store.temporary(UUID.randomUUID(),0);store.append(b,0,data);assertThrows(IOException.class,()->store.commit(b,e));
        assertArrayEquals(new byte[]{9,9},Files.readAllBytes(object));assertTrue(Files.exists(b));
    }
    @Test void stagingIdentityAndQuotaArgumentsBounded() {
        var store=new CabinetGameStore(temp);
        assertThrows(IOException.class,()->store.temporary(null,0));assertThrows(IOException.class,()->store.temporary(UUID.randomUUID(),CabinetGameManifest.MAX_FILES));
        assertThrows(IOException.class,()->store.checkQuota(-1));assertThrows(IOException.class,()->store.checkQuota(CabinetGameStore.QUOTA+1));
    }
    @Test void nonRegularParentsAndDirectoryObjectsRejected()throws Exception {
        Path parent=temp.resolve("file-parent");Files.write(parent,new byte[]{1});
        assertThrows(IOException.class,()->CabinetGameStore.requireDirectory(parent));assertThrows(IOException.class,()->CabinetGameStore.regular(temp));
        var store=new CabinetGameStore(temp.resolve("objects"));Files.createDirectories(temp.resolve("objects/unexpected-directory"));
        assertThrows(IOException.class,()->store.checkQuota(0));
    }
    @Test void symlinkAncestorAndLeafAreRejectedIfSupported()throws Exception {
        Path real=Files.createDirectory(temp.resolve("real"));Files.write(real.resolve("file.gba"),new byte[]{1});Path link=temp.resolve("link");
        boolean symlink=true;
        try{Files.createSymbolicLink(link,real);}catch(UnsupportedOperationException|IOException e){
            symlink=false;
            if(!System.getProperty("os.name").startsWith("Windows"))org.junit.jupiter.api.Assumptions.abort("OS does not permit test links: "+e);
            // A real directory junction requires no symlink privilege on this Windows runner.
            // Both targets are freshly-created TempDir children; no shell deletion or user path.
            var process=new ProcessBuilder("cmd.exe","/d","/c","mklink","/J",link.toString(),real.toString()).redirectErrorStream(true).start();
            if(!process.waitFor(10,java.util.concurrent.TimeUnit.SECONDS)){process.destroyForcibly();fail("Test junction creation timed out");}
            assertEquals(0,process.exitValue(),new String(process.getInputStream().readAllBytes()));
        }
        try{
            assertThrows(IOException.class,()->CabinetGameStore.regular(link.resolve("file.gba")));
            assertThrows(IOException.class,()->CabinetGameStore.requireDirectory(link));
            if(symlink){Path leaf=temp.resolve("leaf.gba");Files.createSymbolicLink(leaf,real.resolve("file.gba"));assertThrows(IOException.class,()->CabinetGameStore.regular(leaf));}
        }finally{Files.deleteIfExists(link);}
        assertArrayEquals(new byte[]{1},Files.readAllBytes(real.resolve("file.gba")));
    }
}
