package cn.piq.nativearcade.bridge;

import cn.piq.nativearcade.NativeSnapshotProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class NativeSnapshotWorkspaceTest {
    @TempDir Path temp;
    @Test void boundedReaderVerifiesExactSizeAndSha()throws Exception{
        Path source=temp.resolve("opaque.bin");byte[] value=new byte[]{2,8,4};Files.write(source,value);var before=Files.getLastModifiedTime(source);
        assertEquals(3,NativeSnapshotWorkspace.verify(source,NativeSnapshotProfile.hash(value),3,()->false).size());
        assertEquals(before,Files.getLastModifiedTime(source));assertArrayEquals(value,Files.readAllBytes(source));
    }
    @Test void wrongSizeOrHashRejectsWithoutWriting()throws Exception{
        Path source=temp.resolve("opaque.bin");Files.write(source,new byte[]{1,2,3});
        assertThrows(IOException.class,()->NativeSnapshotWorkspace.verify(source,"0".repeat(64),3,()->false));
        assertThrows(IOException.class,()->NativeSnapshotWorkspace.verify(source,"0".repeat(64),2,()->false));assertEquals(3,Files.size(source));
    }
    @Test void cancellationRejectsBeforeReadingOrCreatingAnything()throws Exception{
        Path absent=temp.resolve("missing/opaque.bin");assertThrows(IOException.class,()->NativeSnapshotWorkspace.verify(absent,"0".repeat(64),3,()->true));assertFalse(Files.exists(absent.getParent()));
    }
    @Test void directoryIsNotAcceptedAsRuntimeFile()throws Exception{assertThrows(IOException.class,()->NativeSnapshotWorkspace.verify(temp,"0".repeat(64),0,()->false));}
    @Test void unknownRomRejectedWithoutAllocatingNativeSlot()throws Exception{
        assertThrows(IOException.class,()->new NativeSnapshotSession(temp,temp.resolve("unknown.zip"),ignored->fail("No native reservation for unknown games")));
        assertFalse(NativeProcessSession.hasLiveSession());
    }
    @Test void nullPathRejectedBeforeSlotAcquisition(){
        assertThrows(NullPointerException.class,()->new NativeSnapshotSession(null,temp.resolve("kof97.zip"),ignored->{}));assertFalse(NativeProcessSession.hasLiveSession());
    }
    @Test void cancelledOpeningReleasesTheSharedSlotWithoutLaunching()throws Exception{
        assertThrows(IOException.class,()->new NativeSnapshotSession(temp,temp.resolve("kof97.zip"),Runnable::run));
        long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        while(NativeProcessSession.hasLiveSession()&&System.nanoTime()<until)Thread.sleep(10);
        assertFalse(NativeProcessSession.hasLiveSession());
        NativeProcessSession.acquireStepSlot();NativeProcessSession.releaseStepSlot(null);
    }
    @Test void nativeMediaAndSnapshotReservationCannotOverlap()throws Exception{
        NativeProcessSession.acquireStepSlot();try{assertThrows(IOException.class,()->new NativeSnapshotSession(temp,temp.resolve("kof97.zip"),ignored->fail("Must not steal active media slot")));assertTrue(NativeProcessSession.hasLiveSession());}
        finally{NativeProcessSession.releaseStepSlot(null);}assertFalse(NativeProcessSession.hasLiveSession());
    }
}
